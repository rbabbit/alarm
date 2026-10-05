package com.rbabbit.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget showing the current time and all enabled Kala Time alarms. */
public final class KalaTimeWidgetProvider extends AppWidgetProvider {
    private static final int OPEN_REQUEST = 4101;
    private static final int CLOCK_REQUEST = 4103;
    private static final int EDIT_REQUEST = 4104;
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
        int layoutId = isCompactWidget(context, widgetId)
                ? R.layout.widget_kala_time_compact
                : R.layout.widget_kala_time;
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutId);
        String currentDate = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(new Date());
        views.setTextViewText(R.id.widget_current_date, currentDate);

        Intent serviceIntent = new Intent(context, KalaTimeWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("kala-time-widget://alarms/" + widgetId
                        + "/" + AlarmStore.getAlarmRevision(context)));
        views.setRemoteAdapter(R.id.widget_alarm_list, serviceIntent);
        views.setEmptyView(R.id.widget_alarm_list, R.id.widget_alarm_empty);

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, OPEN_REQUEST, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent editPendingIntent = PendingIntent.getActivity(
                context, EDIT_REQUEST, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.widget_alarm_list, editPendingIntent);
        views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent);

        manager.updateAppWidget(widgetId, views);
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_alarm_list);
    }

    /**
     * Android reports the current widget bounds in dp through AppWidgetOptions. The compact
     * layout is used only for a deliberately short widget; the normal default layout remains
     * unchanged for the standard 4x3 placement.
     */
    static boolean isCompactWidget(Context context, int widgetId) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false;
        Bundle options = AppWidgetManager.getInstance(context)
                .getAppWidgetOptions(widgetId);
        int heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        if (heightDp <= 0) {
            heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        }
        return heightDp > 0 && heightDp < 160;
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

}
