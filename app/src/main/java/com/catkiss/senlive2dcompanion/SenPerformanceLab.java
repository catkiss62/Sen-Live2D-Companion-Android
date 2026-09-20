package com.catkiss.senlive2dcompanion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replaceable VAD/FACS adaptation experiment.
 *
 * This layer is deliberately separate from {@link SenPerformanceEngine}: legacy mode performs
 * no writes, every output is limited to standard facial/head/body parameters, and authored
 * actions always take priority. It never owns outfit, Part, Drawable colour, physics, ear, tail,
 * ahoge or lip-sync state.
 */
final class SenPerformanceLab {
    static final String MODE_LEGACY = "legacy";
    static final String MODE_FACS = "facs";
    static final String MODE_HYBRID = "hybrid";

    private static final float HYBRID_FACE_MIX = 0.28f;
    private static final float HYBRID_MOTION_MIX = 0.35f;
    private static final Map<String, VadTarget> EMOTION_TARGETS = buildTargets();

    private String mode = MODE_LEGACY;
    private float targetValence;
    private float targetArousal;
    private float targetDominance;
    private float valence;
    private float arousal;
    private float dominance;
    private float expressionGain = 1.0f;
    private float bodyMotionGain = 1.0f;
    private float responseGain = 1.0f;
    private float elapsed;

    void setMode(String requestedMode) {
        if (MODE_FACS.equals(requestedMode) || MODE_HYBRID.equals(requestedMode)) {
            mode = requestedMode;
        } else {
            mode = MODE_LEGACY;
        }
    }

    String getMode() {
        return mode;
    }

    boolean usesLegacyEmotion() {
        return !MODE_FACS.equals(mode);
    }

    void selectEmotion(String emotionId) {
        VadTarget target = EMOTION_TARGETS.get(emotionId);
        if (target == null) target = EMOTION_TARGETS.get("normal");
        targetValence = target.valence;
        targetArousal = target.arousal;
        targetDominance = target.dominance;
    }

    void setTuning(float requestedExpressionGain, float requestedBodyMotionGain,
                   float requestedResponseGain) {
        expressionGain = clamp(requestedExpressionGain, 0.50f, 1.50f);
        bodyMotionGain = clamp(requestedBodyMotionGain, 0.50f, 1.50f);
        responseGain = clamp(requestedResponseGain, 0.50f, 2.00f);
    }

    void update(float deltaSeconds, boolean authoredActionActive,
                SenPerformanceEngine.ParameterWriter writer) {
        float dt = clamp(deltaSeconds, 0.0f, 0.05f);
        elapsed += dt;
        float timeConstant = 0.38f / responseGain;
        float blend = 1.0f - (float) Math.exp(-dt / Math.max(0.01f, timeConstant));
        valence += (targetValence - valence) * blend;
        arousal += (targetArousal - arousal) * blend;
        dominance += (targetDominance - dominance) * blend;

        if (MODE_LEGACY.equals(mode) || authoredActionActive) return;

        float faceMix = MODE_HYBRID.equals(mode) ? HYBRID_FACE_MIX : 1.0f;
        float motionMix = MODE_HYBRID.equals(mode) ? HYBRID_MOTION_MIX : 1.0f;
        float faceGain = expressionGain * faceMix;
        float motionGain = bodyMotionGain * motionMix;

        float positive = Math.max(0.0f, valence);
        float negative = Math.max(0.0f, -valence);
        float distress = negative * (1.0f - dominance) * 0.5f;
        float confrontation = negative * (1.0f + dominance) * 0.5f;

        // FACS-like standard-channel approximation. Sen-only effects remain in the authored
        // legacy emotion layer and therefore appear only in hybrid/legacy modes.
        writer.add("ParamMouthForm", valence * 0.46f * faceGain);
        writer.add("ParamEyeLSmile", positive * 0.30f * faceGain);
        writer.add("ParamEyeRSmile", positive * 0.30f * faceGain);
        float eyeOpen = (arousal * 0.18f - negative * 0.06f) * faceGain;
        writer.add("ParamEyeLOpen", eyeOpen);
        writer.add("ParamEyeROpen", eyeOpen);
        float browY = (distress * 0.30f - confrontation * 0.24f
                + arousal * 0.08f) * faceGain;
        writer.add("ParamBrowLY", browY);
        writer.add("ParamBrowRY", browY);

        // Low-amplitude continuous motion is intentionally separate from Sen's autonomous idle.
        // Rabbit-ear twitching remains exclusively in the existing idle scheduler.
        float energy = 0.22f + 0.78f * (arousal + 1.0f) * 0.5f;
        float phase = elapsed * (0.70f + 0.55f * energy);
        writer.add("ParamAngleZ", (float) Math.sin(phase) * 1.35f * energy * motionGain);
        writer.add("ParamAngleY", (float) Math.sin(phase * 0.73f + 0.8f)
                * 0.85f * energy * motionGain);
        writer.add("ParamBodyAngleX", (float) Math.sin(phase * 0.57f + 1.1f)
                * 0.60f * energy * motionGain);
        writer.add("ParamBodyAngleZ", (float) Math.sin(phase * 0.49f)
                * 0.45f * energy * motionGain);
    }

    private static Map<String, VadTarget> buildTargets() {
        Map<String, VadTarget> values = new LinkedHashMap<>();
        put(values, "normal", 0.00f, 0.00f, 0.00f);
        put(values, "happy", 0.75f, 0.35f, 0.35f);
        put(values, "excited", 0.85f, 0.95f, 0.45f);
        put(values, "affection", 0.80f, 0.25f, 0.20f);
        put(values, "shy", 0.25f, 0.15f, -0.45f);
        put(values, "romantic_shy", 0.65f, 0.38f, -0.35f);
        put(values, "flustered", -0.05f, 0.85f, -0.55f);
        put(values, "tense", -0.45f, 0.70f, -0.10f);
        put(values, "worried", -0.65f, 0.50f, -0.50f);
        put(values, "confused", -0.15f, 0.35f, -0.20f);
        put(values, "helpless", -0.60f, -0.15f, -0.75f);
        put(values, "afraid", -0.85f, 0.90f, -0.85f);
        put(values, "angry", -0.80f, 0.85f, 0.75f);
        put(values, "sad", -0.80f, -0.55f, -0.65f);
        put(values, "disgust", -0.70f, 0.25f, 0.45f);
        put(values, "serious", -0.15f, 0.05f, 0.55f);
        put(values, "surprised", 0.05f, 1.00f, -0.10f);
        put(values, "confident", 0.45f, 0.25f, 0.85f);
        put(values, "playful", 0.75f, 0.65f, 0.50f);
        put(values, "ashamed", -0.45f, 0.20f, -0.75f);
        put(values, "calm", 0.35f, -0.65f, 0.30f);
        return Collections.unmodifiableMap(values);
    }

    private static void put(Map<String, VadTarget> values, String id,
                            float valence, float arousal, float dominance) {
        values.put(id, new VadTarget(valence, arousal, dominance));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class VadTarget {
        final float valence;
        final float arousal;
        final float dominance;

        VadTarget(float valence, float arousal, float dominance) {
            this.valence = valence;
            this.arousal = arousal;
            this.dominance = dominance;
        }
    }
}
