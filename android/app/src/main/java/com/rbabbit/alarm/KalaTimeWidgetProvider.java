package com.rbabbit.alarm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget showing the next enabled Kala Time alarm. */
public final class KalaTimeWidgetProvider extends AppWidgetProvider {
    private static final int OPEN_REQUEST = 4101;
    private static final int ADD_REQUEST = 4102;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int widgetId : widgetIds) updateWidget(context, manager, widgetId);
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
        NextAlarm next = nextAlarm(context);
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
        if (bestAt == Long.MAX_VALUE) return new NextAlarm("No enabled alarms", "Set an alarm in Kala Time");
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(bestAt));
        return new NextAlarm(time, bestName);
    }

    private static final class NextAlarm {
        final String time;
        final String name;

        NextAlarm(String time, String name) {
            this.time = time;
            this.name = name;
        }
    }
}
