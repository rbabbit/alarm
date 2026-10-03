import { DomainError } from "./errors.mjs";

const PHASES = new Set(["scheduled", "ringing", "snoozed", "dismissed", "completed"]);
const ENDPOINT_POLICIES = new Set(["inclusive", "exclusive"]);
const MISSED_POLICIES = new Set(["reject", "skip", "fire-once", "catch-up"]);
const EXHAUSTED_POLICIES = new Set(["dismiss", "repeat-last", "repeat-sequence", "stay-ringing"]);

function assertFinitePositive(value, name) {
  if (!Number.isFinite(value) || value <= 0) {
    throw new DomainError(`${name} must be a finite positive number`);
  }
}

function assertFinite(value, name) {
  if (!Number.isFinite(value)) throw new DomainError(`${name} must be finite`);
}

function clone(value) {
  return structuredClone(value);
}

export function normalizeSchedule(input) {
  const schedule = clone(input);
  if (!schedule || typeof schedule !== "object") throw new DomainError("schedule is required");
  if (!schedule.id || typeof schedule.id !== "string") throw new DomainError("schedule.id is required");
  if (schedule.kind !== "one-shot" && schedule.kind !== "interval-window") {
    throw new DomainError("schedule.kind must be one-shot or interval-window");
  }
  assertFinite(schedule.startAtMs, "schedule.startAtMs");
  if (schedule.kind === "one-shot") {
    assertFinitePositive(schedule.durationMs, "schedule.durationMs");
  } else {
    assertFinitePositive(schedule.endAtMs, "schedule.endAtMs");
    assertFinitePositive(schedule.intervalMs, "schedule.intervalMs");
    if (schedule.endAtMs <= schedule.startAtMs) {
      throw new DomainError("schedule.endAtMs must be after schedule.startAtMs");
    }
    if (!ENDPOINT_POLICIES.has(schedule.endpointPolicy)) {
      throw new DomainError("interval-window requires an explicit endpointPolicy");
    }
    if (!MISSED_POLICIES.has(schedule.missedOccurrencePolicy)) {
      throw new DomainError("interval-window requires an explicit missedOccurrencePolicy");
    }
  }
  const snooze = schedule.snoozePolicy;
  if (!snooze || !Array.isArray(snooze.sequenceMs)) {
    throw new DomainError("snoozePolicy.sequenceMs is required");
  }
  snooze.sequenceMs.forEach((value, index) => assertFinitePositive(value, `snoozePolicy.sequenceMs[${index}]`));
  if (!EXHAUSTED_POLICIES.has(snooze.afterExhausted)) {
    throw new DomainError("snoozePolicy.afterExhausted is required");
  }
  return schedule;
}

export function startSchedule(input, startedAtMs = input.startAtMs) {
  const schedule = normalizeSchedule(input);
  assertFinite(startedAtMs, "startedAtMs");
  return {
    schedule,
    state: {
      scheduleId: schedule.id,
      phase: "scheduled",
      nextOccurrenceAtMs: schedule.kind === "one-shot"
        ? schedule.startAtMs + schedule.durationMs
        : schedule.startAtMs + schedule.intervalMs,
      ringingOccurrenceAtMs: null,
      snoozeIndex: 0,
      occurrenceIndex: 0,
      startedAtMs,
      revision: 0,
      lastEventId: null
    }
  };
}

function event(state, type, atMs, details = {}) {
  return {
    id: `${state.scheduleId}:${state.revision + 1}:${type}`,
    type,
    scheduleId: state.scheduleId,
    atMs,
    ...details
  };
}

function commit(runtime, nextState, emitted) {
  nextState.revision = runtime.state.revision + 1;
  nextState.lastEventId = emitted.id;
  return { schedule: runtime.schedule, state: nextState, events: [emitted] };
}

function nextIntervalOccurrence(schedule, occurrenceAtMs) {
  const next = occurrenceAtMs + schedule.intervalMs;
  const inside = schedule.endpointPolicy === "inclusive" ? next <= schedule.endAtMs : next < schedule.endAtMs;
  return inside ? next : null;
}

export function advanceAt(runtime, nowMs) {
  assertFinite(nowMs, "nowMs");
  const { schedule, state } = runtime;
  if (state.phase !== "scheduled" && state.phase !== "snoozed") return { schedule, state: clone(state), events: [] };
  if (state.nextOccurrenceAtMs > nowMs) return { schedule, state: clone(state), events: [] };

  const nextState = clone(state);
  nextState.phase = "ringing";
  nextState.ringingOccurrenceAtMs = state.nextOccurrenceAtMs;
  nextState.nextOccurrenceAtMs = null;
  if (state.phase === "scheduled") nextState.snoozeIndex = 0;
  return commit(runtime, nextState, event(state, "ALARM_FIRED", nowMs, {
    occurrenceAtMs: state.nextOccurrenceAtMs,
    lateByMs: nowMs - state.nextOccurrenceAtMs
  }));
}

export function dismiss(runtime, atMs) {
  assertFinite(atMs, "atMs");
  const { schedule, state } = runtime;
  if (state.phase !== "ringing") throw new DomainError("only a ringing schedule can be dismissed");
  const nextOccurrence = schedule.kind === "interval-window"
    ? nextIntervalOccurrence(schedule, state.ringingOccurrenceAtMs)
    : null;
  const nextState = clone(state);
  nextState.phase = nextOccurrence === null ? "completed" : "scheduled";
  nextState.nextOccurrenceAtMs = nextOccurrence;
  nextState.ringingOccurrenceAtMs = null;
  nextState.occurrenceIndex += 1;
  nextState.snoozeIndex = 0;
  return commit(runtime, nextState, event(state, "DISMISSED", atMs, { nextOccurrenceAtMs: nextOccurrence }));
}

export function snooze(runtime, atMs) {
  assertFinite(atMs, "atMs");
  const { schedule, state } = runtime;
  if (state.phase !== "ringing") throw new DomainError("only a ringing schedule can be snoozed");
  const sequence = schedule.snoozePolicy.sequenceMs;
  const index = state.snoozeIndex;
  const exhausted = index >= sequence.length;
  const action = exhausted ? schedule.snoozePolicy.afterExhausted : "snooze";

  if (action === "dismiss") return dismiss(runtime, atMs);
  if (action === "stay-ringing") throw new DomainError("snooze sequence exhausted; schedule remains ringing");

  const duration = exhausted && action === "repeat-last"
    ? sequence.at(-1)
    : exhausted && action === "repeat-sequence"
      ? sequence[0]
      : sequence[index];
  const nextState = clone(state);
  nextState.phase = "snoozed";
  nextState.nextOccurrenceAtMs = atMs + duration;
  nextState.ringingOccurrenceAtMs = null;
  nextState.snoozeIndex = action === "repeat-sequence" && exhausted ? 1 : index + 1;
  return commit(runtime, nextState, event(state, "SNOOZED", atMs, {
    durationMs: duration,
    snoozeIndex: index
  }));
}

export function assertValidState(state) {
  if (!state || !PHASES.has(state.phase)) throw new DomainError("invalid state.phase");
  if (!state.scheduleId) throw new DomainError("state.scheduleId is required");
  return true;
}
