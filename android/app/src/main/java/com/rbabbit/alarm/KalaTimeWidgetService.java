package com.rbabbit.alarm;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** Supplies the scrollable enabled-alarm collection rendered by the launcher widget. */
public final class KalaTimeWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new AlarmViewsFactory(
                getApplicationContext(),
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    private static final class AlarmViewsFactory implements RemoteViewsFactory {
        private final Context context;
        private final int widgetId;
        private List<KalaTimeWidgetData.AlarmRow> rows = new ArrayList<>();

        AlarmViewsFactory(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override
        public void onCreate() {
            reload();
        }

        @Override
        public void onDataSetChanged() {
            reload();
        }

        @Override
        public void onDestroy() {
            rows.clear();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= rows.size()) return null;
            KalaTimeWidgetData.AlarmRow alarm = rows.get(position);
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_alarm_row);
            row.setTextViewText(R.id.widget_alarm_time, alarm.time);
            row.setTextViewText(R.id.widget_alarm_date, alarm.date);
            row.setTextViewText(R.id.widget_alarm_name, alarm.name);
            Intent fillInIntent = new Intent()
                    .putExtra(MainActivity.EXTRA_OPEN_ALARM_ID, alarm.alarmId);
            row.setOnClickFillInIntent(R.id.widget_alarm_row, fillInIntent);
            return row;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= rows.size()) return -1L;
            return rows.get(position).alarmId.hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        private void reload() {
            rows = KalaTimeWidgetData.enabledAlarms(context);
        }
    }
}
