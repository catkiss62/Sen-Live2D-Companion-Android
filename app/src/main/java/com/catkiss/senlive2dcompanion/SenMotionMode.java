package com.catkiss.senlive2dcompanion;

/** Autonomous motion layers kept deliberately separate for device A/B testing. */
enum SenMotionMode {
    ORIGINAL("original", "原 Sen 自主待机"),
    EV_FAITHFUL("ev_faithful", "E.V 忠实动作层"),
    EV_BODY_ENHANCED("ev_body_enhanced", "E.V 大幅头部＋增强身体"),
    SEN_ADAPTED("sen_adapted", "Sen 适配动作层");

    final String id;
    final String displayName;

    SenMotionMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    static SenMotionMode fromId(String id) {
        for (SenMotionMode mode : values()) {
            if (mode.id.equals(id)) return mode;
        }
        return ORIGINAL;
    }
}
