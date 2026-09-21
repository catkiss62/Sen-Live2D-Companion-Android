package com.catkiss.senlive2dcompanion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Direct Java port of the motion-only parts of E.V's bundled VTuber performance layer.
 *
 * <p>Constants, 1/f head drift, pulse curves, sustain noise, gaze/head latency, fixation walk,
 * microsaccade drift and blink timing follow E.V's {@code mixer.ts}/{@code clips.ts}. Android
 * rendering, model appearance, outfits and Sen's existing actions remain outside this class.</p>
 */
final class EvFaithfulMotionEngine {
    private static final float HEAD_FOLLOW_FACTOR = .85f;
    private static final float EYE_RANGE_X_DEG = 18.0f;
    private static final float EYE_RANGE_Y_DEG = 22.0f;
    private static final float HEAD_LATENCY_SECONDS = .060f;
    private static final float SCAN_RADIUS_DEFAULT_DEG = 3.0f;
    private static final float SCAN_AMP_RATIO = .4f;
    private static final float SCAN_VERTICAL_BIAS = .5f;
    private static final float DRIFT_SD_DEG = .22f;
    private static final float DRIFT_TAU_SECONDS = .45f;
    private static final float BLINK_CLOSE_SECONDS = .075f;
    private static final float BLINK_TOTAL_SECONDS = .233f;
    private static final float BLINK_GAP_MIN_SECONDS = 2.0f;
    private static final float BLINK_GAP_MEAN_SECONDS = 2.5f;
    private static final float STATE_FADE_SECONDS = .300f;
    private static final float BODY_FOLLOW_RESPONSE = 7.0f;
    private static final float BODY_LIMIT_DEGREES = 20.0f;

    private final EvMotionPack pack;
    private final Random random = new Random();
    private final Map<String, Float> lastWrites = new LinkedHashMap<>();

    private boolean enabled;
    private String diagnosticKind;
    private float elapsed;
    private String pulseId;
    private float pulseStartedAt;
    private String poseId;
    private float poseStartedAt;
    private String gazeId;
    private float headStartAt;
    private float headFollowX;
    private float headFollowY;
    private float walkX;
    private float walkY;
    private float driftX;
    private float driftY;
    private float eyeDegreesX;
    private float eyeDegreesY;
    private float scanStartedAt;
    private float scanDuration;
    private float scanFromX;
    private float scanFromY;
    private float scanToX;
    private float scanToY;
    private float nextFixAt;
    private float shiftFromX;
    private float shiftFromY;
    private float shiftStartedAt;
    private float shiftDuration;
    private float nextBlinkAt;
    private float blinkStartedAt = -1000.0f;
    private float bodyFollowStrength;
    private float bodyX;
    private float bodyY;
    private float bodyZ;
    private String forcedBodyParameter;
    private float forcedBodyValue;
    private float forcedBodyStartedAt;

    EvFaithfulMotionEngine(EvMotionPack pack) {
        this.pack = pack;
    }

    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        resetTransientState();
        if (enabled) setGaze("camera");
    }

    boolean isEnabled() {
        return enabled;
    }

    void setBodyFollowStrength(float strength) {
        float next = clamp(strength, 0.0f, .60f);
        if (Math.abs(next - bodyFollowStrength) < .0001f) return;
        bodyFollowStrength = next;
        bodyX = 0.0f;
        bodyY = 0.0f;
        bodyZ = 0.0f;
    }

    float getBodyFollowStrength() {
        return bodyFollowStrength;
    }

    void setDiagnosticKind(String kind) {
        diagnosticKind = kind;
    }

    void resetTransientState() {
        pulseId = null;
        poseId = null;
        gazeId = null;
        headFollowX = 0.0f;
        headFollowY = 0.0f;
        walkX = 0.0f;
        walkY = 0.0f;
        driftX = 0.0f;
        driftY = 0.0f;
        eyeDegreesX = 0.0f;
        eyeDegreesY = 0.0f;
        scanDuration = 0.0f;
        shiftDuration = 0.0f;
        nextFixAt = elapsed;
        nextBlinkAt = elapsed + BLINK_GAP_MIN_SECONDS + expo(BLINK_GAP_MEAN_SECONDS);
        blinkStartedAt = -1000.0f;
        bodyX = 0.0f;
        bodyY = 0.0f;
        bodyZ = 0.0f;
        forcedBodyParameter = null;
        forcedBodyValue = 0.0f;
        forcedBodyStartedAt = elapsed;
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
        shiftFromX = eyeDegreesX;
        shiftFromY = eyeDegreesY;
        gazeId = id;
        headStartAt = elapsed + HEAD_LATENCY_SECONDS;
        EvMotionPack.GazeTarget target = gazeTarget();
        float totalX = target == null ? 0.0f
                : target.eyeX * EYE_RANGE_X_DEG + target.headX * HEAD_FOLLOW_FACTOR;
        float totalY = target == null ? 0.0f
                : target.eyeY * EYE_RANGE_Y_DEG + target.headY * HEAD_FOLLOW_FACTOR;
        float amplitude = (float) Math.hypot(totalX - eyeDegreesX, totalY - eyeDegreesY);
        shiftStartedAt = elapsed;
        shiftDuration = Math.max(.130f, (2.2f * amplitude + 21.0f) / 1000.0f);
        scanDuration = 0.0f;
        nextFixAt = elapsed + sampleFixationSeconds();
    }

    void forceBlink() {
        blinkStartedAt = elapsed;
        nextBlinkAt = elapsed + BLINK_TOTAL_SECONDS + BLINK_GAP_MIN_SECONDS
                + expo(BLINK_GAP_MEAN_SECONDS);
    }

    void forceBodySweep(String parameter, float value) {
        if (!"ParamBodyAngleX".equals(parameter)
                && !"ParamBodyAngleY".equals(parameter)
                && !"ParamBodyAngleZ".equals(parameter)) return;
        forcedBodyParameter = parameter;
        forcedBodyValue = clamp(value, -BODY_LIMIT_DEGREES, BODY_LIMIT_DEGREES);
        forcedBodyStartedAt = elapsed;
    }

    Map<String, Float> getLastWrites() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lastWrites));
    }

    Set<String> expectedMappedParameters(EvMotionPack.TestStep step) {
        Set<String> result = new LinkedHashSet<>();
        if ("ambient".equals(step.kind)) {
            result.add("ParamAngleX");
            result.add("ParamAngleY");
            result.add("ParamAngleZ");
        } else if ("blink".equals(step.kind)) {
            result.add("ParamEyeLOpen");
            result.add("ParamEyeROpen");
        } else if ("pulse".equals(step.kind)) {
            EvMotionPack.PulseClip clip = pack.pulses.get(step.id);
            if (clip != null) addMappedNames(result, clip.tracks.keySet());
        } else if ("sustain".equals(step.kind)) {
            EvMotionPack.SustainClip clip = pack.sustains.get(step.id);
            if (clip != null) addMappedNames(result, clip.holds.keySet());
        } else if ("gaze".equals(step.kind)) {
            result.add("ParamAngleX");
            result.add("ParamAngleY");
            result.add("ParamEyeBallX");
            result.add("ParamEyeBallY");
        } else if ("body".equals(step.kind)) {
            result.add(step.id);
        }
        return result;
    }

    void update(float deltaSeconds, SenPerformanceEngine.ParameterWriter writer) {
        lastWrites.clear();
        if (!enabled) return;
        float dt = clamp(deltaSeconds, .001f, .05f);
        elapsed += dt;
        if ("body".equals(diagnosticKind)) {
            applyForcedBodySweep(writer);
            return;
        }
        Map<String, Float> semantic = new LinkedHashMap<>();

        if (diagnosticKind == null || "ambient".equals(diagnosticKind)) {
            // E.V AMBIENT_HEAD: same amplitudes, frequencies and phases.
            add(semantic, "FaceAngleX", 6.5f * EvMotionPack.driftNoise(elapsed, .13f, 0.0f));
            add(semantic, "FaceAngleY", 3.5f * EvMotionPack.driftNoise(elapsed, .17f, 2.1f));
            add(semantic, "FaceAngleZ", 2.5f * EvMotionPack.driftNoise(elapsed, .11f, 4.4f));
        }

        applyPose(semantic);
        applyPulse(semantic);
        if (diagnosticKind == null || "gaze".equals(diagnosticKind)) {
            stepGaze(dt);
            add(semantic, "FaceAngleX", headFollowX);
            add(semantic, "FaceAngleY", headFollowY);
        }

        if (diagnosticKind == null || "blink".equals(diagnosticKind)) {
            float blink = blinkClose();
            if (blink > 0.0f) {
                add(semantic, "EyeOpenLeft", -blink);
                add(semantic, "EyeOpenRight", -blink);
            }
        }
        writeMapped(semantic, dt, writer);
    }

    private void applyPose(Map<String, Float> semantic) {
        EvMotionPack.SustainClip clip = pack.sustains.get(poseId);
        if (clip == null) return;
        float elapsedMs = (elapsed - poseStartedAt) * 1000.0f;
        float weight = smoothStep(elapsedMs / (STATE_FADE_SECONDS * 1000.0f));
        for (Map.Entry<String, EvMotionPack.Hold> entry : clip.holds.entrySet()) {
            add(semantic, entry.getKey(), entry.getValue().sample(elapsedMs) * weight);
        }
    }

    private void applyPulse(Map<String, Float> semantic) {
        EvMotionPack.PulseClip clip = pack.pulses.get(pulseId);
        if (clip == null) return;
        float timeMs = (elapsed - pulseStartedAt) * 1000.0f;
        if (timeMs >= clip.durationMs) {
            pulseId = null;
            return;
        }
        for (Map.Entry<String, java.util.List<EvMotionPack.Key>> entry
                : clip.tracks.entrySet()) {
            add(semantic, entry.getKey(), EvMotionPack.sample(entry.getValue(), timeMs));
        }
    }

    private void stepGaze(float dt) {
        EvMotionPack.GazeTarget target = gazeTarget();
        float headK = elapsed >= headStartAt ? 1.0f - (float) Math.exp(-dt * 5.0f) : 0.0f;
        float targetHeadX = target == null ? 0.0f : target.headX * HEAD_FOLLOW_FACTOR;
        float targetHeadY = target == null ? 0.0f : target.headY * HEAD_FOLLOW_FACTOR;
        headFollowX += (targetHeadX - headFollowX) * headK;
        headFollowY += (targetHeadY - headFollowY) * headK;

        if (target != null) {
            stepScan(target.scanRadiusDeg <= 0.0f
                    ? SCAN_RADIUS_DEFAULT_DEG : target.scanRadiusDeg);
            stepEyeDrift(dt);
        } else {
            float decay = 1.0f - (float) Math.exp(-dt * 4.0f);
            walkX -= walkX * decay;
            walkY -= walkY * decay;
            driftX -= driftX * decay;
            driftY -= driftY * decay;
        }

        float totalX = target == null ? 0.0f
                : target.eyeX * EYE_RANGE_X_DEG + target.headX * HEAD_FOLLOW_FACTOR;
        float totalY = target == null ? 0.0f
                : target.eyeY * EYE_RANGE_Y_DEG + target.headY * HEAD_FOLLOW_FACTOR;
        float desiredX = totalX - headFollowX + walkX + driftX;
        float desiredY = totalY - headFollowY + walkY + driftY;

        if (elapsed - shiftStartedAt < shiftDuration && shiftDuration > 0.0f) {
            float progress = clamp((elapsed - shiftStartedAt) / shiftDuration, 0.0f, 1.0f);
            float peakAt = clamp(.022f / shiftDuration, .25f, .5f);
            float moved = saccadeProgress(progress, peakAt);
            desiredX = shiftFromX + (desiredX - shiftFromX) * moved;
            desiredY = shiftFromY + (desiredY - shiftFromY) * moved;
        }
        eyeDegreesX = clamp(desiredX, -EYE_RANGE_X_DEG, EYE_RANGE_X_DEG);
        eyeDegreesY = clamp(desiredY, -EYE_RANGE_Y_DEG, EYE_RANGE_Y_DEG);
    }

    private void stepScan(float radius) {
        if (scanDuration > 0.0f) {
            float progress = (elapsed - scanStartedAt) / scanDuration;
            if (progress < 1.0f) {
                float peakAt = clamp(.022f / scanDuration, .25f, .5f);
                float moved = saccadeProgress(progress, peakAt);
                walkX = scanFromX + (scanToX - scanFromX) * moved;
                walkY = scanFromY + (scanToY - scanFromY) * moved;
                return;
            }
            walkX = scanToX;
            walkY = scanToY;
            scanDuration = 0.0f;
            nextFixAt = elapsed + sampleFixationSeconds();
            return;
        }
        if (elapsed < nextFixAt) return;
        float amplitude = Math.min(radius * 1.2f,
                Math.max(.1f, expo(radius * SCAN_AMP_RATIO)));
        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        scanFromX = walkX;
        scanFromY = walkY;
        scanToX = reflectInto(walkX + (float) Math.cos(angle) * amplitude, radius);
        scanToY = reflectInto(walkY + (float) Math.sin(angle) * amplitude
                * SCAN_VERTICAL_BIAS, radius * SCAN_VERTICAL_BIAS);
        float degrees = (float) Math.hypot(scanToX - scanFromX, scanToY - scanFromY);
        scanDuration = Math.max(.001f, (2.2f * degrees + 21.0f) / 1000.0f);
        scanStartedAt = elapsed;
    }

    private void stepEyeDrift(float dt) {
        float k = Math.min(1.0f, dt / DRIFT_TAU_SECONDS);
        float gain = DRIFT_SD_DEG * (float) Math.sqrt(2.0f * k);
        driftX += -driftX * k + gauss() * gain;
        driftY += -driftY * k + gauss() * gain * SCAN_VERTICAL_BIAS;
    }

    private float blinkClose() {
        if (elapsed >= nextBlinkAt) forceBlink();
        float time = elapsed - blinkStartedAt;
        if (time < 0.0f || time >= BLINK_TOTAL_SECONDS) return 0.0f;
        float progress = time < BLINK_CLOSE_SECONDS
                ? time / BLINK_CLOSE_SECONDS
                : 1.0f - (time - BLINK_CLOSE_SECONDS)
                / (BLINK_TOTAL_SECONDS - BLINK_CLOSE_SECONDS);
        return smoothStep(progress);
    }

    private void writeMapped(Map<String, Float> semantic, float dt,
                             SenPerformanceEngine.ParameterWriter writer) {
        writeAdd(writer, "ParamAngleX", semantic.get("FaceAngleX"));
        writeAdd(writer, "ParamAngleY", semantic.get("FaceAngleY"));
        writeAdd(writer, "ParamAngleZ", semantic.get("FaceAngleZ"));
        if (bodyFollowStrength > 0.0f) {
            float targetX = valueOrZero(semantic.get("FaceAngleX")) * bodyFollowStrength;
            float targetY = valueOrZero(semantic.get("FaceAngleY")) * bodyFollowStrength;
            float targetZ = valueOrZero(semantic.get("FaceAngleZ")) * bodyFollowStrength;
            float follow = 1.0f - (float) Math.exp(-dt * BODY_FOLLOW_RESPONSE);
            bodyX += (targetX - bodyX) * follow;
            bodyY += (targetY - bodyY) * follow;
            bodyZ += (targetZ - bodyZ) * follow;
            bodyX = clamp(bodyX, -BODY_LIMIT_DEGREES, BODY_LIMIT_DEGREES);
            bodyY = clamp(bodyY, -BODY_LIMIT_DEGREES, BODY_LIMIT_DEGREES);
            bodyZ = clamp(bodyZ, -BODY_LIMIT_DEGREES, BODY_LIMIT_DEGREES);
            writeAdd(writer, "ParamBodyAngleX", bodyX);
            writeAdd(writer, "ParamBodyAngleY", bodyY);
            writeAdd(writer, "ParamBodyAngleZ", bodyZ);
        }
        writeAdd(writer, "ParamMouthOpenY", semantic.get("MouthOpen"));
        writeAdd(writer, "ParamMouthForm", semantic.get("MouthSmile"));
        writeAdd(writer, "ParamEyeLOpen", semantic.get("EyeOpenLeft"));
        writeAdd(writer, "ParamEyeROpen", semantic.get("EyeOpenRight"));
        writeAdd(writer, "ParamBrowLY", semantic.get("BrowLeftY"));
        writeAdd(writer, "ParamBrowRY", semantic.get("BrowRightY"));
        // Sen has no dedicated cheek-puff semantic input. ParamMouthFunnel is the closest
        // existing authored route and stays explicitly visible in diagnostics.
        writeAdd(writer, "ParamMouthFunnel", semantic.get("CheekPuff"));

        Float eyeX = averagePresent(semantic.get("EyeLeftX"), semantic.get("EyeRightX"));
        Float eyeY = averagePresent(semantic.get("EyeLeftY"), semantic.get("EyeRightY"));
        if (gazeId != null) {
            eyeX = clamp(eyeDegreesX / EYE_RANGE_X_DEG
                    + (eyeX == null ? 0.0f : eyeX), -1.0f, 1.0f);
            eyeY = clamp(eyeDegreesY / EYE_RANGE_Y_DEG
                    + (eyeY == null ? 0.0f : eyeY), -1.0f, 1.0f);
            writeSet(writer, "ParamEyeBallX", eyeX);
            writeSet(writer, "ParamEyeBallY", eyeY);
        } else {
            writeAdd(writer, "ParamEyeBallX", eyeX);
            writeAdd(writer, "ParamEyeBallY", eyeY);
        }
    }

    private void applyForcedBodySweep(SenPerformanceEngine.ParameterWriter writer) {
        if (forcedBodyParameter == null) return;
        float time = Math.max(0.0f, elapsed - forcedBodyStartedAt);
        float weight;
        if (time < .30f) {
            weight = smoothStep(time / .30f);
        } else if (time < 1.10f) {
            weight = 1.0f;
        } else if (time < 1.45f) {
            weight = 1.0f - smoothStep((time - 1.10f) / .35f);
        } else {
            weight = 0.0f;
        }
        writeAdd(writer, forcedBodyParameter, forcedBodyValue * weight);
    }

    private void writeAdd(SenPerformanceEngine.ParameterWriter writer, String id, Float value) {
        if (value == null || Math.abs(value) < .00001f) return;
        writer.add(id, value);
        lastWrites.merge(id, value, Float::sum);
    }

    private void writeSet(SenPerformanceEngine.ParameterWriter writer, String id, Float value) {
        if (value == null) return;
        writer.set(id, value);
        lastWrites.put(id, value);
    }

    private EvMotionPack.GazeTarget gazeTarget() {
        return pack.gazes.get(gazeId);
    }

    private float sampleFixationSeconds() {
        return Math.max(.150f, .240f + gauss() * .050f + expo(.060f));
    }

    private float gauss() {
        float first = Math.max(.000001f, random.nextFloat());
        return (float) (Math.sqrt(-2.0 * Math.log(first))
                * Math.cos(Math.PI * 2.0 * random.nextFloat()));
    }

    private float expo(float mean) {
        return (float) (-Math.log(Math.max(.000001f, 1.0f - random.nextFloat())) * mean);
    }

    private static float saccadeProgress(float progress, float peakAt) {
        float p = clamp(progress, 0.0f, 1.0f);
        if (p <= peakAt) return p * p / peakAt;
        float width = 1.0f - peakAt;
        float delta = p - peakAt;
        return 2.0f * (peakAt / 2.0f + delta - delta * delta / (2.0f * width));
    }

    private static float reflectInto(float value, float bound) {
        if (value > bound) return Math.max(-bound, 2.0f * bound - value);
        if (value < -bound) return Math.min(bound, -2.0f * bound - value);
        return value;
    }

    private static Float averagePresent(Float first, Float second) {
        if (first == null) return second;
        if (second == null) return first;
        return (first + second) * .5f;
    }

    private static float valueOrZero(Float value) {
        return value == null ? 0.0f : value;
    }

    private static void add(Map<String, Float> values, String id, float amount) {
        if (Math.abs(amount) < .000001f) return;
        values.merge(id, amount, Float::sum);
    }

    private static void addMappedNames(Set<String> target, Set<String> semantic) {
        for (String id : semantic) {
            String mapped = mappedName(id);
            if (mapped != null) target.add(mapped);
        }
    }

    private static String mappedName(String semantic) {
        switch (semantic) {
            case "FaceAngleX": return "ParamAngleX";
            case "FaceAngleY": return "ParamAngleY";
            case "FaceAngleZ": return "ParamAngleZ";
            case "MouthOpen": return "ParamMouthOpenY";
            case "MouthSmile": return "ParamMouthForm";
            case "EyeOpenLeft": return "ParamEyeLOpen";
            case "EyeOpenRight": return "ParamEyeROpen";
            case "EyeLeftX":
            case "EyeRightX": return "ParamEyeBallX";
            case "EyeLeftY":
            case "EyeRightY": return "ParamEyeBallY";
            case "BrowLeftY": return "ParamBrowLY";
            case "BrowRightY": return "ParamBrowRY";
            case "CheekPuff": return "ParamMouthFunnel";
            default: return null;
        }
    }

    private static float smoothStep(float value) {
        float p = clamp(value, 0.0f, 1.0f);
        return p * p * (3.0f - 2.0f * p);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
