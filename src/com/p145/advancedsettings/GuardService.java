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
            int sec = Prefs.getInt(GuardService.this, Prefs.K_INTERVAL, HookManager.DEFAULT_INTERVAL);
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
        Prefs.setBool(this, Prefs.K_GUARD, true);
        createChannel();
        startForeground(NOTI_ID, buildNotification(null));
        thread = new HandlerThread("adv-guard");
        thread.start();
        handler = new Handler(thread.getLooper());
        HookManager.log("守护启动");
        handler.post(tick);
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
