import { DomainError } from "./errors.mjs";

export const DAY_CODES = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];
export const JS_DAY_CODES = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"];
export const DAY_LABELS = { SU: "Sun", MO: "Mon", TU: "Tue", WE: "Wed", TH: "Thu", FR: "Fri", SA: "Sat" };

export const DEFAULT_ALARM = Object.freeze({
  name: "New alarm",
  frequency: "several",
  repeatDays: [...DAY_CODES],
  startTime: "06:00",
  endTime: "18:00",
  intervalMinutes: 60,
  alarmType: "auto",
  sound: "classic",
  volume: 1,
  durationSeconds: 60,
  createHistory: true,
  snoozeEnabled: true,
  snoozeSequenceMinutes: [25, 15, 10, 5],
  afterSnoozeExhausted: "dismiss",
  wakeScreen: true,
  enabled: false
});

export function createAlarm(overrides = {}) {
  return {
    id: overrides.id ?? `alarm-${crypto.randomUUID()}`,
    ...DEFAULT_ALARM,
    ...overrides,
    repeatDays: [...(overrides.repeatDays ?? DEFAULT_ALARM.repeatDays)],
    snoozeSequenceMinutes: [...(overrides.snoozeSequenceMinutes ?? DEFAULT_ALARM.snoozeSequenceMinutes)]
  };
}

export function parseTime(value) {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) throw new DomainError(`invalid time: ${value}`);
  const [hours, minutes] = value.split(":").map(Number);
  return hours * 60 + minutes;
}

export function formatTime12(value) {
  const minutes = typeof value === "number" ? value : parseTime(value);
  const normalized = ((minutes % 1440) + 1440) % 1440;
  const hours24 = Math.floor(normalized / 60);
  const hours12 = hours24 % 12 || 12;
  const suffix = hours24 >= 12 ? "PM" : "AM";
  return `${hours12}:${String(normalized % 60).padStart(2, "0")} ${suffix}`;
}

export function formatTime24(value) {
  const minutes = typeof value === "number" ? value : parseTime(value);
  const normalized = ((minutes % 1440) + 1440) % 1440;
  return `${String(Math.floor(normalized / 60)).padStart(2, "0")}:${String(normalized % 60).padStart(2, "0")}`;
}

export function formatTimeRange(alarm) {
  return alarm.frequency === "once"
    ? formatTime12(alarm.startTime)
    : `${formatTime12(alarm.startTime)} ~ ${formatTime12(alarm.endTime)}`;
}

export function formatShortRange(alarm) {
  return alarm.frequency === "once"
    ? formatTime12(alarm.startTime).replace(":00", "")
    : `${formatTime12(alarm.startTime).replace(":00", "")} to ${formatTime12(alarm.endTime).replace(":00", "")}`;
}

export function formatDays(days) {
  const sorted = DAY_CODES.filter((day) => days.includes(day));
  if (sorted.length === 7) return "Everyday";
  if (sorted.join(",") === "MO,TU,WE,TH,FR") return "Weekdays";
  if (sorted.length === 2 && sorted.includes("SA") && sorted.includes("SU")) return "Weekends";
  return sorted.map((day) => DAY_LABELS[day]).join(", ") || "No days selected";
}

export function previewOccurrences(alarm, limit = 200) {
  const start = parseTime(alarm.startTime);
  if (alarm.frequency === "once") return [{ index: 1, minutes: start, label: formatTime12(start) }];
  const end = parseTime(alarm.endTime);
  const distance = end >= start ? end - start : 1440 - start + end;
  const values = [];
  for (let offset = 0; offset <= distance && values.length < limit; offset += alarm.intervalMinutes) {
    const minutes = start + offset;
    values.push({ index: values.length + 1, minutes, label: formatTime12(minutes) });
  }
  return values;
}

export function validateAlarm(input) {
  const alarm = createAlarm(input);
  if (!alarm.name.trim()) throw new DomainError("alarm name is required");
  if (!alarm.repeatDays.length) throw new DomainError("select at least one repeat day");
  parseTime(alarm.startTime);
  if (alarm.frequency === "several") {
    parseTime(alarm.endTime);
    if (!Number.isInteger(alarm.intervalMinutes) || alarm.intervalMinutes <= 0) throw new DomainError("interval must be a positive number of minutes");
  }
  if (!Number.isFinite(alarm.volume) || alarm.volume < 0 || alarm.volume > 1) throw new DomainError("volume must be between 0 and 1");
  if (!Number.isInteger(alarm.durationSeconds) || alarm.durationSeconds <= 0) throw new DomainError("alarm duration must be positive");
  if (!Array.isArray(alarm.snoozeSequenceMinutes) || alarm.snoozeSequenceMinutes.some((value) => !Number.isFinite(value) || value <= 0)) throw new DomainError("snooze sequence must contain positive minute values");
  return alarm;
}
