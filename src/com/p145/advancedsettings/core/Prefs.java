package com.p145.advancedsettings.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用配置 */
public final class Prefs {

    private static final String NAME = "adv_settings";

    public static final String K_ICC = "icc_on";
    public static final String K_VOL = "vol_on";
    public static final String K_GUARD = "guard_on";
    public static final String K_INTERVAL = "poll_sec";
    public static final String K_SCRIPT_VER = "script_ver";
    public static final String K_LAST_PID = "pid_";
    public static final String K_LAST_OK = "ok_";
    public static final String K_FRIDA_VER = "frida_ver";

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean getBool(Context c, String k, boolean def) {
        return sp(c).getBoolean(k, def);
    }

    public static void setBool(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
    }

    public static int getInt(Context c, String k, int def) {
        return sp(c).getInt(k, def);
    }

    public static void setInt(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
    }

    public static String getStr(Context c, String k, String def) {
        return sp(c).getString(k, def);
    }

    public static void setStr(Context c, String k, String v) {
        sp(c).edit().putString(k, v).apply();
    }
}
