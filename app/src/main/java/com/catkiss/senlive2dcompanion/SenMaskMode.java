package com.catkiss.senlive2dcompanion;

enum SenMaskMode {
    DEFAULT_SINGLE("A", "默认单缓冲"),
    DYNAMIC_MULTI("B", "动态多缓冲"),
    HIGH_PRECISION("C", "高精度蒙版");

    final String code;
    final String label;

    SenMaskMode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    String displayName() {
        return code + " " + label;
    }

    static SenMaskMode fromPreference(String value) {
        if (value != null) {
            for (SenMaskMode mode : values()) {
                if (mode.name().equals(value)) return mode;
            }
        }
        return DYNAMIC_MULTI;
    }
}
