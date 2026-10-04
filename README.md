# Kala Time Alarm

Web-first foundation for a complex timer, alarm, recurrence, and snooze application.

Play Store title: **Kala Time – Alarm Clock**

## Current status

The repository contains a browser prototype plus a native Android application. The Android application uses native views/widgets rather than a WebView for its UI. It contains:

- a deterministic scheduling core with an injected clock;
- explicit schedule and snooze policies;
- tests for one-shot, repeating, 24-hour-style interval schedules, and snooze progression;
- a browser shell retained for domain/UI prototyping only;
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

The native Android app is in [`android/`](./android). Open `C:\timerApp\android` in Android Studio to run it on a device or emulator. It provides native alarms, a Multi-Timer, Counter, and GPS weather screen, while the existing AlarmManager/notification layer handles delivery when the app is closed.

## Important boundary

The browser app remains a prototype/fallback and is not packaged into the Android application. Native background delivery is provided by the Android project. Exact alarms, notifications, location, and foreground-service behavior remain subject to the permissions and OS policies documented in the research notes.
