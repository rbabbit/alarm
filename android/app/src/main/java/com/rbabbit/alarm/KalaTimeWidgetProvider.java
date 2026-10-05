package com.rbabbit.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget showing the next enabled Kala Time alarm. */
public final class KalaTimeWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "KalaTimeWidget";
    private static final int OPEN_REQUEST = 4101;
    private static final int ADD_REQUEST = 4102;
    private static final int CLOCK_REQUEST = 4103;
    private static final String ACTION_REFRESH_CLOCK = "com.rbabbit.alarm.ACTION_REFRESH_WIDGET_CLOCK";

    @Override
    public void onEnabled(Context context) {
        scheduleClockRefresh(context);
    }

    @Override
    public void onDisabled(Context context) {
        cancelClockRefresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH_CLOCK.equals(intent.getAction())) {
            refresh(context);
            scheduleClockRefresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        scheduleClockRefresh(context);
        for (int widgetId : widgetIds) updateWidget(context, manager, widgetId);
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context, AppWidgetManager manager, int widgetId, Bundle newOptions) {
        updateWidget(context, manager, widgetId);
    }

    /** Refreshes every placed widget after the native alarm schedule changes. */
    public static void refresh(Context context) {
        Context appContext = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(appContext);
        ComponentName component = new ComponentName(appContext, KalaTimeWidgetProvider.class);
        int[] widgetIds = manager.getAppWidgetIds(component);
        for (int widgetId : widgetIds) updateWidget(appContext, manager, widgetId);
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_kala_time);
        String currentDate = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(new Date());
        String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date());
        NextAlarm next;
        try {
            next = nextAlarm(context);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to calculate the next alarm for widget " + widgetId, exception);
            next = new NextAlarm("", "--:--", "Open Kala Time to set an alarm");
        }
        views.setTextViewText(R.id.widget_current_date, currentDate);
        views.setTextViewText(R.id.widget_current_time, currentTime);
        views.setTextViewText(R.id.widget_next_date, next.date);
        views.setTextViewText(R.id.widget_next_time, next.time);
        views.setTextViewText(R.id.widget_next_name, next.name);

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, OPEN_REQUEST, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent);

        Intent addIntent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_ADD_ALARM, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent addPendingIntent = PendingIntent.getActivity(
                context, ADD_REQUEST, addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_add_alarm, addPendingIntent);
        views.setOnClickPendingIntent(R.id.widget_open_alarms, openPendingIntent);
        manager.updateAppWidget(widgetId, views);
    }

    private static NextAlarm nextAlarm(Context context) {
        long now = System.currentTimeMillis();
        long bestAt = Long.MAX_VALUE;
        String bestName = "No enabled alarms";
        JSONArray alarms = AlarmStore.getAlarms(context);
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm == null || !alarm.optBoolean("enabled", false)) continue;
            long occurrence = AlarmScheduler.nextOccurrenceForWidget(alarm, now);
            if (occurrence > now && occurrence < bestAt) {
                bestAt = occurrence;
                bestName = alarm.optString("name", "Alarm");
            }
        }
        if (bestAt == Long.MAX_VALUE) {
            return new NextAlarm("", "--:--", "Set an alarm in Kala Time");
        }
        Date nextDate = new Date(bestAt);
        String date = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(nextDate);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextDate);
        return new NextAlarm(date, time, bestName);
    }

    private static void scheduleClockRefresh(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        long nextMinute = ((System.currentTimeMillis() / 60000L) + 1L) * 60000L;
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC,
                nextMinute,
                clockPendingIntent(context));
    }

    private static void cancelClockRefresh(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(clockPendingIntent(context));
    }

    private static PendingIntent clockPendingIntent(Context context) {
        Intent intent = new Intent(context, KalaTimeWidgetProvider.class)
                .setAction(ACTION_REFRESH_CLOCK);
        return PendingIntent.getBroadcast(
                context,
                CLOCK_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static final class NextAlarm {
        final String date;
        final String time;
        final String name;

        NextAlarm(String date, String time, String name) {
            this.date = date;
            this.time = time;
            this.name = name;
        }
    }
}
