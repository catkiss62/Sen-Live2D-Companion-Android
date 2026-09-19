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
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotionManager;
import com.live2d.sdk.cubism.framework.motion.CubismLipSyncUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismMotionQueueEntry;
import com.live2d.sdk.cubism.framework.motion.CubismPoseUpdater;
import com.live2d.sdk.cubism.framework.motion.IParameterProvider;
import com.live2d.sdk.cubism.framework.physics.CubismPhysics;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SenLive2DModel extends CubismUserModel {
    private static final String[] AHOGE_PART_IDS = {
            "Part13", "Part220", "ArtMesh140_Skinning2", "ArtMesh140_Skinning"
    };
    private static final String[] TAIL_PART_IDS = {"Part239"};
    private static final String[] CHEST_COVER_FOREGROUND_PART_IDS = {
            "Part100", "Part216", "Part101"
    };
    private static final float WHITE_SHIRT_POSE_FIRST_KEYFORM = 0.11f;
    private static final float ACTION_FACE_FADE_SECONDS = 0.28f;
    // Expressions and motions finish by order 310; mouth-driven physics starts at 600.
    // Put lip sync in between so the model's authored MouthOpenY physics receives the voice.
    private static final int LIP_SYNC_UPDATE_ORDER = 550;
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
    private static final String[] CHEST_COVER_ARM_IDS = {
            "larmrotate", "larmrotate2", "larmrotate3", "larmrotate4", "larmrotate5",
            "rarmrotate", "rarmrotate2", "rarmrotate3", "rarmrotate4", "rarmrotate5"
    };
    // This is an experimental pose, intentionally isolated from the normal physics baseline.
    // Mirrored X/rotation targets bring each arm inward; phone testing will determine whether
    // this moc3 exposes enough authored keyforms for a convincing crossed-arm silhouette.
    private static final float[] CHEST_COVER_ARM_TARGETS = {
            -8.5f, -9.0f, -5.0f, 8.0f, 0.8f,
            8.5f, 9.0f, 5.0f, -8.0f, 0.8f
    };
    private final Map<String, ACubismMotion> expressions = new HashMap<>();
    private final Map<String, CubismExpressionMotionManager> expressionManagers =
            new LinkedHashMap<>();
    private final Set<String> activeExpressionNames = new LinkedHashSet<>();
    private final Map<String, CubismMotion> nativeMotions = new HashMap<>();
    private final CubismExpressionMotionManager transientExpressionManager =
            new CubismExpressionMotionManager();
    private volatile float lipSyncValue;
    private final SenPerformanceEngine performance = new SenPerformanceEngine();
    private ICubismModelSetting setting;
    private File homeDirectory;
    private String appearanceDetail = "";
    private boolean hasVtsBaseProfile;
    private boolean geometryDiagnosticsAdded;
    private int[] armPhysicsIndices = new int[0];
    private float[] armPhysicsBaseValues = new float[0];
    private int[] chestCoverForegroundDrawables = new int[0];
    private int[] rabbitEarPhysicsIndices = new int[0];
    private float[] isolatedEarValues = new float[0];
    private CubismPhysics isolatedEarPhysics;
    private float[] prePhysicsValues = new float[0];
    private float[] normalPhysicsValues = new float[0];
    private float pendingEarPhysicsDrive;
    private float pendingEarPhysicsMix;
    private boolean pendingEarPhysicsActive;
    private AhogeAnchorPoint ahogeRootAnchor;
    private AhogeAnchorPoint ahogeDirectionAnchor;
    private float referenceDrawableLeft = -1.0f;
    private float referenceDrawableRight = 1.0f;
    private float referenceDrawableTop = 1.0f;
    private float referenceDrawableBottom = -1.0f;
    private String transientExpressionName = "";
    private float transientExpressionRemaining;
    private float transientExpressionDuration = 1.0f;
    private float transientExpressionFadeOut = 0.05f;
    private boolean glassesEnabled;
    private int[] shapeLockedOutfitDrawables = new int[0];
    private float[][] shapeLockedOutfitVertices = new float[0][];
    private int[] shapeLockedOutfitParameterIndices = new int[0];
    private float[] shapeLockedOutfitParameterValues = new float[0];
    private float[] shapeLockedOutfitParameterRestore = new float[0];
    private SenOutfitPresets.Preset outfitPreset = SenOutfitPresets.MAID;
    private SenRenderOptions renderOptions = new SenRenderOptions(false);

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
        SenVtsHotkeySettings vtsHotkeys = SenVtsHotkeySettings.load(homeDirectory);
        // A VTS profile is now the appearance base, not a frozen final frame. Expressions and
        // native physics are loaded in both modes so body motion, ears and tail can stay alive.
        loadExpressions(listener, vtsHotkeys);
        loadNativeMotions(listener, vtsHotkeys);
        registerLipSyncUpdater();
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
        resolveChestCoverForegroundDrawables();
        resolveRabbitEarPhysicsParameters();
        prePhysicsValues = new float[model.getParameterCount()];
        normalPhysicsValues = new float[model.getParameterCount()];
        model.saveParameters();
        applyVtsArtMeshColors(appearance, listener);
        resolveOutfitShapeLock(outfitPreset, listener);
        updateScheduler.sortUpdatableList();
        updateModelWithOutfitShapeLock();
        captureReferenceDrawableBounds();
        restoreAhogeAnchors(SenRenderOptions.AHOGE_ANCHOR_JSON);
        applyRuntimeGeometry();
        appendAppearanceDetail("动态底座：VTS→情绪/动作→原生物理→运行时几何");

        listener.onStatus("原生渲染：正在创建 OpenGL 渲染器…\n蒙版模式："
                + SenRenderOptions.MASK_MODE.displayName());
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
        if (transientExpressionRemaining > 0.0f) {
            transientExpressionRemaining = Math.max(0.0f,
                    transientExpressionRemaining - deltaSeconds);
            if (transientExpressionRemaining == 0.0f) {
                fadeOutManager(transientExpressionManager, transientExpressionFadeOut);
            }
        }
        updateScheduler.onLateUpdate(model, deltaSeconds);
        // Outfit selection is an App-owned preset. Expressions, native motions and program
        // actions may animate pose parameters, but they must never alter the selected clothes.
        if (hasVtsBaseProfile) applyOutfitParameters(outfitPreset, null);
        setParameter("Glasses", glassesEnabled ? 1.0f : 0.0f);
        skipWhiteShirtPosePreKeyframes();
        applyChestCoverArmPose();
        updateModelWithOutfitShapeLock();
        applyRuntimeGeometry();
    }

    private boolean hasCompleteAhogeAnchor() {
        return ahogeRootAnchor != null && ahogeDirectionAnchor != null;
    }

    void draw(CubismMatrix44 matrix) {
        if (model == null || getRenderer() == null) return;
        CubismMatrix44.multiply(modelMatrix.getArray(), matrix.getArray(), matrix.getArray());
        CubismRendererAndroid renderer = getRenderer();
        renderer.setForegroundDrawableIndices(
                performance.getChestCoverPoseMix() > 0.0001f
                        && outfitPreset == SenOutfitPresets.UNDRESSED
                        ? chestCoverForegroundDrawables : null);
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
        if ("glasses".equals(normalizeExpressionName(name))) {
            glassesEnabled = !glassesEnabled;
            return;
        }
        if (name != null && name.equals(transientExpressionName)) {
            ACubismMotion transientMotion = expressions.get(name);
            if (transientMotion != null) {
                transientExpressionManager.startMotionPriority(transientMotion, 3);
                transientExpressionRemaining = transientExpressionDuration;
            }
            return;
        }
        ACubismMotion motion = expressions.get(name);
        if (motion == null) return;
        CubismExpressionMotionManager manager = expressionManagers.get(name);
        if (manager == null) return;
        if (activeExpressionNames.contains(name)) {
            // A toggle-off is an explicit state change, not another authored transition.
            // Stopping immediately guarantees model.loadParameters() restores the base value
            // on the next frame instead of leaving a fading queue entry apparently enabled.
            manager.stopAllMotions();
            activeExpressionNames.remove(name);
            return;
        }
        if (isExclusiveProp(name)) {
            for (String activeName : new ArrayList<>(activeExpressionNames)) {
                if (!isExclusiveProp(activeName)) continue;
                CubismExpressionMotionManager activeManager = expressionManagers.get(activeName);
                if (activeManager != null) activeManager.stopAllMotions();
                activeExpressionNames.remove(activeName);
            }
        }
        manager.startMotionPriority(motion, 3);
        activeExpressionNames.add(name);
    }

    void resetNativePresets() {
        for (CubismExpressionMotionManager manager : expressionManagers.values()) {
            manager.stopAllMotions();
        }
        activeExpressionNames.clear();
        transientExpressionManager.stopAllMotions();
        transientExpressionRemaining = 0.0f;
        glassesEnabled = false;
    }

    void playNativeMotion(String name) {
        CubismMotion motion = nativeMotions.get(name);
        if (motion == null) return;
        motionManager.startMotionPriority(motion, 3);
    }

    void stopNativeMotion() {
        for (CubismMotionQueueEntry entry : motionManager.getCubismMotionQueueEntries()) {
            if (entry != null && !entry.isFinished()) {
                entry.setFadeOut(entry.getMotion().getFadeOutTime());
            }
        }
    }

    void selectEmotion(String name) {
        // Program emotions and authored ZIP switches are independent layers. Selecting one must
        // not silently turn off props or another explicitly enabled ZIP effect.
        performance.selectEmotion(name);
    }

    void playAction(String name) {
        if ("head_pat".equals(name) || "head_pat_confused".equals(name)) {
            prepareForHeadPat();
        }
        performance.playAction(name);
    }

    void triggerEarTwitch() {
        performance.triggerEarTwitch();
    }

    void setEarTuning(float speedPercent, float amplitudePercent) {
        performance.setEarTuning(speedPercent, amplitudePercent);
    }

    void setLipSyncValue(float value) {
        lipSyncValue = Math.max(0.0f, Math.min(1.0f, value));
    }

    void setTouchFollowEnabled(boolean enabled) {
        performance.setTouchFollowEnabled(enabled);
    }

    void setTouchTarget(boolean active, float normalizedX, float normalizedY) {
        performance.setTouchTarget(active, normalizedX, normalizedY);
    }

    void triggerHeadPat(boolean confused) {
        prepareForHeadPat();
        performance.triggerHeadPat(confused);
    }

    void releaseHeadPat() {
        performance.releaseHeadPat();
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
        resolveOutfitShapeLock(preset, null);
        updateModelWithOutfitShapeLock();
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

    private void loadExpressions(SenRenderer.Listener listener,
                                 SenVtsHotkeySettings hotkeys) throws IOException {
        int count = setting.getExpressionCount();
        if (count <= 0) return;
        listener.onStatus("原生渲染：正在读取表情 0/" + count + "…");
        for (int i = 0; i < count; i++) {
            String name = setting.getExpressionName(i);
            String fileName = setting.getExpressionFileName(i);
            CubismExpressionMotion motion = loadExpression(
                    NativeFileLoader.readFile(child(fileName)));
            if (motion != null) {
                SenVtsHotkeySettings.Rule rule = hotkeys.forFile(fileName);
                if (rule != null && rule.fadeSeconds >= 0.0f) {
                    motion.setFadeInTime(rule.fadeSeconds);
                    motion.setFadeOutTime(rule.fadeSeconds);
                }
                expressions.put(name, motion);
                expressionManagers.put(name, new CubismExpressionMotionManager());
                if (rule != null && rule.deactivateAfterSeconds) {
                    transientExpressionName = name;
                    transientExpressionDuration = Math.max(0.01f,
                            rule.deactivateAfterSecondsAmount);
                    transientExpressionFadeOut = Math.max(0.0f, rule.fadeSeconds);
                }
            }
            listener.onStatus("原生渲染：正在读取表情 " + (i + 1) + "/" + count + "…");
        }
        updateScheduler.addUpdatableList(new ACubismUpdater(300) {
            @Override public void onLateUpdate(
                    com.live2d.sdk.cubism.framework.model.CubismModel target,
                    float deltaTimeSeconds) {
                for (CubismExpressionMotionManager manager : expressionManagers.values()) {
                    manager.updateMotion(target, deltaTimeSeconds);
                }
            }
        });
        updateScheduler.addUpdatableList(new ACubismUpdater(310) {
            @Override public void onLateUpdate(
                    com.live2d.sdk.cubism.framework.model.CubismModel target,
                    float deltaTimeSeconds) {
                transientExpressionManager.updateMotion(target, deltaTimeSeconds);
            }
        });
    }

    private void loadNativeMotions(SenRenderer.Listener listener,
                                   SenVtsHotkeySettings hotkeys) throws IOException {
        List<File> files = new ArrayList<>();
        collectFiles(homeDirectory, ".motion3.json", files);
        int loaded = 0;
        for (File file : files) {
            String baseName = file.getName();
            boolean keyboard = file.getParentFile() != null
                    && "keyboard".equalsIgnoreCase(file.getParentFile().getName());
            if (!keyboard) continue;
            CubismMotion motion = loadMotion(NativeFileLoader.readFile(file));
            if (motion == null) continue;
            SenVtsHotkeySettings.Rule rule = hotkeys.forFile(baseName);
            if (rule != null && rule.fadeSeconds >= 0.0f) {
                motion.setFadeInTime(rule.fadeSeconds);
                motion.setFadeOutTime(rule.fadeSeconds);
            }
            String key = "keyboard/"
                    + baseName.replaceFirst("(?i)\\.motion3\\.json$", "");
            nativeMotions.put(key, motion);
            loaded++;
        }
        if (loaded == 0) return;
        listener.onStatus("原生渲染：已读取原包动作 " + loaded + " 个…");
        updateScheduler.addUpdatableList(new ACubismUpdater(250) {
            @Override public void onLateUpdate(
                    com.live2d.sdk.cubism.framework.model.CubismModel target,
                    float deltaTimeSeconds) {
                motionManager.updateMotion(target, deltaTimeSeconds);
            }
        });
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

    private void registerLipSyncUpdater() {
        int index = findParameterIndex("ParamMouthOpenY");
        if (index < 0) return;
        IParameterProvider provider = new IParameterProvider() {
            @Override public boolean update() { return true; }
            @Override public boolean update(float deltaTimeSeconds) { return true; }
            @Override public float getParameter() { return lipSyncValue; }
        };
        updateScheduler.addUpdatableList(new CubismLipSyncUpdater(
                Collections.singletonList(model.getParameterId(index)),
                provider, LIP_SYNC_UPDATE_ORDER));
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

    private void resolveOutfitShapeLock(SenOutfitPresets.Preset preset,
                                        SenRenderer.Listener listener) {
        Set<Integer> drawables = new LinkedHashSet<>();
        if (preset != null) {
            drawables.addAll(collectChildDrawables(
                    preset.shapeLockedPartIds.toArray(new String[0])));
            for (String drawableId : preset.shapeLockedDrawableIds) {
                int drawableIndex = findExistingDrawableIndex(drawableId);
                if (drawableIndex >= 0) drawables.add(drawableIndex);
            }
        }
        shapeLockedOutfitDrawables = new int[drawables.size()];
        shapeLockedOutfitVertices = new float[drawables.size()][];
        int output = 0;
        for (int drawable : drawables) {
            shapeLockedOutfitDrawables[output] = drawable;
            shapeLockedOutfitVertices[output] = new float[model.getDrawableVertices(drawable).length];
            output++;
        }
        List<Integer> parameterIndices = new ArrayList<>();
        List<Float> parameterValues = new ArrayList<>();
        if (preset != null) {
            for (Map.Entry<String, Float> entry : preset.shapeLockedParameters.entrySet()) {
                int index = findParameterIndex(entry.getKey());
                if (index < 0) continue;
                parameterIndices.add(index);
                parameterValues.add(entry.getValue());
            }
        }
        shapeLockedOutfitParameterIndices = new int[parameterIndices.size()];
        shapeLockedOutfitParameterValues = new float[parameterIndices.size()];
        shapeLockedOutfitParameterRestore = new float[parameterIndices.size()];
        for (int i = 0; i < parameterIndices.size(); i++) {
            shapeLockedOutfitParameterIndices[i] = parameterIndices.get(i);
            shapeLockedOutfitParameterValues[i] = parameterValues.get(i);
        }
        if (listener != null && preset != null && !drawables.isEmpty()) {
            appendAppearanceDetail("Top 0固定版型 " + drawables.size() + "个网格"
                    + " · 隔离胸型/弹跳 " + parameterIndices.size() + "项");
        }
    }

    private void updateModelWithOutfitShapeLock() {
        if (shapeLockedOutfitDrawables.length == 0
                || shapeLockedOutfitParameterIndices.length == 0) {
            model.update();
            return;
        }

        // First evaluate the selected Top with only its breast-size/bounce inputs held at the
        // neutral authored values. All body, head, arm, breathing and action parameters remain
        // untouched, so the garment still follows the character instead of being screen-fixed.
        for (int i = 0; i < shapeLockedOutfitParameterIndices.length; i++) {
            int index = shapeLockedOutfitParameterIndices[i];
            shapeLockedOutfitParameterRestore[i] =
                    model.getModel().getParameterViews()[index].getValue();
            model.getModel().getParameterViews()[index].setValue(
                    shapeLockedOutfitParameterValues[i]);
        }
        model.update();
        for (int i = 0; i < shapeLockedOutfitDrawables.length; i++) {
            float[] source = model.getDrawableVertices(shapeLockedOutfitDrawables[i]);
            System.arraycopy(source, 0, shapeLockedOutfitVertices[i], 0, source.length);
        }

        // Restore the live physics values and evaluate every other drawable normally. Replacing
        // only the selected Top vertices prevents this compatibility layer from freezing skin,
        // hair, ears, tail or the Bottom=4 garment.
        for (int i = 0; i < shapeLockedOutfitParameterIndices.length; i++) {
            model.getModel().getParameterViews()[shapeLockedOutfitParameterIndices[i]].setValue(
                    shapeLockedOutfitParameterRestore[i]);
        }
        model.update();
        for (int i = 0; i < shapeLockedOutfitDrawables.length; i++) {
            float[] destination = model.getDrawableVertices(shapeLockedOutfitDrawables[i]);
            System.arraycopy(shapeLockedOutfitVertices[i], 0, destination, 0,
                    destination.length);
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

    private void resolveChestCoverForegroundDrawables() {
        Set<Integer> drawables = collectChildDrawables(CHEST_COVER_FOREGROUND_PART_IDS);
        chestCoverForegroundDrawables = new int[drawables.size()];
        int output = 0;
        for (int drawable : drawables) chestCoverForegroundDrawables[output++] = drawable;
        appendAppearanceDetail("护胸前景手臂网格 " + drawables.size() + "项（仅脱模板）");
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

    private void applyChestCoverArmPose() {
        float mix = performance.getChestCoverPoseMix();
        if (mix <= 0.0001f) return;
        for (int i = 0; i < CHEST_COVER_ARM_IDS.length; i++) {
            int index = findParameterIndex(CHEST_COVER_ARM_IDS[i]);
            if (index < 0) continue;
            float current = model.getModel().getParameterViews()[index].getValue();
            float target = CHEST_COVER_ARM_TARGETS[i];
            float value = current + (target - current) * mix;
            float minimum = model.getParameterMinimumValue(index);
            float maximum = model.getParameterMaximumValue(index);
            model.getModel().getParameterViews()[index].setValue(
                    Math.max(minimum, Math.min(maximum, value)));
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
                    + " · 呆毛模式 " + (hasCompleteAhogeAnchor()
                    ? "固化锚点调整" : "原生保护")
                    + " · 尾巴子网格 " + tailDrawables.size()
                    + "/可见 " + countVisible(tailDrawables));
            geometryDiagnosticsAdded = true;
        }
        // Keep the exact native vertices as the source. The adjusted mode is only allowed to
        // apply one affine transform around the captured barycentric root anchor. With no valid
        // pair of anchors we deliberately fall back to native output instead of guessing.
        if (hasCompleteAhogeAnchor()) {
            applyAnchoredAhogeTransform(ahogeDrawables);
        }
        applyTailMirror(tailDrawables);
    }

    private void skipWhiteShirtPosePreKeyframes() {
        if (outfitPreset != SenOutfitPresets.WHITE_SHIRT) return;
        skipPosePreKeyframes("ParamKeyboardmouse");
        skipPosePreKeyframes("Paramhandle");
    }

    private void skipPosePreKeyframes(String id) {
        int index = findParameterIndex(id);
        if (index < 0) return;
        float value = model.getModel().getParameterViews()[index].getValue();
        // The authored shirt pose does not have a valid action drawable before 0.11. VTS was
        // captured at 14 FPS and naturally stepped over this interval; a 60 Hz renderer lands
        // on 0.10 for one frame. Keep the native curve and timing after its first keyform, but
        // hold the exact idle endpoint until that keyform exists.
        if (value > 0.0f && value < WHITE_SHIRT_POSE_FIRST_KEYFORM) {
            model.getModel().getParameterViews()[index].setValue(0.0f);
        }
    }

    private static void fadeOutManager(
            com.live2d.sdk.cubism.framework.motion.CubismMotionQueueManager manager,
            float seconds) {
        for (CubismMotionQueueEntry entry : manager.getCubismMotionQueueEntries()) {
            if (entry != null && !entry.isFinished()) entry.setFadeOut(seconds);
        }
    }

    private void clearExpressionsForHeadPat() {
        for (String name : new ArrayList<>(activeExpressionNames)) {
            if (isHeadPatRetainedExpression(name)) continue;
            CubismExpressionMotionManager manager = expressionManagers.get(name);
            if (manager != null) fadeOutManager(manager, ACTION_FACE_FADE_SECONDS);
            activeExpressionNames.remove(name);
        }
        fadeOutManager(transientExpressionManager, ACTION_FACE_FADE_SECONDS);
        transientExpressionRemaining = 0.0f;
    }

    private void prepareForHeadPat() {
        clearExpressionsForHeadPat();
        performance.clearEmotionForHeadPat();
    }

    private static boolean isHeadPatRetainedExpression(String name) {
        String normalized = normalizeExpressionName(name);
        return "controller".equals(normalized)
                || "keyboardmouse".equals(normalized)
                || "microphone".equals(normalized)
                || "glasses".equals(normalized)
                || "loading".equals(normalized);
    }

    private static boolean isExclusiveProp(String name) {
        String normalized = normalizeExpressionName(name);
        return "controller".equals(normalized)
                || "keyboardmouse".equals(normalized)
                || "microphone".equals(normalized);
    }

    private static String normalizeExpressionName(String name) {
        if (name == null) return "";
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static void collectFiles(File directory, String suffix, List<File> destination) {
        File[] children = directory == null ? null : directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectFiles(child, suffix, destination);
            else if (child.getName().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(suffix.toLowerCase(java.util.Locale.ROOT))) {
                destination.add(child);
            }
        }
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

    private static boolean validVertex(float[] vertices, int index) {
        return index >= 0 && index * 2 + 1 < vertices.length;
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
        float overall = SenRenderOptions.AHOGE_SCALE_PERCENT / 100.0f;
        float lengthScale = overall * SenRenderOptions.AHOGE_LENGTH_PERCENT / 100.0f;
        float widthScale = overall * SenRenderOptions.AHOGE_WIDTH_PERCENT / 100.0f;
        double radians = Math.toRadians(SenRenderOptions.AHOGE_ROTATION_DEGREES);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float targetRootX = root[0] + SenRenderOptions.AHOGE_OFFSET_X;
        float targetRootY = root[1] + SenRenderOptions.AHOGE_OFFSET_Y;
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

    private int findExistingDrawableIndex(String id) {
        for (int i = 0; i < model.getDrawableCount(); i++) {
            if (id.equals(model.getDrawableId(i).getString())) return i;
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
        SenMaskMode maskMode = SenRenderOptions.MASK_MODE;
        int requestedBuffers = maskMode == SenMaskMode.DEFAULT_SINGLE
                ? 1 : calculateDynamicBufferCount(stats);

        CubismRendererAndroid nativeRenderer = (CubismRendererAndroid)
                CubismRendererAndroid.create(width, height);
        setupRenderer(nativeRenderer, requestedBuffers);
        if (maskMode == SenMaskMode.HIGH_PRECISION) {
            nativeRenderer.setDrawableClippingMaskBufferSize(
                    SenRenderOptions.HIGH_PRECISION_MASK_SIZE,
                    SenRenderOptions.HIGH_PRECISION_MASK_SIZE);
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
                ? SenRenderOptions.HIGH_PRECISION_MASK_SIZE : 256) + "px"
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

    private static final class AhogeAnchorPoint {
        final int drawableIndex;
        final String drawableId;
        final int vertex1;
        final int vertex2;
        final int vertex3;
        final float weight1;
        final float weight2;
        final float weight3;
        AhogeAnchorPoint(int drawableIndex, String drawableId,
                         int vertex1, int vertex2, int vertex3,
                         float weight1, float weight2, float weight3) {
            this.drawableIndex = drawableIndex;
            this.drawableId = drawableId;
            this.vertex1 = vertex1;
            this.vertex2 = vertex2;
            this.vertex3 = vertex3;
            this.weight1 = weight1;
            this.weight2 = weight2;
            this.weight3 = weight3;
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
            if (drawableIndex < 0 || vertices == null || vertices.length() != 3
                    || weights == null || weights.length() != 3) return null;
            AhogeAnchorPoint result = new AhogeAnchorPoint(drawableIndex, drawableId,
                    vertices.getInt(0), vertices.getInt(1), vertices.getInt(2),
                    (float) weights.getDouble(0), (float) weights.getDouble(1),
                    (float) weights.getDouble(2));
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
