import test from "node:test";
import assert from "node:assert/strict";
import { defaultSettings, parseSnoozeSequence, settingsToSchedule } from "../src/core/settings.mjs";

test("default settings include the requested progressive snooze sequence", () => {
  const settings = defaultSettings(0);
  assert.deepEqual(settings.snoozeSequenceMinutes, [25, 15, 10, 5]);
  assert.equal(settings.intervalMinutes, 60);
});

test("settings create a one-shot countdown schedule", () => {
  const settings = { ...defaultSettings(0), kind: "one-shot", startDate: "1970-01-01", startTime: "00:00", durationMinutes: 25 };
  const schedule = settingsToSchedule(settings);
  assert.equal(schedule.kind, "one-shot");
  assert.equal(schedule.durationMs, 25 * 60_000);
});

test("snooze sequence parser preserves user order", () => {
  assert.deepEqual(parseSnoozeSequence("25, 15, 10, 5"), [25, 15, 10, 5]);
});
