package com.catkiss.senlive2dcompanion;

import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismModelMultiplyAndScreenColor;
import com.live2d.sdk.cubism.framework.model.CubismModelPartInfo;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.motion.ACubismMotion;
import com.live2d.sdk.cubism.framework.motion.ACubismUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismMotionQueueEntry;
import com.live2d.sdk.cubism.framework.motion.CubismPoseUpdater;
import com.live2d.sdk.cubism.framework.physics.CubismPhysics;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SenLive2DModel extends CubismUserModel {
    private static final String[] AHOGE_PART_IDS = {
            "Part13", "Part220", "ArtMesh140_Skinning2", "ArtMesh140_Skinning"
    };
    private static final String[] TAIL_PART_IDS = {"Part239"};
    private static final String[] RABBIT_EAR_PHYSICS_OUTPUT_IDS = {
            "ParamL_angle", "ParamR_angle", "ParamR_angle2"
    };
    private static final float EAR_HIDDEN_EYE_DRIVE = -1.05f;
    private static final float EAR_HIDDEN_NINE_AXIS_DRIVE = -6.0f;
    private static final String[] ARM_PHYSICS_OUTPUT_IDS = {
            "ParamBodyShoulder", "ParamBodyShoulder2", "ParamBodyShoulder3",
            "ParamBodyShoulder4", "larmrotate", "larmrotate2", "larmrotate3",
            "larmrotate4", "larmrotate5", "larmrotate7", "larmrotate8",
            "rarmrotate", "rarmrotate2", "rarmrotate3", "rarmrotate4",
            "rarmrotate5", "larmrotate17", "larmrotate18"
    };
    private final Map<String, ACubismMotion> expressions = new HashMap<>();
    private final SenPerformanceEngine performance = new SenPerformanceEngine();
    private ICubismModelSetting setting;
    private File homeDirectory;
    private String appearanceDetail = "";
    private boolean hasVtsBaseProfile;
    private boolean geometryDiagnosticsAdded;
    private int[] armPhysicsIndices = new int[0];
    private float[] armPhysicsBaseValues = new float[0];
    private int[] rabbitEarPhysicsIndices = new int[0];
    private float[] isolatedEarValues = new float[0];
    private CubismPhysics isolatedEarPhysics;
    private float[] prePhysicsValues = new float[0];
    private float[] normalPhysicsValues = new float[0];
    private float pendingEarPhysicsDrive;
    private float pendingEarPhysicsMix;
    private boolean pendingEarPhysicsActive;
    private final List<AhogeHit> lastRootHits = new ArrayList<>();
    private final List<AhogeHit> lastDirectionHits = new ArrayList<>();
    private AhogeAnchorPoint ahogeRootAnchor;
    private AhogeAnchorPoint ahogeDirectionAnchor;
    private float referenceDrawableLeft = -1.0f;
    private float referenceDrawableRight = 1.0f;
    private float referenceDrawableTop = 1.0f;
    private float referenceDrawableBottom = -1.0f;
    private SenOutfitPresets.Preset outfitPreset = SenOutfitPresets.MAID;
    private SenRenderOptions renderOptions = new SenRenderOptions(
            SenMaskMode.HIGH_PRECISION, 1024, false, 0.0f, 0.0f,
            100.0f, 100.0f, 100.0f, 0.0f, 0.0f, 0.0f,
            50.0f, 50.0f, 50.0f, false, "", false, false);

    void load(File modelFile, int width, int height, NativeTextureManager textures,
              SenRenderer.Listener listener, List<String> startupExpressions,
              SenVtsAppearance appearance, SenVtsProfile frozenProfile,
              SenRenderOptions requestedOptions,
              SenOutfitPresets.Preset requestedOutfit) throws IOException {
        homeDirectory = modelFile.getParentFile();
        if (homeDirectory == null) throw new IOException("model3 所在目录无效");

        listener.onStatus("原生渲染：正在读取 model3…");
        setting = new CubismModelSettingJson(NativeFileLoader.readFile(modelFile));
        if (setting.getJson() == null) throw new IOException("无法解析 model3.json");

        String mocName = setting.getModelFileName();
        if (mocName == null || mocName.isEmpty()) throw new IOException("model3 没有登记 moc3");
        File mocFile = child(mocName);
        listener.onStatus("原生渲染：正在创建 Cubism Core 模型…\n"
                + String.format(java.util.Locale.ROOT, "moc3 %.1f MiB · 已关闭重复一致性检查",
                mocFile.length() / 1048576.0));
        byte[] mocBytes = NativeFileLoader.readFile(mocFile);
        loadModel(mocBytes, false);
        mocBytes = null;
        if (model == null || modelMatrix == null) throw new IOException("Cubism Core 无法创建模型");

        // The 139 MiB Java buffer is no longer needed once Core has created its native model.
        // Reclaim it before decoding 26 textures one by one.
        System.gc();

        hasVtsBaseProfile = frozenProfile != null;
        outfitPreset = requestedOutfit == null ? SenOutfitPresets.MAID : requestedOutfit;
        renderOptions = requestedOptions == null ? renderOptions : requestedOptions;
        performance.setAutoIdle(renderOptions.autoIdleEnabled);
        // A VTS profile is now the appearance base, not a frozen final frame. Expressions and
        // native physics are loaded in both modes so body motion, ears and tail can stay alive.
        loadExpressions(listener);
        loadPhysicsAndPose(listener);

        Map<String, Float> layout = new HashMap<>();
        if (setting.getLayoutMap(layout)) modelMatrix.setupFromLayout(layout);
        appearanceDetail = "";
        geometryDiagnosticsAdded = false;
        if (hasVtsBaseProfile) {
            applyFrozenProfile(frozenProfile, listener);
            applyOutfitParameters(outfitPreset, listener);
        }
        resolveArmPhysicsParameters();
        resolveRabbitEarPhysicsParameters();
        prePhysicsValues = new float[model.getParameterCount()];
        normalPhysicsValues = new float[model.getParameterCount()];
        model.saveParameters();
        applyVtsArtMeshColors(appearance, listener);
        updateScheduler.sortUpdatableList();
        model.update();
        captureReferenceDrawableBounds();
        restoreAhogeAnchors(renderOptions.ahogeAnchorJson);
        applyRuntimeGeometry();
        appendAppearanceDetail("动态底座：VTS→情绪/动作→原生物理→运行时几何");

        listener.onStatus("原生渲染：正在创建 OpenGL 渲染器…\n蒙版模式："
                + renderOptions.maskMode.displayName());
        setupNativeRenderer(width, height);
        setupTextures(textures, listener);

        for (String expression : startupExpressions) setExpression(expression);
    }

    void reloadRenderer(int width, int height, NativeTextureManager textures,
                        SenRenderer.Listener listener) throws IOException {
        deleteRenderer();
        setupNativeRenderer(width, height);
        setupTextures(textures, listener);
    }

    void update(float deltaSeconds) {
        if (model == null) return;
        // Always restore the captured appearance base. Dynamic features must never accumulate
        // into part-selection, opacity or colour parameters from a previous frame.
        model.loadParameters();
        captureArmPhysicsBase();
        performance.update(deltaSeconds, new SenPerformanceEngine.ParameterWriter() {
            @Override public void add(String id, float value) { addParameter(id, value); }
            @Override public void set(String id, float value) { setParameter(id, value); }
        });
        setParameter("ParamBreath", performance.getBreathValue());
        pendingEarPhysicsDrive = performance.getEarPhysicsDrive();
        pendingEarPhysicsMix = performance.getEarPhysicsMix();
        pendingEarPhysicsActive = performance.isEarPhysicsActive();
        updateScheduler.onLateUpdate(model, deltaSeconds);
        model.update();
        applyRuntimeGeometry();
    }

    void setCustomization(boolean earEnabled, float earAngleDegrees, float earVerticalOffset,
                          float ahogeScalePercent, float ahogeLengthPercent,
                          float ahogeWidthPercent,
                          float ahogeRotationDegrees,
                          float ahogeOffsetX, float ahogeOffsetY,
                          boolean tailMirrored) {
        renderOptions = renderOptions.withCustomization(
                false, 0.0f, 0.0f,
                ahogeScalePercent, ahogeLengthPercent, ahogeWidthPercent,
                ahogeRotationDegrees,
                ahogeOffsetX, ahogeOffsetY, tailMirrored);
        if (model == null) return;
        model.loadParameters();
        model.update();
        applyRuntimeGeometry();
    }

    void setAhogeMotionTuning(float rootFollowPercent, float rootRotationPercent,
                              float localMotionPercent) {
        renderOptions = renderOptions.withAhogeMotion(
                rootFollowPercent, rootRotationPercent, localMotionPercent);
        if (model == null) return;
        model.loadParameters();
        model.update();
        applyRuntimeGeometry();
    }

    void setAhogeNativePassthrough(boolean enabled) {
        renderOptions = renderOptions.withAhogeNativePassthrough(enabled);
        if (model == null) return;
        model.loadParameters();
        model.update();
        applyRuntimeGeometry();
    }

    void setAhogeAnchorJson(String anchorJson) {
        renderOptions = renderOptions.withAhogeAnchorJson(anchorJson);
        restoreAhogeAnchors(anchorJson);
    }

    String getAhogeDiagnostic() {
        if (model == null) return "ParamAngleZ3：模型未加载";
        int index = findParameterIndex("ParamAngleZ3");
        float hairZ = index < 0 ? Float.NaN : model.getParameterValue(index);
        float[] points = getAhogeAnchorModelPoints();
        float[] root = points == null ? null : new float[]{points[0], points[1]};
        float[] direction = points == null ? null : new float[]{points[2], points[3]};
        String anchor = root == null || direction == null
                ? "固定点：未采集"
                : String.format(java.util.Locale.ROOT,
                "根(%+.4f,%+.4f) → 向(%+.4f,%+.4f) · %s",
                root[0], root[1], direction[0], direction[1],
                ahogeRootAnchor.drawableId);
        return String.format(java.util.Locale.ROOT,
                "Hair Z：%s · 模式：%s\n%s\n形状：整体%.0f%% / 长度%.0f%% / 宽度%.0f%% / 角度%+.0f° / 模型X%+.3f",
                Float.isFinite(hairZ) ? String.format(java.util.Locale.ROOT, "%+.4f", hairZ)
                        : "不存在",
                renderOptions.ahogeNativePassthrough ? "原生直通"
                        : (hasCompleteAhogeAnchor() ? "固定根部调整" : "无固定点→原生保护"),
                anchor,
                renderOptions.ahogeScalePercent, renderOptions.ahogeLengthPercent,
                renderOptions.ahogeWidthPercent, renderOptions.ahogeRotationDegrees,
                renderOptions.ahogeOffsetX);
    }

    boolean hasCompleteAhogeAnchor() {
        return ahogeRootAnchor != null && ahogeDirectionAnchor != null;
    }

    float[] getAhogeAnchorModelPoints() {
        if (!hasCompleteAhogeAnchor() || model == null) return null;
        float[] root = ahogeRootAnchor.currentPoint(model);
        float[] direction = ahogeDirectionAnchor.currentPoint(model);
        if (root == null || direction == null) return null;
        if (!renderOptions.ahogeNativePassthrough) {
            float axisX = direction[0] - root[0];
            float axisY = direction[1] - root[1];
            float axisLength = (float) Math.hypot(axisX, axisY);
            if (axisLength < 1e-5f) return null;
            axisX /= axisLength;
            axisY /= axisLength;
            float lengthScale = renderOptions.ahogeScalePercent / 100.0f
                    * renderOptions.ahogeLengthPercent / 100.0f;
            double radians = Math.toRadians(renderOptions.ahogeRotationDegrees);
            float alongX = axisX * axisLength * lengthScale;
            float alongY = axisY * axisLength * lengthScale;
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            float targetRootX = root[0] + renderOptions.ahogeOffsetX;
            float targetRootY = root[1] + renderOptions.ahogeOffsetY;
            return new float[]{targetRootX, targetRootY,
                    targetRootX + alongX * cos - alongY * sin,
                    targetRootY + alongX * sin + alongY * cos};
        }
        return new float[]{root[0], root[1], direction[0], direction[1]};
    }

    AhogeCaptureResult captureAhogeAnchor(float modelX, float modelY,
                                          float tolerance, boolean rootPoint) {
        if (model == null) return AhogeCaptureResult.error("模型尚未加载");
        List<AhogeHit> hits = findAhogeHits(modelX, modelY, tolerance);
        if (hits.isEmpty()) {
            return AhogeCaptureResult.error("点击位置没有命中长呆毛网格；请放大后点在线条内部");
        }
        if (rootPoint) {
            lastRootHits.clear();
            lastRootHits.addAll(hits);
            lastDirectionHits.clear();
            return AhogeCaptureResult.rootAccepted(String.format(java.util.Locale.ROOT,
                    "根部已采集：模型坐标(%+.5f,%+.5f)，命中%d个候选；请点根部朝尖端方向的第二点",
                    modelX, modelY, hits.size()));
        }
        if (lastRootHits.isEmpty()) {
            return AhogeCaptureResult.error("请先采集根部点");
        }
        lastDirectionHits.clear();
        lastDirectionHits.addAll(hits);
        AhogeHit[] pair = chooseAnchorPair(lastRootHits, lastDirectionHits);
        if (pair == null) {
            return AhogeCaptureResult.error("两个点过近或无法形成稳定方向；请重新从根部开始采集");
        }
        ahogeRootAnchor = pair[0].toAnchorPoint();
        ahogeDirectionAnchor = pair[1].toAnchorPoint();
        String json = buildAhogeAnchorJson();
        renderOptions = renderOptions.withAhogeAnchorJson(json);
        return AhogeCaptureResult.complete(String.format(java.util.Locale.ROOT,
                "固定点已完成：%s · 根(%+.5f,%+.5f) → 方向(%+.5f,%+.5f)",
                ahogeRootAnchor.drawableId, modelXOf(ahogeRootAnchor), modelYOf(ahogeRootAnchor),
                modelXOf(ahogeDirectionAnchor), modelYOf(ahogeDirectionAnchor)), json);
    }

    String buildAhogeDiagnosticJson() {
        try {
            JSONObject result = new JSONObject();
            result.put("schema", "sen-ahoge-headpat-diagnostics");
            result.put("schemaVersion", 1);
            result.put("coordinateSystem", new JSONObject()
                    .put("anchor", "Cubism model-local barycentric triangle coordinates")
                    .put("screenPixelsUsedAsPersistentCoordinates", false)
                    .put("stageZoomAndTranslationAffectAnchor", false));
            result.put("anchorCapture", new JSONObject(buildAhogeAnchorJson()));
            result.put("referenceDrawableBounds", new JSONObject()
                    .put("left", referenceDrawableLeft)
                    .put("right", referenceDrawableRight)
                    .put("top", referenceDrawableTop)
                    .put("bottom", referenceDrawableBottom));
            int hairIndex = findParameterIndex("ParamAngleZ3");
            result.put("currentParameters", new JSONObject()
                    .put("ParamAngleZ3", hairIndex < 0 ? JSONObject.NULL
                            : model.getParameterValue(hairIndex))
                    .put("overallPercent", renderOptions.ahogeScalePercent)
                    .put("lengthPercent", renderOptions.ahogeLengthPercent)
                    .put("widthPercent", renderOptions.ahogeWidthPercent)
                    .put("rotationDegrees", renderOptions.ahogeRotationDegrees)
                    .put("translationX", renderOptions.ahogeOffsetX)
                    .put("translationY", renderOptions.ahogeOffsetY)
                    .put("nativePassthrough", renderOptions.ahogeNativePassthrough));
            return result.toString(2);
        } catch (JSONException error) {
            return "{\"schema\":\"sen-ahoge-headpat-diagnostics\",\"error\":\""
                    + error.getClass().getSimpleName() + "\"}";
        }
    }

    void draw(CubismMatrix44 matrix) {
        if (model == null || getRenderer() == null) return;
        CubismMatrix44.multiply(modelMatrix.getArray(), matrix.getArray(), matrix.getArray());
        CubismRendererAndroid renderer = getRenderer();
        renderer.setMvpMatrix(matrix);
        renderer.drawModel();
    }

    void copyMvpMatrix(CubismMatrix44 projection, CubismMatrix44 destination) {
        destination.setMatrix(projection);
        CubismMatrix44.multiply(modelMatrix.getArray(), destination.getArray(),
                destination.getArray());
    }

    float getReferenceDrawableLeft() { return referenceDrawableLeft; }
    float getReferenceDrawableRight() { return referenceDrawableRight; }
    float getReferenceDrawableTop() { return referenceDrawableTop; }
    float getReferenceDrawableBottom() { return referenceDrawableBottom; }

    void setExpression(String name) {
        ACubismMotion motion = expressions.get(name);
        if (motion != null) expressionManager.startMotionPriority(motion, 3);
    }

    void selectEmotion(String name) {
        // Let ZIP expressions fade out instead of vanishing on the same rendered frame.
        for (CubismMotionQueueEntry entry : expressionManager.getCubismMotionQueueEntries()) {
            if (entry != null && !entry.isFinished()) entry.setFadeOut(.42f);
        }
        performance.selectEmotion(name);
    }

    void playAction(String name) {
        performance.playAction(name);
    }

    void triggerEarTwitch() {
        performance.triggerEarTwitch();
    }

    void setEarTuning(float speedPercent, float amplitudePercent) {
        performance.setEarTuning(speedPercent, amplitudePercent);
    }

    void setTouchFollowEnabled(boolean enabled) {
        performance.setTouchFollowEnabled(enabled);
    }

    void setTouchTarget(boolean active, float normalizedX, float normalizedY) {
        performance.setTouchTarget(active, normalizedX, normalizedY);
    }

    void triggerHeadPat(boolean confused) {
        performance.triggerHeadPat(confused);
    }

    void setAutoIdle(boolean enabled) {
        performance.setAutoIdle(enabled);
    }

    void selectOutfit(SenOutfitPresets.Preset preset) {
        if (model == null || preset == null || !hasVtsBaseProfile) return;
        outfitPreset = preset;
        model.loadParameters();
        applyOutfitParameters(preset, null);
        model.saveParameters();
        applyVtsArtMeshColors(preset.appearance, null);
        model.update();
        applyRuntimeGeometry();
    }

    boolean isAutoIdle() {
        return performance.isAutoIdle();
    }

    float getCanvasWidth() {
        return model == null ? 1.0f : model.getCanvasWidth();
    }

    float getCanvasHeight() {
        return model == null ? 1.0f : model.getCanvasHeight();
    }

    void fitWidth(float width) {
        if (modelMatrix != null) modelMatrix.setWidth(width);
    }

    void fitHeight(float height) {
        if (modelMatrix != null) modelMatrix.setHeight(height);
    }

    void closeModel() {
        delete();
    }

    String getAppearanceDetail() {
        return appearanceDetail;
    }

    private void loadExpressions(SenRenderer.Listener listener) throws IOException {
        int count = setting.getExpressionCount();
        if (count <= 0) return;
        listener.onStatus("原生渲染：正在读取表情 0/" + count + "…");
        for (int i = 0; i < count; i++) {
            String name = setting.getExpressionName(i);
            CubismExpressionMotion motion = loadExpression(
                    NativeFileLoader.readFile(child(setting.getExpressionFileName(i))));
            if (motion != null) expressions.put(name, motion);
            listener.onStatus("原生渲染：正在读取表情 " + (i + 1) + "/" + count + "…");
        }
        updateScheduler.addUpdatableList(new CubismExpressionUpdater(expressionManager));
    }

    private void loadPhysicsAndPose(SenRenderer.Listener listener) throws IOException {
        String physicsName = setting.getPhysicsFileName();
        if (physicsName != null && !physicsName.isEmpty()) {
            listener.onStatus("原生渲染：正在读取物理参数…");
            byte[] physicsBytes = NativeFileLoader.readFile(child(physicsName));
            loadPhysics(physicsBytes);
            isolatedEarPhysics = CubismPhysics.create(physicsBytes);
            if (physics != null) {
                // Run the ordinary rig and an independent hidden slow-blink rig from the same
                // pre-physics parameters. Only the latter's three rabbit-ear outputs are copied
                // back, so eyes, head angles, hair, body, tail and every other physics output
                // remain exactly as produced by the ordinary pass.
                updateScheduler.addUpdatableList(new ACubismUpdater(600) {
                    @Override public void onLateUpdate(
                            com.live2d.sdk.cubism.framework.model.CubismModel ignored,
                            float deltaTimeSeconds) {
                        evaluateIsolatedEarPhysics(deltaTimeSeconds);
                    }
                });
            }
        }
        String poseName = setting.getPoseFileName();
        if (poseName != null && !poseName.isEmpty()) {
            listener.onStatus("原生渲染：正在读取部件姿态…");
            loadPose(NativeFileLoader.readFile(child(poseName)));
            if (pose != null) updateScheduler.addUpdatableList(new CubismPoseUpdater(pose));
        }
    }

    private void applyVtsArtMeshColors(SenVtsAppearance appearance,
                                       SenRenderer.Listener listener) {
        if (appearance == null) return;
        if (listener != null) listener.onStatus("正在叠加 VTS 逐部件颜色…");

        CubismModelMultiplyAndScreenColor overrides = model.getOverrideMultiplyAndScreenColor();
        // A preset is a complete desired colour state. Disable every previous override first so
        // bunny-only greys cannot leak into maid/white-shirt after an in-place switch.
        for (int i = 0; i < model.getDrawableCount(); i++) {
            overrides.setDrawableMultiplyColorEnabled(i, false);
            overrides.setDrawableScreenColorEnabled(i, false);
        }
        int applied = 0;
        int missing = 0;
        for (SenVtsAppearance.ArtMeshColor entry : appearance.colors) {
            int index = model.getDrawableIndex(
                    CubismFramework.getIdManager().getId(entry.id));
            if (index < 0) {
                missing++;
                continue;
            }
            overrides.setDrawableMultiplyColor(index,
                    entry.multiply[0], entry.multiply[1], entry.multiply[2], entry.multiply[3]);
            overrides.setDrawableMultiplyColorEnabled(index, true);
            overrides.setDrawableScreenColor(index,
                    entry.screen[0], entry.screen[1], entry.screen[2], entry.screen[3]);
            overrides.setDrawableScreenColorEnabled(index, true);
            applied++;
        }
        if (listener != null) {
            appendAppearanceDetail("内置服装染色 " + applied + "项"
                    + (missing == 0 ? "" : " · 缺失 " + missing + "项"));
        }
    }

    private void applyOutfitParameters(SenOutfitPresets.Preset preset,
                                       SenRenderer.Listener listener) {
        if (preset == null) return;
        int applied = 0;
        int missing = 0;
        for (Map.Entry<String, Float> entry : preset.parameterOverrides.entrySet()) {
            int index = findParameterIndex(entry.getKey());
            if (index < 0) {
                missing++;
                continue;
            }
            model.getModel().getParameterViews()[index].setValue(entry.getValue());
            applied++;
        }
        if (listener != null) {
            appendAppearanceDetail("服装“" + preset.displayName + "”参数 " + applied + "项"
                    + (missing == 0 ? "" : " · 缺失 " + missing + "项"));
        }
    }

    private void resolveRabbitEarPhysicsParameters() {
        int count = 0;
        int[] candidates = new int[RABBIT_EAR_PHYSICS_OUTPUT_IDS.length];
        for (String id : RABBIT_EAR_PHYSICS_OUTPUT_IDS) {
            int index = findParameterIndex(id);
            if (index >= 0) candidates[count++] = index;
        }
        rabbitEarPhysicsIndices = Arrays.copyOf(candidates, count);
        isolatedEarValues = new float[count];
        appendAppearanceDetail("九轴兔耳隔离输出 " + count + "/3");
    }

    private void evaluateIsolatedEarPhysics(float deltaTimeSeconds) {
        if (physics == null) return;
        captureParameterValues(prePhysicsValues);
        physics.evaluate(model, deltaTimeSeconds);
        captureParameterValues(normalPhysicsValues);

        if (isolatedEarPhysics != null) {
            restoreParameterValues(prePhysicsValues);
            if (pendingEarPhysicsActive) {
                addParameter("ParamEyeLOpen", EAR_HIDDEN_EYE_DRIVE * pendingEarPhysicsDrive);
                addParameter("ParamEyeROpen", EAR_HIDDEN_EYE_DRIVE * pendingEarPhysicsDrive);
                addParameter("ParamAngleY", EAR_HIDDEN_NINE_AXIS_DRIVE
                        * pendingEarPhysicsDrive);
            }
            isolatedEarPhysics.evaluate(model, deltaTimeSeconds);
            for (int i = 0; i < rabbitEarPhysicsIndices.length; i++) {
                isolatedEarValues[i] = model.getModel().getParameterViews()[
                        rabbitEarPhysicsIndices[i]].getValue();
            }
            restoreParameterValues(normalPhysicsValues);
            if (pendingEarPhysicsActive) {
                for (int i = 0; i < rabbitEarPhysicsIndices.length; i++) {
                    int index = rabbitEarPhysicsIndices[i];
                    float normal = normalPhysicsValues[index];
                    model.getModel().getParameterViews()[index].setValue(
                            normal + (isolatedEarValues[i] - normal) * pendingEarPhysicsMix);
                }
            }
        }
        dampActionArmPhysics();
    }

    private void captureParameterValues(float[] destination) {
        int count = Math.min(destination.length, model.getParameterCount());
        for (int i = 0; i < count; i++) {
            destination[i] = model.getModel().getParameterViews()[i].getValue();
        }
    }

    private void restoreParameterValues(float[] source) {
        int count = Math.min(source.length, model.getParameterCount());
        for (int i = 0; i < count; i++) {
            model.getModel().getParameterViews()[i].setValue(source[i]);
        }
    }

    private void resolveArmPhysicsParameters() {
        int count = 0;
        int[] candidates = new int[ARM_PHYSICS_OUTPUT_IDS.length];
        for (String id : ARM_PHYSICS_OUTPUT_IDS) {
            int index = findParameterIndex(id);
            if (index >= 0) candidates[count++] = index;
        }
        armPhysicsIndices = Arrays.copyOf(candidates, count);
        armPhysicsBaseValues = new float[count];
        appendAppearanceDetail("动作手臂物理 " + count + "项×35%→平滑100%");
    }

    private void captureArmPhysicsBase() {
        for (int i = 0; i < armPhysicsIndices.length; i++) {
            armPhysicsBaseValues[i] = model.getModel().getParameterViews()[
                    armPhysicsIndices[i]].getValue();
        }
    }

    private void dampActionArmPhysics() {
        float gain = performance.getActionArmPhysicsGain();
        if (gain >= .999f) return;
        for (int i = 0; i < armPhysicsIndices.length; i++) {
            int index = armPhysicsIndices[i];
            float current = model.getModel().getParameterViews()[index].getValue();
            float base = armPhysicsBaseValues[i];
            model.getModel().getParameterViews()[index].setValue(
                    base + (current - base) * gain);
        }
    }

    private void applyFrozenProfile(SenVtsProfile profile, SenRenderer.Listener listener) {
        listener.onStatus("正在写入VTS动态外观底座…\n"
                + "保留部件与颜色，并在每帧叠加情绪、动作和物理");

        int modelCount = model.getParameterCount();
        Map<String, Integer> actual = new HashMap<>();
        for (int i = 0; i < modelCount; i++) {
            actual.put(model.getParameterId(i).getString(), i);
        }

        int applied = 0;
        int outsideDeclaredRange = 0;
        int missing = 0;
        for (Map.Entry<String, Float> entry : profile.parameters.entrySet()) {
            Integer index = actual.get(entry.getKey());
            if (index == null) {
                missing++;
                continue;
            }
            float value = entry.getValue();
            if (value < model.getParameterMinimumValue(index)
                    || value > model.getParameterMaximumValue(index)) {
                outsideDeclaredRange++;
            }
            // Deliberately bypass Framework setParameterValue(): it clamps to the parameter's
            // declared range, while VTube Studio captured Warning2=-1 even though its declared
            // minimum is 0. VTS_Add relies on preserving that exact out-of-range result.
            model.getModel().getParameterViews()[index].setValue(value);
            applied++;
        }
        appendAppearanceDetail("VTS底座参数 " + applied + "项"
                + " · 越界直写 " + outsideDeclaredRange + "项"
                + (missing == 0 ? "" : " · 缺失 " + missing + "项"));
    }

    private int findParameterIndex(String id) {
        for (int i = 0; i < model.getParameterCount(); i++) {
            if (id.equals(model.getParameterId(i).getString())) return i;
        }
        return -1;
    }

    private void applyRuntimeGeometry() {
        Set<Integer> ahogeDrawables = collectChildDrawables(AHOGE_PART_IDS);
        Set<Integer> tailDrawables = collectChildDrawables(TAIL_PART_IDS);
        if (!geometryDiagnosticsAdded) {
            appendAppearanceDetail("耳鳍人工网格 0（已撤销）"
                    + " · 呆毛子网格 " + ahogeDrawables.size()
                    + "/可见 " + countVisible(ahogeDrawables)
                    + " · 呆毛模式 "
                    + (renderOptions.ahogeNativePassthrough ? "原生直通"
                    : (hasCompleteAhogeAnchor() ? "固定根部调整" : "原生保护"))
                    + " · 尾巴子网格 " + tailDrawables.size()
                    + "/可见 " + countVisible(tailDrawables));
            geometryDiagnosticsAdded = true;
        }
        // Keep the exact native vertices as the source. The adjusted mode is only allowed to
        // apply one affine transform around the captured barycentric root anchor. With no valid
        // pair of anchors we deliberately fall back to native output instead of guessing.
        if (!renderOptions.ahogeNativePassthrough && hasCompleteAhogeAnchor()) {
            applyAnchoredAhogeTransform(ahogeDrawables);
        }
        applyTailMirror(tailDrawables);
    }

    private void restoreAhogeAnchors(String json) {
        ahogeRootAnchor = null;
        ahogeDirectionAnchor = null;
        if (json == null || json.trim().isEmpty() || model == null) return;
        try {
            JSONObject object = new JSONObject(json);
            AhogeAnchorPoint root = AhogeAnchorPoint.fromJson(
                    object.optJSONObject("root"), model);
            AhogeAnchorPoint direction = AhogeAnchorPoint.fromJson(
                    object.optJSONObject("direction"), model);
            if (root != null && direction != null) {
                ahogeRootAnchor = root;
                ahogeDirectionAnchor = direction;
                appendAppearanceDetail("呆毛固定点已恢复 · " + root.drawableId);
            }
        } catch (JSONException ignored) {
            appendAppearanceDetail("呆毛固定点JSON无效，已回退原生保护");
        }
    }

    private List<AhogeHit> findAhogeHits(float x, float y, float tolerance) {
        List<AhogeHit> hits = new ArrayList<>();
        AhogeHit nearest = null;
        float nearestDistanceSquared = Float.POSITIVE_INFINITY;
        int[] renderOrders = model.getRenderOrders();
        for (int drawable : collectChildDrawables(AHOGE_PART_IDS)) {
            if (!isDrawableVisible(drawable)) continue;
            float[] vertices = model.getDrawableVertices(drawable);
            short[] indices = model.getDrawableVertexIndices(drawable);
            String drawableId = model.getDrawableId(drawable).getString();
            int renderOrder = drawable < renderOrders.length ? renderOrders[drawable] : 0;
            for (int i = 0; i + 2 < indices.length; i += 3) {
                int v1 = indices[i] & 0xffff;
                int v2 = indices[i + 1] & 0xffff;
                int v3 = indices[i + 2] & 0xffff;
                if (!validVertex(vertices, v1) || !validVertex(vertices, v2)
                        || !validVertex(vertices, v3)) continue;
                float[] weights = barycentric(x, y,
                        vertices[v1 * 2], vertices[v1 * 2 + 1],
                        vertices[v2 * 2], vertices[v2 * 2 + 1],
                        vertices[v3 * 2], vertices[v3 * 2 + 1]);
                if (weights != null && weights[0] >= -.015f && weights[1] >= -.015f
                        && weights[2] >= -.015f) {
                    hits.add(new AhogeHit(drawable, drawableId, renderOrder,
                            v1, v2, v3, weights[0], weights[1], weights[2],
                            x, y, false));
                }
                for (int vertex : new int[]{v1, v2, v3}) {
                    float dx = vertices[vertex * 2] - x;
                    float dy = vertices[vertex * 2 + 1] - y;
                    float distanceSquared = dx * dx + dy * dy;
                    if (distanceSquared < nearestDistanceSquared) {
                        nearestDistanceSquared = distanceSquared;
                        nearest = new AhogeHit(drawable, drawableId, renderOrder,
                                vertex, vertex, vertex, 1.0f, 0.0f, 0.0f,
                                vertices[vertex * 2], vertices[vertex * 2 + 1], true);
                    }
                }
            }
        }
        hits.sort((a, b) -> Integer.compare(b.renderOrder, a.renderOrder));
        if (hits.isEmpty() && nearest != null
                && nearestDistanceSquared <= tolerance * tolerance) {
            hits.add(nearest);
        }
        return hits;
    }

    private AhogeHit[] chooseAnchorPair(List<AhogeHit> roots, List<AhogeHit> directions) {
        for (AhogeHit root : roots) {
            for (AhogeHit direction : directions) {
                if (root.drawableIndex != direction.drawableIndex) continue;
                if (anchorDistance(root, direction) > 1e-4f) {
                    return new AhogeHit[]{root, direction};
                }
            }
        }
        for (AhogeHit root : roots) {
            for (AhogeHit direction : directions) {
                if (anchorDistance(root, direction) > 1e-4f) {
                    return new AhogeHit[]{root, direction};
                }
            }
        }
        return null;
    }

    private float anchorDistance(AhogeHit first, AhogeHit second) {
        float[] a = first.toAnchorPoint().currentPoint(model);
        float[] b = second.toAnchorPoint().currentPoint(model);
        if (a == null || b == null) return 0.0f;
        return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private String buildAhogeAnchorJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("schema", "sen-ahoge-anchor");
            object.put("schemaVersion", 2);
            object.put("coordinateSystem", "Cubism model-local barycentric triangle coordinates");
            object.put("screenPixelsPersisted", false);
            object.put("root", ahogeRootAnchor == null ? JSONObject.NULL
                    : ahogeRootAnchor.toJson(model));
            object.put("direction", ahogeDirectionAnchor == null ? JSONObject.NULL
                    : ahogeDirectionAnchor.toJson(model));
            object.put("rootCandidates", hitsToJson(lastRootHits));
            object.put("directionCandidates", hitsToJson(lastDirectionHits));
            return object.toString();
        } catch (JSONException error) {
            return "{}";
        }
    }

    private JSONArray hitsToJson(List<AhogeHit> hits) throws JSONException {
        JSONArray result = new JSONArray();
        for (AhogeHit hit : hits) result.put(hit.toJson(model));
        return result;
    }

    private float modelXOf(AhogeAnchorPoint point) {
        float[] value = point == null ? null : point.currentPoint(model);
        return value == null ? Float.NaN : value[0];
    }

    private float modelYOf(AhogeAnchorPoint point) {
        float[] value = point == null ? null : point.currentPoint(model);
        return value == null ? Float.NaN : value[1];
    }

    private static boolean validVertex(float[] vertices, int index) {
        return index >= 0 && index * 2 + 1 < vertices.length;
    }

    private static float[] barycentric(float px, float py,
                                       float ax, float ay, float bx, float by,
                                       float cx, float cy) {
        float denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (Math.abs(denominator) < 1e-10f) return null;
        float w1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / denominator;
        float w2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / denominator;
        return new float[]{w1, w2, 1.0f - w1 - w2};
    }

    private void applyAnchoredAhogeTransform(Set<Integer> candidates) {
        float[] root = ahogeRootAnchor.currentPoint(model);
        float[] direction = ahogeDirectionAnchor.currentPoint(model);
        if (root == null || direction == null) return;
        float axisX = direction[0] - root[0];
        float axisY = direction[1] - root[1];
        float axisLength = (float) Math.hypot(axisX, axisY);
        if (axisLength < 1e-5f) return;
        axisX /= axisLength;
        axisY /= axisLength;
        float perpendicularX = -axisY;
        float perpendicularY = axisX;
        float overall = renderOptions.ahogeScalePercent / 100.0f;
        float lengthScale = overall * renderOptions.ahogeLengthPercent / 100.0f;
        float widthScale = overall * renderOptions.ahogeWidthPercent / 100.0f;
        double radians = Math.toRadians(renderOptions.ahogeRotationDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float targetRootX = root[0] + renderOptions.ahogeOffsetX;
        float targetRootY = root[1] + renderOptions.ahogeOffsetY;
        for (int index : candidates) {
            if (!isDrawableVisible(index)) continue;
            float[] vertices = model.getDrawableVertices(index);
            for (int i = 0; i + 1 < vertices.length; i += 2) {
                float dx = vertices[i] - root[0];
                float dy = vertices[i + 1] - root[1];
                float along = (dx * axisX + dy * axisY) * lengthScale;
                float across = (dx * perpendicularX + dy * perpendicularY) * widthScale;
                float scaledX = axisX * along + perpendicularX * across;
                float scaledY = axisY * along + perpendicularY * across;
                vertices[i] = targetRootX + scaledX * cos - scaledY * sin;
                vertices[i + 1] = targetRootY + scaledX * sin + scaledY * cos;
            }
        }
    }

    private void captureReferenceDrawableBounds() {
        float left = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float top = Float.NEGATIVE_INFINITY;
        float bottom = Float.POSITIVE_INFINITY;
        for (int drawable = 0; drawable < model.getDrawableCount(); drawable++) {
            if (model.getDrawableOpacity(drawable) <= .001f) continue;
            float[] vertices = model.getDrawableVertices(drawable);
            for (int i = 0; i + 1 < vertices.length; i += 2) {
                left = Math.min(left, vertices[i]);
                right = Math.max(right, vertices[i]);
                top = Math.max(top, vertices[i + 1]);
                bottom = Math.min(bottom, vertices[i + 1]);
            }
        }
        if (Float.isFinite(left) && Float.isFinite(right) && right - left > 1e-5f
                && Float.isFinite(top) && Float.isFinite(bottom) && top - bottom > 1e-5f) {
            referenceDrawableLeft = left;
            referenceDrawableRight = right;
            referenceDrawableTop = top;
            referenceDrawableBottom = bottom;
        }
    }

    private void applyTailMirror(Set<Integer> indices) {
        if (!renderOptions.tailMirrored) return;
        for (int index : indices) {
            if (!isDrawableVisible(index)) continue;
            float[] vertices = model.getDrawableVertices(index);
            for (int i = 0; i + 1 < vertices.length; i += 2) vertices[i] = -vertices[i];
        }
    }


    private void addParameter(String id, float delta) {
        int index = findParameterIndex(id);
        if (index < 0 || Math.abs(delta) < 0.00001f) return;
        float value = model.getModel().getParameterViews()[index].getValue() + delta;
        float min = model.getParameterMinimumValue(index);
        float max = model.getParameterMaximumValue(index);
        model.getModel().getParameterViews()[index].setValue(Math.max(min, Math.min(max, value)));
    }

    private void setParameter(String id, float value) {
        int index = findParameterIndex(id);
        if (index < 0) return;
        float min = model.getParameterMinimumValue(index);
        float max = model.getParameterMaximumValue(index);
        model.getModel().getParameterViews()[index].setValue(Math.max(min, Math.min(max, value)));
    }

    private Set<Integer> collectChildDrawables(String[] partIds) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String partId : partIds) {
            int partIndex = findExistingPartIndex(partId);
            if (partIndex < 0 || partIndex >= model.getPartsHierarchy().size()) continue;
            model.getPartChildDrawObjects(partIndex);
            CubismModelPartInfo info = model.getPartsHierarchy().get(partIndex);
            result.addAll(info.childDrawObjects.drawableIndices);
        }
        return result;
    }

    private int findExistingPartIndex(String id) {
        for (int i = 0; i < model.getPartCount(); i++) {
            if (id.equals(model.getPartId(i).getString())) return i;
        }
        return -1;
    }

    private int countVisible(Set<Integer> indices) {
        int count = 0;
        for (int index : indices) if (isDrawableVisible(index)) count++;
        return count;
    }

    private boolean isDrawableVisible(int index) {
        return index >= 0 && index < model.getDrawableCount()
                && model.getDrawableDynamicFlagIsVisible(index)
                && model.getDrawableOpacity(index) > 0.001f;
    }

    private void appendAppearanceDetail(String detail) {
        if (detail == null || detail.isEmpty()) return;
        appearanceDetail = appearanceDetail.isEmpty() ? detail : appearanceDetail + " · " + detail;
    }

    private void setupNativeRenderer(int width, int height) {
        MaskStats stats = inspectMasks();
        SenMaskMode maskMode = renderOptions.maskMode;
        int requestedBuffers = maskMode == SenMaskMode.DEFAULT_SINGLE
                ? 1 : calculateDynamicBufferCount(stats);

        CubismRendererAndroid nativeRenderer = (CubismRendererAndroid)
                CubismRendererAndroid.create(width, height);
        setupRenderer(nativeRenderer, requestedBuffers);
        if (maskMode == SenMaskMode.HIGH_PRECISION) {
            nativeRenderer.setDrawableClippingMaskBufferSize(
                    renderOptions.highPrecisionMaskSize,
                    renderOptions.highPrecisionMaskSize);
            nativeRenderer.isUsingHighPrecisionMask(true);
        }

        int drawableBuffers = stats.drawableGroups == 0
                ? 0 : nativeRenderer.getDrawableRenderTextureCount();
        int offscreenBuffers = stats.offscreenGroups == 0
                ? 0 : nativeRenderer.getOffscreenRenderTextureCount();
        appendAppearanceDetail("蒙版" + maskMode.code
                + " · Drawable组 " + stats.drawableGroups
                + "/对象 " + stats.maskedDrawables
                + " · Offscreen组 " + stats.offscreenGroups
                + "/对象 " + stats.maskedOffscreens
                + " · 缓冲 D" + drawableBuffers + "/O" + offscreenBuffers
                + " · 尺寸 " + (maskMode == SenMaskMode.HIGH_PRECISION
                ? renderOptions.highPrecisionMaskSize : 256) + "px"
                + " · 高精度 " + (nativeRenderer.isUsingHighPrecisionMask() ? "开" : "关")
                + " · Blend " + (model.isBlendModeEnabled() ? "有" : "无")
                + " · Offscreen总数 " + model.getOffscreenCount());
    }

    private MaskStats inspectMasks() {
        MaskStats drawable = countUniqueMaskGroups(
                model.getDrawableMasks(), model.getDrawableMaskCounts(), model.getDrawableCount());
        MaskStats offscreen = countUniqueMaskGroups(
                model.getOffscreenMasks(), model.getOffscreenMaskCounts(), model.getOffscreenCount());
        return new MaskStats(drawable.drawableGroups, drawable.maskedDrawables,
                offscreen.drawableGroups, offscreen.maskedDrawables);
    }

    private static MaskStats countUniqueMaskGroups(int[][] masks, int[] counts, int objectCount) {
        Set<String> unique = new HashSet<>();
        int maskedObjects = 0;
        int safeCount = Math.min(objectCount,
                Math.min(masks == null ? 0 : masks.length, counts == null ? 0 : counts.length));
        for (int i = 0; i < safeCount; i++) {
            int count = Math.min(Math.max(0, counts[i]), masks[i] == null ? 0 : masks[i].length);
            if (count == 0) continue;
            maskedObjects++;
            int[] canonical = Arrays.copyOf(masks[i], count);
            Arrays.sort(canonical);
            unique.add(Arrays.toString(canonical));
        }
        return new MaskStats(unique.size(), maskedObjects, 0, 0);
    }

    private static int calculateDynamicBufferCount(MaskStats stats) {
        int groups = Math.max(stats.drawableGroups, stats.offscreenGroups);
        if (groups <= 36) return 1;
        // With two or more render textures the official Framework lays out up to 32 contexts
        // per texture. 64 is a safety ceiling for malformed or hostile imported models.
        return Math.min(64, Math.max(2, (groups + 31) / 32));
    }

    static final class AhogeCaptureResult {
        final boolean success;
        final boolean complete;
        final String message;
        final String anchorJson;

        private AhogeCaptureResult(boolean success, boolean complete,
                                   String message, String anchorJson) {
            this.success = success;
            this.complete = complete;
            this.message = message;
            this.anchorJson = anchorJson == null ? "" : anchorJson;
        }

        static AhogeCaptureResult error(String message) {
            return new AhogeCaptureResult(false, false, message, "");
        }

        static AhogeCaptureResult rootAccepted(String message) {
            return new AhogeCaptureResult(true, false, message, "");
        }

        static AhogeCaptureResult complete(String message, String json) {
            return new AhogeCaptureResult(true, true, message, json);
        }
    }

    private static final class AhogeHit {
        final int drawableIndex;
        final String drawableId;
        final int renderOrder;
        final int vertex1;
        final int vertex2;
        final int vertex3;
        final float weight1;
        final float weight2;
        final float weight3;
        final float capturedX;
        final float capturedY;
        final boolean nearestVertexFallback;

        AhogeHit(int drawableIndex, String drawableId, int renderOrder,
                 int vertex1, int vertex2, int vertex3,
                 float weight1, float weight2, float weight3,
                 float capturedX, float capturedY, boolean nearestVertexFallback) {
            this.drawableIndex = drawableIndex;
            this.drawableId = drawableId;
            this.renderOrder = renderOrder;
            this.vertex1 = vertex1;
            this.vertex2 = vertex2;
            this.vertex3 = vertex3;
            this.weight1 = weight1;
            this.weight2 = weight2;
            this.weight3 = weight3;
            this.capturedX = capturedX;
            this.capturedY = capturedY;
            this.nearestVertexFallback = nearestVertexFallback;
        }

        AhogeAnchorPoint toAnchorPoint() {
            return new AhogeAnchorPoint(drawableIndex, drawableId,
                    vertex1, vertex2, vertex3, weight1, weight2, weight3,
                    capturedX, capturedY);
        }

        JSONObject toJson(com.live2d.sdk.cubism.framework.model.CubismModel model)
                throws JSONException {
            JSONObject result = toAnchorPoint().toJson(model);
            result.put("renderOrder", renderOrder);
            result.put("nearestVertexFallback", nearestVertexFallback);
            return result;
        }
    }

    private static final class AhogeAnchorPoint {
        final int drawableIndex;
        final String drawableId;
        final int vertex1;
        final int vertex2;
        final int vertex3;
        final float weight1;
        final float weight2;
        final float weight3;
        final float capturedX;
        final float capturedY;

        AhogeAnchorPoint(int drawableIndex, String drawableId,
                         int vertex1, int vertex2, int vertex3,
                         float weight1, float weight2, float weight3,
                         float capturedX, float capturedY) {
            this.drawableIndex = drawableIndex;
            this.drawableId = drawableId;
            this.vertex1 = vertex1;
            this.vertex2 = vertex2;
            this.vertex3 = vertex3;
            this.weight1 = weight1;
            this.weight2 = weight2;
            this.weight3 = weight3;
            this.capturedX = capturedX;
            this.capturedY = capturedY;
        }

        float[] currentPoint(com.live2d.sdk.cubism.framework.model.CubismModel model) {
            if (drawableIndex < 0 || drawableIndex >= model.getDrawableCount()) return null;
            float[] vertices = model.getDrawableVertices(drawableIndex);
            if (!validVertex(vertices, vertex1) || !validVertex(vertices, vertex2)
                    || !validVertex(vertices, vertex3)) return null;
            return new float[]{
                    vertices[vertex1 * 2] * weight1
                            + vertices[vertex2 * 2] * weight2
                            + vertices[vertex3 * 2] * weight3,
                    vertices[vertex1 * 2 + 1] * weight1
                            + vertices[vertex2 * 2 + 1] * weight2
                            + vertices[vertex3 * 2 + 1] * weight3
            };
        }

        JSONObject toJson(com.live2d.sdk.cubism.framework.model.CubismModel model)
                throws JSONException {
            float[] current = currentPoint(model);
            return new JSONObject()
                    .put("drawableId", drawableId)
                    .put("drawableIndexDiagnosticOnly", drawableIndex)
                    .put("triangleVertexIds", new JSONArray()
                            .put(vertex1).put(vertex2).put(vertex3))
                    .put("barycentricWeights", new JSONArray()
                            .put(weight1).put(weight2).put(weight3))
                    .put("capturedModelPoint", new JSONArray()
                            .put(capturedX).put(capturedY))
                    .put("currentModelPoint", current == null ? JSONObject.NULL
                            : new JSONArray().put(current[0]).put(current[1]));
        }

        static AhogeAnchorPoint fromJson(JSONObject object,
                                         com.live2d.sdk.cubism.framework.model.CubismModel model)
                throws JSONException {
            if (object == null) return null;
            String drawableId = object.optString("drawableId", "");
            int drawableIndex = -1;
            for (int i = 0; i < model.getDrawableCount(); i++) {
                if (drawableId.equals(model.getDrawableId(i).getString())) {
                    drawableIndex = i;
                    break;
                }
            }
            JSONArray vertices = object.optJSONArray("triangleVertexIds");
            JSONArray weights = object.optJSONArray("barycentricWeights");
            JSONArray captured = object.optJSONArray("capturedModelPoint");
            if (drawableIndex < 0 || vertices == null || vertices.length() != 3
                    || weights == null || weights.length() != 3) return null;
            AhogeAnchorPoint result = new AhogeAnchorPoint(drawableIndex, drawableId,
                    vertices.getInt(0), vertices.getInt(1), vertices.getInt(2),
                    (float) weights.getDouble(0), (float) weights.getDouble(1),
                    (float) weights.getDouble(2),
                    captured == null ? Float.NaN : (float) captured.optDouble(0, Double.NaN),
                    captured == null ? Float.NaN : (float) captured.optDouble(1, Double.NaN));
            return result.currentPoint(model) == null ? null : result;
        }
    }

    private static final class MaskStats {
        final int drawableGroups;
        final int maskedDrawables;
        final int offscreenGroups;
        final int maskedOffscreens;

        MaskStats(int drawableGroups, int maskedDrawables,
                  int offscreenGroups, int maskedOffscreens) {
            this.drawableGroups = drawableGroups;
            this.maskedDrawables = maskedDrawables;
            this.offscreenGroups = offscreenGroups;
            this.maskedOffscreens = maskedOffscreens;
        }
    }

    private void setupTextures(NativeTextureManager textures,
                               SenRenderer.Listener listener) throws IOException {
        int count = setting.getTextureCount();
        for (int i = 0; i < count; i++) {
            String relative = setting.getTextureFileName(i);
            if (relative == null || relative.isEmpty()) continue;
            listener.onStatus("原生渲染：正在逐张上传 2K 贴图 " + (i + 1) + "/" + count
                    + "…\nGL_LINEAR 单级贴图，未生成 mipmap");
            NativeTextureManager.TextureInfo texture = textures.loadPng(child(relative));
            CubismRendererAndroid renderer = getRenderer();
            renderer.bindTexture(i, texture.id);
            renderer.isPremultipliedAlpha(true);
        }
    }

    private File child(String relative) throws IOException {
        File file = new File(homeDirectory, relative);
        String safeRoot = homeDirectory.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(safeRoot) || !file.isFile()) {
            throw new IOException("模型引用的文件不存在或路径不安全：" + relative);
        }
        return file;
    }
}
