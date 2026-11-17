package com.p145.advancedsettings.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检测更新 / 下载 / 静默安装。
 *
 * 版本信息放在仓库根目录的 version.json：
 *   https://gitee.com/magiskalive/P145-AdvancedSettings/raw/main/version.json
 *   {"versionCode":14,"versionName":"2.3","url":"...apk","mirror":"...","notes":"..."}
 *
 * 安装走 root 的 `pm install -r`，不弹系统安装界面（AdbClient 本身就是 uid=0）。
 */
public final class Updater {

    public static final String TAG = "P145ADV";
    private static final String GITEE =
            "https://gitee.com/magiskalive/P145-AdvancedSettings/raw/main/version.json";
    private static final String GITHUB =
            "https://raw.githubusercontent.com/helloworldbugs/P145-AdvancedSettings/main/version.json";

    public static final class Info {
        public boolean ok;
        public String error = "";
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String mirror = "";
        public String notes = "";
    }

    private Updater() {
    }

    public static int localVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String localVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 拉 version.json（Gitee 优先，失败回退 GitHub raw） */
    public static Info check() {
        Info out = new Info();
        String body = httpGet(GITEE);
        if (body == null || body.isEmpty()) {
            body = httpGet(GITHUB);
        }
        if (body == null || body.isEmpty()) {
            out.error = "无法获取版本信息（网络不可用？）";
            return out;
        }
        try {
            JSONObject o = new JSONObject(body);
            out.versionCode = o.optInt("versionCode", 0);
            out.versionName = o.optString("versionName", "");
            out.url = o.optString("url", "");
            out.mirror = o.optString("mirror", "");
            out.notes = o.optString("notes", "");
            out.ok = out.versionCode > 0 && !out.url.isEmpty();
            if (!out.ok) {
                out.error = "version.json 格式不对";
            }
        } catch (Exception e) {
            out.error = "解析失败: " + e;
        }
        return out;
    }

    /** 下载 APK 到 cacheDir，返回文件路径；失败返回 null */
    public static String download(Info info, Context ctx) {
        String[] urls = new String[]{info.url, info.mirror};
        for (String u : urls) {
            if (u == null || u.isEmpty()) {
                continue;
            }
            File dst = new File(ctx.getCacheDir(), "update.apk");
            if (httpDownload(u, dst)) {
                return dst.getAbsolutePath();
            }
            Log.w(TAG, "下载失败: " + u);
        }
        return null;
    }

    /** root 静默安装：pm install -r，装完返回 null 表示成功 */
    public static String install(String apkPath) {
        try {
            Root.R r = Root.sh("pm install -r " + apkPath + " 2>&1", 120000);
            String o = r.out == null ? "" : r.out;
            if (o.contains("Success")) {
                return null;
            }
            return o.trim();
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    // ------------------------------------------------------------ HTTP

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "P145-AdvancedSettings");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) {
                return null;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "GET 失败: " + url + " -> " + t);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static boolean httpDownload(String url, File dst) {
        HttpURLConnection c = null;
        FileOutputStream fos = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "P145-AdvancedSettings");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) {
                return false;
            }
            if (dst.exists() && !dst.delete()) {
                return false;
            }
            InputStream in = c.getInputStream();
            fos = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
            in.close();
            return total > 1024;
        } catch (Throwable t) {
            Log.w(TAG, "下载失败: " + url + " -> " + t);
            return false;
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignored) {
            }
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
