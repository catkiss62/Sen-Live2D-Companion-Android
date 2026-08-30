package com.catkiss.senlive2dcompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Small parameter performance layer shared by manual tests and the later AI companion.
 * It never owns appearance/part-selection parameters: every frame starts from the captured
 * VTS base, then this class adds only face/body performance offsets before native physics.
 */
final class SenPerformanceEngine {
    interface ParameterWriter {
        void add(String id, float value);
        void set(String id, float value);
    }

    static final List<String> EMOTIONS = Collections.unmodifiableList(Arrays.asList(
            "normal", "happy", "excited", "affection", "shy",
            "flustered", "tense", "worried", "confused", "helpless",
            "afraid", "angry", "sad", "disgust", "serious",
            "surprised", "confident", "playful", "ashamed", "calm"));

    static final List<String> ACTIONS = Collections.unmodifiableList(Arrays.asList(
            "nod", "shake_head", "tilt_head", "lean_forward", "lean_back",
            "blink_surprised", "sigh", "pout", "excited_bounce", "listening",
            "look_around", "soft_sway", "look_down_up", "small_nod", "head_tilt_idle",
            "side_look", "weight_shift", "gentle_lean", "sigh_sink", "slow_blink",
            "wind_sway_soft", "wind_sway_medium", "wind_sway_showcase", "showcase_orbit"));

    private static final List<String> IDLE_ACTIONS = Arrays.asList(
            "small_nod", "head_tilt_idle", "side_look", "weight_shift",
            "gentle_lean", "slow_blink", "look_around", "soft_sway", "wind_sway_soft");

    private final Random random = new Random();
    private String emotion = "normal";
    private String action;
    private String previousIdle;
    private float actionTime;
    private float elapsed;
    private float nextIdleAt = 8.0f;
    private float earTwitchTime = -1.0f;
    private boolean autoIdle;

    void selectEmotion(String name) {
        if (EMOTIONS.contains(name)) emotion = name;
    }

    String getEmotion() {
        return emotion;
    }

    void playAction(String name) {
        if (!ACTIONS.contains(name)) return;
        action = name;
        actionTime = 0.0f;
    }

    void triggerEarTwitch() {
        earTwitchTime = 0.0f;
    }

    void setAutoIdle(boolean enabled) {
        autoIdle = enabled;
        nextIdleAt = elapsed + 4.0f + random.nextFloat() * 5.0f;
    }

    boolean isAutoIdle() {
        return autoIdle;
    }

    String getActionLabel() {
        return action == null ? "none" : action;
    }

    void update(float deltaSeconds, ParameterWriter writer) {
        float dt = Math.max(0.0f, Math.min(0.05f, deltaSeconds));
        elapsed += dt;
        applyEmotion(writer);

        if (autoIdle && action == null && elapsed >= nextIdleAt) {
            String next;
            do {
                next = IDLE_ACTIONS.get(random.nextInt(IDLE_ACTIONS.size()));
            } while (IDLE_ACTIONS.size() > 1 && next.equals(previousIdle));
            previousIdle = next;
            playAction(next);
            nextIdleAt = elapsed + 7.0f + random.nextFloat() * 8.0f;
        }

        if (action != null) {
            actionTime += dt;
            applyAction(action, actionTime, writer);
            if (actionTime >= duration(action)) action = null;
        }

        if (earTwitchTime >= 0.0f) earTwitchTime += dt;
    }

    /** A two-pulse signal sampled by native ear physics and then removed before final drawing. */
    float getEarPhysicsImpulse() {
        if (earTwitchTime < 0.0f) return 0.0f;
        if (earTwitchTime >= 0.72f) {
            earTwitchTime = -1.0f;
            return 0.0f;
        }
        float phase = earTwitchTime < 0.30f
                ? earTwitchTime / 0.30f
                : (earTwitchTime - 0.38f) / 0.30f;
        if (phase < 0.0f || phase > 1.0f) return 0.0f;
        return (float) Math.sin(Math.PI * phase);
    }

    float getBreathValue() {
        return 0.50f + 0.42f * (float) Math.sin(elapsed * 1.55f);
    }

    private void applyEmotion(ParameterWriter writer) {
        switch (emotion) {
            case "happy":
                smile(writer, 0.55f, 0.14f); break;
            case "excited":
                smile(writer, 0.75f, 0.30f); writer.add("ParamEyeBallY", 0.12f); break;
            case "affection":
                smile(writer, 0.45f, 0.10f); writer.add("Param13", 0.65f); break;
            case "shy":
                smile(writer, 0.18f, -0.05f); writer.add("Param13", 0.75f);
                writer.add("ParamEyeBallY", -0.18f); break;
            case "flustered":
            case "tense":
                writer.add("Param13", 0.55f); writer.add("ParamMouthForm", -0.28f);
                writer.add("ParamBrowLY", 0.18f); writer.add("ParamBrowRY", 0.18f); break;
            case "worried":
                writer.add("ParamMouthForm", -0.42f); writer.add("ParamBrowLY", 0.30f);
                writer.add("ParamBrowRY", 0.30f); break;
            case "confused":
                writer.add("ParamAngleZ", 8.0f); writer.add("ParamBrowLY", 0.25f);
                writer.add("ParamBrowRY", -0.08f); break;
            case "helpless":
                writer.add("ParamAngleZ", -5.0f); writer.add("ParamMouthForm", -0.35f);
                writer.add("ParamEyeLOpen", -0.18f); writer.add("ParamEyeROpen", -0.18f); break;
            case "afraid":
                writer.add("ParamEyeLOpen", 0.30f); writer.add("ParamEyeROpen", 0.30f);
                writer.add("ParamMouthOpenY", 0.18f); writer.add("ParamBrowLY", 0.35f);
                writer.add("ParamBrowRY", 0.35f); break;
            case "angry":
                writer.add("ParamMouthForm", -0.55f); writer.add("ParamBrowLY", -0.32f);
                writer.add("ParamBrowRY", -0.32f); writer.add("ParamEyeLOpen", -0.12f);
                writer.add("ParamEyeROpen", -0.12f); break;
            case "sad":
                writer.add("ParamMouthForm", -0.58f); writer.add("ParamBrowLY", 0.36f);
                writer.add("ParamBrowRY", 0.36f); writer.add("ParamEyeBallY", -0.16f); break;
            case "disgust":
                writer.add("ParamMouthForm", -0.62f); writer.add("ParamAngleZ", -4.0f);
                writer.add("ParamEyeLOpen", -0.24f); writer.add("ParamEyeROpen", -0.10f); break;
            case "serious":
                writer.add("ParamMouthForm", -0.20f); writer.add("ParamEyeLOpen", -0.13f);
                writer.add("ParamEyeROpen", -0.13f); break;
            case "surprised":
                writer.add("ParamEyeLOpen", 0.42f); writer.add("ParamEyeROpen", 0.42f);
                writer.add("ParamMouthOpenY", 0.38f); break;
            case "confident":
                smile(writer, 0.35f, 0.05f); writer.add("ParamAngleY", 4.0f);
                writer.add("ParamAngleZ", -3.0f); break;
            case "playful":
                smile(writer, 0.62f, 0.12f); writer.add("ParamEyeLOpen", -0.46f);
                writer.add("ParamAngleZ", 7.0f); break;
            case "ashamed":
                writer.add("Param13", 0.82f); writer.add("ParamEyeBallY", -0.25f);
                writer.add("ParamMouthForm", -0.18f); break;
            case "calm":
                smile(writer, 0.12f, 0.0f); writer.add("ParamEyeLOpen", -0.14f);
                writer.add("ParamEyeROpen", -0.14f); break;
            default:
                break;
        }
    }

    private static void smile(ParameterWriter writer, float smile, float mouthOpen) {
        writer.add("ParamMouthForm", smile);
        writer.add("ParamMouthOpenY", mouthOpen);
        writer.add("ParamEyeLSmile", smile * 0.55f);
        writer.add("ParamEyeRSmile", smile * 0.55f);
    }

    private static void applyAction(String name, float t, ParameterWriter writer) {
        float d = duration(name);
        float p = Math.min(1.0f, t / d);
        float envelope = (float) Math.sin(Math.PI * p);
        float wave1 = (float) Math.sin(Math.PI * 2.0f * p);
        float wave2 = (float) Math.sin(Math.PI * 4.0f * p);
        switch (name) {
            case "nod": writer.add("ParamAngleY", -16.0f * envelope * envelope); break;
            case "small_nod": writer.add("ParamAngleY", -8.0f * envelope * envelope); break;
            case "shake_head": writer.add("ParamAngleX", 16.0f * wave2 * envelope); break;
            case "tilt_head": writer.add("ParamAngleZ", 13.0f * envelope); break;
            case "head_tilt_idle": writer.add("ParamAngleZ", -9.0f * envelope); break;
            case "lean_forward": writer.add("ParamAngleY", -10.0f * envelope);
                writer.add("ParamBodyAngleY", -6.0f * envelope); break;
            case "lean_back": writer.add("ParamAngleY", 11.0f * envelope);
                writer.add("ParamBodyAngleY", 7.0f * envelope); break;
            case "blink_surprised": blink(writer, p, 0.20f); writer.add("ParamAngleY", 8.0f * envelope); break;
            case "sigh": writer.add("ParamAngleY", -7.0f * envelope);
                writer.add("ParamMouthOpenY", 0.22f * envelope); break;
            case "pout": writer.add("ParamMouthForm", -0.70f * envelope);
                writer.add("ParamMouthFunnel", 0.45f * envelope); break;
            case "excited_bounce": writer.add("ParamBodyAngleY", 10.0f * wave2 * envelope);
                writer.add("ParamAngleY", -9.0f * wave2 * envelope); break;
            case "listening": writer.add("ParamAngleZ", 8.0f * envelope);
                writer.add("ParamAngleY", -5.0f * envelope); break;
            case "look_around": writer.add("ParamAngleX", 18.0f * wave1);
                writer.add("ParamEyeBallX", 0.65f * wave1); break;
            case "soft_sway": writer.add("ParamAngleZ", 7.0f * wave1);
                writer.add("ParamBodyAngleX", 8.0f * wave1); break;
            case "look_down_up": writer.add("ParamAngleY", -14.0f * wave1);
                writer.add("ParamEyeBallY", -0.48f * wave1); break;
            case "side_look": writer.add("ParamAngleX", 13.0f * envelope);
                writer.add("ParamEyeBallX", 0.55f * envelope); break;
            case "weight_shift": writer.add("ParamBodyAngleX", 12.0f * wave1);
                writer.add("ParamAngleZ", -5.0f * wave1); break;
            case "gentle_lean": writer.add("ParamBodyAngleX", 8.0f * envelope);
                writer.add("ParamAngleZ", 6.0f * envelope); break;
            case "sigh_sink": writer.add("ParamAngleY", -12.0f * envelope);
                writer.add("ParamBodyAngleY", -8.0f * envelope); break;
            case "slow_blink": blink(writer, p, 0.72f); break;
            case "wind_sway_soft": sway(writer, p, 8.0f, 10.0f); break;
            case "wind_sway_medium": sway(writer, p, 13.0f, 17.0f); break;
            case "wind_sway_showcase": sway(writer, p, 18.0f, 24.0f); break;
            case "showcase_orbit": writer.add("ParamAngleX", 19.0f * wave1);
                writer.add("ParamAngleY", 12.0f * (float) Math.cos(Math.PI * 2.0f * p));
                writer.add("ParamAngleZ", 7.0f * wave1);
                writer.add("ParamBodyAngleX", 20.0f * wave1); break;
            default: break;
        }
    }

    private static void blink(ParameterWriter writer, float p, float strength) {
        float close = (float) Math.pow(Math.sin(Math.PI * p), 4.0);
        writer.add("ParamEyeLOpen", -strength * close);
        writer.add("ParamEyeROpen", -strength * close);
    }

    private static void sway(ParameterWriter writer, float p, float head, float body) {
        float wave = (float) Math.sin(Math.PI * 4.0f * p);
        float envelope = (float) Math.sin(Math.PI * p);
        writer.add("ParamAngleZ", head * wave * envelope);
        writer.add("ParamAngleX", head * 0.42f * wave * envelope);
        writer.add("ParamBodyAngleX", body * wave * envelope);
    }

    private static float duration(String name) {
        switch (name) {
            case "look_around": case "look_down_up": return 3.8f;
            case "soft_sway": case "wind_sway_soft": return 4.8f;
            case "wind_sway_medium": return 5.2f;
            case "wind_sway_showcase": case "showcase_orbit": return 6.0f;
            case "slow_blink": return 2.4f;
            default: return 2.2f;
        }
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "emotion=%s action=%s auto=%s",
                emotion, getActionLabel(), autoIdle);
    }
}
