package com.rbabbit.alarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native persistence for Multi-Timer instances. */
public final class QuickTimerStore {
    private static final String PREFS = "quick_timer_native_state";
    private static final String TIMERS = "timers";

    private QuickTimerStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized JSONArray getTimers(Context context) {
        String value = prefs(context).getString(TIMERS, "[]");
        try {
            return new JSONArray(value);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    public static synchronized JSONObject find(Context context, String timerId) {
        if (timerId == null) return null;
        JSONArray timers = getTimers(context);
        for (int index = 0; index < timers.length(); index += 1) {
            JSONObject timer = timers.optJSONObject(index);
            if (timer != null && timerId.equals(timer.optString("id"))) {
                try {
                    return new JSONObject(timer.toString());
                } catch (JSONException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static synchronized void sync(Context context, String timersJson) {
        try {
            JSONArray timers = new JSONArray(timersJson == null ? "[]" : timersJson);
            prefs(context).edit().putString(TIMERS, timers.toString()).apply();
        } catch (JSONException ignored) {
            // Keep the last valid native timer state.
        }
    }

    public static synchronized void replace(Context context, JSONArray timers) {
        prefs(context).edit().putString(TIMERS, timers == null ? "[]" : timers.toString()).apply();
    }

    public static synchronized boolean update(Context context, JSONObject replacement) {
        if (replacement == null) return false;
        String timerId = replacement.optString("id");
        JSONArray timers = getTimers(context);
        boolean found = false;
        for (int index = 0; index < timers.length(); index += 1) {
            JSONObject timer = timers.optJSONObject(index);
            if (timer != null && timerId.equals(timer.optString("id"))) {
                try {
                    timers.put(index, replacement);
                    found = true;
                } catch (JSONException ignored) {
                    // Keep the prior valid timer record.
                }
                break;
            }
        }
        if (found) replace(context, timers);
        return found;
    }

    public static synchronized JSONObject remove(Context context, String timerId) {
        JSONArray timers = getTimers(context);
        JSONObject removed = null;
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < timers.length(); index += 1) {
            JSONObject timer = timers.optJSONObject(index);
            if (timer != null && timerId != null && timerId.equals(timer.optString("id"))) {
                removed = timer;
            } else if (timer != null) {
                remaining.put(timer);
            }
        }
        if (removed != null) replace(context, remaining);
        return removed;
    }
}
