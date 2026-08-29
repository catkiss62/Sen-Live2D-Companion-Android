package com.catkiss.senlive2dcompanion;

import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.motion.ACubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismPoseUpdater;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

final class SenLive2DModel extends CubismUserModel {
    private final Map<String, ACubismMotion> expressions = new HashMap<>();
    private ICubismModelSetting setting;
    private File homeDirectory;
    private String profileDetail = "";

    void load(File modelFile, int width, int height, NativeTextureManager textures,
              SenRenderer.Listener listener, List<String> startupExpressions,
              SenVtsProfile profile) throws IOException {
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

        loadExpressions(listener);
        loadPhysicsAndPose(listener);

        Map<String, Float> layout = new HashMap<>();
        if (setting.getLayoutMap(layout)) modelMatrix.setupFromLayout(layout);
        applyProfileBase(profile, listener);
        model.saveParameters();
        updateScheduler.sortUpdatableList();

        listener.onStatus("原生渲染：正在创建 OpenGL 渲染器…");
        setupRenderer(CubismRendererAndroid.create(width, height));
        setupTextures(textures, listener);

        for (String expression : startupExpressions) setExpression(expression);
    }

    void reloadRenderer(int width, int height, NativeTextureManager textures,
                        SenRenderer.Listener listener) throws IOException {
        deleteRenderer();
        setupRenderer(CubismRendererAndroid.create(width, height));
        setupTextures(textures, listener);
    }

    void update(float deltaSeconds) {
        if (model == null) return;
        model.loadParameters();
        model.saveParameters();
        updateScheduler.onLateUpdate(model, deltaSeconds);
        model.update();
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

    String getProfileDetail() {
        return profileDetail;
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
            loadPhysics(NativeFileLoader.readFile(child(physicsName)));
            if (physics != null) updateScheduler.addUpdatableList(new CubismPhysicsUpdater(physics));
        }
        String poseName = setting.getPoseFileName();
        if (poseName != null && !poseName.isEmpty()) {
            listener.onStatus("原生渲染：正在读取部件姿态…");
            loadPose(NativeFileLoader.readFile(child(poseName)));
            if (pose != null) updateScheduler.addUpdatableList(new CubismPoseUpdater(pose));
        }
    }

    private void applyProfileBase(SenVtsProfile profile, SenRenderer.Listener listener)
            throws IOException {
        profileDetail = "";
        if (profile == null) return;

        int modelCount = model.getParameterCount();
        if (profile.expectedParameterCount > 0 && profile.expectedParameterCount != modelCount) {
            listener.onStatus("VTS参数数量与模型不同，正在只应用能匹配的参数…\n"
                    + profile.expectedParameterCount + " → " + modelCount);
        } else {
            listener.onStatus("正在应用 VTS 小鲸鱼静态外观参数…");
        }

        Set<String> dynamic = collectDynamicParameterIds();
        Map<String, Integer> actual = new HashMap<>();
        for (int i = 0; i < modelCount; i++) {
            actual.put(model.getParameterId(i).getString(), i);
        }

        int applied = 0;
        int excluded = 0;
        int missing = 0;
        for (Map.Entry<String, Float> entry : profile.parameters.entrySet()) {
            if (dynamic.contains(entry.getKey())) {
                excluded++;
                continue;
            }
            Integer index = actual.get(entry.getKey());
            if (index == null) {
                missing++;
                continue;
            }
            model.setParameterValue(index, entry.getValue());
            applied++;
        }
        profileDetail = "VTS静态参数 " + applied + "项"
                + " · 排除动态 " + excluded + "项"
                + (missing == 0 ? "" : " · 缺失 " + missing + "项");
    }

    private Set<String> collectDynamicParameterIds() throws IOException {
        Set<String> result = new HashSet<>();

        try {
            String physicsName = setting.getPhysicsFileName();
            if (physicsName != null && !physicsName.isEmpty()) {
                JSONObject root = new JSONObject(new String(
                        NativeFileLoader.readFile(child(physicsName)), StandardCharsets.UTF_8));
                JSONArray groups = root.optJSONArray("PhysicsSettings");
                if (groups != null) {
                    for (int i = 0; i < groups.length(); i++) {
                        JSONObject group = groups.optJSONObject(i);
                        JSONArray outputs = group == null ? null : group.optJSONArray("Output");
                        if (outputs == null) continue;
                        for (int j = 0; j < outputs.length(); j++) {
                            JSONObject output = outputs.optJSONObject(j);
                            JSONObject destination = output == null ? null
                                    : output.optJSONObject("Destination");
                            String id = destination == null ? "" : destination.optString("Id", "");
                            if (!id.isEmpty()) result.add(id);
                        }
                    }
                }
            }

            File vtubeFile = findSiblingWithSuffix(".vtube.json");
            if (vtubeFile != null) {
                JSONObject root = new JSONObject(new String(
                        NativeFileLoader.readFile(vtubeFile), StandardCharsets.UTF_8));
                JSONArray settings = root.optJSONArray("ParameterSettings");
                if (settings != null) {
                    for (int i = 0; i < settings.length(); i++) {
                        JSONObject item = settings.optJSONObject(i);
                        String id = item == null ? "" : item.optString("OutputLive2D", "");
                        if (!id.isEmpty()) result.add(id);
                    }
                }
            }
        } catch (org.json.JSONException error) {
            throw new IOException("无法解析模型的物理或VTS动态参数设置", error);
        }
        return result;
    }

    private File findSiblingWithSuffix(String suffix) {
        File[] files = homeDirectory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(suffix)) return file;
        }
        return null;
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
