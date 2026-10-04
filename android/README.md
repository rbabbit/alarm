# Kala Time Alarm — Android Studio project

This is the native Android application for Kala Time Alarm.

## Open it

In Android Studio, choose **Open**, then select this folder:

```text
C:\timerApp\android
```

Android Studio will sync the Gradle project and run the app on an emulator or connected phone.

The app uses native Android views and widgets for its screens. It does not load the web interface or use CSS controls. Alarms and timers are scheduled through Android's AlarmManager, restored after reboot, and delivered through native foreground services and notifications. There is no microphone permission or voice-stop feature.

On Android 12+, exact-alarm access must be allowed in system settings. On Android 13+, notification permission is required for notification controls. Full-screen-intent policy still applies to the device and OS version.
