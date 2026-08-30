package com.catkiss.senlive2dcompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in clothing presets for the purchased Sen model.
 *
 * <p>Handoff rule: these maps are deliberately not full VTS snapshots. The three source
 * profiles each contained 581 values, including tracking, physics, eye, arm and expression
 * outputs captured at one instant. Only stable clothing selectors and clothing colour overrides
 * belong here. Runtime order is VTS appearance base -> outfit -> performance -> native physics ->
 * tail/ahoge geometry.</p>
 *
 * <p>Private files used for offline comparison (never commit the originals):
 * Sen.vts-profile.json + Sen Customizable Model_2K.vtube.json (maid), 白衬衫.json + the same
 * colour table (white shirt), and 兔女郎.json + Sen Customizable Model_2K.vtube兔女郎.json
 * (bunny). Owner-confirmed corrections: ArtMesh210/276/1324/1689 use #444573 in every outfit;
 * the white shirt forces Hair_behindEar10 (CDI: Maid Headband) off.</p>
 */
final class SenOutfitPresets {
    static final class Preset {
        final String id;
        final String displayName;
        final Map<String, Float> parameterOverrides;
        final SenVtsAppearance appearance;

        private Preset(String id, String displayName, Map<String, Float> parameters,
                       SenVtsAppearance appearance) {
            this.id = id;
            this.displayName = displayName;
            this.parameterOverrides = Collections.unmodifiableMap(parameters);
            this.appearance = appearance;
        }
    }

    private static final String[][] COMMON_COLORS = {
            {"ArtMesh1077", "383763FF|000000FF"},
            {"ArtMesh1209", "4CAFDCFF|000000FF"},
            {"ArtMesh1211", "4CAFDCFF|000000FF"},
            {"ArtMesh1324", "444573FF|000000FF"},
            {"ArtMesh1333", "3500C7FF|000000FF"},
            {"ArtMesh1341", "5162A7FF|000000FF"},
            {"ArtMesh1344", "5162A7FF|000000FF"},
            {"ArtMesh1412", "5162A7FF|000000FF"},
            {"ArtMesh1413", "5162A7FF|000000FF"},
            {"ArtMesh1414", "5162A7FF|000000FF"},
            {"ArtMesh1415", "5162A7FF|000000FF"},
            {"ArtMesh1421", "6374B8FF|000000FF"},
            {"ArtMesh1422", "5162A7FF|000000FF"},
            {"ArtMesh1436", "6172B8FF|000000FF"},
            {"ArtMesh1437", "5162A7FF|000000FF"},
            {"ArtMesh1440", "5D6DB1FF|000000FF"},
            {"ArtMesh1447", "5162A7FF|000000FF"},
            {"ArtMesh1448", "5162A7FF|000000FF"},
            {"ArtMesh1449", "5162A7FF|000000FF"},
            {"ArtMesh1450", "5469BFFF|000000FF"},
            {"ArtMesh1451", "4E3C9FFF|000000FF"},
            {"ArtMesh1452", "4E3C9FFF|000000FF"},
            {"ArtMesh1453", "5162A7FF|000000FF"},
            {"ArtMesh1456", "5162A7FF|000000FF"},
            {"ArtMesh1457", "5162A7FF|000000FF"},
            {"ArtMesh1459", "5162A7FF|000000FF"},
            {"ArtMesh1460", "6289C8FF|000000FF"},
            {"ArtMesh1461", "5162A7FF|000000FF"},
            {"ArtMesh1464", "5162A7FF|000000FF"},
            {"ArtMesh1466", "5162A7FF|000000FF"},
            {"ArtMesh1475", "5469BFFF|000000FF"},
            {"ArtMesh1484", "596AB0FF|000000FF"},
            {"ArtMesh1491", "4E3C9FFF|000000FF"},
            {"ArtMesh151", "5162A7FF|000000FF"},
            {"ArtMesh1531", "5162A7FF|000000FF"},
            {"ArtMesh1533", "5162A7FF|000000FF"},
            {"ArtMesh1535", "5162A7FF|000000FF"},
            {"ArtMesh1537", "5162A7FF|000000FF"},
            {"ArtMesh1543", "4E3C9FFF|000000FF"},
            {"ArtMesh1575", "5162A7FF|000000FF"},
            {"ArtMesh1576", "5162A7FF|000000FF"},
            {"ArtMesh1578", "5162A7FF|000000FF"},
            {"ArtMesh1582", "6289C8FF|000000FF"},
            {"ArtMesh1584", "5162A7FF|000000FF"},
            {"ArtMesh1589", "5162A7FF|000000FF"},
            {"ArtMesh1590", "5162A7FF|000000FF"},
            {"ArtMesh160", "5162A7FF|000000FF"},
            {"ArtMesh1689", "444573FF|000000FF"},
            {"ArtMesh1739", "5162A7FF|000000FF"},
            {"ArtMesh1741", "3500C7FF|000000FF"},
            {"ArtMesh189", "5162A7FF|000000FF"},
            {"ArtMesh190", "5162A7FF|000000FF"},
            {"ArtMesh191", "5162A7FF|000000FF"},
            {"ArtMesh192", "5162A7FF|000000FF"},
            {"ArtMesh210", "444573FF|000000FF"},
            {"ArtMesh224", "1F1E46FF|000000FF"},
            {"ArtMesh276", "444573FF|000000FF"},
            {"ArtMesh287", "444573FF|000000FF"},
            {"ArtMesh318", "C7CFFFFF|000000FF"},
            {"ArtMesh334", "444573FF|000000FF"},
            {"ArtMesh335", "444573FF|000000FF"},
            {"ArtMesh366", "444573FF|000000FF"},
            {"ArtMesh417", "5162A7FF|000000FF"},
            {"ArtMesh418", "1F1E46FF|000000FF"},
            {"ArtMesh419", "1F1E46FF|000000FF"},
            {"ArtMesh420", "1F1E46FF|000000FF"},
            {"ArtMesh484", "301853FF|000000FF"},
            {"ArtMesh501", "1F1E46FF|000000FF"},
            {"ArtMesh620", "5162A7FF|000000FF"},
            {"ArtMesh629", "444573FF|000000FF"},
            {"ArtMesh723", "444573FF|000000FF"},
            {"ArtMesh726", "1F1E46FF|000000FF"},
            {"ArtMesh731", "383763FF|000000FF"},
            {"ArtMesh805", "C7CFFFFF|000000FF"},
            {"ArtMesh857", "14074DFF|000000FF"},
            {"ArtMesh879", "4CAFDCFF|000000FF"},
            {"ArtMesh910", "14074DFF|000000FF"},
            {"ArtMesh960", "7360C5FF|000000FF"}
    };

    static final Preset MAID = new Preset("maid", "女仆装", maidParameters(), colors(false));
    static final Preset WHITE_SHIRT = new Preset(
            "white_shirt", "白衬衫", whiteShirtParameters(), colors(false));
    static final Preset BUNNY = new Preset(
            "bunny", "兔女郎", bunnyParameters(), colors(true));

    static final List<Preset> ALL = Collections.unmodifiableList(
            Arrays.asList(MAID, WHITE_SHIRT, BUNNY));

    private SenOutfitPresets() { }

    static Preset fromId(String id) {
        for (Preset preset : ALL) if (preset.id.equals(id)) return preset;
        return MAID;
    }

    private static Map<String, Float> maidParameters() {
        Map<String, Float> values = new LinkedHashMap<>();
        clothing(values, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f,
                0.91401434f, 0.0f, 1.0f, 1.4948306f, 1.0f,
                0.75403f, 0.7540296f, 0.0f, 0.2f, 0.0f, 1.0f);
        return values;
    }

    private static Map<String, Float> whiteShirtParameters() {
        Map<String, Float> values = new LinkedHashMap<>();
        clothing(values, 0.0f, 0.0f, 2.835882f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.2f, 0.0f, 0.0f);
        return values;
    }

    private static Map<String, Float> bunnyParameters() {
        Map<String, Float> values = new LinkedHashMap<>();
        clothing(values, 0.0f, 0.0f, 2.835882f, 0.93261665f, 1.0f,
                0.0f, 0.0f, 1.0f, 1.185986f, 0.0f,
                0.82471985f, 0.0f, 10.0f, 0.5803234f, 1.3493168f, 1.0f);
        return values;
    }

    private static void clothing(Map<String, Float> values,
                                 float apronLower, float apronUpper, float bottom,
                                 float detachableCollar, float garter, float ribbonChoker,
                                 float shirt, float shirtCuffs, float shoes, float sleeve,
                                 float sockRight, float sockLeft, float sockLightness,
                                 float sockOpacity, float top, float maidHeadband) {
        values.put("ApronLower", apronLower);
        values.put("ApronUpper", apronUpper);
        values.put("Bottom", bottom);
        values.put("Detachable_Collar", detachableCollar);
        values.put("GarterR", garter);
        values.put("Ribbon_Choker", ribbonChoker);
        values.put("Shirt", shirt);
        values.put("Shirt_cuffs", shirtCuffs);
        values.put("Shoes", shoes);
        values.put("Sleeve", sleeve);
        values.put("SockR", sockRight);
        values.put("SockR2", sockLeft);
        values.put("SockR_Lightness", sockLightness);
        values.put("SockR_Opacity", sockOpacity);
        values.put("Top", top);
        values.put("Hair_behindEar10", maidHeadband);
    }

    private static SenVtsAppearance colors(boolean bunny) {
        List<String[]> entries = new ArrayList<>(Arrays.asList(COMMON_COLORS));
        if (bunny) {
            entries.add(new String[]{"ArtMesh1080", "515151FF|000000FF"});
            entries.add(new String[]{"ArtMesh1081", "343434FF|000000FF"});
            entries.add(new String[]{"ArtMesh489", "343434FF|000000FF"});
            entries.add(new String[]{"ArtMesh524", "343434FF|000000FF"});
            entries.add(new String[]{"ArtMesh588", "515151FF|000000FF"});
            entries.add(new String[]{"ArtMesh596", "4A4A4AFF|000000FF"});
            entries.add(new String[]{"ArtMesh732", "343434FF|000000FF"});
        } else {
            entries.add(new String[]{"ArtMesh1080", "302E5EFF|000000FF"});
            entries.add(new String[]{"ArtMesh1081", "1F1E46FF|000000FF"});
            entries.add(new String[]{"ArtMesh489", "FFFFFFFF|000000FF"});
            entries.add(new String[]{"ArtMesh524", "FFFFFFFF|000000FF"});
            entries.add(new String[]{"ArtMesh588", "302E5EFF|000000FF"});
            entries.add(new String[]{"ArtMesh596", "FFFFFFFF|000000FF"});
            entries.add(new String[]{"ArtMesh732", "1F1E46FF|000000FF"});
        }
        return SenVtsAppearance.fromEncoded(entries);
    }
}
