# Kala Time Alarm — Android Studio project

This is the first Android wrapper for the web app in the repository root.

## Open it

In Android Studio, choose **Open**, then select this folder:

```text
C:\timerApp\android
```

Android Studio will sync the Gradle project and run the app on an emulator or connected phone.

The wrapper loads the current web interface from `app/src/main/assets/web/` using Android's secure `WebViewAssetLoader`. Enabled alarms are mirrored into native Android scheduling so they can fire when the WebView is closed. The native layer restores schedules after reboot, posts alarm notifications, can wake the screen, and supports native snooze and manual Stop actions.

On Android 12+, exact-alarm access must be allowed in system settings. On Android 13+, notification permission is required for notification controls. Full-screen-intent policy still applies to the device and OS version.
