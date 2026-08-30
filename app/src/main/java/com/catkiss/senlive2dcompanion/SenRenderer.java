package com.catkiss.senlive2dcompanion;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismFrameworkConfig;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class SenRenderer implements GLSurfaceView.Renderer {
    interface Listener {
        void onStatus(String status);
        void onReady(String detail);
        void onError(Throwable error);
    }

    private static final String TAG = "SenNativeCubism";

    private final Context context;
    private final Listener listener;
    private final NativeTextureManager textures = new NativeTextureManager();
    private final CubismMatrix44 projection = CubismMatrix44.create();

    private SenLive2DModel model;
    private ModelRequest pendingRequest;
    private int surfaceWidth;
    private int surfaceHeight;
    private int maxTextureSize;
    private boolean frameworkReady;
    private boolean contextRecreated;
    private long lastFrameNanos;
    private volatile float stageScale = 1.0f;
    private volatile float stageTranslateX;
    private volatile float stageTranslateY;

    SenRenderer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void requestModel(File modelFile, List<String> startupExpressions, SenVtsAppearance appearance,
                      SenVtsProfile frozenProfile, SenRenderOptions options) {
        pendingRequest = new ModelRequest(modelFile, startupExpressions, appearance, frozenProfile,
                options);
    }

    void setStageTransform(float scale, float translateX, float translateY) {
        stageScale = Math.max(0.35f, Math.min(6.0f, scale));
        // Keep at least part of the model inside the viewport at every zoom level. The previous
        // fixed +/-4 range allowed a small model to be moved completely outside the clip volume.
        float translationLimit = 0.9f + 0.5f * stageScale;
        stageTranslateX = Math.max(-translationLimit, Math.min(translationLimit, translateX));
        stageTranslateY = Math.max(-translationLimit, Math.min(translationLimit, translateY));
    }

    void applyExpression(String name) {
        if (model != null) model.setExpression(name);
    }

    void setCustomization(boolean earEnabled, float earAngleDegrees, float earVerticalOffset,
                          boolean ahogeShortened, boolean ahogeRaised) {
        if (model != null) {
            model.setCustomization(earEnabled, earAngleDegrees, earVerticalOffset,
                    ahogeShortened, ahogeRaised);
        }
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            initializeFramework();
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            int[] value = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0);
            maxTextureSize = value[0];
            textures.forgetAfterContextLoss();
            CubismShaderAndroid.getInstance().releaseInvalidShaderProgram();
            CubismShaderAndroid.deleteInstance();
            contextRecreated = model != null;
            listener.onStatus("原生 OpenGL 已启动 · 最大贴图 " + maxTextureSize + "px");
        } catch (Throwable error) {
            listener.onError(error);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        GLES20.glViewport(0, 0, width, height);
        if (contextRecreated && model != null) {
            try {
                listener.onStatus("OpenGL 上下文已恢复，正在重建原生贴图…");
                model.reloadRenderer(width, height, textures, listener);
                contextRecreated = false;
                listener.onReady(readyDetail());
            } catch (Throwable error) {
                listener.onError(error);
            }
        }
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glClearColor(0.055f, 0.035f, 0.085f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glClearDepthf(1.0f);

        if (pendingRequest != null && surfaceWidth > 0 && surfaceHeight > 0) {
            ModelRequest request = pendingRequest;
            pendingRequest = null;
            loadRequestedModel(request);
        }

        if (model == null) return;
        long now = System.nanoTime();
        float delta = lastFrameNanos == 0L ? 1.0f / 60.0f
                : Math.min(0.05f, (now - lastFrameNanos) / 1_000_000_000.0f);
        lastFrameNanos = now;

        try {
            model.update(delta);
            projection.loadIdentity();
            float aspectRatio = (float) surfaceWidth / (float) surfaceHeight;
            float displayRatio = (float) surfaceHeight / (float) surfaceWidth;
            float canvasRatio = model.getCanvasHeight() / model.getCanvasWidth();
            if (canvasRatio < displayRatio) {
                model.fitWidth(2.0f);
                projection.scale(1.0f, aspectRatio);
            } else {
                model.fitHeight(2.0f);
                projection.scale(1.0f / aspectRatio, 1.0f);
            }
            projection.scaleRelative(stageScale, stageScale);
            projection.translateRelative(stageTranslateX, stageTranslateY);
            model.draw(projection);
        } catch (Throwable error) {
            listener.onError(error);
            releaseCurrentModel();
        }
    }

    void release() {
        releaseCurrentModel();
        if (frameworkReady && CubismFramework.isInitialized()) CubismFramework.dispose();
        CubismFramework.cleanUp();
        frameworkReady = false;
    }

    private void initializeFramework() {
        if (frameworkReady && CubismFramework.isInitialized()) return;
        CubismFramework.Option option = new CubismFramework.Option();
        option.logFunction = message -> Log.d(TAG, message);
        option.loggingLevel = CubismFrameworkConfig.LogLevel.INFO;
        option.loadFileFunction = new NativeFileLoader(context);
        CubismFramework.cleanUp();
        if (!CubismFramework.startUp(option)) throw new IllegalStateException("Cubism Framework 启动失败");
        CubismFramework.initialize();
        if (!CubismFramework.isInitialized()) throw new IllegalStateException("Cubism Framework 初始化失败");
        frameworkReady = true;
    }

    private void loadRequestedModel(ModelRequest request) {
        try {
            releaseCurrentModel();
            lastFrameNanos = 0L;
            listener.onStatus("原生渲染：准备加载 Sen 2K 模型…");
            SenLive2DModel next = new SenLive2DModel();
            model = next;
            next.load(request.modelFile, surfaceWidth, surfaceHeight, textures,
                    listener, request.startupExpressions, request.appearance,
                    request.frozenProfile, request.options);
            listener.onReady(readyDetail());
        } catch (Throwable error) {
            releaseCurrentModel();
            listener.onError(error);
        }
    }

    private String readyDetail() {
        String profile = model == null ? "" : model.getAppearanceDetail();
        return "原生 Cubism 5 已就绪 · 原始2K · GL_LINEAR · GL_MAX_TEXTURE_SIZE="
                + maxTextureSize + (profile.isEmpty() ? "" : " · " + profile);
    }

    private void releaseCurrentModel() {
        textures.releaseAll();
        if (model != null) {
            model.closeModel();
            model = null;
        }
    }

    private static final class ModelRequest {
        final File modelFile;
        final List<String> startupExpressions;
        final SenVtsAppearance appearance;
        final SenVtsProfile frozenProfile;
        final SenRenderOptions options;

        ModelRequest(File modelFile, List<String> startupExpressions, SenVtsAppearance appearance,
                     SenVtsProfile frozenProfile, SenRenderOptions options) {
            this.modelFile = modelFile;
            this.startupExpressions = new ArrayList<>(startupExpressions);
            this.appearance = appearance;
            this.frozenProfile = frozenProfile;
            this.options = options;
        }
    }
}
