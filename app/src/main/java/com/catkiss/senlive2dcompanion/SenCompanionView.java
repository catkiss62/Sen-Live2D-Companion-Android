package com.catkiss.senlive2dcompanion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Transparent Sen portrait layer that can be copied into the AI companion's middle stage.
 *
 * <p>This class owns OpenGL configuration, render-thread dispatch and Cubism resource lifetime.
 * The current Android app is only a test harness around this view. A host should call
 * {@link #onHostResume()}, {@link #onHostPause()} and {@link #release()} from the matching owner
 * lifecycle. One released instance must not be reused.</p>
 */
public final class SenCompanionView extends GLSurfaceView implements SenCompanionController {
    public interface Listener {
        // Renderer callbacks arrive on the GL thread. UI hosts must marshal them to their UI
        // thread; the included MainActivity demonstrates that boundary.
        void onStatus(String status);
        void onReady(String detail);
        void onError(Throwable error);
        void onMotionDiagnosticStep(String label, int index, int total);
        void onMotionDiagnosticComplete(String report);
    }

    public static final String DEFAULT_PROFILE_ASSET = "sen-default-profile-v1.json";

    private static final Listener NO_OP_LISTENER = new Listener() {
        @Override public void onStatus(String status) { }
        @Override public void onReady(String detail) { }
        @Override public void onError(Throwable error) { }
        @Override public void onMotionDiagnosticStep(String label, int index, int total) { }
        @Override public void onMotionDiagnosticComplete(String report) { }
    };

    private final SenRenderer renderer;
    private volatile Listener listener = NO_OP_LISTENER;
    private volatile boolean released;

    public SenCompanionView(Context context) {
        this(context, null);
    }

    public SenCompanionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.TRANSPARENT);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        renderer = new SenRenderer(context, new SenRenderer.Listener() {
            @Override public void onStatus(String status) {
                listener.onStatus(status);
            }

            @Override public void onReady(String detail) {
                listener.onReady(detail);
            }

            @Override public void onError(Throwable error) {
                listener.onError(error);
            }

            @Override public void onMotionDiagnosticStep(String label, int index, int total) {
                listener.onMotionDiagnosticStep(label, index, total);
            }

            @Override public void onMotionDiagnosticComplete(String report) {
                listener.onMotionDiagnosticComplete(report);
            }
        });
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? NO_OP_LISTENER : listener;
    }

    /** Loads the imported model with Sen's bundled baseline and a built-in outfit ID. */
    public void loadModel(File modelFile, boolean autoIdle, String outfitId) {
        loadModel(modelFile, Collections.emptyList(), autoIdle,
                SenMotionMode.ORIGINAL.id, outfitId);
    }

    /** Loads the model with one of the isolated autonomous motion modes. */
    public void loadModel(File modelFile, boolean autoIdle, String motionModeId,
                          String outfitId) {
        loadModel(modelFile, Collections.emptyList(), autoIdle, motionModeId, outfitId);
    }

    public void loadModel(File modelFile, boolean autoIdle, String motionModeId,
                          float evBodyFollowStrength, String outfitId) {
        loadModel(modelFile, Collections.emptyList(), autoIdle, motionModeId,
                evBodyFollowStrength, outfitId);
    }

    /** Loads the imported model and optionally enables named ZIP expressions at startup. */
    public void loadModel(File modelFile, List<String> startupExpressions,
                          boolean autoIdle, String outfitId) {
        loadModel(modelFile, startupExpressions, autoIdle, SenMotionMode.ORIGINAL.id, outfitId);
    }

    public void loadModel(File modelFile, List<String> startupExpressions,
                          boolean autoIdle, String motionModeId, String outfitId) {
        loadModel(modelFile, startupExpressions, autoIdle, motionModeId,
                SenRenderOptions.DEFAULT_EV_BODY_FOLLOW_STRENGTH, outfitId);
    }

    public void loadModel(File modelFile, List<String> startupExpressions,
                          boolean autoIdle, String motionModeId,
                          float evBodyFollowStrength, String outfitId) {
        if (released) {
            listener.onError(new IllegalStateException("SenCompanionView 已释放，不能再次加载"));
            return;
        }
        if (modelFile == null || !modelFile.isFile()) {
            listener.onError(new IOException("Sen model3 文件不存在"));
            return;
        }
        final SenVtsProfile profile;
        try {
            profile = loadBundledProfile(getContext());
        } catch (IOException error) {
            listener.onError(error);
            return;
        }
        SenOutfitPresets.Preset outfit = SenOutfitPresets.fromId(outfitId);
        List<String> expressions = startupExpressions == null
                ? Collections.emptyList() : startupExpressions;
        queueRenderer(() -> renderer.requestModel(
                modelFile, expressions, outfit.appearance, profile,
                new SenRenderOptions(autoIdle, SenMotionMode.fromId(motionModeId),
                        evBodyFollowStrength), outfit));
    }

    @Override
    public void setEmotion(String emotionId) {
        queueRenderer(() -> renderer.selectEmotion(emotionId));
    }

    @Override
    public void playAction(String actionId) {
        queueRenderer(() -> renderer.playAction(actionId));
    }

    @Override
    public void setSpeechAmplitude(float amplitude) {
        queueRenderer(() -> renderer.setLipSyncValue(amplitude));
    }

    @Override
    public void setLookTarget(boolean active, float normalizedX, float normalizedY) {
        queueRenderer(() -> renderer.setTouchTarget(active, normalizedX, normalizedY));
    }

    @Override
    public void setAutoIdle(boolean enabled) {
        queueRenderer(() -> renderer.setAutoIdle(enabled));
    }

    public void setMotionMode(String motionModeId) {
        SenMotionMode mode = SenMotionMode.fromId(motionModeId);
        queueRenderer(() -> renderer.setMotionMode(mode));
    }

    public void setEvBodyFollowStrength(float strength) {
        queueRenderer(() -> renderer.setEvBodyFollowStrength(strength));
    }

    public void startMotionDiagnostic(String motionModeId) {
        SenMotionMode mode = SenMotionMode.fromId(motionModeId);
        queueRenderer(() -> renderer.startMotionDiagnostic(mode));
    }

    public void stopMotionDiagnostic() {
        queueRenderer(renderer::stopMotionDiagnostic);
    }

    @Override
    public void setVisible(boolean visible) {
        setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    public void setOutfit(String outfitId) {
        SenOutfitPresets.Preset outfit = SenOutfitPresets.fromId(outfitId);
        queueRenderer(() -> renderer.selectOutfit(outfit));
    }

    public void setStageTransform(float scale, float translateX, float translateY) {
        if (!released) renderer.setStageTransform(scale, translateX, translateY);
    }

    public boolean screenToModelNormalized(float screenX, float screenY, float[] result) {
        return !released && renderer.screenToModelNormalized(screenX, screenY, result);
    }

    public void onHostResume() {
        if (!released) onResume();
    }

    public void onHostPause() {
        if (!released) {
            queueRenderer(renderer::releaseHeadPat);
            onPause();
        }
    }

    @Override
    public void release() {
        if (released) return;
        released = true;
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        // GLSurfaceView executes queued events on its GL thread even while paused. This keeps
        // texture deletion and Cubism renderer teardown on the context-owning thread.
        queueEvent(renderer::release);
        listener = NO_OP_LISTENER;
    }

    void setTouchFollowEnabled(boolean enabled) {
        queueRenderer(() -> renderer.setTouchFollowEnabled(enabled));
    }

    void triggerEarTwitch() {
        queueRenderer(renderer::triggerEarTwitch);
    }

    void triggerHeadPat(boolean confused) {
        queueRenderer(() -> renderer.triggerHeadPat(confused));
    }

    void releaseHeadPat() {
        queueRenderer(renderer::releaseHeadPat);
    }

    void applyExpression(String name) {
        queueRenderer(() -> renderer.applyExpression(name));
    }

    void resetNativePresets() {
        queueRenderer(renderer::resetNativePresets);
    }

    void playNativeMotion(String name) {
        queueRenderer(() -> renderer.playNativeMotion(name));
    }

    void stopNativeMotion() {
        queueRenderer(renderer::stopNativeMotion);
    }

    private void queueRenderer(Runnable command) {
        if (!released) queueEvent(command);
    }

    private static SenVtsProfile loadBundledProfile(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(DEFAULT_PROFILE_ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return SenVtsProfile.parse(result.toString());
        }
    }
}
