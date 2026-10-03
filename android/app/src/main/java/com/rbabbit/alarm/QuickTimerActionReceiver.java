package com.rbabbit.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Handles native notification actions for Multi-Timer. */
public final class QuickTimerActionReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP = "com.rbabbit.alarm.ACTION_STOP_QUICK_TIMER";
    public static final String ACTION_SNOOZE = "com.rbabbit.alarm.ACTION_SNOOZE_QUICK_TIMER";

    @Override
    public void onReceive(Context context, Intent intent) {
        String timerId = intent.getStringExtra(QuickTimerScheduler.EXTRA_TIMER_ID);
        if (ACTION_STOP.equals(intent.getAction())) stop(context, timerId);
        if (ACTION_SNOOZE.equals(intent.getAction())) snooze(context, timerId);
    }

    public static void stop(Context context, String timerId) {
        JSONObject timer = QuickTimerStore.remove(context, timerId);
        QuickTimerScheduler.cancel(context, timerId);
        if (timer != null) AlarmStore.appendHistory(context, timer, "stopped");
        stopRinging(context, timerId);
    }

    public static void snooze(Context context, String timerId) {
        JSONObject timer = QuickTimerStore.find(context, timerId);
        if (timer == null) {
            stopRinging(context, timerId);
            return;
        }
        try {
            timer.put("state", "running");
            timer.put("snoozeCount", timer.optInt("snoozeCount", 0) + 1);
            timer.put("remainingSeconds", 5 * 60);
            timer.put("endsAtMs", System.currentTimeMillis() + 5 * 60_000L);
        } catch (Exception ignored) {
            return;
        }
        QuickTimerStore.update(context, timer);
        AlarmStore.appendHistory(context, timer, "snoozed 5 minutes");
        stopRinging(context, timerId);
        QuickTimerScheduler.syncAll(context);
        QuickTimerScheduler.broadcastStateChanged(context);
    }

    private static void stopRinging(Context context, String timerId) {
        context.stopService(new Intent(context, QuickTimerRingingService.class));
        QuickTimerNotificationHelper.cancel(context, timerId);
        QuickTimerScheduler.broadcastStateChanged(context);
    }
}
