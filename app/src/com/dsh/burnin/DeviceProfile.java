package com.dsh.burnin;

import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;

import rikka.shizuku.ShizukuShell;

/**
 * 机型识别 + 发热策略。
 * 优先用 Shizuku 以 shell 身份读 getprop（ro.board.platform / ro.soc.model 更准），
 * 拿不到就退回 Build.* 和 /proc/cpuinfo。
 */
public final class DeviceProfile {

    public final String source;
    public final String brand;
    public final String model;
    public final String soc;
    public final String family;
    public final String note;
    public final int cores;
    public final int defaultTarget;
    public final int maxTarget;
    public final int workers;
    public final int memFloats;

    private DeviceProfile(String source, String brand, String model, String soc,
                          String family, String note, int cores, int defaultTarget,
                          int maxTarget, int workers, int memFloats) {
        this.source = source;
        this.brand = brand;
        this.model = model;
        this.soc = soc;
        this.family = family;
        this.note = note;
        this.cores = cores;
        this.defaultTarget = defaultTarget;
        this.maxTarget = maxTarget;
        this.workers = workers;
        this.memFloats = memFloats;
    }

    public String title() {
        return soc + " · " + cores + " 核 · " + family;
    }

    public static DeviceProfile detect() {
        return detect(true);
    }

    /** 不调用 Shizuku 的快速版本，可在主线程安全使用。 */
    public static DeviceProfile quick() {
        return detect(false);
    }

    private static DeviceProfile detect(boolean allowShizuku) {
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE;
        String soc = "";
        String board = "";
        String source = "系统 API";
        int cores = Runtime.getRuntime().availableProcessors();

        // 1) Shizuku：shell 身份读系统属性，最准
        if (allowShizuku && ShizukuShell.ready()) {
            String out = ShizukuShell.exec(
                    "getprop ro.product.brand; getprop ro.product.model; "
                            + "getprop ro.board.platform; getprop ro.soc.model; "
                            + "getprop ro.hardware; getprop ro.product.cpu.abi", 4000);
            if (out != null && !out.trim().isEmpty()) {
                String[] l = out.split("\n");
                if (l.length >= 5) {
                    if (!l[0].trim().isEmpty()) brand = l[0].trim();
                    if (!l[1].trim().isEmpty()) model = l[1].trim();
                    board = l[2].trim();
                    String socModel = l[3].trim();
                    soc = socModel.isEmpty() ? board : socModel;
                    if (!l[4].trim().isEmpty()) hardware = l[4].trim();
                    source = "Shizuku";
                }
            }
        }

        // 2) 系统 API / CPU 信息兜底
        if (soc.isEmpty() && Build.VERSION.SDK_INT >= 31) {
            try {
                String s = Build.SOC_MODEL;
                if (s != null && !s.equalsIgnoreCase("unknown")) soc = s;
            } catch (Throwable ignored) {
            }
        }
        if (soc.isEmpty()) soc = cpuinfoHardware();
        if (soc.isEmpty()) soc = hardware;

        String up = (soc + " " + board + " " + hardware).toUpperCase();
        String family;
        String note;
        int defaultTarget;
        int maxTarget;
        int memFloats;
        int workers = cores;

        if (up.contains("SM8450") || up.contains("SM8475") || up.contains("SM8550")
                || up.contains("SM8650") || up.contains("SM8750")
                || up.contains("SM8325") || up.contains("SM8350")
                || up.contains("LAHAINA") || up.contains("WAIPIO") || up.contains("TARO")
                || up.contains("KALAMA") || up.contains("CROW") || up.contains("PINEAPPLE")
                || up.contains("KONA")) {
            family = "骁龙 8 系";
            note = "旗舰 8 系：发热猛、热墙来得早，控机身温度最稳";
            defaultTarget = 43;
            maxTarget = 44;
            memFloats = 1_000_000;
        } else if (up.startsWith("SM7") || up.startsWith("SM6")) {
            family = "骁龙 7/6 系";
            note = "中端平台：升温慢，目标可略低";
            defaultTarget = 42;
            maxTarget = 43;
            memFloats = 600_000;
        } else if (up.startsWith("MT")) {
            family = "天玑";
            note = "天玑平台：温控较积极，建议目标 42℃ 以内";
            defaultTarget = 42;
            maxTarget = 43;
            memFloats = 600_000;
        } else if (up.contains("KIRIN") || up.startsWith("HI")) {
            family = "麒麟";
            note = "麒麟平台：温控保守，目标别拉太高";
            defaultTarget = 41;
            maxTarget = 42;
            memFloats = 500_000;
        } else {
            family = "通用";
            note = "未识别平台，用通用策略";
            defaultTarget = 42;
            maxTarget = 43;
            memFloats = 600_000;
        }

        return new DeviceProfile(source, brand, model, soc.isEmpty() ? "未知" : soc,
                family, note, cores, defaultTarget, maxTarget, workers, memFloats);
    }

    private static String cpuinfoHardware() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Hardware")) {
                    int i = line.indexOf(':');
                    if (i > 0) return line.substring(i + 1).trim();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
        return "";
    }
}
