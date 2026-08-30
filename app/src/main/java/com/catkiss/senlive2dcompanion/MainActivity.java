package com.catkiss.senlive2dcompanion;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
    private static final int AHOGE_CONFIRMED_PRESET_VERSION = 3;
    private static final int EAR_CONFIRMED_PRESET_VERSION = 1;
    private static final int HEAD_ZONE_CONFIRMED_PRESET_VERSION = 2;
    private static final float CONFIRMED_AHOGE_HEIGHT = 56.0f;
    private static final float CONFIRMED_AHOGE_WIDTH = 83.0f;
    private static final float CONFIRMED_AHOGE_ROTATION = -49.0f;
    private static final float CONFIRMED_AHOGE_X = -16.0f;
    private static final float CONFIRMED_AHOGE_Y = 70.0f;
    private static final float DEFAULT_EAR_SPEED_PERCENT = 135.0f;
    private static final float DEFAULT_EAR_AMPLITUDE_PERCENT = 100.0f;
    private static final float DEFAULT_HEAD_ZONE_LEFT = .3875f;
    private static final float DEFAULT_HEAD_ZONE_TOP = .2813f;
    private static final float DEFAULT_HEAD_ZONE_RIGHT = .6234f;
    private static final float DEFAULT_HEAD_ZONE_BOTTOM = .3778f;
    private static final long MAX_EXTRACTED_BYTES = 1_500_000_000L;
    private static final int MAX_ZIP_ENTRIES = 8_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> expressionNames = new ArrayList<>();
    private final List<String> savedVtsExpressions = new ArrayList<>();

    private SharedPreferences prefs;
    private File modelRoot;
    private File importRoot;
    private File appearanceExpressionFile;
    private File appearanceVtubeFile;
    private File profileFile;
    private GLSurfaceView glSurfaceView;
    private SenRenderer renderer;
    private TextView statusText;
    private TextView summaryText;
    private LinearLayout expressionArea;
    private FrameLayout loadingOverlay;
    private TextView loadingText;
    private boolean applyVtsPreset = true;
    private boolean freezeVtsSnapshot = true;
    private SenMaskMode maskMode = SenMaskMode.HIGH_PRECISION;
    private int highPrecisionMaskSize = 1024;
    private float earSpeedPercent;
    private float earAmplitudePercent;
    private float ahogeScalePercent;
    private float ahogeWidthPercent;
    private float ahogeRotationDegrees;
    private float ahogeOffsetX;
    private float ahogeOffsetY;
    private boolean tailMirrored;
    private boolean adjustmentEnabled;
    private float stageScale = 1.0f;
    private float stageTranslateX;
    private float stageTranslateY;
    private float lastTouchX;
    private float lastTouchY;
    private ScaleGestureDetector scaleGestureDetector;
    private Button adjustmentButton;
    private TextView earSpeedStatus;
    private SeekBar earSpeedSeekBar;
    private TextView earAmplitudeStatus;
    private SeekBar earAmplitudeSeekBar;
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
    private SenOutfitPresets.Preset selectedOutfit;
    private long nativeLoadStartedAt;
    private String rendererDetail = "";

    private final ActivityResultLauncher<String[]> modelZipPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onModelZipPicked);

    private final ActivityResultLauncher<String[]> vtsAppearancePicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::onVtsAppearancePicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        maskMode = SenMaskMode.fromPreference(prefs.getString("mask_mode", null));
        highPrecisionMaskSize = normalizeMaskSize(prefs.getInt("mask_size", 1024));
        // v0.3.7 retires the manual ear Drawable transforms. Clear old persisted test values so
        // an in-place APK upgrade always returns to the captured VTS ear state.
        if (prefs.getInt("ear_confirmed_preset_version", 0)
                < EAR_CONFIRMED_PRESET_VERSION) {
            prefs.edit()
                    .putInt("ear_confirmed_preset_version", EAR_CONFIRMED_PRESET_VERSION)
                    .putFloat("ear_speed_percent", DEFAULT_EAR_SPEED_PERCENT)
                    .putFloat("ear_amplitude_percent", DEFAULT_EAR_AMPLITUDE_PERCENT)
                    .apply();
        }
        earSpeedPercent = Math.max(50.0f, Math.min(250.0f,
                prefs.getFloat("ear_speed_percent", DEFAULT_EAR_SPEED_PERCENT)));
        earAmplitudePercent = Math.max(50.0f, Math.min(250.0f,
                prefs.getFloat("ear_amplitude_percent", DEFAULT_EAR_AMPLITUDE_PERCENT)));
        prefs.edit().remove("ear_angle_enabled").remove("ear_angle_degrees")
                .remove("ear_vertical_offset").apply();
        if (prefs.getInt("ahoge_confirmed_preset_version", 0)
                < AHOGE_CONFIRMED_PRESET_VERSION) {
            prefs.edit()
                    .putInt("ahoge_confirmed_preset_version", AHOGE_CONFIRMED_PRESET_VERSION)
                    .putFloat("ahoge_scale_percent", CONFIRMED_AHOGE_HEIGHT)
                    .putFloat("ahoge_width_percent", CONFIRMED_AHOGE_WIDTH)
                    .putFloat("ahoge_rotation_degrees", CONFIRMED_AHOGE_ROTATION)
                    .putFloat("ahoge_offset_x", CONFIRMED_AHOGE_X)
                    .putFloat("ahoge_offset_y", CONFIRMED_AHOGE_Y)
                    .remove("ahoge_root_drawable_id")
                    .remove("ahoge_root_vertex_index")
                    .remove("ahoge_root_model_x")
                    .remove("ahoge_root_model_y")
                    .remove("ahoge_root_distance")
                    .putBoolean("tail_mirrored", true)
                    .apply();
        }
        ahogeScalePercent = CONFIRMED_AHOGE_HEIGHT;
        ahogeWidthPercent = CONFIRMED_AHOGE_WIDTH;
        ahogeRotationDegrees = CONFIRMED_AHOGE_ROTATION;
        ahogeOffsetX = CONFIRMED_AHOGE_X;
        ahogeOffsetY = CONFIRMED_AHOGE_Y;
        tailMirrored = true;
        prefs.edit().putBoolean("tail_mirrored", true)
                .remove("ahoge_root_drawable_id").remove("ahoge_root_vertex_index")
                .remove("ahoge_root_model_x").remove("ahoge_root_model_y")
                .remove("ahoge_root_distance").apply();
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
        appearanceExpressionFile = new File(getFilesDir(), "xiaojingyu.exp3.json");
        appearanceVtubeFile = new File(getFilesDir(), "Sen Customizable Model_2K.vtube.json");
        profileFile = new File(getFilesDir(), "Sen.vts-profile.json");
        //noinspection ResultOfMethodCallIgnored
        modelRoot.mkdirs();
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

        Button appearanceButton = compactButton("导入外观");
        appearanceButton.setOnClickListener(v -> vtsAppearancePicker.launch(
                new String[]{"application/json", "text/json", "text/plain", "application/octet-stream"}));
        toolbar.addView(appearanceButton);

        Button reloadButton = compactButton("重载");
        reloadButton.setOnClickListener(v -> loadNativeModel());
        toolbar.addView(reloadButton);

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(235, 224, 246));
        statusText.setTextSize(10);
        statusText.setSingleLine(true);
        statusText.setText("v0.4.5 · 模型局部摸头与原生呆毛变形修正版");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.setMarginStart(dp(5));
        toolbar.addView(statusText, statusParams);
        page.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        FrameLayout stage = new FrameLayout(this);
        glSurfaceView = new GLSurfaceView(this);
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        renderer = new SenRenderer(this, this);
        renderer.setTouchFollowEnabled(touchFollowEnabled);
        renderer.setEarTuning(earSpeedPercent, earAmplitudePercent);
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

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button vtsButton = panelButton("使用VTS外观底座");
        vtsButton.setOnClickListener(v -> {
            if (!profileFile.isFile()) {
                toastLong("请点顶部“导入外观”，选择一次 Sen.vts-profile.json；服装颜色已经内置");
                return;
            }
            applyVtsPreset = true;
            freezeVtsSnapshot = true;
            loadNativeModel();
        });
        controls.addView(vtsButton, weightedButtonParams());

        Button layeredButton = panelButton("旧分层状态对照");
        layeredButton.setOnClickListener(v -> {
            if (!appearanceExpressionFile.isFile() || !appearanceVtubeFile.isFile()) {
                toastLong("旧分层对照需要 xiaojingyu.exp3.json 和当前 .vtube.json");
                return;
            }
            applyVtsPreset = true;
            freezeVtsSnapshot = false;
            loadNativeModel();
        });
        controls.addView(layeredButton, weightedButtonParams());

        Button rawButton = panelButton("查看原始状态");
        rawButton.setOnClickListener(v -> {
            applyVtsPreset = false;
            freezeVtsSnapshot = false;
            loadNativeModel();
        });
        controls.addView(rawButton, weightedButtonParams());
        panel.addView(controls);

        TextView memoryNote = new TextView(this);
        memoryNote.setText("动态顺序：VTS外观底座 → 情绪/动作 → 原生物理 → 尾巴镜像与呆毛。外观参数每帧恢复，动作不会累积破坏部件选择。");
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
        outfitNote.setText("女仆装/白衬衫共用配色，兔女郎使用独立配色；白衬衫会关闭Maid Headband。服装参数不包含九轴、手臂或物理瞬时值。");
        outfitNote.setTextColor(Color.rgb(186, 164, 204));
        outfitNote.setTextSize(10);
        outfitNote.setPadding(dp(3), dp(2), 0, dp(3));
        panel.addView(outfitNote);

        TextView maskHeading = new TextView(this);
        maskHeading.setText("底层蒙版模式（只改变渲染策略；每次会重载大模型）");
        maskHeading.setTextColor(Color.rgb(238, 207, 255));
        maskHeading.setTextSize(11);
        maskHeading.setPadding(dp(3), dp(4), 0, 0);
        panel.addView(maskHeading);

        LinearLayout maskControls = new LinearLayout(this);
        maskControls.setOrientation(LinearLayout.HORIZONTAL);
        for (SenMaskMode option : SenMaskMode.values()) {
            Button button = panelButton(option.displayName());
            button.setOnClickListener(v -> selectMaskMode(option));
            maskControls.addView(button, weightedButtonParams());
        }
        panel.addView(maskControls);

        TextView maskQualityHeading = new TextView(this);
        maskQualityHeading.setText("C蒙版清晰度（默认1024；切换后会重载模型）");
        maskQualityHeading.setTextColor(Color.rgb(220, 198, 238));
        maskQualityHeading.setTextSize(10);
        maskQualityHeading.setPadding(dp(3), dp(3), 0, 0);
        panel.addView(maskQualityHeading);

        LinearLayout maskQualityControls = new LinearLayout(this);
        maskQualityControls.setOrientation(LinearLayout.HORIZONTAL);
        for (int size : new int[]{512, 1024}) {
            Button button = panelButton(size + "px");
            button.setOnClickListener(v -> selectHighPrecisionMaskSize(size));
            maskQualityControls.addView(button, weightedButtonParams());
        }
        panel.addView(maskQualityControls);

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
        earNotice.setText("兔耳双脉冲：继续走九轴→原生物理，只取三项兔耳输出。确认预设为速度135%、幅度100%；滑杆保留用于以后试调，自主待机会低频触发。");
        earNotice.setTextColor(Color.rgb(220, 198, 238));
        earNotice.setTextSize(10);
        earNotice.setPadding(dp(3), dp(5), 0, dp(2));
        panel.addView(earNotice);

        earSpeedStatus = adjustmentStatusText();
        panel.addView(earSpeedStatus);
        earSpeedSeekBar = new SeekBar(this);
        earSpeedSeekBar.setMax(200);
        earSpeedSeekBar.setProgress(Math.round(earSpeedPercent) - 50);
        earSpeedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                earSpeedPercent = progress + 50.0f;
                updateCustomizationControls();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                persistAndApplyEarTuning();
            }
        });
        panel.addView(earSpeedSeekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        earAmplitudeStatus = adjustmentStatusText();
        panel.addView(earAmplitudeStatus);
        earAmplitudeSeekBar = new SeekBar(this);
        earAmplitudeSeekBar.setMax(200);
        earAmplitudeSeekBar.setProgress(Math.round(earAmplitudePercent) - 50);
        earAmplitudeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                earAmplitudePercent = progress + 50.0f;
                updateCustomizationControls();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                persistAndApplyEarTuning();
            }
        });
        panel.addView(earAmplitudeSeekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        Button earTwitchButton = panelButton("测试：猫耳快速抖动两次");
        earTwitchButton.setOnClickListener(v -> glSurfaceView.queueEvent(
                () -> renderer.triggerEarTwitch()));
        panel.addView(earTwitchButton);

        TextView ahogeHeading = new TextView(this);
        ahogeHeading.setText("长呆毛（固定确认预设 56/83/-49/-16/+70）");
        ahogeHeading.setTextColor(Color.rgb(238, 207, 255));
        ahogeHeading.setTextSize(11);
        ahogeHeading.setPadding(dp(3), dp(5), 0, 0);
        panel.addView(ahogeHeading);

        TextView ahogeNote = adjustmentStatusText();
        ahogeNote.setText("位置、大小和角度固定；保留模型原生头发物理，只在原生更新后围绕呆毛自身固定发根施加外观变换，不再绑定任何外部头发、兔耳或尾巴网格。");
        panel.addView(ahogeNote);
        Button ahogeResetButton = panelButton("还原确认的呆毛预设");
        ahogeResetButton.setOnClickListener(v -> resetAhogeTransform());
        panel.addView(ahogeResetButton);

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
        emotionHeading.setText("AI伴侣情绪（20个语义入口；紧张/慌张允许共享视觉）");
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

        TextView actionHeading = new TextView(this);
        actionHeading.setText("程序动作测试（完整26项；暂不自动隐藏任何按钮）");
        actionHeading.setTextColor(Color.rgb(238, 207, 255));
        actionHeading.setTextSize(12);
        actionHeading.setPadding(0, dp(7), 0, dp(3));
        panel.addView(actionHeading);
        String[] manualActionLabels = {
                "点头", "摇头", "歪头", "前倾", "后仰",
                "惊讶眨眼", "叹气", "撅嘴", "兴奋弹跳", "倾听",
                "环顾", "轻摆", "低头抬头", "小点头", "待机歪头",
                "侧看", "重心切换", "轻靠", "叹气下沉", "慢眨眼",
                "柔风摆动", "明显风摆", "展示级大摆", "视频式环绕",
                "摸头常规", "摸头疑惑彩蛋"
        };
        addPerformanceGrid(panel, SenPerformanceEngine.MANUAL_TEST_ACTIONS,
                manualActionLabels, false);

        TextView actionNote = adjustmentStatusText();
        actionNote.setText("已恢复优化自主待机前的完整26项入口，包括曾要求删除及已加入待机的动作；后续只按实机逐项确认结果删除按钮。ZIP预设动作/表情不改。");
        panel.addView(actionNote);

        TextView expressionHeading = new TextView(this);
        expressionHeading.setText("Sen ZIP 原生表情/道具（Watermark仅作模型自带测试项）");
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
        updateCustomizationControls();
        updateSummary();
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

    private void selectMaskMode(SenMaskMode selected) {
        if (selected == null) return;
        maskMode = selected;
        prefs.edit().putString("mask_mode", selected.name()).apply();
        updateSummary();
        toastLong("切换到 " + selected.displayName() + "，正在重载同一模型");
        loadNativeModel();
    }

    private void selectHighPrecisionMaskSize(int size) {
        highPrecisionMaskSize = normalizeMaskSize(size);
        maskMode = SenMaskMode.HIGH_PRECISION;
        prefs.edit()
                .putInt("mask_size", highPrecisionMaskSize)
                .putString("mask_mode", maskMode.name())
                .apply();
        updateSummary();
        toastLong("C高精度蒙版切换到 " + highPrecisionMaskSize + "px，正在重载模型");
        loadNativeModel();
    }

    private void selectOutfitPreset(SenOutfitPresets.Preset preset) {
        if (preset == null) return;
        if (!profileFile.isFile()) {
            toastLong("内置服装只需一份VTS外观底座；请先导入Sen.vts-profile.json");
            return;
        }
        selectedOutfit = preset;
        prefs.edit().putString("outfit_preset", preset.id).apply();
        if (applyVtsPreset && freezeVtsSnapshot) {
            glSurfaceView.queueEvent(() -> renderer.selectOutfit(preset));
            updateSummary();
            toastLong("已切换服装：" + preset.displayName);
        } else {
            applyVtsPreset = true;
            freezeVtsSnapshot = true;
            loadNativeModel();
        }
    }

    private static int normalizeMaskSize(int size) {
        if (size >= 1024) return 1024;
        return 512;
    }

    private void resetAhogeTransform() {
        ahogeScalePercent = CONFIRMED_AHOGE_HEIGHT;
        ahogeWidthPercent = CONFIRMED_AHOGE_WIDTH;
        ahogeRotationDegrees = CONFIRMED_AHOGE_ROTATION;
        ahogeOffsetX = CONFIRMED_AHOGE_X;
        ahogeOffsetY = CONFIRMED_AHOGE_Y;
        persistAndApplyCustomization();
        toastLong("呆毛已恢复固定预设 56/83/-49/-16/+70 与头发整体九轴支撑");
    }

    private void persistAndApplyCustomization() {
        ahogeScalePercent = CONFIRMED_AHOGE_HEIGHT;
        ahogeWidthPercent = CONFIRMED_AHOGE_WIDTH;
        ahogeRotationDegrees = CONFIRMED_AHOGE_ROTATION;
        ahogeOffsetX = CONFIRMED_AHOGE_X;
        ahogeOffsetY = CONFIRMED_AHOGE_Y;
        tailMirrored = true;
        prefs.edit()
                .putFloat("ahoge_scale_percent", ahogeScalePercent)
                .putFloat("ahoge_width_percent", ahogeWidthPercent)
                .putFloat("ahoge_rotation_degrees", ahogeRotationDegrees)
                .putFloat("ahoge_offset_x", ahogeOffsetX)
                .putFloat("ahoge_offset_y", ahogeOffsetY)
                .remove("ahoge_root_drawable_id")
                .remove("ahoge_root_vertex_index")
                .remove("ahoge_root_model_x")
                .remove("ahoge_root_model_y")
                .remove("ahoge_root_distance")
                .putBoolean("tail_mirrored", true)
                .apply();
        updateCustomizationControls();
        if (glSurfaceView != null && renderer != null) {
            glSurfaceView.queueEvent(() -> renderer.setCustomization(
                    false, 0.0f, 0.0f,
                    ahogeScalePercent, ahogeWidthPercent, ahogeRotationDegrees,
                    ahogeOffsetX, ahogeOffsetY, tailMirrored));
        }
        updateSummary();
    }

    private void updateCustomizationControls() {
        if (earSpeedStatus != null) {
            earSpeedStatus.setText(String.format(java.util.Locale.ROOT,
                    "兔耳速度：%.0f%%（50%%～250%%）", earSpeedPercent));
        }
        if (earAmplitudeStatus != null) {
            earAmplitudeStatus.setText(String.format(java.util.Locale.ROOT,
                    "兔耳幅度：%.0f%%（50%%～250%%）", earAmplitudePercent));
        }
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

    private void persistAndApplyEarTuning() {
        earSpeedPercent = Math.max(50.0f, Math.min(250.0f, earSpeedPercent));
        earAmplitudePercent = Math.max(50.0f, Math.min(250.0f, earAmplitudePercent));
        prefs.edit()
                .putFloat("ear_speed_percent", earSpeedPercent)
                .putFloat("ear_amplitude_percent", earAmplitudePercent)
                .apply();
        updateCustomizationControls();
        if (glSurfaceView != null && renderer != null) {
            glSurfaceView.queueEvent(() -> renderer.setEarTuning(
                    earSpeedPercent, earAmplitudePercent));
        }
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
        toastLong("摸头范围已恢复确认值 0.3875/0.2813/0.6234/0.3778");
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

                postLoading("正在识别 model3、表情与 VTS 启动预设…");
                File modelFile = findFirst(importRoot, ".model3.json");
                if (modelFile == null) throw new IOException("ZIP 中没有找到 .model3.json");
                installSavedAppearanceExpression(modelFile);
                List<String> detectedExpressions = registerExpressions(modelFile);
                List<String> detectedSaved = readSavedVtsExpressions(modelFile, detectedExpressions);
                if (detectedExpressions.contains("xiaojingyu")) {
                    detectedSaved.clear();
                    detectedSaved.add("xiaojingyu");
                }
                String modelRelative = relativePath(importRoot, modelFile);
                String diagnostics = buildDiagnostics(modelFile, detectedExpressions, detectedSaved);

                postLoading("正在替换 App 私有目录中的旧模型…");
                deleteChildren(modelRoot);
                moveChildren(importRoot, modelRoot);
                prefs.edit()
                        .putString("model_path", modelRelative)
                        .putString("expressions", new JSONArray(detectedExpressions).toString())
                        .putString("saved_vts_expressions", new JSONArray(detectedSaved).toString())
                        .putString("diagnostics", diagnostics)
                        .apply();

                runOnUiThread(() -> {
                    expressionNames.clear();
                    expressionNames.addAll(detectedExpressions);
                    savedVtsExpressions.clear();
                    savedVtsExpressions.addAll(detectedSaved);
                    applyVtsPreset = true;
                    rebuildExpressionButtons();
                    updateSummary();
                    toastLong("模型导入成功：表情 " + detectedExpressions.size()
                            + " 个，VTS 启动预设 " + detectedSaved.size() + " 个");
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

    private void onVtsAppearancePicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        showLoading("正在读取 Sen VTS 外观文件…");
        executor.execute(() -> {
            try {
                boolean importedExpression = false;
                boolean importedColors = false;
                boolean importedProfile = false;
                int expressionParameters = 0;
                SenVtsAppearance appearance = null;
                SenVtsProfile profile = null;
                for (Uri uri : uris) {
                    String text = readUtf8Uri(uri, 5 * 1024 * 1024);
                    JSONObject json = new JSONObject(text);
                    if ("Live2D Expression".equals(json.optString("Type", ""))) {
                        JSONArray parameters = json.optJSONArray("Parameters");
                        if (parameters == null || parameters.length() == 0) {
                            throw new IOException("xiaojingyu表情中没有参数");
                        }
                        validateXiaojingyuExpression(parameters);
                        normalizeVtsExpressionBlends(parameters);
                        writeUtf8File(appearanceExpressionFile, json.toString(2));
                        expressionParameters = parameters.length();
                        importedExpression = true;
                    } else if (json.has("ArtMeshDetails")) {
                        appearance = SenVtsAppearance.parse(text);
                        writeUtf8File(appearanceVtubeFile, text);
                        importedColors = true;
                    } else if (SenVtsProfile.SCHEMA.equals(json.optString("schema", ""))) {
                        profile = SenVtsProfile.parse(text);
                        writeUtf8File(profileFile, text);
                        importedProfile = true;
                    } else {
                        throw new IOException("请选择 Sen.vts-profile.json、xiaojingyu.exp3.json 或当前 .vtube.json");
                    }
                }

                String relative = prefs.getString("model_path", "");
                File modelFile = new File(modelRoot, relative);
                if (!relative.isBlank() && modelFile.isFile()) {
                    installSavedAppearanceExpression(modelFile);
                    List<String> detectedExpressions = registerExpressions(modelFile);
                    expressionNames.clear();
                    expressionNames.addAll(detectedExpressions);
                    if (detectedExpressions.contains("xiaojingyu")) {
                        savedVtsExpressions.clear();
                        savedVtsExpressions.add("xiaojingyu");
                    }
                    prefs.edit()
                            .putString("expressions", new JSONArray(expressionNames).toString())
                            .putString("saved_vts_expressions",
                                    new JSONArray(savedVtsExpressions).toString())
                            .apply();
                }

                if (appearance == null && appearanceVtubeFile.isFile()) {
                    appearance = SenVtsAppearance.parse(readUtf8File(appearanceVtubeFile));
                }
                if (profile == null && profileFile.isFile()) {
                    profile = SenVtsProfile.parse(readUtf8File(profileFile));
                }
                String summary = "xiaojingyu "
                        + (appearanceExpressionFile.isFile()
                        ? (expressionParameters > 0 ? expressionParameters + "项" : "已保存") : "未导入")
                        + " · " + (appearance == null ? "逐部件颜色未导入" : appearance.summary())
                        + " · " + (profile == null ? "VTS底座未导入" : profile.summary());
                prefs.edit().putString("vts_appearance_summary", summary).apply();
                boolean expressionChanged = importedExpression;
                boolean colorsChanged = importedColors;
                boolean profileChanged = importedProfile;
                runOnUiThread(() -> {
                    applyVtsPreset = true;
                    if (profileFile.isFile()) freezeVtsSnapshot = true;
                    rebuildExpressionButtons();
                    updateSummary();
                    toastLong("外观导入成功："
                            + (expressionChanged ? "xiaojingyu增量 " : "")
                            + (colorsChanged ? "VTS逐部件颜色 " : "")
                            + (profileChanged ? "VTS动态底座" : ""));
                    loadNativeModel();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    hideLoading();
                    String message = "外观导入失败：" + readableError(error);
                    setStatus(message);
                    toastLong(message);
                });
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
        if (applyVtsPreset && !freezeVtsSnapshot) {
            if (expressionNames.contains("xiaojingyu")) startup.add("xiaojingyu");
            else startup.addAll(savedVtsExpressions);
        }
        SenVtsAppearance appearance = null;
        if (applyVtsPreset && freezeVtsSnapshot) {
            appearance = selectedOutfit.appearance;
        } else if (applyVtsPreset && appearanceVtubeFile.isFile()) {
            try {
                appearance = SenVtsAppearance.parse(readUtf8File(appearanceVtubeFile));
            } catch (IOException error) {
                hideLoading();
                String message = "已保存的VTS逐部件颜色无效：" + readableError(error);
                setStatus(message);
                toastLong(message);
                return;
            }
        }
        SenVtsProfile frozenProfile = null;
        if (applyVtsPreset && freezeVtsSnapshot) {
            if (!profileFile.isFile()) {
                hideLoading();
                String message = "VTS外观底座需要 Sen.vts-profile.json，请点“导入外观”选择参数包";
                setStatus(message);
                toastLong(message);
                return;
            }
            try {
                frozenProfile = SenVtsProfile.parse(readUtf8File(profileFile));
            } catch (IOException error) {
                hideLoading();
                String message = "已保存的VTS底座参数无效：" + readableError(error);
                setStatus(message);
                toastLong(message);
                return;
            }
        }
        SenVtsAppearance selectedAppearance = appearance;
        SenVtsProfile selectedFrozenProfile = frozenProfile;
        SenRenderOptions selectedOptions = new SenRenderOptions(
                maskMode, highPrecisionMaskSize,
                false, 0.0f, 0.0f,
                ahogeScalePercent, ahogeWidthPercent, ahogeRotationDegrees,
                ahogeOffsetX, ahogeOffsetY,
                true, autoIdleEnabled);
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

    private void installSavedAppearanceExpression(File modelFile) throws IOException {
        if (!appearanceExpressionFile.isFile()) return;
        File directory = modelFile.getParentFile();
        if (directory == null) throw new IOException("模型目录无效");
        File target = new File(directory, "xiaojingyu.exp3.json");
        try (InputStream input = new FileInputStream(appearanceExpressionFile);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private void normalizeVtsExpressionBlends(JSONArray parameters) throws Exception {
        for (int i = 0; i < parameters.length(); i++) {
            JSONObject parameter = parameters.optJSONObject(i);
            if (parameter == null) continue;
            String blend = parameter.optString("Blend", "Add");
            if ("VTS_Add".equalsIgnoreCase(blend)) parameter.put("Blend", "Add");
        }
    }

    private void validateXiaojingyuExpression(JSONArray parameters) throws IOException {
        boolean hasEars = false;
        boolean hasShoes = false;
        boolean hasHair = false;
        boolean hasApron = false;
        for (int i = 0; i < parameters.length(); i++) {
            JSONObject parameter = parameters.optJSONObject(i);
            String id = parameter == null ? "" : parameter.optString("Id", "");
            if ("RabbitEarrs".equals(id)) hasEars = true;
            else if ("Shoes".equals(id)) hasShoes = true;
            else if ("ParamHair_Hue4".equals(id)) hasHair = true;
            else if ("ApronUpper".equals(id)) hasApron = true;
        }
        if (!hasEars || !hasShoes || !hasHair || !hasApron) {
            throw new IOException("所选表情不是本次生成的 xiaojingyu.exp3.json");
        }
    }

    private List<String> readSavedVtsExpressions(File modelFile, List<String> known) throws Exception {
        File directory = modelFile.getParentFile();
        File vtube = directory == null ? null : findFirst(directory, ".vtube.json");
        List<String> saved = new ArrayList<>();
        if (vtube == null) return saved;
        JSONArray array = new JSONObject(readUtf8File(vtube)).optJSONArray("SavedActiveExpressions");
        if (array == null) return saved;
        for (int i = 0; i < array.length(); i++) {
            String name = array.optString(i, "").replaceFirst("\\.exp3\\.json$", "").trim();
            if (!name.isEmpty() && known.contains(name) && !saved.contains(name)) saved.add(name);
        }
        return saved;
    }

    private String buildDiagnostics(File modelFile, List<String> expressions, List<String> saved) {
        try {
            JSONObject json = new JSONObject(readUtf8File(modelFile));
            JSONObject refs = json.optJSONObject("FileReferences");
            JSONArray textures = refs == null ? null : refs.optJSONArray("Textures");
            File moc = refs == null ? null : new File(modelFile.getParentFile(), refs.optString("Moc", ""));
            return "Cubism model3 v" + json.optInt("Version", 0)
                    + " · 贴图 " + (textures == null ? 0 : textures.length()) + " 张"
                    + " · moc3 " + (moc != null && moc.isFile() ? formatMiB(moc.length()) : "未知")
                    + "\nZIP 表情 " + expressions.size() + " 个"
                    + " · VTS 启动预设：" + (saved.isEmpty() ? "无" : String.join("、", saved));
        } catch (Exception ignored) {
            return "模型已导入 · ZIP 表情 " + expressions.size() + " 个";
        }
    }

    private void restoreMetadata() {
        expressionNames.clear();
        savedVtsExpressions.clear();
        try {
            JSONArray expressions = new JSONArray(prefs.getString("expressions", "[]"));
            for (int i = 0; i < expressions.length(); i++) {
                String value = expressions.optString(i, "");
                if (!value.isBlank()) expressionNames.add(value);
            }
            JSONArray saved = new JSONArray(prefs.getString("saved_vts_expressions", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String value = saved.optString(i, "");
                if (!value.isBlank()) savedVtsExpressions.add(value);
            }
        } catch (Exception ignored) {
            expressionNames.clear();
            savedVtsExpressions.clear();
        }
    }

    private void rebuildExpressionButtons() {
        if (expressionArea == null) return;
        expressionArea.removeAllViews();
        if (expressionNames.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("导入模型后，这里会列出 ZIP 中的 .exp3.json 表情。");
            empty.setTextColor(Color.rgb(177, 162, 194));
            empty.setTextSize(11);
            expressionArea.addView(empty);
            return;
        }
        for (int start = 0; start < expressionNames.size(); start += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3; column++) {
                int index = start + column;
                if (index < expressionNames.size()) {
                    String name = expressionNames.get(index);
                    Button button = panelButton(name);
                    button.setTextSize(10);
                    button.setOnClickListener(v -> {
                        glSurfaceView.queueEvent(() -> renderer.applyExpression(name));
                    });
                    row.addView(button, weightedButtonParams());
                } else {
                    row.addView(new View(this), weightedButtonParams());
                }
            }
            expressionArea.addView(row);
        }
    }

    private void updateSummary() {
        if (summaryText == null) return;
        String diagnostics = prefs.getString("diagnostics", "尚未导入模型 ZIP");
        String appearance = prefs.getString("vts_appearance_summary",
                "尚未导入 Sen.vts-profile.json、xiaojingyu.exp3.json 与当前 .vtube.json");
        summaryText.setText(diagnostics
                + "\n" + appearance
                + "\n当前蒙版：" + maskMode.displayName()
                + (maskMode == SenMaskMode.HIGH_PRECISION
                ? " · " + highPrecisionMaskSize + "px" : "")
                + String.format(java.util.Locale.ROOT,
                " · 兔耳：隔离九轴双脉冲/速度%.0f%%/幅度%.0f%%",
                earSpeedPercent, earAmplitudePercent)
                + " · 服装：" + selectedOutfit.displayName
                + String.format(java.util.Locale.ROOT,
                " · 呆毛：高%.0f%%/宽%.0f%%/%+.0f°/X%+.0f/Y%+.0f",
                ahogeScalePercent, ahogeWidthPercent, ahogeRotationDegrees,
                ahogeOffsetX, ahogeOffsetY)
                + " · 呆毛：原生物理后按自身发根变换"
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
            toastLong(freezeVtsSnapshot
                    ? maskMode.displayName() + " 动态底座已加载：可测试情绪、动作、耳鳍与尾巴"
                    : "Sen 原始状态已加载，可用于对照");
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

    private String readUtf8File(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return result.toString();
        }
    }

    private String readUtf8Uri(Uri uri, int maxBytes) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取参数文件");
            byte[] buffer = new byte[16 * 1024];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IOException("参数文件超过5 MiB限制");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
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
        if (glSurfaceView != null) glSurfaceView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
