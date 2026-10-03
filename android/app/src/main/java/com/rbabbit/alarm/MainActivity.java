package com.rbabbit.alarm;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.KeyEvent;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST_CODE = 1001;
    private static final int APP_PERMISSIONS_REQUEST_CODE = 1002;
    private WebView webView;
    private int topInsetPx;
    private int bottomInsetPx;
    private boolean appPermissionsRequested;
    private boolean exactAlarmSettingsRequested;
    private boolean fullScreenSettingsRequested;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setFitsSystemWindows(true);
        setContentView(webView);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        applySystemBarInsets();
        configureWebView();
        NotificationHelper.createChannel(this);
        AlarmScheduler.syncAll(this);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBack
            );
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_REQUEST_CODE
            );
        } else {
            loadWebApp();
        }
    }

    private void applySystemBarInsets() {
        webView.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            topInsetPx = topInset;
            bottomInsetPx = bottomInset;
            // The WebView is edge-to-edge on current target SDKs. Reserve the
            // status-bar space in the web app itself; keep only the bottom
            // padding here for the navigation bar.
            view.setPadding(view.getPaddingLeft(), 0, view.getPaddingRight(), bottomInset);
            updateWebSafeArea();
            return insets;
        });
        webView.requestApplyInsets();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new AndroidAlarmBridge(), "AndroidAlarmBridge");

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request
            ) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript("document.documentElement.classList.add('native-android');", null);
                updateWebSafeArea();
                notifyWebState();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback
            ) {
                callback.invoke(origin, true, false);
            }
        });
    }

    private void loadWebApp() {
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html");
    }

    private void notifyWebState() {
        if (webView == null) return;
        String state = JSONObject.quote(AlarmStore.getStateJson(this));
        webView.evaluateJavascript(
                "window.applyNativeAlarmState && window.applyNativeAlarmState(" + state + ");",
                null
        );
    }

    private void requestAppPermissions(String stateJson) {
        if (appPermissionsRequested) return;
        try {
            JSONObject state = new JSONObject(stateJson == null ? "{}" : stateJson);
            JSONArray alarms = state.optJSONArray("alarms");
            boolean hasEnabledAlarm = false;
            if (alarms != null) {
                for (int index = 0; index < alarms.length(); index += 1) {
                    JSONObject alarm = alarms.optJSONObject(index);
                    if (alarm == null || !alarm.optBoolean("enabled", false)) continue;
                    hasEnabledAlarm = true;
                }
            }
            if (!hasEnabledAlarm) return;

            if (Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canScheduleExactAlarms(this) && !exactAlarmSettingsRequested) {
                try {
                    exactAlarmSettingsRequested = true;
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:" + getPackageName())));
                    return;
                } catch (RuntimeException ignored) {
                    // The app continues with inexact delivery if this settings page is unavailable.
                }
            }

            if (Build.VERSION.SDK_INT >= 34 && needsWakeScreen(alarms)
                    && !NotificationHelper.canUseFullScreenIntent(this)
                    && !fullScreenSettingsRequested) {
                try {
                    fullScreenSettingsRequested = true;
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:" + getPackageName())));
                    return;
                } catch (RuntimeException ignored) {
                    // The notification remains available if this settings page is unavailable.
                }
            }

            java.util.ArrayList<String> missing = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!missing.isEmpty()) {
                appPermissionsRequested = true;
                requestPermissions(missing.toArray(new String[0]), APP_PERMISSIONS_REQUEST_CODE);
            }
        } catch (JSONException ignored) {
            // Invalid web state cannot be used to request permissions.
        }
    }

    private boolean needsWakeScreen(JSONArray alarms) {
        if (alarms == null) return false;
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm != null && alarm.optBoolean("enabled", false) && alarm.optBoolean("wakeScreen", true)) return true;
        }
        return false;
    }

    public final class AndroidAlarmBridge {
        @JavascriptInterface
        public String getState() {
            return AlarmStore.getStateJson(MainActivity.this);
        }

        @JavascriptInterface
        public void syncState(String stateJson) {
            runOnUiThread(() -> {
                AlarmScheduler.cancelKnownAlarms(MainActivity.this);
                AlarmStore.syncState(MainActivity.this, stateJson);
                AlarmScheduler.syncAll(MainActivity.this);
                requestAppPermissions(stateJson);
                notifyWebState();
            });
        }

        @JavascriptInterface
        public boolean canScheduleExactAlarms() {
            return AlarmScheduler.canScheduleExactAlarms(MainActivity.this);
        }

        @JavascriptInterface
        public void openExactAlarmSettings() {
            if (Build.VERSION.SDK_INT < 31) return;
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignored) { }
        }
    }

    private void updateWebSafeArea() {
        if (webView == null) return;
        float density = getResources().getDisplayMetrics().density;
        int topInsetCssPx = Math.round(topInsetPx / Math.max(density, 1f));
        int bottomInsetCssPx = Math.round(bottomInsetPx / Math.max(density, 1f));
        webView.evaluateJavascript(
                "document.documentElement.style.setProperty('--native-top-inset', '" + topInsetCssPx + "px');" +
                        "document.documentElement.style.setProperty('--native-bottom-inset', '" + bottomInsetCssPx + "px');",
                null
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST_CODE) {
            loadWebApp();
        }
    }

    private void handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT < 33) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlarmScheduler.syncAll(this);
        requestAppPermissions(AlarmStore.getStateJson(this));
        if (webView != null) webView.postDelayed(this::notifyWebState, 150);
    }
}
