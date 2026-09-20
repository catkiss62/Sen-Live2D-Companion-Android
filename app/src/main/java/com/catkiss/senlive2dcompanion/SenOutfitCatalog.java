package com.catkiss.senlive2dcompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stable outfit IDs accepted by {@link SenCompanionView#setOutfit(String)}. */
public final class SenOutfitCatalog {
    public static final String MAID = "maid";
    public static final String WHITE_SHIRT = "white_shirt";
    public static final String BUNNY = "bunny";
    public static final String UNDRESSED = "undressed";
    public static final List<String> ALL = Collections.unmodifiableList(
            Arrays.asList(MAID, WHITE_SHIRT, BUNNY, UNDRESSED));

    private SenOutfitCatalog() { }
}
