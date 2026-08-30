package com.catkiss.senlive2dcompanion;

final class SenRenderOptions {
    final SenMaskMode maskMode;
    final int highPrecisionMaskSize;
    final boolean earAngleOverrideEnabled;
    final float earAngleDegrees;
    final boolean ahogeShortened;
    final boolean ahogeRaised;

    SenRenderOptions(SenMaskMode maskMode, int highPrecisionMaskSize,
                     boolean earAngleOverrideEnabled, float earAngleDegrees,
                     boolean ahogeShortened, boolean ahogeRaised) {
        this.maskMode = maskMode == null ? SenMaskMode.HIGH_PRECISION : maskMode;
        this.highPrecisionMaskSize = normalizeMaskSize(highPrecisionMaskSize);
        this.earAngleOverrideEnabled = earAngleOverrideEnabled;
        this.earAngleDegrees = Math.max(-30.0f, Math.min(30.0f, earAngleDegrees));
        this.ahogeShortened = ahogeShortened;
        this.ahogeRaised = ahogeRaised;
    }

    SenRenderOptions withCustomization(boolean earEnabled, float earDegrees,
                                       boolean shortened, boolean raised) {
        return new SenRenderOptions(maskMode, highPrecisionMaskSize,
                earEnabled, earDegrees, shortened, raised);
    }

    private static int normalizeMaskSize(int size) {
        if (size >= 1024) return 1024;
        if (size >= 512) return 512;
        return 256;
    }
}
