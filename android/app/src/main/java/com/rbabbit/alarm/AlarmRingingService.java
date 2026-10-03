package com.rbabbit.alarm;

import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;

import androidx.annotation.Nullable;

import org.json.JSONObject;


/** Plays the alarm sound for the configured duration outside the WebView. */
public final class AlarmRingingService extends Service {
    private final Handler handler = new Handler();
    private ToneGenerator toneGenerator;
    private MediaPlayer player;
    private String alarmId;
    private boolean timedOut;
    private int[][] tonePattern;
    private int toneSegment;

    private static final int SILENCE = -1;
    private static final int[][] CLASSIC_PATTERN = {
            {ToneGenerator.TONE_PROP_BEEP2, 220}, {SILENCE, 110},
            {ToneGenerator.TONE_PROP_BEEP, 220}, {SILENCE, 350}
    };
    private static final int[][] GENTLE_PATTERN = {
            {ToneGenerator.TONE_PROP_BEEP, 280}, {SILENCE, 100},
            {ToneGenerator.TONE_PROP_BEEP2, 280}, {SILENCE, 100},
            {ToneGenerator.TONE_PROP_ACK, 280}, {SILENCE, 520}
    };
    private static final int[][] PULSE_PATTERN = {
            {ToneGenerator.TONE_PROP_BEEP2, 160}, {SILENCE, 460}
    };
    private static final int[][] CHIME_PATTERN = {
            {ToneGenerator.TONE_PROP_BEEP, 230}, {SILENCE, 90},
            {ToneGenerator.TONE_PROP_ACK, 230}, {SILENCE, 90},
            {ToneGenerator.TONE_PROP_BEEP2, 230}, {SILENCE, 480}
    };
    private static final int[][] DIGITAL_PATTERN = {
            {ToneGenerator.TONE_PROP_BEEP2, 90}, {SILENCE, 80},
            {ToneGenerator.TONE_PROP_BEEP2, 90}, {SILENCE, 300}
    };
    private static final int[][] WAKE_UP_PATTERN = {
            {ToneGenerator.TONE_PROP_BEEP2, 360}, {SILENCE, 80},
            {ToneGenerator.TONE_PROP_ACK, 360}, {SILENCE, 80},
            {ToneGenerator.TONE_PROP_BEEP2, 360}, {SILENCE, 400}
    };
    private static final int[][] LOUD_ALARM_PATTERN = {
            {ToneGenerator.TONE_PROP_ACK, 600}, {SILENCE, 90},
            {ToneGenerator.TONE_PROP_BEEP2, 600}, {SILENCE, 280}
    };

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
        try {
            int generatorVolume = (int) Math.round(Math.max(0.0, Math.min(1.0, volume)) * 100.0);
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, generatorVolume);
            tonePattern = patternFor(sound);
            toneSegment = 0;
            handler.post(playToneSegment);
            return;
        } catch (Exception ignored) {
            releaseAudio();
        }

        playDefaultAlarm(volume);
    }

    private final Runnable playToneSegment = new Runnable() {
        @Override
        public void run() {
            if (toneGenerator == null || tonePattern == null || tonePattern.length == 0) return;
            int[] segment = tonePattern[toneSegment];
            if (segment[0] == SILENCE) toneGenerator.stopTone();
            else toneGenerator.startTone(segment[0], segment[1]);
            toneSegment = (toneSegment + 1) % tonePattern.length;
            handler.postDelayed(this, segment[1]);
        }
    };

    private static int[][] patternFor(String sound) {
        if ("gentle".equals(sound)) return GENTLE_PATTERN;
        if ("pulse".equals(sound)) return PULSE_PATTERN;
        if ("chime".equals(sound)) return CHIME_PATTERN;
        if ("digital".equals(sound)) return DIGITAL_PATTERN;
        if ("wake-up".equals(sound)) return WAKE_UP_PATTERN;
        if ("loud-alarm".equals(sound)) return LOUD_ALARM_PATTERN;
        return CLASSIC_PATTERN;
    }

    private void playDefaultAlarm(double volume) {
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
            releaseAudio();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(playToneSegment);
        releaseAudio();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    private void releaseAudio() {
        handler.removeCallbacks(playToneSegment);
        if (toneGenerator != null) {
            toneGenerator.stopTone();
            toneGenerator.release();
            toneGenerator = null;
        }
        tonePattern = null;
        if (player == null) return;
        try { player.stop(); } catch (IllegalStateException ignored) { }
        player.release();
        player = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
