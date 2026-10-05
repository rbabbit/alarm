package com.rbabbit.alarm;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared alarm data used by the native home-screen widget collection. */
public final class KalaTimeWidgetData {
    private KalaTimeWidgetData() { }

    public static List<AlarmRow> enabledAlarms(Context context) {
        long now = System.currentTimeMillis();
        List<AlarmRow> rows = new ArrayList<>();
        JSONArray alarms = AlarmStore.getAlarms(context);
        for (int index = 0; index < alarms.length(); index += 1) {
            JSONObject alarm = alarms.optJSONObject(index);
            if (alarm == null || !alarm.optBoolean("enabled", false)) continue;

            long occurrence = AlarmScheduler.nextOccurrenceForWidget(alarm, now);
            if (occurrence <= now) continue;

            Date nextDate = new Date(occurrence);
            rows.add(new AlarmRow(
                    alarm.optString("id"),
                    occurrence,
                    new SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextDate),
                    new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(nextDate),
                    alarm.optString("name", "Alarm").trim()));
        }
        rows.sort(Comparator
                .comparingLong((AlarmRow row) -> row.occurrenceAt)
                .thenComparing(row -> row.alarmId, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    public static final class AlarmRow {
        public final String alarmId;
        public final long occurrenceAt;
        public final String time;
        public final String date;
        public final String name;

        AlarmRow(String alarmId, long occurrenceAt, String time, String date, String name) {
            this.alarmId = alarmId;
            this.occurrenceAt = occurrenceAt;
            this.time = time;
            this.date = date;
            this.name = name.isEmpty() ? "Alarm" : name;
        }
    }
}
