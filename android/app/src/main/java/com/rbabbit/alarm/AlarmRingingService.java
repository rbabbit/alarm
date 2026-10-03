package com.rbabbit.alarm;

import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/** Plays the alarm sound for the configured duration outside the WebView. */
public final class AlarmRingingService extends Service {
    private final Handler handler = new Handler();
    private MediaPlayer player;
    private String alarmId;
    private boolean timedOut;

    private final Runnable timeout = () -> {
        timedOut = true;
        JSONObject alarm = AlarmStore.findAlarm(this, alarmId);
        if (alarm != null) AlarmScheduler.scheduleNextBaseline(this, alarm, System.currentTimeMillis());
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
        playAlarm(alarm.optDouble("volume", 0.8));
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, Math.max(1, alarm.optInt("durationSeconds", 60)) * 1000L);
        return START_NOT_STICKY;
    }

    private void startForegroundCompat(android.app.Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotificationHelper.notificationId(alarmId), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NotificationHelper.notificationId(alarmId), notification);
        }
    }

    private void playAlarm(double volume) {
        releasePlayer();
        try {
            android.net.Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            float safeVolume = (float) Math.max(0.0, Math.min(1.0, volume));
            player.setVolume(safeVolume, safeVolume);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            releasePlayer();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        releasePlayer();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player == null) return;
        try { player.stop(); } catch (IllegalStateException ignored) { }
        player.release();
        player = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
