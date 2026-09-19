package com.catkiss.senlive2dcompanion;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity implements SenRenderer.Listener {
    private static final String PREFS = "sen_live2d_renderer_test";
    private static final int HEAD_ZONE_CONFIRMED_PRESET_VERSION = 3;
    private static final String DEFAULT_PROFILE_ASSET = "sen-default-profile-v1.json";
    private static final float DEFAULT_HEAD_ZONE_LEFT = .4927f;
    private static final float DEFAULT_HEAD_ZONE_TOP = .0482f;
    private static final float DEFAULT_HEAD_ZONE_RIGHT = .7095f;
    private static final float DEFAULT_HEAD_ZONE_BOTTOM = .1088f;
    private static final long MAX_EXTRACTED_BYTES = 1_500_000_000L;
    private static final int MAX_ZIP_ENTRIES = 8_000;
    // One legacy user-authored expression was accidentally treated as model content. This
    // migration-only filename must never be exposed in diagnostics, labels or startup state.
    private static final String REMOVED_USER_EXPRESSION_FILE =
            "xiao" + "jingyu.exp3.json";
    private static final int EXPRESSION_CLEANUP_VERSION = 2;
    private static final String RESET_NATIVE_PRESETS = "__reset_native_presets__";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> expressionNames = new ArrayList<>();

    private SharedPreferences prefs;
    private File modelRoot;
    private File importRoot;
    private GLSurfaceView glSurfaceView;
    private SenRenderer renderer;
    private TextView statusText;
    private TextView summaryText;
    private LinearLayout expressionArea;
    private FrameLayout loadingOverlay;
    private TextView loadingText;
    private SenVtsProfile defaultProfile;
    private boolean adjustmentEnabled;
    private float stageScale = 1.0f;
    private float stageTranslateX;
    private float stageTranslateY;
    private float lastTouchX;
    private float lastTouchY;
    private ScaleGestureDetector scaleGestureDetector;
    private Button adjustmentButton;
    private Button autoIdleButton;
    private Button touchFollowButton;
    private boolean autoIdleEnabled;
    private boolean touchFollowEnabled = true;
    private int interactionPointerId = -1;
    private boolean headPatCandidate;
    private boolean headPatTriggered;
    private float headPatLastX;
    private float headPatLastY;
    private float headPatTravel;
    private long headPatStartedAt;
    private float headZoneLeft;
    private float headZoneTop;
    private float headZoneRight;
    private float headZoneBottom;
    private boolean headZoneCalibrationMode;
    private float headZoneFirstX = Float.NaN;
    private float headZoneFirstY = Float.NaN;
    private final float[] headZonePoint = new float[2];
    private TextView headZoneStatus;
    private TextView ttsLipSyncStatus;
    private SenSystemTtsLipSync systemTtsLipSync;
    private SenOutfitPresets.Preset selectedOutfit;
    private long nativeLoadStartedAt;
    private String rendererDetail = "";

    private final ActivityResultLauncher<String[]> modelZipPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onModelZipPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        defaultProfile = loadBundledProfile();
        prefs.edit()
                .remove("mask_mode").remove("mask_size")
                .remove("ear_speed_percent").remove("ear_amplitude_percent")
                .remove("ahoge_root_follow_percent").remove("ahoge_root_rotation_percent")
                .remove("ahoge_local_motion_percent").remove("ahoge_native_passthrough")
                .remove("ahoge_anchor_json").remove("ahoge_scale_percent")
                .remove("ahoge_length_percent").remove("ahoge_width_percent")
                .remove("ahoge_rotation_degrees").remove("ahoge_offset_x")
                .remove("ahoge_offset_y").remove("tail_mirrored")
                .apply();
        if (prefs.getInt("head_zone_confirmed_preset_version", 0)
                < HEAD_ZONE_CONFIRMED_PRESET_VERSION) {
            prefs.edit()
                    .putInt("head_zone_confirmed_preset_version",
                            HEAD_ZONE_CONFIRMED_PRESET_VERSION)
                    .putFloat("head_zone_left", DEFAULT_HEAD_ZONE_LEFT)
                    .putFloat("head_zone_top", DEFAULT_HEAD_ZONE_TOP)
                    .putFloat("head_zone_right", DEFAULT_HEAD_ZONE_RIGHT)
                    .putFloat("head_zone_bottom", DEFAULT_HEAD_ZONE_BOTTOM)
                    .apply();
        }
        headZoneLeft = clamp01(prefs.getFloat("head_zone_left", DEFAULT_HEAD_ZONE_LEFT));
        headZoneTop = clamp01(prefs.getFloat("head_zone_top", DEFAULT_HEAD_ZONE_TOP));
        headZoneRight = clamp01(prefs.getFloat("head_zone_right", DEFAULT_HEAD_ZONE_RIGHT));
        headZoneBottom = clamp01(prefs.getFloat("head_zone_bottom", DEFAULT_HEAD_ZONE_BOTTOM));
        if (headZoneRight - headZoneLeft < .08f || headZoneBottom - headZoneTop < .06f) {
            resetHeadZoneValues();
        }
        autoIdleEnabled = prefs.getBoolean("auto_idle_enabled", false);
        touchFollowEnabled = prefs.getBoolean("touch_follow_enabled", true);
        selectedOutfit = SenOutfitPresets.fromId(
                prefs.getString("outfit_preset", SenOutfitPresets.MAID.id));
        modelRoot = new File(getFilesDir(), "sen-live2d-model");
        importRoot = new File(getFilesDir(), "sen-import-temp");
        //noinspection ResultOfMethodCallIgnored
        modelRoot.mkdirs();
        purgeRemovedUserExpressionOnUpgrade();
        restoreMetadata();
        buildUi();
        loadNativeModel();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(23, 17, 33));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(7), dp(4), dp(7), dp(4));
        toolbar.setBackgroundColor(Color.rgb(31, 24, 44));

        Button importButton = compactButton("导入ZIP");
        importButton.setOnClickListener(v -> modelZipPicker.launch(
                new String[]{"application/zip", "application/octet-stream"}));
        toolbar.addView(importButton);

        Button reloadButton = compactButton("重载");
        reloadButton.setOnClickListener(v -> loadNativeModel());
        toolbar.addView(reloadButton);

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(235, 224, 246));
        statusText.setTextSize(10);
        statusText.setSingleLine(true);
        statusText.setText("v0.5.14 · 无衣底图上衣修正版");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.setMarginStart(dp(5));
        toolbar.addView(statusText, statusParams);
        page.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        FrameLayout stage = new FrameLayout(this);
        glSurfaceView = new GLSurfaceView(this);
        glSurfaceView.setBackgroundColor(Color.TRANSPARENT);
        glSurfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        renderer = new SenRenderer(this, this);
        renderer.setTouchFollowEnabled(touchFollowEnabled);
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glSurfaceView.setPreserveEGLContextOnPause(true);
        installStageAdjustmentGestures();
        stage.addView(glSurfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));

        ScrollView panelScroll = new ScrollView(this);
        panelScroll.setFillViewport(true);
        panelScroll.setBackgroundColor(Color.rgb(30, 23, 43));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(12));
        panelScroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText("Sen · Cubism 5 动态动作测试");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(15);
        panel.addView(heading);

        summaryText = new TextView(this);
        summaryText.setTextColor(Color.rgb(205, 190, 220));
        summaryText.setTextSize(11);
        summaryText.setPadding(0, dp(3), 0, dp(6));
        panel.addView(summaryText);

        TextView memoryNote = new TextView(this);
        memoryNote.setText("已内置正常待机581项参数。动态顺序：默认参数 → 情绪/动作 → 原生物理 → 尾巴镜像与固化呆毛；透明渲染面可直接替换AI伴侣立绘层。");
        memoryNote.setTextColor(Color.rgb(186, 164, 204));
        memoryNote.setTextSize(10);
        memoryNote.setPadding(dp(3), dp(3), 0, dp(3));
        panel.addView(memoryNote);

        TextView outfitHeading = new TextView(this);
        outfitHeading.setText("内置服装预设（按钮直接切换，不再选择服装JSON）");
        outfitHeading.setTextColor(Color.rgb(238, 207, 255));
        outfitHeading.setTextSize(12);
        outfitHeading.setPadding(0, dp(6), 0, dp(3));
        panel.addView(outfitHeading);

        LinearLayout outfitRow = new LinearLayout(this);
        outfitRow.setOrientation(LinearLayout.HORIZONTAL);
        for (SenOutfitPresets.Preset preset : SenOutfitPresets.ALL) {
            Button button = panelButton(preset.displayName);
            button.setOnClickListener(v -> selectOutfitPreset(preset));
            outfitRow.addView(button, weightedButtonParams());
        }
        panel.addView(outfitRow);

        TextView outfitNote = new TextView(this);
        outfitNote.setText("女仆装/白衬衫/脱共用配色，兔女郎使用独立配色；脱以女仆装为底，关闭女仆附件，使用固定版型的Top 0和原生Bottom 4。四套均固定ArtMesh387为#444573。");
        outfitNote.setTextColor(Color.rgb(186, 164, 204));
        outfitNote.setTextSize(10);
        outfitNote.setPadding(dp(3), dp(2), 0, dp(3));
        panel.addView(outfitNote);

        LinearLayout adjustmentControls = new LinearLayout(this);
        adjustmentControls.setOrientation(LinearLayout.HORIZONTAL);
        adjustmentButton = panelButton("调整模型：关闭");
        adjustmentButton.setOnClickListener(v -> {
            adjustmentEnabled = !adjustmentEnabled;
            adjustmentButton.setText(adjustmentEnabled ? "调整模型：开启" : "调整模型：关闭");
            toastLong(adjustmentEnabled ? "单指拖动，双指缩放" : "模型位置已锁定");
        });
        adjustmentControls.addView(adjustmentButton, weightedButtonParams());
        Button resetTransformButton = panelButton("还原位置与大小");
        resetTransformButton.setOnClickListener(v -> resetStageTransform());
        adjustmentControls.addView(resetTransformButton, weightedButtonParams());
        panel.addView(adjustmentControls);

        TextView earNotice = new TextView(this);
        earNotice.setText("兔耳双脉冲固定为速度135%、幅度100%，继续走九轴→原生物理，只取三项兔耳输出；自主待机会低频触发。");
        earNotice.setTextColor(Color.rgb(220, 198, 238));
        earNotice.setTextSize(10);
        earNotice.setPadding(dp(3), dp(5), 0, dp(2));
        panel.addView(earNotice);

        Button earTwitchButton = panelButton("测试：猫耳快速抖动两次");
        earTwitchButton.setOnClickListener(v -> glSurfaceView.queueEvent(
                () -> renderer.triggerEarTwitch()));
        panel.addView(earTwitchButton);

        TextView tailHeading = new TextView(this);
        tailHeading.setText("尾巴：固定右侧（中心线镜像；身体中心不变）");
        tailHeading.setTextColor(Color.rgb(238, 207, 255));
        tailHeading.setTextSize(11);
        tailHeading.setPadding(dp(3), dp(5), 0, 0);
        panel.addView(tailHeading);

        TextView interactionHeading = new TextView(this);
        interactionHeading.setText("触屏互动（迷梦极限跟随路线；头部区域来回抚摸可触发摸头）");
        interactionHeading.setTextColor(Color.rgb(238, 207, 255));
        interactionHeading.setTextSize(12);
        interactionHeading.setPadding(0, dp(7), 0, dp(3));
        panel.addView(interactionHeading);

        touchFollowButton = panelButton(touchFollowEnabled
                ? "极限触屏跟随：开启" : "极限触屏跟随：关闭");
        touchFollowButton.setOnClickListener(v -> {
            touchFollowEnabled = !touchFollowEnabled;
            prefs.edit().putBoolean("touch_follow_enabled", touchFollowEnabled).apply();
            glSurfaceView.queueEvent(() -> renderer.setTouchFollowEnabled(touchFollowEnabled));
            updateCustomizationControls();
        });
        panel.addView(touchFollowButton);

        LinearLayout headZoneRow = new LinearLayout(this);
        headZoneRow.setOrientation(LinearLayout.HORIZONTAL);
        Button headZoneButton = panelButton("框选摸头范围");
        headZoneButton.setOnClickListener(v -> beginHeadZoneCalibration());
        headZoneRow.addView(headZoneButton, weightedButtonParams());
        Button resetHeadZoneButton = panelButton("还原确认范围");
        resetHeadZoneButton.setOnClickListener(v -> resetHeadZone());
        headZoneRow.addView(resetHeadZoneButton, weightedButtonParams());
        panel.addView(headZoneRow);
        headZoneStatus = adjustmentStatusText();
        panel.addView(headZoneStatus);

        TextView dynamicHeading = new TextView(this);
        dynamicHeading.setText("动态基础（原生物理常开；自主待机使用持续柔风底座、自然眨眼和随机动作池）");
        dynamicHeading.setTextColor(Color.rgb(238, 207, 255));
        dynamicHeading.setTextSize(12);
        dynamicHeading.setPadding(0, dp(7), 0, dp(3));
        panel.addView(dynamicHeading);

        autoIdleButton = panelButton(autoIdleEnabled
                ? "自主待机：开启（柔风+眨眼+随机动作）" : "自主待机：关闭");
        autoIdleButton.setOnClickListener(v -> {
            autoIdleEnabled = !autoIdleEnabled;
            prefs.edit().putBoolean("auto_idle_enabled", autoIdleEnabled).apply();
            glSurfaceView.queueEvent(() -> renderer.setAutoIdle(autoIdleEnabled));
            updateCustomizationControls();
        });
        panel.addView(autoIdleButton);

        TextView emotionHeading = new TextView(this);
        emotionHeading.setText("AI伴侣情绪（20个语义入口；本轮启用原生眼效和完整嘴型）");
        emotionHeading.setTextColor(Color.rgb(238, 207, 255));
        emotionHeading.setTextSize(12);
        emotionHeading.setPadding(0, dp(7), 0, dp(3));
        panel.addView(emotionHeading);
        String[] emotionLabels = {
                "普通", "开心", "兴奋", "喜爱", "害羞",
                "慌张", "紧张", "担心", "疑惑", "无奈",
                "害怕", "生气", "伤心", "嫌弃", "认真",
                "惊讶", "自信", "调皮", "羞愧", "平静"
        };
        addPerformanceGrid(panel, SenPerformanceEngine.EMOTIONS, emotionLabels, true);

        TextView ttsHeading = new TextView(this);
        ttsHeading.setText("系统语音口型测试（4句循环；实际PCM音量优先）");
        ttsHeading.setTextColor(Color.rgb(238, 207, 255));
        ttsHeading.setTextSize(12);
        ttsHeading.setPadding(0, dp(7), 0, dp(3));
        panel.addView(ttsHeading);

        Button ttsButton = panelButton("播放下一句系统语音");
        ttsButton.setOnClickListener(v -> {
            if (systemTtsLipSync != null) systemTtsLipSync.speakNext();
        });
        panel.addView(ttsButton);
        ttsLipSyncStatus = adjustmentStatusText();
        ttsLipSyncStatus.setText("系统TTS等待初始化…");
        panel.addView(ttsLipSyncStatus);

        TextView actionHeading = new TextView(this);
        actionHeading.setText("程序动作手动测试（19项；7项已转入自主待机）");
        actionHeading.setTextColor(Color.rgb(238, 207, 255));
        actionHeading.setTextSize(12);
        actionHeading.setPadding(0, dp(7), 0, dp(3));
        panel.addView(actionHeading);
        String[] manualActionLabels = {
                "点头", "摇头", "歪头", "前倾", "后仰",
                "惊讶眨眼", "叹气", "撅嘴", "兴奋弹跳", "倾听",
                "轻摆", "低头抬头", "小点头", "侧看", "重心切换",
                "轻靠", "慢眨眼",
                "摸头常规", "摸头疑惑彩蛋"
        };
        addPerformanceGrid(panel, SenPerformanceEngine.MANUAL_TEST_ACTIONS,
                manualActionLabels, false);

        TextView actionNote = adjustmentStatusText();
        actionNote.setText("环顾、待机歪头、叹气下沉、柔风摆动、明显风摆、展示级大摆和视频式环绕仅由自主待机调用；动作源码仍完整保留。");
        panel.addView(actionNote);

        TextView expressionHeading = new TextView(this);
        expressionHeading.setText("Sen ZIP 原生预设/道具（再次点击关闭；手柄/键鼠/麦克风三选一）");
        expressionHeading.setTextColor(Color.rgb(238, 207, 255));
        expressionHeading.setTextSize(12);
        expressionHeading.setPadding(0, dp(5), 0, dp(3));
        panel.addView(expressionHeading);

        expressionArea = new LinearLayout(this);
        expressionArea.setOrientation(LinearLayout.VERTICAL);
        panel.addView(expressionArea);
        rebuildExpressionButtons();

        page.addView(panelScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadingOverlay = buildLoadingOverlay();
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        initializeSystemTtsLipSync();
        updateCustomizationControls();
        updateSummary();
    }

    private void initializeSystemTtsLipSync() {
        systemTtsLipSync = new SenSystemTtsLipSync(this,
                new SenSystemTtsLipSync.Listener() {
                    @Override public void onStatus(String status) {
                        runOnUiThread(() -> {
                            if (ttsLipSyncStatus != null) ttsLipSyncStatus.setText(status);
                        });
                    }

                    @Override public void onMouthValue(float value) {
                        GLSurfaceView surface = glSurfaceView;
                        SenRenderer activeRenderer = renderer;
                        if (surface == null || activeRenderer == null) return;
                        surface.queueEvent(() -> activeRenderer.setLipSyncValue(value));
                    }
                });
    }

    private void installStageAdjustmentGestures() {
        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (!adjustmentEnabled) return false;
                        float oldScale = stageScale;
                        float newScale = Math.max(0.35f,
                                Math.min(6.0f, oldScale * detector.getScaleFactor()));
                        if (Math.abs(newScale - oldScale) < 0.0001f) return true;

                        int width = Math.max(1, glSurfaceView.getWidth());
                        int height = Math.max(1, glSurfaceView.getHeight());
                        float focusX = detector.getFocusX() * 2.0f / width - 1.0f;
                        float focusY = 1.0f - detector.getFocusY() * 2.0f / height;
                        float ratio = newScale / oldScale;
                        stageTranslateX = focusX - (focusX - stageTranslateX) * ratio;
                        stageTranslateY = focusY - (focusY - stageTranslateY) * ratio;
                        stageScale = newScale;
                        clampStageTranslation();
                        applyStageTransform();
                        return true;
                    }
                });
        glSurfaceView.setOnTouchListener((view, event) -> {
            if (headZoneCalibrationMode && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                handleHeadZoneCalibration(view, event.getX(), event.getY());
                return true;
            }
            if (!adjustmentEnabled) return handleStageInteraction(view, event);
            scaleGestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    int lifted = event.getActionIndex();
                    int remaining = lifted == 0 ? 1 : 0;
                    if (remaining < event.getPointerCount()) {
                        lastTouchX = event.getX(remaining);
                        lastTouchY = event.getY(remaining);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        int width = Math.max(1, view.getWidth());
                        int height = Math.max(1, view.getHeight());
                        stageTranslateX += dx * 2.0f / width;
                        stageTranslateY -= dy * 2.0f / height;
                        clampStageTranslation();
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        applyStageTransform();
                    }
                    break;
                default:
                    break;
            }
            return true;
        });
    }

    private boolean handleStageInteraction(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            interactionPointerId = event.getPointerId(0);
            headPatLastX = event.getX();
            headPatLastY = event.getY();
            headPatTravel = 0.0f;
            headPatStartedAt = SystemClock.elapsedRealtime();
            headPatTriggered = false;
            headPatCandidate = screenToModelPoint(
                    view, headPatLastX, headPatLastY, headZonePoint)
                    && isInHeadZone(headZonePoint[0], headZonePoint[1], 0.0f);
            queueTouchTarget(view, true, headPatLastX, headPatLastY);
            return true;
        }
        if (interactionPointerId < 0) return false;
        int pointerIndex = event.findPointerIndex(interactionPointerId);
        if (pointerIndex < 0) pointerIndex = 0;
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        if (action == MotionEvent.ACTION_MOVE) {
            queueTouchTarget(view, true, x, y);
            float dx = x - headPatLastX;
            float dy = y - headPatLastY;
            headPatTravel += (float) Math.sqrt(dx * dx + dy * dy);
            headPatLastX = x;
            headPatLastY = y;
            if (!screenToModelPoint(view, x, y, headZonePoint)
                    || !isInHeadZone(headZonePoint[0], headZonePoint[1], .08f)) {
                headPatCandidate = false;
            }
            long duration = SystemClock.elapsedRealtime() - headPatStartedAt;
            float threshold = Math.max(dp(34), view.getWidth() * .075f);
            if (headPatCandidate && !headPatTriggered
                    && duration >= 120L && headPatTravel >= threshold) {
                headPatTriggered = true;
                boolean confused = Math.random() < .10;
                glSurfaceView.queueEvent(() -> renderer.triggerHeadPat(confused));
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_UP) {
            queueTouchTarget(view, false, x, y);
            glSurfaceView.queueEvent(renderer::releaseHeadPat);
            interactionPointerId = -1;
            headPatCandidate = false;
            headPatTriggered = false;
            return true;
        }
        return true;
    }

    private void queueTouchTarget(View view, boolean active, float x, float y) {
        float normalizedX = x * 2.0f / Math.max(1, view.getWidth()) - 1.0f;
        float normalizedY = 1.0f - y * 2.0f / Math.max(1, view.getHeight());
        glSurfaceView.queueEvent(() -> renderer.setTouchTarget(
                active, normalizedX, normalizedY));
    }

    private void applyStageTransform() {
        if (renderer != null) {
            renderer.setStageTransform(stageScale, stageTranslateX, stageTranslateY);
        }
    }

    private void clampStageTranslation() {
        float limit = 0.9f + 0.5f * stageScale;
        stageTranslateX = Math.max(-limit, Math.min(limit, stageTranslateX));
        stageTranslateY = Math.max(-limit, Math.min(limit, stageTranslateY));
    }

    private void selectOutfitPreset(SenOutfitPresets.Preset preset) {
        if (preset == null) return;
        selectedOutfit = preset;
        prefs.edit().putString("outfit_preset", preset.id).apply();
        glSurfaceView.queueEvent(() -> renderer.selectOutfit(preset));
        updateSummary();
        toastLong("已切换服装：" + preset.displayName);
    }

    private void updateCustomizationControls() {
        if (autoIdleButton != null) {
            autoIdleButton.setText(autoIdleEnabled
                    ? "自主待机：开启（柔风+眨眼+随机动作）" : "自主待机：关闭");
        }
        if (touchFollowButton != null) {
            touchFollowButton.setText(touchFollowEnabled
                    ? "极限触屏跟随：开启" : "极限触屏跟随：关闭");
        }
        updateHeadZoneStatus();
    }

    private void beginHeadZoneCalibration() {
        headZoneCalibrationMode = true;
        headZoneFirstX = Float.NaN;
        headZoneFirstY = Float.NaN;
        adjustmentEnabled = false;
        if (adjustmentButton != null) adjustmentButton.setText("调整模型：关闭");
        toastLong("请在上方舞台依次点击摸头矩形的两个对角；完成后会显示 L/T/R/B 数值");
    }

    private void handleHeadZoneCalibration(View view, float x, float y) {
        if (!screenToModelPoint(view, x, y, headZonePoint)) {
            toastLong("模型边界尚未就绪，请等待模型显示后重新框选");
            return;
        }
        float nx = clamp01(headZonePoint[0]);
        float ny = clamp01(headZonePoint[1]);
        if (Float.isNaN(headZoneFirstX)) {
            headZoneFirstX = nx;
            headZoneFirstY = ny;
            toastLong(String.format(java.util.Locale.ROOT,
                    "第一点 X=%.4f / Y=%.4f；请点击矩形另一对角", nx, ny));
            return;
        }
        float left = Math.min(headZoneFirstX, nx);
        float top = Math.min(headZoneFirstY, ny);
        float right = Math.max(headZoneFirstX, nx);
        float bottom = Math.max(headZoneFirstY, ny);
        if (right - left < .08f || bottom - top < .06f) {
            headZoneFirstX = Float.NaN;
            headZoneFirstY = Float.NaN;
            toastLong("框选范围太小（宽至少8%、高至少6%）；请重新点击两个对角");
            return;
        }
        headZoneLeft = left;
        headZoneTop = top;
        headZoneRight = right;
        headZoneBottom = bottom;
        headZoneCalibrationMode = false;
        headZoneFirstX = Float.NaN;
        headZoneFirstY = Float.NaN;
        persistHeadZone();
        updateHeadZoneStatus();
        toastLong(String.format(java.util.Locale.ROOT,
                "摸头范围已保存：L %.4f / T %.4f / R %.4f / B %.4f",
                headZoneLeft, headZoneTop, headZoneRight, headZoneBottom));
    }

    private void resetHeadZone() {
        headZoneCalibrationMode = false;
        headZoneFirstX = Float.NaN;
        headZoneFirstY = Float.NaN;
        resetHeadZoneValues();
        persistHeadZone();
        updateHeadZoneStatus();
        toastLong("摸头范围已恢复模型局部确认值 0.4927/0.0482/0.7095/0.1088");
    }

    private void resetHeadZoneValues() {
        headZoneLeft = DEFAULT_HEAD_ZONE_LEFT;
        headZoneTop = DEFAULT_HEAD_ZONE_TOP;
        headZoneRight = DEFAULT_HEAD_ZONE_RIGHT;
        headZoneBottom = DEFAULT_HEAD_ZONE_BOTTOM;
    }

    private void persistHeadZone() {
        prefs.edit()
                .putFloat("head_zone_left", headZoneLeft)
                .putFloat("head_zone_top", headZoneTop)
                .putFloat("head_zone_right", headZoneRight)
                .putFloat("head_zone_bottom", headZoneBottom)
                .apply();
    }

    private void updateHeadZoneStatus() {
        if (headZoneStatus == null) return;
        headZoneStatus.setText(String.format(java.util.Locale.ROOT,
                "摸头范围（模型局部归一化，随模型移动/缩放）：L %.4f / T %.4f / R %.4f / B %.4f",
                headZoneLeft, headZoneTop, headZoneRight, headZoneBottom));
    }

    private boolean screenToModelPoint(View view, float x, float y, float[] result) {
        if (renderer == null) return false;
        float screenX = x / Math.max(1, view.getWidth());
        float screenY = y / Math.max(1, view.getHeight());
        return renderer.screenToModelNormalized(screenX, screenY, result);
    }

    private boolean isInHeadZone(float x, float y, float margin) {
        return x >= Math.max(0.0f, headZoneLeft - margin)
                && x <= Math.min(1.0f, headZoneRight + margin)
                && y >= Math.max(0.0f, headZoneTop - margin)
                && y <= Math.min(1.0f, headZoneBottom + margin);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private void resetStageTransform() {
        stageScale = 1.0f;
        stageTranslateX = 0.0f;
        stageTranslateY = 0.0f;
        applyStageTransform();
        toastLong("模型位置与大小已还原");
    }

    private void onModelZipPicked(Uri uri) {
        if (uri == null) return;
        showLoading("准备导入 Sen 模型…");
        executor.execute(() -> {
            try {
                deleteRecursivelyIfExists(importRoot);
                //noinspection ResultOfMethodCallIgnored
                importRoot.mkdirs();
                postLoading("正在解压模型 ZIP…\n2K 模型较大，请稍候");
                unzipSecure(uri, importRoot);

                postLoading("正在识别 model3 与 ZIP 原生预设…");
                File modelFile = findFirst(importRoot, ".model3.json");
                if (modelFile == null) throw new IOException("ZIP 中没有找到 .model3.json");
                removeExcludedExpressionFiles(modelFile.getParentFile());
                List<String> detectedExpressions = registerExpressions(modelFile);
                String modelRelative = relativePath(importRoot, modelFile);
                String diagnostics = buildDiagnostics(modelFile, detectedExpressions);

                postLoading("正在替换 App 私有目录中的旧模型…");
                deleteChildren(modelRoot);
                moveChildren(importRoot, modelRoot);
                prefs.edit()
                        .putString("model_path", modelRelative)
                        .putString("expressions", new JSONArray(detectedExpressions).toString())
                        .putString("diagnostics", diagnostics)
                        .remove("saved_vts_expressions")
                        .apply();

                runOnUiThread(() -> {
                    expressionNames.clear();
                    expressionNames.addAll(detectedExpressions);
                    rebuildExpressionButtons();
                    updateSummary();
                    toastLong("模型导入成功：ZIP 原生预设 "
                            + detectedExpressions.size() + " 个");
                    loadNativeModel();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    hideLoading();
                    String message = "模型导入失败：" + readableError(error);
                    setStatus(message);
                    toastLong(message);
                });
            } finally {
                try {
                    deleteRecursivelyIfExists(importRoot);
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void loadNativeModel() {
        String relative = prefs.getString("model_path", "");
        File modelFile = new File(modelRoot, relative);
        if (relative.isBlank() || !modelFile.isFile()) {
            hideLoading();
            setStatus("请先导入 Sen 模型 ZIP");
            return;
        }
        nativeLoadStartedAt = SystemClock.elapsedRealtime();
        showLoading("正在启动 Android 原生 Cubism 5…");
        List<String> startup = new ArrayList<>();
        SenVtsAppearance selectedAppearance = selectedOutfit.appearance;
        SenVtsProfile selectedFrozenProfile = defaultProfile;
        SenRenderOptions selectedOptions = new SenRenderOptions(autoIdleEnabled);
        rendererDetail = "";
        updateSummary();
        glSurfaceView.queueEvent(() -> renderer.requestModel(
                modelFile, startup, selectedAppearance, selectedFrozenProfile, selectedOptions,
                selectedOutfit));
    }

    private List<String> registerExpressions(File modelFile) throws Exception {
        File modelDirectory = modelFile.getParentFile();
        if (modelDirectory == null) throw new IOException("模型目录无效");
        List<File> expressionFiles = new ArrayList<>();
        collectFiles(modelDirectory, ".exp3.json", expressionFiles);
        expressionFiles.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        JSONArray expressions = new JSONArray();
        List<String> names = new ArrayList<>();
        for (File expression : expressionFiles) {
            if (isExcludedExpressionFile(expression)) {
                if (expression.isFile() && !expression.delete()) {
                    throw new IOException("无法移除已废弃的原生预设");
                }
                continue;
            }
            String name = expression.getName().replaceFirst("\\.exp3\\.json$", "");
            names.add(name);
            expressions.put(new JSONObject()
                    .put("Name", name)
                    .put("File", relativePath(modelDirectory, expression)));
        }

        JSONObject modelJson = new JSONObject(readUtf8File(modelFile));
        JSONObject references = modelJson.optJSONObject("FileReferences");
        if (references == null) {
            references = new JSONObject();
            modelJson.put("FileReferences", references);
        }
        references.put("Expressions", expressions);
        writeUtf8File(modelFile, modelJson.toString(2));
        return names;
    }

    private String buildDiagnostics(File modelFile, List<String> expressions) {
        try {
            JSONObject json = new JSONObject(readUtf8File(modelFile));
            JSONObject refs = json.optJSONObject("FileReferences");
            JSONArray textures = refs == null ? null : refs.optJSONArray("Textures");
            File moc = refs == null ? null : new File(modelFile.getParentFile(), refs.optString("Moc", ""));
            return "Cubism model3 v" + json.optInt("Version", 0)
                    + " · 贴图 " + (textures == null ? 0 : textures.length()) + " 张"
                    + " · moc3 " + (moc != null && moc.isFile() ? formatMiB(moc.length()) : "未知")
                    + "\nZIP 原生预设 " + expressions.size() + " 个";
        } catch (Exception ignored) {
            return "模型已导入 · ZIP 原生预设 " + expressions.size() + " 个";
        }
    }

    private void restoreMetadata() {
        expressionNames.clear();
        try {
            JSONArray expressions = new JSONArray(prefs.getString("expressions", "[]"));
            for (int i = 0; i < expressions.length(); i++) {
                String value = expressions.optString(i, "");
                if (!value.isBlank() && !isExcludedExpressionName(value)) {
                    expressionNames.add(value);
                }
            }
        } catch (Exception ignored) {
            expressionNames.clear();
        }
    }

    private void rebuildExpressionButtons() {
        if (expressionArea == null) return;
        expressionArea.removeAllViews();
        List<String> switches = new ArrayList<>();
        switches.add(RESET_NATIVE_PRESETS);
        switches.addAll(expressionNames);
        boolean hasGlasses = false;
        for (String name : switches) {
            if ("glasses".equals(name.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", ""))) {
                hasGlasses = true;
                break;
            }
        }
        if (!hasGlasses) switches.add("Glasses");
        for (int start = 0; start < switches.size(); start += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3; column++) {
                int index = start + column;
                if (index < switches.size()) {
                    String name = switches.get(index);
                    boolean isReset = RESET_NATIVE_PRESETS.equals(name);
                    Button button = panelButton(isReset
                            ? "还原全部预设"
                            : SenExpressionLabels.displayName(name));
                    button.setContentDescription(isReset ? "还原全部预设" : name);
                    button.setTextSize(10);
                    button.setOnClickListener(v -> {
                        glSurfaceView.queueEvent(() -> {
                            if (isReset) {
                                renderer.resetNativePresets();
                            } else {
                                renderer.applyExpression(name);
                            }
                        });
                    });
                    row.addView(button, weightedButtonParams());
                } else {
                    row.addView(new View(this), weightedButtonParams());
                }
            }
            expressionArea.addView(row);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String nativeKey = nativeKeyboardMotionName(event.getKeyCode());
        if (nativeKey != null && glSurfaceView != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                glSurfaceView.queueEvent(() -> renderer.playNativeMotion(
                        "keyboard/" + nativeKey));
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                glSurfaceView.queueEvent(renderer::stopNativeMotion);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static String nativeKeyboardMotionName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return Character.toString((char) ('A' + keyCode - KeyEvent.KEYCODE_A));
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return "ALT";
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "CTRL";
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "SHIFT";
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT: return "WIN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_SPACE: return "SPANCE"; // Original package filename.
            default: return null;
        }
    }

    private void updateSummary() {
        if (summaryText == null) return;
        String diagnostics = prefs.getString("diagnostics", "尚未导入模型 ZIP");
        summaryText.setText(diagnostics
                + "\n" + defaultProfile.summary() + "（APK内置）"
                + "\n当前蒙版：" + SenRenderOptions.MASK_MODE.displayName()
                + " · " + SenRenderOptions.HIGH_PRECISION_MASK_SIZE + "px · 连续渲染"
                + String.format(java.util.Locale.ROOT,
                " · 兔耳：隔离九轴双脉冲/速度%.0f%%/幅度%.0f%%",
                SenRenderOptions.EAR_SPEED_PERCENT, SenRenderOptions.EAR_AMPLITUDE_PERCENT)
                + " · 服装：" + selectedOutfit.displayName
                + String.format(java.util.Locale.ROOT,
                " · 呆毛：整体%.0f%%/长度%.0f%%/宽度%.0f%%/%+.0f°/模型X%+.3f/Y%+.3f",
                SenRenderOptions.AHOGE_SCALE_PERCENT,
                SenRenderOptions.AHOGE_LENGTH_PERCENT,
                SenRenderOptions.AHOGE_WIDTH_PERCENT,
                SenRenderOptions.AHOGE_ROTATION_DEGREES,
                SenRenderOptions.AHOGE_OFFSET_X,
                SenRenderOptions.AHOGE_OFFSET_Y)
                + " · 呆毛：ArtMesh151固定根部"
                + " · 尾巴：固定右侧镜像"
                + " · 极限跟随：" + (touchFollowEnabled ? "开" : "关")
                + " · 自主待机：" + (autoIdleEnabled ? "开" : "关")
                + (rendererDetail.isEmpty() ? "" : "\n渲染实测：" + rendererDetail)
                + "\nCore：官方 Cubism Java 5 R5 · Android 原生 OpenGL · 原始2K");
    }

    @Override
    public void onStatus(String status) {
        runOnUiThread(() -> {
            setStatus(status == null ? "" : status.replace('\n', ' '));
            if (loadingOverlay.getVisibility() == View.VISIBLE && status != null) {
                loadingText.setText(status);
            }
        });
    }

    @Override
    public void onReady(String detail) {
        runOnUiThread(() -> {
            hideLoading();
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - nativeLoadStartedAt);
            rendererDetail = detail == null ? "" : detail;
            updateSummary();
            setStatus(detail + " · "
                    + String.format(java.util.Locale.ROOT, "%.1fs", elapsed / 1000.0));
            toastLong("内置参数与C高精度512px已加载：可测试情绪、动作、耳鳍与尾巴");
        });
    }

    @Override
    public void onError(Throwable error) {
        runOnUiThread(() -> {
            hideLoading();
            String message = "原生 Cubism 加载失败：" + readableError(error);
            setStatus(message);
            toastLong(message);
        });
    }

    private FrameLayout buildLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setBackgroundColor(Color.argb(170, 8, 5, 14));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(24), dp(20), dp(24), dp(20));
        card.setBackground(rounded(Color.rgb(43, 32, 59), 18));
        card.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(46), dp(46)));
        loadingText = new TextView(this);
        loadingText.setTextColor(Color.WHITE);
        loadingText.setTextSize(14);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, dp(12), 0, 0);
        card.addView(loadingText);
        overlay.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return overlay;
    }

    private void addPerformanceGrid(LinearLayout parent, List<String> ids,
                                    String[] labels, boolean emotion) {
        for (int start = 0; start < ids.size(); start += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3; column++) {
                int index = start + column;
                if (index >= ids.size()) {
                    row.addView(new View(this), weightedButtonParams());
                    continue;
                }
                String id = ids.get(index);
                String label = index < labels.length ? labels[index] : id;
                Button button = panelButton(label);
                button.setTextSize(10);
                button.setOnClickListener(v -> glSurfaceView.queueEvent(() -> {
                    if (emotion) renderer.selectEmotion(id);
                    else renderer.playAction(id);
                }));
                row.addView(button, weightedButtonParams());
            }
            parent.addView(row);
        }
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setBackground(rounded(Color.rgb(94, 65, 132), 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button panelButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setBackground(rounded(Color.rgb(78, 58, 103), 10));
        return button;
    }

    private TextView adjustmentStatusText() {
        TextView text = new TextView(this);
        text.setTextColor(Color.rgb(205, 190, 220));
        text.setTextSize(10);
        text.setPadding(dp(3), dp(2), 0, 0);
        return text;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void unzipSecure(Uri uri, File destination) throws Exception {
        String destinationPath = destination.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("无法读取 ZIP");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_ZIP_ENTRIES) throw new IOException("ZIP 文件数量异常");
                    File output = new File(destination, entry.getName());
                    String outputPath = output.getCanonicalPath();
                    if (!outputPath.startsWith(destinationPath)) throw new IOException("ZIP 路径不安全");
                    if (entry.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        output.mkdirs();
                    } else {
                        File parent = output.getParentFile();
                        if (parent != null) {
                            //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        }
                        try (OutputStream out = new FileOutputStream(output)) {
                            int count;
                            while ((count = zip.read(buffer)) != -1) {
                                total += count;
                                if (total > MAX_EXTRACTED_BYTES) {
                                    throw new IOException("解压体积超过 1.5 GB 安全限制");
                                }
                                out.write(buffer, 0, count);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    private void purgeRemovedUserExpressionOnUpgrade() {
        if (prefs.getInt("expression_cleanup_version", 0) >= EXPRESSION_CLEANUP_VERSION) return;
        try {
            File cachedExpression = new File(getFilesDir(), REMOVED_USER_EXPRESSION_FILE);
            deleteRecursivelyIfExists(cachedExpression);

            String relative = prefs.getString("model_path", "");
            File installedModel = new File(modelRoot, relative);
            List<String> remaining = null;
            if (!relative.isBlank() && installedModel.isFile()) {
                removeExcludedExpressionFiles(installedModel.getParentFile());
                remaining = registerExpressions(installedModel);
            }

            SharedPreferences.Editor editor = prefs.edit()
                    .putInt("expression_cleanup_version", EXPRESSION_CLEANUP_VERSION)
                    .remove("saved_vts_expressions");
            if (remaining != null) {
                editor.putString("expressions", new JSONArray(remaining).toString());
            }
            editor.apply();
        } catch (Exception ignored) {
            // Do not mark the migration complete: the next launch will safely retry it.
        }
    }

    private void removeExcludedExpressionFiles(File root) throws IOException {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                removeExcludedExpressionFiles(child);
            } else if (isExcludedExpressionFile(child) && !child.delete()) {
                throw new IOException("无法移除已废弃的原生预设");
            }
        }
    }

    private static boolean isExcludedExpressionFile(File file) {
        return file != null && isExcludedExpressionName(file.getName());
    }

    private static boolean isExcludedExpressionName(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("\\.exp3\\.json$", "")
                .replaceAll("[^a-z0-9]+", "");
        String removedUserExpression = REMOVED_USER_EXPRESSION_FILE
                .toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("\\.exp3\\.json$", "")
                .replaceAll("[^a-z0-9]+", "");
        return removedUserExpression.equals(normalized)
                || "watermark".equals(normalized)
                || "hearteyes1".equals(normalized)
                || "press".equals(normalized);
    }

    private File findFirst(File root, String suffix) {
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(suffix)) return file;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findFirst(file, suffix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void collectFiles(File root, String suffix, List<File> output) {
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectFiles(file, suffix, output);
            else if (file.getName().endsWith(suffix)) output.add(file);
        }
    }

    private void moveChildren(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(to, child.getName());
            if (!child.renameTo(target)) throw new IOException("无法移动模型文件：" + child.getName());
        }
    }

    private void deleteChildren(File root) throws IOException {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) deleteRecursivelyIfExists(child);
    }

    private void deleteRecursivelyIfExists(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) deleteChildren(file);
        if (!file.delete() && file.exists()) throw new IOException("无法清理：" + file.getName());
    }

    private String relativePath(File base, File target) {
        return base.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/');
    }

    private SenVtsProfile loadBundledProfile() {
        try (InputStream input = getAssets().open(DEFAULT_PROFILE_ASSET)) {
            return SenVtsProfile.parse(readUtf8Stream(input));
        } catch (IOException error) {
            throw new IllegalStateException("APK内置默认参数无效", error);
        }
    }

    private String readUtf8File(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readUtf8Stream(input);
        }
    }

    private static String readUtf8Stream(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return result.toString();
        }
    }

    private void writeUtf8File(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    private void showLoading(String text) {
        loadingText.setText(text);
        loadingOverlay.setVisibility(View.VISIBLE);
        setStatus(text.replace('\n', ' '));
    }

    private void postLoading(String text) {
        runOnUiThread(() -> showLoading(text));
    }

    private void hideLoading() {
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
    }

    private String readableError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        if (error instanceof OutOfMemoryError) return "内存不足（原生阶段）· " + message;
        return message;
    }

    private String formatMiB(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toastLong(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glSurfaceView != null) glSurfaceView.onResume();
    }

    @Override
    protected void onPause() {
        if (systemTtsLipSync != null) systemTtsLipSync.stop();
        if (glSurfaceView != null && renderer != null) {
            glSurfaceView.queueEvent(renderer::releaseHeadPat);
        }
        if (glSurfaceView != null) glSurfaceView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (systemTtsLipSync != null) systemTtsLipSync.shutdown();
        executor.shutdownNow();
        super.onDestroy();
    }
}
