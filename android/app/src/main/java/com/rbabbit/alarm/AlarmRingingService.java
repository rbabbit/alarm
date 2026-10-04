package com.rbabbit.alarm;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.json.JSONObject;


/** Plays the alarm sound for the configured duration in the native Android app. */
public final class AlarmRingingService extends Service {
    private final Handler handler = new Handler();
    private RingingAudioPlayer sharedAudio;
    private String alarmId;
    private boolean timedOut;

    private final Runnable timeout = () -> {
        timedOut = true;
        JSONObject alarm = AlarmStore.findAlarm(this, alarmId);
        if (alarm != null) {
            if ("once".equals(alarm.optString("frequency"))) {
                AlarmStore.setEnabled(this, alarmId, false);
            } else {
                AlarmScheduler.scheduleNextBaseline(this, alarm, System.currentTimeMillis());
            }
        }
        NotificationHelper.cancel(this, alarmId);
        stopSelf();
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alarmId = intent == null ? null : intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID);
        long occurrence = intent == null ? System.currentTimeMillis() : intent.getLongExtra(AlarmScheduler.EXTRA_OCCURRENCE_AT, System.currentTimeMillis());
        int snoozeIndex = intent == null ? 0 : intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_INDEX, 0);
        JSONObject alarm = AlarmStore.findAlarm(this, alarmId);
        if (alarm == null) return START_NOT_STICKY;
        NotificationHelper.createChannel(this);
        startForegroundCompat(NotificationHelper.buildAlarmNotification(this, alarm, occurrence, snoozeIndex));
        playAlarm(alarm.optString("sound", "classic"), alarm.optDouble("volume", 1.0));
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, Math.max(1, alarm.optInt("durationSeconds", 60)) * 1000L);
        return START_NOT_STICKY;
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
