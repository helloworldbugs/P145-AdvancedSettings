package com.p145.advancedsettings.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 极简 ADB 客户端：直连本机 adbd (127.0.0.1:5555)。
 * 本机 ro.adb.secure=0 且 adbd 常驻 root，因此无需 RSA 认证，CNXN 后直接 OPEN shell 即可拿到 root shell。
 */
public final class AdbClient {

    public static final String HOST = "127.0.0.1";
    /** 最近一次成功连接用的地址（::1 或 127.0.0.1），供界面显示真实通道 */
    public static volatile String lastHost = "";
    public static final int PORT = 5555;

    private static final int CMD_CNXN = 0x4e584e43;
    private static final int CMD_AUTH = 0x48545541;
    private static final int CMD_OPEN = 0x4e45504f;
    private static final int CMD_OKAY = 0x59414b4f;
    private static final int CMD_CLSE = 0x45534c43;
    private static final int CMD_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;

    private AdbClient() {
    }

    /** 连接后是否处于已认证状态 */
    public static boolean isAuthRequired(String command, int timeoutMs) {
        try {
            exec(command, timeoutMs);
            return false;
        } catch (AuthRequiredException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static final class AuthRequiredException extends IOException {
        AuthRequiredException(String m) {
            super(m);
        }
    }

    /**
     * 连 adbd：优先 IPv6 回环 ::1。
     * 车机在 setup_eth.sh 里用 iptables(IPv4) 把 5555 端口 DROP 掉，
     * 但 adbd 同时监听 [::]:5555，而 ip6tables 没有对应规则 —— 走 IPv6 可以绕过，
     * 拿到 root 后再把 IPv4 的规则删掉，外部 adb 就恢复了。
     */
    private static Socket open() throws IOException {
        IOException last = null;
        for (String host : new String[]{"::1", "127.0.0.1"}) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(InetAddress.getByName(host), PORT), 1500);
                s.setSoTimeout(800);
                s.setTcpNoDelay(true);
                lastHost = host;
                return s;
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw last != null ? last : new IOException("adbd 不可达");
    }

    public static String exec(String command, int timeoutMs) throws IOException {
        Socket socket = open();
        try {
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            send(out, CMD_CNXN, A_VERSION, MAX_DATA, "host::\0".getBytes("UTF-8"));

            boolean connected = false;
            long deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline) {
                int[] h = readHeader(in);
                if (h == null) {
                    continue;
                }
                int hcmd = h[0];
                int hlen = checkLen(h[3]);
                // 任何报文都要先吃掉 payload，否则后续解析会错位
                if (hlen > 0) {
                    readData(in, hlen);
                }
                if (hcmd == CMD_CNXN) {
                    connected = true;
                    break;
                }
                if (hcmd == CMD_AUTH) {
                    throw new AuthRequiredException("adbd 要求密钥授权");
                }
            }
            if (!connected) {
                throw new IOException("CNXN 超时");
            }

            final int localId = 1;
            send(out, CMD_OPEN, localId, 0, ("shell:" + command + "\0").getBytes("UTF-8"));

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int remoteId = 0;
            long dl = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < dl) {
                int[] h = readHeader(in);
                if (h == null) {
                    continue;
                }
                int cmd = h[0];
                int a0 = h[1];
                int len = checkLen(h[3]);
                byte[] data = len > 0 ? readData(in, len) : null;

                if (cmd == CMD_OKAY) {
                    remoteId = a0;
                } else if (cmd == CMD_WRTE) {
                    if (remoteId == 0) {
                        remoteId = a0;
                    }
                    send(out, CMD_OKAY, localId, remoteId, null);
                    if (data != null) {
                        bos.write(data);
                    }
                } else if (cmd == CMD_CLSE) {
                    try {
                        send(out, CMD_CLSE, localId, a0, null);
                    } catch (Exception ignored) {
                    }
                    return bos.toString("UTF-8");
                }
            }
            return bos.toString("UTF-8");
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static int checkLen(int len) throws IOException {
        if (len < 0 || len > MAX_DATA) {
            throw new IOException("协议错位: data_length=" + len);
        }
        return len;
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int magic = cmd ^ 0xffffffff;
        byte[] buf = new byte[24 + len];
        putInt(buf, 0, cmd);
        putInt(buf, 4, a0);
        putInt(buf, 8, a1);
        putInt(buf, 12, len);
        putInt(buf, 16, crc32(data));
        putInt(buf, 20, magic);
        if (len > 0) {
            System.arraycopy(data, 0, buf, 24, len);
        }
        out.write(buf);
        out.flush();
    }

    private static int[] readHeader(InputStream in) throws IOException {
        byte[] b = new byte[24];
        int n = readFully(in, b);
        if (n < 0) {
            return null;
        }
        if (n < 24) {
            return null;
        }
        return new int[]{getInt(b, 0), getInt(b, 4), getInt(b, 8), getInt(b, 12), getInt(b, 16)};
    }

    private static byte[] readData(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int n = readFully(in, b);
        return n == len ? b : new byte[0];
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n;
            try {
                n = in.read(b, off, b.length - off);
            } catch (SocketTimeoutException e) {
                if (off == 0) {
                    return -1;
                }
                continue;
            }
            if (n < 0) {
                return off;
            }
            off += n;
        }
        return off;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int crc32(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(data, 0, data.length);
        return (int) c.getValue();
    }
}