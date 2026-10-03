package com.rbabbit.alarm;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/** Visible, lock-screen-safe alarm screen. */
public final class RingingActivity extends Activity {
    public static final String ACTION_FINISH = "com.rbabbit.alarm.ACTION_FINISH_RINGING";
    private JSONObject alarm;
    private String alarmId;
    private int snoozeIndex;

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_FINISH.equals(intent.getAction())) finish();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);

        alarmId = getIntent().getStringExtra(AlarmScheduler.EXTRA_ALARM_ID);
        snoozeIndex = getIntent().getIntExtra(AlarmScheduler.EXTRA_SNOOZE_INDEX, 0);
        alarm = AlarmStore.findAlarm(this, alarmId);
        if (alarm == null) { finish(); return; }
        setContentView(buildView());

        IntentFilter filter = new IntentFilter(ACTION_FINISH);
        ContextCompat.registerReceiver(this, finishReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private View buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 80, 48, 48);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText(alarm.optString("name", "Alarm"));
        title.setTextColor(Color.BLACK);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView ringing = new TextView(this);
        ringing.setText("Alarm ringing");
        ringing.setTextColor(Color.DKGRAY);
        ringing.setTextSize(22);
        ringing.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ringingParams = new LinearLayout.LayoutParams(-1, -2);
        ringingParams.topMargin = 32;
        root.addView(ringing, ringingParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = 48;
        root.addView(actions, actionsParams);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(view -> {
            AlarmActionReceiver.performStop(this, alarmId);
            finish();
        });
        actions.addView(stop, new LinearLayout.LayoutParams(0, 120, 1));

        int nextSnooze = AlarmScheduler.nextSnoozeMinutes(alarm, snoozeIndex);
        if (nextSnooze > 0) {
            Button snooze = new Button(this);
            snooze.setText("Snooze " + nextSnooze + " min");
            snooze.setOnClickListener(view -> {
                AlarmActionReceiver.performSnooze(this, alarmId, snoozeIndex);
                finish();
            });
            LinearLayout.LayoutParams snoozeParams = new LinearLayout.LayoutParams(0, 120, 1);
            snoozeParams.leftMargin = 20;
            actions.addView(snooze, snoozeParams);
        }
        return root;
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(finishReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }
}
