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
    private boolean earAngleOverrideEnabled;
    private float earAngleDegrees;
    private boolean ahogeShortened;
    private boolean ahogeRaised;
    private boolean adjustmentEnabled;
    private float stageScale = 1.0f;
    private float stageTranslateX;
    private float stageTranslateY;
    private float lastTouchX;
    private float lastTouchY;
    private ScaleGestureDetector scaleGestureDetector;
    private Button adjustmentButton;
    private TextView earAngleStatus;
    private SeekBar earAngleSeekBar;
    private Button ahogeShortButton;
    private Button ahogeRaiseButton;
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
        earAngleOverrideEnabled = prefs.getBoolean("ear_angle_enabled", false);
        earAngleDegrees = Math.max(-30.0f,
                Math.min(30.0f, prefs.getFloat("ear_angle_degrees", 0.0f)));
        ahogeShortened = prefs.getBoolean("ahoge_shortened", false);
        ahogeRaised = prefs.getBoolean("ahoge_raised", false);
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
        statusText.setText("v0.3.4 · C蒙版清晰度与外形试调");
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
        heading.setText("Sen · Cubism 5 原生静态显示检查");
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
        Button vtsButton = panelButton("冻结VTS采集状态");
        vtsButton.setOnClickListener(v -> {
            if (!profileFile.isFile() || !appearanceVtubeFile.isFile()) {
                toastLong("请点顶部“导入外观”，同时选择 Sen.vts-profile.json 和当前 .vtube.json");
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
        memoryNote.setText("冻结模式：581项VTS最终值直接写入且不做范围裁剪；关闭表情、物理和每帧更新，仅验证静态底层显示");
        memoryNote.setTextColor(Color.rgb(186, 164, 204));
        memoryNote.setTextSize(10);
        memoryNote.setPadding(dp(3), dp(3), 0, dp(3));
        panel.addView(memoryNote);

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
        for (int size : new int[]{256, 512, 1024}) {
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

        TextView earHeading = new TextView(this);
        earHeading.setText("耳鳍角度试调（左右联动，不写回模型文件）");
        earHeading.setTextColor(Color.rgb(238, 207, 255));
        earHeading.setTextSize(11);
        earHeading.setPadding(dp(3), dp(5), 0, 0);
        panel.addView(earHeading);

        earAngleStatus = new TextView(this);
        earAngleStatus.setTextColor(Color.rgb(205, 190, 220));
        earAngleStatus.setTextSize(10);
        earAngleStatus.setPadding(dp(3), dp(2), 0, 0);
        panel.addView(earAngleStatus);

        earAngleSeekBar = new SeekBar(this);
        earAngleSeekBar.setMax(60);
        earAngleSeekBar.setProgress(Math.round(earAngleDegrees) + 30);
        earAngleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                earAngleOverrideEnabled = true;
                earAngleDegrees = progress - 30.0f;
                updateCustomizationControls();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                persistAndApplyCustomization();
            }
        });
        panel.addView(earAngleSeekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout earPresets = new LinearLayout(this);
        earPresets.setOrientation(LinearLayout.HORIZONTAL);
        Button earOriginal = panelButton("原VTS值");
        earOriginal.setOnClickListener(v -> restoreEarAngle());
        earPresets.addView(earOriginal, weightedButtonParams());
        for (int angle : new int[]{0, 10, 20}) {
            Button button = panelButton((angle > 0 ? "+" : "") + angle + "°");
            button.setOnClickListener(v -> setEarAngleOverride(angle));
            earPresets.addView(button, weightedButtonParams());
        }
        panel.addView(earPresets);

        TextView ahogeHeading = new TextView(this);
        ahogeHeading.setText("长呆毛运行时试调（两个选项可分别开关）");
        ahogeHeading.setTextColor(Color.rgb(238, 207, 255));
        ahogeHeading.setTextSize(11);
        ahogeHeading.setPadding(dp(3), dp(5), 0, 0);
        panel.addView(ahogeHeading);

        LinearLayout ahogeControls = new LinearLayout(this);
        ahogeControls.setOrientation(LinearLayout.HORIZONTAL);
        ahogeShortButton = panelButton("");
        ahogeShortButton.setOnClickListener(v -> {
            ahogeShortened = !ahogeShortened;
            persistAndApplyCustomization();
        });
        ahogeControls.addView(ahogeShortButton, weightedButtonParams());
        ahogeRaiseButton = panelButton("");
        ahogeRaiseButton.setOnClickListener(v -> {
            ahogeRaised = !ahogeRaised;
            persistAndApplyCustomization();
        });
        ahogeControls.addView(ahogeRaiseButton, weightedButtonParams());
        panel.addView(ahogeControls);
        updateCustomizationControls();

        TextView expressionHeading = new TextView(this);
        expressionHeading.setText("ZIP 表情（冻结模式停用；本轮仍不测试表情与动作）");
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
            if (!adjustmentEnabled) return false;
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

    private static int normalizeMaskSize(int size) {
        if (size >= 1024) return 1024;
        if (size >= 512) return 512;
        return 256;
    }

    private void setEarAngleOverride(float angle) {
        earAngleOverrideEnabled = true;
        earAngleDegrees = Math.max(-30.0f, Math.min(30.0f, angle));
        if (earAngleSeekBar != null) {
            int progress = Math.round(earAngleDegrees) + 30;
            if (earAngleSeekBar.getProgress() != progress) earAngleSeekBar.setProgress(progress);
        }
        persistAndApplyCustomization();
    }

    private void restoreEarAngle() {
        earAngleOverrideEnabled = false;
        persistAndApplyCustomization();
        toastLong("耳鳍已恢复导入的VTS原值（本次采集约 -8.8°）");
    }

    private void persistAndApplyCustomization() {
        prefs.edit()
                .putBoolean("ear_angle_enabled", earAngleOverrideEnabled)
                .putFloat("ear_angle_degrees", earAngleDegrees)
                .putBoolean("ahoge_shortened", ahogeShortened)
                .putBoolean("ahoge_raised", ahogeRaised)
                .apply();
        updateCustomizationControls();
        if (glSurfaceView != null && renderer != null) {
            glSurfaceView.queueEvent(() -> renderer.setCustomization(
                    earAngleOverrideEnabled, earAngleDegrees,
                    ahogeShortened, ahogeRaised));
        }
        updateSummary();
    }

    private void updateCustomizationControls() {
        if (earAngleStatus != null) {
            earAngleStatus.setText(earAngleOverrideEnabled
                    ? String.format(java.util.Locale.ROOT,
                    "当前联动角度：%+.0f°（滑杆范围 -30°～+30°）", earAngleDegrees)
                    : "当前：导入的VTS原值（本次采集约 -8.8°）");
        }
        if (ahogeShortButton != null) {
            ahogeShortButton.setText("缩短：" + (ahogeShortened ? "开" : "关"));
        }
        if (ahogeRaiseButton != null) {
            ahogeRaiseButton.setText("上移：" + (ahogeRaised ? "开" : "关"));
        }
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
                        + " · " + (profile == null ? "冻结参数未导入" : profile.summary());
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
                            + (profileChanged ? "VTS冻结参数" : ""));
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
        if (applyVtsPreset && appearanceVtubeFile.isFile()) {
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
                String message = "冻结模式需要 Sen.vts-profile.json，请点“导入外观”选择参数包";
                setStatus(message);
                toastLong(message);
                return;
            }
            try {
                frozenProfile = SenVtsProfile.parse(readUtf8File(profileFile));
            } catch (IOException error) {
                hideLoading();
                String message = "已保存的VTS冻结参数无效：" + readableError(error);
                setStatus(message);
                toastLong(message);
                return;
            }
        }
        SenVtsAppearance selectedAppearance = appearance;
        SenVtsProfile selectedFrozenProfile = frozenProfile;
        SenRenderOptions selectedOptions = new SenRenderOptions(
                maskMode, highPrecisionMaskSize,
                earAngleOverrideEnabled, earAngleDegrees,
                ahogeShortened, ahogeRaised);
        rendererDetail = "";
        updateSummary();
        glSurfaceView.queueEvent(() -> renderer.requestModel(
                modelFile, startup, selectedAppearance, selectedFrozenProfile, selectedOptions));
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
                        if (freezeVtsSnapshot) {
                            toastLong("冻结模式不会运行表情；请先完成静态显示验收");
                            return;
                        }
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
                + " · 耳鳍：" + (earAngleOverrideEnabled
                ? String.format(java.util.Locale.ROOT, "%+.0f°", earAngleDegrees) : "VTS原值")
                + " · 呆毛：" + (ahogeShortened ? "缩短" : "原长")
                + "/" + (ahogeRaised ? "上移" : "原位")
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
                    ? maskMode.displayName() + " 已加载：请检查耳鳍白边、角度和呆毛"
                    : "Sen 已加载，请检查颜色、头发、眼睛和部件状态");
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
