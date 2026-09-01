package com.catkiss.senlive2dcompanion;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Small parameter performance layer shared by manual tests and the later AI companion.
 * It never owns appearance/part-selection parameters: every frame starts from the captured
 * VTS base, then this class adds only face/body performance offsets before native physics.
 */
final class SenPerformanceEngine {
    private static final float HEAD_GAIN = 1.35f;
    private static final float BODY_GAIN = 1.20f;
    private static final float EMOTION_TRANSITION_SECONDS = .42f;
    private static final float ACTION_CROSSFADE_SECONDS = .24f;
    private static final float ACTION_PHYSICS_RELEASE_SECONDS = .55f;
    // Mimeng closes and opens in about .20 s. Sen's eye-open baseline clips sooner when an
    // additive close value is used, so the same numeric timing looks noticeably sharper here.
    // Keep the close brisk, add a tiny closed hold, and let the eyes reopen more gently.
    private static final float NATURAL_BLINK_CLOSE_SECONDS = .13f;
    private static final float NATURAL_BLINK_HOLD_SECONDS = .04f;
    private static final float NATURAL_BLINK_OPEN_SECONDS = .17f;
    private static final float NATURAL_BLINK_SECONDS = NATURAL_BLINK_CLOSE_SECONDS
            + NATURAL_BLINK_HOLD_SECONDS + NATURAL_BLINK_OPEN_SECONDS;
    private static final float EAR_PULSE_SECONDS = .34f;
    private static final float EAR_PULSE_GAP_SECONDS = .10f;
    private static final float EAR_SETTLE_SECONDS = .82f;
    private static final float DEFAULT_EAR_SPEED = 1.35f;
    private static final float DEFAULT_EAR_AMPLITUDE = 1.00f;
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
            "wind_sway_soft", "wind_sway_medium", "wind_sway_showcase", "showcase_orbit",
            "head_pat", "head_pat_confused"));

    // These seven routes have been confirmed as autonomous-idle responsibilities, so they stay
    // in ACTIONS/MOTIONS but no longer occupy manual test buttons:
    // 环顾 look_around、待机歪头 head_tilt_idle、叹气下沉 sigh_sink；
    // 柔风摆动 wind_sway_soft（持续底层）；明显风摆 wind_sway_medium、
    // 展示级大摆 wind_sway_showcase、视频式环绕 showcase_orbit（低频展示池）。
    private static final List<String> AUTO_IDLE_ONLY_ACTIONS = Arrays.asList(
            "look_around", "head_tilt_idle", "sigh_sink", "wind_sway_soft",
            "wind_sway_medium", "wind_sway_showcase", "showcase_orbit");
    static final List<String> MANUAL_TEST_ACTIONS = createManualTestActions();

    private static final Map<String, Motion> MOTIONS = createMotions();

    static {
        validateMotionLibrary();
    }

    // 常规自主待机池：环顾、待机歪头和叹气下沉保留在这里；重复项是随机权重。
    // 柔风摆动不在本池抽取，因为 update() 会在自主待机期间持续叠加它。
    // 明显风摆 / 视频式环绕 / 展示级大摆由 update() 的低频展示池按
    // 55% / 31% / 14% 抽取，首次28～60秒，随后48～100秒。
    private static final List<String> ROUTINE_IDLE_ACTIONS = Arrays.asList(
            "head_tilt_idle", "head_tilt_idle", "side_look", "weight_shift",
            "gentle_lean", "slow_blink", "look_around", "look_around",
            "soft_sway", "soft_sway", "small_nod", "sigh_sink");

    private final Random random = new Random();
    private String emotion = "normal";
    private String action;
    private final List<String> recentIdleActions = new ArrayList<>();
    private float actionTime;
    private float elapsed;
    private float nextIdleAt = 8.0f;
    private float nextShowcaseAt = 30.0f;
    private float nextBlinkAt = 2.2f;
    private float nextIdleEarTwitchAt = 24.0f;
    private float blinkTime = -1.0f;
    private float earTwitchTime = -1.0f;
    private float earSpeed = DEFAULT_EAR_SPEED;
    private float earAmplitude = DEFAULT_EAR_AMPLITUDE;
    private boolean autoIdle;
    private Map<String, Float> lastActionValues = new HashMap<>();
    private Map<String, Float> actionTransitionFromValues = new HashMap<>();
    private float actionTransitionTime = ACTION_CROSSFADE_SECONDS;
    private Map<String, Float> emotionFromValues = new HashMap<>();
    private Map<String, Float> emotionCurrentValues = new HashMap<>();
    private Map<String, Float> emotionTargetValues = new HashMap<>();
    private float emotionTransitionTime = EMOTION_TRANSITION_SECONDS;
    private boolean touchFollowEnabled = true;
    private boolean touchActive;
    private float touchTargetX;
    private float touchTargetY;
    private float touchEyeX;
    private float touchEyeY;
    private float touchHeadX;
    private float touchHeadY;
    private float touchHeadZ;
    private float touchBodyX;
    private float touchBodyY;
    private float touchBodyZ;

    void selectEmotion(String name) {
        if (!EMOTIONS.contains(name) || name.equals(emotion)) return;
        emotion = name;
        emotionFromValues = new HashMap<>(emotionCurrentValues);
        emotionTargetValues = collectEmotion(name);
        emotionTransitionTime = 0.0f;
    }

    String getEmotion() {
        return emotion;
    }

    void playAction(String name) {
        if (!ACTIONS.contains(name)) return;
        actionTransitionFromValues = new HashMap<>(lastActionValues);
        actionTransitionTime = actionTransitionFromValues.isEmpty()
                ? ACTION_CROSSFADE_SECONDS : 0.0f;
        action = name;
        actionTime = 0.0f;
    }

    void triggerEarTwitch() {
        earTwitchTime = 0.0f;
    }

    void setEarTuning(float speedPercent, float amplitudePercent) {
        earSpeed = clamp(speedPercent / 100.0f, .50f, 2.50f);
        earAmplitude = clamp(amplitudePercent / 100.0f, .50f, 2.50f);
    }

    void setTouchFollowEnabled(boolean enabled) {
        touchFollowEnabled = enabled;
        if (!enabled) touchActive = false;
    }

    void setTouchTarget(boolean active, float normalizedX, float normalizedY) {
        touchActive = active && touchFollowEnabled;
        touchTargetX = clamp(normalizedX, -1.0f, 1.0f);
        touchTargetY = clamp(normalizedY, -1.0f, 1.0f);
    }

    void triggerHeadPat(boolean confused) {
        playAction(confused ? "head_pat_confused" : "head_pat");
    }

    void setAutoIdle(boolean enabled) {
        autoIdle = enabled;
        nextIdleAt = elapsed + 4.0f + random.nextFloat() * 5.0f;
        nextShowcaseAt = elapsed + 28.0f + random.nextFloat() * 32.0f;
        nextBlinkAt = elapsed + 1.2f + random.nextFloat() * 2.0f;
        nextIdleEarTwitchAt = elapsed + 18.0f + random.nextFloat() * 20.0f;
        if (!enabled) blinkTime = -1.0f;
    }

    boolean isAutoIdle() {
        return autoIdle;
    }

    String getActionLabel() {
        return action == null ? "none" : action;
    }

    boolean isActionActive() {
        return action != null;
    }

    float getActionArmPhysicsGain() {
        if (action == null) return 1.0f;
        float duration = duration(action);
        if (actionTime <= duration) return .35f;
        float progress = clamp((actionTime - duration) / ACTION_PHYSICS_RELEASE_SECONDS,
                0.0f, 1.0f);
        return .35f + .65f * smoothStep(progress);
    }

    void update(float deltaSeconds, ParameterWriter writer) {
        float dt = Math.max(0.0f, Math.min(0.05f, deltaSeconds));
        elapsed += dt;
        updateEmotion(dt, writer);
        updateTouchFollow(dt, writer);

        // The real wind_sway_soft route is now the continuous idle base. It is not a small
        // optional overlay: it replaces the old still-standing state, including between and
        // underneath random actions, as Mimeng keeps its idle layer active under actions.
        if (autoIdle) applyLoopingSoftWindIdle(writer);

        if (autoIdle && action == null) {
            if (elapsed >= nextIdleEarTwitchAt) {
                triggerEarTwitch();
                nextIdleEarTwitchAt = elapsed + 28.0f + random.nextFloat() * 40.0f;
            }
            if (elapsed >= nextShowcaseAt) {
                float roll = random.nextFloat();
                String next = roll < .55f ? "wind_sway_medium"
                        : (roll < .86f ? "showcase_orbit" : "wind_sway_showcase");
                rememberIdleAction(next);
                playAction(next);
                nextShowcaseAt = elapsed + 48.0f + random.nextFloat() * 52.0f;
                nextIdleAt = elapsed + 8.0f + random.nextFloat() * 6.0f;
            } else if (elapsed >= nextIdleAt) {
                String next = chooseRoutineIdleAction();
                rememberIdleAction(next);
                playAction(next);
                nextIdleAt = elapsed + 4.8f + random.nextFloat() * 6.7f;
            }
        }

        updateAction(dt, writer);
        updateNaturalBlink(dt, writer);

        if (earTwitchTime >= 0.0f) {
            earTwitchTime += dt;
            if (earTwitchTime >= earInputSeconds() + earSettleSeconds()) {
                earTwitchTime = -1.0f;
            }
        }
    }

    /** Two broader copies of the slow-blink driver, evaluated only by the isolated ear rig. */
    float getEarPhysicsDrive() {
        if (earTwitchTime < 0.0f) return 0.0f;
        float pulseSeconds = EAR_PULSE_SECONDS / earSpeed;
        float gapSeconds = EAR_PULSE_GAP_SECONDS / earSpeed;
        if (earTwitchTime >= pulseSeconds * 2.0f + gapSeconds) return 0.0f;
        float secondStart = pulseSeconds + gapSeconds;
        float phase = earTwitchTime < pulseSeconds
                ? earTwitchTime / pulseSeconds
                : (earTwitchTime - secondStart) / pulseSeconds;
        if (phase < 0.0f || phase > 1.0f) return 0.0f;
        return earAmplitude * (float) Math.pow(Math.sin(Math.PI * phase), 1.15);
    }

    boolean isEarPhysicsActive() {
        return earTwitchTime >= 0.0f;
    }

    float getEarPhysicsMix() {
        if (earTwitchTime < 0.0f) return 0.0f;
        float inputSeconds = earInputSeconds();
        if (earTwitchTime <= inputSeconds) return 1.0f;
        float release = (earTwitchTime - inputSeconds) / earSettleSeconds();
        return 1.0f - smoothStep(release);
    }

    private float earInputSeconds() {
        return (EAR_PULSE_SECONDS * 2.0f + EAR_PULSE_GAP_SECONDS) / earSpeed;
    }

    private float earSettleSeconds() {
        // Keep enough inertia after a fast pulse; scaling only halfway avoids cutting the
        // native physics tail as aggressively as the input itself.
        return EAR_SETTLE_SECONDS / (float) Math.sqrt(earSpeed);
    }

    private void applyLoopingSoftWindIdle(ParameterWriter writer) {
        float duration = duration("wind_sway_soft");
        float phaseA = elapsed % duration;
        float phaseB = (phaseA + duration * .5f) % duration;
        String[] ids = {"ParamAngleX", "ParamAngleY", "ParamAngleZ",
                "ParamBodyAngleX", "ParamBodyAngleY", "ParamBodyAngleZ",
                "ParamEyeBallX", "ParamEyeBallY"};
        float[] gains = {HEAD_GAIN, HEAD_GAIN, HEAD_GAIN,
                BODY_GAIN, BODY_GAIN, BODY_GAIN, 1.0f, 1.0f};
        for (int channel = 0; channel < ids.length; channel++) {
            // Two half-cycle-offset copies cover each other's zero envelope, so the exact
            // wind_sway_soft path loops without returning to a visible still pose.
            float value = (wind(phaseA, duration, .68f, channel)
                    + wind(phaseB, duration, .68f, channel)) * .72f * gains[channel];
            if (channel == 0) value = clamp(value, -24.0f, 24.0f);
            writer.add(ids[channel], value);
        }
    }

    private String chooseRoutineIdleAction() {
        List<String> candidates = new ArrayList<>();
        for (String name : ROUTINE_IDLE_ACTIONS) {
            if (!recentIdleActions.contains(name)) candidates.add(name);
        }
        List<String> pool = candidates.isEmpty() ? ROUTINE_IDLE_ACTIONS : candidates;
        return pool.get(random.nextInt(pool.size()));
    }

    private void rememberIdleAction(String name) {
        recentIdleActions.add(name);
        while (recentIdleActions.size() > 3) recentIdleActions.remove(0);
    }

    float getBreathValue() {
        return 0.50f + 0.42f * (float) Math.sin(elapsed * 1.55f);
    }

    private void updateEmotion(float dt, ParameterWriter writer) {
        if (emotionTransitionTime < EMOTION_TRANSITION_SECONDS) {
            emotionTransitionTime = Math.min(EMOTION_TRANSITION_SECONDS,
                    emotionTransitionTime + dt);
            float progress = smoothStep(emotionTransitionTime / EMOTION_TRANSITION_SECONDS);
            Map<String, Float> blended = new HashMap<>();
            LinkedHashSet<String> keys = new LinkedHashSet<>(emotionFromValues.keySet());
            keys.addAll(emotionTargetValues.keySet());
            for (String key : keys) {
                float from = emotionFromValues.getOrDefault(key, 0.0f);
                float to = emotionTargetValues.getOrDefault(key, 0.0f);
                float value = from + (to - from) * progress;
                if (Math.abs(value) > .00001f) blended.put(key, value);
            }
            emotionCurrentValues = blended;
        } else if (!emotionCurrentValues.equals(emotionTargetValues)) {
            emotionCurrentValues = new HashMap<>(emotionTargetValues);
        }
        for (Map.Entry<String, Float> entry : emotionCurrentValues.entrySet()) {
            writer.add(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, Float> collectEmotion(String name) {
        Map<String, Float> values = new LinkedHashMap<>();
        applyEmotion(name, new ParameterWriter() {
            @Override public void add(String id, float value) {
                values.merge(id, value, Float::sum);
            }

            @Override public void set(String id, float value) {
                values.put(id, value);
            }
        });
        return values;
    }

    private void updateTouchFollow(float dt, ParameterWriter writer) {
        float targetX = touchActive && touchFollowEnabled ? touchTargetX : 0.0f;
        float targetY = touchActive && touchFollowEnabled ? touchTargetY : 0.0f;
        float eyeBlend = exponentialBlend(dt, .075f);
        float headBlend = exponentialBlend(dt, .19f);
        float rollBlend = exponentialBlend(dt, .25f);
        float bodyBlend = exponentialBlend(dt, .43f);
        touchEyeX += (targetX - touchEyeX) * eyeBlend;
        touchEyeY += (targetY - touchEyeY) * eyeBlend;
        touchHeadX += (targetX - touchHeadX) * headBlend;
        touchHeadY += (targetY - touchHeadY) * headBlend;
        float rollTarget = -targetX * targetY;
        touchHeadZ += (rollTarget - touchHeadZ) * rollBlend;
        touchBodyX += (targetX - touchBodyX) * bodyBlend;
        touchBodyY += (targetY - touchBodyY) * bodyBlend;
        touchBodyZ += (rollTarget - touchBodyZ) * bodyBlend;

        boolean patActive = "head_pat".equals(action) || "head_pat_confused".equals(action);
        float headWeight = patActive ? .25f : 1.0f;
        float bodyWeight = patActive ? .15f : 1.0f;
        writer.add("ParamEyeBallX", touchEyeX);
        writer.add("ParamEyeBallY", touchEyeY);
        writer.add("ParamAngleX", touchHeadX * 30.0f * headWeight);
        writer.add("ParamAngleY", touchHeadY * 30.0f * headWeight);
        writer.add("ParamAngleZ", touchHeadZ * 30.0f * headWeight);
        writer.add("ParamBodyAngleX", touchBodyX * 8.0f * bodyWeight);
        writer.add("ParamBodyAngleY", touchBodyY * .70f * bodyWeight);
        writer.add("ParamBodyAngleZ", touchBodyZ * 7.0f * bodyWeight);
    }

    private void updateAction(float dt, ParameterWriter writer) {
        if (action == null) {
            lastActionValues.clear();
            return;
        }
        actionTime += dt;
        float actionDuration = duration(action);
        Map<String, Float> target = collectActionValues(
                action, Math.min(actionTime, actionDuration));
        Map<String, Float> current = target;
        if (actionTransitionTime < ACTION_CROSSFADE_SECONDS) {
            actionTransitionTime = Math.min(ACTION_CROSSFADE_SECONDS,
                    actionTransitionTime + dt);
            float progress = smoothStep(actionTransitionTime / ACTION_CROSSFADE_SECONDS);
            current = blendValues(actionTransitionFromValues, target, progress);
        }
        writeValues(current, writer);
        lastActionValues = current;
        if (actionTime >= actionDuration + ACTION_PHYSICS_RELEASE_SECONDS) {
            action = null;
            lastActionValues = new HashMap<>();
            actionTransitionFromValues = new HashMap<>();
            actionTransitionTime = ACTION_CROSSFADE_SECONDS;
        }
    }

    private void updateNaturalBlink(float dt, ParameterWriter writer) {
        if (!autoIdle) return;
        if (blinkTime < 0.0f && elapsed >= nextBlinkAt) {
            if (actionTouchesEyeOpen(action)) {
                nextBlinkAt = elapsed + .55f;
                return;
            }
            blinkTime = 0.0f;
        }
        if (blinkTime < 0.0f) return;
        blinkTime += dt;
        float close;
        if (blinkTime < NATURAL_BLINK_CLOSE_SECONDS) {
            close = smoothStep(blinkTime / NATURAL_BLINK_CLOSE_SECONDS);
        } else if (blinkTime < NATURAL_BLINK_CLOSE_SECONDS + NATURAL_BLINK_HOLD_SECONDS) {
            close = 1.0f;
        } else {
            float openTime = blinkTime - NATURAL_BLINK_CLOSE_SECONDS
                    - NATURAL_BLINK_HOLD_SECONDS;
            close = 1.0f - smoothStep(openTime / NATURAL_BLINK_OPEN_SECONDS);
        }
        // Applied after the active action, matching Mimeng's final min-to-closed blink layer.
        writer.add("ParamEyeLOpen", -1.50f * close);
        writer.add("ParamEyeROpen", -1.50f * close);
        if (blinkTime >= NATURAL_BLINK_SECONDS) {
            blinkTime = -1.0f;
            nextBlinkAt = elapsed + 2.2f + random.nextFloat() * 3.2f;
        }
    }

    private static boolean actionTouchesEyeOpen(String name) {
        Motion motion = MOTIONS.get(name);
        if (motion == null) return false;
        for (Track track : motion.tracks) {
            if ("ParamEyeLOpen".equals(track.parameterId)
                    || "ParamEyeROpen".equals(track.parameterId)) return true;
        }
        return false;
    }

    private static float exponentialBlend(float dt, float timeConstant) {
        return 1.0f - (float) Math.exp(-dt / Math.max(.001f, timeConstant));
    }

    private void applyEmotion(String name, ParameterWriter writer) {
        switch (name) {
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
                writer.add("ParamEyeLOpen", -0.25f); writer.add("ParamEyeROpen", 0.05f);
                writer.add("ParamEyeBallX", 0.25f); writer.add("ParamBrowLY", -0.25f);
                writer.add("ParamBrowRY", 0.45f); writer.add("ParamMouthForm", -0.20f);
                writer.add("ParamAngleZ", -7.0f); break;
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
        Motion motion = MOTIONS.get(name);
        if (motion == null) return;
        boolean yawLimited = name.startsWith("wind_sway_") || "showcase_orbit".equals(name);
        for (Track track : motion.tracks) {
            float value = track.sample(t) * track.gain;
            if (yawLimited && "ParamAngleX".equals(track.parameterId)) {
                value = Math.max(-24.0f, Math.min(24.0f, value));
            }
            writer.add(track.parameterId, value);
        }
    }

    private static Map<String, Float> collectActionValues(String name, float time) {
        Map<String, Float> values = new LinkedHashMap<>();
        applyAction(name, time, new ParameterWriter() {
            @Override public void add(String id, float value) {
                values.merge(id, value, Float::sum);
            }

            @Override public void set(String id, float value) {
                values.put(id, value);
            }
        });
        return values;
    }

    private static Map<String, Float> blendValues(Map<String, Float> from,
                                                   Map<String, Float> to,
                                                   float progress) {
        Map<String, Float> values = new LinkedHashMap<>();
        LinkedHashSet<String> keys = new LinkedHashSet<>(from.keySet());
        keys.addAll(to.keySet());
        for (String key : keys) {
            float value = from.getOrDefault(key, 0.0f)
                    + (to.getOrDefault(key, 0.0f) - from.getOrDefault(key, 0.0f)) * progress;
            if (Math.abs(value) > .00001f) values.put(key, value);
        }
        return values;
    }

    private static void writeValues(Map<String, Float> values, ParameterWriter writer) {
        for (Map.Entry<String, Float> entry : values.entrySet()) {
            writer.add(entry.getKey(), entry.getValue());
        }
    }

    private static float duration(String name) {
        Motion motion = MOTIONS.get(name);
        return motion == null ? 2.2f : motion.duration;
    }

    /**
     * The keyframes below are the already device-approved Mimeng routes. Sen uses the standard
     * Angle/BodyAngle parameters, so only a small model-specific pose gain is added here. Arm
     * physics is deliberately not reduced in this table; it is damped after native physics so
     * head, hair, ears and tail retain the full motion.
     */
    private static Map<String, Motion> createMotions() {
        Map<String, Motion> result = new LinkedHashMap<>();
        result.put("nod", motion(1.35f,
                head("ParamAngleY", 0,0, .14f,4, .40f,-20, .68f,8.5f, .96f,-8.5f, 1.16f,1.8f, 1.35f,0),
                body("ParamBodyAngleY", 0,0, .14f,1.4f, .40f,-3.8f, .68f,4.8f, .96f,-1.4f, 1.16f,.7f, 1.35f,0),
                head("ParamAngleZ", 0,0, .14f,1.5f, .40f,-2.5f, .68f,2, .96f,-.9f, 1.16f,.35f, 1.35f,0),
                body("ParamBodyAngleX", 0,0, .14f,.6f, .40f,-.9f, .68f,.7f, .96f,-.3f, 1.16f,.12f, 1.35f,0)));
        result.put("shake_head", motion(1.20f,
                head("ParamAngleX", 0,0, .12f,3, .34f,-14, .58f,13, .82f,-9, 1.02f,4, 1.20f,0),
                body("ParamBodyAngleX", 0,0, .12f,.6f, .34f,-2, .58f,1.8f, .82f,-1, 1.02f,.4f, 1.20f,0),
                head("ParamAngleZ", 0,0, .12f,1, .34f,-3, .58f,2.5f, .82f,-1.5f, 1.02f,.8f, 1.20f,0)));
        result.put("tilt_head", motion(1.50f,
                head("ParamAngleZ", 0,0, .70f,16, 1.20f,5, 1.50f,0),
                body("ParamBodyAngleX", 0,0, .70f,1.2f, 1.20f,.5f, 1.50f,0),
                body("ParamBodyAngleY", 0,0, .70f,.8f, 1.20f,.2f, 1.50f,0)));
        result.put("lean_forward", motion(2.00f,
                body("ParamBodyAngleY", 0,0, .20f,-1.5f, .78f,2.4f, 1.18f,2.8f, 1.52f,1.8f, 1.78f,.7f, 2,0),
                head("ParamAngleY", 0,0, .20f,1, .78f,-4, 1.18f,-5.5f, 1.52f,-2, 1.78f,-.6f, 2,0),
                head("ParamAngleZ", 0,0, .20f,-1, .78f,2, 1.18f,2.5f, 1.52f,1, 1.78f,.2f, 2,0)));
        result.put("lean_back", motion(1.25f,
                body("ParamBodyAngleY", 0,0, .14f,1, .48f,-2, .78f,-2.7f, 1,-1.4f, 1.25f,0),
                head("ParamAngleY", 0,0, .14f,-.8f, .48f,3.5f, .78f,4.6f, 1,1.8f, 1.25f,0),
                head("ParamAngleZ", 0,0, .14f,.6f, .48f,-1.6f, .78f,-2, 1,-.7f, 1.25f,0)));
        result.put("blink_surprised", motion(.88f,
                head("ParamAngleY", 0,0, .16f,2.5f, .36f,-5.5f, .58f,1.8f, .88f,0),
                body("ParamBodyAngleY", 0,0, .16f,2, .36f,-2.5f, .58f,1.4f, .88f,0),
                face("ParamEyeLOpen", 0,0, .16f,.08f, .36f,.42f, .58f,.2f, .88f,0),
                face("ParamEyeROpen", 0,0, .16f,.08f, .36f,.42f, .58f,.2f, .88f,0),
                face("ParamBrowLY", 0,0, .16f,.12f, .36f,.82f, .58f,.38f, .88f,0),
                face("ParamBrowRY", 0,0, .16f,.12f, .36f,.82f, .58f,.38f, .88f,0),
                face("ParamMouthOpenY", 0,0, .16f,.02f, .36f,.22f, .58f,.08f, .88f,0)));
        result.put("sigh", motion(2.00f,
                head("ParamAngleY", 0,0, .30f,-4, 1,-6, 1.5f,-4, 2,0),
                head("ParamAngleX", 0,0, .30f,5, 1,-5, 1.5f,5, 2,0),
                face("ParamEyeLOpen", 0,0, .30f,-.2f, 1,-.3f, 1.5f,-.15f, 2,0),
                face("ParamEyeROpen", 0,0, .30f,-.2f, 1,-.3f, 1.5f,-.15f, 2,0),
                face("ParamMouthOpenY", 0,0, .30f,.25f, 1,.5f, 1.5f,.1f, 2,0),
                body("ParamBodyAngleY", 0,0, .30f,-1, 1,-2.2f, 1.5f,-.9f, 2,0)));
        result.put("pout", motion(1.70f,
                face("ParamMouthFunnel", 0,0, .30f,.45f, .78f,1.38f, 1.16f,1.18f, 1.42f,.55f, 1.70f,0),
                face("ParamMouthForm", 0,0, .30f,-.24f, .78f,-.7f, 1.16f,-.62f, 1.42f,-.26f, 1.70f,0),
                face("Param13", 0,0, .30f,.22f, .78f,1.18f, 1.16f,1, 1.42f,.32f, 1.70f,0),
                head("ParamAngleZ", 0,0, .30f,-2.2f, .78f,-8.5f, 1.16f,-5, 1.42f,-2, 1.70f,0),
                body("ParamBodyAngleX", 0,0, .30f,-.25f, .78f,-.9f, 1.16f,-.45f, 1.42f,-.12f, 1.70f,0)));
        result.put("excited_bounce", motion(2.00f,
                head("ParamAngleY", 0,0, .30f,5, .80f,-2, 1,3, 1.5f,-1, 2,0),
                body("ParamBodyAngleY", 0,0, .30f,3, .80f,-5, 1,2, 1.5f,1, 2,0),
                face("ParamEyeLSmile", 0,0, .30f,.45f, .80f,.68f, 1,.56f, 1.5f,.34f, 2,0),
                face("ParamEyeRSmile", 0,0, .30f,.45f, .80f,.68f, 1,.56f, 1.5f,.34f, 2,0),
                face("ParamMouthForm", 0,0, .30f,.42f, .80f,.72f, 1,.56f, 1.5f,.36f, 2,0),
                face("ParamMouthOpenY", 0,0, .30f,.14f, .80f,.28f, 1,.18f, 1.5f,.1f, 2,0),
                face("Param13", 0,0, .30f,.28f, .80f,.48f, 1,.36f, 1.5f,.2f, 2,0)));
        result.put("listening", motion(2.20f,
                head("ParamAngleZ", 0,0, .40f,6, 1.55f,6, 2.20f,0),
                head("ParamAngleY", 0,0, .40f,2, 1.55f,2, 2.20f,0)));
        result.put("look_around", motion(3.20f,
                head("ParamAngleX", 0,0, .70f,-8, 1.70f,9, 2.50f,3, 3.20f,0),
                face("ParamEyeBallX", 0,0, .70f,-.55f, 1.70f,.65f, 2.50f,.25f, 3.20f,0),
                body("ParamBodyAngleX", 0,0, .70f,-.8f, 1.70f,.9f, 2.50f,.25f, 3.20f,0)));
        result.put("soft_sway", motion(2.80f,
                head("ParamAngleZ", 0,0, .80f,-4, 1.70f,4.5f, 2.80f,0),
                body("ParamBodyAngleX", 0,0, .80f,-2.6f, 1.70f,2.8f, 2.80f,0),
                body("ParamBodyAngleZ", 0,0, .80f,-.8f, 1.70f,.9f, 2.80f,0)));
        result.put("look_down_up", motion(2.50f,
                head("ParamAngleY", 0,0, .80f,-7, 1.55f,5, 2.50f,0),
                face("ParamEyeBallY", 0,0, .80f,-.35f, 1.55f,.22f, 2.50f,0),
                body("ParamBodyAngleY", 0,0, .80f,-1.5f, 1.55f,1, 2.50f,0)));
        result.put("small_nod", motion(1.05f,
                head("ParamAngleY", 0,0, .28f,-7, .53f,2.5f, .78f,-1.2f, 1.05f,0),
                body("ParamBodyAngleY", 0,0, .28f,-.7f, .53f,.25f, .78f,-.1f, 1.05f,0)));
        result.put("head_tilt_idle", motion(1.90f,
                head("ParamAngleZ", 0,0, .55f,-8, 1.35f,-6, 1.90f,0),
                head("ParamAngleX", 0,0, .55f,-1.5f, 1.35f,-1, 1.90f,0),
                face("ParamEyeBallX", 0,0, .55f,.2f, 1.35f,.12f, 1.90f,0)));
        result.put("side_look", motion(2.15f,
                face("ParamEyeBallX", 0,0, .35f,.65f, 1.35f,.56f, 1.75f,.12f, 2.15f,0),
                face("ParamEyeBallY", 0,0, .35f,.06f, 1.35f,.04f, 1.75f,0, 2.15f,0),
                head("ParamAngleX", 0,0, .35f,3, 1.35f,4.8f, 1.75f,3, 2.15f,0),
                head("ParamAngleZ", 0,0, .35f,-1.5f, 1.35f,-2.2f, 1.75f,-1.2f, 2.15f,0)));
        result.put("weight_shift", motion(2.35f,
                body("ParamBodyAngleX", 0,0, .70f,-3.8f, 1.60f,-3.1f, 2.35f,0),
                body("ParamBodyAngleZ", 0,0, .70f,-1.8f, 1.60f,-1.4f, 2.35f,0),
                head("ParamAngleZ", 0,0, .70f,3.5f, 1.60f,2.7f, 2.35f,0)));
        result.put("gentle_lean", motion(1.80f,
                body("ParamBodyAngleY", 0,0, .55f,1.7f, 1.25f,1.35f, 1.80f,0),
                head("ParamAngleY", 0,0, .55f,-3.5f, 1.25f,-2.7f, 1.80f,0),
                face("ParamEyeBallY", 0,0, .55f,.16f, 1.25f,.12f, 1.80f,0)));
        result.put("sigh_sink", motion(2.30f,
                head("ParamAngleY", 0,0, .75f,-6, 1.65f,-4.5f, 2.30f,0),
                body("ParamBodyAngleY", 0,0, .75f,-1.7f, 1.65f,-1.2f, 2.30f,0),
                face("ParamEyeBallY", 0,0, .75f,-.3f, 1.65f,-.2f, 2.30f,0)));
        result.put("slow_blink", motion(.95f,
                face("ParamEyeLOpen", 0,0, .38f,-1, .58f,-1, .95f,0),
                face("ParamEyeROpen", 0,0, .38f,-1, .58f,-1, .95f,0),
                head("ParamAngleY", 0,0, .38f,-1, .58f,-1, .95f,0)));
        result.put("wind_sway_soft", windMotion(6.20f, .68f));
        result.put("wind_sway_medium", windMotion(6.60f, 1.00f));
        result.put("wind_sway_showcase", windMotion(7.20f, 1.34f));
        result.put("showcase_orbit", motion(5.20f,
                head("ParamAngleX", 0,0, .52f,-12, 1.08f,-22, 1.72f,-8, 2.38f,16, 3.02f,23, 3.68f,7, 4.28f,-9, 4.78f,3, 5.20f,0),
                head("ParamAngleY", 0,0, .52f,9, 1.08f,1, 1.72f,-12, 2.38f,-9, 3.02f,5, 3.68f,13, 4.28f,5, 4.78f,-2, 5.20f,0),
                head("ParamAngleZ", 0,0, .52f,-9, 1.08f,-15, 1.72f,-5, 2.38f,12, 3.02f,16, 3.68f,5, 4.28f,-7, 4.78f,2, 5.20f,0),
                body("ParamBodyAngleX", 0,0, .52f,-2.4f, 1.08f,-6.6f, 1.72f,-5.2f, 2.38f,2.8f, 3.02f,6.8f, 3.68f,5.1f, 4.28f,-.8f, 4.78f,.8f, 5.20f,0),
                body("ParamBodyAngleY", 0,0, .52f,.2f, 1.08f,.55f, 1.72f,-.4f, 2.38f,-.52f, 3.02f,.25f, 3.68f,.58f, 4.28f,.15f, 4.78f,-.08f, 5.20f,0),
                body("ParamBodyAngleZ", 0,0, .52f,-1.8f, 1.08f,-5.5f, 1.72f,-4.2f, 2.38f,2.4f, 3.02f,5.8f, 3.68f,4.1f, 4.28f,-.7f, 4.78f,.65f, 5.20f,0),
                face("ParamEyeBallX", 0,0, .52f,-.38f, 1.08f,-.7f, 1.72f,-.24f, 2.38f,.5f, 3.02f,.72f, 3.68f,.2f, 4.28f,-.28f, 4.78f,.08f, 5.20f,0),
                face("ParamEyeBallY", 0,0, .52f,.28f, 1.08f,.04f, 1.72f,-.38f, 2.38f,-.3f, 3.02f,.16f, 3.68f,.42f, 4.28f,.15f, 4.78f,-.05f, 5.20f,0)));
        result.put("head_pat", motion(1.75f,
                head("ParamAngleY", 0,0, .28f,-2.2f, .68f,-4.5f, 1.08f,-3.6f, 1.42f,-1.3f, 1.75f,0),
                head("ParamAngleZ", 0,0, .28f,-2.5f, .68f,3.8f, 1.08f,-3.1f, 1.42f,1.2f, 1.75f,0),
                body("ParamBodyAngleX", 0,0, .28f,-.35f, .68f,.55f, 1.08f,-.45f, 1.42f,.16f, 1.75f,0),
                face("ParamEyeLOpen", 0,0, .28f,-.45f, .68f,-.85f, 1.08f,-.72f, 1.42f,-.30f, 1.75f,0),
                face("ParamEyeROpen", 0,0, .28f,-.45f, .68f,-.85f, 1.08f,-.72f, 1.42f,-.30f, 1.75f,0),
                face("ParamEyeLSmile", 0,0, .28f,.45f, .68f,.92f, 1.08f,.80f, 1.42f,.35f, 1.75f,0),
                face("ParamEyeRSmile", 0,0, .28f,.45f, .68f,.92f, 1.08f,.80f, 1.42f,.35f, 1.75f,0),
                face("Param13", 0,0, .28f,.20f, .68f,.82f, 1.08f,.68f, 1.42f,.25f, 1.75f,0),
                face("ParamMouthForm", 0,0, .28f,.20f, .68f,.48f, 1.08f,.40f, 1.42f,.18f, 1.75f,0)));
        result.put("head_pat_confused", motion(1.80f,
                head("ParamAngleX", 0,0, .30f,1.5f, .78f,3.2f, 1.28f,2.3f, 1.80f,0),
                head("ParamAngleY", 0,0, .30f,1.0f, .78f,-1.0f, 1.28f,-.5f, 1.80f,0),
                head("ParamAngleZ", 0,0, .30f,-2.0f, .78f,-7.0f, 1.28f,-5.2f, 1.80f,0),
                face("ParamEyeBallX", 0,0, .30f,.12f, .78f,.28f, 1.28f,.20f, 1.80f,0),
                face("ParamEyeLOpen", 0,0, .30f,-.08f, .78f,-.28f, 1.28f,-.20f, 1.80f,0),
                face("ParamEyeROpen", 0,0, .30f,.08f, .78f,.15f, 1.28f,.10f, 1.80f,0),
                face("ParamBrowLY", 0,0, .30f,-.12f, .78f,-.35f, 1.28f,-.24f, 1.80f,0),
                face("ParamBrowRY", 0,0, .30f,.20f, .78f,.48f, 1.28f,.34f, 1.80f,0),
                face("ParamMouthForm", 0,0, .30f,-.08f, .78f,-.25f, 1.28f,-.16f, 1.80f,0)));
        return Collections.unmodifiableMap(result);
    }

    private static Motion windMotion(final float duration, final float windGain) {
        return motion(duration,
                sampled("ParamAngleX", HEAD_GAIN, time -> wind(time, duration, windGain, 0)),
                sampled("ParamAngleY", HEAD_GAIN, time -> wind(time, duration, windGain, 1)),
                sampled("ParamAngleZ", HEAD_GAIN, time -> wind(time, duration, windGain, 2)),
                sampled("ParamBodyAngleX", BODY_GAIN, time -> wind(time, duration, windGain, 3)),
                sampled("ParamBodyAngleY", BODY_GAIN, time -> wind(time, duration, windGain, 4)),
                sampled("ParamBodyAngleZ", BODY_GAIN, time -> wind(time, duration, windGain, 5)),
                sampled("ParamEyeBallX", 1.0f, time -> wind(time, duration, windGain, 6)),
                sampled("ParamEyeBallY", 1.0f, time -> wind(time, duration, windGain, 7)));
    }

    private static float wind(float time, float duration, float gain, int channel) {
        float progress = Math.max(0.0f, Math.min(1.0f, time / duration));
        float envelope = (float) Math.pow(Math.max(0.0, Math.sin(Math.PI * progress)), .68);
        float omega = (float) (Math.PI * 2.0 * .235);
        float headWave = (float) (Math.sin(omega * time)
                + Math.sin(omega * .46 * time + .8) * .22);
        float bodyWave = (float) (Math.sin(omega * time - .58)
                + Math.sin(omega * .43 * time + .25) * .18);
        switch (channel) {
            case 0: return (float) ((Math.sin(omega * .72 * time + 1.08) * 10
                    + Math.sin(omega * .31 * time - .4) * 1.8) * gain * envelope);
            case 1: return (float) ((Math.sin(omega * .53 * time - .38) * 5.8
                    + Math.sin(omega * 1.08 * time + .6) * 1.2) * gain * envelope);
            case 2: return headWave * 11.5f * gain * envelope;
            case 3: return (float) ((Math.sin(omega * .72 * time + .52) * 4.7
                    + Math.sin(omega * .29 * time) * .6) * gain * envelope);
            case 4: return (float) (Math.sin(omega * .55 * time - .42) * .52
                    * gain * envelope);
            case 5: return bodyWave * 5.2f * gain * envelope;
            case 6: return (float) (Math.sin(omega * .72 * time + 1.18) * .18
                    * gain * envelope);
            case 7: return (float) (Math.sin(omega * .53 * time - .28) * .1
                    * gain * envelope);
            default: return 0.0f;
        }
    }

    private static float smoothStep(float value) {
        float p = clamp(value, 0.0f, 1.0f);
        return p * p * (3.0f - 2.0f * p);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Motion motion(float duration, Track... tracks) {
        return new Motion(duration, tracks);
    }

    private static List<String> createManualTestActions() {
        List<String> result = new ArrayList<>();
        for (String action : ACTIONS) {
            if (!AUTO_IDLE_ONLY_ACTIONS.contains(action)) result.add(action);
        }
        return Collections.unmodifiableList(result);
    }

    private static void validateMotionLibrary() {
        if (!MOTIONS.keySet().equals(new LinkedHashSet<>(ACTIONS))) {
            throw new IllegalStateException("Program action list and motion library differ");
        }
        if (!ACTIONS.containsAll(MANUAL_TEST_ACTIONS)) {
            throw new IllegalStateException("Manual test action is missing from motion library");
        }
        if (!ACTIONS.containsAll(AUTO_IDLE_ONLY_ACTIONS)
                || MANUAL_TEST_ACTIONS.size() + AUTO_IDLE_ONLY_ACTIONS.size() != ACTIONS.size()) {
            throw new IllegalStateException("Autonomous-idle/manual action partition is invalid");
        }
        for (Map.Entry<String, Motion> entry : MOTIONS.entrySet()) {
            Motion motion = entry.getValue();
            for (Track track : motion.tracks) {
                if (track.sampler != null) continue;
                float[] frames = track.frames;
                if (frames == null || frames.length < 4 || (frames.length & 1) != 0) {
                    throw new IllegalStateException("Invalid keyframes: " + entry.getKey()
                            + "/" + track.parameterId);
                }
                float previousTime = -Float.MAX_VALUE;
                for (int i = 0; i < frames.length; i += 2) {
                    if (frames[i] <= previousTime) {
                        throw new IllegalStateException("Non-increasing keyframes: "
                                + entry.getKey() + "/" + track.parameterId);
                    }
                    previousTime = frames[i];
                }
                if (Math.abs(frames[0]) > .0001f
                        || Math.abs(frames[1]) > .0001f
                        || Math.abs(frames[frames.length - 2] - motion.duration) > .0001f
                        || Math.abs(frames[frames.length - 1]) > .0001f) {
                    throw new IllegalStateException("Motion must enter/return through zero: "
                            + entry.getKey() + "/" + track.parameterId);
                }
            }
        }
    }

    private static Track head(String id, float... frames) {
        return new Track(id, HEAD_GAIN, frames, null);
    }

    private static Track body(String id, float... frames) {
        return new Track(id, BODY_GAIN, frames, null);
    }

    private static Track face(String id, float... frames) {
        return new Track(id, 1.0f, frames, null);
    }

    private static Track sampled(String id, float gain, Sampler sampler) {
        return new Track(id, gain, null, sampler);
    }

    private interface Sampler {
        float sample(float time);
    }

    private static final class Motion {
        final float duration;
        final Track[] tracks;

        Motion(float duration, Track[] tracks) {
            this.duration = duration;
            this.tracks = tracks;
        }
    }

    private static final class Track {
        final String parameterId;
        final float gain;
        final float[] frames;
        final Sampler sampler;

        Track(String parameterId, float gain, float[] frames, Sampler sampler) {
            this.parameterId = parameterId;
            this.gain = gain;
            this.frames = frames;
            this.sampler = sampler;
        }

        float sample(float time) {
            if (sampler != null) return sampler.sample(time);
            if (frames == null || frames.length < 2) return 0.0f;
            if (time <= frames[0]) return frames[1];
            for (int i = 2; i + 1 < frames.length; i += 2) {
                if (time <= frames[i]) {
                    int point = i / 2;
                    float t0 = frames[i - 2];
                    float v0 = frames[i - 1];
                    float t1 = frames[i];
                    float v1 = frames[i + 1];
                    float span = Math.max(.0001f, t1 - t0);
                    float p = Math.max(0.0f, Math.min(1.0f, (time - t0) / span));
                    float m0 = tangent(point - 1);
                    float m1 = tangent(point);
                    float p2 = p * p;
                    float p3 = p2 * p;
                    float value = (2 * p3 - 3 * p2 + 1) * v0
                            + (p3 - 2 * p2 + p) * span * m0
                            + (-2 * p3 + 3 * p2) * v1
                            + (p3 - p2) * span * m1;
                    // Cardinal interpolation keeps velocity through internal guide points. A
                    // small overshoot is useful for organic movement, but bound malformed data.
                    float localMin = Math.min(v0, v1);
                    float localMax = Math.max(v0, v1);
                    float allowance = Math.max(.02f, (localMax - localMin) * .12f);
                    return clamp(value, localMin - allowance, localMax + allowance);
                }
            }
            return frames[frames.length - 1];
        }

        private float tangent(int point) {
            int count = frames.length / 2;
            if (point <= 0 || point >= count - 1) return 0.0f;
            int previous = (point - 1) * 2;
            int next = (point + 1) * 2;
            float span = Math.max(.0001f, frames[next] - frames[previous]);
            return .65f * (frames[next + 1] - frames[previous + 1]) / span;
        }
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "emotion=%s action=%s auto=%s",
                emotion, getActionLabel(), autoIdle);
    }
}
