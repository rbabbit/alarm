package com.rbabbit.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Handles notification Stop and Snooze actions while the UI is closed. */
public final class AlarmActionReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP = "com.rbabbit.alarm.ACTION_STOP_ALARM";
    public static final String ACTION_SNOOZE = "com.rbabbit.alarm.ACTION_SNOOZE_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        String alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID);
        if (ACTION_SNOOZE.equals(intent.getAction())) {
            int snoozeIndex = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_INDEX, 0);
            performSnooze(context, alarmId, snoozeIndex);
        } else if (ACTION_STOP.equals(intent.getAction())) {
            performStop(context, alarmId);
        }
    }

    public static void performStop(Context context, String alarmId) {
        JSONObject alarm = AlarmStore.findAlarm(context, alarmId);
        if (alarm == null) {
            stopRinging(context, alarmId);
            return;
        }
        // Stopping an occurrence must not change the user's enabled preference.
        // The main-screen switch is user-controlled; scheduling follows that state.
        long nextAtMs = AlarmScheduler.scheduleNextBaseline(context, alarm, System.currentTimeMillis());
        AlarmStore.appendHistory(context, alarm, "stopped");
        stopRinging(context, alarmId);
        NotificationHelper.showNextAlarm(context, alarm, nextAtMs);
    }

    public static void performSnooze(Context context, String alarmId, int snoozeIndex) {
        JSONObject alarm = AlarmStore.findAlarm(context, alarmId);
        if (alarm == null) {
            stopRinging(context, alarmId);
            return;
        }
        int minutes = AlarmScheduler.nextSnoozeMinutes(alarm, snoozeIndex);
        if (minutes <= 0) {
            performStop(context, alarmId);
            return;
        }
        long nextAtMs = AlarmScheduler.scheduleSnooze(context, alarm, minutes, snoozeIndex + 1);
        AlarmStore.appendHistory(context, alarm, "snoozed " + minutes + " minutes");
        stopRinging(context, alarmId);
        NotificationHelper.showNextAlarm(context, alarm, nextAtMs);
    }

    private static void stopRinging(Context context, String alarmId) {
        AlarmRingingService.clearActive(context, alarmId);
        context.stopService(new Intent(context, AlarmRingingService.class));
        NotificationHelper.cancel(context, alarmId);
    }
}
