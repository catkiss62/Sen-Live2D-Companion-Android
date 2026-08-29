package com.catkiss.senlive2dcompanion;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
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
    private static final long MAX_EXTRACTED_BYTES = 1_500_000_000L;
    private static final int MAX_ZIP_ENTRIES = 8_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> expressionNames = new ArrayList<>();
    private final List<String> savedVtsExpressions = new ArrayList<>();

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
    private boolean applyVtsPreset = true;
    private long nativeLoadStartedAt;

    private final ActivityResultLauncher<String[]> modelZipPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onModelZipPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        modelRoot = new File(getFilesDir(), "sen-live2d-model");
        importRoot = new File(getFilesDir(), "sen-import-temp");
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

        Button reloadButton = compactButton("重载原生模型");
        reloadButton.setOnClickListener(v -> loadNativeModel());
        toolbar.addView(reloadButton);

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(235, 224, 246));
        statusText.setTextSize(10);
        statusText.setSingleLine(true);
        statusText.setText("v0.2.1 · 原生 Cubism 等待模型");
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
        Button vtsButton = panelButton("恢复VTS启动状态");
        vtsButton.setOnClickListener(v -> applyExpressions(savedVtsExpressions));
        controls.addView(vtsButton, weightedButtonParams());

        Button rawButton = panelButton("查看原始状态");
        rawButton.setOnClickListener(v -> {
            applyVtsPreset = false;
            loadNativeModel();
        });
        controls.addView(rawButton, weightedButtonParams());

        Button reloadVtsButton = panelButton("重载VTS状态");
        reloadVtsButton.setOnClickListener(v -> {
            applyVtsPreset = true;
            loadNativeModel();
        });
        controls.addView(reloadVtsButton, weightedButtonParams());
        panel.addView(controls);

        TextView memoryNote = new TextView(this);
        memoryNote.setText("原始2K · 原生 OpenGL · 贴图 GL_LINEAR（无 mipmap）");
        memoryNote.setTextColor(Color.rgb(186, 164, 204));
        memoryNote.setTextSize(10);
        memoryNote.setPadding(dp(3), dp(3), 0, dp(3));
        panel.addView(memoryNote);

        TextView expressionHeading = new TextView(this);
        expressionHeading.setText("ZIP 表情（点按后如需取消，请点“重载VTS状态”）");
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
        List<String> startup = applyVtsPreset
                ? new ArrayList<>(savedVtsExpressions) : new ArrayList<>();
        glSurfaceView.queueEvent(() -> renderer.requestModel(modelFile, startup));
    }

    private void applyExpressions(List<String> names) {
        for (String name : names) {
            glSurfaceView.queueEvent(() -> renderer.applyExpression(name));
        }
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
                    button.setOnClickListener(v -> glSurfaceView.queueEvent(
                            () -> renderer.applyExpression(name)));
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
        summaryText.setText(diagnostics
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
            setStatus(detail + " · "
                    + String.format(java.util.Locale.ROOT, "%.1fs", elapsed / 1000.0));
            toastLong("Sen 原生模型已加载，请检查水印、头发和眼睛");
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
