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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
    private static final float AHOGE_FLEX_GAIN = .18f;
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
    private final Map<Integer, float[]> ahogeReferenceVertices = new HashMap<>();
    private final List<HairSupportPoint> ahogeHairSupports = new ArrayList<>();
    private final Set<Integer> rabbitEarDrivenDrawables = new HashSet<>();
    private float ahogeReferenceAnchorX;
    private float ahogeReferenceAnchorY;
    private SenOutfitPresets.Preset outfitPreset = SenOutfitPresets.MAID;
    private SenRenderOptions renderOptions = new SenRenderOptions(
            SenMaskMode.HIGH_PRECISION, 1024, false, 0.0f, 0.0f,
            100.0f, 100.0f, 0.0f, 0.0f, 0.0f, false, false);

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
        captureAhogeReferenceGeometry();
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
                          float ahogeScalePercent, float ahogeWidthPercent,
                          float ahogeRotationDegrees,
                          float ahogeOffsetX, float ahogeOffsetY,
                          boolean tailMirrored) {
        renderOptions = renderOptions.withCustomization(
                false, 0.0f, 0.0f,
                ahogeScalePercent, ahogeWidthPercent, ahogeRotationDegrees,
                ahogeOffsetX, ahogeOffsetY, tailMirrored);
        if (model == null) return;
        model.loadParameters();
        model.update();
        applyRuntimeGeometry();
    }

    void draw(CubismMatrix44 matrix) {
        if (model == null || getRenderer() == null) return;
        CubismMatrix44.multiply(modelMatrix.getArray(), matrix.getArray(), matrix.getArray());
        CubismRendererAndroid renderer = getRenderer();
        renderer.setMvpMatrix(matrix);
        renderer.drawModel();
    }

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
                    + " · 尾巴子网格 " + tailDrawables.size()
                    + "/可见 " + countVisible(tailDrawables));
            geometryDiagnosticsAdded = true;
        }
        applyAhogeHairRig(ahogeDrawables);
        applyTailMirror(tailDrawables);
    }

    private void captureAhogeReferenceGeometry() {
        ahogeReferenceVertices.clear();
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        for (int index : collectChildDrawables(AHOGE_PART_IDS)) {
            if (isDrawableVisible(index)) {
                float[] vertices = model.getDrawableVertices(index).clone();
                ahogeReferenceVertices.put(index, vertices);
                for (int i = 0; i + 1 < vertices.length; i += 2) {
                    minX = Math.min(minX, vertices[i]);
                    maxX = Math.max(maxX, vertices[i]);
                    minY = Math.min(minY, vertices[i + 1]);
                }
            }
        }
        ahogeReferenceAnchorX = Float.isFinite(minX) ? (minX + maxX) * .5f : 0.0f;
        ahogeReferenceAnchorY = Float.isFinite(minY) ? minY : 0.0f;
        detectRabbitEarDrivenDrawables();
        captureAhogeHairSupports();
        appendAppearanceDetail("呆毛头发整体支撑 " + ahogeHairSupports.size()
                + "点 · 排除兔耳网格 " + rabbitEarDrivenDrawables.size()
                + "个 · 局部柔性 " + Math.round(AHOGE_FLEX_GAIN * 100.0f) + "%");
    }

    /**
     * The purchased model does not expose a stable semantic part id for every rabbit-ear
     * Drawable. Selecting the nearest visible vertices therefore attached the ahoge support to
     * the ears in v0.4.2. Probe the three confirmed rabbit-ear output parameters once at load
     * time and exclude every Drawable whose vertices respond. This follows actual rig behaviour
     * and does not guess from ArtMesh names or screen position.
     */
    private void detectRabbitEarDrivenDrawables() {
        rabbitEarDrivenDrawables.clear();
        if (rabbitEarPhysicsIndices.length == 0) return;
        int parameterCount = model.getParameterCount();
        float[] savedParameters = new float[parameterCount];
        for (int i = 0; i < parameterCount; i++) {
            savedParameters[i] = model.getModel().getParameterViews()[i].getValue();
        }
        Map<Integer, float[]> baselineVertices = new HashMap<>();
        for (int drawable = 0; drawable < model.getDrawableCount(); drawable++) {
            if (isDrawableVisible(drawable)) {
                baselineVertices.put(drawable, model.getDrawableVertices(drawable).clone());
            }
        }
        for (int parameterIndex : rabbitEarPhysicsIndices) {
            float minimum = model.getParameterMinimumValue(parameterIndex);
            float maximum = model.getParameterMaximumValue(parameterIndex);
            float current = savedParameters[parameterIndex];
            float probe = Math.max(2.0f, (maximum - minimum) * .12f);
            float target = current + probe <= maximum ? current + probe : current - probe;
            model.getModel().getParameterViews()[parameterIndex].setValue(
                    Math.max(minimum, Math.min(maximum, target)));
            model.update();
            for (Map.Entry<Integer, float[]> entry : baselineVertices.entrySet()) {
                float[] baseline = entry.getValue();
                float[] currentVertices = model.getDrawableVertices(entry.getKey());
                if (baseline.length != currentVertices.length) continue;
                for (int i = 0; i < baseline.length; i++) {
                    float delta = currentVertices[i] - baseline[i];
                    if (delta * delta > 1.0e-8f) {
                        rabbitEarDrivenDrawables.add(entry.getKey());
                        break;
                    }
                }
            }
            model.getModel().getParameterViews()[parameterIndex].setValue(current);
            model.update();
        }
        for (int i = 0; i < parameterCount; i++) {
            model.getModel().getParameterViews()[i].setValue(savedParameters[i]);
        }
        model.update();
    }

    private void captureAhogeHairSupports() {
        ahogeHairSupports.clear();
        if (ahogeReferenceVertices.isEmpty()) return;
        Set<Integer> excluded = collectChildDrawables(AHOGE_PART_IDS);
        excluded.addAll(collectChildDrawables(TAIL_PART_IDS));
        excluded.addAll(rabbitEarDrivenDrawables);
        List<HairSupportCandidate> candidates = new ArrayList<>();
        for (int drawable = 0; drawable < model.getDrawableCount(); drawable++) {
            if (excluded.contains(drawable) || !isDrawableVisible(drawable)) continue;
            float[] vertices = model.getDrawableVertices(drawable);
            for (int vertex = 0; vertex * 2 + 1 < vertices.length; vertex++) {
                int offset = vertex * 2;
                float dx = vertices[offset] - ahogeReferenceAnchorX;
                float dy = vertices[offset + 1] - ahogeReferenceAnchorY;
                candidates.add(new HairSupportCandidate(drawable, vertex,
                        vertices[offset], vertices[offset + 1], dx * dx + dy * dy));
            }
        }
        candidates.sort(Comparator.comparingDouble(
                (HairSupportCandidate value) -> value.distanceSquared));
        Map<Integer, Integer> perDrawable = new HashMap<>();
        for (HairSupportCandidate candidate : candidates) {
            int used = perDrawable.getOrDefault(candidate.drawableIndex, 0);
            if (used >= 4) continue;
            ahogeHairSupports.add(new HairSupportPoint(candidate.drawableIndex,
                    candidate.vertexIndex, candidate.referenceX, candidate.referenceY));
            perDrawable.put(candidate.drawableIndex, used + 1);
            if (ahogeHairSupports.size() >= 20) break;
        }
    }

    private SimilarityTransform estimateAhogeTransform(Set<Integer> candidates) {
        if (ahogeReferenceVertices.isEmpty()) return null;
        double referenceCenterX = 0.0;
        double referenceCenterY = 0.0;
        double currentCenterX = 0.0;
        double currentCenterY = 0.0;
        int points = 0;
        for (int index : candidates) {
            if (!isDrawableVisible(index)) continue;
            float[] reference = ahogeReferenceVertices.get(index);
            float[] current = model.getDrawableVertices(index);
            if (reference == null || reference.length != current.length) continue;
            for (int i = 0; i + 1 < current.length; i += 2) {
                referenceCenterX += reference[i];
                referenceCenterY += reference[i + 1];
                currentCenterX += current[i];
                currentCenterY += current[i + 1];
                points++;
            }
        }
        if (points < 3) return null;
        referenceCenterX /= points;
        referenceCenterY /= points;
        currentCenterX /= points;
        currentCenterY /= points;
        double denominator = 0.0;
        double dot = 0.0;
        double cross = 0.0;
        for (int index : candidates) {
            if (!isDrawableVisible(index)) continue;
            float[] reference = ahogeReferenceVertices.get(index);
            float[] current = model.getDrawableVertices(index);
            if (reference == null || reference.length != current.length) continue;
            for (int i = 0; i + 1 < current.length; i += 2) {
                double rx = reference[i] - referenceCenterX;
                double ry = reference[i + 1] - referenceCenterY;
                double cx = current[i] - currentCenterX;
                double cy = current[i + 1] - currentCenterY;
                denominator += rx * rx + ry * ry;
                dot += rx * cx + ry * cy;
                cross += rx * cy - ry * cx;
            }
        }
        if (denominator < 1e-8) return null;
        return new SimilarityTransform((float) referenceCenterX, (float) referenceCenterY,
                (float) currentCenterX, (float) currentCenterY,
                (float) (dot / denominator), (float) (cross / denominator));
    }

    private SimilarityTransform estimateHairSupportTransform() {
        if (ahogeHairSupports.size() < 3) return null;
        double referenceCenterX = 0.0;
        double referenceCenterY = 0.0;
        double currentCenterX = 0.0;
        double currentCenterY = 0.0;
        int count = 0;
        for (HairSupportPoint point : ahogeHairSupports) {
            if (!isDrawableVisible(point.drawableIndex)) continue;
            float[] vertices = model.getDrawableVertices(point.drawableIndex);
            int offset = point.vertexIndex * 2;
            if (offset < 0 || offset + 1 >= vertices.length) continue;
            referenceCenterX += point.referenceX;
            referenceCenterY += point.referenceY;
            currentCenterX += vertices[offset];
            currentCenterY += vertices[offset + 1];
            count++;
        }
        if (count < 3) return null;
        referenceCenterX /= count;
        referenceCenterY /= count;
        currentCenterX /= count;
        currentCenterY /= count;
        double denominator = 0.0;
        double dot = 0.0;
        double cross = 0.0;
        for (HairSupportPoint point : ahogeHairSupports) {
            if (!isDrawableVisible(point.drawableIndex)) continue;
            float[] vertices = model.getDrawableVertices(point.drawableIndex);
            int offset = point.vertexIndex * 2;
            if (offset < 0 || offset + 1 >= vertices.length) continue;
            double rx = point.referenceX - referenceCenterX;
            double ry = point.referenceY - referenceCenterY;
            double cx = vertices[offset] - currentCenterX;
            double cy = vertices[offset + 1] - currentCenterY;
            denominator += rx * rx + ry * ry;
            dot += rx * cx + ry * cy;
            cross += rx * cy - ry * cx;
        }
        if (denominator < 1e-8) return null;
        return new SimilarityTransform((float) referenceCenterX, (float) referenceCenterY,
                (float) currentCenterX, (float) currentCenterY,
                (float) (dot / denominator), (float) (cross / denominator));
    }

    private void applyAhogeHairRig(Set<Integer> candidates) {
        SimilarityTransform selfTransform = estimateAhogeTransform(candidates);
        SimilarityTransform hairTransform = estimateHairSupportTransform();
        if (selfTransform == null) return;
        if (hairTransform == null) hairTransform = selfTransform;

        float scale = renderOptions.ahogeScalePercent / 100.0f;
        float widthScale = renderOptions.ahogeWidthPercent / 100.0f;
        double radians = Math.toRadians(renderOptions.ahogeRotationDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float offsetX = getCanvasWidth() * renderOptions.ahogeOffsetX / 1000.0f;
        float offsetY = getCanvasHeight() * renderOptions.ahogeOffsetY / 1000.0f;
        for (int index : candidates) {
            if (!isDrawableVisible(index)) continue;
            float[] reference = ahogeReferenceVertices.get(index);
            float[] current = model.getDrawableVertices(index);
            if (reference == null || reference.length != current.length) continue;
            for (int i = 0; i + 1 < current.length; i += 2) {
                float selfX = selfTransform.mapX(reference[i], reference[i + 1]);
                float selfY = selfTransform.mapY(reference[i], reference[i + 1]);
                float residualX = current[i] - selfX;
                float residualY = current[i + 1] - selfY;

                float dx = (reference[i] - ahogeReferenceAnchorX) * scale * widthScale;
                float dy = (reference[i + 1] - ahogeReferenceAnchorY) * scale;
                float confirmedX = ahogeReferenceAnchorX + dx * cos - dy * sin + offsetX;
                float confirmedY = ahogeReferenceAnchorY + dx * sin + dy * cos + offsetY;
                current[i] = hairTransform.mapX(confirmedX, confirmedY)
                        + residualX * AHOGE_FLEX_GAIN;
                current[i + 1] = hairTransform.mapY(confirmedX, confirmedY)
                        + residualY * AHOGE_FLEX_GAIN;
            }
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

    private static final class HairSupportCandidate {
        final int drawableIndex;
        final int vertexIndex;
        final float referenceX;
        final float referenceY;
        final float distanceSquared;

        HairSupportCandidate(int drawableIndex, int vertexIndex,
                             float referenceX, float referenceY, float distanceSquared) {
            this.drawableIndex = drawableIndex;
            this.vertexIndex = vertexIndex;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
            this.distanceSquared = distanceSquared;
        }
    }

    private static final class HairSupportPoint {
        final int drawableIndex;
        final int vertexIndex;
        final float referenceX;
        final float referenceY;

        HairSupportPoint(int drawableIndex, int vertexIndex, float referenceX, float referenceY) {
            this.drawableIndex = drawableIndex;
            this.vertexIndex = vertexIndex;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
        }
    }

    private static final class SimilarityTransform {
        final float referenceCenterX;
        final float referenceCenterY;
        final float currentCenterX;
        final float currentCenterY;
        final float a;
        final float b;

        SimilarityTransform(float referenceCenterX, float referenceCenterY,
                            float currentCenterX, float currentCenterY,
                            float a, float b) {
            this.referenceCenterX = referenceCenterX;
            this.referenceCenterY = referenceCenterY;
            this.currentCenterX = currentCenterX;
            this.currentCenterY = currentCenterY;
            this.a = a;
            this.b = b;
        }

        float mapX(float x, float y) {
            float dx = x - referenceCenterX;
            float dy = y - referenceCenterY;
            return currentCenterX + a * dx - b * dy;
        }

        float mapY(float x, float y) {
            float dx = x - referenceCenterX;
            float dy = y - referenceCenterY;
            return currentCenterY + b * dx + a * dy;
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
