# Requirements and unresolved product decisions

This document separates requirements already stated from behavior that must be decided explicitly. Undefined behavior is not silently defaulted in the core engine.

## Confirmed scope

- Web-first application.
- Android Studio port after browser behavior is tested.
- Timers and alarms are both supported.
- Snooze can be an ordered user-defined sequence, for example 25, 15, 10, then 5 minutes.
- A long timer can emit recurring events, for example every hour during a 24-hour window.
- Multiple and complex schedules are expected.
- The Android version must preserve the actual schedule state when the app process is stopped, the device sleeps, or the device reboots.
- Voice control is a later Android feature; the target command is `STOP`.

## Explicit schedule concepts

Every schedule must declare:

- an immutable schedule ID;
- a start instant;
- a recurrence model or a one-shot end instant;
- a time basis: elapsed duration or wall-clock calendar time;
- an endpoint policy for boundary events;
- a missed-occurrence policy;
- a snooze policy;
- an enabled/disabled state.

## Decisions required before production behavior

These are intentionally unresolved:

1. For a 24-hour timer with hourly events, does an event fire at the exact 24-hour endpoint, or does the final event occur before it?
2. If the device misses three hourly occurrences, should the app catch up all three, fire once, skip them, or show a missed-event history without ringing?
3. Does snooze restart the same recurrence occurrence, or does it advance the underlying recurring schedule?
4. After the last snooze duration, should the alarm dismiss, repeat the last snooze duration, repeat the sequence, or remain ringing?
5. When two alarms are due together, should they merge, queue, or ring simultaneously?
6. Which calendar/time-zone rules apply to daily and weekly alarms around daylight-saving changes?
7. What is the minimum Android version and which languages must voice commands support?

## Non-negotiable correctness rules

- The source of truth is persisted schedule state, not a browser interval or Android process lifetime.
- A schedule is identified by stable IDs, not by display labels or array positions.
- Every state transition is recorded as an event with an event ID and timestamp.
- Applying the same command twice must not create a second dismissal, snooze, or occurrence.
- The browser clock is injectable in tests.
- Android scheduling is an adapter around the same domain rules, not a second undocumented rules engine.
