package rikka.shizuku;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 以 shell (uid 2000) 身份执行命令的极简封装。
 * 放在 rikka.shizuku 包里是为了能直接用包内可见的 ShizukuRemoteProcess 构造器。
 */
public final class ShizukuShell {

    private ShizukuShell() {
    }

    public static boolean ready() {
        try {
            return Shizuku.pingBinder() && Shizuku.getBinder() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 执行命令并返回 stdout；失败/超时返回 null。 */
    public static String exec(String cmd, long timeoutMs) {
        Process p = null;
        try {
            moe.shizuku.server.IShizukuService svc =
                    moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (svc == null) { Log.e("BurnInShizuku", "svc null"); return null; }
            moe.shizuku.server.IRemoteProcess rp =
                    svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            if (rp == null) { Log.e("BurnInShizuku", "newProcess null"); return null; }
            p = new ShizukuRemoteProcess(rp);

            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final InputStream is = p.getInputStream();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[8192];
                    try {
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "shizuku-shell-read");
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            if (t.isAlive()) {
                p.destroy();
                return null;
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            Log.e("BurnInShizuku", "exec failed: " + cmd, t);
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
