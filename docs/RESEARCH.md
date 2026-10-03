# Research record

This record contains sources consulted for platform behavior. Product behavior not established by these sources remains an explicit decision in [REQUIREMENTS.md](./REQUIREMENTS.md).

## Android alarms

- [Android: Schedule alarms](https://developer.android.com/develop/background-work/services/alarms) — documents `AlarmManager`, exact alarms, Doze behavior, `setExactAndAllowWhileIdle()`, alarm permissions, and rescheduling after permission changes.
- [Android: AlarmClock API](https://developer.android.com/reference/android/provider/AlarmClock) — documents `ACTION_SET_ALARM`, `ACTION_SET_TIMER`, `ACTION_DISMISS_ALARM`, `ACTION_DISMISS_TIMER`, and `ACTION_SNOOZE_ALARM` interoperability.
- [Android: Full-screen notifications](https://developer.android.com/develop/ui/views/notifications/build-notification#display-time-sensitive-notifications) — documents full-screen intents for urgent, time-sensitive events such as ringing alarms.

## Android voice stop research

- [Android SpeechRecognizer API](https://developer.android.com/reference/kotlin/android/speech/SpeechRecognizer) — documents microphone permission, on-device recognition availability, and the limitation that the API is not intended for continuous recognition.
- [Vosk Android demo](https://github.com/alphacephei/vosk-android-demo) — working Android integration using `Model`, `Recognizer`, `SpeechService`, partial results, and final results.
- [Vosk Recognizer source](https://github.com/alphacep/vosk-api/blob/master/java/lib/src/main/java/org/vosk/Recognizer.java) — documents grammar restriction, 16-bit PCM input, partial results, final results, and recognizer lifecycle.
- [Offline Vosk wake-word sample](https://github.com/nishio/android-vosk-wakeword-sample) — working example of a restricted grammar, matching partial results, stopping the microphone after the first match, and avoiding duplicate triggers.
- [WakeMove](https://github.com/Ykedan/WakeMove) — an Android alarm project combining exact scheduling, lock-screen alarms, foreground services, and bundled offline Vosk voice challenges.
- [Google Assistant alarm help](https://support.google.com/assistant/answer/9275058?hl=en-uk) — confirms that Google's own alarm system supports spoken stop/snooze commands; this does not by itself give a third-party app ownership of the command.

## Constraints to verify during Android implementation

- [Android microphone foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone) — microphone access, `RECORD_AUDIO`, foreground-service type declarations, and background-start restrictions must be tested against the target Android versions and devices.
