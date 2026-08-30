package com.catkiss.senlive2dcompanion;

final class SenRenderOptions {
    final SenMaskMode maskMode;
    final int highPrecisionMaskSize;
    final boolean earAngleOverrideEnabled;
    final float earAngleDegrees;
    final float earVerticalOffset;
    final float ahogeScalePercent;
    final float ahogeRotationDegrees;
    final float ahogeOffsetX;
    final float ahogeOffsetY;
    final boolean tailMirrored;

    SenRenderOptions(SenMaskMode maskMode, int highPrecisionMaskSize,
                     boolean earAngleOverrideEnabled, float earAngleDegrees,
                     float earVerticalOffset,
                     float ahogeScalePercent, float ahogeRotationDegrees,
                     float ahogeOffsetX, float ahogeOffsetY,
                     boolean tailMirrored) {
        this.maskMode = maskMode == null ? SenMaskMode.HIGH_PRECISION : maskMode;
        this.highPrecisionMaskSize = normalizeMaskSize(highPrecisionMaskSize);
        this.earAngleOverrideEnabled = earAngleOverrideEnabled;
        // The purchased model declares -30..+30, but VTS-compatible frozen values are written
        // through the Core view. Keep the test range requested by the owner so -50 can be
        // verified on-device without Framework-side clamping.
        this.earAngleDegrees = Math.max(-50.0f, Math.min(30.0f, earAngleDegrees));
        this.earVerticalOffset = Math.max(-50.0f, Math.min(20.0f, earVerticalOffset));
        this.ahogeScalePercent = Math.max(40.0f, Math.min(160.0f, ahogeScalePercent));
        this.ahogeRotationDegrees = Math.max(-90.0f, Math.min(90.0f, ahogeRotationDegrees));
        this.ahogeOffsetX = Math.max(-100.0f, Math.min(100.0f, ahogeOffsetX));
        this.ahogeOffsetY = Math.max(-100.0f, Math.min(100.0f, ahogeOffsetY));
        this.tailMirrored = tailMirrored;
    }

    SenRenderOptions withCustomization(boolean earEnabled, float earDegrees,
                                       float earOffset,
                                       float ahogeScale, float ahogeRotation,
                                       float ahogeX, float ahogeY,
                                       boolean mirrorTail) {
        return new SenRenderOptions(maskMode, highPrecisionMaskSize,
                earEnabled, earDegrees, earOffset,
                ahogeScale, ahogeRotation, ahogeX, ahogeY, mirrorTail);
    }

    private static int normalizeMaskSize(int size) {
        return size >= 1024 ? 1024 : 512;
    }
}
