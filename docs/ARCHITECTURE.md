# Architecture

## Layers

```text
Web UI / Android UI
        |
        v
Application commands and state projection
        |
        v
Pure scheduling domain engine
        |
        +--> Web persistence and browser adapter
        |
        +--> Android persistence and AlarmManager adapter
        |
        +--> Android notification, audio, vibration, and voice adapters
```

## Domain state

The domain engine works with an explicit schedule definition and an instance state. It does not call `Date.now()`, access browser APIs, or access Android APIs.

### Schedule definition

```text
ScheduleDefinition
  id
  kind: one-shot | interval-window
  startAtMs                 # timer/schedule start, not automatically a fire time
  endAtMs
  intervalMs
  durationMs                # one-shot only; fire time = startAtMs + durationMs
  endpointPolicy: inclusive | exclusive
  missedOccurrencePolicy: explicit policy value
  snoozePolicy
```

### Runtime state

```text
ScheduleState
  scheduleId
  phase: scheduled | ringing | snoozed | dismissed | completed
  nextOccurrenceAtMs
  ringingOccurrenceAtMs
  snoozeIndex
  occurrenceIndex
  revision
  lastEventId
```

## Event flow

```text
START
  -> SCHEDULED
  -> ALARM_FIRED
  -> RINGING
  -> DISMISS | SNOOZE
  -> SCHEDULED or COMPLETED
```

The Android adapter will later translate `nextOccurrenceAtMs` into an Android alarm request. When an Android receiver runs, it reloads the state, applies the same domain command, and writes the resulting state before scheduling the next occurrence.

For a one-shot countdown, `nextOccurrenceAtMs = startAtMs + durationMs`. For an interval-window schedule, the first occurrence is `startAtMs + intervalMs`; endpoint inclusion is controlled explicitly by `endpointPolicy`.

## Web-first decision

The initial project uses browser-native ES modules and Node's built-in test runner. This is a reversible implementation choice for the first slice; it avoids adding a dependency/toolchain decision before the domain behavior is verified.

## Android port boundary

The Android port must include native components for exact alarms, notifications, audio, vibration, boot/time-zone recovery, persistent storage, and later voice recognition. The web UI may remain inside a WebView, but the alarm authority must be native for reliable background behavior.
