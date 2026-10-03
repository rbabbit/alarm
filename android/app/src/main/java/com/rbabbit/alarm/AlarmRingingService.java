package com.rbabbit.alarm;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** Plays the alarm sound for the configured duration outside the WebView. */
public final class AlarmRingingService extends Service {
    private final Handler handler = new Handler();
    private MediaPlayer player;
    private SpeechRecognizer speechRecognizer;
    private String alarmId;
    private JSONObject activeAlarm;
    private boolean timedOut;
    private boolean voiceStopFinished;

    private final Runnable restartVoiceStop = () -> {
        if (!voiceStopFinished && activeAlarm != null) startVoiceStop();
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
        activeAlarm = alarm;
        voiceStopFinished = false;

        NotificationHelper.createChannel(this);
        startForegroundCompat(NotificationHelper.buildAlarmNotification(this, alarm, occurrence, snoozeIndex), alarm);
        playAlarm(alarm.optDouble("volume", 0.8));
        if (alarm.optBoolean("voiceStopEnabled", true)) startVoiceStop();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, Math.max(1, alarm.optInt("durationSeconds", 60)) * 1000L);
        return START_NOT_STICKY;
    }

    private void startForegroundCompat(android.app.Notification notification, JSONObject alarm) {
        if (Build.VERSION.SDK_INT >= 29) {
            int foregroundType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (alarm.optBoolean("voiceStopEnabled", true)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                foregroundType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NotificationHelper.notificationId(alarmId), notification, foregroundType);
        } else {
            startForeground(NotificationHelper.notificationId(alarmId), notification);
        }
    }

    private void startVoiceStop() {
        if (voiceStopFinished || activeAlarm == null) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        destroySpeechRecognizer();
        try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) {
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) scheduleVoiceRestart();
                }
                @Override public void onResults(Bundle results) {
                    if (containsStop(results)) finishByVoice();
                    else scheduleVoiceRestart();
                }
                @Override public void onPartialResults(Bundle results) {
                    if (containsStop(results)) finishByVoice();
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent recognition = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
            speechRecognizer.startListening(recognition);
        } catch (RuntimeException ignored) {
            scheduleVoiceRestart();
        }
    }

    private void scheduleVoiceRestart() {
        handler.removeCallbacks(restartVoiceStop);
        handler.postDelayed(restartVoiceStop, 450L);
    }

    private boolean containsStop(Bundle results) {
        ArrayList<String> matches = results == null
                ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) return false;
        for (String match : matches) {
            if (match != null && match.toLowerCase(Locale.UK).matches(".*\\bstop\\b.*")) return true;
        }
        return false;
    }

    private void finishByVoice() {
        if (voiceStopFinished || alarmId == null) return;
        voiceStopFinished = true;
        destroySpeechRecognizer();
        AlarmActionReceiver.performStop(this, alarmId, true);
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
        voiceStopFinished = true;
        handler.removeCallbacks(restartVoiceStop);
        handler.removeCallbacks(timeout);
        destroySpeechRecognizer();
        releasePlayer();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer == null) return;
        try { speechRecognizer.cancel(); } catch (RuntimeException ignored) { }
        try { speechRecognizer.destroy(); } catch (RuntimeException ignored) { }
        speechRecognizer = null;
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
