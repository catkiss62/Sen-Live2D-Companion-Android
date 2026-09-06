package com.catkiss.senlive2dcompanion;

/**
 * Confirmed renderer baseline kept in one small class so the same values can be copied into the
 * AI companion project without carrying the old tuning UI or its SharedPreferences migrations.
 */
final class SenRenderOptions {
    static final SenMaskMode MASK_MODE = SenMaskMode.HIGH_PRECISION;
    static final int HIGH_PRECISION_MASK_SIZE = 512;
    static final float EAR_SPEED_PERCENT = 135.0f;
    static final float EAR_AMPLITUDE_PERCENT = 100.0f;

    static final float AHOGE_SCALE_PERCENT = 56.0f;
    static final float AHOGE_LENGTH_PERCENT = 100.0f;
    static final float AHOGE_WIDTH_PERCENT = 83.0f;
    static final float AHOGE_ROTATION_DEGREES = -49.0f;
    static final float AHOGE_OFFSET_X = -0.006f;
    static final float AHOGE_OFFSET_Y = 0.0f;
    static final String AHOGE_ANCHOR_JSON =
            "{\"schema\":\"sen-ahoge-anchor\",\"schemaVersion\":2,"
                    + "\"coordinateSystem\":\"Cubism model-local barycentric triangle coordinates\","
                    + "\"screenPixelsPersisted\":false,"
                    + "\"root\":{\"drawableId\":\"ArtMesh151\","
                    + "\"triangleVertexIds\":[8,8,8],\"barycentricWeights\":[1,0,0],"
                    + "\"capturedModelPoint\":[-0.007617833,2.1282744]},"
                    + "\"direction\":{\"drawableId\":\"ArtMesh151\","
                    + "\"triangleVertexIds\":[21,5,22],"
                    + "\"barycentricWeights\":[0.65576994,0.30497047,0.039259583],"
                    + "\"capturedModelPoint\":[-0.04180951,2.1795814]},"
                    + "\"rootCandidates\":[],\"directionCandidates\":[]}";

    final boolean autoIdleEnabled;

    SenRenderOptions(boolean autoIdleEnabled) {
        this.autoIdleEnabled = autoIdleEnabled;
    }
}
