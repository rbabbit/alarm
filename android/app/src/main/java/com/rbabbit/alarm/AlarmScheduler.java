package com.rbabbit.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/** Schedules one next occurrence per alarm using Android's system alarm service. */
public final class AlarmScheduler {
    public static final String ACTION_TRIGGER = "com.rbabbit.alarm.ACTION_TRIGGER_ALARM";
    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_OCCURRENCE_AT = "occurrence_at_ms";
    public static final String EXTRA_SNOOZE_INDEX = "snooze_index";
    private static final String DATA_PREFIX = "alarm://com.rbabbit.alarm/";

    private AlarmScheduler() { }

    public static void syncAll(Context context) {
        cancelKnownAlarms(context);
        JSONArray alarms = AlarmStore.getAlarms(context);
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm != null && alarm.optBoolean("enabled", false)) {
                scheduleNextBaseline(context, alarm, System.currentTimeMillis());
            }
        }
    }

    public static void cancelKnownAlarms(Context context) {
        JSONArray alarms = AlarmStore.getAlarms(context);
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm != null) cancelAlarm(context, alarm.optString("id"));
        }
    }

    public static void cancelAlarm(Context context, String alarmId) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null || alarmId == null || alarmId.isEmpty()) return;
        manager.cancel(triggerIntent(context, alarmId, "baseline", 0, 0));
        manager.cancel(triggerIntent(context, alarmId, "snooze", 0, 0));
    }

    public static int requestCode(String alarmId) {
        return alarmId == null ? 1 : (alarmId.hashCode() & 0x7fffffff);
    }

    public static void scheduleNextBaseline(Context context, JSONObject alarm, long afterMs) {
        if (alarm == null || !alarm.optBoolean("enabled", false)) return;
        long next = nextOccurrence(alarm, Math.max(afterMs, System.currentTimeMillis() - 1000));
        if (next > 0) scheduleAt(context, alarm, next, 0, "baseline");
    }

    public static void scheduleSnooze(Context context, JSONObject alarm, int minutes, int nextIndex) {
        if (alarm == null || minutes <= 0) return;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(triggerIntent(context, alarm.optString("id"), "baseline", 0, 0));
        scheduleAt(context, alarm, System.currentTimeMillis() + minutes * 60_000L, nextIndex, "snooze");
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    public static int nextSnoozeMinutes(JSONObject alarm, int snoozeIndex) {
        if (alarm == null || !alarm.optBoolean("snoozeEnabled", false)) return -1;
        JSONArray sequence = alarm.optJSONArray("snoozeSequenceMinutes");
        if (sequence == null || sequence.length() == 0) return -1;
        if (snoozeIndex < sequence.length()) return sequence.optInt(snoozeIndex, -1);
        String exhausted = alarm.optString("afterSnoozeExhausted", "dismiss");
        if ("repeat-last".equals(exhausted)) return sequence.optInt(sequence.length() - 1, -1);
        if ("repeat-sequence".equals(exhausted)) return sequence.optInt(0, -1);
        return -1;
    }

    private static void scheduleAt(Context context, JSONObject alarm, long triggerAtMs, int snoozeIndex, String kind) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        String alarmId = alarm.optString("id");
        PendingIntent operation = triggerIntent(context, alarmId, kind, triggerAtMs, snoozeIndex);
        try {
            if (canScheduleExactAlarms(context)) {
                Intent showIntent = new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                AlarmManager.AlarmClockInfo clockInfo = new AlarmManager.AlarmClockInfo(triggerAtMs, PendingIntent.getActivity(
                        context, 0, showIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                manager.setAlarmClock(clockInfo, operation);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // The alarm still works without special access, but Android may defer it.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            }
        } catch (SecurityException error) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            }
        }
    }

    private static PendingIntent triggerIntent(Context context, String alarmId, String kind, long occurrenceAtMs, int snoozeIndex) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(ACTION_TRIGGER)
                .setData(Uri.parse(DATA_PREFIX + kind + "/" + Uri.encode(alarmId)))
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_OCCURRENCE_AT, occurrenceAtMs)
                .putExtra(EXTRA_SNOOZE_INDEX, snoozeIndex);
        return PendingIntent.getBroadcast(
                context,
                requestCode(alarmId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static long nextOccurrence(JSONObject alarm, long afterMs) {
        String startTime = alarm.optString("startTime", "06:00");
        String endTime = alarm.optString("endTime", "18:00");
        int start = parseMinutes(startTime);
        int end = parseMinutes(endTime);
        int interval = Math.max(1, alarm.optInt("intervalMinutes", 60));
        String frequency = alarm.optString("frequency", "several");
        Set<String> days = readDays(alarm.optJSONArray("repeatDays"));
        Calendar after = Calendar.getInstance();
        after.setTimeInMillis(afterMs);
        if ("once".equals(frequency)) {
            Calendar occurrence = (Calendar) after.clone();
            occurrence.set(Calendar.HOUR_OF_DAY, start / 60);
            occurrence.set(Calendar.MINUTE, start % 60);
            occurrence.set(Calendar.SECOND, 0);
            occurrence.set(Calendar.MILLISECOND, 0);
            if (occurrence.getTimeInMillis() <= afterMs) occurrence.add(Calendar.DAY_OF_YEAR, 1);
            return occurrence.getTimeInMillis();
        }
        for (int offset = 0; offset <= 8; offset += 1) {
            Calendar base = (Calendar) after.clone();
            base.set(Calendar.HOUR_OF_DAY, 0);
            base.set(Calendar.MINUTE, 0);
            base.set(Calendar.SECOND, 0);
            base.set(Calendar.MILLISECOND, 0);
            base.add(Calendar.DAY_OF_YEAR, offset);
            if (!days.contains(dayCode(base.get(Calendar.DAY_OF_WEEK)))) continue;

            int distance = end >= start ? end - start : 1440 - start + end;
            for (int minuteOffset = 0; minuteOffset <= distance; minuteOffset += interval) {
                Calendar occurrence = (Calendar) base.clone();
                occurrence.add(Calendar.MINUTE, start + minuteOffset);
                long timestamp = occurrence.getTimeInMillis();
                if (timestamp > afterMs) return timestamp;
            }
        }
        return -1;
    }

    private static Set<String> readDays(JSONArray values) {
        Set<String> days = new HashSet<>();
        if (values == null) return days;
        for (int index = 0; index < values.length(); index += 1) days.add(values.optString(index));
        return days;
    }

    private static String dayCode(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY: return "MO";
            case Calendar.TUESDAY: return "TU";
            case Calendar.WEDNESDAY: return "WE";
            case Calendar.THURSDAY: return "TH";
            case Calendar.FRIDAY: return "FR";
            case Calendar.SATURDAY: return "SA";
            default: return "SU";
        }
    }

    private static int parseMinutes(String value) {
        try {
            String[] parts = value.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
