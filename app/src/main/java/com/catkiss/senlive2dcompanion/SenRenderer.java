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
        void onAhogeDiagnostic(String detail);
        void onAhogeAnchorProjection(float rootScreenX, float rootScreenY,
                                     float directionScreenX, float directionScreenY,
                                     boolean valid);
        void onAhogeAnchorCaptured(SenLive2DModel.AhogeCaptureResult result);
        void onAhogeDiagnosticExport(String modelJson);
        void onError(Throwable error);
    }

    private static final String TAG = "SenNativeCubism";

    private final Context context;
    private final Listener listener;
    private final NativeTextureManager textures = new NativeTextureManager();
    private final CubismMatrix44 projection = CubismMatrix44.create();
    private final CubismMatrix44 interactionMvp = CubismMatrix44.create();

    private SenLive2DModel model;
    private ModelRequest pendingRequest;
    private int surfaceWidth;
    private int surfaceHeight;
    private int maxTextureSize;
    private boolean frameworkReady;
    private boolean contextRecreated;
    private long lastFrameNanos;
    private long lastAhogeDiagnosticNanos;
    private volatile float stageScale = 1.0f;
    private volatile float stageTranslateX;
    private volatile float stageTranslateY;
    private volatile boolean touchFollowEnabled = true;
    private volatile float earSpeedPercent = 135.0f;
    private volatile float earAmplitudePercent = 100.0f;
    private volatile float modelBoundsLeft;
    private volatile float modelBoundsRight;
    private volatile float modelBoundsTop;
    private volatile float modelBoundsBottom;
    private volatile boolean modelBoundsValid;

    SenRenderer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void requestModel(File modelFile, List<String> startupExpressions, SenVtsAppearance appearance,
                      SenVtsProfile frozenProfile, SenRenderOptions options,
                      SenOutfitPresets.Preset outfitPreset) {
        pendingRequest = new ModelRequest(modelFile, startupExpressions, appearance, frozenProfile,
                options, outfitPreset);
    }

    void setStageTransform(float scale, float translateX, float translateY) {
        stageScale = Math.max(0.35f, Math.min(6.0f, scale));
        // Keep at least part of the model inside the viewport at every zoom level. The previous
        // fixed +/-4 range allowed a small model to be moved completely outside the clip volume.
        float translationLimit = 0.9f + 0.5f * stageScale;
        stageTranslateX = Math.max(-translationLimit, Math.min(translationLimit, translateX));
        stageTranslateY = Math.max(-translationLimit, Math.min(translationLimit, translateY));
    }

    boolean screenToModelNormalized(float screenX, float screenY, float[] result) {
        if (!modelBoundsValid || result == null || result.length < 2) return false;
        float left = modelBoundsLeft;
        float right = modelBoundsRight;
        float top = modelBoundsTop;
        float bottom = modelBoundsBottom;
        if (right - left < 1e-5f || top - bottom < 1e-5f) return false;
        float clipX = screenX * 2.0f - 1.0f;
        float clipY = 1.0f - screenY * 2.0f;
        result[0] = (clipX - left) / (right - left);
        result[1] = (top - clipY) / (top - bottom);
        return Float.isFinite(result[0]) && Float.isFinite(result[1]);
    }

    void applyExpression(String name) {
        if (model != null) model.setExpression(name);
    }

    void selectEmotion(String name) {
        if (model != null) model.selectEmotion(name);
    }

    void playAction(String name) {
        if (model != null) model.playAction(name);
    }

    void playNativeMotion(String name) {
        if (model != null) model.playNativeMotion(name);
    }

    void stopNativeMotion() {
        if (model != null) model.stopNativeMotion();
    }

    void triggerEarTwitch() {
        if (model != null) model.triggerEarTwitch();
    }

    void setEarTuning(float speedPercent, float amplitudePercent) {
        earSpeedPercent = Math.max(50.0f, Math.min(250.0f, speedPercent));
        earAmplitudePercent = Math.max(50.0f, Math.min(250.0f, amplitudePercent));
        if (model != null) model.setEarTuning(earSpeedPercent, earAmplitudePercent);
    }

    void setTouchFollowEnabled(boolean enabled) {
        touchFollowEnabled = enabled;
        if (model != null) model.setTouchFollowEnabled(enabled);
    }

    void setTouchTarget(boolean active, float normalizedX, float normalizedY) {
        if (model != null) model.setTouchTarget(active, normalizedX, normalizedY);
    }

    void triggerHeadPat(boolean confused) {
        if (model != null) model.triggerHeadPat(confused);
    }

    void setAutoIdle(boolean enabled) {
        if (model != null) model.setAutoIdle(enabled);
    }

    void selectOutfit(SenOutfitPresets.Preset preset) {
        if (model != null) model.selectOutfit(preset);
    }

    void setCustomization(boolean earEnabled, float earAngleDegrees, float earVerticalOffset,
                          float ahogeScalePercent, float ahogeLengthPercent,
                          float ahogeWidthPercent,
                          float ahogeRotationDegrees,
                          float ahogeOffsetX, float ahogeOffsetY,
                          boolean tailMirrored) {
        if (model != null) {
            model.setCustomization(earEnabled, earAngleDegrees, earVerticalOffset,
                    ahogeScalePercent, ahogeLengthPercent, ahogeWidthPercent,
                    ahogeRotationDegrees,
                    ahogeOffsetX, ahogeOffsetY, tailMirrored);
        }
    }

    void setAhogeMotionTuning(float rootFollowPercent, float rootRotationPercent,
                              float localMotionPercent) {
        if (model != null) {
            model.setAhogeMotionTuning(
                    rootFollowPercent, rootRotationPercent, localMotionPercent);
        }
    }

    void setAhogeNativePassthrough(boolean enabled) {
        if (model != null) model.setAhogeNativePassthrough(enabled);
    }

    void setAhogeAnchorJson(String anchorJson) {
        if (model != null) model.setAhogeAnchorJson(anchorJson);
    }

    void captureAhogeAnchor(float normalizedScreenX, float normalizedScreenY,
                            boolean rootPoint) {
        if (model == null || surfaceWidth <= 0 || surfaceHeight <= 0) {
            listener.onAhogeAnchorCaptured(
                    SenLive2DModel.AhogeCaptureResult.error("模型尚未加载"));
            return;
        }
        float clipX = normalizedScreenX * 2.0f - 1.0f;
        float clipY = 1.0f - normalizedScreenY * 2.0f;
        float modelX = interactionMvp.invertTransformX(clipX);
        float modelY = interactionMvp.invertTransformY(clipY);
        float pixelsToClip = 2.0f / Math.max(1, Math.min(surfaceWidth, surfaceHeight));
        float matrixScale = Math.max(1e-6f, Math.min(
                Math.abs(interactionMvp.getScaleX()), Math.abs(interactionMvp.getScaleY())));
        float tolerance = 18.0f * pixelsToClip / matrixScale;
        listener.onAhogeAnchorCaptured(
                model.captureAhogeAnchor(modelX, modelY, tolerance, rootPoint));
    }

    void requestAhogeDiagnosticExport() {
        listener.onAhogeDiagnosticExport(model == null
                ? "{\"error\":\"model not loaded\"}"
                : model.buildAhogeDiagnosticJson());
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
            updateInteractionBounds();
            if (now - lastAhogeDiagnosticNanos >= 500_000_000L) {
                lastAhogeDiagnosticNanos = now;
                listener.onAhogeDiagnostic(model.getAhogeDiagnostic());
                updateAhogeAnchorProjection();
            }
            model.draw(projection);
        } catch (Throwable error) {
            listener.onError(error);
            releaseCurrentModel();
        }
    }

    private void updateInteractionBounds() {
        model.copyMvpMatrix(projection, interactionMvp);
        float x1 = interactionMvp.transformX(model.getReferenceDrawableLeft());
        float x2 = interactionMvp.transformX(model.getReferenceDrawableRight());
        float y1 = interactionMvp.transformY(model.getReferenceDrawableTop());
        float y2 = interactionMvp.transformY(model.getReferenceDrawableBottom());
        modelBoundsLeft = Math.min(x1, x2);
        modelBoundsRight = Math.max(x1, x2);
        modelBoundsTop = Math.max(y1, y2);
        modelBoundsBottom = Math.min(y1, y2);
        modelBoundsValid = modelBoundsRight - modelBoundsLeft > 1e-5f
                && modelBoundsTop - modelBoundsBottom > 1e-5f;
    }

    private void updateAhogeAnchorProjection() {
        float[] points = model.getAhogeAnchorModelPoints();
        if (points == null) {
            listener.onAhogeAnchorProjection(0.0f, 0.0f, 0.0f, 0.0f, false);
            return;
        }
        float rootX = (interactionMvp.transformX(points[0]) + 1.0f) * .5f;
        float rootY = (1.0f - interactionMvp.transformY(points[1])) * .5f;
        float directionX = (interactionMvp.transformX(points[2]) + 1.0f) * .5f;
        float directionY = (1.0f - interactionMvp.transformY(points[3])) * .5f;
        listener.onAhogeAnchorProjection(rootX, rootY, directionX, directionY, true);
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
            lastAhogeDiagnosticNanos = 0L;
            listener.onStatus("原生渲染：准备加载 Sen 2K 模型…");
            SenLive2DModel next = new SenLive2DModel();
            model = next;
            next.load(request.modelFile, surfaceWidth, surfaceHeight, textures,
                    listener, request.startupExpressions, request.appearance,
                    request.frozenProfile, request.options, request.outfitPreset);
            next.setTouchFollowEnabled(touchFollowEnabled);
            next.setEarTuning(earSpeedPercent, earAmplitudePercent);
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
        modelBoundsValid = false;
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
        final SenOutfitPresets.Preset outfitPreset;

        ModelRequest(File modelFile, List<String> startupExpressions, SenVtsAppearance appearance,
                     SenVtsProfile frozenProfile, SenRenderOptions options,
                     SenOutfitPresets.Preset outfitPreset) {
            this.modelFile = modelFile;
            this.startupExpressions = new ArrayList<>(startupExpressions);
            this.appearance = appearance;
            this.frozenProfile = frozenProfile;
            this.options = options;
            this.outfitPreset = outfitPreset;
        }
    }
}
