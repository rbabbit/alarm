package com.rbabbit.alarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrialManager {
    private static final String PREFS_NAME = "trial_state";
    private static final String CACHED_EXPIRY_ELAPSED = "cached_expiry_elapsed_ms";
    private static final String ENDPOINT = "https://krishnacollection.app/api/trial.php";

    /** Five minutes keeps the test build quick to validate. */
    public static final long TEST_TRIAL_DURATION_MS = 5L * 60L * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final boolean active;
        public final boolean verified;
        public final long remainingMs;
        public final String errorMessage;

        private Result(boolean active, boolean verified, long remainingMs, String errorMessage) {
            this.active = active;
            this.verified = verified;
            this.remainingMs = Math.max(0L, remainingMs);
            this.errorMessage = errorMessage;
        }

        public static Result active(boolean verified, long remainingMs) {
            return new Result(true, verified, remainingMs, null);
        }

        public static Result inactive(boolean verified, String errorMessage) {
            return new Result(false, verified, 0L, errorMessage);
        }
    }

    private TrialManager() {
    }

    public static void refresh(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Result result;
            try {
                result = request(appContext);
            } catch (Exception error) {
                long cached = remainingMs(appContext);
                result = cached > 0L
                        ? Result.active(false, cached)
                        : Result.inactive(false, error.getMessage());
            }
            Result finalResult = result;
            MAIN.post(() -> callback.onResult(finalResult));
        });
    }

    public static long remainingMs(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long expiry = preferences.getLong(CACHED_EXPIRY_ELAPSED, 0L);
        return Math.max(0L, expiry - android.os.SystemClock.elapsedRealtime());
    }

    public static boolean isExpired(Context context) {
        return remainingMs(context) <= 0L;
    }

    private static Result request(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = stableDeviceId(context);

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = new JSONObject().put("device_id", deviceId).toString()
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = read(stream);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Trial server HTTP " + status);
        }

        JSONObject json = new JSONObject(response);
        boolean active = json.optBoolean("trial_active", false);
        long seconds = Math.max(0L, json.optLong("seconds_remaining", 0L));
        long remaining = seconds * 1000L;
        preferences.edit()
                .putLong(CACHED_EXPIRY_ELAPSED,
                        android.os.SystemClock.elapsedRealtime() + remaining)
                .apply();
        return active && remaining > 0L
                ? Result.active(true, remaining)
                : Result.inactive(true, null);
    }

    private static String stableDeviceId(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            throw new IllegalStateException("No stable device identity available");
        }
        return "android:" + androidId;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }
}
