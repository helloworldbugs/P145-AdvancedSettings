package com.p145.advancedsettings.core;

/** 通过本机 adb 通道执行 root 命令 */
public final class Root {

    public static final class R {
        public final int rc;
        public final String out;

        R(int rc, String out) {
            this.rc = rc;
            this.out = out;
        }

        public boolean ok() {
            return rc == 0;
        }
    }

    private Root() {
    }

    public static R sh(String cmd, int timeoutMs) throws Exception {
        String wrapped = "{ " + cmd + "\n} 2>&1; echo \"__RC=$?\"";
        String raw = AdbClient.exec(wrapped, timeoutMs);
        int rc = -1;
        String out = raw;
        int idx = raw.lastIndexOf("__RC=");
        if (idx >= 0) {
            String tail = raw.substring(idx + 5).trim();
            int nl = tail.indexOf('\n');
            if (nl > 0) {
                tail = tail.substring(0, nl).trim();
            }
            try {
                rc = Integer.parseInt(tail.replaceAll("[^0-9-]", ""));
            } catch (Exception ignored) {
            }
            out = raw.substring(0, idx);
        }
        return new R(rc, out);
    }

    public static String out(String cmd, int timeoutMs) {
        try {
            return sh(cmd, timeoutMs).out.trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean test(String cmd, int timeoutMs) {
        try {
            return sh(cmd, timeoutMs).ok();
        } catch (Exception e) {
            return false;
        }
    }
}
