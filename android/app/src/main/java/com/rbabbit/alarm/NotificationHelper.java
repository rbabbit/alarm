package com.rbabbit.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;

import org.json.JSONObject;

/** Creates the native notification card used when an alarm is ringing. */
public final class NotificationHelper {
    public static final String CHANNEL_ID = "alarm_ringing";
    private static final String FALLBACK_CHANNEL_ID = "alarm_fallback";
    private static final int NOTIFICATION_ID_BASE = 4101;

    private NotificationHelper() { }

    public static int notificationId(String alarmId) {
        return NOTIFICATION_ID_BASE + (alarmId == null ? 0 : (alarmId.hashCode() & 0x0fffffff));
    }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Alarms",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Ringing alarms and alarm controls");
        channel.setSound(null, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 350, 250, 350});
        manager.createNotificationChannel(channel);

        NotificationChannel fallback = new NotificationChannel(
                FALLBACK_CHANNEL_ID,
                "Alarm fallback alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        fallback.setDescription("Fallback sound for alarms when the ringing service cannot start");
        fallback.setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        fallback.enableVibration(true);
        fallback.setVibrationPattern(new long[]{0, 350, 250, 350});
        manager.createNotificationChannel(fallback);
    }

    public static Notification buildAlarmNotification(Context context, JSONObject alarm, long occurrenceAtMs, int snoozeIndex) {
        return buildAlarmNotification(context, alarm, occurrenceAtMs, snoozeIndex, CHANNEL_ID);
    }

    private static Notification buildAlarmNotification(Context context, JSONObject alarm, long occurrenceAtMs, int snoozeIndex, String channelId) {
        createChannel(context);
        String alarmId = alarm.optString("id");
        String name = alarm.optString("name", "Alarm");
        int nextSnooze = AlarmScheduler.nextSnoozeMinutes(alarm, snoozeIndex);
        Intent mainIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, AlarmScheduler.requestCode(alarmId), mainIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channelId)
                : new Notification.Builder(context).setPriority(Notification.PRIORITY_MAX);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(name)
                .setContentText("Alarm ringing")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(contentIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter(Math.max(1, alarm.optInt("durationSeconds", 60)) * 1000L);
        }

        if (alarm.optBoolean("snoozeEnabled", false) && nextSnooze > 0) {
            Intent snoozeIntent = new Intent(context, AlarmActionReceiver.class)
                    .setAction(AlarmActionReceiver.ACTION_SNOOZE)
                    .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    .putExtra(AlarmScheduler.EXTRA_SNOOZE_INDEX, snoozeIndex);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Snooze " + nextSnooze + " min",
                    PendingIntent.getBroadcast(context, AlarmScheduler.requestCode(alarmId) + 1, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            ).build());
        }

        Intent stopIntent = new Intent(context, AlarmActionReceiver.class)
                .setAction(AlarmActionReceiver.ACTION_STOP)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getBroadcast(context, AlarmScheduler.requestCode(alarmId) + 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
        ).build());

        return builder.build();
    }

    public static void show(Context context, JSONObject alarm, long occurrenceAtMs, int snoozeIndex) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            String alarmId = alarm.optString("id");
            manager.notify(notificationId(alarmId), buildAlarmNotification(context, alarm, occurrenceAtMs, snoozeIndex, FALLBACK_CHANNEL_ID));
        }
    }

    public static void cancel(Context context, String alarmId) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(notificationId(alarmId));
    }
}
