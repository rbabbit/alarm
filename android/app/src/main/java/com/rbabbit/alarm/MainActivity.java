package com.rbabbit.alarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.location.Criteria;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import java.text.SimpleDateFormat;

/** Native Android UI. No WebView, JavaScript bridge, or CSS controls are used here. */
public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 71;
    private static final int NOTIFICATION_REQUEST = 72;
    private static final long MAX_TIMER_MINUTES = 30L * 24L * 60L;
    private static final String PREFS_NAME = "app_preferences";
    private static final String EXACT_ALARM_PROMPT_SHOWN = "exact_alarm_prompt_shown";
    private static final String[] DAY_CODES = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};
    private static final String[] DAY_LABELS = {"M", "T", "W", "T", "F", "S", "S"};
    private static final int INK = Color.rgb(20, 20, 20);
    private static final int MUTED = Color.rgb(105, 105, 105);
    private static final int PAGE = Color.rgb(247, 247, 247);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private FrameLayout content;
    private LinearLayout bottomNav;
    private TextView pageTitle;
    private boolean editing;
    private JSONObject editingAlarm;
    private String currentPage = "alarms";
    private Runnable ticker;
    private TextView weatherStatus;
    private TextView weatherCoordinates;
    private LinearLayout weatherForecast;
    private int weatherRequestGeneration;
    private LinearLayout timerList;
    private boolean exactAlarmAccess;
    private boolean exactAlarmPromptShowing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        NotificationHelper.createChannel(this);
        QuickTimerNotificationHelper.createChannel(this);
        AlarmScheduler.syncAll(this);
        QuickTimerScheduler.syncAll(this);
        exactAlarmAccess = AlarmScheduler.canScheduleExactAlarms(this);
        buildShell();
        showAlarms();
        maybeRequestExactAlarmAccess(false);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean currentExactAlarmAccess = AlarmScheduler.canScheduleExactAlarms(this);
        if (currentExactAlarmAccess && !exactAlarmAccess) {
            AlarmScheduler.syncAll(this);
            QuickTimerScheduler.syncAll(this);
        }
        exactAlarmAccess = currentExactAlarmAccess;
        if (!editing && "timers".equals(currentPage)) showTimers();
        if (!editing && "alarms".equals(currentPage)) showAlarms();
        if (ticker == null) {
            ticker = new Runnable() {
                @Override public void run() {
                    if (!editing && "timers".equals(currentPage)) {
                        reconcileExpiredTimers();
                        refreshTimerList();
                    }
                    handler.postDelayed(this, 1000L);
                }
            };
            handler.post(ticker);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST) {
            if (!NotificationHelper.areNotificationsEnabled(this)) showNotificationSettingsPrompt();
            return;
        }
        if (requestCode != LOCATION_REQUEST || weatherStatus == null || weatherForecast == null) return;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        if (granted) {
            requestWeather(weatherStatus, weatherForecast);
        } else {
            weatherStatus.setText("Location permission was not granted.");
        }
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(22), dp(12), dp(18), dp(12));
        toolbar.setBackgroundColor(Color.WHITE);
        pageTitle = text("Alarms", 32, INK);
        toolbar.addView(pageTitle, new LinearLayout.LayoutParams(0, dp(64), 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(88)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomNav = buildBottomNav();
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(82)));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public void onBackPressed() {
        if (editing) {
            showAlarms();
        } else {
            super.onBackPressed();
        }
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(Color.WHITE);
        nav.setPadding(0, dp(2), 0, dp(2));
        addNavButton(nav, com.rbabbit.alarm.R.drawable.ic_timer, "Multi-Timer", "timers");
        addNavButton(nav, com.rbabbit.alarm.R.drawable.ic_list, "Alarms", "alarms");
        addNavButton(nav, com.rbabbit.alarm.R.drawable.ic_counter, "Counter", "counter");
        addNavButton(nav, com.rbabbit.alarm.R.drawable.ic_weather, "Weather", "weather");
        return nav;
    }

    private void addNavButton(LinearLayout nav, int icon, String label, String page) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2), dp(2), dp(2), dp(2));
        button.setTag(page);
        button.setBackgroundColor("alarms".equals(page) ? Color.rgb(242, 242, 242) : Color.TRANSPARENT);
        button.setContentDescription(label);
        ImageView iconView = new ImageView(this);
        iconView.setImageResource(icon);
        iconView.setColorFilter(INK);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        TextView labelView = text(label, 12, INK);
        labelView.setGravity(Gravity.CENTER);
        button.addView(iconView, new LinearLayout.LayoutParams(-1, dp(34)));
        button.addView(labelView, new LinearLayout.LayoutParams(-1, dp(28)));
        button.setOnClickListener(view -> {
            if ("timers".equals(page)) showTimers();
            else if ("alarms".equals(page)) showAlarms();
            else if ("counter".equals(page)) showCounter();
            else showWeather();
        });
        nav.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void setPage(String title, View pageView, boolean showNavigation) {
        pageTitle.setText(title);
        content.removeAllViews();
        content.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        bottomNav.setVisibility(showNavigation ? View.VISIBLE : View.GONE);
        for (int index = 0; index < bottomNav.getChildCount(); index += 1) {
            View child = bottomNav.getChildAt(index);
            child.setBackgroundColor(showNavigation && currentPage.equals(child.getTag())
                    ? Color.rgb(242, 242, 242) : Color.TRANSPARENT);
        }
        editing = !showNavigation;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        scroll.addView(child, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout pageColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(24));
        return column;
    }

    private void showAlarms() {
        currentPage = "alarms";
        Button add = headerButton("+");
        add.setOnClickListener(view -> showEditAlarm(defaultAlarm()));
        Button calendarAdd = calendarAddButton();
        calendarAdd.setOnClickListener(view -> addCalendarAlarm());
        resetToolbar("Alarms", headerActions(add, calendarAdd));
        LinearLayout column = pageColumn();
        JSONArray alarms = AlarmStore.getAlarms(this);
        if (alarms.length() == 0) {
            column.addView(empty("No alarms yet.\nUse + to create your first alarm."), new LinearLayout.LayoutParams(-1, dp(260)));
        } else {
            for (int index = 0; index < alarms.length(); index += 1) {
                JSONObject alarm = alarms.optJSONObject(index);
                if (alarm != null) column.addView(alarmCard(alarm));
            }
        }
        setPage("Alarms", scroll(column), true);
    }

    private void resetToolbar(String title, View action) {
        ViewGroup toolbar = (ViewGroup) root.getChildAt(0);
        toolbar.removeAllViews();
        pageTitle.setText(title);
        toolbar.addView(pageTitle, new LinearLayout.LayoutParams(0, dp(64), 1));
        if (action != null) {
            int width = action instanceof ViewGroup ? dp(120) : dp(58);
            toolbar.addView(action, new LinearLayout.LayoutParams(width, dp(58)));
        }
    }

    private LinearLayout alarmCard(JSONObject alarm) {
        LinearLayout card = card();
        LinearLayout top = row();
        boolean calendarAlarm = alarm.optLong("calendarAtMs", 0) > 0;
        String start = alarm.optString("startTime", "06:00");
        String frequency = alarm.optString("frequency", "several");
        String range = calendarAlarm ? formatCalendarDateTime(alarm.optLong("calendarAtMs")) : start;
        if (!calendarAlarm && !"once".equals(frequency)) range += " – " + alarm.optString("endTime", "18:00");
        Button time = plainButton(range, 22);
        time.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        time.setOnClickListener(view -> showEditAlarm(alarm));
        top.addView(time, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button settings = headerButton("settings");
        settings.setContentDescription("Edit alarm");
        settings.setOnClickListener(view -> showEditAlarm(alarm));
        top.addView(settings, new LinearLayout.LayoutParams(dp(58), dp(58)));
        boolean alarmEnabled = alarm.optBoolean("enabled", false);
        Switch enabled = new Switch(this);
        enabled.setShowText(false);
        enabled.setText(null);
        enabled.setChecked(alarmEnabled);
        enabled.setContentDescription("Enable alarm");
        enabled.setOnCheckedChangeListener((button, checked) -> {
            AlarmStore.setEnabled(this, alarm.optString("id"), checked);
            AlarmScheduler.syncAll(this);
            if (checked) maybeRequestExactAlarmAccess(true);
        });
        top.addView(enabled, new LinearLayout.LayoutParams(dp(58), dp(58)));
        card.addView(top);

        LinearLayout detail = row();
        TextView name = text(alarm.optString("name", "New alarm"), 18, MUTED);
        detail.addView(name, new LinearLayout.LayoutParams(0, dp(44), 1));
        String frequencyText = calendarAlarm ? "Calendar Alarm" : ("once".equals(frequency) ? "Single Alarm" : "Repeat Alarm");
        detail.addView(text(frequencyText, 16, MUTED), new LinearLayout.LayoutParams(-2, dp(44)));
        card.addView(detail);

        if (!calendarAlarm) {
            LinearLayout days = row();
            JSONArray selected = alarm.optJSONArray("repeatDays");
            for (int index = 0; index < DAY_CODES.length; index += 1) {
                ToggleButton day = dayButton(DAY_LABELS[index]);
                day.setTag(DAY_CODES[index]);
                day.setChecked(hasDay(selected, DAY_CODES[index]));
                day.setOnClickListener(view -> {
                    try {
                        JSONArray updated = new JSONArray();
                        for (int dayIndex = 0; dayIndex < DAY_CODES.length; dayIndex += 1) {
                            View candidate = days.findViewWithTag(DAY_CODES[dayIndex]);
                            if (candidate instanceof ToggleButton && ((ToggleButton) candidate).isChecked()) updated.put(DAY_CODES[dayIndex]);
                        }
                        alarm.put("repeatDays", updated);
                        AlarmStore.upsert(this, alarm);
                        AlarmScheduler.syncAll(this);
                    } catch (JSONException ignored) { }
                });
                days.addView(day, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            card.addView(days);
        }
        return card;
    }

    private void showEditAlarm(JSONObject alarm) {
        try { editingAlarm = new JSONObject(alarm.toString()); } catch (JSONException error) { return; }
        ensureRepeatDays(editingAlarm);
        currentPage = "alarms";
        LinearLayout toolbar = (LinearLayout) root.getChildAt(0);
        while (toolbar.getChildCount() > 0) toolbar.removeViewAt(0);
        Button back = headerButton("back");
        back.setOnClickListener(view -> showAlarms());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(58), dp(64)));
        TextView title = text("Edit Alarm", 29, INK);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(64), 1));
        Button delete = headerButton("delete");
        delete.setTextSize(14);
        delete.setOnClickListener(view -> {
            AlarmRingingService.clearActive(this, editingAlarm.optString("id"));
            stopService(new android.content.Intent(this, AlarmRingingService.class));
            NotificationHelper.cancel(this, editingAlarm.optString("id"));
            AlarmScheduler.cancelAlarm(this, editingAlarm.optString("id"));
            AlarmStore.remove(this, editingAlarm.optString("id"));
            AlarmScheduler.syncAll(this);
            showAlarms();
        });
        toolbar.addView(delete, new LinearLayout.LayoutParams(dp(82), dp(58)));

        LinearLayout column = pageColumn();
        column.addView(sectionTitle("Alarm type"));
        RadioGroup type = new RadioGroup(this);
        type.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton repeat = radio("Repeat Alarm");
        RadioButton single = radio("Single Alarm");
        type.addView(repeat, new RadioGroup.LayoutParams(0, dp(56), 1));
        type.addView(single, new RadioGroup.LayoutParams(0, dp(56), 1));
        type.check("once".equals(editingAlarm.optString("frequency")) ? single.getId() : repeat.getId());
        column.addView(type, new LinearLayout.LayoutParams(-1, dp(64)));

        column.addView(sectionTitle("Alarm name"));
        EditText name = edit(editingAlarm.optString("name", "New alarm"), "Alarm name");
        column.addView(name, fieldParams());

        TextView repeatDaysTitle = sectionTitle("Repeat days");
        column.addView(repeatDaysTitle);
        LinearLayout days = row();
        JSONArray selected = editingAlarm.optJSONArray("repeatDays");
        for (int index = 0; index < DAY_CODES.length; index += 1) {
            ToggleButton day = dayButton(DAY_LABELS[index]);
            day.setTag(DAY_CODES[index]);
            day.setChecked(hasDay(selected, DAY_CODES[index]));
            days.addView(day, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
        column.addView(days);

        column.addView(sectionTitle("Alarm time"));
        LinearLayout times = row();
        Button start = plainButton(editingAlarm.optString("startTime", "06:00"), 19);
        Button end = plainButton(editingAlarm.optString("endTime", "18:00"), 19);
        times.addView(start, new LinearLayout.LayoutParams(0, dp(56), 1));
        times.addView(end, new LinearLayout.LayoutParams(0, dp(56), 1));
        column.addView(times);

        TextView intervalTitle = sectionTitle("Alarm interval (minutes)");
        column.addView(intervalTitle);
        EditText interval = edit(String.valueOf(Math.max(1, editingAlarm.optInt("intervalMinutes", 60))), "Minutes between occurrences");
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        column.addView(interval, fieldParams());

        column.addView(sectionTitle("Alarm sound"));
        Spinner sound = spinner(new String[]{"Classic", "Gentle", "Pulse", "Chime", "Digital", "Wake-up", "Loud alarm"});
        String[] soundCodes = {"classic", "gentle", "pulse", "chime", "digital", "wake-up", "loud-alarm"};
        sound.setSelection(indexOf(soundCodes, editingAlarm.optString("sound", "classic")));
        column.addView(sound, fieldParams());

        column.addView(sectionTitle("Alarm volume"));
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress((int) Math.round(Math.max(0.0, Math.min(1.0, editingAlarm.optDouble("volume", 1.0))) * 100));
        column.addView(volume, fieldParams());

        column.addView(sectionTitle("Alarm duration"));
        Spinner duration = spinner(new String[]{"30 seconds", "60 seconds", "120 seconds", "300 seconds"});
        int durationValue = editingAlarm.optInt("durationSeconds", 60);
        duration.setSelection(durationValue == 30 ? 0 : durationValue == 120 ? 2 : durationValue == 300 ? 3 : 1);
        column.addView(duration, fieldParams());

        column.addView(sectionTitle("Snooze"));
        Switch snooze = new Switch(this);
        snooze.setText("Allow snooze");
        snooze.setShowText(false);
        snooze.setTextSize(17);
        snooze.setChecked(editingAlarm.optBoolean("snoozeEnabled", true));
        column.addView(snooze, new LinearLayout.LayoutParams(-1, dp(56)));
        EditText sequence = edit(sequenceText(editingAlarm.optJSONArray("snoozeSequenceMinutes")), "Snooze minutes, e.g. 25,15,10,5");
        column.addView(sequence, fieldParams());

        column.addView(sectionTitle("Enable alarm"));
        Switch enable = new Switch(this);
        enable.setText("Enabled");
        enable.setShowText(false);
        enable.setTextSize(17);
        enable.setChecked(editingAlarm.optBoolean("enabled", true));
        column.addView(enable, new LinearLayout.LayoutParams(-1, dp(56)));

        Button save = wideButton("Save alarm");
        save.setOnClickListener(view -> {
            try {
                editingAlarm.put("name", safeName(name.getText().toString(), "New alarm"));
                boolean repeatMode = type.getCheckedRadioButtonId() == repeat.getId();
                editingAlarm.put("frequency", repeatMode ? "several" : "once");
                if (repeatMode) {
                    editingAlarm.remove("calendarAtMs");
                } else if (editingAlarm.optLong("calendarAtMs", 0) > 0) {
                    updateCalendarAlarmTime(editingAlarm, start.getText().toString());
                }
                JSONArray updatedDays = new JSONArray();
                for (int index = 0; index < DAY_CODES.length; index += 1) {
                    View candidate = days.findViewWithTag(DAY_CODES[index]);
                    if (candidate instanceof ToggleButton && ((ToggleButton) candidate).isChecked()) updatedDays.put(DAY_CODES[index]);
                }
                // Preserve the user's weekday selection for both alarm modes. A single alarm
                // does not use the days for scheduling, but the selection must remain available
                // if the user switches back to Repeat Alarm later.
                editingAlarm.put("repeatDays", updatedDays);
                editingAlarm.put("startTime", start.getText().toString());
                editingAlarm.put("endTime", end.getText().toString());
                editingAlarm.put("intervalMinutes", clampInt(interval.getText().toString(), 1, 1440, 60));
                editingAlarm.put("sound", soundCodes[sound.getSelectedItemPosition()]);
                editingAlarm.put("volume", volume.getProgress() / 100.0);
                editingAlarm.put("durationSeconds", new int[]{30, 60, 120, 300}[duration.getSelectedItemPosition()]);
                editingAlarm.put("snoozeEnabled", snooze.isChecked());
                editingAlarm.put("snoozeSequenceMinutes", parseSequence(sequence.getText().toString()));
                editingAlarm.put("enabled", enable.isChecked());
                AlarmStore.upsert(this, editingAlarm);
                AlarmScheduler.syncAll(this);
                maybeRequestExactAlarmAccess(true);
                warnIfNotificationsDisabled();
                showAlarms();
            } catch (JSONException ignored) { }
        });
        column.addView(save, new LinearLayout.LayoutParams(-1, dp(62)));

        start.setOnClickListener(view -> chooseTime(start, start.getText().toString()));
        end.setOnClickListener(view -> chooseTime(end, end.getText().toString()));
        RadioGroup.OnCheckedChangeListener typeListener = (group, checkedId) -> {
            boolean repeatMode = checkedId == repeat.getId();
            repeatDaysTitle.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
            days.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
            end.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
            intervalTitle.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
            interval.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
        };
        type.setOnCheckedChangeListener(typeListener);
        boolean repeatMode = !"once".equals(editingAlarm.optString("frequency"));
        repeatDaysTitle.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
        days.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
        end.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
        intervalTitle.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
        interval.setVisibility(repeatMode ? View.VISIBLE : View.GONE);
        setPage("Edit Alarm", scroll(column), false);
    }

    private void showTimers() {
        currentPage = "timers";
        reconcileExpiredTimers();
        Button add = headerButton("+");
        resetToolbar("Multi-Timer", add);
        LinearLayout column = pageColumn();
        EditText name = edit("", "Timer Name");
        column.addView(name, fieldParams());
        LinearLayout controls = row();
        Spinner minutes = spinner(timerMinuteLabels());
        EditText custom = edit("", "Custom minutes (1–43200)");
        custom.setInputType(InputType.TYPE_CLASS_NUMBER);
        custom.setVisibility(View.GONE);
        minutes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { custom.setVisibility(position == 60 ? View.VISIBLE : View.GONE); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        FrameLayout durationControl = new FrameLayout(this);
        durationControl.addView(minutes, new FrameLayout.LayoutParams(-1, dp(58)));
        durationControl.addView(custom, new FrameLayout.LayoutParams(-1, dp(58)));
        controls.addView(durationControl, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button start = wideButton("Start timer");
        controls.addView(start, new LinearLayout.LayoutParams(0, dp(58), 1));
        column.addView(controls);
        column.addView(sectionTitle("Timer sound"));
        Spinner timerSound = spinner(new String[]{"Classic", "Gentle", "Pulse", "Chime", "Digital", "Wake-up", "Loud alarm"});
        column.addView(timerSound, fieldParams());
        column.addView(sectionTitle("Timers"));
        timerList = new LinearLayout(this);
        timerList.setOrientation(LinearLayout.VERTICAL);
        column.addView(timerList, new LinearLayout.LayoutParams(-1, -2));
        refreshTimerList();
        start.setOnClickListener(view -> {
            int selected = minutes.getSelectedItemPosition();
            int value;
            if (selected == 60) {
                String raw = custom.getText().toString().trim();
                if (raw.isEmpty()) {
                    custom.setError("Enter a duration");
                    return;
                }
                value = clampInt(raw, 1, (int) MAX_TIMER_MINUTES, 0);
                if (value <= 0) {
                    custom.setError("Use 1–" + MAX_TIMER_MINUTES + " minutes");
                    return;
                }
            } else {
                value = selected + 1;
            }
            String label = safeName(name.getText().toString(), "Timer");
            try {
                String[] timerSoundCodes = {"classic", "gentle", "pulse", "chime", "digital", "wake-up", "loud-alarm"};
                JSONObject timer = new JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("label", label)
                        .put("totalSeconds", value * 60L)
                        .put("remainingSeconds", value * 60L)
                        .put("endsAtMs", System.currentTimeMillis() + value * 60_000L)
                        .put("state", "running")
                        .put("snoozeCount", 0)
                        .put("sound", timerSoundCodes[timerSound.getSelectedItemPosition()])
                        .put("volume", 1.0)
                        .put("ringDurationSeconds", 60);
                JSONArray updated = QuickTimerStore.getTimers(this);
                if (updated.length() < 10) {
                    updated.put(timer);
                    QuickTimerStore.replace(this, updated);
                    QuickTimerScheduler.syncAll(this);
                    maybeRequestExactAlarmAccess(true);
                    warnIfNotificationsDisabled();
                    AlarmStore.appendHistory(this, timer, "started");
                    showTimers();
                }
            } catch (JSONException ignored) { }
        });
        setPage("Multi-Timer", scroll(column), true);
    }

    private void refreshTimerList() {
        if (timerList == null) return;
        timerList.removeAllViews();
        JSONArray timers = QuickTimerStore.getTimers(this);
        if (timers.length() == 0) {
            timerList.addView(empty("No timers running."), new LinearLayout.LayoutParams(-1, dp(220)));
            return;
        }
        for (int index = 0; index < timers.length(); index += 1) {
            JSONObject timer = timers.optJSONObject(index);
            if (timer != null) timerList.addView(timerCard(timer));
        }
    }

    private LinearLayout timerCard(JSONObject timer) {
        LinearLayout card = card();
        card.addView(text(timer.optString("label", "Timer"), 19, INK));
        String state = timer.optString("state", "running");
        String stateLabel = "ringing".equals(state) ? "Ringing" : "paused".equals(state) ? "Paused" : "Running";
        card.addView(text(stateLabel, 15, MUTED));
        TextView countdown = text(formatRemaining(timer), 30, INK);
        countdown.setGravity(Gravity.RIGHT);
        card.addView(countdown, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout actions = row();
        boolean ringing = "ringing".equals(timer.optString("state"));
        Button pause = plainButton(ringing ? "Ringing" : "running".equals(timer.optString("state")) ? "Pause" : "Resume", 16);
        pause.setEnabled(!ringing);
        Button stop = plainButton("Stop", 16);
        actions.addView(pause, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(stop, new LinearLayout.LayoutParams(0, dp(52), 1));
        pause.setOnClickListener(view -> toggleTimer(timer));
        stop.setOnClickListener(view -> { QuickTimerActionReceiver.stop(this, timer.optString("id")); showTimers(); });
        card.addView(actions);
        return card;
    }

    private void reconcileExpiredTimers() {
        JSONArray timers = QuickTimerStore.getTimers(this);
        long now = System.currentTimeMillis();
        for (int index = 0; index < timers.length(); index += 1) {
            JSONObject timer = timers.optJSONObject(index);
            if (timer == null || !"running".equals(timer.optString("state"))) continue;
            if (timer.optLong("endsAtMs", 0) > now) continue;
            try {
                timer.put("state", "ringing");
                timer.put("remainingSeconds", 0);
                timer.remove("endsAtMs");
                QuickTimerStore.update(this, timer);
                AlarmStore.appendHistory(this, timer, "ringing");
                QuickTimerReceiver.startRinging(this, timer);
            } catch (Exception ignored) { }
        }
    }

    private void toggleTimer(JSONObject timer) {
        try {
            if ("running".equals(timer.optString("state"))) {
                long remaining = Math.max(0L, (timer.optLong("endsAtMs", 0) - System.currentTimeMillis()) / 1000L);
                timer.put("remainingSeconds", remaining);
                timer.put("state", "paused");
                timer.remove("endsAtMs");
            } else {
                long remaining = Math.max(1L, timer.optLong("remainingSeconds", 1));
                timer.put("state", "running");
                timer.put("endsAtMs", System.currentTimeMillis() + remaining * 1000L);
            }
            QuickTimerStore.update(this, timer);
            QuickTimerScheduler.syncAll(this);
            showTimers();
        } catch (JSONException ignored) { }
    }

    private void showCounter() {
        currentPage = "counter";
        Button add = headerButton("+");
        add.setOnClickListener(view -> addCounter());
        resetToolbar("Counter", add);
        LinearLayout column = pageColumn();
        JSONArray counters = CounterStore.get(this);
        if (counters.length() == 0) column.addView(empty("No counters yet.\nUse + to create one."), new LinearLayout.LayoutParams(-1, dp(260)));
        for (int index = 0; index < counters.length(); index += 1) {
            JSONObject counter = counters.optJSONObject(index);
            if (counter != null) column.addView(counterCard(counter));
        }
        setPage("Counter", scroll(column), true);
    }

    private void addCounter() {
        EditText input = edit("", "Counter name");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), dp(10), dp(24), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(-1, dp(58)));
        new android.app.AlertDialog.Builder(this).setTitle("New counter").setView(wrap)
                .setPositiveButton("Add", (dialog, which) -> { CounterStore.add(this, input.getText().toString()); showCounter(); })
                .setNegativeButton("Cancel", null).show();
    }

    private LinearLayout counterCard(JSONObject counter) {
        LinearLayout card = card();
        LinearLayout heading = row();
        EditText name = edit(counter.optString("name", "Counter"), "Counter name");
        heading.addView(name, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button remove = headerButton("delete");
        remove.setOnClickListener(view -> { CounterStore.remove(this, counter.optString("id")); showCounter(); });
        heading.addView(remove, new LinearLayout.LayoutParams(dp(54), dp(58)));
        name.setOnFocusChangeListener((view, focused) -> { if (!focused) saveCounterName(counter, name); });
        card.addView(heading);
        TextView value = text(String.valueOf(counter.optLong("value", 0)), 48, INK);
        value.setGravity(Gravity.CENTER);
        card.addView(value, new LinearLayout.LayoutParams(-1, dp(86)));
        LinearLayout controls = row();
        Button minus = wideButton("−");
        Button reset = plainButton("Reset", 16);
        Button plus = wideButton("+");
        controls.addView(minus, new LinearLayout.LayoutParams(0, dp(64), 1));
        controls.addView(reset, new LinearLayout.LayoutParams(0, dp(64), 1));
        controls.addView(plus, new LinearLayout.LayoutParams(0, dp(64), 1));
        minus.setOnClickListener(view -> changeCounter(counter, -1));
        plus.setOnClickListener(view -> changeCounter(counter, 1));
        reset.setOnClickListener(view -> changeCounter(counter, 0));
        card.addView(controls);
        return card;
    }

    private void saveCounterName(JSONObject counter, EditText input) {
        try { counter.put("name", safeName(input.getText().toString(), "Counter")); CounterStore.update(this, counter); } catch (JSONException ignored) { }
    }

    private void changeCounter(JSONObject counter, long delta) {
        try {
            long current = counter.optLong("value", 0);
            long next = delta == 0 ? 0 : Math.min(CounterStore.MAX_COUNTER_VALUE, current + delta);
            counter.put("value", next);
            CounterStore.update(this, counter);
            showCounter();
        } catch (JSONException ignored) { }
    }

    private void showWeather() {
        currentPage = "weather";
        weatherRequestGeneration += 1;
        resetToolbar("Weather", null);
        LinearLayout column = pageColumn();
        LinearLayout weatherHeader = row();
        TextView weatherTitle = sectionTitle("Local weather");
        weatherHeader.addView(weatherTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        weatherStatus = text("Refreshing…", 12, MUTED);
        weatherStatus.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        weatherHeader.addView(weatherStatus, new LinearLayout.LayoutParams(dp(92), dp(48)));
        column.addView(weatherHeader);
        weatherForecast = card();
        weatherCoordinates = text("GPS coordinates unavailable", 15, MUTED);
        weatherCoordinates.setVisibility(View.GONE);
        weatherCoordinates.setPaintFlags(weatherCoordinates.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        weatherCoordinates.setContentDescription("Open GPS coordinates in a map app");
        weatherForecast.addView(weatherCoordinates, new LinearLayout.LayoutParams(-1, dp(36)));
        weatherForecast.addView(text("No forecast loaded", 22, INK));
        column.addView(weatherForecast);
        column.addView(text("Forecast data: Open-Meteo", 14, MUTED));
        setPage("Weather", scroll(column), true);
        requestWeather(weatherStatus, weatherForecast);
    }

    private void maybeRequestExactAlarmAccess(boolean userInitiated) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
                || AlarmScheduler.canScheduleExactAlarms(this)
                || exactAlarmPromptShowing) return;
        android.content.SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!userInitiated && preferences.getBoolean(EXACT_ALARM_PROMPT_SHOWN, false)) return;
        preferences.edit().putBoolean(EXACT_ALARM_PROMPT_SHOWN, true).apply();
        exactAlarmPromptShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("Allow alarms and reminders")
                .setMessage("To ring alarms and timers at the scheduled time while Kala Time is closed or the phone is idle, allow this app to set alarms and reminders in Android settings.")
                .setNegativeButton("Not now", (dialog, which) -> exactAlarmPromptShowing = false)
                .setPositiveButton("Allow", (dialog, which) -> {
                    exactAlarmPromptShowing = false;
                    startActivity(AlarmScheduler.exactAlarmSettingsIntent(this));
                })
                .setOnCancelListener(dialog -> exactAlarmPromptShowing = false)
                .show();
    }

    private void warnIfNotificationsDisabled() {
        if (!NotificationHelper.areNotificationsEnabled(this)) showNotificationSettingsPrompt();
    }

    private void showNotificationSettingsPrompt() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Notifications are disabled")
                .setMessage("Android is blocking the alarm notification. Enable notifications so the alarm controls appear when the app is closed.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings", (dialog, which) -> {
                    Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(settings);
                })
                .show();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestWeather(TextView status, LinearLayout forecast) {
        final int requestGeneration = ++weatherRequestGeneration;
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            status.setText("Location permission is required for the forecast.");
            return;
        }
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            status.setText("Location services are not available.");
            return;
        }
        Location fallbackLocation = null;
        try {
            fallbackLocation = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (fallbackLocation == null) fallbackLocation = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (fallbackLocation == null) fallbackLocation = manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        } catch (SecurityException ignored) { }
        status.setText("Refreshing…");
        try {
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_COARSE);
            criteria.setPowerRequirement(Criteria.POWER_LOW);
            String provider = manager.getBestProvider(criteria, true);
            if (provider == null) provider = LocationManager.NETWORK_PROVIDER;
            final String selectedProvider = provider;
            final Location fallback = fallbackLocation;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                manager.getCurrentLocation(selectedProvider, null, getMainExecutor(), result -> {
                    if (result != null) {
                        loadWeather(result, status, forecast, requestGeneration);
                    } else if (fallback != null) {
                        loadWeather(fallback, status, forecast, requestGeneration);
                    } else {
                        status.setText("Location is not available.");
                    }
                });
            } else {
                manager.requestSingleUpdate(selectedProvider, new LocationListener() {
                    @Override public void onLocationChanged(Location result) { loadWeather(result, status, forecast, requestGeneration); }
                }, Looper.getMainLooper());
            }
        } catch (Exception ignored) {
            if (fallbackLocation != null) {
                loadWeather(fallbackLocation, status, forecast, requestGeneration);
            } else {
                status.setText("Location is not available.");
            }
        }
    }

    private void loadWeather(Location location, TextView status, LinearLayout forecast, int requestGeneration) {
        status.setText("Refreshing…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String query = "https://api.open-meteo.com/v1/forecast?latitude=" + location.getLatitude()
                        + "&longitude=" + location.getLongitude()
                        + "&current=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m"
                        + "&daily=temperature_2m_max,temperature_2m_min,weather_code&forecast_days=3&timezone=auto";
                connection = (HttpURLConnection) new URL(query).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) throw new java.io.IOException("HTTP " + responseCode);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();
                JSONObject json = new JSONObject(body.toString());
                JSONObject current = json.optJSONObject("current");
                JSONObject daily = json.optJSONObject("daily");
                final JSONObject currentSnapshot = current;
                final JSONObject dailySnapshot = daily;
                final double latitude = location.getLatitude();
                final double longitude = location.getLongitude();
                runOnUiThread(() -> {
                    if (requestGeneration == weatherRequestGeneration && status == weatherStatus && forecast == weatherForecast) {
                        renderWeather(status, forecast, currentSnapshot, dailySnapshot, latitude, longitude);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (requestGeneration == weatherRequestGeneration && status == weatherStatus && forecast == weatherForecast) {
                        status.setText("Forecast unavailable. Check your connection.");
                    }
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void renderWeather(TextView status, LinearLayout forecast, JSONObject current, JSONObject daily,
                               double latitude, double longitude) {
        status.setText("Ready");
        forecast.removeAllViews();
        weatherCoordinates = text(String.format(Locale.UK, "GPS coordinates  %.4f, %.4f", latitude, longitude), 15, MUTED);
        weatherCoordinates.setPaintFlags(weatherCoordinates.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        weatherCoordinates.setContentDescription("Open GPS coordinates in a map app");
        weatherCoordinates.setOnClickListener(view -> openGoogleMaps(latitude, longitude));
        forecast.addView(weatherCoordinates, new LinearLayout.LayoutParams(-1, dp(36)));
        forecast.addView(text("Current weather", 20, INK));
        TextView temperature = text(String.format(Locale.UK, "%.1f°C", current == null ? 0 : current.optDouble("temperature_2m")), 38, INK);
        temperature.setTypeface(null, android.graphics.Typeface.BOLD);
        forecast.addView(temperature, new LinearLayout.LayoutParams(-1, dp(58)));
        forecast.addView(text(weatherCode(current == null ? 0 : current.optInt("weather_code")), 19, MUTED));
        forecast.addView(text(String.format(Locale.UK, "Wind %.1f km/h  •  Humidity %.0f%%",
                current == null ? 0 : current.optDouble("wind_speed_10m"),
                current == null ? 0 : current.optDouble("relative_humidity_2m")), 16, MUTED));

        if (daily == null) return;
        forecast.addView(sectionTitle("3-day forecast"));
        JSONArray dates = daily.optJSONArray("time");
        JSONArray highs = daily.optJSONArray("temperature_2m_max");
        JSONArray lows = daily.optJSONArray("temperature_2m_min");
        JSONArray codes = daily.optJSONArray("weather_code");
        int count = Math.min(3, dates == null ? 0 : dates.length());
        for (int index = 0; index < count; index += 1) {
            LinearLayout day = row();
            day.setPadding(0, dp(7), 0, dp(7));
            String date = dates.optString(index, "");
            String high = highs == null ? "—" : String.format(Locale.UK, "%.0f°C", highs.optDouble(index));
            String low = lows == null ? "—" : String.format(Locale.UK, "%.0f°C", lows.optDouble(index));
            String condition = weatherCode(codes == null ? 0 : codes.optInt(index));
            TextView dateView = text(date, 15, INK);
            TextView conditionView = text(condition, 15, MUTED);
            TextView temperatureView = text(high + " / " + low, 15, INK);
            dateView.setSingleLine(true);
            dateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            conditionView.setSingleLine(true);
            conditionView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            temperatureView.setSingleLine(true);
            temperatureView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            temperatureView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            day.addView(dateView, new LinearLayout.LayoutParams(0, dp(34), 1));
            day.addView(conditionView, new LinearLayout.LayoutParams(0, dp(34), 1.25f));
            day.addView(temperatureView, new LinearLayout.LayoutParams(0, dp(34), 1));
            forecast.addView(day);
        }
    }

    private void openGoogleMaps(double latitude, double longitude) {
        Intent maps = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + latitude + "," + longitude + "?q=" + latitude + "," + longitude));
        try {
            startActivity(maps);
        } catch (android.content.ActivityNotFoundException ignored) { }
    }

    private String weatherCode(int code) {
        if (code == 0) return "Clear sky";
        if (code <= 3) return "Partly cloudy";
        if (code <= 48) return "Foggy";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        return "Thunderstorms";
    }

    private JSONObject defaultAlarm() {
        JSONObject alarm = new JSONObject();
        JSONArray days = new JSONArray();
        for (String code : DAY_CODES) days.put(code);
        try {
            alarm.put("id", UUID.randomUUID().toString());
            alarm.put("frequency", "several");
            alarm.put("repeatDays", days);
            alarm.put("startTime", "06:00");
            alarm.put("endTime", "18:00");
            alarm.put("intervalMinutes", 60);
            alarm.put("name", "New alarm");
            alarm.put("sound", "classic");
            alarm.put("volume", 1.0);
            alarm.put("durationSeconds", 60);
            alarm.put("snoozeEnabled", true);
            alarm.put("snoozeSequenceMinutes", new JSONArray().put(25).put(15).put(10).put(5));
            alarm.put("afterSnoozeExhausted", "dismiss");
            alarm.put("enabled", true);
        } catch (JSONException ignored) { }
        return alarm;
    }

    private void ensureRepeatDays(JSONObject alarm) {
        if (alarm == null || "once".equals(alarm.optString("frequency"))) return;
        JSONArray days = alarm.optJSONArray("repeatDays");
        if (days != null && days.length() > 0) return;
        try { alarm.put("repeatDays", allRepeatDays()); } catch (JSONException ignored) { }
    }

    private JSONArray allRepeatDays() {
        JSONArray days = new JSONArray();
        for (String code : DAY_CODES) days.put(code);
        return days;
    }

    private void chooseTime(Button target, String value) {
        int hour = 6;
        int minute = 0;
        try { String[] parts = value.split(":"); hour = Integer.parseInt(parts[0]); minute = Integer.parseInt(parts[1]); } catch (Exception ignored) { }
        new TimePickerDialog(this, (view, selectedHour, selectedMinute) -> target.setText(String.format(Locale.UK, "%02d:%02d", selectedHour, selectedMinute)), hour, minute, true).show();
    }

    private void addCalendarAlarm() {
        Calendar now = Calendar.getInstance();
        DatePickerDialog datePicker = new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), 0);
            selected.set(Calendar.MILLISECOND, 0);
            new TimePickerDialog(this, (timeView, hour, minute) -> {
                selected.set(Calendar.HOUR_OF_DAY, hour);
                selected.set(Calendar.MINUTE, minute);
                long atMs = selected.getTimeInMillis();
                if (atMs <= System.currentTimeMillis()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Choose a future time")
                            .setMessage("Calendar alarms must be scheduled in the future.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                JSONObject alarm = defaultAlarm();
                try {
                    alarm.put("frequency", "once");
                    alarm.put("calendarAtMs", atMs);
                    alarm.put("startTime", String.format(Locale.UK, "%02d:%02d", hour, minute));
                    alarm.put("endTime", alarm.optString("startTime"));
                    alarm.put("repeatDays", new JSONArray());
                    alarm.put("name", "Calendar alarm");
                    AlarmStore.upsert(this, alarm);
                    AlarmScheduler.syncAll(this);
                    maybeRequestExactAlarmAccess(true);
                    warnIfNotificationsDisabled();
                    showAlarms();
                } catch (JSONException ignored) { }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        datePicker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
        datePicker.show();
    }

    private void updateCalendarAlarmTime(JSONObject alarm, String value) {
        long atMs = alarm.optLong("calendarAtMs", 0);
        if (atMs <= 0) return;
        try {
            String[] parts = value.split(":");
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(atMs);
            calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
            calendar.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            alarm.put("calendarAtMs", calendar.getTimeInMillis());
        } catch (Exception ignored) { }
    }

    private String formatCalendarDateTime(long atMs) {
        if (atMs <= 0) return "Calendar alarm";
        return new SimpleDateFormat("dd MMM HH:mm", Locale.UK).format(new Date(atMs));
    }

    private String[] timerMinuteLabels() {
        String[] values = new String[61];
        for (int index = 0; index < 60; index += 1) values[index] = (index + 1) + " minutes";
        values[60] = "Custom…";
        return values;
    }

    private String formatRemaining(JSONObject timer) {
        long seconds = timer.optLong("remainingSeconds", 0);
        if ("running".equals(timer.optString("state"))) seconds = Math.max(0, (timer.optLong("endsAtMs", 0) - System.currentTimeMillis()) / 1000L);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return String.format(Locale.UK, "%02d:%02d:%02d", hours, minutes, remainder);
    }

    private int clampInt(String value, int min, int max, int fallback) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value.trim()))); } catch (Exception ignored) { return fallback; }
    }

    private JSONArray parseSequence(String value) {
        JSONArray result = new JSONArray();
        String[] pieces = value.split(",");
        for (String piece : pieces) {
            int minutes = clampInt(piece, 1, 1440, 0);
            if (minutes > 0) result.put(minutes);
        }
        return result.length() == 0 ? new JSONArray().put(25).put(15).put(10).put(5) : result;
    }

    private String sequenceText(JSONArray values) {
        if (values == null || values.length() == 0) return "25, 15, 10, 5";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length(); index += 1) {
            if (index > 0) result.append(", ");
            result.append(values.optInt(index));
        }
        return result.toString();
    }

    private boolean hasDay(JSONArray days, String code) {
        if (days == null) return false;
        for (int index = 0; index < days.length(); index += 1) if (code.equals(days.optString(index))) return true;
        return false;
    }

    private int indexOf(String[] values, String target) {
        for (int index = 0; index < values.length; index += 1) if (values[index].equals(target)) return index;
        return 0;
    }

    private String safeName(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); return row; }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 20, INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(18), 0, dp(8));
        return title;
    }

    private TextView empty(String value) { TextView empty = text(value, 20, MUTED); empty.setGravity(Gravity.CENTER); return empty; }

    private TextView text(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); return view; }

    private EditText edit(String value, String hint) { EditText input = new EditText(this); input.setText(value); input.setHint(hint); input.setTextSize(18); input.setTextColor(INK); input.setHintTextColor(MUTED); input.setSingleLine(true); input.setPadding(dp(12), 0, dp(12), 0); return input; }

    private LinearLayout.LayoutParams fieldParams() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58)); params.setMargins(0, 0, 0, dp(4)); return params; }

    private Button headerButton(String value) {
        Button button = plainButton("", 22);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        if ("+".equals(value)) {
            button.setCompoundDrawablesWithIntrinsicBounds(com.rbabbit.alarm.R.drawable.ic_add, 0, 0, 0);
            button.setContentDescription("Add");
        } else if ("settings".equals(value)) {
            button.setCompoundDrawablesWithIntrinsicBounds(com.rbabbit.alarm.R.drawable.ic_settings, 0, 0, 0);
            button.setContentDescription("Edit alarm");
        } else if ("back".equals(value)) {
            button.setCompoundDrawablesWithIntrinsicBounds(com.rbabbit.alarm.R.drawable.ic_arrow_back, 0, 0, 0);
            button.setContentDescription("Back");
        } else if ("delete".equals(value)) {
            button.setCompoundDrawablesWithIntrinsicBounds(com.rbabbit.alarm.R.drawable.ic_delete, 0, 0, 0);
            button.setContentDescription("Delete");
        } else {
            button.setText(value);
        }
        return button;
    }

    private Button calendarAddButton() {
        Button button = plainButton("+", 26);
        button.setTextColor(MUTED);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("Add calendar alarm");
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), Color.rgb(150, 150, 150));
        background.setCornerRadius(dp(18));
        button.setBackground(background);
        return button;
    }

    private LinearLayout headerActions(Button... buttons) {
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        for (Button button : buttons) actions.addView(button, new LinearLayout.LayoutParams(dp(54), dp(54)));
        return actions;
    }

    private Button plainButton(String value, float size) { Button button = new Button(this); button.setText(value); button.setTextSize(size); button.setTextColor(INK); button.setAllCaps(false); button.setBackgroundColor(Color.TRANSPARENT); return button; }

    private Button wideButton(String value) { Button button = plainButton(value, 18); button.setTypeface(null, android.graphics.Typeface.BOLD); button.setBackground(outline()); return button; }

    private android.graphics.drawable.Drawable outline() { android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable(); drawable.setColor(Color.WHITE); drawable.setStroke(dp(2), INK); drawable.setCornerRadius(dp(24)); return drawable; }

    private ToggleButton dayButton(String label) {
        ToggleButton button = new ToggleButton(this);
        button.setTextOn(label);
        button.setTextOff(label);
        button.setTextSize(16);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.WHITE, INK}));
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable selected = new android.graphics.drawable.GradientDrawable();
        selected.setColor(INK);
        selected.setCornerRadius(dp(24));
        android.graphics.drawable.GradientDrawable unselected = new android.graphics.drawable.GradientDrawable();
        unselected.setColor(Color.WHITE);
        unselected.setStroke(dp(1), Color.rgb(150, 150, 150));
        unselected.setCornerRadius(dp(24));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked}, selected);
        states.addState(new int[]{}, unselected);
        button.setBackground(states);
        button.setElevation(dp(1));
        return button;
    }

    private RadioButton radio(String value) { RadioButton radio = new RadioButton(this); radio.setId(View.generateViewId()); radio.setText(value); radio.setTextSize(15); radio.setTextColor(INK); return radio; }

    private Spinner spinner(String[] values) { Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values)); return spinner; }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
