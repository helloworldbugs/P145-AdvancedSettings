package com.p145.advancedsettings.core;

import android.content.Context;

import com.p145.advancedsettings.GuardService;

/**
 * 命令分发（广播 / 命令文件共用）。
 * 命令：status | start | stop | inject | detach | icc_on | icc_off | vol_on | vol_off
 */
public final class Commands {

    private Commands() {
    }

    public static String handle(Context ctx, String cmd) {
        HookManager.log("命令: " + cmd);
        try {
            Root.sh("mkdir -p " + HookManager.WORK + " && printf '%s %s\\n' \"$(date '+%F %T')\" '" + cmd
                    + "' >> " + HookManager.WORK + "/cmd.log", 6000);
        } catch (Exception ignored) {
        }
        switch (cmd) {
            case "start":
                Prefs.setBool(ctx, Prefs.K_GUARD, true);
                GuardService.start(ctx);
                return HookManager.probe(ctx).json();
            case "stop":
                Prefs.setBool(ctx, Prefs.K_GUARD, false);
                GuardService.stop(ctx);
                sleep(600);
                return HookManager.probe(ctx).json();
            case "inject":
                // 「立即注入」= 一键启动全部：先确保守护在跑（顺带把开关置开），再立刻同步注入
                Prefs.setBool(ctx, Prefs.K_GUARD, true);
                if (!HookManager.guardRunning) {
                    GuardService.start(ctx);
                    sleep(900);
                }
                return HookManager.sync(ctx).json();
            case "detach":
            case "restart":
                HookManager.restartAll(ctx);
                sleep(3500);
                return HookManager.probe(ctx).json();
            case "icc_on":
                HookManager.setFeature(ctx, Targets.F_ICC, true);
                sleep(600);
                return HookManager.sync(ctx).json();
            case "icc_off":
                HookManager.setFeature(ctx, Targets.F_ICC, false);
                sleep(2500);
                return HookManager.probe(ctx).json();
            case "vol_on":
                HookManager.setFeature(ctx, Targets.F_VOL, true);
                sleep(600);
                return HookManager.sync(ctx).json();
            case "vol_off":
                HookManager.setFeature(ctx, Targets.F_VOL, false);
                sleep(3000);
                return HookManager.probe(ctx).json();
            case "status":
            default:
                return HookManager.probe(ctx).json();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
