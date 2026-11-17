package com.p145.advancedsettings.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 注入编排：部署脚本 / 保证运行时 / 计算 pid / 注入 / 校验 / 守护。
 * 一切都是通过本机 adb(root) 完成，App 本身不需要 root。
 */
public final class HookManager {

    public static final String TAG = "P145ADV";
    public static final String WORK = "/data/local/p145adv";
    /** 上一次防火墙巡检的结果（空 = 没巡检过） */
    public static volatile String lastFwLeft;
    public static final int SCRIPT_VERSION = 3;
    public static final int DEFAULT_INTERVAL = 5;

    private static final Object LOCK = new Object();
    private static final LinkedList<String> LOG = new LinkedList<>();
    private static final Map<String, Long> LAST_ATTEMPT = new HashMap<>();

    public static volatile boolean guardRunning = false;

    private static final String[] INJECTOR_CANDIDATES = {
            WORK + "/frida-inject16",
            "/data/local/ttsguard/frida-inject16",
            "/data/local/tmp/frida-inject16",
            "/data/local/tmp/frida-inject1723",
            "/sdcard/Download/frida-inject16"
    };

    private static final String[] SCRIPTS = {"hook_icc.js", "hook_volume_all.js"};

    public static final class TS {
        public int pid;
        public boolean injected;
        public String note = "未检测";
    }

    public static final class Status {
        public boolean adbOk;
        public boolean rootOk;
        public boolean fridaOk;
        public boolean guardRunning;
        public String adbDetail = "";
        public String rootDetail = "";
        public String fridaVer = "";
        public String fridaPath = "";
        public String lastError = "";
        /** 5555 端口的 iptables 规则数；-1 = 未知 */
        public int fwRules = -1;
        public boolean iccOn;
        public boolean volOn;
        public long at;
        public final LinkedHashMap<String, TS> targets = new LinkedHashMap<>();

        public TS t(String key) {
            TS s = targets.get(key);
            if (s == null) {
                s = new TS();
                targets.put(key, s);
            }
            return s;
        }

        public int injectedCount() {
            int n = 0;
            for (TS s : targets.values()) {
                if (s.injected) {
                    n++;
                }
            }
            return n;
        }

        public String json() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"adb\":").append(adbOk).append(',');
            sb.append("\"root\":").append(rootOk).append(',');
            sb.append("\"frida\":").append(fridaOk).append(',');
            sb.append("\"frida_ver\":\"").append(esc(fridaVer)).append("\",");
            sb.append("\"guard\":").append(guardRunning).append(',');
            sb.append("\"icc_on\":").append(iccOn).append(',');
            sb.append("\"vol_on\":").append(volOn).append(',');
            sb.append("\"hooks\":{");
            boolean first = true;
            for (Map.Entry<String, TS> e : targets.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                TS s = e.getValue();
                sb.append('"').append(e.getKey()).append("\":{\"pid\":").append(s.pid)
                        .append(",\"injected\":").append(s.injected)
                        .append(",\"note\":\"").append(esc(s.note)).append("\"}");
            }
            sb.append("},");
            sb.append("\"error\":\"").append(esc(lastError)).append("\",");
            sb.append("\"at\":").append(at);
            sb.append('}');
            return sb.toString();
        }
    }

    private HookManager() {
    }

    // ------------------------------------------------------------------ log

    public static void log(String line) {
        String s = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + line;
        synchronized (LOG) {
            LOG.addFirst(s);
            while (LOG.size() > 120) {
                LOG.removeLast();
            }
        }
        Log.i(TAG, line);
    }

    public static List<String> getLog() {
        synchronized (LOG) {
            return new ArrayList<>(LOG);
        }
    }

    public static void clearLog() {
        synchronized (LOG) {
            LOG.clear();
        }
    }

    // --------------------------------------------------------------- 部署

    private static boolean copyAsset(Context ctx, String name, File out) {
        try (InputStream in = ctx.getAssets().open(name); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            return true;
        } catch (Exception e) {
            log("读取内置脚本失败 " + name + ": " + e);
            return false;
        }
    }

    private static boolean deploy(Context ctx) {
        if (Prefs.getInt(ctx, Prefs.K_SCRIPT_VER, 0) == SCRIPT_VERSION) {
            return true;
        }
        File cache = ctx.getCacheDir();
        StringBuilder cmd = new StringBuilder();
        cmd.append("mkdir -p ").append(WORK).append("/logs; chmod 755 ").append(WORK).append(' ').append(WORK).append("/logs; ");
        for (String f : SCRIPTS) {
            File out = new File(cache, f);
            if (!copyAsset(ctx, f, out)) {
                return false;
            }
            cmd.append("cp ").append(out.getAbsolutePath()).append(' ').append(WORK).append('/').append(f).append("; ");
        }
        cmd.append("chmod 644 ").append(WORK).append("/*.js");
        // 目标进程(uid 1000)需要能追加命中日志
        cmd.append("; touch ").append(WORK).append("/hits.log; chmod 666 ").append(WORK).append("/hits.log");
        try {
            Root.sh(cmd.toString(), 20000);
        } catch (Exception e) {
            log("部署脚本失败: " + e.getMessage());
            return false;
        }
        String ls = Root.out("ls " + WORK + "/*.js 2>/dev/null", 5000);
        if (!ls.contains("hook_icc.js") || !ls.contains("hook_volume_all.js")) {
            log("脚本部署校验失败");
            return false;
        }
        Prefs.setInt(ctx, Prefs.K_SCRIPT_VER, SCRIPT_VERSION);
        log("脚本已部署 → " + WORK);
        return true;
    }

    private static String resolveInjector() {
        String own = WORK + "/frida-inject16";
        if (Root.test("test -x " + own, 4000)) {
            return own;
        }
        for (String c : INJECTOR_CANDIDATES) {
            if (own.equals(c)) {
                continue;
            }
            if (Root.test("test -f " + c, 4000)) {
                String cp = "mkdir -p " + WORK + " && cp " + c + " " + own + " && chmod 755 " + own;
                if (Root.test(cp, 90000)) {
                    log("运行时复制就位 → " + own);
                    return own;
                }
            }
        }
        return null;
    }

    private static String probeVersion(String path) {
        String v = Root.out(path + " --version 2>/dev/null", 8000).trim();
        if (v.isEmpty()) {
            v = "frida";
        }
        return v;
    }

    // ---------------------------------------------------------------- pid


    // -------------------------------------------------------------- 注入

    private static boolean inject(Targets.T t, int pid, long waitMs) {
        String logf = WORK + "/logs/" + t.key + ".log";
        String script = WORK + "/" + t.script;
        // 同步执行：frida-inject -e 加载完 agent 就会退出，直接拿它的输出做判定
        String cmd = "cd " + WORK + " && timeout 25 ./frida-inject16 -e -p " + pid + " -s " + script
                + " 2>&1 | tee " + logf;
        String out;
        try {
            out = Root.sh(cmd, (int) Math.max(waitMs, 40000)).out;
        } catch (Exception e) {
            log("注入异常 " + t.label + ": " + e.getMessage());
            return false;
        }
        if (out == null) {
            out = "";
        }
        log("注入输出 " + t.label + " :: " + firstLine(out));
        if (out.contains("hook ok")) {
            return true;
        }
        if (out.contains("fail ") || out.contains("Failed") || out.contains("Process not found")
                || out.contains("Unable") || out.contains("Error")) {
            return false;
        }
        return !out.trim().isEmpty();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        for (String l : s.split("\n")) {
            if (!l.trim().isEmpty()) {
                return l.trim();
            }
        }
        return "";
    }

    // --------------------------------------------------------------- 主流程

    /** 只读探测（不注入），UI 轮询用 */
    public static Status probe(Context ctx) {
        return run(ctx, false);
    }

    /** 完整同步：探测 + 按开关注入 */
    public static Status sync(Context ctx) {
        return run(ctx, true);
    }

    private static Status run(Context ctx, boolean doInject) {
        synchronized (LOCK) {
            Status st = new Status();
            st.iccOn = Prefs.getBool(ctx, Prefs.K_ICC, true);
            st.volOn = Prefs.getBool(ctx, Prefs.K_VOL, true);
            st.guardRunning = guardRunning;
            st.adbOk = false;
            try {
                // 一次连接拿齐：uid / 运行时是否存在 / 4 个目标 pid（降低守护开销）
                StringBuilder pc = new StringBuilder();
                pc.append("echo \"U=$(id -u)\"; ");
                pc.append("echo \"X=$(test -x ").append(WORK).append("/frida-inject16 && echo 1 || echo 0)\"; ");
                pc.append("echo \"F=$(iptables -L INPUT -n 2>/dev/null | grep -c 5555)\"; ");
                for (Targets.T t : Targets.ALL) {
                    pc.append("echo \"@").append(t.pkg).append("=$(pidof ").append(t.pkg).append(")\"; ");
                }
                Root.R probe = null;
                try {
                    probe = Root.sh(pc.toString(), 8000);
                } catch (Exception ignored) {
                }
                String probeOut = probe == null ? "" : probe.out;
                st.adbOk = probeOut.contains("U=");
                if (!st.adbOk) {
                    st.lastError = "adb 通道无响应";
                    st.at = System.currentTimeMillis();
                    return st;
                }
                st.adbDetail = AdbClient.HOST + ":" + AdbClient.PORT;

                String uid = "";
                boolean hasRuntime = false;
                Map<String, Integer> pids = new HashMap<>();
                for (String line : probeOut.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("U=")) {
                        uid = line.substring(2).trim();
                    } else if (line.startsWith("X=")) {
                        hasRuntime = "1".equals(line.substring(2).trim());
                    } else if (line.startsWith("F=")) {
                        try {
                            st.fwRules = Integer.parseInt(line.substring(2).trim());
                        } catch (Exception ignored) {
                        }
                    } else if (line.startsWith("@")) {
                        int eq = line.indexOf('=');
                        if (eq > 1) {
                            String pkg = line.substring(1, eq).trim();
                            String v = line.substring(eq + 1).trim();
                            if (!v.isEmpty()) {
                                try {
                                    pids.put(pkg, Integer.parseInt(v.split("\\s+")[0]));
                                } catch (Exception ignored2) {
                                }
                            }
                        }
                    }
                }
                st.rootOk = uid.contains("0");
                st.rootDetail = st.rootOk ? "uid=0 (root)" : ("uid=" + uid);

                String injector = hasRuntime ? (WORK + "/frida-inject16") : resolveInjector();
                st.fridaOk = injector != null;
                st.fridaPath = injector == null ? "" : injector;
                if (!st.fridaOk) {
                    st.lastError = "缺少 frida-inject16";
                    st.at = System.currentTimeMillis();
                    return st;
                }
                st.fridaVer = Prefs.getStr(ctx, Prefs.K_FRIDA_VER, "");
                if (st.fridaVer.isEmpty()) {
                    st.fridaVer = probeVersion(injector);
                    Prefs.setStr(ctx, Prefs.K_FRIDA_VER, st.fridaVer);
                }

                if (!deploy(ctx)) {
                    st.lastError = "脚本部署失败";
                }

                for (Targets.T t : Targets.ALL) {
                    TS ts = st.t(t.key);
                    Integer p = pids.get(t.pkg);
                    ts.pid = p == null ? 0 : p;
                }

                for (Targets.T t : Targets.ALL) {
                    boolean on = Targets.F_ICC.equals(t.feature) ? st.iccOn : st.volOn;
                    TS ts = st.t(t.key);
                    if (!on) {
                        ts.injected = false;
                        ts.note = "已关闭";
                        continue;
                    }
                    if (ts.pid == 0) {
                        ts.injected = false;
                        ts.note = "进程未运行";
                        continue;
                    }
                    int lastPid = Prefs.getInt(ctx, Prefs.K_LAST_PID + t.key, -1);
                    boolean lastOk = Prefs.getBool(ctx, Prefs.K_LAST_OK + t.key, false);
                    if (lastPid == ts.pid && lastOk) {
                        ts.injected = true;
                        ts.note = "已注入";
                        continue;
                    }
                    if (!doInject) {
                        ts.injected = lastOk && lastPid == ts.pid;
                        ts.note = ts.injected ? "已注入" : "待注入";
                        continue;
                    }
                    Long la = LAST_ATTEMPT.get(t.key);
                    if (la != null && System.currentTimeMillis() - la < 12000) {
                        ts.injected = lastOk && lastPid == ts.pid;
                        ts.note = ts.injected ? "已注入" : "重试中";
                        continue;
                    }
                    LAST_ATTEMPT.put(t.key, System.currentTimeMillis());
                    boolean ok = inject(t, ts.pid, st.guardRunning ? 9000 : 12000);
                    Prefs.setInt(ctx, Prefs.K_LAST_PID + t.key, ok ? ts.pid : -1);
                    Prefs.setBool(ctx, Prefs.K_LAST_OK + t.key, ok);
                    ts.injected = ok;
                    ts.note = ok ? "已注入" : "注入失败";
                }
            } catch (Exception e) {
                st.lastError = String.valueOf(e.getMessage());
                log("同步异常: " + e);
            }
            st.at = System.currentTimeMillis();
            writeStatusFile(st);
            return st;
        }
    }

    private static void writeStatusFile(Status st) {
        try {
            String json = st.json();
            Root.sh("mkdir -p " + WORK + " && printf '%s' '" + json + "' > " + WORK + "/status.json", 6000);
        } catch (Exception ignored) {
        }
    }

    // --------------------------------------------------------------- 动作

    public static void setFeature(Context ctx, String feature, boolean on) {
        if (Targets.F_ICC.equals(feature)) {
            Prefs.setBool(ctx, Prefs.K_ICC, on);
        } else {
            Prefs.setBool(ctx, Prefs.K_VOL, on);
        }
        if (on) {
            for (Targets.T t : Targets.of(feature)) {
                Prefs.setInt(ctx, Prefs.K_LAST_PID + t.key, -1);
                Prefs.setBool(ctx, Prefs.K_LAST_OK + t.key, false);
                LAST_ATTEMPT.remove(t.key);
            }
            log((on ? "开启" : "关闭") + " " + feature);
        } else {
            log("关闭 " + feature + " → 重启目标进程以卸载 hook");
            restartTargets(ctx, feature);
        }
    }

    /** 强杀目标进程，让系统重新拉起（同时清掉已注入的 hook） */
    public static int restartTargets(Context ctx, String feature) {
        StringBuilder sb = new StringBuilder();
        for (Targets.T t : Targets.of(feature)) {
            sb.append("for p in $(pidof ").append(t.pkg).append("); do kill -9 $p; done; ");
            Prefs.setInt(ctx, Prefs.K_LAST_PID + t.key, -1);
            Prefs.setBool(ctx, Prefs.K_LAST_OK + t.key, false);
        }
        try {
            Root.sh(sb.toString(), 10000);
            log("已重启目标进程: " + feature);
        } catch (Exception e) {
            log("重启目标失败: " + e.getMessage());
            return -1;
        }
        return 0;
    }

    public static int restartAll(Context ctx) {
        restartTargets(ctx, Targets.F_ICC);
        return restartTargets(ctx, Targets.F_VOL);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "/").replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }


}
