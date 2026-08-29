package com.catkiss.senlive2dcompanion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact VTube Studio snapshot used only by the static renderer parity test. */
final class SenVtsProfile {
    static final String SCHEMA = "sen-vts-profile";

    final String modelName;
    final int expectedParameterCount;
    final String snapshotName;
    final Map<String, Float> parameters;
    final List<String> activeExpressions;

    private SenVtsProfile(String modelName, int expectedParameterCount, String snapshotName,
                          Map<String, Float> parameters, List<String> activeExpressions) {
        this.modelName = modelName;
        this.expectedParameterCount = expectedParameterCount;
        this.snapshotName = snapshotName;
        this.parameters = Collections.unmodifiableMap(parameters);
        this.activeExpressions = Collections.unmodifiableList(activeExpressions);
    }

    static SenVtsProfile parse(String text) throws IOException {
        final JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException("参数包不是有效 JSON", error);
        }
        if (!SCHEMA.equals(root.optString("schema", ""))) {
            throw new IOException("不是 Sen VTS 参数包（schema 不匹配）");
        }
        if (root.optInt("schemaVersion", 0) != 1) {
            throw new IOException("暂不支持这个参数包版本");
        }

        JSONObject model = root.optJSONObject("model");
        String modelName = model == null ? "" : model.optString("modelName", "").trim();
        int expectedCount = model == null ? 0 : model.optInt("numberOfLive2DParameters", 0);
        if (!modelName.toLowerCase(java.util.Locale.ROOT).contains("sen customizable model")) {
            throw new IOException("参数包不是 Sen Customizable Model："
                    + (modelName.isEmpty() ? "未知模型" : modelName));
        }

        JSONArray snapshots = root.optJSONArray("snapshots");
        if (snapshots == null || snapshots.length() == 0) {
            throw new IOException("参数包中没有采集状态");
        }
        JSONObject snapshot = chooseSnapshot(snapshots);
        JSONArray list = snapshot.optJSONArray("live2DParameters");
        if (list == null || list.length() == 0) {
            throw new IOException("选中的状态没有 Live2D 参数");
        }
        if (list.length() > 5_000) throw new IOException("参数数量异常");

        Map<String, Float> parameters = new LinkedHashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").trim();
            if (name.isEmpty() || !item.has("value")) continue;
            double raw = item.optDouble("value", Double.NaN);
            if (Double.isFinite(raw)) parameters.put(name, (float) raw);
        }
        if (parameters.isEmpty()) throw new IOException("参数包中没有可用参数");

        List<String> active = new ArrayList<>();
        JSONArray expressions = snapshot.optJSONArray("expressions");
        if (expressions != null) {
            for (int i = 0; i < expressions.length(); i++) {
                JSONObject expression = expressions.optJSONObject(i);
                if (expression == null || !expression.optBoolean("active", false)) continue;
                String file = expression.optString("file", "").trim();
                if (!file.isEmpty() && !active.contains(file)) active.add(file);
            }
        }
        return new SenVtsProfile(modelName, expectedCount,
                snapshot.optString("name", "未命名状态"), parameters, active);
    }

    String summary() {
        return "VTS冻结状态：" + snapshotName + " · " + parameters.size() + "项"
                + (activeExpressions.isEmpty() ? ""
                : " · 活动预设" + activeExpressions.size() + "个");
    }

    private static JSONObject chooseSnapshot(JSONArray snapshots) throws IOException {
        JSONObject namedFallback = null;
        JSONObject last = null;
        for (int i = 0; i < snapshots.length(); i++) {
            JSONObject candidate = snapshots.optJSONObject(i);
            if (candidate == null) continue;
            last = candidate;
            if (containsActiveExpression(candidate, "xiaojingyu.exp3.json")) return candidate;
            String name = candidate.optString("name", "");
            if (namedFallback == null && (name.contains("小鲸鱼") || name.contains("正常待机"))) {
                namedFallback = candidate;
            }
        }
        if (namedFallback != null) return namedFallback;
        if (last != null) return last;
        throw new IOException("参数包中没有有效采集状态");
    }

    private static boolean containsActiveExpression(JSONObject snapshot, String target) {
        JSONArray expressions = snapshot.optJSONArray("expressions");
        if (expressions == null) return false;
        for (int i = 0; i < expressions.length(); i++) {
            JSONObject expression = expressions.optJSONObject(i);
            if (expression != null && expression.optBoolean("active", false)
                    && target.equalsIgnoreCase(expression.optString("file", ""))) return true;
        }
        return false;
    }
}
