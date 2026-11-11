package com.p145.advancedsettings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.p145.advancedsettings.core.Commands;

/**
 * 广播控制入口：
 *   am broadcast -a com.p145.adv.STATUS
 *   am broadcast -a com.p145.adv.INJECT
 *   am broadcast -a com.p145.adv.CMD --es cmd vol_off
 * 也支持显式组件（不受后台广播限制）：
 *   am broadcast -n com.p145.advancedsettings/.CmdReceiver -a com.p145.adv.CMD --es cmd status
 */
public class CmdReceiver extends BroadcastReceiver {

    public static final String ACTION_RESULT = "com.p145.adv.RESULT";

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null || cmd.trim().isEmpty()) {
            cmd = fromAction(action);
        }
        final String c = cmd == null ? "status" : cmd.trim().toLowerCase();
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String json = "";
                try {
                    json = Commands.handle(app, c);
                } catch (Throwable t) {
                    com.p145.advancedsettings.core.HookManager.log("广播命令异常 " + c + ": " + t);
                } finally {
                    try {
                        Intent res = new Intent(ACTION_RESULT);
                        res.putExtra("cmd", c);
                        res.putExtra("json", json);
                        res.setPackage(app.getPackageName());
                        app.sendBroadcast(res);
                    } catch (Throwable ignored) {
                    }
                    pr.finish();
                }
            }
        }, "adv-cmd").start();
    }

    private static String fromAction(String action) {
        if (action == null) {
            return "status";
        }
        String a = action.substring(action.lastIndexOf('.') + 1).toLowerCase();
        switch (a) {
            case "icc_on":
            case "icc_off":
            case "vol_on":
            case "vol_off":
            case "inject":
            case "detach":
            case "start":
            case "stop":
                return a;
            case "cmd":
            case "status":
            default:
                return "status";
        }
    }
}
