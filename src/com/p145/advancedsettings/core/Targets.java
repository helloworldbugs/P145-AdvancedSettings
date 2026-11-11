package com.p145.advancedsettings.core;

import java.util.ArrayList;
import java.util.List;

/** 功能与目标进程定义 */
public final class Targets {

    public static final String F_ICC = "icc";
    public static final String F_VOL = "vol";

    public static final class T {
        public final String feature;
        public final String key;
        public final String pkg;
        public final String label;
        public final String script;

        T(String feature, String key, String pkg, String label, String script) {
            this.feature = feature;
            this.key = key;
            this.pkg = pkg;
            this.label = label;
            this.script = script;
        }
    }

    public static final T ICC = new T(F_ICC, "icc", "com.baidu.che.codriver", "DuerOS", "hook_icc.js");
    public static final T CAR = new T(F_VOL, "car", "com.android.car", "car", "hook_volume_all.js");
    public static final T KEY = new T(F_VOL, "key", "com.flyme.auto.keyserver", "keyserver", "hook_volume_all.js");
    public static final T KNOB = new T(F_VOL, "knob", "com.flyme.auto.knobservice", "knob", "hook_volume_all.js");

    public static final List<T> ALL = new ArrayList<>();

    static {
        ALL.add(ICC);
        ALL.add(CAR);
        ALL.add(KEY);
        ALL.add(KNOB);
    }

    public static List<T> of(String feature) {
        List<T> l = new ArrayList<>();
        for (T t : ALL) {
            if (t.feature.equals(feature)) {
                l.add(t);
            }
        }
        return l;
    }

    public static T byKey(String key) {
        for (T t : ALL) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }

    private Targets() {
    }
}
