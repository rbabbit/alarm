package com.rbabbit.alarm;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.json.JSONObject;


/** Plays the alarm sound for the configured duration in the native Android app. */
public final class AlarmRingingService extends Service {
    private static final String PREFS = "alarm_ringing_state";
    private static final String KEY_ID = "alarm_id";
    private static final String KEY_OCCURRENCE = "occurrence_at_ms";
    private static final String KEY_SNOOZE = "snooze_index";
    private final Handler handler = new Handler();
    private RingingAudioPlayer sharedAudio;
    private String alarmId;
    private boolean timedOut;

    private final Runnable timeout = () -> {
        timedOut = true;
        JSONObject alarm = AlarmStore.findAlarm(this, alarmId);
        long nextAtMs = 0;
        if (alarm != null) {
            // Ringing completion must not change the user's enabled preference.
            // The main-screen switch is user-controlled; scheduling follows that state.
            nextAtMs = AlarmScheduler.scheduleNextBaseline(this, alarm, System.currentTimeMillis());
        }
        NotificationHelper.cancel(this, alarmId);
        clearActive(this, alarmId);
        stopSelf();
        NotificationHelper.showNextAlarm(this, alarm, nextAtMs);
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        android.content.SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        alarmId = intent == null ? preferences.getString(KEY_ID, null) : intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID);
        long occurrence = intent == null ? preferences.getLong(KEY_OCCURRENCE, System.currentTimeMillis()) : intent.getLongExtra(AlarmScheduler.EXTRA_OCCURRENCE_AT, System.currentTimeMillis());
        int snoozeIndex = intent == null ? preferences.getInt(KEY_SNOOZE, 0) : intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_INDEX, 0);
        JSONObject alarm = AlarmStore.findAlarm(this, alarmId);
        if (alarm == null) return START_NOT_STICKY;
        preferences.edit().putString(KEY_ID, alarmId).putLong(KEY_OCCURRENCE, occurrence).putInt(KEY_SNOOZE, snoozeIndex).apply();
        NotificationHelper.createChannel(this);
        try {
            startForegroundCompat(NotificationHelper.buildAlarmNotification(this, alarm, occurrence, snoozeIndex));
        } catch (RuntimeException error) {
            NotificationHelper.show(this, alarm, occurrence, snoozeIndex);
            stopSelf();
            return START_NOT_STICKY;
        }
        playAlarm(alarm.optString("sound", "classic"), alarm.optDouble("volume", 1.0));
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, Math.max(1, alarm.optInt("durationSeconds", 60)) * 1000L);
        return START_STICKY;
    }

    private void startForegroundCompat(android.app.Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotificationHelper.notificationId(alarmId), notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NotificationHelper.notificationId(alarmId), notification);
        }
    }

    private void playAlarm(String sound, double volume) {
        releaseAudio();
        sharedAudio = new RingingAudioPlayer();
        sharedAudio.start(this, sound, volume);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        releaseAudio();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    private void releaseAudio() {
        if (sharedAudio != null) {
            sharedAudio.release();
            sharedAudio = null;
        }
    }

    public static void clearActive(Context context, String alarmId) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (alarmId == null || alarmId.equals(preferences.getString(KEY_ID, null))) preferences.edit().clear().apply();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
