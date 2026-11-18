package com.p145.advancedsettings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.p145.advancedsettings.core.HookManager;
import com.p145.advancedsettings.core.PerfMonitor;
import com.p145.advancedsettings.core.Prefs;
import com.p145.advancedsettings.core.Updater;
import com.p145.advancedsettings.core.Root;
import com.p145.advancedsettings.core.Targets;
import com.p145.advancedsettings.ui.PillSwitch;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int[] INTERVALS = {3, 5, 10, 15, 30};
    private static final String K_PAGE = "last_page";

    private static final String[] PAGES = {"overview", "voice", "volume", "perf", "cmd", "about"};
    private static final String[] TITLES = {"概览", "语音", "媒体", "性能", "命令", "关于"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private final Map<String, View> navs = new LinkedHashMap<>();
    private final Map<String, View> pages = new LinkedHashMap<>();

    private View dotAdb, dotRoot, dotFrida, dotIcc;
    private TextView tvAdb, tvAdbSub, tvRoot, tvRootSub, tvFrida, tvFridaSub;
    private View dotFw;
    // ---- 更新 ----
    private TextView tvUpdCur, tvUpdState, tvUpdNotes, btnUpdCheck, btnUpdInstall;
    private volatile Updater.Info updInfo;
    private volatile boolean updBusy;
    private TextView tvFw, tvFwSub;
    private TextView tvGuardPill, tvIccState, tvIccPid, tvChipCar, tvChipKey, tvChipKnob;
    private TextView tvGuardState, tvInterval, tvBc, tvFooter, tvSection;
    private TextView tvSumIcc, tvSumVol, tvAbout, tvLogTail;
    private PillSwitch swIcc, swVol, swGuard;

    // ---- 性能页 ----
    private LinearLayout listPerf;
    private TextView tvPerfStatus;
    private PerfMonitor.Snapshot perfSnap;
    private PerfMonitor.Dim perfDim = PerfMonitor.Dim.CPU;
    private boolean perfDesc = true;
    private boolean perfBusy;
    private boolean perfLoaded;
    private int perfCollectN;      // 每 3 次采集带一次 PSS
    private final Runnable perfTick = new Runnable() {
        @Override
        public void run() {
            int sec = 1;   // 采集本身要 4 秒左右，这里只作"忙则跳过"的节拍
            if (sec <= 0 || !"perf".equals(current)) {
                return;
            }
            loadPerf();
            ui.postDelayed(this, sec * 1000L);
        }
    };

    // ---- 命令页 ----
    private EditText edtCmd, edtBootScript;
    private TextView tvCmdOut, tvCmdState, tvBootState;
    private PillSwitch swBootScript;
    private static final String K_BOOT_SCRIPT = "boot_script";
    private static final String K_BOOT_ON = "boot_script_on";

    private TextView sumCpu, sumMem, sumDisk, sumNet;
    private android.widget.ProgressBar pbPerf;
    private final Map<PerfMonitor.Dim, TextView> hdrViews = new LinkedHashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();

    private volatile boolean busy;
    private boolean binding;
    private boolean firstResume = true;
    private String current = "overview";

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            ui.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        wireNav();
        wirePerf();
        wireCmd();
        wireUpdate();
        wire();
        applyPrefs();
        tvBc.setText(bcText());
        tvAbout.setText(aboutText());
        selectPage("overview", false);
        HookManager.log("界面启动");
    }

    // ----------------------------------------------------------- 基础绑定

    private void bindViews() {
        dotAdb = findViewById(R.id.dot_adb);
        dotRoot = findViewById(R.id.dot_root);
        dotFrida = findViewById(R.id.dot_frida);
        dotIcc = findViewById(R.id.dot_icc);
        tvAdb = findViewById(R.id.tv_adb);
        tvAdbSub = findViewById(R.id.tv_adb_sub);
        tvRoot = findViewById(R.id.tv_root);
        tvRootSub = findViewById(R.id.tv_root_sub);
        tvFrida = findViewById(R.id.tv_frida);
        tvFridaSub = findViewById(R.id.tv_frida_sub);
        dotFw = findViewById(R.id.dot_fw);
        tvFw = findViewById(R.id.tv_fw);
        tvFwSub = findViewById(R.id.tv_fw_sub);
        tvGuardPill = findViewById(R.id.tv_guard_pill);
        tvIccState = findViewById(R.id.tv_icc_state);
        tvIccPid = findViewById(R.id.tv_icc_pid);
        tvChipCar = findViewById(R.id.tv_chip_car);
        tvChipKey = findViewById(R.id.tv_chip_key);
        tvChipKnob = findViewById(R.id.tv_chip_knob);
        tvGuardState = findViewById(R.id.tv_guard_state);
        tvInterval = findViewById(R.id.tv_interval);
        tvBc = findViewById(R.id.tv_bc);
        tvFooter = findViewById(R.id.tv_footer);
        tvSection = findViewById(R.id.tv_section);
        tvSumIcc = findViewById(R.id.tv_sum_icc);
        tvSumVol = findViewById(R.id.tv_sum_vol);
        tvAbout = findViewById(R.id.tv_about);
        tvLogTail = findViewById(R.id.tv_log_tail);
        swIcc = findViewById(R.id.sw_icc);
        swVol = findViewById(R.id.sw_vol);
        swGuard = findViewById(R.id.sw_guard);
    }

    // ----------------------------------------------------------- 菜单栏

    private void wireNav() {
        navs.put("overview", findViewById(R.id.nav_overview));
        navs.put("voice", findViewById(R.id.nav_voice));
        navs.put("volume", findViewById(R.id.nav_volume));
        navs.put("perf", findViewById(R.id.nav_perf));
        navs.put("cmd", findViewById(R.id.nav_cmd));
        navs.put("about", findViewById(R.id.nav_about));

        pages.put("overview", findViewById(R.id.page_overview));
        pages.put("voice", findViewById(R.id.page_voice));
        pages.put("volume", findViewById(R.id.page_volume));
        pages.put("perf", findViewById(R.id.page_perf));
        pages.put("cmd", findViewById(R.id.page_cmd));
        pages.put("about", findViewById(R.id.page_about));

        for (final String key : PAGES) {
            View v = navs.get(key);
            if (v != null) {
                v.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        selectPage(key, true);
                    }
                });
            }
        }
        findViewById(R.id.row_sum_icc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPage("voice", true);
            }
        });
        findViewById(R.id.row_sum_vol).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPage("volume", true);
            }
        });
    }

    private void selectPage(String key, boolean remember) {
        if (!pages.containsKey(key)) {
            key = "overview";
        }
        current = key;
        for (String k : PAGES) {
            View p = pages.get(k);
            View n = navs.get(k);
            boolean on = k.equals(key);
            if (p != null) {
                p.setVisibility(on ? View.VISIBLE : View.GONE);
            }
            if (n != null) {
                n.setSelected(on);
                int txtId = getResources().getIdentifier("nav_txt_" + k, "id", getPackageName());
                int dotId = getResources().getIdentifier("nav_dot_" + k, "id", getPackageName());
                if (txtId != 0) {
                    ((TextView) n.findViewById(txtId))
                            .setTextColor(getResources().getColor(on ? R.color.text : R.color.text2));
                }
                if (dotId != 0) {
                    n.findViewById(dotId).setBackgroundResource(on ? R.drawable.dot_accent : R.drawable.dot_idle);
                }
            }
        }
        for (int i = 0; i < PAGES.length; i++) {
            if (PAGES[i].equals(key)) {
                tvSection.setText(TITLES[i]);
            }
        }
        ui.removeCallbacks(perfTick);
        if ("perf".equals(key)) {
            if (!perfLoaded || perfSnap == null || perfSnap.apps.isEmpty()) {
                perfLoaded = true;
                loadPerf();
            } else {
                renderPerf();
            }
            {
                ui.postDelayed(perfTick, 1000L);
            }
        }
        if (remember) {
            Prefs.setStr(this, K_PAGE, key);
        }
    }

    // ----------------------------------------------------------- 交互

    private void wire() {
        swIcc.setOnCheckedChangeListener(new PillSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChange(PillSwitch v, boolean c) {
                if (binding) {
                    return;
                }
                HookManager.setFeature(MainActivity.this, Targets.F_ICC, c);
                toast(c ? "语音播报屏蔽：开启" : "语音播报屏蔽：关闭");
                refresh(true);
            }
        });
        swVol.setOnCheckedChangeListener(new PillSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChange(PillSwitch v, boolean c) {
                if (binding) {
                    return;
                }
                HookManager.setFeature(MainActivity.this, Targets.F_VOL, c);
                toast(c ? "音量锁媒体：开启" : "音量锁媒体：关闭");
                refresh(true);
            }
        });
        swGuard.setOnCheckedChangeListener(new PillSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChange(PillSwitch v, boolean c) {
                if (binding) {
                    return;
                }
                Prefs.setBool(MainActivity.this, Prefs.K_GUARD, c);
                if (c) {
                    GuardService.start(MainActivity.this);
                } else {
                    GuardService.stop(MainActivity.this);
                }
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh(false);
                    }
                }, 500);
            }
        });

        tvInterval.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = Prefs.getInt(MainActivity.this, Prefs.K_INTERVAL, HookManager.DEFAULT_INTERVAL);
                int idx = 0;
                for (int i = 0; i < INTERVALS.length; i++) {
                    if (INTERVALS[i] == cur) {
                        idx = i;
                        break;
                    }
                }
                int next = INTERVALS[(idx + 1) % INTERVALS.length];
                Prefs.setInt(MainActivity.this, Prefs.K_INTERVAL, next);
                tvInterval.setText(next + " 秒");
            }
        });

        findViewById(R.id.btn_inject).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("正在注入…");
                refresh(true);
            }
        });

        findViewById(R.id.btn_detach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("重启目标进程")
                        .setMessage("将 kill 掉 4 个目标进程让系统重新拉起，已注入的 hook 会全部卸载，随后按开关状态重新注入。\n\n期间车机可能短暂无声，确认继续？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("重启", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                pool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        HookManager.restartAll(MainActivity.this);
                                        sleep(4000);
                                        refresh(true);
                                    }
                                });
                            }
                        }).show();
            }
        });

        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("p145-adv", bcText()));
                toast("命令已复制");
            }
        });

        findViewById(R.id.tv_log_link).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog();
            }
        });

        findViewById(R.id.btn_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog();
            }
        });


        wireDetail(R.id.btn_detail_icc, R.id.tv_detail_icc, "icc");
        wireDetail(R.id.btn_detail_vol, R.id.tv_detail_vol, "vol");

        tvGuardPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(false);
            }
        });
    }
    // ------------------------------------------------------------ 更新

    private void wireUpdate() {
        tvUpdCur = findViewById(R.id.tv_upd_cur);
        tvUpdState = findViewById(R.id.tv_upd_state);
        tvUpdNotes = findViewById(R.id.tv_upd_notes);
        btnUpdCheck = findViewById(R.id.btn_upd_check);
        btnUpdInstall = findViewById(R.id.btn_upd_install);

        tvUpdCur.setText("当前 v" + Updater.localVersionName(this)
                + " (" + Updater.localVersionCode(this) + ")");

        btnUpdCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        });
        btnUpdInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installUpdate();
            }
        });
    }

    private void checkUpdate() {
        if (updBusy) {
            return;
        }
        updBusy = true;
        btnUpdCheck.setEnabled(false);
        btnUpdInstall.setVisibility(View.GONE);
        tvUpdNotes.setVisibility(View.GONE);
        tvUpdState.setText("检查中…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Updater.Info info = Updater.check();
                final int local = Updater.localVersionCode(MainActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updBusy = false;
                        btnUpdCheck.setEnabled(true);
                        updInfo = info;
                        if (!info.ok) {
                            tvUpdState.setText("检查失败：" + info.error);
                            return;
                        }
                        if (info.versionCode > local) {
                            tvUpdState.setText("发现新版本 v" + info.versionName
                                    + "（当前 " + local + " → " + info.versionCode + "）");
                            tvUpdNotes.setText(info.notes);
                            tvUpdNotes.setVisibility(View.VISIBLE);
                            btnUpdInstall.setVisibility(View.VISIBLE);
                        } else {
                            tvUpdState.setText("已是最新（v" + info.versionName + "）");
                        }
                    }
                });
            }
        });
    }

    private void installUpdate() {
        final Updater.Info info = updInfo;
        if (info == null || !info.ok || updBusy) {
            return;
        }
        updBusy = true;
        btnUpdInstall.setEnabled(false);
        tvUpdState.setText("下载中…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String path = Updater.download(info, MainActivity.this);
                if (path == null) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updBusy = false;
                            btnUpdInstall.setEnabled(true);
                            tvUpdState.setText("下载失败，检查网络后重试");
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tvUpdState.setText("正在安装…");
                    }
                });
                final String err = Updater.install(path);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updBusy = false;
                        btnUpdInstall.setEnabled(true);
                        if (err == null) {
                            tvUpdState.setText("安装完成，重启 App 生效");
                            btnUpdInstall.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this,
                                    "已更新，重启 App 生效", Toast.LENGTH_LONG).show();
                        } else {
                            tvUpdState.setText("安装失败：" + err);
                        }
                    }
                });
            }
        });
    }
    // ------------------------------------------------------------ 命令页

    private void wireCmd() {
        edtCmd = findViewById(R.id.edt_cmd);
        edtCmd.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                // 软键盘「完成/回车」直接执行，省得再去点按钮
                runCmd();
                return true;
            }
        });
        edtBootScript = findViewById(R.id.edt_boot_script);
        tvCmdOut = findViewById(R.id.tv_cmd_out);
        tvCmdState = findViewById(R.id.tv_cmd_state);
        tvBootState = findViewById(R.id.tv_boot_state);
        swBootScript = findViewById(R.id.sw_boot_script);

        findViewById(R.id.btn_run_cmd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runCmd();
            }
        });
        findViewById(R.id.btn_clear_in).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edtCmd.setText("");
            }
        });
        findViewById(R.id.btn_clear_out).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvCmdOut.setText("（已清空）");
                tvCmdState.setText("");
            }
        });

        findViewById(R.id.chip_fw).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edtCmd.setText("iptables -L INPUT -n | head -40");
            }
        });
        findViewById(R.id.chip_df).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edtCmd.setText("df -h /data; echo ---; du -sh /data/data/* 2>/dev/null | sort -rh | head -10");
            }
        });
        findViewById(R.id.chip_bootlog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edtCmd.setText("logcat -d -v brief | grep -E P145ADV | tail -40");
            }
        });

        try {
            String saved = Prefs.getStr(this, K_BOOT_SCRIPT, "");
            if (saved != null) {
                edtBootScript.setText(saved);
            }
            swBootScript.setChecked(Prefs.getBool(this, K_BOOT_ON, false));
        } catch (Throwable ignored) {
        }

        swBootScript.setOnCheckedChangeListener(new PillSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChange(PillSwitch v, boolean c) {
                Prefs.setBool(MainActivity.this, K_BOOT_ON, c);
                saveBootScript(c ? "已启用" : "已停用");
            }
        });

        findViewById(R.id.btn_save_boot).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveBootScript("已保存");
            }
        });

        updateBootState();
    }

    private void runCmd() {
        final String cmd = edtCmd.getText().toString().trim();
        if (cmd.isEmpty()) {
            tvCmdState.setText("先输入命令");
            return;
        }
        tvCmdState.setText("执行中…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String out;
                long t0 = System.currentTimeMillis();
                try {
                    out = Root.out(cmd, 30000);
                } catch (Throwable e) {
                    out = "执行异常: " + e;
                }
                final String text = stripAnsi(out == null || out.isEmpty() ? "（无输出）" : out);
                final long cost = System.currentTimeMillis() - t0;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tvCmdOut.setText(text.length() > 20000 ? text.substring(0, 20000) + "\n…(截断)" : text);
                        tvCmdState.setText("完成 " + cost + " ms");
                    }
                });
            }
        });
    }

    /** 去掉命令输出里的 ANSI 颜色控制符，否则 TextView 里会显示乱码 */
    private static String stripAnsi(String s) {
        return s.replaceAll("\\u001B\\[[0-9;]*[A-Za-z]", "");
    }

    private void saveBootScript(final String okText) {
        final String script = edtBootScript.getText().toString();
        Prefs.setStr(this, K_BOOT_SCRIPT, script);
        Prefs.setBool(this, K_BOOT_ON, swBootScript.isChecked());
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String b64 = android.util.Base64.encodeToString(
                            script.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                    String path = HookManager.WORK + "/boot.sh";
                    Root.sh("mkdir -p " + HookManager.WORK, 8000);
                    Root.sh("echo " + b64 + " | base64 -d > " + path + " && chmod 755 " + path, 15000);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tvBootState.setText(okText + " · " + HookManager.WORK + "/boot.sh");
                        }
                    });
                } catch (Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tvBootState.setText("保存失败: " + e);
                        }
                    });
                }
            }
        });
    }

    /** 显示实际使用的 adb 通道：IPv6 回环优先，回退 IPv4 */
    private String adbChannel() {
        String h = com.p145.advancedsettings.core.AdbClient.lastHost;
        if (h == null || h.isEmpty()) {
            return "::1 / 127.0.0.1 :5555";
        }
        return h + ":5555";
    }

    private void updateBootState() {
        try {
            boolean on = Prefs.getBool(this, K_BOOT_ON, false);
            String s = Prefs.getStr(this, K_BOOT_SCRIPT, "");
            tvBootState.setText(on ? ("已启用 · " + (s.isEmpty() ? "脚本为空" : "开机执行")) : "未启用");
        } catch (Throwable ignored) {
        }
    }


    /** 标题下方的「展开详情」折叠区 */
    // ------------------------------------------------------------ 性能页

    private void wirePerf() {
        listPerf = findViewById(R.id.list_perf);
        tvPerfStatus = findViewById(R.id.tv_perf_status);
        sumCpu = findViewById(R.id.sum_cpu);
        sumMem = findViewById(R.id.sum_mem);
        sumDisk = findViewById(R.id.sum_disk);
        sumNet = findViewById(R.id.sum_net);
        pbPerf = findViewById(R.id.pb_perf);

        hdrViews.put(PerfMonitor.Dim.CPU, findViewById(R.id.hdr_cpu));
        hdrViews.put(PerfMonitor.Dim.MEM, findViewById(R.id.hdr_mem));
        hdrViews.put(PerfMonitor.Dim.DISK, findViewById(R.id.hdr_disk));
        hdrViews.put(PerfMonitor.Dim.NET, findViewById(R.id.hdr_net));

        for (final PerfMonitor.Dim d : PerfMonitor.Dim.values()) {
            TextView h = hdrViews.get(d);
            if (h == null) {
                continue;
            }
            h.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (perfDim == d) {
                        perfDesc = !perfDesc;
                    } else {
                        perfDim = d;
                        perfDesc = true;
                    }
                    renderPerf();
                }
            });
        }


        renderPerf();
    }


    private void loadPerf() {
        if (perfBusy) {
            return;
        }
        perfBusy = true;
        if (pbPerf != null) {
            pbPerf.setVisibility(View.VISIBLE);
        }
        if (tvPerfStatus != null) {
            tvPerfStatus.setText("正在采集进程数据…（约 3~5 秒）");
        }
        // 主采集永远不带 PSS：带 PSS 要 8~22 秒，会把界面卡成"假死"。
        // PSS 由 GuardService 每 10 分钟在后台预取一次，写进缓存供这里直接用。
        final boolean withPss = false;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final PerfMonitor.Snapshot s = PerfMonitor.collect(MainActivity.this, withPss);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        perfBusy = false;
                        perfSnap = s;
                        renderPerf();
                    }
                });
            }
        });
    }

    private void renderPerf() {
        if (pbPerf != null) {
            pbPerf.setVisibility(View.GONE);
        }
        // 表头：当前排序列高亮 + 箭头
        for (PerfMonitor.Dim d : PerfMonitor.Dim.values()) {
            TextView h = hdrViews.get(d);
            if (h == null) {
                continue;
            }
            boolean sel = (d == perfDim);
            String arrow = sel ? (perfDesc ? " ↓" : " ↑") : "";
            h.setText(d.label + arrow);
            h.setTextColor(color(sel ? R.color.accent : R.color.text2));
        }

        if (listPerf == null) {
            return;
        }
        listPerf.removeAllViews();

        if (perfSnap == null) {
            if (tvPerfStatus != null) {
                tvPerfStatus.setText(perfBusy ? "采集中…" : "点「刷新」开始采集");
            }
            return;
        }
        if (perfSnap.error != null && !perfSnap.error.isEmpty()) {
            tvPerfStatus.setText("采集失败：" + perfSnap.error);
            return;
        }

        // 汇总
        sumCpu.setText(String.format("%.0f%%", perfSnap.machineCpu));
        sumMem.setText(String.format("%.2f / %.1f GB", perfSnap.memUsedMb / 1024, perfSnap.memTotalMb / 1024));
        sumDisk.setText(String.format("%.1f / %.0f GB", perfSnap.diskUsedMb / 1024, perfSnap.diskTotalMb / 1024));
        sumNet.setText("↑ " + PerfMonitor.rate(perfSnap.netUpBps) + "\n↓ " + PerfMonitor.rate(perfSnap.netDownBps));

        List<PerfMonitor.AppUsage> list = perfSnap.sorted(perfDim, perfDesc);
        int limit = Math.min(24, list.size());
        for (int i = 0; i < limit; i++) {
            listPerf.addView(createPerfRow(list.get(i)));
        }

        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        int running = 0;
        for (PerfMonitor.AppUsage a : perfSnap.apps) {
            if (a.pid > 0) {
                running++;
            }
        }
        tvPerfStatus.setText(String.format("运行 %d · 共 %d 个应用 · 更新于 %s · 点表头换排序",
                running, perfSnap.processCount, f.format(new java.util.Date(perfSnap.at))));

    }
    /** 表格行：图标 + 应用名 + CPU / 内存 / 磁盘 / 网络 四列 */
    private View createPerfRow(PerfMonitor.AppUsage u) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(14), dp(12), dp(14));

        // 名称区：图标 + 名字，整体 weight 1 ���- 和表头的「应用 / 进程」列同宽
        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.HORIZONTAL);
        nameBox.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        Drawable d = appIcon(u.name);
        if (d != null) {
            icon.setImageDrawable(d);
        } else {
            icon.setImageResource(R.drawable.ic_launcher);
            icon.setAlpha(0.55f);
        }
        nameBox.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView tvName = new TextView(this);
        String label = appLabel(u.name);
        tvName.setText(label != null ? label : u.name);
        tvName.setTextSize(18);
        tvName.setTextColor(color(R.color.text));
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(10);
        nameBox.addView(tvName, np);

        row.addView(nameBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(cell(u.display(PerfMonitor.Dim.CPU), perfDim == PerfMonitor.Dim.CPU));
        row.addView(cell(u.display(PerfMonitor.Dim.MEM), perfDim == PerfMonitor.Dim.MEM));
        row.addView(cell(u.display(PerfMonitor.Dim.DISK), perfDim == PerfMonitor.Dim.DISK));
        row.addView(cell(u.display(PerfMonitor.Dim.NET), perfDim == PerfMonitor.Dim.NET));

        wrap.addView(row);

        View div = new View(this);
        div.setBackgroundColor(0x14FFFFFF);
        wrap.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    private TextView cell(String text, boolean highlight) {
        TextView tv = new TextView(this);
        tv.setText(text);
        if (text.indexOf('\n') >= 0) {
            tv.setTextSize(11);
            tv.setLineSpacing(0, 0.85f);
            tv.setSingleLine(false);
            tv.setMaxLines(2);
        } else {
            tv.setTextSize(17);
            tv.setSingleLine(true);
        }
        tv.setTextColor(color(highlight ? R.color.accent : R.color.text2));
        tv.setGravity(android.view.Gravity.RIGHT);
        tv.setPadding(dp(4), 0, 0, 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.25f));
        return tv;
    }

    /** 包名 → 应用名（带缓存） */
    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        if (labelCache.containsKey(pkg)) {
            return labelCache.get(pkg);
        }
        String label = null;
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence cs = getPackageManager().getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) {
                label = cs.toString();
            }
        } catch (Exception ignored) {
        }
        labelCache.put(pkg, label);
        return label;
    }

    /** 包名 → 图标（带缓存） */
    private Drawable appIcon(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        if (iconCache.containsKey(pkg)) {
            return iconCache.get(pkg);
        }
        Drawable d = null;
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            d = getPackageManager().getApplicationIcon(ai);
        } catch (Exception ignored) {
        }
        iconCache.put(pkg, d);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }


    private void wireDetail(final int toggleId, final int detailId, final String key) {
        final TextView toggle = findViewById(toggleId);
        final View detail = findViewById(detailId);
        if (toggle == null || detail == null) {
            return;
        }
        boolean open = Prefs.getBool(this, "exp_" + key, false);
        detail.setVisibility(open ? View.VISIBLE : View.GONE);
        toggle.setText(open ? "收起详情 ⌃" : "展开详情 ⌄");
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = detail.getVisibility() != View.VISIBLE;
                detail.setVisibility(now ? View.VISIBLE : View.GONE);
                toggle.setText(now ? "收起详情 ⌃" : "展开详情 ⌄");
                Prefs.setBool(MainActivity.this, "exp_" + key, now);
            }
        });
    }

    private String bcText() {
        return "am broadcast -a com.p145.adv.STATUS      # 查询状态\n"
                + "am broadcast -a com.p145.adv.INJECT      # 立即注入(含起守护)\n"
                + "am broadcast -a com.p145.adv.STOP        # 停守护\n"
                + "am broadcast -a com.p145.adv.ICC_OFF     # 关语音屏蔽\n"
                + "am broadcast -a com.p145.adv.CMD --es cmd vol_off\n"
                + "echo vol_on > /data/local/p145adv/cmd    # 命令文件\n"
                + "\n"
                + "# 兜底(不是广播)：被强行停止/开机时用这条，不弹界面\n"
                + "am start-foreground-service -n com.p145.advancedsettings/.GuardService";
    }

    private String aboutText() {
        return "包名　com.p145.advancedsettings\n"
                + "版本　2.4.2 (versionCode 17)\n"
                + "签名　平台公签 platform.pk8 / x509.pem\n"
                + "通道　" + adbChannel() + "（adbd root）\n"
                + "运行时　frida-inject 16.7.19\n"
                + "工作目录　/data/local/p145adv\n"
                + "状态文件　/data/local/p145adv/status.json\n"
                + "文档　D:\\ARM\\Auto\\P145车机改造记录.md";
    }

    private void applyPrefs() {
        binding = true;
        swIcc.setChecked(Prefs.getBool(this, Prefs.K_ICC, true));
        swVol.setChecked(Prefs.getBool(this, Prefs.K_VOL, true));
        swGuard.setChecked(Prefs.getBool(this, Prefs.K_GUARD, true));
        binding = false;
        tvInterval.setText(Prefs.getInt(this, Prefs.K_INTERVAL, HookManager.DEFAULT_INTERVAL) + " 秒");
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPrefs();
        if (Prefs.getBool(this, Prefs.K_GUARD, true)) {
            GuardService.start(this);
        }
        refresh(firstResume);
        firstResume = false;
        ui.removeCallbacks(periodic);
        ui.postDelayed(periodic, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(periodic);
    }

    // ------------------------------------------------------------ 刷新

    private void refresh(final boolean inject) {
        if (busy && !inject) {
            return;
        }
        busy = true;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final HookManager.Status st = inject ? HookManager.sync(MainActivity.this)
                        : HookManager.probe(MainActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        bind(st);
                    }
                });
            }
        });
    }

    private void bind(HookManager.Status st) {
        if (st == null) {
            return;
        }
        dotAdb.setBackgroundResource(st.adbOk ? R.drawable.dot_ok : R.drawable.dot_err);
        tvAdb.setText(st.adbOk ? "已连接" : "未连接");
        tvAdb.setTextColor(color(st.adbOk ? R.color.ok : R.color.err));
        tvAdbSub.setText(st.adbOk ? adbChannel() : (st.lastError.isEmpty() ? "adbd" : st.lastError));

        dotRoot.setBackgroundResource(st.rootOk ? R.drawable.dot_ok : R.drawable.dot_err);
        tvRoot.setText(st.rootOk ? "root" : "受限");
        tvRoot.setTextColor(color(st.rootOk ? R.color.ok : R.color.err));
        tvRootSub.setText(st.rootOk ? "uid=0 · adbd" : st.rootDetail);

        dotFrida.setBackgroundResource(st.fridaOk ? R.drawable.dot_ok : R.drawable.dot_warn);
        tvFrida.setText(st.fridaOk ? (st.fridaVer.isEmpty() ? "就绪" : st.fridaVer) : "缺失");
        tvFrida.setTextColor(color(st.fridaOk ? R.color.text : R.color.warn));
        tvFridaSub.setText(st.fridaOk ? "frida-inject16" : "需 frida-inject16");

        // 防火墙：5555 端口还剩几条 DROP 规则
        if (dotFw != null && tvFw != null) {
            if (st.fwRules < 0) {
                dotFw.setBackgroundResource(R.drawable.dot_idle);
                tvFw.setText("—");
                tvFw.setTextColor(color(R.color.text));
                tvFwSub.setText("5555 端口 · 未知");
            } else if (st.fwRules == 0) {
                dotFw.setBackgroundResource(R.drawable.dot_ok);
                tvFw.setText("已放行");
                tvFw.setTextColor(color(R.color.ok));
                tvFwSub.setText("5555 端口 · 0 条规则");
            } else {
                dotFw.setBackgroundResource(R.drawable.dot_warn);
                tvFw.setText("被拦截");
                tvFw.setTextColor(color(R.color.warn));
                tvFwSub.setText("5555 端口 · " + st.fwRules + " 条 DROP");
            }
        }

        boolean iccOn = Prefs.getBool(this, Prefs.K_ICC, true);
        boolean volOn = Prefs.getBool(this, Prefs.K_VOL, true);
        binding = true;
        swIcc.setChecked(iccOn);
        swVol.setChecked(volOn);
        swGuard.setChecked(Prefs.getBool(this, Prefs.K_GUARD, true));
        binding = false;

        findViewById(R.id.card_icc).setBackgroundResource(iccOn ? R.drawable.card_bg_on : R.drawable.card_bg);
        findViewById(R.id.card_vol).setBackgroundResource(volOn ? R.drawable.card_bg_on : R.drawable.card_bg);

        HookManager.TS icc = st.targets.get(Targets.ICC.key);
        if (icc == null) {
            setIcc("未检测", R.drawable.dot_idle);
        } else if (!iccOn) {
            setIcc("已关闭", R.drawable.dot_idle);
        } else if (icc.injected) {
            setIcc("已注入 · PID " + icc.pid, R.drawable.dot_ok);
        } else if (icc.pid == 0) {
            setIcc("目标进程未运行", R.drawable.dot_warn);
        } else {
            setIcc(icc.note.isEmpty() ? "未注入" : icc.note, R.drawable.dot_warn);
        }
        tvIccPid.setText(Targets.ICC.pkg);

        bindChip(tvChipCar, "car", st.targets.get(Targets.CAR.key), volOn);
        bindChip(tvChipKey, "keyserver", st.targets.get(Targets.KEY.key), volOn);
        bindChip(tvChipKnob, "knob", st.targets.get(Targets.KNOB.key), volOn);

        // 概览摘要
        String iccSum;
        if (!iccOn) {
            iccSum = "已关闭";
        } else if (icc != null && icc.injected) {
            iccSum = "已注入 · PID " + icc.pid;
        } else {
            iccSum = "未注入";
        }
        tvSumIcc.setText(iccSum);
        tvSumIcc.setTextColor(color((iccOn && icc != null && icc.injected) ? R.color.ok : R.color.text3));

        int volOk = 0;
        for (Targets.T t : Targets.of(Targets.F_VOL)) {
            HookManager.TS s = st.targets.get(t.key);
            if (s != null && s.injected) {
                volOk++;
            }
        }
        String volSum = !volOn ? "已关闭" : ("已注入 " + volOk + "/3");
        tvSumVol.setText(volSum);
        tvSumVol.setTextColor(color((volOn && volOk == 3) ? R.color.ok : R.color.text3));

        boolean running = st.guardRunning;
        tvGuardPill.setText(running ? "● 守护运行中" : "○ 守护未运行");
        tvGuardPill.setTextColor(color(running ? R.color.ok : R.color.text3));
        tvGuardState.setText(running
                ? "已注入 " + st.injectedCount() + "/" + Targets.ALL.size() + " · 每 "
                + Prefs.getInt(this, Prefs.K_INTERVAL, HookManager.DEFAULT_INTERVAL) + " 秒巡检"
                : "守护已关闭或未启动");

        tvFooter.setText(running ? "v2.4.2 · 守护运行中" : "v2.4.2 · 守护已停止");

        List<String> logs = HookManager.getLog();
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < Math.min(4, logs.size()); i++) {
            tail.append(logs.get(i)).append('\n');
        }
        tvLogTail.setText(tail.length() == 0 ? "(暂无日志)" : tail.toString().trim());
    }

    private void setIcc(String text, int dot) {
        tvIccState.setText(text);
        tvIccState.setTextColor(color(dot == R.drawable.dot_ok ? R.color.text : R.color.text2));
        dotIcc.setBackgroundResource(dot);
    }

    private void bindChip(TextView v, String label, HookManager.TS ts, boolean on) {
        boolean inj = on && ts != null && ts.injected;
        if (!on) {
            v.setText(label + " 关");
            v.setTextColor(color(R.color.text3));
        } else if (ts == null || ts.pid == 0) {
            v.setText(label + " 未运行");
            v.setTextColor(color(R.color.warn));
        } else if (inj) {
            v.setText(label + " " + ts.pid);
            v.setTextColor(color(R.color.ok));
        } else {
            v.setText(label + " " + ts.pid);
            v.setTextColor(color(R.color.warn));
        }
    }

    private int color(int id) {
        return getResources().getColor(id);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void showLog() {
        List<String> lines = HookManager.getLog();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("(暂无日志)");
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(0xFFB9CBD9);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(36, 28, 36, 28);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0C141C);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        HookManager.clearLog();
                    }
                })
                .show();
    }
}
