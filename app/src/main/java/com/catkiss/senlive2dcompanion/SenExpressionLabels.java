package com.catkiss.senlive2dcompanion;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** UI-only bilingual labels for ZIP expression filenames; runtime expression IDs stay intact. */
final class SenExpressionLabels {
    private static final Map<String, String> CHINESE = createLabels();

    private SenExpressionLabels() { }

    static String displayName(String rawName) {
        if (rawName == null) return "";
        String original = rawName.trim();
        if (original.isEmpty()) return original;

        String normalized = original.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        String chinese = CHINESE.get(normalized);
        String suffix = "";
        if (chinese == null) {
            String withoutPrefix = normalized.replaceFirst("^(expression|expr|exp)", "");
            chinese = CHINESE.get(withoutPrefix);
            if (chinese == null) {
                String base = withoutPrefix.replaceFirst("[0-9]+$", "");
                suffix = withoutPrefix.substring(base.length());
                chinese = CHINESE.get(base);
            }
        }
        if (chinese == null) chinese = translateKnownWords(original);
        // Unknown abbreviations, proper names, Japanese terms and non-English filenames remain
        // untouched instead of receiving a guessed translation.
        if (chinese == null) return original;
        if (!suffix.isEmpty()) chinese += " " + suffix;
        return chinese + "\n" + original;
    }

    private static String translateKnownWords(String original) {
        String separated = original.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] words = separated.split("[^A-Za-z0-9]+");
        StringBuilder result = new StringBuilder();
        int translated = 0;
        for (String word : words) {
            if (word.isEmpty()) continue;
            String key = word.toLowerCase(Locale.ROOT);
            if (translated == 0
                    && ("expression".equals(key) || "expr".equals(key) || "exp".equals(key))) {
                continue;
            }
            String base = key.replaceFirst("[0-9]+$", "");
            String number = key.substring(base.length());
            String value = CHINESE.get(base);
            if (value == null) return null;
            if (result.length() > 0) result.append('·');
            result.append(value);
            if (!number.isEmpty()) result.append(' ').append(number);
            translated++;
        }
        return translated == 0 ? null : result.toString();
    }

    private static Map<String, String> createLabels() {
        Map<String, String> values = new HashMap<>();
        put(values, "normal", "普通");
        put(values, "neutral", "自然");
        put(values, "happy", "开心");
        put(values, "smile", "微笑");
        put(values, "laugh", "大笑");
        put(values, "excited", "兴奋");
        put(values, "love", "喜爱");
        put(values, "heart", "爱心");
        put(values, "hearteyes", "爱心眼");
        put(values, "shy", "害羞");
        put(values, "blush", "脸红");
        put(values, "embarrassed", "羞涩");
        put(values, "angry", "生气");
        put(values, "anger", "生气");
        put(values, "annoyed", "不满");
        put(values, "sad", "伤心");
        put(values, "cry", "哭泣");
        put(values, "crying", "哭泣");
        put(values, "tears", "眼泪");
        put(values, "scared", "害怕");
        put(values, "fear", "害怕");
        put(values, "worried", "担心");
        put(values, "confused", "疑惑");
        put(values, "question", "疑问");
        put(values, "disgust", "嫌弃");
        put(values, "serious", "认真");
        put(values, "surprised", "惊讶");
        put(values, "surprise", "惊讶");
        put(values, "shock", "震惊");
        put(values, "shocked", "震惊");
        put(values, "calm", "平静");
        put(values, "sleepy", "困倦");
        put(values, "sleep", "睡眠");
        put(values, "dizzy", "晕眩");
        put(values, "dizzyeyes", "晕眩眼");
        put(values, "sweat", "流汗");
        put(values, "pout", "撅嘴");
        put(values, "smug", "得意");
        put(values, "playful", "调皮");
        put(values, "wink", "眨单眼");
        put(values, "leftwink", "左眼眨眼");
        put(values, "rightwink", "右眼眨眼");
        put(values, "tongue", "吐舌");
        put(values, "tongueout", "吐舌");
        put(values, "stareyes", "星星眼");
        put(values, "starryeyes", "星星眼");
        put(values, "spiraleyes", "蚊香眼");
        put(values, "glasses", "眼镜");
        put(values, "sunglasses", "墨镜");
        put(values, "mask", "口罩");
        put(values, "microphone", "麦克风");
        put(values, "mic", "麦克风");
        put(values, "controller", "游戏手柄");
        put(values, "keyboardmouse", "键盘与鼠标");
        put(values, "loading", "加载中");
        put(values, "sulking", "闹别扭");
        put(values, "tearyeyes", "含泪眼");
        put(values, "weepyeyes", "泪汪汪");
        put(values, "press", "按压");
        put(values, "cat", "猫咪");
        put(values, "catears", "猫耳");
        put(values, "bunny", "兔女郎");
        put(values, "bunnyears", "兔耳");
        put(values, "halo", "光环");
        put(values, "horns", "角");
        put(values, "watermark", "水印");
        return values;
    }

    private static void put(Map<String, String> values, String english, String chinese) {
        values.put(english, chinese);
    }
}
