package com.rbabbit.alarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
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
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST_CODE = 1001;
    private static final int APP_PERMISSIONS_REQUEST_CODE = 1002;
    private WebView webView;
    private FrameLayout rootLayout;
    private final Map<String, Switch> nativeSwitches = new HashMap<>();
    private final Map<String, View> nativeControls = new HashMap<>();
    private final Map<String, String> nativeControlTypes = new HashMap<>();
    private final Map<String, JSONObject> nativeControlStates = new HashMap<>();
    private boolean syncingNativeSwitches;
    private boolean syncingNativeControls;
    private int topInsetPx;
    private int bottomInsetPx;
    private boolean appPermissionsRequested;
    private boolean exactAlarmSettingsRequested;
    private final BroadcastReceiver quickTimerStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (QuickTimerScheduler.ACTION_STATE_CHANGED.equals(intent.getAction())) notifyWebState();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setFitsSystemWindows(false);
        rootLayout = new FrameLayout(this);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(rootLayout);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        applySystemBarInsets();
        configureWebView();
        NotificationHelper.createChannel(this);
        AlarmScheduler.syncAll(this);
        QuickTimerNotificationHelper.createChannel(this);
        QuickTimerScheduler.syncAll(this);
        ContextCompat.registerReceiver(this, quickTimerStateReceiver,
                new IntentFilter(QuickTimerScheduler.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
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
            // Keep the WebView edge-to-edge and let the web shell own layout
            // insets through the CSS variables below. Applying native padding
            // as well would create two competing inset systems and can place
            // headers underneath the Android status bar on some devices.
            view.setPadding(0, 0, 0, 0);
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
        webView.addJavascriptInterface(new NativeControlsBridge(), "AndroidNativeControls");

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
            JSONArray quickTimers = state.optJSONArray("quickTimers");
            boolean hasEnabledAlarm = false;
            if (alarms != null) {
                for (int index = 0; index < alarms.length(); index += 1) {
                    JSONObject alarm = alarms.optJSONObject(index);
                    if (alarm == null || !alarm.optBoolean("enabled", false)) continue;
                    hasEnabledAlarm = true;
                }
            }
            boolean hasRunningQuickTimer = false;
            if (quickTimers != null) {
                for (int index = 0; index < quickTimers.length(); index += 1) {
                    JSONObject timer = quickTimers.optJSONObject(index);
                    if (timer != null && ("running".equals(timer.optString("state"))
                            || "ringing".equals(timer.optString("state")))) {
                        hasRunningQuickTimer = true;
                        break;
                    }
                }
            }
            if (!hasEnabledAlarm && !hasRunningQuickTimer) return;

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
        public void syncQuickTimers(String timersJson) {
            runOnUiThread(() -> {
                QuickTimerScheduler.cancelKnownTimers(MainActivity.this);
                QuickTimerStore.sync(MainActivity.this, timersJson);
                QuickTimerScheduler.syncAll(MainActivity.this);
                requestAppPermissions(AlarmStore.getStateJson(MainActivity.this));
            });
        }

        @JavascriptInterface
        public void stopQuickTimer(String timerId) {
            QuickTimerActionReceiver.stop(MainActivity.this, timerId);
        }

        @JavascriptInterface
        public void snoozeQuickTimer(String timerId) {
            QuickTimerActionReceiver.snooze(MainActivity.this, timerId);
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

    private Switch createNativeSwitch(String key, String description) {
        Switch control = new Switch(this);
        control.setShowText(false);
        control.setSwitchMinWidth(dp(64));
        control.setContentDescription(description);
        control.setOnCheckedChangeListener((button, checked) -> {
            if (syncingNativeSwitches || webView == null) return;
            String script = "window.setNativeSwitch && window.setNativeSwitch("
                    + JSONObject.quote(key) + "," + checked + ");";
            webView.post(() -> webView.evaluateJavascript(script, null));
        });
        control.setVisibility(View.GONE);
        return control;
    }

    private Switch nativeSwitchFor(String key, String description) {
        Switch existing = nativeSwitches.get(key);
        if (existing != null) return existing;
        Switch created = createNativeSwitch(key, description);
        nativeSwitches.put(key, created);
        rootLayout.addView(created, new FrameLayout.LayoutParams(1, 1));
        return created;
    }

    private void updateNativeSwitch(Switch control, JSONObject state) {
        if (control == null || state == null) {
            if (control != null) control.setVisibility(View.GONE);
            return;
        }
        float scale = webView == null ? 1f : webView.getScale();
        int width = Math.max(dp(48), Math.round((float) state.optDouble("width", 64) * scale));
        int height = Math.max(dp(48), Math.round((float) state.optDouble("height", 34) * scale));
        int left = webView == null ? 0 : webView.getLeft() + Math.round((float) state.optDouble("left", 0) * scale);
        int top = webView == null ? 0 : webView.getTop() + Math.round((float) state.optDouble("top", 0) * scale)
                - Math.max(0, height - Math.round((float) state.optDouble("height", 34) * scale)) / 2;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        control.setLayoutParams(params);
        syncingNativeSwitches = true;
        control.setChecked(state.optBoolean("checked", false));
        syncingNativeSwitches = false;
        control.setVisibility(View.VISIBLE);
        control.bringToFront();
    }

    private void dispatchNativeControl(String key, String value) {
        if (webView == null) return;
        String script = "window.dispatchNativeControl && window.dispatchNativeControl(" + JSONObject.quote(key);
        if (value != null) script += "," + JSONObject.quote(value);
        script += ");";
        final String javascript = script;
        webView.post(() -> webView.evaluateJavascript(javascript, null));
    }

    private String selectedOptionLabel(JSONObject state) {
        String value = state.optString("value", "");
        JSONArray options = state.optJSONArray("options");
        if (options != null) {
            for (int index = 0; index < options.length(); index += 1) {
                JSONObject option = options.optJSONObject(index);
                if (option != null && value.equals(option.optString("value"))) {
                    return option.optString("label", value);
                }
            }
        }
        return value;
    }

    private void showNativeTimePicker(String key, JSONObject state) {
        String value = state.optString("value", "00:00");
        int hour = 0;
        int minute = 0;
        try {
            String[] parts = value.split(":");
            hour = Math.max(0, Math.min(23, Integer.parseInt(parts[0])));
            minute = Math.max(0, Math.min(59, Integer.parseInt(parts[1])));
        } catch (RuntimeException ignored) { }
        new TimePickerDialog(
                this,
                (view, selectedHour, selectedMinute) -> dispatchNativeControl(
                        key,
                        String.format(Locale.US, "%02d:%02d", selectedHour, selectedMinute)
                ),
                hour,
                minute,
                true
        ).show();
    }

    private void showNativeSelect(String key, JSONObject state) {
        JSONArray options = state.optJSONArray("options");
        if (options == null || options.length() == 0) return;
        String[] labels = new String[options.length()];
        int selected = 0;
        String current = state.optString("value", "");
        for (int index = 0; index < options.length(); index += 1) {
            JSONObject option = options.optJSONObject(index);
            labels[index] = option == null ? "" : option.optString("label", option.optString("value", ""));
            if (option != null && current.equals(option.optString("value"))) selected = index;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(state.optString("text", "Choose an option"))
                .setSingleChoiceItems(labels, selected, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            android.widget.ListView list = dialog.getListView();
            list.setOnItemClickListener((parent, view, position, id) -> {
                JSONObject option = options.optJSONObject(position);
                if (option != null) dispatchNativeControl(key, option.optString("value", ""));
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private GradientDrawable nativeBackground(JSONObject state, boolean selected) {
        String classes = state.optString("className", "") + " " + state.optString("parentClassName", "");
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? Color.BLACK : Color.WHITE);
        background.setStroke(dp(1), Color.BLACK);
        if (classes.contains("bottom-nav")) {
            background.setColor(Color.TRANSPARENT);
            background.setStroke(0, Color.TRANSPARENT);
        } else if (classes.contains("day-picker") || classes.contains("alarm-day-strip")) {
            background.setShape(GradientDrawable.OVAL);
        } else {
            background.setCornerRadius(dp(8));
        }
        return background;
    }

    private View createNativeControl(String key, String type) {
        if ("switch".equals(type)) {
            Switch control = nativeSwitchFor(key, key);
            nativeControls.put(key, control);
            return control;
        }
        if ("input".equals(type)) {
            EditText control = new EditText(this);
            control.setSingleLine(true);
            control.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            control.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    if (!syncingNativeControls) dispatchNativeControl(key, value.toString());
                }
                @Override public void afterTextChanged(Editable value) { }
            });
            nativeControls.put(key, control);
            rootLayout.addView(control, new FrameLayout.LayoutParams(1, 1));
            return control;
        }
        if ("range".equals(type)) {
            SeekBar control = new SeekBar(this);
            control.setMax(1000);
            control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!syncingNativeControls && fromUser) {
                        JSONObject state = nativeControlStates.get(key);
                        double min = state == null ? 0 : state.optDouble("min", 0);
                        double max = state == null ? 1 : state.optDouble("max", 1);
                        double value = min + (max - min) * progress / 1000d;
                        dispatchNativeControl(key, String.format(Locale.US, "%.3f", value));
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            nativeControls.put(key, control);
            rootLayout.addView(control, new FrameLayout.LayoutParams(1, 1));
            return control;
        }
        Button control = new Button(this);
        control.setAllCaps(false);
        control.setGravity(Gravity.CENTER);
        control.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        control.setPadding(dp(4), 0, dp(4), 0);
        control.setOnClickListener(view -> {
            JSONObject state = nativeControlStates.get(key);
            if (state == null) return;
            if ("time".equals(type)) showNativeTimePicker(key, state);
            else if ("select".equals(type)) showNativeSelect(key, state);
            else dispatchNativeControl(key, null);
        });
        nativeControls.put(key, control);
        rootLayout.addView(control, new FrameLayout.LayoutParams(1, 1));
        return control;
    }

    private void updateNativeControl(View control, JSONObject state) {
        String key = state.optString("key", "");
        String type = state.optString("type", "button");
        nativeControlStates.put(key, state);
        float scale = webView == null ? 1f : webView.getScale();
        int width = Math.max(dp(32), Math.round((float) state.optDouble("width", 48) * scale));
        int height = Math.max(dp(32), Math.round((float) state.optDouble("height", 40) * scale));
        int left = webView == null ? 0 : webView.getLeft() + Math.round((float) state.optDouble("left", 0) * scale);
        int top = webView == null ? 0 : webView.getTop() + Math.round((float) state.optDouble("top", 0) * scale);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        control.setLayoutParams(params);
        control.setEnabled(state.optBoolean("enabled", true));
        if (control instanceof Switch) {
            syncingNativeSwitches = true;
            ((Switch) control).setChecked(state.optBoolean("checked", false));
            syncingNativeSwitches = false;
        } else if (control instanceof EditText) {
            EditText editText = (EditText) control;
            String value = state.optString("value", "");
            syncingNativeControls = true;
            if (!value.equals(editText.getText().toString())) {
                editText.setText(value);
                editText.setSelection(editText.length());
            }
            editText.setHint(state.optString("hint", ""));
            if ("number".equals(state.optString("inputType", ""))) {
                editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            }
            syncingNativeControls = false;
        } else if (control instanceof SeekBar) {
            double min = state.optDouble("min", 0);
            double max = state.optDouble("max", 1);
            double value = state.optDouble("value", min);
            int progress = max <= min ? 0 : (int) Math.round((value - min) * 1000d / (max - min));
            syncingNativeControls = true;
            ((SeekBar) control).setProgress(Math.max(0, Math.min(1000, progress)));
            syncingNativeControls = false;
        } else if (control instanceof Button) {
            Button button = (Button) control;
            String label;
            if ("time".equals(type)) label = state.optString("value", "00:00");
            else if ("select".equals(type)) label = selectedOptionLabel(state);
            else label = state.optString("text", "");
            button.setText(label);
            boolean selected = state.optBoolean("selected", false) || state.optString("className", "").contains("selected");
            button.setTextColor(selected ? Color.WHITE : Color.BLACK);
            button.setBackground(nativeBackground(state, selected));
        }
        control.setVisibility(View.VISIBLE);
        control.bringToFront();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public final class NativeControlsBridge {
        @JavascriptInterface
        public void syncNativeControls(String stateJson) {
            runOnUiThread(() -> {
                try {
                    JSONObject state = new JSONObject(stateJson == null ? "{}" : stateJson);
                    for (View control : nativeControls.values()) control.setVisibility(View.GONE);
                    JSONArray controls = state.optJSONArray("controls");
                    if (controls == null) return;
                    for (int index = 0; index < controls.length(); index += 1) {
                        JSONObject controlState = controls.optJSONObject(index);
                        if (controlState == null) continue;
                        String key = controlState.optString("key", "");
                        String type = controlState.optString("type", "button");
                        if (key.isEmpty()) continue;
                        View control = nativeControls.get(key);
                        if (control == null || !type.equals(nativeControlTypes.get(key))) {
                            if (control != null) rootLayout.removeView(control);
                            nativeControlTypes.put(key, type);
                            control = createNativeControl(key, type);
                        }
                        updateNativeControl(control, controlState);
                    }
                } catch (JSONException ignored) {
                    for (View control : nativeControls.values()) control.setVisibility(View.GONE);
                }
            });
        }

        @JavascriptInterface
        public void syncNativeSwitches(String stateJson) {
            runOnUiThread(() -> {
                try {
                    JSONObject state = new JSONObject(stateJson == null ? "{}" : stateJson);
                    for (Switch control : nativeSwitches.values()) control.setVisibility(View.GONE);
                    JSONArray switches = state.optJSONArray("switches");
                    if (switches == null) return;
                    for (int index = 0; index < switches.length(); index += 1) {
                        JSONObject switchState = switches.optJSONObject(index);
                        if (switchState == null) continue;
                        String key = switchState.optString("key", "");
                        if (key.isEmpty()) continue;
                        Switch control = nativeSwitchFor(key, switchState.optString("description", key));
                        updateNativeSwitch(control, switchState);
                    }
                } catch (JSONException ignored) {
                    for (Switch control : nativeSwitches.values()) control.setVisibility(View.GONE);
                }
            });
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
        try { unregisterReceiver(quickTimerStateReceiver); } catch (IllegalArgumentException ignored) { }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlarmScheduler.syncAll(this);
        QuickTimerScheduler.syncAll(this);
        requestAppPermissions(AlarmStore.getStateJson(this));
        if (webView != null) webView.postDelayed(this::notifyWebState, 150);
    }
}
