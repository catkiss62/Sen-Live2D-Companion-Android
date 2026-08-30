package com.catkiss.senlive2dcompanion;

final class SenRenderOptions {
    final SenMaskMode maskMode;
    final int highPrecisionMaskSize;
    final boolean earAngleOverrideEnabled;
    final float earAngleDegrees;
    final float earVerticalOffset;
    final boolean ahogeShortened;
    final boolean ahogeRaised;

    SenRenderOptions(SenMaskMode maskMode, int highPrecisionMaskSize,
                     boolean earAngleOverrideEnabled, float earAngleDegrees,
                     float earVerticalOffset,
                     boolean ahogeShortened, boolean ahogeRaised) {
        this.maskMode = maskMode == null ? SenMaskMode.HIGH_PRECISION : maskMode;
        this.highPrecisionMaskSize = normalizeMaskSize(highPrecisionMaskSize);
        this.earAngleOverrideEnabled = earAngleOverrideEnabled;
        // The purchased model declares -30..+30, but VTS-compatible frozen values are written
        // through the Core view. Keep the test range requested by the owner so -50 can be
        // verified on-device without Framework-side clamping.
        this.earAngleDegrees = Math.max(-50.0f, Math.min(30.0f, earAngleDegrees));
        this.earVerticalOffset = Math.max(-50.0f, Math.min(20.0f, earVerticalOffset));
        this.ahogeShortened = ahogeShortened;
        this.ahogeRaised = ahogeRaised;
    }

    SenRenderOptions withCustomization(boolean earEnabled, float earDegrees,
                                       float earOffset,
                                       boolean shortened, boolean raised) {
        return new SenRenderOptions(maskMode, highPrecisionMaskSize,
                earEnabled, earDegrees, earOffset, shortened, raised);
    }

    private static int normalizeMaskSize(int size) {
        if (size >= 1024) return 1024;
        if (size >= 512) return 512;
        return 256;
    }
}
