package com.catkiss.senlive2dcompanion;

import java.util.List;

/** Stable semantic IDs available to an AI-companion host. */
public final class SenPerformanceCatalog {
    public static final List<String> EMOTIONS = SenPerformanceEngine.EMOTIONS;
    public static final List<String> ACTIONS = SenPerformanceEngine.ACTIONS;
    public static final List<String> MANUAL_ACTIONS = SenPerformanceEngine.MANUAL_TEST_ACTIONS;

    private SenPerformanceCatalog() { }
}
