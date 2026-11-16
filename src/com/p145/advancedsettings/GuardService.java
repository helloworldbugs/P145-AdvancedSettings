package com.p145.advancedsettings;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.p145.advancedsettings.core.HookManager;
import com.p145.advancedsettings.core.Prefs;
import com.p145.advancedsettings.core.Targets;

public class GuardService extends Service {

    public static final String CHANNEL = "guard";
    public static final int NOTI_ID = 1001;
    public static final String ACTION_SYNC = "com.p145.adv.action.SYNC";

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean alive;
    /** 防火墙巡检计数（每 6 次 tick ≈ 30 秒检查一次） */
    private int fwCheckTick;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!alive) {
                return;
            }
            try {
                HookManager.Status st = HookManager.sync(GuardService.this);
                updateNotification(st);
            } catch (Throwable t) {
                HookManager.log("守护异常: " + t);
            }
            pollCommandFile();
            if (++fwCheckTick >= 6) {
                fwCheckTick = 0;
                ensureFirewallOpen();
            }
            int sec = HookManager.DEFAULT_INTERVAL;
            try {
                sec = Prefs.getInt(GuardService.this, Prefs.K_INTERVAL, HookManager.DEFAULT_INTERVAL);
            } catch (Throwable ignored) {
            }
            if (sec < 3) {
                sec = 3;
            }
            handler.postDelayed(this, sec * 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        HookManager.guardRunning = true;
        // 不管是开机广播、界面还是外部 am start-foreground-service 拉起来的，都视为「守护开启」
        try {
            Prefs.setBool(this, Prefs.K_GUARD, true);
        } catch (Throwable ignored) {
            // LOCKED_BOOT_COMPLETED 阶段 credential 存储还没挂载
        }
        createChannel();
        startForeground(NOTI_ID, buildNotification(null));
        thread = new HandlerThread("adv-guard");
        thread.start();
        handler = new Handler(thread.getLooper());
        HookManager.log("守护启动");
        handler.post(new Runnable() {
            @Override
            public void run() {
                runBootScript();
                ensureFirewallOpen();
            }
        });
        handler.post(tick);
    }
    /** 执行用户配置的开机自启脚本（/data/local/p145adv/boot.sh） */
    private void runBootScript() {
        boolean on = false;
        try {
            on = Prefs.getBool(this, Prefs.K_BOOT_ON, false);
        } catch (Throwable ignored) {
            // direct boot 阶段读不到
        }
        if (!on) {
            return;
        }
        String path = HookManager.WORK + "/boot.sh";
        try {
            com.p145.advancedsettings.core.Root.R r = com.p145.advancedsettings.core.Root.sh(
                    "if [ -x " + path + " ]; then sh " + path + " > " + HookManager.WORK + "/boot.log 2>&1; echo __done; fi",
                    60000);
            HookManager.log("开机脚本: " + (r.ok() ? "ok" : r.out));
        } catch (Throwable t) {
            HookManager.log("开机脚本失败: " + t);
        }
    }


    /**
     * 清掉车机开机时 setup_eth.sh 加的 5555 端口防火墙规则。
     *
     * /vendor/bin/setup_eth.sh 在 init 阶段用 `iptables -I INPUT` 把 tcp/udp 5555 DROP
     * 插到链首，会同时挡掉外部 adb 和本 App 自己用的 127.0.0.1:5555。
     * 本 App 的 AdbClient 走 IPv6 回环 ::1 绕过（ip6tables 没有对应规则），
     * 拿到 root shell 后把这两条 IPv4 规则删掉，外部 adb / scrcpy 才能恢复。
     *
     * 幂等：规则不存在时 iptables -D 只会报错，不影响。
     */
    /**
     * 巡检并清掉 setup_eth.sh 在开机时加的 5555 端口防火墙规则。
     *
     * /vendor/bin/setup_eth.sh 在 init 阶段执行：
     *     iptables -I INPUT -p tcp --dport 5555 -j DROP
     *     iptables -I INPUT -p udp --dport 5555 -j DROP
     * 会把外部 adb 和 App 自己用的 127.0.0.1:5555 一起挡掉。
     *
     * 本 App 的 AdbClient 走 IPv6 回环 ::1 绕过（ip6tables 没有 5555 规则，
     * 实测 /proc/net/tcp6 里能看到 ::1:C404 -> ::1:5555 的连接），
     * 拿到 root 后按 30 秒周期把这俩 IPv4 规则删掉。
     *
     * 幂等：用 `iptables -C` 先判断存在再删，避免刷错误日志。
     */
    private void ensureFirewallOpen() {
        if (!alive) {
            return;
        }
        try {
            String left = com.p145.advancedsettings.core.Root.out(
                    "iptables -C INPUT -p tcp --dport 5555 -j DROP 2>/dev/null && "
                    + "iptables -D INPUT -p tcp --dport 5555 -j DROP 2>/dev/null; "
                    + "iptables -C INPUT -p udp --dport 5555 -j DROP 2>/dev/null && "
                    + "iptables -D INPUT -p udp --dport 5555 -j DROP 2>/dev/null; "
                    + "iptables -L INPUT -n 2>/dev/null | grep -c 5555",
                    15000).trim();
            if (!"0".equals(left)) {
                HookManager.log("防火墙巡检: 仍有 " + left + " 条 5555 规则");
            } else if (HookManager.lastFwLeft == null || !"0".equals(HookManager.lastFwLeft)) {
                HookManager.log("防火墙巡检: 5555 端口已放行");
            }
            HookManager.lastFwLeft = left;
        } catch (Throwable t) {
            HookManager.log("防火墙巡检失败: " + t);
        }
    }
    /** 命令文件通道：写入 /data/local/p145adv/cmd 即可远程下发（任何 shell/脚本都能用） */
    private void pollCommandFile() {
        try {
            String file = HookManager.WORK + "/cmd";
            String c = com.p145.advancedsettings.core.Root.out(
                    "if [ -s " + file + " ]; then cat " + file + "; rm -f " + file + "; fi", 5000).trim();
            if (!c.isEmpty()) {
                com.p145.advancedsettings.core.Commands.handle(this, c.toLowerCase());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {        if (intent != null && ACTION_SYNC.equals(intent.getAction()) && handler != null) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        HookManager.guardRunning = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
        }
        HookManager.log("守护停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Hook 守护", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(HookManager.Status st) {
        String title = "高级设置 · 守护运行中";
        String text;
        if (st == null) {
            text = "正在检测…";
        } else if (!st.adbOk) {
            text = "adb 通道无响应";
        } else {
            int total = Targets.ALL.size();
            text = "已注入 " + st.injectedCount() + "/" + total + " · root " + (st.rootOk ? "正常" : "异常")
                    + " · " + st.fridaVer;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private String lastNotiText;

    private void updateNotification(HookManager.Status st) {
        try {
            Notification n = buildNotification(st);
            String t = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
            if (t.equals(lastNotiText)) {
                return; // 内容没变就不重发，省电也少刷日志
            }
            lastNotiText = t;
            getSystemService(NotificationManager.class).notify(NOTI_ID, n);
        } catch (Throwable ignored) {
        }
    }

    public static void start(android.content.Context c) {
        Intent i = new Intent(c, GuardService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            HookManager.log("启动守护失败: " + t);
        }
    }

    public static void stop(android.content.Context c) {
        c.stopService(new Intent(c, GuardService.class));
    }
}
