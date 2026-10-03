import test from "node:test";
import assert from "node:assert/strict";
import { advanceAt, dismiss, snooze, startSchedule } from "../src/core/engine.mjs";
import { DomainError } from "../src/core/errors.mjs";

const minute = 60_000;
const hour = 60 * minute;

function oneShot(overrides = {}) {
  return {
    id: "one-shot-1",
    kind: "one-shot",
    startAtMs: 1_000,
    durationMs: 10 * minute,
    snoozePolicy: {
      sequenceMs: [25 * minute, 15 * minute, 10 * minute, 5 * minute],
      afterExhausted: "dismiss"
    },
    ...overrides
  };
}

function hourlyDay(overrides = {}) {
  return {
    id: "hourly-day-1",
    kind: "interval-window",
    startAtMs: 0,
    endAtMs: 24 * hour,
    intervalMs: hour,
    endpointPolicy: "exclusive",
    missedOccurrencePolicy: "fire-once",
    snoozePolicy: {
      sequenceMs: [25 * minute, 15 * minute, 10 * minute, 5 * minute],
      afterExhausted: "dismiss"
    },
    ...overrides
  };
}

test("rejects schedules that omit explicit recurrence semantics", () => {
  assert.throws(() => startSchedule({
    id: "missing-policy",
    kind: "interval-window",
    startAtMs: 0,
    endAtMs: hour,
    intervalMs: hour,
    snoozePolicy: { sequenceMs: [], afterExhausted: "dismiss" }
  }), DomainError);
});

test("fires a one-shot after its declared duration", () => {
  const runtime = startSchedule(oneShot());
  const fireAt = 1_000 + 10 * minute;
  const before = advanceAt(runtime, fireAt - 1);
  assert.deepEqual(before.events, []);

  const due = advanceAt(runtime, fireAt);
  assert.equal(due.events[0].type, "ALARM_FIRED");
  assert.equal(due.state.phase, "ringing");
  assert.equal(due.state.ringingOccurrenceAtMs, fireAt);
});

test("applies the ordered snooze sequence exactly", () => {
  let runtime = startSchedule(oneShot());
  const fireAt = 1_000 + 10 * minute;
  runtime = advanceAt(runtime, fireAt);
  runtime = snooze(runtime, fireAt);
  assert.equal(runtime.events[0].durationMs, 25 * minute);
  runtime = advanceAt(runtime, fireAt + 25 * minute);
  runtime = snooze(runtime, fireAt + 25 * minute);
  assert.equal(runtime.events[0].durationMs, 15 * minute);
  runtime = advanceAt(runtime, fireAt + 40 * minute);
  runtime = snooze(runtime, fireAt + 40 * minute);
  assert.equal(runtime.events[0].durationMs, 10 * minute);
  runtime = advanceAt(runtime, fireAt + 50 * minute);
  runtime = snooze(runtime, fireAt + 50 * minute);
  assert.equal(runtime.events[0].durationMs, 5 * minute);
});

test("continues an interval-window schedule after dismissal", () => {
  let runtime = startSchedule(hourlyDay());
  runtime = advanceAt(runtime, hour);
  runtime = dismiss(runtime, hour);
  assert.equal(runtime.state.nextOccurrenceAtMs, 2 * hour);
  assert.equal(runtime.state.phase, "scheduled");

  runtime = advanceAt(runtime, 2 * hour);
  assert.equal(runtime.state.phase, "ringing");
  assert.equal(runtime.state.ringingOccurrenceAtMs, 2 * hour);
});

test("does not invent an endpoint event when endpointPolicy is exclusive", () => {
  let runtime = startSchedule(hourlyDay());
  for (let index = 1; index < 24; index += 1) {
    runtime = advanceAt(runtime, index * hour);
    runtime = dismiss(runtime, index * hour);
  }
  assert.equal(runtime.state.phase, "completed");
  assert.equal(runtime.state.nextOccurrenceAtMs, null);
});

test("dismissal is not accepted while the schedule is not ringing", () => {
  const runtime = startSchedule(oneShot());
  assert.throws(() => dismiss(runtime, 1_000), DomainError);
});
