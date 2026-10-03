# Research record

This record contains sources consulted for platform behavior. Product behavior not established by these sources remains an explicit decision in [REQUIREMENTS.md](./REQUIREMENTS.md).

## Android alarms

- [Android: Schedule alarms](https://developer.android.com/develop/background-work/services/alarms) — documents `AlarmManager`, exact alarms, Doze behavior, `setExactAndAllowWhileIdle()`, alarm permissions, and rescheduling after permission changes.
- [Android: AlarmClock API](https://developer.android.com/reference/android/provider/AlarmClock) — documents `ACTION_SET_ALARM`, `ACTION_SET_TIMER`, `ACTION_DISMISS_ALARM`, `ACTION_DISMISS_TIMER`, and `ACTION_SNOOZE_ALARM` interoperability.
- [Android: Full-screen notifications](https://developer.android.com/develop/ui/views/notifications/build-notification#display-time-sensitive-notifications) — documents full-screen intents for urgent, time-sensitive events such as ringing alarms.

## Removed voice-stop experiment

The experimental voice-activated STOP path was removed. The app deliberately uses notification and on-screen Stop controls only; it does not request microphone access or run speech recognition.
