package com.rbabbit.alarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Native persistence for alarms and the capped event history. */
public final class AlarmStore {
    private static final String PREFS = "alarm_native_state";
    private static final String ALARMS = "alarms";
    private static final String HISTORY = "history";
    private static final String ALARM_REVISION = "alarm_revision";
    private static final int MAX_HISTORY = 100;

    private AlarmStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized String getStateJson(Context context) {
        JSONArray alarms = readArray(context, ALARMS);
        JSONArray history = readArray(context, HISTORY);
        try {
            return new JSONObject()
                    .put("alarms", alarms)
                    .put("history", history)
                    .put("quickTimers", QuickTimerStore.getTimers(context))
                    .toString();
        } catch (JSONException error) {
            return "{\"alarms\":[],\"history\":[],\"quickTimers\":[]}";
        }
    }

    public static synchronized JSONArray getAlarms(Context context) {
        return readArray(context, ALARMS);
    }

    /** Monotonic revision used to invalidate launcher widget collections after changes. */
    public static synchronized long getAlarmRevision(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(ALARM_REVISION)) {
            preferences.edit().putLong(ALARM_REVISION, 1L).apply();
            return 1L;
        }
        return preferences.getLong(ALARM_REVISION, 1L);
    }

    public static synchronized JSONObject findAlarm(Context context, String alarmId) {
        JSONArray alarms = readArray(context, ALARMS);
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm != null && alarmId.equals(alarm.optString("id"))) {
                try {
                    return new JSONObject(alarm.toString());
                } catch (JSONException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static synchronized void syncState(Context context, String stateJson) {
        try {
            JSONObject incoming = new JSONObject(stateJson == null ? "{}" : stateJson);
            JSONArray alarms = incoming.optJSONArray("alarms");
            JSONArray incomingHistory = incoming.optJSONArray("history");
            if (alarms == null) alarms = new JSONArray();
            if (incomingHistory == null) incomingHistory = new JSONArray();

            JSONArray mergedHistory = mergeHistory(readArray(context, HISTORY), incomingHistory);
            writeAlarms(context, alarms)
                    .putString(HISTORY, mergedHistory.toString())
                    .apply();
        } catch (JSONException ignored) {
            // Invalid bridge data is ignored rather than destroying the last valid schedule.
        }
    }

    public static synchronized void setEnabled(Context context, String alarmId, boolean enabled) {
        JSONArray alarms = readArray(context, ALARMS);
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm != null && alarmId.equals(alarm.optString("id"))) {
                try {
                    alarm.put("enabled", enabled);
                } catch (JSONException ignored) {
                    // Keep the last valid native state if a malformed alarm is encountered.
                }
            }
        }
        writeAlarms(context, alarms).apply();
    }

    public static synchronized void upsert(Context context, JSONObject replacement) {
        if (replacement == null) return;
        String alarmId = replacement.optString("id");
        if (alarmId.isEmpty()) return;
        JSONArray alarms = readArray(context, ALARMS);
        boolean found = false;
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm != null && alarmId.equals(alarm.optString("id"))) {
                try {
                    alarms.put(index, new JSONObject(replacement.toString()));
                    found = true;
                } catch (JSONException ignored) {
                    return;
                }
                break;
            }
        }
        if (!found) {
            try {
                alarms.put(new JSONObject(replacement.toString()));
            } catch (JSONException ignored) {
                return;
            }
        }
        writeAlarms(context, alarms).apply();
    }

    public static synchronized JSONObject remove(Context context, String alarmId) {
        if (alarmId == null || alarmId.isEmpty()) return null;
        JSONArray alarms = readArray(context, ALARMS);
        JSONObject removed = null;
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm != null && alarmId.equals(alarm.optString("id"))) {
                removed = alarm;
            } else if (alarm != null) {
                remaining.put(alarm);
            }
        }
        if (removed != null) writeAlarms(context, remaining).apply();
        return removed;
    }

    public static synchronized void appendHistory(Context context, JSONObject alarm, String action) {
        if (alarm == null) return;
        JSONArray history = readArray(context, HISTORY);
        try {
            JSONObject item = new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("alarmId", alarm.optString("id"))
                    .put("name", alarm.optString("name", "Alarm"))
                    .put("action", action)
                    .put("atMs", System.currentTimeMillis())
                    .put("alarmSnapshot", new JSONObject(alarm.toString()));
            history.put(item);
            prefs(context).edit().putString(HISTORY, trimHistory(history).toString()).apply();
        } catch (JSONException ignored) {
            // History must never prevent an alarm from being stopped or rescheduled.
        }
    }

    private static JSONArray readArray(Context context, String key) {
        String value = prefs(context).getString(key, "[]");
        try {
            return new JSONArray(value);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private static SharedPreferences.Editor writeAlarms(Context context, JSONArray alarms) {
        SharedPreferences preferences = prefs(context);
        long revision = preferences.getLong(ALARM_REVISION, 0L) + 1L;
        return preferences.edit()
                .putString(ALARMS, alarms.toString())
                .putLong(ALARM_REVISION, revision);
    }

    private static JSONArray mergeHistory(JSONArray first, JSONArray second) {
        Map<String, JSONObject> byId = new HashMap<>();
        addHistoryItems(byId, first);
        addHistoryItems(byId, second);
        JSONArray merged = new JSONArray();
        byId.values().stream()
                .sorted((left, right) -> Long.compare(left.optLong("atMs"), right.optLong("atMs")))
                .forEach(merged::put);
        return trimHistory(merged);
    }

    private static void addHistoryItems(Map<String, JSONObject> byId, JSONArray source) {
        for (int index = 0; index < source.length(); index += 1) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) continue;
            String id = item.optString("id", UUID.randomUUID().toString());
            byId.put(id, item);
        }
    }

    private static JSONArray trimHistory(JSONArray source) {
        int first = Math.max(0, source.length() - MAX_HISTORY);
        JSONArray trimmed = new JSONArray();
        for (int index = first; index < source.length(); index += 1) {
            trimmed.put(source.opt(index));
        }
        return trimmed;
    }
}
