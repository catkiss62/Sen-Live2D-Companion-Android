package com.catkiss.senlive2dcompanion;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

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

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "sen_live2d_renderer_test";
    private static final long MAX_EXTRACTED_BYTES = 1_500_000_000L;
    private static final int MAX_ZIP_ENTRIES = 8_000;
    private static final int MOBILE_TEXTURE_MAX = 1024;
    private static final String QUALITY_2K = "2k";
    private static final String QUALITY_1K = "1k";
    private static final String STAGE_URL =
            "https://appassets.androidplatform.net/assets/stage/index.html";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> expressionNames = new ArrayList<>();
    private final List<String> savedVtsExpressions = new ArrayList<>();

    private SharedPreferences prefs;
    private File modelRoot;
    private File runtimeRoot;
    private File importRoot;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private TextView statusText;
    private TextView summaryText;
    private LinearLayout expressionArea;
    private FrameLayout loadingOverlay;
    private TextView loadingText;
    private boolean applyVtsPreset = true;
    private boolean qualityFallbackInProgress;
    private long stageLoadStartedAt;

    private final ActivityResultLauncher<String[]> modelZipPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onModelZipPicked);
    private final ActivityResultLauncher<String[]> corePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onCorePicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        modelRoot = new File(getFilesDir(), "sen-live2d-model");
        runtimeRoot = new File(getFilesDir(), "cubism-runtime");
        importRoot = new File(getFilesDir(), "sen-import-temp");
        //noinspection ResultOfMethodCallIgnored
        modelRoot.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        runtimeRoot.mkdirs();
        restoreMetadata();
        buildUi();
        configureWebView();
        loadStage();
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

        Button coreButton = compactButton("导入Core");
        coreButton.setOnClickListener(v -> corePicker.launch(
                new String[]{"application/javascript", "text/javascript", "text/plain", "application/octet-stream"}));
        toolbar.addView(coreButton);

        Button reloadButton = compactButton("重载");
        reloadButton.setOnClickListener(v -> loadStage());
        toolbar.addView(reloadButton);

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(235, 224, 246));
        statusText.setTextSize(10);
        statusText.setSingleLine(true);
        statusText.setText("v0.1 · 等待模型");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.setMarginStart(dp(5));
        toolbar.addView(statusText, statusParams);
        page.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        FrameLayout stage = new FrameLayout(this);
        webView = new WebView(this);
        stage.addView(webView, new FrameLayout.LayoutParams(
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
        heading.setText("Sen · Cubism 5 静态显示检查");
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
        Button vtsButton = panelButton("恢复VTS启动状态");
        vtsButton.setOnClickListener(v -> {
            applyVtsPreset = true;
            evaluateStage("window.SenStage&&window.SenStage.applyExpressions("
                    + new JSONArray(savedVtsExpressions) + ");");
        });
        controls.addView(vtsButton, weightedButtonParams());

        Button rawButton = panelButton("查看原始状态");
        rawButton.setOnClickListener(v -> {
            applyVtsPreset = false;
            loadStage();
        });
        controls.addView(rawButton, weightedButtonParams());

        Button reloadVtsButton = panelButton("重载VTS状态");
        reloadVtsButton.setOnClickListener(v -> {
            applyVtsPreset = true;
            loadStage();
        });
        controls.addView(reloadVtsButton, weightedButtonParams());
        panel.addView(controls);

        LinearLayout qualityControls = new LinearLayout(this);
        qualityControls.setOrientation(LinearLayout.HORIZONTAL);
        Button quality2kButton = panelButton("画质：原始2K");
        quality2kButton.setOnClickListener(v -> requestRenderQuality(QUALITY_2K, false, "手动切换原始2K"));
        qualityControls.addView(quality2kButton, weightedButtonParams());
        Button quality1kButton = panelButton("画质：1K兼容");
        quality1kButton.setOnClickListener(v -> requestRenderQuality(QUALITY_1K, false, "手动切换1K兼容"));
        qualityControls.addView(quality1kButton, weightedButtonParams());
        panel.addView(qualityControls);

        TextView expressionHeading = new TextView(this);
        expressionHeading.setText("ZIP 表情（点按后如需取消，请点“重载VTS状态”）");
        expressionHeading.setTextColor(Color.rgb(238, 207, 255));
        expressionHeading.setTextSize(12);
        expressionHeading.setPadding(0, dp(8), 0, dp(3));
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

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView() {
        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/model/", new WebViewAssetLoader.InternalStoragePathHandler(this, modelRoot))
                .addPathHandler("/runtime/", new WebViewAssetLoader.InternalStoragePathHandler(this, runtimeRoot))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.addJavascriptInterface(new StageBridge(), "AndroidStage");
        webView.setWebViewClient(new WebViewClientCompat() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
                                                              @NonNull WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
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
                List<String> detectedExpressions = registerExpressions(modelFile);
                List<String> detectedSaved = readSavedVtsExpressions(modelFile, detectedExpressions);
                String modelRelative = relativePath(importRoot, modelFile);
                String diagnostics = buildDiagnostics(modelFile, detectedExpressions, detectedSaved);

                postLoading("正在替换 App 内的旧模型…");
                deleteChildren(modelRoot);
                moveChildren(importRoot, modelRoot);
                prefs.edit()
                        .putString("model_path", modelRelative)
                        .remove("model_path_1k")
                        .putString("render_quality", QUALITY_2K)
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
                    hideLoading();
                    rebuildExpressionButtons();
                    updateSummary();
                    toastLong("模型导入成功：表情 " + detectedExpressions.size()
                            + " 个，VTS 启动预设 " + detectedSaved.size() + " 个");
                    loadStage();
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

    private void onCorePicked(Uri uri) {
        if (uri == null) return;
        showLoading("正在导入 Cubism Core…");
        executor.execute(() -> {
            File temporary = new File(runtimeRoot, "live2dcubismcore.importing.js");
            File target = new File(runtimeRoot, "live2dcubismcore.min.js");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new IOException("无法读取所选文件");
                copy(in, out);
                String head;
                try (InputStream check = new FileInputStream(temporary)) {
                    byte[] bytes = new byte[8192];
                    int count = check.read(bytes);
                    head = count <= 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8);
                }
                if (!head.contains("Live2DCubismCore") && !head.contains("CubismCore")) {
                    throw new IOException("所选文件不像 live2dcubismcore.min.js");
                }
                if (target.exists() && !target.delete()) throw new IOException("无法替换旧 Core");
                if (!temporary.renameTo(target)) throw new IOException("无法保存 Core");
                runOnUiThread(() -> {
                    hideLoading();
                    updateSummary();
                    toastLong("离线 Cubism Core 导入成功");
                    loadStage();
                });
            } catch (Throwable error) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
                runOnUiThread(() -> {
                    hideLoading();
                    String message = "Core 导入失败：" + readableError(error);
                    setStatus(message);
                    toastLong(message);
                });
            }
        });
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

    private void loadStage() {
        String quality = prefs.getString("render_quality", QUALITY_2K);
        String relative = prefs.getString("model_path", "");
        if (QUALITY_1K.equals(quality)) {
            String mobileRelative = prefs.getString("model_path_1k", "");
            if (!mobileRelative.isBlank() && new File(modelRoot, mobileRelative).isFile()) {
                relative = mobileRelative;
            } else {
                quality = QUALITY_2K;
            }
        }
        String url = STAGE_URL;
        if (!relative.isBlank() && new File(modelRoot, relative).isFile()) {
            String modelUrl = "https://appassets.androidplatform.net/model/" + encodePath(relative);
            JSONArray saved = applyVtsPreset ? new JSONArray(savedVtsExpressions) : new JSONArray();
            url += "?model=" + Uri.encode(modelUrl)
                    + "&saved=" + Uri.encode(saved.toString())
                    + "&quality=" + Uri.encode(quality);
        }
        stageLoadStartedAt = SystemClock.elapsedRealtime();
        setStatus(relative.isBlank() ? "等待导入模型 ZIP"
                : "正在启动 Cubism 5 渲染器 · " + (QUALITY_1K.equals(quality) ? "1K兼容" : "原始2K") + "…");
        webView.loadUrl(url);
    }

    private void requestRenderQuality(String quality, boolean automatic, String reason) {
        if (QUALITY_2K.equals(quality)) {
            prefs.edit().putString("render_quality", QUALITY_2K).apply();
            updateSummary();
            loadStage();
            return;
        }
        if (qualityFallbackInProgress) return;
        String originalRelative = prefs.getString("model_path", "");
        File originalModel = new File(modelRoot, originalRelative);
        if (originalRelative.isBlank() || !originalModel.isFile()) {
            toastLong("请先导入 Sen 模型 ZIP");
            return;
        }
        String savedVariant = prefs.getString("model_path_1k", "");
        if (!savedVariant.isBlank() && new File(modelRoot, savedVariant).isFile()) {
            prefs.edit().putString("render_quality", QUALITY_1K).apply();
            updateSummary();
            loadStage();
            return;
        }

        qualityFallbackInProgress = true;
        showLoading((automatic ? "检测到2K黑屏，" : "") + "正在生成1K兼容贴图…\n原始2K文件会完整保留");
        executor.execute(() -> {
            try {
                File variant = createOneKVariant(originalModel);
                String variantRelative = relativePath(modelRoot, variant);
                prefs.edit()
                        .putString("model_path_1k", variantRelative)
                        .putString("render_quality", QUALITY_1K)
                        .apply();
                runOnUiThread(() -> {
                    qualityFallbackInProgress = false;
                    hideLoading();
                    updateSummary();
                    toastLong((automatic ? "已自动" : "已") + "切换1K兼容模式 · " + reason);
                    loadStage();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    qualityFallbackInProgress = false;
                    hideLoading();
                    String message = "1K兼容贴图生成失败：" + readableError(error);
                    setStatus(message);
                    toastLong(message);
                });
            }
        });
    }

    private File createOneKVariant(File originalModel) throws Exception {
        File modelDirectory = originalModel.getParentFile();
        if (modelDirectory == null) throw new IOException("模型目录无效");
        String safeRoot = modelDirectory.getCanonicalPath() + File.separator;
        JSONObject modelJson = new JSONObject(readUtf8File(originalModel));
        JSONObject references = modelJson.optJSONObject("FileReferences");
        JSONArray textures = references == null ? null : references.optJSONArray("Textures");
        if (textures == null || textures.length() == 0) throw new IOException("model3 没有贴图列表");

        File variantDirectory = new File(modelDirectory, ".sen-mobile-1k");
        deleteRecursivelyIfExists(variantDirectory);
        if (!variantDirectory.mkdirs() && !variantDirectory.isDirectory()) {
            throw new IOException("无法创建1K贴图目录");
        }

        JSONArray mobileTextures = new JSONArray();
        for (int i = 0; i < textures.length(); i++) {
            String sourceRelative = textures.optString(i, "");
            File source = new File(modelDirectory, sourceRelative);
            if (!source.getCanonicalPath().startsWith(safeRoot) || !source.isFile()) {
                throw new IOException("找不到原始贴图 " + (i + 1));
            }
            postLoading("正在生成1K兼容贴图 " + (i + 1) + "/" + textures.length()
                    + "\n原始2K文件会完整保留");

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            if (largest <= 0) throw new IOException("无法读取贴图尺寸：" + source.getName());
            if (largest <= MOBILE_TEXTURE_MAX) {
                mobileTextures.put(sourceRelative);
                continue;
            }

            int sample = 1;
            while (largest / sample > MOBILE_TEXTURE_MAX) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (bitmap == null) throw new IOException("无法缩放贴图：" + source.getName());

            File target = new File(variantDirectory,
                    String.format(java.util.Locale.ROOT, "%02d-%s", i, source.getName()));
            try (OutputStream out = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("无法保存1K贴图：" + source.getName());
                }
            } finally {
                bitmap.recycle();
            }
            mobileTextures.put(relativePath(modelDirectory, target));
        }

        references.put("Textures", mobileTextures);
        String originalName = originalModel.getName();
        String variantName = originalName.replaceFirst("\\.model3\\.json$", ".mobile1k.model3.json");
        if (variantName.equals(originalName)) variantName = originalName + ".mobile1k.model3.json";
        File variant = new File(modelDirectory, variantName);
        writeUtf8File(variant, modelJson.toString(2));
        return variant;
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
                    button.setOnClickListener(v -> evaluateStage(
                            "window.SenStage&&window.SenStage.applyExpressions(["
                                    + JSONObject.quote(name) + "]);"));
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
        boolean localCore = new File(runtimeRoot, "live2dcubismcore.min.js").isFile();
        summaryText.setText(diagnostics + "\nCore："
                + (localCore ? "本地导入" : "Live2D 官方地址（联网）")
                + " · 当前画质："
                + (QUALITY_1K.equals(prefs.getString("render_quality", QUALITY_2K)) ? "1K兼容" : "原始2K"));
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

    private void evaluateStage(String script) {
        webView.evaluateJavascript(script, null);
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

    private String encodePath(String path) {
        String[] segments = path.replace('\\', '/').split("/");
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (result.length() > 0) result.append('/');
            result.append(Uri.encode(segment));
        }
        return result.toString();
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

    private void writeUtf8File(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
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
        loadingOverlay.setVisibility(View.GONE);
    }

    private String readableError(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
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
    protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private final class StageBridge {
        @JavascriptInterface
        public void onStageStatus(String status) {
            runOnUiThread(() -> {
                if (status != null && status.startsWith("模型已就绪")) {
                    long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - stageLoadStartedAt);
                    setStatus(status + " · "
                            + String.format(java.util.Locale.ROOT, "%.1fs", elapsed / 1000.0));
                } else {
                    setStatus(status == null ? "" : status);
                }
            });
        }

        @JavascriptInterface
        public void onStageError(String error) {
            runOnUiThread(() -> {
                // JavaScript errors are not always renderer errors. For example,
                // an optional sample button once crashed only when touch ended.
                String message = "舞台运行失败：" + (error == null ? "未知错误" : error);
                setStatus(message);
                toastLong(message);
            });
        }

        @JavascriptInterface
        public void onWebGlContextLost(String detail) {
            runOnUiThread(() -> {
                if (QUALITY_2K.equals(prefs.getString("render_quality", QUALITY_2K))) {
                    requestRenderQuality(QUALITY_1K, true,
                            detail == null ? "WebGL显存上下文丢失" : detail);
                } else {
                    String message = "1K模式仍发生 WebGL 上下文丢失："
                            + (detail == null ? "未知原因" : detail);
                    setStatus(message);
                    toastLong(message);
                }
            });
        }

        @JavascriptInterface
        public void onStageBlackFrame(String detail) {
            runOnUiThread(() -> {
                if (QUALITY_2K.equals(prefs.getString("render_quality", QUALITY_2K))) {
                    requestRenderQuality(QUALITY_1K, true,
                            detail == null ? "首帧采样全黑" : detail);
                } else {
                    String message = "1K首帧仍然全黑：" + (detail == null ? "未知原因" : detail);
                    setStatus(message);
                    toastLong(message);
                }
            });
        }
    }
}
