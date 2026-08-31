package com.catkiss.senlive2dcompanion;

final class SenRenderOptions {
    final SenMaskMode maskMode;
    final int highPrecisionMaskSize;
    final boolean earAngleOverrideEnabled;
    final float earAngleDegrees;
    final float earVerticalOffset;
    final float ahogeScalePercent;
    final float ahogeLengthPercent;
    final float ahogeWidthPercent;
    final float ahogeRotationDegrees;
    final float ahogeOffsetX;
    final float ahogeOffsetY;
    final float ahogeRootFollowPercent;
    final float ahogeRootRotationPercent;
    final float ahogeLocalMotionPercent;
    final boolean ahogeNativePassthrough;
    final String ahogeAnchorJson;
    final boolean tailMirrored;
    final boolean autoIdleEnabled;

    SenRenderOptions(SenMaskMode maskMode, int highPrecisionMaskSize,
                     boolean earAngleOverrideEnabled, float earAngleDegrees,
                     float earVerticalOffset,
                     float ahogeScalePercent, float ahogeLengthPercent,
                     float ahogeWidthPercent,
                     float ahogeRotationDegrees,
                     float ahogeOffsetX, float ahogeOffsetY,
                     float ahogeRootFollowPercent, float ahogeRootRotationPercent,
                     float ahogeLocalMotionPercent,
                     boolean ahogeNativePassthrough, String ahogeAnchorJson,
                     boolean tailMirrored, boolean autoIdleEnabled) {
        this.maskMode = maskMode == null ? SenMaskMode.HIGH_PRECISION : maskMode;
        this.highPrecisionMaskSize = normalizeMaskSize(highPrecisionMaskSize);
        this.earAngleOverrideEnabled = earAngleOverrideEnabled;
        // The purchased model declares -30..+30, but VTS-compatible frozen values are written
        // through the Core view. Keep the test range requested by the owner so -50 can be
        // verified on-device without Framework-side clamping.
        this.earAngleDegrees = Math.max(-50.0f, Math.min(30.0f, earAngleDegrees));
        this.earVerticalOffset = Math.max(-50.0f, Math.min(20.0f, earVerticalOffset));
        this.ahogeScalePercent = Math.max(40.0f, Math.min(160.0f, ahogeScalePercent));
        this.ahogeLengthPercent = Math.max(40.0f, Math.min(160.0f, ahogeLengthPercent));
        this.ahogeWidthPercent = Math.max(40.0f, Math.min(160.0f, ahogeWidthPercent));
        this.ahogeRotationDegrees = Math.max(-90.0f, Math.min(90.0f, ahogeRotationDegrees));
        this.ahogeOffsetX = Math.max(-100.0f, Math.min(100.0f, ahogeOffsetX));
        this.ahogeOffsetY = Math.max(-100.0f, Math.min(100.0f, ahogeOffsetY));
        this.ahogeRootFollowPercent = clampPercent(ahogeRootFollowPercent);
        this.ahogeRootRotationPercent = clampPercent(ahogeRootRotationPercent);
        this.ahogeLocalMotionPercent = clampPercent(ahogeLocalMotionPercent);
        this.ahogeNativePassthrough = ahogeNativePassthrough;
        this.ahogeAnchorJson = ahogeAnchorJson == null ? "" : ahogeAnchorJson;
        this.tailMirrored = tailMirrored;
        this.autoIdleEnabled = autoIdleEnabled;
    }

    SenRenderOptions withCustomization(boolean earEnabled, float earDegrees,
                                       float earOffset,
                                       float ahogeScale, float ahogeLength,
                                       float ahogeWidth,
                                       float ahogeRotation,
                                       float ahogeX, float ahogeY,
                                       boolean mirrorTail) {
        return new SenRenderOptions(maskMode, highPrecisionMaskSize,
                earEnabled, earDegrees, earOffset,
                ahogeScale, ahogeLength, ahogeWidth, ahogeRotation, ahogeX, ahogeY,
                ahogeRootFollowPercent, ahogeRootRotationPercent,
                ahogeLocalMotionPercent,
                ahogeNativePassthrough, ahogeAnchorJson,
                mirrorTail, autoIdleEnabled);
    }

    SenRenderOptions withAhogeMotion(float rootFollowPercent,
                                     float rootRotationPercent,
                                     float localMotionPercent) {
        return new SenRenderOptions(maskMode, highPrecisionMaskSize,
                earAngleOverrideEnabled, earAngleDegrees, earVerticalOffset,
                ahogeScalePercent, ahogeLengthPercent, ahogeWidthPercent,
                ahogeRotationDegrees,
                ahogeOffsetX, ahogeOffsetY,
                rootFollowPercent, rootRotationPercent, localMotionPercent,
                ahogeNativePassthrough, ahogeAnchorJson,
                tailMirrored, autoIdleEnabled);
    }

    SenRenderOptions withAhogeNativePassthrough(boolean enabled) {
        return new SenRenderOptions(maskMode, highPrecisionMaskSize,
                earAngleOverrideEnabled, earAngleDegrees, earVerticalOffset,
                ahogeScalePercent, ahogeLengthPercent, ahogeWidthPercent,
                ahogeRotationDegrees,
                ahogeOffsetX, ahogeOffsetY,
                ahogeRootFollowPercent, ahogeRootRotationPercent,
                ahogeLocalMotionPercent, enabled, ahogeAnchorJson,
                tailMirrored, autoIdleEnabled);
    }

    SenRenderOptions withAhogeAnchorJson(String anchorJson) {
        return new SenRenderOptions(maskMode, highPrecisionMaskSize,
                earAngleOverrideEnabled, earAngleDegrees, earVerticalOffset,
                ahogeScalePercent, ahogeLengthPercent, ahogeWidthPercent,
                ahogeRotationDegrees, ahogeOffsetX, ahogeOffsetY,
                ahogeRootFollowPercent, ahogeRootRotationPercent,
                ahogeLocalMotionPercent, ahogeNativePassthrough, anchorJson,
                tailMirrored, autoIdleEnabled);
    }

    private static float clampPercent(float value) {
        return Math.max(0.0f, Math.min(100.0f, value));
    }

    private static int normalizeMaskSize(int size) {
        return size >= 1024 ? 1024 : 512;
    }
}
