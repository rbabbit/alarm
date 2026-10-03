package com.rbabbit.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Schedules Multi-Timer deadlines with Android AlarmManager. */
public final class QuickTimerScheduler {
    public static final String ACTION_TRIGGER = "com.rbabbit.alarm.ACTION_TRIGGER_QUICK_TIMER";
    public static final String ACTION_STATE_CHANGED = "com.rbabbit.alarm.ACTION_QUICK_TIMER_STATE_CHANGED";
    public static final String EXTRA_TIMER_ID = "quick_timer_id";
    private static final String DATA_PREFIX = "quick-timer://com.rbabbit.alarm/";

    private QuickTimerScheduler() { }

    public static void syncAll(Context context) {
        cancelKnownTimers(context);
        JSONArray timers = QuickTimerStore.getTimers(context);
        long now = System.currentTimeMillis();
        for (int index = 0; index < timers.length(); index += 1) {
            JSONObject timer = timers.optJSONObject(index);
            if (timer == null) continue;
            if ("running".equals(timer.optString("state"))) {
                long deadline = timer.optLong("endsAtMs", 0);
                if (deadline > 0) scheduleAt(context, timer, Math.max(deadline, now + 1000));
            } else if ("ringing".equals(timer.optString("state"))) {
                QuickTimerReceiver.startRinging(context, timer);
            }
        }
    }

    public static void cancelKnownTimers(Context context) {
        JSONArray timers = QuickTimerStore.getTimers(context);
        for (int index = 0; index < timers.length(); index += 1) {
            JSONObject timer = timers.optJSONObject(index);
            if (timer != null) cancel(context, timer.optString("id"));
        }
    }

    public static void cancel(Context context, String timerId) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null && timerId != null && !timerId.isEmpty()) {
            manager.cancel(triggerIntent(context, timerId));
        }
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    public static int requestCode(String timerId) {
        return 1_000_000 + (timerId == null ? 0 : (timerId.hashCode() & 0x000fffff));
    }

    private static void scheduleAt(Context context, JSONObject timer, long triggerAtMs) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        String timerId = timer.optString("id");
        PendingIntent operation = triggerIntent(context, timerId);
        try {
            if (canScheduleExactAlarms(context)) {
                Intent showIntent = new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent show = PendingIntent.getActivity(context, requestCode(timerId), showIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAtMs, show), operation);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

    private static PendingIntent triggerIntent(Context context, String timerId) {
        Intent intent = new Intent(context, QuickTimerReceiver.class)
                .setAction(ACTION_TRIGGER)
                .setData(Uri.parse(DATA_PREFIX + Uri.encode(timerId)))
                .putExtra(EXTRA_TIMER_ID, timerId);
        return PendingIntent.getBroadcast(context, requestCode(timerId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void broadcastStateChanged(Context context) {
        context.sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(context.getPackageName()));
    }
}
