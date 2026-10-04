package com.rbabbit.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/** Receives the system alarm even when the app process was not running. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AlarmScheduler.ACTION_TRIGGER.equals(intent.getAction())) return;
        String alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID);
        JSONObject alarm = AlarmStore.findAlarm(context, alarmId);
        if (alarm == null || !alarm.optBoolean("enabled", false)) return;

        NotificationHelper.cancelNextAlarm(context, alarmId);

        long occurrenceAtMs = intent.getLongExtra(AlarmScheduler.EXTRA_OCCURRENCE_AT, System.currentTimeMillis());
        int snoozeIndex = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_INDEX, 0);
        if (snoozeIndex == 0 && !"once".equals(alarm.optString("frequency"))) {
            AlarmScheduler.scheduleNextBaseline(context, alarm, occurrenceAtMs);
        }
        AlarmStore.appendHistory(context, alarm, snoozeIndex == 0 ? "started" : "snoozed ringing");
        Intent serviceIntent = new Intent(context, AlarmRingingService.class)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmScheduler.EXTRA_OCCURRENCE_AT, occurrenceAtMs)
                .putExtra(AlarmScheduler.EXTRA_SNOOZE_INDEX, snoozeIndex);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent);
            else context.startService(serviceIntent);
        } catch (RuntimeException error) {
            // The notification remains a usable fallback if a manufacturer blocks the service start.
            NotificationHelper.show(context, alarm, occurrenceAtMs, snoozeIndex);
        }
    }
}
