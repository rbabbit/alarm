package com.rbabbit.alarm;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/** Keeps a Multi-Timer audible after the native app is backgrounded or closed. */
public final class QuickTimerRingingService extends Service {
    private final Handler handler = new Handler();
    private RingingAudioPlayer audio;
    private String timerId;

    private final Runnable timeout = () -> {
        String id = timerId;
        if (id != null) QuickTimerActionReceiver.stop(this, id);
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String requestedId = intent == null ? null : intent.getStringExtra(QuickTimerScheduler.EXTRA_TIMER_ID);
        if (requestedId == null) return START_NOT_STICKY;
        if (requestedId.equals(timerId) && audio != null) return START_NOT_STICKY;
        JSONObject timer = QuickTimerStore.find(this, requestedId);
        if (timer == null || !"ringing".equals(timer.optString("state"))) return START_NOT_STICKY;
        timerId = requestedId;
        QuickTimerNotificationHelper.createChannel(this);
        startForegroundCompat(QuickTimerNotificationHelper.build(this, timer));
        audio = new RingingAudioPlayer();
        audio.start(this, timer.optString("sound", "loud-alarm"), timer.optDouble("volume", 1.0));
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

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        if (audio != null) {
            audio.release();
            audio = null;
        }
        QuickTimerNotificationHelper.cancel(this, timerId);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
