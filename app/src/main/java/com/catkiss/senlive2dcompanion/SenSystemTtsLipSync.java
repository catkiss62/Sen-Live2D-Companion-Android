package com.catkiss.senlive2dcompanion;

import android.content.Context;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Small offline test harness for volume-driven mouth movement.
 *
 * <p>The Android TTS engine supplies synthesized PCM through onAudioAvailable(). We reduce it to
 * one RMS value every 20 ms and replay that envelope against the utterance start time. Engines
 * that do not expose PCM use a speech-like fallback pulse, so the diagnostic never silently
 * becomes a closed-mouth test.</p>
 */
final class SenSystemTtsLipSync {
    interface Listener {
        void onStatus(String status);
        void onMouthValue(float value);
    }

    private static final long TICK_MS = 16L;
    private static final int ENVELOPE_HZ = 50;
    private static final String[] TEST_LINES = {
            "你好呀，这是系统语音口型测试。现在说话的时候，嘴巴应该会跟着声音张开和闭合。",
            "今天看起来很有精神嘛，要不要一起找点有趣的事情做？",
            "哼，我才没有特意等你回来，只是刚好还没有睡而已。",
            "真的假的？你刚才说的事情，让我稍微有一点惊讶。"
    };

    private final Object audioLock = new Object();
    private final ArrayList<Float> envelope = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final TextToSpeech textToSpeech;
    private final Runnable mouthTicker = this::tickMouth;

    private volatile boolean ready;
    private volatile boolean released;
    private volatile boolean speaking;
    private volatile String activeUtteranceId = "";
    private long playbackStartMs;
    private int phraseIndex;
    private int utteranceSerial;
    private int mouthGeneration;
    private int sampleRate = 24000;
    private int channelCount = 1;
    private int encoding = AudioFormat.ENCODING_PCM_16BIT;
    private int samplesPerEnvelopeFrame = 480;
    private double squareSum;
    private int squareSampleCount;
    private byte[] carry = new byte[4];
    private int carryLength;
    private float smoothedMouth;

    SenSystemTtsLipSync(Context context, Listener listener) {
        this.listener = listener;
        textToSpeech = new TextToSpeech(context.getApplicationContext(), this::onInitialized);
        listener.onStatus("系统TTS正在初始化…");
    }

    void speakNext() {
        if (!ready || released) {
            listener.onStatus("系统TTS尚未就绪，请稍后再试");
            return;
        }
        String line = TEST_LINES[phraseIndex];
        int shownIndex = phraseIndex + 1;
        phraseIndex = (phraseIndex + 1) % TEST_LINES.length;
        String utteranceId = "sen_lip_test_" + (++utteranceSerial);
        mouthGeneration++;
        activeUtteranceId = utteranceId;
        speaking = false;
        resetEnvelope();
        listener.onMouthValue(0.0f);
        int result = textToSpeech.speak(line, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result == TextToSpeech.ERROR) {
            listener.onStatus("系统TTS播放失败");
            activeUtteranceId = "";
            return;
        }
        listener.onStatus("准备播放第" + shownIndex + "/" + TEST_LINES.length + "句：" + line);
    }

    void stop() {
        if (!released) textToSpeech.stop();
        finishMouth(false);
    }

    void shutdown() {
        if (released) return;
        released = true;
        mouthGeneration++;
        mainHandler.removeCallbacks(mouthTicker);
        textToSpeech.stop();
        textToSpeech.shutdown();
        speaking = false;
        activeUtteranceId = "";
        smoothedMouth = 0.0f;
        listener.onMouthValue(0.0f);
    }

    private void onInitialized(int status) {
        if (released) return;
        if (status != TextToSpeech.SUCCESS) {
            listener.onStatus("系统TTS初始化失败：本机可能没有可用语音引擎");
            return;
        }
        int language = textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE);
        if (language == TextToSpeech.LANG_MISSING_DATA
                || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            language = textToSpeech.setLanguage(Locale.getDefault());
        }
        textToSpeech.setSpeechRate(0.92f);
        textToSpeech.setPitch(1.05f);
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                if (!isActive(utteranceId)) return;
                mainHandler.post(() -> beginPlayback(utteranceId));
            }

            @Override public void onDone(String utteranceId) {
                if (!isActive(utteranceId)) return;
                mainHandler.post(() -> finishMouth(true));
            }

            @Override public void onError(String utteranceId) {
                if (!isActive(utteranceId)) return;
                mainHandler.post(() -> {
                    listener.onStatus("系统TTS播放出错");
                    finishMouth(false);
                });
            }

            @Override public void onStop(String utteranceId, boolean interrupted) {
                if (!isActive(utteranceId)) return;
                mainHandler.post(() -> finishMouth(false));
            }

            @Override public void onBeginSynthesis(String utteranceId, int rate,
                                                   int audioFormat, int channels) {
                if (!isActive(utteranceId)) return;
                synchronized (audioLock) {
                    sampleRate = Math.max(1, rate);
                    channelCount = Math.max(1, channels);
                    encoding = audioFormat;
                    samplesPerEnvelopeFrame = Math.max(1,
                            sampleRate * channelCount / ENVELOPE_HZ);
                    envelope.clear();
                    squareSum = 0.0;
                    squareSampleCount = 0;
                    carryLength = 0;
                }
            }

            @Override public void onAudioAvailable(String utteranceId, byte[] audio) {
                if (!isActive(utteranceId) || audio == null || audio.length == 0) return;
                appendPcm(audio);
            }
        });
        ready = language != TextToSpeech.LANG_MISSING_DATA
                && language != TextToSpeech.LANG_NOT_SUPPORTED;
        listener.onStatus(ready
                ? "系统TTS已就绪；按钮会循环播放4句中文"
                : "系统TTS缺少可用语言数据");
    }

    private boolean isActive(String utteranceId) {
        return !released && utteranceId != null && utteranceId.equals(activeUtteranceId);
    }

    private void beginPlayback(String utteranceId) {
        if (!isActive(utteranceId)) return;
        mouthGeneration++;
        speaking = true;
        playbackStartMs = SystemClock.elapsedRealtime();
        smoothedMouth = 0.0f;
        mainHandler.removeCallbacks(mouthTicker);
        mainHandler.post(mouthTicker);
        listener.onStatus("系统TTS正在播放 · 优先使用实际PCM音量驱动");
    }

    private void tickMouth() {
        if (!speaking || released) return;
        long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - playbackStartMs);
        int frame = (int) (elapsedMs * ENVELOPE_HZ / 1000L);
        float rms = envelopeAt(frame);
        float target = Float.isNaN(rms)
                ? fallbackMouth(elapsedMs)
                : normalizeRms(rms);
        float blend = target > smoothedMouth ? 0.48f : 0.22f;
        smoothedMouth += (target - smoothedMouth) * blend;
        listener.onMouthValue(smoothedMouth);
        mainHandler.postDelayed(mouthTicker, TICK_MS);
    }

    private void finishMouth(boolean completed) {
        if (released) return;
        speaking = false;
        activeUtteranceId = "";
        mainHandler.removeCallbacks(mouthTicker);
        final int closeGeneration = ++mouthGeneration;
        int frameCount;
        synchronized (audioLock) {
            frameCount = envelope.size();
        }
        final int closeSteps = 7;
        for (int step = 1; step <= closeSteps; step++) {
            final int currentStep = step;
            mainHandler.postDelayed(() -> {
                if (speaking || released || mouthGeneration != closeGeneration) return;
                float remaining = 1.0f - currentStep / (float) closeSteps;
                listener.onMouthValue(smoothedMouth * remaining);
                if (currentStep == closeSteps) smoothedMouth = 0.0f;
            }, step * TICK_MS);
        }
        if (completed) {
            listener.onStatus(frameCount > 0
                    ? "播放完成 · 使用实际PCM音量帧 " + frameCount + " 个"
                    : "播放完成 · 本机TTS未返回PCM，已使用节奏开合兜底");
        }
    }

    private void resetEnvelope() {
        synchronized (audioLock) {
            envelope.clear();
            squareSum = 0.0;
            squareSampleCount = 0;
            carryLength = 0;
        }
    }

    private float envelopeAt(int index) {
        synchronized (audioLock) {
            return index >= 0 && index < envelope.size() ? envelope.get(index) : Float.NaN;
        }
    }

    private void appendPcm(byte[] audio) {
        synchronized (audioLock) {
            int bytesPerSample = bytesPerSample(encoding);
            if (bytesPerSample <= 0) return;
            int combinedLength = carryLength + audio.length;
            byte[] combined = new byte[combinedLength];
            System.arraycopy(carry, 0, combined, 0, carryLength);
            System.arraycopy(audio, 0, combined, carryLength, audio.length);
            int usable = combinedLength - combinedLength % bytesPerSample;
            for (int offset = 0; offset < usable; offset += bytesPerSample) {
                float sample = decodeSample(combined, offset, encoding);
                if (!Float.isFinite(sample)) sample = 0.0f;
                sample = Math.max(-1.0f, Math.min(1.0f, sample));
                squareSum += sample * sample;
                squareSampleCount++;
                if (squareSampleCount >= samplesPerEnvelopeFrame) {
                    envelope.add((float) Math.sqrt(squareSum / squareSampleCount));
                    squareSum = 0.0;
                    squareSampleCount = 0;
                }
            }
            carryLength = combinedLength - usable;
            if (carry.length < bytesPerSample) carry = new byte[bytesPerSample];
            if (carryLength > 0) {
                System.arraycopy(combined, usable, carry, 0, carryLength);
            }
        }
    }

    private static int bytesPerSample(int audioFormat) {
        if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) return 1;
        if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) return 2;
        if (audioFormat == AudioFormat.ENCODING_PCM_FLOAT) return 4;
        return 0;
    }

    private static float decodeSample(byte[] data, int offset, int audioFormat) {
        if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) {
            return ((data[offset] & 0xff) - 128) / 128.0f;
        }
        int bits = (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8);
        if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
            return (short) bits / 32768.0f;
        }
        bits |= (data[offset + 2] & 0xff) << 16;
        bits |= (data[offset + 3] & 0xff) << 24;
        return Float.intBitsToFloat(bits);
    }

    private static float normalizeRms(float rms) {
        float level = Math.max(0.0f, (rms - 0.006f) / 0.18f);
        return Math.min(0.95f, (float) Math.pow(Math.min(1.0f, level), 0.58f));
    }

    private static float fallbackMouth(long elapsedMs) {
        double fast = Math.abs(Math.sin(elapsedMs * 0.020));
        double slow = 0.72 + 0.28 * Math.sin(elapsedMs * 0.006 + 0.7);
        return (float) Math.max(0.10, Math.min(0.72, fast * slow));
    }
}
