package com.catkiss.senlive2dcompanion;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only loader for the E.V repository's VTuber performance-pack data.
 *
 * <p>The JSON files are kept byte-for-byte as upstream assets. This class only translates their
 * generic pulse/sustain/gaze schema into small Java value objects; it does not contain Sen tuning.
 * Keeping the source data independent makes the faithful port removable without touching Sen's
 * existing performance engine.</p>
 */
final class EvMotionPack {
    static final String CLIPS_ASSET = "ev-vtuber-pack/clips.json";
    static final String VOCAB_ASSET = "ev-vtuber-pack/vocab.json";

    final Map<String, PulseClip> pulses;
    final Map<String, SustainClip> sustains;
    final Map<String, GazeTarget> gazes;
    final Map<String, Entry> entriesByClip;

    private EvMotionPack(Map<String, PulseClip> pulses,
                         Map<String, SustainClip> sustains,
                         Map<String, GazeTarget> gazes,
                         Map<String, Entry> entriesByClip) {
        this.pulses = Collections.unmodifiableMap(pulses);
        this.sustains = Collections.unmodifiableMap(sustains);
        this.gazes = Collections.unmodifiableMap(gazes);
        this.entriesByClip = Collections.unmodifiableMap(entriesByClip);
    }

    static EvMotionPack load(AssetManager assets) throws IOException {
        try {
            JSONObject clips = new JSONObject(readUtf8(assets, CLIPS_ASSET));
            JSONObject vocab = new JSONObject(readUtf8(assets, VOCAB_ASSET));
            Map<String, PulseClip> pulses = parsePulses(clips.getJSONObject("pulse"));
            Map<String, SustainClip> sustains = parseSustains(clips.getJSONObject("sustain"));
            Map<String, GazeTarget> gazes = parseGazes(clips.getJSONObject("gaze"));
            Map<String, Entry> entries = parseEntries(vocab.getJSONArray("entries"));
            return new EvMotionPack(pulses, sustains, gazes, entries);
        } catch (JSONException error) {
            throw new IOException("E.V动作包JSON无效", error);
        }
    }

    List<TestStep> diagnosticSteps() {
        List<TestStep> result = new ArrayList<>();
        result.add(new TestStep("ambient", "持续环境摇动", "ambient", 4.0f));
        result.add(new TestStep("blink", "自然眨眼", "blink", 1.2f));
        for (Map.Entry<String, PulseClip> item : pulses.entrySet()) {
            // speech_onset and accent_* are prosody tracks, but they are still motion primitives
            // and therefore stay in the forced diagnostic instead of being silently skipped.
            result.add(new TestStep("pulse", label(item.getKey()), item.getKey(),
                    item.getValue().durationMs / 1000.0f + .55f));
        }
        for (String id : sustains.keySet()) {
            result.add(new TestStep("sustain", label(id), id, 3.0f));
        }
        for (String id : gazes.keySet()) {
            result.add(new TestStep("gaze", label(id), id, 3.0f));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Sen-only calibration sweep. E.V's source pack has no torso semantic, so these steps stay
     * outside the byte-for-byte upstream clips and test the model's native BodyAngle axes
     * directly. Each signed level is held long enough for visual comparison on a phone.
     */
    List<TestStep> bodyDiagnosticSteps() {
        List<TestStep> result = new ArrayList<>();
        addBodyAxisSteps(result, "ParamBodyAngleX", "身体左右倾斜");
        addBodyAxisSteps(result, "ParamBodyAngleY", "身体前后倾斜");
        addBodyAxisSteps(result, "ParamBodyAngleZ", "身体侧向旋转");
        return Collections.unmodifiableList(result);
    }

    private static void addBodyAxisSteps(List<TestStep> target, String parameter,
                                         String chineseName) {
        float[] levels = {-5.0f, 5.0f, -10.0f, 10.0f, -15.0f, 15.0f};
        for (float level : levels) {
            String signed = level > 0.0f ? "+" + (int) level : Integer.toString((int) level);
            target.add(new TestStep("body", chineseName + " " + signed + "°",
                    parameter, 1.7f, level));
        }
    }

    String label(String clipId) {
        Entry entry = entriesByClip.get(clipId);
        if (entry != null) return entry.word + "（" + clipId + "）";
        switch (clipId) {
            case "speech_onset": return "句首抬头（speech_onset）";
            case "accent_nod": return "语气小点头（accent_nod）";
            case "accent_nod_overshoot": return "语气回弹点头（accent_nod_overshoot）";
            case "accent_swing": return "语气左右摆头（accent_swing）";
            case "accent_brow": return "语气抬眉（accent_brow）";
            default: return clipId;
        }
    }

    private static Map<String, PulseClip> parsePulses(JSONObject source) throws JSONException {
        Map<String, PulseClip> result = new LinkedHashMap<>();
        for (Iterator<String> ids = source.keys(); ids.hasNext(); ) {
            String id = ids.next();
            JSONObject object = source.getJSONObject(id);
            Map<String, List<Key>> tracks = new LinkedHashMap<>();
            JSONObject trackObject = object.getJSONObject("tracks");
            for (Iterator<String> parameters = trackObject.keys(); parameters.hasNext(); ) {
                String parameter = parameters.next();
                JSONArray keys = trackObject.getJSONArray(parameter);
                List<Key> parsed = new ArrayList<>();
                for (int i = 0; i < keys.length(); i++) {
                    JSONArray key = keys.getJSONArray(i);
                    parsed.add(new Key((float) key.getDouble(0), (float) key.getDouble(1),
                            key.length() >= 3 ? key.getString(2) : "smooth"));
                }
                tracks.put(parameter, Collections.unmodifiableList(parsed));
            }
            result.put(id, new PulseClip(id, (float) object.getDouble("durationMs"), tracks));
        }
        return result;
    }

    private static Map<String, SustainClip> parseSustains(JSONObject source)
            throws JSONException {
        Map<String, SustainClip> result = new LinkedHashMap<>();
        for (Iterator<String> ids = source.keys(); ids.hasNext(); ) {
            String id = ids.next();
            JSONObject holdsObject = source.getJSONObject(id).getJSONObject("hold");
            Map<String, Hold> holds = new LinkedHashMap<>();
            for (Iterator<String> parameters = holdsObject.keys(); parameters.hasNext(); ) {
                String parameter = parameters.next();
                JSONObject hold = holdsObject.getJSONObject(parameter);
                holds.put(parameter, new Hold(
                        (float) hold.getDouble("v"),
                        optionalFloat(hold, "settleTo"),
                        optionalFloat(hold, "settleMs"),
                        optionalFloat(hold, "noiseAmp"),
                        optionalFloat(hold, "noiseHz"),
                        optionalFloat(hold, "phase"),
                        hold.optString("noiseKind", "sine")));
            }
            result.put(id, new SustainClip(id, holds));
        }
        return result;
    }

    private static Map<String, GazeTarget> parseGazes(JSONObject source) throws JSONException {
        Map<String, GazeTarget> result = new LinkedHashMap<>();
        for (Iterator<String> ids = source.keys(); ids.hasNext(); ) {
            String id = ids.next();
            JSONObject object = source.getJSONObject(id);
            result.put(id, new GazeTarget(id,
                    (float) object.getDouble("eyeX"),
                    (float) object.getDouble("eyeY"),
                    (float) object.getDouble("headX"),
                    (float) object.getDouble("headY"),
                    (float) object.optDouble("scanRadiusDeg", 3.0)));
        }
        return result;
    }

    private static Map<String, Entry> parseEntries(JSONArray source) throws JSONException {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject object = source.getJSONObject(i);
            Entry entry = new Entry(object.getString("word"), object.getString("channel"),
                    object.getString("clipId"));
            if (!result.containsKey(entry.clipId)) result.put(entry.clipId, entry);
        }
        return result;
    }

    private static Float optionalFloat(JSONObject object, String key) throws JSONException {
        return object.has(key) ? (float) object.getDouble(key) : null;
    }

    private static String readUtf8(AssetManager assets, String path) throws IOException {
        try (InputStream input = assets.open(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return result.toString();
        }
    }

    static float driftNoise(float seconds, float hz, float phase) {
        float[] ratios = {1.0f, 1.73f, 2.61f, 4.13f};
        float value = 0.0f;
        float norm = 0.0f;
        for (int i = 0; i < ratios.length; i++) {
            float amplitude = 1.0f / ratios[i];
            value += amplitude * (float) Math.sin(
                    Math.PI * 2.0 * hz * ratios[i] * seconds + phase + i * 1.7);
            norm += amplitude;
        }
        return value / norm;
    }

    static float sample(List<Key> keys, float timeMs) {
        if (keys.isEmpty()) return 0.0f;
        if (timeMs <= keys.get(0).timeMs) return keys.get(0).value;
        Key last = keys.get(keys.size() - 1);
        if (timeMs >= last.timeMs) return last.value;
        for (int i = 1; i < keys.size(); i++) {
            Key next = keys.get(i);
            if (timeMs > next.timeMs) continue;
            Key previous = keys.get(i - 1);
            float progress = (timeMs - previous.timeMs) / (next.timeMs - previous.timeMs);
            float eased = ease(next.ease, progress);
            return previous.value + (next.value - previous.value) * eased;
        }
        return last.value;
    }

    private static float ease(String name, float progress) {
        float p = clamp(progress, 0.0f, 1.0f);
        switch (name) {
            case "in": return p * p * p;
            case "out": {
                float q = 1.0f - p;
                return 1.0f - q * q * q;
            }
            case "back": {
                float smooth = p * p * (3.0f - 2.0f * p);
                float c1 = 1.70158f;
                float v = smooth - 1.0f;
                return 1.0f + (c1 + 1.0f) * v * v * v + c1 * v * v;
            }
            default: return p * p * (3.0f - 2.0f * p);
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Key {
        final float timeMs;
        final float value;
        final String ease;

        Key(float timeMs, float value, String ease) {
            this.timeMs = timeMs;
            this.value = value;
            this.ease = ease;
        }
    }

    static final class PulseClip {
        final String id;
        final float durationMs;
        final Map<String, List<Key>> tracks;

        PulseClip(String id, float durationMs, Map<String, List<Key>> tracks) {
            this.id = id;
            this.durationMs = durationMs;
            this.tracks = Collections.unmodifiableMap(tracks);
        }
    }

    static final class Hold {
        final float value;
        final Float settleTo;
        final Float settleMs;
        final Float noiseAmp;
        final Float noiseHz;
        final Float phase;
        final String noiseKind;

        Hold(float value, Float settleTo, Float settleMs, Float noiseAmp, Float noiseHz,
             Float phase, String noiseKind) {
            this.value = value;
            this.settleTo = settleTo;
            this.settleMs = settleMs;
            this.noiseAmp = noiseAmp;
            this.noiseHz = noiseHz;
            this.phase = phase;
            this.noiseKind = noiseKind;
        }

        float sample(float elapsedMs) {
            float current = value;
            if (settleTo != null && settleMs != null && settleMs > 0.0f) {
                float p = clamp(elapsedMs / settleMs, 0.0f, 1.0f);
                p = p * p * (3.0f - 2.0f * p);
                current += (settleTo - value) * p;
            }
            if (noiseAmp != null && noiseHz != null) {
                float seconds = elapsedMs / 1000.0f;
                float wave = "drift".equals(noiseKind)
                        ? driftNoise(seconds, noiseHz, phase == null ? 0.0f : phase)
                        : (float) Math.sin(Math.PI * 2.0 * noiseHz * seconds
                        + (phase == null ? 0.0f : phase));
                current += noiseAmp * wave;
            }
            return current;
        }
    }

    static final class SustainClip {
        final String id;
        final Map<String, Hold> holds;

        SustainClip(String id, Map<String, Hold> holds) {
            this.id = id;
            this.holds = Collections.unmodifiableMap(holds);
        }
    }

    static final class GazeTarget {
        final String id;
        final float eyeX;
        final float eyeY;
        final float headX;
        final float headY;
        final float scanRadiusDeg;

        GazeTarget(String id, float eyeX, float eyeY, float headX, float headY,
                   float scanRadiusDeg) {
            this.id = id;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.headX = headX;
            this.headY = headY;
            this.scanRadiusDeg = scanRadiusDeg;
        }
    }

    static final class Entry {
        final String word;
        final String channel;
        final String clipId;

        Entry(String word, String channel, String clipId) {
            this.word = word;
            this.channel = channel;
            this.clipId = clipId;
        }
    }

    static final class TestStep {
        final String kind;
        final String label;
        final String id;
        final float durationSeconds;
        final float diagnosticValue;

        TestStep(String kind, String label, String id, float durationSeconds) {
            this(kind, label, id, durationSeconds, 0.0f);
        }

        TestStep(String kind, String label, String id, float durationSeconds,
                 float diagnosticValue) {
            this.kind = kind;
            this.label = label;
            this.id = id;
            this.durationSeconds = durationSeconds;
            this.diagnosticValue = diagnosticValue;
        }
    }
}
