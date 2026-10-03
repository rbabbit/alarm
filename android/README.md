# Android Studio project

This is the first Android wrapper for the web app in the repository root.

## Open it

In Android Studio, choose **Open**, then select this folder:

```text
C:\timerApp\android
```

Android Studio will sync the Gradle project and run the app on an emulator or connected phone.

The wrapper loads the current web interface from `app/src/main/assets/web/` using Android's secure `WebViewAssetLoader`. The web app remains the source of the current interface; native alarm scheduling and background ringing will be added in a later Android phase.
