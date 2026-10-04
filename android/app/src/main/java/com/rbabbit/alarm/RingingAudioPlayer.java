package com.rbabbit.alarm;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.provider.Settings;

/** Plays the same generated alarm tones for alarms and Multi-Timer notifications. */
final class RingingAudioPlayer {
    private static final int SAMPLE_RATE = 44_100;
    private static final int WAVE_SINE = 0;
    private static final int WAVE_SQUARE = 1;
    private static final int WAVE_SAWTOOTH = 2;

    private AudioTrack generatedTrack;
    private MediaPlayer fallbackPlayer;

    void start(Context context, String sound, double volume) {
        release();
        try {
            ToneStep[] pattern = patternFor(sound);
            short[] samples = generateSamples(pattern);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            generatedTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.length * 2)
                    .build();
            int written = generatedTrack.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
            if (written != samples.length) throw new IllegalStateException("Unable to load tone");
            generatedTrack.setLoopPoints(0, samples.length, -1);
            generatedTrack.setVolume((float) Math.max(0.0, Math.min(1.0, volume)));
            generatedTrack.play();
        } catch (Exception ignored) {
            release();
            playDefault(context, volume);
        }
    }

    void release() {
        if (generatedTrack != null) {
            try { generatedTrack.stop(); } catch (IllegalStateException ignored) { }
            generatedTrack.release();
            generatedTrack = null;
        }
        if (fallbackPlayer != null) {
            try { fallbackPlayer.stop(); } catch (IllegalStateException ignored) { }
            fallbackPlayer.release();
            fallbackPlayer = null;
        }
    }

    private void playDefault(Context context, double volume) {
        try {
            android.net.Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            fallbackPlayer = new MediaPlayer();
            fallbackPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            fallbackPlayer.setDataSource(context, uri);
            fallbackPlayer.setLooping(true);
            float safeVolume = (float) Math.max(0.0, Math.min(1.0, volume));
            fallbackPlayer.setVolume(safeVolume, safeVolume);
            fallbackPlayer.prepare();
            fallbackPlayer.start();
        } catch (Exception ignored) {
            release();
        }
    }

    private static final class ToneStep {
        final double frequency;
        final int durationMs;
        final int waveform;

        ToneStep(double frequency, int durationMs, int waveform) {
            this.frequency = frequency;
            this.durationMs = durationMs;
            this.waveform = waveform;
        }
    }

    private static ToneStep tone(double frequency, int durationMs) { return new ToneStep(frequency, durationMs, WAVE_SINE); }
    private static ToneStep loudTone(double frequency, int durationMs, int waveform) { return new ToneStep(frequency, durationMs, waveform); }
    private static ToneStep silence(int durationMs) { return new ToneStep(0, durationMs, WAVE_SINE); }

    private static ToneStep[] patternFor(String sound) {
        if ("gentle".equals(sound)) return new ToneStep[]{tone(523.25, 280), silence(100), tone(659.25, 280), silence(100), tone(783.99, 280), silence(160)};
        if ("pulse".equals(sound)) return new ToneStep[]{tone(1_046.5, 160), silence(460)};
        if ("chime".equals(sound)) return new ToneStep[]{tone(659.25, 230), silence(90), tone(783.99, 230), silence(90), tone(1_046.5, 230), silence(480)};
        if ("digital".equals(sound)) return new ToneStep[]{tone(1_046.5, 90), silence(80), tone(1_046.5, 90), silence(300)};
        if ("wake-up".equals(sound)) return new ToneStep[]{loudTone(880, 360, WAVE_SQUARE), silence(80), loudTone(1_046.5, 360, WAVE_SQUARE), silence(80), loudTone(880, 360, WAVE_SQUARE), silence(60)};
        if ("loud-alarm".equals(sound)) return new ToneStep[]{loudTone(659.25, 600, WAVE_SAWTOOTH), silence(90), loudTone(1_046.5, 600, WAVE_SAWTOOTH), silence(190)};
        return new ToneStep[]{tone(880, 220), silence(110), tone(660, 220), silence(350)};
    }

    private static short[] generateSamples(ToneStep[] pattern) {
        int sampleCount = 0;
        for (ToneStep step : pattern) sampleCount += Math.round(step.durationMs * SAMPLE_RATE / 1000f);
        short[] samples = new short[sampleCount];
        int offset = 0;
        for (ToneStep step : pattern) {
            int length = Math.round(step.durationMs * SAMPLE_RATE / 1000f);
            if (step.frequency > 0) {
                for (int index = 0; index < length; index += 1) {
                    double phase = 2 * Math.PI * step.frequency * index / SAMPLE_RATE;
                    double wave = step.waveform == WAVE_SQUARE ? (Math.sin(phase) >= 0 ? 1 : -1)
                            : step.waveform == WAVE_SAWTOOTH ? 2 * (phase / (2 * Math.PI) - Math.floor(phase / (2 * Math.PI) + 0.5))
                            : Math.sin(phase);
                    int fadeSamples = Math.min(length / 2, SAMPLE_RATE * 15 / 1000);
                    double envelope = 1;
                    if (fadeSamples > 0 && index < fadeSamples) envelope = index / (double) fadeSamples;
                    if (fadeSamples > 0 && index >= length - fadeSamples) envelope = (length - index) / (double) fadeSamples;
                    samples[offset + index] = (short) Math.round(wave * envelope * 0.9 * Short.MAX_VALUE);
                }
            }
            offset += length;
        }
        return samples;
    }
}
