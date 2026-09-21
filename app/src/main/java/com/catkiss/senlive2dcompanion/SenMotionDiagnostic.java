package com.catkiss.senlive2dcompanion;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Forced one-by-one motion runner and compact on-device evidence collector. */
final class SenMotionDiagnostic {
    interface Listener {
        void onStep(String label, int index, int total);
        void onComplete(String report);
    }

    private static final String EV_SOURCE_COMMIT =
            "473a3563d91cd4708dd3683d0cff0dc6f522fe52";

    private final SenMotionMode mode;
    private final List<EvMotionPack.TestStep> steps;
    private final EvFaithfulMotionEngine faithful;
    private final SenNaturalMotionEngine adapted;
    private final float enhancedBodyStrength;
    private final Listener listener;
    private final StringBuilder report = new StringBuilder();

    private int stepIndex = -1;
    private float stepElapsed;
    private boolean finished;
    private StepMetrics metrics;

    SenMotionDiagnostic(SenMotionMode mode, EvMotionPack pack,
                        EvFaithfulMotionEngine faithful,
                        SenNaturalMotionEngine adapted,
                        float enhancedBodyStrength,
                        Listener listener) {
        this.mode = mode;
        List<EvMotionPack.TestStep> selectedSteps = new ArrayList<>(pack.diagnosticSteps());
        if (mode == SenMotionMode.EV_BODY_ENHANCED) {
            selectedSteps.addAll(pack.bodyDiagnosticSteps());
        }
        this.steps = selectedSteps;
        this.faithful = faithful;
        this.adapted = adapted;
        this.enhancedBodyStrength = enhancedBodyStrength;
        this.listener = listener;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        report.append("Sen E.V动作层真机诊断\n")
                .append("生成时间：").append(format.format(new Date())).append('\n')
                .append("测试模式：").append(mode.displayName).append(" (")
                .append(mode.id).append(")\n")
                .append("E.V参考提交：").append(EV_SOURCE_COMMIT).append('\n')
                .append(mode == SenMotionMode.EV_BODY_ENHANCED
                        ? String.format(Locale.ROOT,
                        "身体跟随强度：头部语义幅度的 %.0f%%；另附身体三轴 ±5°/±10°/±15° 直驱测试\n",
                        enhancedBodyStrength * 100.0f) : "")
                .append("判定说明：写入范围证明程序实际输出；Core变化帧证明至少有可见网格响应。")
                .append("最终观感仍以屏幕肉眼记录为准。\n\n");
    }

    void beforeFrame(float deltaSeconds) {
        if (finished) return;
        if (stepIndex < 0) {
            beginNextStep();
        } else if (stepElapsed >= steps.get(stepIndex).durationSeconds) {
            finishCurrentStep();
            beginNextStep();
        }
        if (!finished) stepElapsed += Math.max(0.0f, deltaSeconds);
    }

    void afterFrame(SenLive2DModel model) {
        if (finished || metrics == null) return;
        Map<String, Float> writes = mode == SenMotionMode.EV_FAITHFUL
                || mode == SenMotionMode.EV_BODY_ENHANCED
                ? faithful.getLastWrites() : adapted.getLastWrites();
        metrics.frames++;
        for (Map.Entry<String, Float> entry : writes.entrySet()) {
            metrics.maxWrite.merge(entry.getKey(), Math.abs(entry.getValue()), Math::max);
        }
        for (String parameter : metrics.expectedParameters) {
            if (!model.hasParameter(parameter)) {
                metrics.missingParameters.add(parameter);
                continue;
            }
            float value = model.getParameterValue(parameter);
            metrics.minimum.merge(parameter, value, Math::min);
            metrics.maximum.merge(parameter, value, Math::max);
        }
        int changed = model.countChangedVisibleDrawables();
        if (changed > 0) metrics.changedFrames++;
        metrics.maxChangedDrawables = Math.max(metrics.maxChangedDrawables, changed);
        metrics.visibleDrawables = Math.max(metrics.visibleDrawables,
                model.countVisibleDrawables());
    }

    void stop() {
        if (finished) return;
        if (metrics != null) finishCurrentStep();
        finish("用户提前停止；报告包含已执行项目。");
    }

    boolean isFinished() {
        return finished;
    }

    private void beginNextStep() {
        stepIndex++;
        if (stepIndex >= steps.size()) {
            finish("全部项目执行完毕。");
            return;
        }
        EvMotionPack.TestStep step = steps.get(stepIndex);
        stepElapsed = 0.0f;
        faithful.resetTransientState();
        adapted.resetTransientState();
        faithful.setDiagnosticKind(step.kind);
        adapted.setDiagnosticMode(true);
        adapted.setDiagnosticKind(step.kind);
        if (mode == SenMotionMode.EV_FAITHFUL || mode == SenMotionMode.EV_BODY_ENHANCED) {
            faithful.setBodyFollowStrength(mode == SenMotionMode.EV_BODY_ENHANCED
                    ? enhancedBodyStrength : 0.0f);
            faithful.setEnabled(true);
            adapted.setEnabled(false);
            prepareFaithful(step);
        } else {
            faithful.setEnabled(false);
            adapted.setEnabled(true);
            prepareAdapted(step);
        }
        Set<String> expected = faithful.expectedMappedParameters(step);
        if (mode == SenMotionMode.SEN_ADAPTED) {
            if ("ambient".equals(step.kind)) {
                expected.add("ParamBodyAngleX");
                expected.add("ParamBodyAngleY");
                expected.add("ParamBodyAngleZ");
            } else {
                if (expected.contains("ParamAngleX")) expected.add("ParamBodyAngleX");
                if (expected.contains("ParamAngleY")) expected.add("ParamBodyAngleY");
                if (expected.contains("ParamAngleZ")) expected.add("ParamBodyAngleZ");
            }
        } else if (mode == SenMotionMode.EV_BODY_ENHANCED
                && !"body".equals(step.kind)) {
            if (expected.contains("ParamAngleX")) expected.add("ParamBodyAngleX");
            if (expected.contains("ParamAngleY")) expected.add("ParamBodyAngleY");
            if (expected.contains("ParamAngleZ")) expected.add("ParamBodyAngleZ");
        }
        metrics = new StepMetrics(step, expected);
        if (listener != null) listener.onStep(step.label, stepIndex + 1, steps.size());
    }

    private void prepareFaithful(EvMotionPack.TestStep step) {
        faithful.setGaze("gaze".equals(step.kind) ? step.id : null);
        switch (step.kind) {
            case "blink": faithful.forceBlink(); break;
            case "pulse": faithful.playPulse(step.id); break;
            case "sustain": faithful.setPose(step.id); break;
            case "body": faithful.forceBodySweep(step.id, step.diagnosticValue); break;
            default: break;
        }
    }

    private void prepareAdapted(EvMotionPack.TestStep step) {
        adapted.setGaze("gaze".equals(step.kind) ? step.id : null);
        switch (step.kind) {
            case "blink": adapted.forceBlink(); break;
            case "pulse": adapted.playPulse(step.id); break;
            case "sustain": adapted.setPose(step.id); break;
            default: break;
        }
    }

    private void finishCurrentStep() {
        if (metrics == null) return;
        report.append(String.format(Locale.ROOT, "[%02d/%02d] %s\n",
                stepIndex + 1, steps.size(), metrics.step.label));
        report.append("  类型/ID：").append(metrics.step.kind).append(" / ")
                .append(metrics.step.id).append('\n');
        report.append(String.format(Locale.ROOT,
                "  采样：%d帧；Core可见网格变化 %d帧；单帧最多 %d/%d 个可见网格\n",
                metrics.frames, metrics.changedFrames, metrics.maxChangedDrawables,
                metrics.visibleDrawables));
        report.append("  预期参数：").append(metrics.expectedParameters).append('\n');
        report.append("  缺失参数：").append(metrics.missingParameters.isEmpty()
                ? "无" : metrics.missingParameters).append('\n');
        report.append("  最大写入绝对值：").append(metrics.maxWrite).append('\n');
        report.append("  Core参数范围：");
        boolean parameterMoved = false;
        if (metrics.minimum.isEmpty()) {
            report.append("无有效采样");
        } else {
            boolean first = true;
            for (String id : metrics.minimum.keySet()) {
                if (!first) report.append("；");
                first = false;
                report.append(id).append('=')
                        .append(String.format(Locale.ROOT, "%.4f..%.4f",
                                metrics.minimum.get(id), metrics.maximum.get(id)));
                if (metrics.maximum.get(id) - metrics.minimum.get(id) > .0005f) {
                    parameterMoved = true;
                }
            }
        }
        report.append("\n  预期参数随时间变化：").append(parameterMoved ? "是" : "否")
                .append('\n');
        String result;
        if (!metrics.missingParameters.isEmpty()
                && metrics.missingParameters.size() == metrics.expectedParameters.size()) {
            result = "不可用：所需参数全部缺失";
        } else if (parameterMoved && metrics.changedFrames > 0 && !metrics.maxWrite.isEmpty()) {
            result = "已响应：预期参数确实变化且Core可见网格发生变化";
        } else if (!parameterMoved && !metrics.maxWrite.isEmpty()) {
            result = "疑似无效：程序有写入，但预期参数没有形成可测变化（可能被钳位或覆盖）";
        } else if (!metrics.maxWrite.isEmpty()) {
            result = "需肉眼复核：参数已写入，但Core未报告可见网格变化";
        } else {
            result = "无有效写入：需检查曲线、时序或映射";
        }
        report.append("  自动结论：").append(result).append("\n\n");
        metrics = null;
    }

    private void finish(String reason) {
        if (finished) return;
        finished = true;
        faithful.setEnabled(false);
        faithful.setDiagnosticKind(null);
        adapted.setEnabled(false);
        adapted.setDiagnosticMode(false);
        adapted.setDiagnosticKind(null);
        report.append("结束：").append(reason).append('\n');
        if (listener != null) listener.onComplete(report.toString());
    }

    private static final class StepMetrics {
        final EvMotionPack.TestStep step;
        final Set<String> expectedParameters;
        final Set<String> missingParameters = new LinkedHashSet<>();
        final Map<String, Float> maxWrite = new LinkedHashMap<>();
        final Map<String, Float> minimum = new LinkedHashMap<>();
        final Map<String, Float> maximum = new LinkedHashMap<>();
        int frames;
        int changedFrames;
        int maxChangedDrawables;
        int visibleDrawables;

        StepMetrics(EvMotionPack.TestStep step, Set<String> expectedParameters) {
            this.step = step;
            this.expectedParameters = new LinkedHashSet<>(expectedParameters);
        }
    }
}
