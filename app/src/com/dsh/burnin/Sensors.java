package com.dsh.burnin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.ShizukuShell;

/**
 * 温度采集。
 * 优先直接读 /sys/class/thermal（普通 App 通常可读）；
 * 被 SELinux 拦住时改用 Shizuku 以 shell 身份读同一批文件。
 */
public final class Sensors {

    public static volatile float batteryC = Float.NaN;
    public static volatile float cpuC = Float.NaN;
    public static volatile float skinC = Float.NaN;
    public static volatile String mode = "未初始化";

    private static String[] names = new String[0];
    private static String[] paths = new String[0];
    private static boolean direct;
    private static final long ZONE_INTERVAL_MS = 900;
    private static long lastZoneRead;

    private Sensors() {
    }

    public static synchronized void init() {
        List<String> n = new ArrayList<String>();
        List<String> p = new ArrayList<String>();
        File dir = new File("/sys/class/thermal");
        File[] zones = dir.listFiles();
        if (zones != null) {
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                try {
                    String type = readLine(new File(z, "type"));
                    String temp = readLine(new File(z, "temp"));
                    if (type == null || type.trim().isEmpty() || temp == null) continue;
                    n.add(type.trim());
                    p.add(new File(z, "temp").getAbsolutePath());
                } catch (Throwable ignored) {
                }
            }
        }
        names = n.toArray(new String[0]);
        paths = p.toArray(new String[0]);

        direct = names.length > 0;
        if (direct) {
            int cpuCount = 0;
            for (String nm : names) if (isCpu(nm)) cpuCount++;
            mode = "直接读取 · " + names.length + " 个热区（CPU " + cpuCount + "）";
        } else if (ShizukuShell.ready()) {
            mode = "Shizuku 读取";
            readViaShell();
        } else if (ShizukuShell.ready() == false && rikka.shizuku.Shizuku.pingBinder()) {
            mode = "Shizuku 未授权 · 仅电池温度";
        } else {
            mode = "仅电池温度 · 授权 Shizuku 可读机身";
        }
    }

    /** 更新 CPU / 机身温度；节流到约 1Hz。 */
    public static void update() {
        long now = System.currentTimeMillis();
        if (now - lastZoneRead < ZONE_INTERVAL_MS) return;
        lastZoneRead = now;
        if (direct) {
            readDirect();
        } else if (ShizukuShell.ready()) {
            readViaShell();
        }
    }

    private static void readDirect() {
        float cpu = Float.NaN, skin = Float.NaN;
        for (int i = 0; i < names.length; i++) {
            try {
                String raw = readLine(new File(paths[i]));
                if (raw == null) continue;
                float v = Float.parseFloat(raw) / 1000f;
                if (v < -40 || v > 200) continue;
                String n = names[i];
                if (isCpu(n)) cpu = Float.isNaN(cpu) ? v : Math.max(cpu, v);
                else if (isSkin(n)) skin = Float.isNaN(skin) ? v : Math.max(skin, v);
            } catch (Throwable ignored) {
            }
        }
        cpuC = cpu;
        skinC = skin;
    }

    private static void readViaShell() {
        String out = ShizukuShell.exec(
                "for z in /sys/class/thermal/thermal_zone*; do "
                        + "printf '%s %s\\n' \"$(cat $z/type 2>/dev/null)\" "
                        + "\"$(cat $z/temp 2>/dev/null)\"; done", 4000);
        if (out == null || out.isEmpty()) {
            mode = "Shizuku 无权限 · 仅电池温度";
            return;
        }
        float cpu = Float.NaN, skin = Float.NaN;
        int zones = 0;
        for (String line : out.split("\n")) {
            String[] kv = line.trim().split(" ");
            if (kv.length < 2) continue;
            zones++;
            try {
                float v = Float.parseFloat(kv[1]) / 1000f;
                if (v < -40 || v > 200) continue;
                if (isCpu(kv[0])) cpu = Float.isNaN(cpu) ? v : Math.max(cpu, v);
                else if (isSkin(kv[0])) skin = Float.isNaN(skin) ? v : Math.max(skin, v);
            } catch (Throwable ignored) {
            }
        }
        if (zones > 0) {
            cpuC = cpu;
            skinC = skin;
            mode = "Shizuku 读取 · " + zones + " 个热区";
        }
    }

    private static boolean isCpu(String name) {
        return name.matches("cpu-\\d+-\\d+-(step|usr)") || name.matches("cpuss-\\d+-(step|usr)");
    }

    private static boolean isSkin(String name) {
        return name.startsWith("shell_") && !name.startsWith("shell_level");
    }

    private static String readLine(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f), 64);
            return r.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
