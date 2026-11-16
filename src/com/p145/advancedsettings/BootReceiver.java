package com.p145.advancedsettings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.p145.advancedsettings.core.HookManager;
import com.p145.advancedsettings.core.Prefs;

/**
 * 开机/唤醒自启。
 * 这台车机不一定给第三方 App 发标准 BOOT_COMPLETED，所以同时监听 Geely / ECARX / Lynk 的
 * SYSTEM_READY、ACC_ON、WAKEUP、STR_RESUME 等厂商广播（清单里注册，收不到也无害）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        // LOCKED_BOOT_COMPLETED 在用户解锁前到达，此时 credential 加密存储还没挂载，
        // 读 SharedPreferences 可能拿不到值甚至抛异常 —— 用默认值兜底，别让广播挂掉。
        boolean guard = true;
        try {
            guard = Prefs.getBool(context, Prefs.K_GUARD, true);
        } catch (Throwable ignored) {
        }
        HookManager.log("开机/唤醒广播 " + action + " guard=" + guard);
        if (!guard) {
            return;
        }
        GuardService.start(context);
        // 广播里不方便做长任务，交给守护服务的第一次巡检即可（5 秒内）
    }
}
