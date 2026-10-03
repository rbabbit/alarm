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

/** Keeps a Multi-Timer audible after the WebView is backgrounded or closed. */
public final class QuickTimerRingingService extends Service {
    private final Handler handler = new Handler();
    private MediaPlayer player;
    private String timerId;

    private final Runnable timeout = () -> {
        String id = timerId;
        if (id != null) QuickTimerActionReceiver.stop(this, id);
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String requestedId = intent == null ? null : intent.getStringExtra(QuickTimerScheduler.EXTRA_TIMER_ID);
        if (requestedId == null) return START_NOT_STICKY;
        if (requestedId.equals(timerId) && player != null) return START_NOT_STICKY;
        JSONObject timer = QuickTimerStore.find(this, requestedId);
        if (timer == null || !"ringing".equals(timer.optString("state"))) return START_NOT_STICKY;
        timerId = requestedId;
        QuickTimerNotificationHelper.createChannel(this);
        startForegroundCompat(QuickTimerNotificationHelper.build(this, timer));
        playAlarm();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 60_000L);
        return START_NOT_STICKY;
    }

    private void startForegroundCompat(android.app.Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(QuickTimerNotificationHelper.notificationId(timerId), notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(QuickTimerNotificationHelper.notificationId(timerId), notification);
        }
    }

    private void playAlarm() {
        releaseAudio();
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
            player.setVolume(1f, 1f);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            releaseAudio();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        releaseAudio();
        QuickTimerNotificationHelper.cancel(this, timerId);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    private void releaseAudio() {
        if (player == null) return;
        try { player.stop(); } catch (IllegalStateException ignored) { }
        player.release();
        player = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
