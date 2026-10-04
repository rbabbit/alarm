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

/** Native high-priority notification and actions for Multi-Timer ringing. */
public final class QuickTimerNotificationHelper {
    public static final String CHANNEL_ID = "quick_timer_ringing_v2";
    private static final int NOTIFICATION_ID_BASE = 5201;

    private QuickTimerNotificationHelper() { }

    public static int notificationId(String timerId) {
        return NOTIFICATION_ID_BASE + (timerId == null ? 0 : (timerId.hashCode() & 0x0fffffff));
    }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Multi-Timers", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Ringing Multi-Timers and timer controls");
        channel.setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 350, 250, 350});
        manager.createNotificationChannel(channel);
    }

    public static boolean areNotificationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager == null || manager.areNotificationsEnabled();
    }

    public static Notification build(Context context, JSONObject timer) {
        createChannel(context);
        String timerId = timer.optString("id");
        String name = timer.optString("label", "Timer");
        PendingIntent content = PendingIntent.getActivity(context, notificationId(timerId),
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent snooze = new Intent(context, QuickTimerActionReceiver.class)
                .setAction(QuickTimerActionReceiver.ACTION_SNOOZE)
                .putExtra(QuickTimerScheduler.EXTRA_TIMER_ID, timerId);
        Intent stop = new Intent(context, QuickTimerActionReceiver.class)
                .setAction(QuickTimerActionReceiver.ACTION_STOP)
                .putExtra(QuickTimerScheduler.EXTRA_TIMER_ID, timerId);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context).setPriority(Notification.PRIORITY_MAX);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(name)
                .setContentText("Timer ringing")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(content)
                .setTimeoutAfter(Math.max(1, timer.optInt("ringDurationSeconds", 60)) * 1000L)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Snooze 5 min",
                        PendingIntent.getBroadcast(context, notificationId(timerId) + 1, snooze,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                        PendingIntent.getBroadcast(context, notificationId(timerId) + 2, stop,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        return builder.build();
    }

    public static void show(Context context, JSONObject timer) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(notificationId(timer.optString("id")), build(context, timer));
    }

    public static void cancel(Context context, String timerId) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(notificationId(timerId));
    }
}
