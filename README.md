# Timer App

Web-first foundation for a complex timer, alarm, recurrence, and snooze application.

## Current status

This is the first foundation slice. It contains:

- a deterministic scheduling core with an injected clock;
- explicit schedule and snooze policies;
- tests for one-shot, repeating, 24-hour-style interval schedules, and snooze progression;
- a browser shell ready for the UI layer;
- research and architecture records so platform behavior is not guessed.

## Run

The project uses browser-native ES modules and Node's built-in test runner for this first slice.
No package download is required.

```powershell
cd C:\timerApp
node --test
```

To view the browser shell, serve the folder with any local static HTTP server and open `web/index.html`.
The browser's `file:` URL restrictions are intentionally avoided by using a local server.

## Documents

- [Requirements](./docs/REQUIREMENTS.md)
- [Architecture](./docs/ARCHITECTURE.md)
- [Research sources](./docs/RESEARCH.md)

## Android Studio

The Android wrapper is in [`android/`](./android). Open `C:\timerApp\android` in Android Studio to run it on a device or emulator. The wrapper mirrors enabled alarms into native exact-alarm delivery, handles reboot rescheduling and alarm notifications, and provides a native ringing screen with snooze and optional voice `STOP` disable behavior.

## Important boundary

The browser app remains a control surface and fallback; native background delivery is provided by the Android project. Exact alarms, notifications, full-screen ringing, microphone access, and voice recognition remain subject to the permissions and OS policies documented in the research notes.
