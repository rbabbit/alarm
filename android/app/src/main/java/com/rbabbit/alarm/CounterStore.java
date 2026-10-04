package com.rbabbit.alarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Small native store for persistent manual counters. */
public final class CounterStore {
    private static final String PREFS = "counter_native_state";
    private static final String COUNTERS = "counters";
    private static final int MAX_COUNTERS = 20;
    static final long MAX_COUNTER_VALUE = 10_000L;

    private CounterStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized JSONArray get(Context context) {
        try {
            JSONArray counters = new JSONArray(prefs(context).getString(COUNTERS, "[]"));
            boolean changed = false;
            for (int index = 0; index < counters.length(); index += 1) {
                JSONObject counter = counters.optJSONObject(index);
                if (counter != null && counter.optLong("value", 0L) > MAX_COUNTER_VALUE) {
                    counter.put("value", MAX_COUNTER_VALUE);
                    changed = true;
                }
            }
            if (changed) save(context, counters);
            return counters;
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    public static synchronized JSONObject add(Context context, String name) {
        JSONArray counters = get(context);
        if (counters.length() >= MAX_COUNTERS) return null;
        JSONObject counter = new JSONObject();
        try {
            counter.put("id", UUID.randomUUID().toString());
            counter.put("name", name == null || name.trim().isEmpty() ? "Counter" : name.trim());
            counter.put("value", 0);
            counters.put(counter);
            save(context, counters);
            return counter;
        } catch (JSONException ignored) {
            return null;
        }
    }

    public static synchronized void update(Context context, JSONObject replacement) {
        if (replacement == null) return;
        JSONArray counters = get(context);
        String id = replacement.optString("id");
        for (int index = 0; index < counters.length(); index += 1) {
            JSONObject counter = counters.optJSONObject(index);
            if (counter != null && id.equals(counter.optString("id"))) {
                try {
                    if (replacement.optLong("value", 0L) > MAX_COUNTER_VALUE) replacement.put("value", MAX_COUNTER_VALUE);
                    counters.put(index, replacement);
                } catch (JSONException ignored) { return; }
                save(context, counters);
                return;
            }
        }
    }

    public static synchronized void remove(Context context, String id) {
        JSONArray counters = get(context);
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < counters.length(); index += 1) {
            JSONObject counter = counters.optJSONObject(index);
            if (counter != null && !id.equals(counter.optString("id"))) remaining.put(counter);
        }
        save(context, remaining);
    }

    private static void save(Context context, JSONArray counters) {
        prefs(context).edit().putString(COUNTERS, counters.toString()).apply();
    }
}
