import { DomainError } from "./errors.mjs";

export const DEFAULT_SETTINGS = Object.freeze({
  label: "Practice timer",
  kind: "one-shot",
  enabled: true,
  startDate: "",
  startTime: "",
  durationMinutes: 25,
  windowMinutes: 24 * 60,
  intervalMinutes: 60,
  endpointPolicy: "exclusive",
  missedOccurrencePolicy: "fire-once",
  repeatDays: [],
  pauseDates: [],
  snoozeSequenceMinutes: [25, 15, 10, 5],
  afterSnoozeExhausted: "dismiss",
  sound: "classic",
  volume: 0.8,
  gradualVolume: false,
  vibration: true,
  vibrationPattern: "standard"
});

export function localDateParts(timestamp = Date.now()) {
  const date = new Date(timestamp);
  return {
    startDate: date.toISOString().slice(0, 10),
    startTime: `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`
  };
}

export function defaultSettings(now = Date.now()) {
  const parts = localDateParts(now + 60_000);
  return { ...DEFAULT_SETTINGS, ...parts };
}

export function parseSnoozeSequence(value) {
  const values = Array.isArray(value) ? value : String(value).split(",");
  const result = values
    .map((item) => Number(String(item).trim()))
    .filter((item) => Number.isFinite(item) && item > 0);
  if (!result.length) throw new DomainError("snooze sequence needs at least one positive minute value");
  return result;
}

export function toTimestamp(date, time) {
  const value = new Date(`${date}T${time}`);
  if (!date || !time || Number.isNaN(value.getTime())) throw new DomainError("a valid local date and time are required");
  return value.getTime();
}

export function validateSettings(input) {
  const settings = { ...DEFAULT_SETTINGS, ...input };
  if (typeof settings.label !== "string" || !settings.label.trim()) throw new DomainError("label is required");
  if (!Number.isFinite(settings.durationMinutes) || settings.durationMinutes <= 0) throw new DomainError("duration must be positive");
  if (!Number.isFinite(settings.windowMinutes) || settings.windowMinutes <= 0) throw new DomainError("window duration must be positive");
  if (!Number.isFinite(settings.intervalMinutes) || settings.intervalMinutes <= 0) throw new DomainError("interval must be positive");
  if (!Array.isArray(settings.repeatDays)) throw new DomainError("repeatDays must be an array");
  if (!Array.isArray(settings.pauseDates)) throw new DomainError("pauseDates must be an array");
  if (!Number.isFinite(settings.volume) || settings.volume < 0 || settings.volume > 1) throw new DomainError("volume must be between 0 and 1");
  settings.snoozeSequenceMinutes = parseSnoozeSequence(settings.snoozeSequenceMinutes);
  return settings;
}

export function settingsToSchedule(settings, id = "browser-alarm") {
  const normalized = validateSettings(settings);
  const startAtMs = toTimestamp(normalized.startDate, normalized.startTime);
  const snoozePolicy = {
    sequenceMs: normalized.snoozeSequenceMinutes.map((minutes) => minutes * 60_000),
    afterExhausted: normalized.afterSnoozeExhausted
  };

  if (normalized.kind === "one-shot") {
    return {
      id,
      kind: "one-shot",
      startAtMs,
      durationMs: normalized.durationMinutes * 60_000,
      snoozePolicy
    };
  }

  return {
    id,
    kind: "interval-window",
    startAtMs,
    endAtMs: startAtMs + normalized.windowMinutes * 60_000,
    intervalMs: normalized.intervalMinutes * 60_000,
    endpointPolicy: normalized.endpointPolicy,
    missedOccurrencePolicy: normalized.missedOccurrencePolicy,
    snoozePolicy
  };
}
