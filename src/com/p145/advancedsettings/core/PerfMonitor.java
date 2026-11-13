package com.p145.advancedsettings.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源利用率采集（CPU / 内存 / 磁盘 IO / 网络），按进程维度排行。
 *
 * 数据全部来自 /proc 与 xt_qtaguid（绕开 dumpsys meminfo —— 实测单次 7.5s 太慢），
 * 设备端脚本 assets/perf_collect.sh 输出结构化文本，Java 只负责解析和计算：
 *
 *   T <total_jiffies1> <ncpu>
 *   A <pid> <user> <cpuJiffies> <rssPages> <name...>
 *   I <pid> <ioBytes>
 *   N <uid> <netBytes>
 *   Z <total_jiffies2>
 *   J <pid> <cpuJiffies2>
 */
public final class PerfMonitor {

    public static final String TAG = "P145ADV";
    public static final String SCRIPT = "perf_collect.sh";
    private static final int SCRIPT_VER = 6;   // 5: 修 /proc/<pid>/stat 的 pid 取值（原来取到 "stat"）

    /** 四个排行维度 */
    public enum Dim {
        CPU("CPU"), MEM("内存"), DISK("磁盘"), NET("网络");

        public final String label;

        Dim(String l) {
            this.label = l;
        }
    }

    public static final class AppUsage {
        public int pid;
        public int uid = -1;
        public String name = "";
        public double cpu;      // 占整机 CPU 的百分比（所有核合计 = 100%）
        public double memMb;    // MB
        public double diskMb;   // MB（累计读写）
        public double netMb;    // MB（累计收发）

        public double value(Dim d) {
            switch (d) {
                case CPU:
                    return cpu;
                case MEM:
                    return memMb;
                case DISK:
                    return diskMb;
                default:
                    return netMb;
            }
        }

        public String display(Dim d) {
            switch (d) {
                case CPU:
                    return String.format("%.1f%%", cpu);
                case MEM:
                    return memMb >= 1024 ? String.format("%.2f GB", memMb / 1024) : String.format("%.0f MB", memMb);
                case DISK:
                    return diskMb >= 1024 ? String.format("%.2f GB", diskMb / 1024) : String.format("%.1f MB", diskMb);
                default:
                    return netMb >= 1024 ? String.format("%.2f GB", netMb / 1024) : String.format("%.2f MB", netMb);
            }
        }
    }

    public static final class Snapshot {
        public long at;
        public int processCount;
        public int ncpu = 4;
        /** 整机 CPU 使用率（与系统状态栏 / EVCC 同口径） */
        public double machineCpu;
        /** 物理内存总量 / 已用（MB） */
        public double memTotalMb;
        public double memUsedMb;
        public final List<AppUsage> apps = new ArrayList<>();
        public String error = "";

        /** 按维度排序后返回副本 */
        public List<AppUsage> sorted(Dim d, boolean desc) {
            List<AppUsage> l = new ArrayList<>(apps);
            final Dim dim = d;
            final boolean dsc = desc;
            Collections.sort(l, new Comparator<AppUsage>() {
                @Override
                public int compare(AppUsage a, AppUsage b) {
                    double va = a.value(dim);
                    double vb = b.value(dim);
                    int c = Double.compare(va, vb);
                    return dsc ? -c : c;
                }
            });
            return l;
        }

        /** 某个维度的合计（网络按 uid 去重，避免同一 uid 的多个进程重复累加） */
        public double total(Dim d) {
            if (d == Dim.NET) {
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                double n = 0;
                for (AppUsage a : apps) {
                    if (a.uid >= 0 && seen.add(a.uid)) {
                        n += a.netMb;
                    }
                }
                return n;
            }
            double s = 0;
            for (AppUsage a : apps) {
                s += a.value(d);
            }
            return s;
        }
    }

    private PerfMonitor() {
    }

    // ------------------------------------------------------------ 脚本部署

    private static boolean deploy(Context ctx) {
        if (Prefs.getInt(ctx, "perf_script_ver", 0) == SCRIPT_VER) {
            return true;
        }
        try {
            File out = new File(ctx.getCacheDir(), SCRIPT);
            try (InputStream in = ctx.getAssets().open(SCRIPT);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            String dst = HookManager.WORK + "/" + SCRIPT;
            Root.R r = Root.sh("mkdir -p " + HookManager.WORK + " && cp " + out.getAbsolutePath() + " " + dst
                    + " && chmod 755 " + dst, 15000);
            if (!r.ok()) {
                Log.w(TAG, "perf 脚本部署失败: " + r.out);
                return false;
            }
            Prefs.setInt(ctx, "perf_script_ver", SCRIPT_VER);
            HookManager.log("性能采集脚本已部署");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "perf 脚本部署异常", e);
            return false;
        }
    }

    // ------------------------------------------------------------ 采集

    public static Snapshot collect(Context ctx) {
        Snapshot snap = new Snapshot();
        snap.at = System.currentTimeMillis();
        if (!deploy(ctx)) {
            snap.error = "采集脚本部署失败";
            return snap;
        }
        String out;
        try {
            Root.R r = Root.sh("sh " + HookManager.WORK + "/" + SCRIPT, 30000);
            out = r.out;
        } catch (Exception e) {
            snap.error = "执行采集脚本失败: " + e.getMessage();
            return snap;
        }
        if (out != null) {
            HookManager.log("性能采集: " + out.length() + " 字符 / " + out.split("\n").length + " 行");
        }
        parse(out, snap);
        HookManager.log("性能采集: 解析到 " + snap.apps.size() + " 个进程" + (snap.error.isEmpty() ? "" : " (" + snap.error + ")"));
        return snap;
    }

    private static void parse(String out, Snapshot snap) {
        if (out == null) {
            snap.error = "无输出";
            return;
        }
        long total1 = 0, total2 = 0, idle1 = 0, idle2 = 0;
        Map<Integer, AppUsage> map = new HashMap<>();
        Map<Integer, Long> j2 = new HashMap<>();
        Map<Integer, Long> uidNet = new HashMap<>();

        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith("M ")) {
                    String[] p = line.split("\\s+");
                    double totalKb = Long.parseLong(p[1]);
                    double availKb = Long.parseLong(p[2]);
                    snap.memTotalMb = totalKb / 1024.0;
                    snap.memUsedMb = (totalKb - availKb) / 1024.0;
                } else if (line.startsWith("T ")) {
                    String[] p = line.split("\\s+");
                    total1 = Long.parseLong(p[1]);
                    idle1 = Long.parseLong(p[2]);
                    snap.ncpu = Integer.parseInt(p[3]);
                } else if (line.startsWith("A ")) {
                    // A pid user jiffies rss name...
                    String[] p = line.split("\\s+", 6);
                    AppUsage u = new AppUsage();
                    u.pid = Integer.parseInt(p[1]);
                    u.uid = uidOf(p[2]);
                    u.memMb = Long.parseLong(p[3 + 1]) * 4.0 / 1024.0;   // rss pages → MB
                    u.name = p.length >= 6 ? p[5] : ("pid " + p[1]);
                    u.cpu = Long.parseLong(p[3]);                        // 暂存 jiffies1
                    map.put(u.pid, u);
                } else if (line.startsWith("I ")) {
                    String[] p = line.split("\\s+");
                    AppUsage u = map.get(Integer.parseInt(p[1]));
                    if (u != null) {
                        u.diskMb = Long.parseLong(p[2]) / 1048576.0;
                    }
                } else if (line.startsWith("N ")) {
                    String[] p = line.split("\\s+");
                    uidNet.put(Integer.parseInt(p[1]), Long.parseLong(p[2]));
                } else if (line.startsWith("Z ")) {
                    String[] p = line.split("\\s+");
                    total2 = Long.parseLong(p[1]);
                    idle2 = Long.parseLong(p[2]);
                } else if (line.startsWith("J ")) {
                    String[] p = line.split("\\s+");
                    j2.put(Integer.parseInt(p[1]), Long.parseLong(p[2]));
                }
            } catch (Exception ignored) {
            }
        }

        long dTotal = total2 - total1;
        long dIdle = idle2 - idle1;
        if (dTotal <= 0) {
            dTotal = 1;
        }
        if (dIdle < 0) {
            dIdle = 0;
        }
        // 整机使用率 = (总时间 - 空闲时间) / 总时间；口径与 top / 状态栏一致
        snap.machineCpu = (dTotal - dIdle) * 100.0 / dTotal;
        for (AppUsage u : map.values()) {
            Long v2 = j2.get(u.pid);
            double j1 = u.cpu;   // 前面暂存的 jiffies1
            if (v2 != null) {
                double dj = v2 - j1;
                if (dj < 0) {
                    dj = 0;
                }
                // 占「整机」百分比：所有核的时间之和为 100%，单进程最多 100%
                u.cpu = dj / (double) dTotal * 100.0;
            } else {
                u.cpu = 0;
            }
            if (u.uid >= 0) {
                Long net = uidNet.get(u.uid);
                if (net != null) {
                    u.netMb = net / 1048576.0;
                }
            }
            u.name = simplifyName(u.name);
            snap.apps.add(u);
        }
        snap.processCount = snap.apps.size();
        if (snap.apps.isEmpty()) {
            snap.error = "未采集到进程数据";
        }
    }

    private static int uidOf(String user) {
        if (user == null) {
            return -1;
        }
        try {
            if (user.startsWith("u0_a")) {
                return 10000 + Integer.parseInt(user.substring(4));
            }
            if ("system".equals(user)) {
                return 1000;
            }
            if ("root".equals(user)) {
                return 0;
            }
            if ("shell".equals(user)) {
                return 2000;
            }
            if ("radio".equals(user)) {
                return 1001;
            }
            if (user.startsWith("u0_")) {
                return -1;
            }
            return Integer.parseInt(user);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 把 "app_process /system/bin com.kooo.evcc.xxx" 之类压缩成好读的名字 */
    private static String simplifyName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        String s = raw.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) {
            // 取形如 com.xxx / cn.xxx 的 token
            for (String tok : s.split("\\s+")) {
                if (tok.contains(".") && !tok.startsWith("/") && !tok.startsWith("-")) {
                    s = tok;
                    break;
                }
            }
            if (s.indexOf(' ') > 0) {
                s = s.substring(0, s.indexOf(' '));
            }
        }
        if (s.length() > 42) {
            s = s.substring(0, 40) + "…";
        }
        return s;
    }
}
