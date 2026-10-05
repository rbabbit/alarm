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
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget showing the current time and all enabled Kala Time alarms. */
public final class KalaTimeWidgetProvider extends AppWidgetProvider {
    private static final int OPEN_REQUEST = 4101;
    private static final int CLOCK_REQUEST = 4103;
    private static final int EDIT_REQUEST = 4104;
    private static final int NEXT_CARD_REQUEST = 4105;
    private static final String ACTION_REFRESH_CLOCK = "com.rbabbit.alarm.ACTION_REFRESH_WIDGET_CLOCK";
    private static final String ACTION_NEXT_ALARM_CARD =
            "com.rbabbit.alarm.ACTION_NEXT_WIDGET_ALARM_CARD";
    private static final String WIDGET_STATE_PREFS = "kala_time_widget_state";
    private static final String CARD_INDEX_PREFIX = "card_index_";

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
        if (ACTION_NEXT_ALARM_CARD.equals(intent.getAction())) {
            int widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                int alarmCount = KalaTimeWidgetData.enabledAlarms(context).size();
                if (alarmCount > 0) {
                    int nextIndex = (readCardIndex(context, widgetId) + 1) % alarmCount;
                    writeCardIndex(context, widgetId, nextIndex);
                    updateWidget(context, AppWidgetManager.getInstance(context), widgetId);
                }
            }
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
        int alarmCount = KalaTimeWidgetData.enabledAlarms(context).size();
        int cardIndex = alarmCount == 0 ? 0 : readCardIndex(context, widgetId) % alarmCount;

        Intent serviceIntent = new Intent(context, KalaTimeWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("kala-time-widget://alarms/" + widgetId
                        + "/" + AlarmStore.getAlarmRevision(context)));
        views.setRemoteAdapter(R.id.widget_alarm_cards, serviceIntent);
        views.setEmptyView(R.id.widget_alarm_cards, R.id.widget_alarm_empty);
        views.setDisplayedChild(R.id.widget_alarm_cards, cardIndex);

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, OPEN_REQUEST, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent editPendingIntent = PendingIntent.getActivity(
                context, EDIT_REQUEST, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.widget_alarm_cards, editPendingIntent);
        views.setOnClickPendingIntent(R.id.widget_clock_column, openPendingIntent);
        views.setViewVisibility(
                R.id.widget_alarm_next,
                alarmCount > 1 ? View.VISIBLE : View.GONE);
        if (alarmCount > 1) {
            views.setOnClickPendingIntent(
                    R.id.widget_alarm_next,
                    nextCardPendingIntent(context, widgetId));
        }

        manager.updateAppWidget(widgetId, views);
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_alarm_cards);
    }

    private static int readCardIndex(Context context, int widgetId) {
        return context.getSharedPreferences(WIDGET_STATE_PREFS, Context.MODE_PRIVATE)
                .getInt(CARD_INDEX_PREFIX + widgetId, 0);
    }

    private static void writeCardIndex(Context context, int widgetId, int index) {
        context.getSharedPreferences(WIDGET_STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(CARD_INDEX_PREFIX + widgetId, index)
                .apply();
    }

    private static PendingIntent nextCardPendingIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, KalaTimeWidgetProvider.class)
                .setAction(ACTION_NEXT_ALARM_CARD)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("kala-time-widget://next/" + widgetId));
        return PendingIntent.getBroadcast(
                context,
                NEXT_CARD_REQUEST + widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
