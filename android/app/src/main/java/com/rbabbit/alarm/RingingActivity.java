package com.rbabbit.alarm;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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

import java.util.ArrayList;
import java.util.Locale;

/** Visible, lock-screen-safe alarm screen with voice STOP support. */
public final class RingingActivity extends Activity {
    public static final String ACTION_FINISH = "com.rbabbit.alarm.ACTION_FINISH_RINGING";
    private final Handler handler = new Handler();
    private SpeechRecognizer speechRecognizer;
    private JSONObject alarm;
    private String alarmId;
    private int snoozeIndex;
    private boolean voiceListening;
    private boolean finished;
    private TextView voiceStatus;

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
        if (alarm.optBoolean("voiceStopEnabled", true)) handler.postDelayed(this::startVoiceStop, 500);
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

        voiceStatus = new TextView(this);
        voiceStatus.setText(alarm.optBoolean("voiceStopEnabled", true)
                ? "Listening for: STOP"
                : "");
        voiceStatus.setTextColor(Color.DKGRAY);
        voiceStatus.setTextSize(18);
        voiceStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 18;
        root.addView(voiceStatus, statusParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = 48;
        root.addView(actions, actionsParams);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(view -> {
            AlarmActionReceiver.performStop(this, alarmId, false);
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

    private void startVoiceStop() {
        if (finished || !alarm.optBoolean("voiceStopEnabled", true)) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStatus.setText("Allow microphone access to use voice STOP");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus.setText("Voice STOP is unavailable on this phone");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { voiceListening = true; }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { voiceListening = false; }
                @Override public void onError(int error) { voiceListening = false; restartVoiceStop(); }
                @Override public void onResults(Bundle results) { handleSpeech(results); }
                @Override public void onPartialResults(Bundle results) { if (containsStop(results)) finishByVoice(); }
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
        } catch (RuntimeException error) {
            voiceStatus.setText("Voice STOP is unavailable on this phone");
        }
    }

    private void handleSpeech(Bundle results) {
        voiceListening = false;
        if (containsStop(results)) finishByVoice();
        else restartVoiceStop();
    }

    private boolean containsStop(Bundle results) {
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) return false;
        for (String match : matches) {
            if (match != null && match.toLowerCase(Locale.UK).matches(".*\\bstop\\b.*")) return true;
        }
        return false;
    }

    private void restartVoiceStop() {
        if (finished) return;
        handler.postDelayed(() -> {
            if (!finished) {
                if (speechRecognizer != null) {
                    try { speechRecognizer.destroy(); } catch (RuntimeException ignored) { }
                    speechRecognizer = null;
                }
                startVoiceStop();
            }
        }, 300);
    }

    private void finishByVoice() {
        if (finished) return;
        finished = true;
        AlarmActionReceiver.performStop(this, alarmId, true);
        finish();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (RuntimeException ignored) { }
            speechRecognizer = null;
        }
        try { unregisterReceiver(finishReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }
}
