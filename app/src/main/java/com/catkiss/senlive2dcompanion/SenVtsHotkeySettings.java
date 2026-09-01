package com.catkiss.senlive2dcompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Reads the original VTube Studio hotkey semantics shipped beside the model. */
final class SenVtsHotkeySettings {
    static final class Rule {
        final String action;
        final float fadeSeconds;
        final boolean deactivateAfterSeconds;
        final float deactivateAfterSecondsAmount;

        Rule(String action, float fadeSeconds, boolean deactivateAfterSeconds,
             float deactivateAfterSecondsAmount) {
            this.action = action;
            this.fadeSeconds = fadeSeconds;
            this.deactivateAfterSeconds = deactivateAfterSeconds;
            this.deactivateAfterSecondsAmount = deactivateAfterSecondsAmount;
        }
    }

    private final Map<String, Rule> rulesByFile = new HashMap<>();

    static SenVtsHotkeySettings load(File modelDirectory) {
        SenVtsHotkeySettings result = new SenVtsHotkeySettings();
        if (modelDirectory == null) return result;
        File[] files = modelDirectory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT)
                    .endsWith(".vtube.json")) continue;
            try {
                JSONObject root = new JSONObject(new String(
                        NativeFileLoader.readFile(file), StandardCharsets.UTF_8));
                JSONArray hotkeys = root.optJSONArray("Hotkeys");
                if (hotkeys == null) continue;
                for (int i = 0; i < hotkeys.length(); i++) {
                    JSONObject hotkey = hotkeys.optJSONObject(i);
                    if (hotkey == null) continue;
                    String hotkeyFile = hotkey.optString("File", "").trim();
                    if (hotkeyFile.isEmpty()) continue;
                    float fade = (float) hotkey.optDouble("FadeSecondsAmount", -1.0);
                    boolean autoStop = hotkey.optBoolean("DeactivateAfterSeconds", false);
                    float autoStopSeconds = (float) hotkey.optDouble(
                            "DeactivateAfterSecondsAmount", 0.0);
                    result.rulesByFile.put(normalize(hotkeyFile), new Rule(
                            hotkey.optString("Action", ""), fade,
                            autoStop, autoStopSeconds));
                }
            } catch (Exception ignored) {
                // A missing or older VTS sidecar must not make an otherwise valid model fail.
            }
            break;
        }
        return result;
    }

    Rule forFile(String fileName) {
        if (fileName == null) return null;
        Rule direct = rulesByFile.get(normalize(fileName));
        if (direct != null) return direct;
        return rulesByFile.get(normalize(new File(fileName).getName()));
    }

    private static String normalize(String value) {
        return new File(value.replace('\\', '/')).getName().toLowerCase(Locale.ROOT);
    }
}
