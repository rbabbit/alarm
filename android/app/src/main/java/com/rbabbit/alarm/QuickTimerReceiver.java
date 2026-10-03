package com.rbabbit.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/** Wakes the app process at a Multi-Timer deadline. */
public final class QuickTimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!QuickTimerScheduler.ACTION_TRIGGER.equals(intent.getAction())) return;
        String timerId = intent.getStringExtra(QuickTimerScheduler.EXTRA_TIMER_ID);
        JSONObject timer = QuickTimerStore.find(context, timerId);
        if (timer == null || !"running".equals(timer.optString("state"))) return;

        long deadline = timer.optLong("endsAtMs", 0);
        if (deadline > System.currentTimeMillis() + 500) {
            QuickTimerScheduler.syncAll(context);
            return;
        }

        try {
            timer.put("state", "ringing");
            timer.put("remainingSeconds", 0);
            timer.remove("endsAtMs");
        } catch (Exception ignored) {
            return;
        }
        QuickTimerStore.update(context, timer);
        AlarmStore.appendHistory(context, timer, "ringing");
        QuickTimerScheduler.broadcastStateChanged(context);
        startRinging(context, timer);
    }

    static void startRinging(Context context, JSONObject timer) {
        Intent service = new Intent(context, QuickTimerRingingService.class)
                .putExtra(QuickTimerScheduler.EXTRA_TIMER_ID, timer.optString("id"));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
        } catch (RuntimeException error) {
            QuickTimerNotificationHelper.show(context, timer);
        }
    }
}
