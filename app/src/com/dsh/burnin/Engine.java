package com.dsh.burnin;

import android.content.Context;
import android.os.PowerManager;

/**
 * 烤机引擎：N 个满载线程 + 占空比闭环 + 临界暂停 + 看门狗急停。
 * 控温对象是"整机温度" = max(机身壳温, 电池温度)。CPU 表盘只做急停保护。
 */
public final class Engine {

    public static volatile boolean running;
    public static volatile boolean paused;
    public static volatile double duty;
    public static volatile double target = 43.0;
    public static volatile double metric = Double.NaN;
    public static volatile double maxMetric = Double.NaN;
    public static volatile int pauseCount;
    public static volatile int thermalStatus = -1;
    public static volatile String note = "";
    public static volatile long startAt;
    public static volatile long pausedMs;
    public static volatile int threads;
    public static volatile int mode;          // 用户选择：0=纯计算 1=计算+内存带宽
    private static volatile int runMode;      // 本次运行实际使用：激进模式强制 1
    public static volatile boolean aggressive; // 激进升温：不控温、超订线程、阈值上调
    public static volatile DeviceProfile profile;
    private static volatile int memFloats = 2_500_000;

    public static final double BATT_MAX = 45.0;
    public static final double CPU_MAX = 102.0;
    public static final double AGGR_PAUSE = 50.0;
    public static final double AGGR_ABORT = 52.0;
    public static final double AGGR_BATT = 47.0;
    public static final double AGGR_CPU = 105.0;
    /** 临界暂停：目标 +2℃，最高 62℃ */
    public static final double PAUSE_CAP = 62.0;
    /** 急停：临界 +2℃，最高 64℃ */
    public static final double ABORT_CAP = 64.0;

    private static final long PERIOD_NS = 100_000_000L; // 100ms
    private static volatile Thread monitor;
    private static volatile Thread watchdog;
    private static volatile Thread[] workers = new Thread[0];
    private static volatile double integ, ema;
    private static volatile long lastTick;

    private Engine() {
    }

    public static double pauseAt() {
        return aggressive ? AGGR_PAUSE : Math.min(PAUSE_CAP, target + 2.0);
    }

    public static double abortAt() {
        return aggressive ? AGGR_ABORT : Math.min(ABORT_CAP, pauseAt() + 2.0);
    }

    public static double battMax() {
        return aggressive ? AGGR_BATT : BATT_MAX;
    }

    public static double cpuMax() {
        return aggressive ? AGGR_CPU : CPU_MAX;
    }

    public static double resumeAt() {
        return aggressive ? AGGR_PAUSE - 2.0 : target;
    }

    public static synchronized void start(Context ctx, boolean aggr) {
        if (running) return;
        aggressive = aggr;
        runMode = aggr ? 1 : mode;
        Context app = ctx.getApplicationContext();
        running = true;
        paused = false;
        duty = 0;
        integ = 0;
        ema = Double.NaN;
        note = "";
        startAt = System.currentTimeMillis();
        pausedMs = 0;
        pauseCount = 0;
        maxMetric = Double.NaN;
        lastTick = System.currentTimeMillis();

        if (profile == null) profile = DeviceProfile.quick();   // 不阻塞主线程，Activity 会补全
        memFloats = profile.memFloats;
        int n = aggressive
                ? Math.max(1, Math.min(profile.cores + profile.cores / 2, 16))
                : Math.max(1, Math.min(profile.workers, 16));
        threads = n;
        Thread[] ws = new Thread[n];
        for (int i = 0; i < n; i++) {
            ws[i] = new Thread(new Worker(i), "burn-" + i);
            ws[i].setDaemon(true);
            ws[i].setPriority(Thread.MAX_PRIORITY);
            ws[i].start();
        }
        workers = ws;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Sensors.init();
                } catch (Throwable ignored) {
                }
            }
        }, "sensor-init").start();

        monitor = new Thread(new Monitor(app), "burn-monitor");
        monitor.setDaemon(true);
        monitor.start();

        watchdog = new Thread(new Watchdog(), "burn-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public static synchronized void stop() {
        running = false;
        paused = false;
        aggressive = false;
        duty = 0;
        startAt = 0;
        for (Thread t : workers) {
            if (t != null) t.interrupt();
        }
        if (monitor != null) monitor.interrupt();
        if (watchdog != null) watchdog.interrupt();
    }

    public static double elapsedSec() {
        if (startAt == 0) return 0;
        return (System.currentTimeMillis() - startAt) / 1000.0;
    }

    public static String elapsedText() {
        int s = (int) elapsedSec();
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private static void hardStop(String why) {
        note = why;
        stop();
    }

    /** 一个满载线程，按 duty 做时间片。 */
    private static final class Worker implements Runnable {
        private final int id;

        Worker(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            double x = 1.0 + id * 0.001;
            float[] arrA = null, arrB = null;
            if (runMode == 1) {
                arrA = new float[memFloats];
                arrB = new float[memFloats];
                for (int i = 0; i < arrA.length; i++) arrA[i] = 1.0f + i * 1e-6f;
            }
            while (running) {
                double d = duty;
                if (d <= 0.02) {
                    sleep(30);
                    continue;
                }
                long start = System.nanoTime();
                long end = start + (long) (PERIOD_NS * d);
                int spin = 0;
                while (System.nanoTime() < end && running) {
                    for (int i = 0; i < 4000; i++) {
                        x = x * 1.0000003 + 0.7;
                        if (x > 1e9) x = 1.0;
                    }
                    if (arrA != null && (spin++ & 63) == 0) {
                        // 内存带宽压力：每 64 轮插入一次整块读写，计算为主、内存为辅
                        for (int i = 0; i < arrA.length; i++) {
                            arrB[i] = arrA[i] * 1.000001f + 0.5f;
                        }
                    }
                }
                SINK = x;
                long rest = PERIOD_NS - (System.nanoTime() - start);
                if (rest > 2_000_000L) sleep(rest / 1_000_000L);
            }
        }
    }

    private static volatile double SINK;

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 10Hz 采样 + PI 控温 + 临界暂停。 */
    private static final class Monitor implements Runnable {
        private final PowerManager pm;

        Monitor(Context ctx) {
            pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        }

        @Override
        public void run() {
            while (running) {
                sleep(100);
                if (!running) break;
                long now = System.currentTimeMillis();
                double dt = Math.max(0.05, (now - lastTick) / 1000.0);
                lastTick = now;

                Sensors.update();
                float skin = Sensors.skinC;
                float batt = Sensors.batteryC;
                float cpu = Sensors.cpuC;
                // 控温对象：机身壳温（读不到时才退回电池）
                double m = Float.isNaN(skin) ? batt : skin;
                if (Double.isNaN(m)) continue;
                metric = m;
                maxMetric = Double.isNaN(maxMetric) ? m : Math.max(maxMetric, m);
                try {
                    thermalStatus = pm.getCurrentThermalStatus();
                } catch (Throwable ignored) {
                }

                // 硬保护：直接结束
                if ((!Float.isNaN(batt) && batt >= battMax() + 1)
                        || (!Float.isNaN(cpu) && cpu >= cpuMax() + 2)
                        || m >= abortAt()
                        || thermalStatus >= 5) {
                    hardStop("急停：机身 " + fmt(m) + "℃ / CPU " + fmt(cpu) + "℃ / 热状态 " + thermalStatus);
                    break;
                }

                // 临界暂停
                boolean trip = m >= pauseAt()
                        || (!Float.isNaN(batt) && batt >= battMax())
                        || (!Float.isNaN(cpu) && cpu >= cpuMax());
                if (!paused && trip) {
                    paused = true;
                    pauseCount++;
                    duty = 0;
                    integ = 0;
                    if (!Float.isNaN(batt) && batt >= battMax()) {
                        note = String.format("暂停：电池 %.1f℃ 达上限", batt);
                    } else if (!Float.isNaN(cpu) && cpu >= cpuMax()) {
                        note = String.format("暂停：CPU %.1f℃ 达上限", cpu);
                    } else {
                        note = String.format("暂停：机身 %.1f℃（临界 %.0f℃）", m, pauseAt());
                    }
                } else if (paused && m <= resumeAt()
                        && (Float.isNaN(batt) || batt <= battMax() - 2)
                        && (Float.isNaN(cpu) || cpu <= cpuMax() - 6)) {
                    paused = false;
                    note = "已降温，恢复负载";
                }

                if (paused) {
                    duty = 0;
                    continue;
                }

                if (aggressive) {
                    duty = 1.0;   // 激进模式不控温，直接满载
                    continue;
                }

                // PI 控制
                double tau = 3.0;
                if (Double.isNaN(ema)) ema = m;
                ema += (1 - Math.exp(-dt / tau)) * (m - ema);
                double err = target - ema;
                double uRaw = 0.8 * err + 0.05 * integ;
                if (!((uRaw >= 1 && err > 0) || (uRaw <= 0 && err < 0))) {
                    integ = Math.max(-60, Math.min(60, integ + err * dt));
                }
                double u = Math.max(0, Math.min(1, 0.8 * err + 0.05 * integ));
                double step = 0.6 * dt;
                duty = Math.max(0, Math.min(1, duty + Math.max(-step, Math.min(step, u - duty))));
            }
        }
    }

    /** 独立看门狗：2Hz 检查，越过硬阈值直接结束（主循环卡住也有效）。 */
    private static final class Watchdog implements Runnable {
        @Override
        public void run() {
            while (running) {
                sleep(500);
                float batt = Sensors.batteryC;
                float cpu = Sensors.cpuC;
                if ((!Float.isNaN(batt) && batt >= battMax() + 1)
                        || (!Float.isNaN(cpu) && cpu >= cpuMax() + 2)) {
                    hardStop("看门狗急停：电池 " + fmt(batt) + "℃ / CPU " + fmt(cpu) + "℃");
                    break;
                }
            }
        }
    }

    public static String fmt(float v) {
        return Float.isNaN(v) ? "--" : String.format("%.1f", v);
    }

    public static String fmt(double v) {
        return Double.isNaN(v) ? "--" : String.format("%.1f", v);
    }
}
