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
    private static final int SCRIPT_VER = 10;   // 7: 可选的 PSS 采集   // 5: 修 /proc/<pid>/stat 的 pid 取值（原来取到 "stat"）

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
        public double netMb;    // 保留字段（旧累计，已不用）
        public double upBps;    // 上行 B/s
        public double downBps;  // 下行 B/s

        public double value(Dim d) {
            switch (d) {
                case CPU:
                    return cpu;
                case MEM:
                    return memMb;
                case DISK:
                    return diskMb;
                default:
                    return upBps + downBps;
            }
        }

        public String display(Dim d) {
            switch (d) {
                case CPU:
                    return String.format("%.1f%%", cpu);
                case MEM:
                    return memMb >= 1024 ? String.format("%.2f GB", memMb / 1024) : String.format("%.0f MB", memMb);
                case DISK:
                    if (diskMb <= 0) { return "—"; }
                    return diskMb >= 1024 ? String.format("%.2f GB", diskMb / 1024) : String.format("%.1f MB", diskMb);
                default:
                    return "↑ " + rate(upBps) + "\n↓ " + rate(downBps);
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
        /** 整机网络速率（B/s） */
        public double netUpBps;
        public double netDownBps;
        /** /data 分区总量 / 已用（MB） */
        public double diskTotalMb;
        public double diskUsedMb;
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

    /** 进程名 → PSS(MB)：内存变化慢，每 N 次采集刷新一次，中间沿用缓存 */
    private static final Map<String, Double> PSS_CACHE = new HashMap<>();

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

    public static Snapshot collect(Context ctx, boolean withPss) {
        Snapshot snap = new Snapshot();
        snap.at = System.currentTimeMillis();
        if (!deploy(ctx)) {
            snap.error = "采集脚本部署失败";
            return snap;
        }
        String out;
        try {
            Root.R r = Root.sh("sh " + HookManager.WORK + "/" + SCRIPT + (withPss ? " pss" : ""), 40000);
            out = r.out;
        } catch (Exception e) {
            snap.error = "执行采集脚本失败: " + e.getMessage();
            return snap;
        }
        if (out != null) {
            HookManager.log("性能采集: " + out.length() + " 字符 / " + out.split("\n").length + " 行");
        }
        parse(out, snap, withPss);
        HookManager.log("性能采集: 解析到 " + snap.apps.size() + " 个进程" + (snap.error.isEmpty() ? "" : " (" + snap.error + ")"));
        return snap;
    }

    private static void parse(String out, Snapshot snap, boolean withPss) {
        if (out == null) {
            snap.error = "无输出";
            return;
        }
        long total1 = 0, total2 = 0, idle1 = 0, idle2 = 0;
        Map<Integer, AppUsage> map = new HashMap<>();
        Map<Integer, Long> j2 = new HashMap<>();
        Map<Integer, double[]> netBps = new HashMap<>();
        Map<Integer, Double> pssByPid = new HashMap<>();
        Map<String, Long> apkBytes = new HashMap<>();
        Map<String, Long> dataKb = new HashMap<>();

        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith("F ")) {
                    String[] p = line.split("\\s+");
                    snap.diskTotalMb = Long.parseLong(p[1]) / 1024.0;
                    snap.diskUsedMb = Long.parseLong(p[2]) / 1024.0;
                } else if (line.startsWith("S ")) {
                    String[] p = line.split("\\s+", 3);
                    apkBytes.put(p[1], Long.parseLong(p[2]));
                } else if (line.startsWith("D ")) {
                    String[] p = line.split("\\s+", 3);
                    dataKb.put(p[1], Long.parseLong(p[2]));
                } else if (line.startsWith("M ")) {
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
                } else if (line.startsWith("W ")) {
                    String[] p = line.split("\\s+");
                    snap.netUpBps = Long.parseLong(p[1]);
                    snap.netDownBps = Long.parseLong(p[2]);
                } else if (line.startsWith("NU ")) {
                    String[] p = line.split("\\s+");
                    netBps.put(Integer.parseInt(p[1]),
                            new double[]{Long.parseLong(p[2]), Long.parseLong(p[3])});
                } else if (line.startsWith("Z ")) {
                    String[] p = line.split("\\s+");
                    total2 = Long.parseLong(p[1]);
                    idle2 = Long.parseLong(p[2]);
                } else if (line.startsWith("P ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 3) {
                        pssByPid.put(Integer.parseInt(p[1]), Long.parseLong(p[2]) / 1024.0);
                    }
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
                double[] nr = netBps.get(u.uid);
                if (nr != null) {
                    u.upBps = nr[0];
                    u.downBps = nr[1];
                }
            }
            String pkg = u.name;
            int colon = pkg.indexOf(':');
            if (colon > 0) {
                pkg = pkg.substring(0, colon);
            }
            Long apk = apkBytes.get(pkg);
            Long dk = dataKb.get(pkg);
            if (apk != null || dk != null) {
                u.diskMb = (apk == null ? 0 : apk) / 1048576.0 + (dk == null ? 0 : dk) / 1024.0;
            } else {
                u.diskMb = 0;
            }

            // 内存：优先本轮的 PSS，其次缓存里的 PSS，最后才是 RSS
            Double fresh = pssByPid.get(u.pid);
            if (fresh != null && fresh > 0) {
                u.memMb = fresh;
                PSS_CACHE.put(u.name, fresh);
            } else {
                Double cached = PSS_CACHE.get(u.name);
                if (cached != null && cached > 0) {
                    u.memMb = cached;
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

    /** B/s → 自适应单位 */
    public static String rate(double bps) {
        if (bps >= 1048576) {
            return String.format("%.1f MB/s", bps / 1048576.0);
        }
        if (bps >= 1024) {
            return String.format("%.0f KB/s", bps / 1024.0);
        }
        return String.format("%.0f B/s", bps);
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
