package com.catkiss.senlive2dcompanion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Sen-specific reinterpretation of the E.V motion vocabulary.
 *
 * <p>This is intentionally a separate implementation from {@link EvFaithfulMotionEngine}. It
 * adds correlated torso movement, gentler face gains and an autonomous scheduler suited to a
 * one-to-one companion. Deleting it cannot alter the faithful port or Sen's original idle.</p>
 */
final class SenNaturalMotionEngine {
    private static final String[] IDLE_PULSES = {
            "accent_nod", "accent_nod_overshoot", "accent_swing",
            "tilt_curious", "glance_danmaku", "nod_rapid"
    };
    private static final String[] IDLE_GAZES = {"camera", "camera", "screen", "chat", "up"};

    private final EvMotionPack pack;
    private final Random random = new Random();
    private final Map<String, Float> lastWrites = new LinkedHashMap<>();

    private boolean enabled;
    private boolean diagnosticMode;
    private String diagnosticKind;
    private float elapsed;
    private String pulseId;
    private float pulseStartedAt;
    private String poseId;
    private float poseStartedAt;
    private String gazeId = "camera";
    private float gazeChangedAt;
    private float eyeX;
    private float eyeY;
    private float headX;
    private float headY;
    private float nextGestureAt;
    private float nextGazeAt;
    private float nextBlinkAt;
    private float blinkStartedAt = -1000.0f;

    SenNaturalMotionEngine(EvMotionPack pack) {
        this.pack = pack;
    }

    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        resetTransientState();
    }

    void setDiagnosticMode(boolean diagnosticMode) {
        this.diagnosticMode = diagnosticMode;
    }

    void setDiagnosticKind(String kind) {
        diagnosticKind = kind;
    }

    void resetTransientState() {
        pulseId = null;
        poseId = null;
        gazeId = "camera";
        gazeChangedAt = elapsed;
        eyeX = 0.0f;
        eyeY = 0.0f;
        headX = 0.0f;
        headY = 0.0f;
        nextGestureAt = elapsed + 7.0f + random.nextFloat() * 6.0f;
        nextGazeAt = elapsed + 3.0f + random.nextFloat() * 4.0f;
        nextBlinkAt = elapsed + 1.8f + random.nextFloat() * 2.6f;
        blinkStartedAt = -1000.0f;
        lastWrites.clear();
    }

    void playPulse(String id) {
        if (!pack.pulses.containsKey(id)) return;
        pulseId = id;
        pulseStartedAt = elapsed;
    }

    void setPose(String id) {
        if (id != null && !pack.sustains.containsKey(id)) return;
        poseId = id;
        poseStartedAt = elapsed;
    }

    void setGaze(String id) {
        if (id != null && !pack.gazes.containsKey(id)) return;
        gazeId = id;
        gazeChangedAt = elapsed;
    }

    void forceBlink() {
        blinkStartedAt = elapsed;
        nextBlinkAt = elapsed + .34f + 2.0f + random.nextFloat() * 2.5f;
    }

    Map<String, Float> getLastWrites() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lastWrites));
    }

    void update(float deltaSeconds, SenPerformanceEngine.ParameterWriter writer) {
        lastWrites.clear();
        if (!enabled) return;
        float dt = clamp(deltaSeconds, .001f, .05f);
        elapsed += dt;
        if (!diagnosticMode) scheduleIdle();

        if (!diagnosticMode || "ambient".equals(diagnosticKind)) {
            float headAmbientX = 7.2f * EvMotionPack.driftNoise(elapsed, .105f, .2f);
            float headAmbientY = 4.0f * EvMotionPack.driftNoise(elapsed, .145f, 2.4f);
            float headAmbientZ = 3.4f * EvMotionPack.driftNoise(elapsed, .09f, 4.1f);
            writeAdd(writer, "ParamAngleX", headAmbientX);
            writeAdd(writer, "ParamAngleY", headAmbientY);
            writeAdd(writer, "ParamAngleZ", headAmbientZ);
            // Sen's long hair, shoulders, tail and ahoge all read body movement through native
            // physics. The delayed/counter-phase torso layer gives the head drift a tracked-body
            // silhouette rather than moving a rigid mannequin as one block.
            writeAdd(writer, "ParamBodyAngleX",
                    1.7f * EvMotionPack.driftNoise(elapsed - .16f, .105f, .2f));
            writeAdd(writer, "ParamBodyAngleY",
                    .75f * EvMotionPack.driftNoise(elapsed - .13f, .145f, 2.4f));
            writeAdd(writer, "ParamBodyAngleZ",
                    1.45f * EvMotionPack.driftNoise(elapsed - .19f, .09f, 4.1f));
        }
        applyPose(writer);
        applyPulse(writer);
        if (!diagnosticMode || "gaze".equals(diagnosticKind)) applyGaze(dt, writer);
        if (!diagnosticMode || "blink".equals(diagnosticKind)) applyBlink(writer);
    }

    private void scheduleIdle() {
        if (elapsed >= nextGestureAt && pulseId == null) {
            playPulse(IDLE_PULSES[random.nextInt(IDLE_PULSES.length)]);
            nextGestureAt = elapsed + 8.0f + random.nextFloat() * 8.0f;
        }
        if (elapsed >= nextGazeAt) {
            setGaze(IDLE_GAZES[random.nextInt(IDLE_GAZES.length)]);
            nextGazeAt = elapsed + 3.5f + random.nextFloat() * 5.0f;
        }
    }

    private void applyPose(SenPerformanceEngine.ParameterWriter writer) {
        EvMotionPack.SustainClip clip = pack.sustains.get(poseId);
        if (clip == null) return;
        float timeMs = (elapsed - poseStartedAt) * 1000.0f;
        float weight = smoothStep(timeMs / 420.0f);
        for (Map.Entry<String, EvMotionPack.Hold> entry : clip.holds.entrySet()) {
            writeSemantic(writer, entry.getKey(), entry.getValue().sample(timeMs) * weight);
        }
    }

    private void applyPulse(SenPerformanceEngine.ParameterWriter writer) {
        EvMotionPack.PulseClip clip = pack.pulses.get(pulseId);
        if (clip == null) return;
        float timeMs = (elapsed - pulseStartedAt) * 1000.0f;
        if (timeMs >= clip.durationMs) {
            pulseId = null;
            return;
        }
        for (Map.Entry<String, java.util.List<EvMotionPack.Key>> entry
                : clip.tracks.entrySet()) {
            float value = EvMotionPack.sample(entry.getValue(), timeMs);
            writeSemantic(writer, entry.getKey(), adaptGain(entry.getKey()) * value);
        }
    }

    private void applyGaze(float dt, SenPerformanceEngine.ParameterWriter writer) {
        EvMotionPack.GazeTarget target = pack.gazes.get(gazeId);
        float desiredEyeX = target == null ? 0.0f : target.eyeX;
        float desiredEyeY = target == null ? 0.0f : target.eyeY;
        float desiredHeadX = target == null ? 0.0f : target.headX * .62f;
        float desiredHeadY = target == null ? 0.0f : target.headY * .62f;

        float eyeGain = 1.0f - (float) Math.exp(-dt * 15.0f);
        float headGain = elapsed - gazeChangedAt < .085f
                ? 0.0f : 1.0f - (float) Math.exp(-dt * 3.8f);
        eyeX += (desiredEyeX - eyeX) * eyeGain;
        eyeY += (desiredEyeY - eyeY) * eyeGain;
        headX += (desiredHeadX - headX) * headGain;
        headY += (desiredHeadY - headY) * headGain;

        float microX = .018f * EvMotionPack.driftNoise(elapsed, .72f, .9f);
        float microY = .012f * EvMotionPack.driftNoise(elapsed, .59f, 2.2f);
        writeSet(writer, "ParamEyeBallX", clamp(eyeX + microX, -1.0f, 1.0f));
        writeSet(writer, "ParamEyeBallY", clamp(eyeY + microY, -1.0f, 1.0f));
        writeAdd(writer, "ParamAngleX", headX);
        writeAdd(writer, "ParamAngleY", headY);
        writeAdd(writer, "ParamBodyAngleX", headX * .09f);
        writeAdd(writer, "ParamBodyAngleY", headY * .055f);
    }

    private void applyBlink(SenPerformanceEngine.ParameterWriter writer) {
        if (elapsed >= nextBlinkAt) forceBlink();
        float time = elapsed - blinkStartedAt;
        if (time < 0.0f || time >= .34f) return;
        float close;
        if (time < .13f) close = time / .13f;
        else if (time < .17f) close = 1.0f;
        else close = 1.0f - (time - .17f) / .17f;
        close = smoothStep(close);
        writeAdd(writer, "ParamEyeLOpen", -close);
        writeAdd(writer, "ParamEyeROpen", -close);
    }

    private void writeSemantic(SenPerformanceEngine.ParameterWriter writer, String semantic,
                               float value) {
        switch (semantic) {
            case "FaceAngleX":
                writeAdd(writer, "ParamAngleX", value);
                writeAdd(writer, "ParamBodyAngleX", value * .10f);
                break;
            case "FaceAngleY":
                writeAdd(writer, "ParamAngleY", value);
                writeAdd(writer, "ParamBodyAngleY", value * .065f);
                break;
            case "FaceAngleZ":
                writeAdd(writer, "ParamAngleZ", value);
                writeAdd(writer, "ParamBodyAngleZ", value * .11f);
                break;
            case "MouthOpen": writeAdd(writer, "ParamMouthOpenY", value); break;
            case "MouthSmile": writeAdd(writer, "ParamMouthForm", value); break;
            case "EyeOpenLeft": writeAdd(writer, "ParamEyeLOpen", value); break;
            case "EyeOpenRight": writeAdd(writer, "ParamEyeROpen", value); break;
            case "EyeLeftX":
            case "EyeRightX": writeAdd(writer, "ParamEyeBallX", value * .5f); break;
            case "EyeLeftY":
            case "EyeRightY": writeAdd(writer, "ParamEyeBallY", value * .5f); break;
            case "BrowLeftY": writeAdd(writer, "ParamBrowLY", value); break;
            case "BrowRightY": writeAdd(writer, "ParamBrowRY", value); break;
            case "CheekPuff":
                writeAdd(writer, "ParamMouthFunnel", value * .85f);
                writeAdd(writer, "Param13", value * .25f);
                break;
            default: break;
        }
    }

    private float adaptGain(String semantic) {
        if (semantic.startsWith("FaceAngle")) return .78f;
        if (semantic.startsWith("EyeOpen")) return .88f;
        if (semantic.startsWith("Brow")) return .82f;
        if ("MouthOpen".equals(semantic)) return .72f;
        return .86f;
    }

    private void writeAdd(SenPerformanceEngine.ParameterWriter writer, String id, float value) {
        if (Math.abs(value) < .00001f) return;
        writer.add(id, value);
        lastWrites.merge(id, value, Float::sum);
    }

    private void writeSet(SenPerformanceEngine.ParameterWriter writer, String id, float value) {
        writer.set(id, value);
        lastWrites.put(id, value);
    }

    private static float smoothStep(float value) {
        float p = clamp(value, 0.0f, 1.0f);
        return p * p * (3.0f - 2.0f * p);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
