package com.catkiss.senlive2dcompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SenVtsAppearance {
    static final class ArtMeshColor {
        final String id;
        final float[] multiply;
        final float[] screen;

        ArtMeshColor(String id, float[] multiply, float[] screen) {
            this.id = id;
            this.multiply = multiply;
            this.screen = screen;
        }
    }

    final List<ArtMeshColor> colors;

    private SenVtsAppearance(List<ArtMeshColor> colors) {
        this.colors = Collections.unmodifiableList(colors);
    }

    /** Builds a minimal in-code preset without bundling the owner's full VTS configuration. */
    static SenVtsAppearance fromEncoded(List<String[]> entries) {
        List<ArtMeshColor> colors = new ArrayList<>();
        try {
            for (String[] entry : entries) {
                if (entry == null || entry.length != 2) continue;
                String[] channels = entry[1].split("\\|", -1);
                if (entry[0].isEmpty() || channels.length != 2) continue;
                colors.add(new ArtMeshColor(
                        entry[0], parseRgba(channels[0]), parseRgba(channels[1])));
            }
        } catch (IOException | NumberFormatException error) {
            throw new IllegalArgumentException("内置服装颜色表无效", error);
        }
        return new SenVtsAppearance(colors);
    }

    static SenVtsAppearance parse(String text) throws IOException {
        try {
            JSONObject root = new JSONObject(text);
            String modelName = root.optString("Name", "");
            if (!modelName.toLowerCase(java.util.Locale.ROOT).contains("sen")) {
                throw new IOException("VTS配置不是Sen模型：" + modelName);
            }
            JSONObject details = root.optJSONObject("ArtMeshDetails");
            JSONArray entries = details == null ? null
                    : details.optJSONArray("ArtMeshMultiplyAndScreenColors");
            if (entries == null || entries.length() == 0) {
                throw new IOException("VTS配置中没有逐部件颜色数据");
            }

            List<ArtMeshColor> colors = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("ID", "").trim();
                String value = entry.optString("Value", "").trim();
                String[] channels = value.split("\\|", -1);
                if (id.isEmpty() || channels.length != 2) continue;
                colors.add(new ArtMeshColor(id, parseRgba(channels[0]), parseRgba(channels[1])));
            }
            if (colors.isEmpty()) throw new IOException("VTS逐部件颜色格式无效");
            return new SenVtsAppearance(colors);
        } catch (org.json.JSONException | NumberFormatException error) {
            throw new IOException("无法解析VTS逐部件颜色", error);
        }
    }

    String summary() {
        return "VTS逐部件染色 " + colors.size() + "项";
    }

    private static float[] parseRgba(String value) throws IOException {
        if (value.length() != 8) throw new IOException("颜色值不是RRGGBBAA格式：" + value);
        long rgba = Long.parseLong(value, 16);
        return new float[]{
                ((rgba >> 24) & 0xff) / 255.0f,
                ((rgba >> 16) & 0xff) / 255.0f,
                ((rgba >> 8) & 0xff) / 255.0f,
                (rgba & 0xff) / 255.0f
        };
    }
}
