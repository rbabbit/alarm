import test from "node:test";
import assert from "node:assert/strict";
import { createAlarm, formatDays, formatTime24, previewOccurrences, validateAlarm } from "../src/core/alarm-model.mjs";

test("previews the inclusive hourly list from 6 AM through 6 PM", () => {
  const alarm = createAlarm({ startTime: "06:00", endTime: "18:00", intervalMinutes: 60 });
  const preview = previewOccurrences(alarm);
  assert.equal(preview.length, 13);
  assert.equal(preview[0].label, "6:00 AM");
  assert.equal(preview.at(-1).label, "6:00 PM");
});

test("supports an overnight interval range", () => {
  const alarm = createAlarm({ startTime: "18:00", endTime: "06:00", intervalMinutes: 60 });
  const preview = previewOccurrences(alarm);
  assert.equal(preview.length, 13);
  assert.equal(preview.at(-1).label, "6:00 AM");
});

test("supports a custom 45-minute interval", () => {
  const alarm = createAlarm({ startTime: "11:00", endTime: "14:00", intervalMinutes: 45 });
  const preview = previewOccurrences(alarm);
  assert.deepEqual(preview.map((item) => item.minutes), [660, 705, 750, 795, 840]);
});

test("formats standard repeat-day groups", () => {
  assert.equal(formatDays(["SU", "MO", "TU", "WE", "TH", "FR", "SA"]), "Everyday");
  assert.equal(formatDays(["MO", "TU", "WE", "TH", "FR"]), "Weekdays");
  assert.equal(formatDays(["SU", "SA"]), "Weekends");
});

test("formats 24-hour times without an AM/PM suffix", () => {
  assert.equal(formatTime24("00:05"), "00:05");
  assert.equal(formatTime24("11:14"), "11:14");
  assert.equal(formatTime24("23:45"), "23:45");
});

test("rejects an alarm without repeat days", () => {
  assert.throws(() => validateAlarm(createAlarm({ repeatDays: [] })));
});
