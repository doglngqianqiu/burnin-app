package com.dsh.burnin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 1001;
    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF171C24;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int SUB = 0xFF8B949E;
    private static final int HOT = 0xFFFF6B35;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView tvState, tvMain, tvSub, tvRun, tvMode, tvNote, tvTarget, tvProfile;
    private Button btnToggle, btnPerm, btnCpu, btnMixed;
    private DeviceProfile profile;
    private SeekBar seek;
    private BroadcastReceiver batteryRx;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 500);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startSensorInit();
                            render();
                        }
                    }, 800);
                }
            };

    private final Shizuku.OnBinderReceivedListener binderListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            render();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        setContentView(buildUi());
        maybeShowDisclaimer();

        batteryRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (t > 0) Sensors.batteryC = t / 10f;
            }
        };
        Intent sticky = registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            int t = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (t > 0) Sensors.batteryC = t / 10f;
        }

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
            Shizuku.addBinderReceivedListenerSticky(binderListener);
        } catch (Throwable ignored) {
        }

        startSensorInit();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DeviceProfile p = DeviceProfile.detect();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyProfile(p);
                    }
                });
            }
        }, "profile-detect").start();
        ui.post(ticker);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        try {
            if (batteryRx != null) unregisterReceiver(batteryRx);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private long lastInitAt;

    private void applyProfile(DeviceProfile p) {
        profile = p;
        Engine.profile = p;
        if (!Engine.running) {
            Engine.target = p.defaultTarget;
        }
        seek.setMax(22);
        seek.setProgress((int) Math.max(0, Math.min(22, Engine.target - 38)));
        updateTargetText();
        styleMode();
        render();
    }

    private void styleMode() {
        boolean mixed = Engine.mode == 1;
        btnCpu.setBackground(roundRect(mixed ? 0xFF232A35 : 0xFF2D6A4F, dp(12)));
        btnMixed.setBackground(roundRect(mixed ? 0xFFB33A00 : 0xFF232A35, dp(12)));
    }

    private void startSensorInit() {
        lastInitAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Sensors.init();
                } catch (Throwable ignored) {
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                });
            }
        }, "sensor-init").start();
    }

    // ---------------- UI ----------------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = text("🔥 烤机", 26, TEXT, true);
        root.addView(title);
        tvState = text("空闲", 15, SUB, false);
        tvState.setPadding(0, dp(4), 0, dp(18));
        root.addView(tvState);

        LinearLayout cardMain = card();
        tvMain = text("--", 44, TEXT, true);
        cardMain.addView(tvMain);
        tvSub = text("整机温度", 13, SUB, false);
        tvSub.setPadding(0, dp(2), 0, dp(10));
        cardMain.addView(tvSub);
        tvRun = text("运行 00:00 · 峰值 -- · 暂停 0 次", 13, SUB, false);
        cardMain.addView(tvRun);
        root.addView(cardMain);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        LinearLayout cardCpu = card();
        tvCpuText = text("CPU --", 17, TEXT, true);
        cardCpu.addView(tvCpuText);
        cardCpu.addView(text("表盘 / 系统热状态", 11, SUB, false));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp1.rightMargin = dp(6);
        row.addView(cardCpu, lp1);

        LinearLayout cardBatt = card();
        tvBattText = text("电池 --", 17, TEXT, true);
        cardBatt.addView(tvBattText);
        tvBattSub = text("负载 / 占空", 11, SUB, false);
        cardBatt.addView(tvBattSub);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp2.leftMargin = dp(6);
        row.addView(cardBatt, lp2);
        root.addView(row);

        LinearLayout cardTarget = card();
        cardTarget.setPadding(dp(14), dp(12), dp(14), dp(6));
        tvTarget = text("目标整机温度 43℃ · 临界 45℃", 14, TEXT, false);
        cardTarget.addView(tvTarget);
        seek = new SeekBar(this);
        seek.setMax(22);
        seek.setProgress(5);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                Engine.target = 38 + progress;
                updateTargetText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        cardTarget.addView(seek);
        root.addView(cardTarget);

        LinearLayout rowMode = new LinearLayout(this);
        rowMode.setOrientation(LinearLayout.HORIZONTAL);
        rowMode.setPadding(0, dp(10), 0, 0);
        btnCpu = new Button(this);
        btnCpu.setText("纯计算");
        btnCpu.setTextSize(14);
        btnCpu.setAllCaps(false);
        btnCpu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine.mode = 0;
                styleMode();
            }
        });
        btnMixed = new Button(this);
        btnMixed.setText("混合（更热）");
        btnMixed.setTextSize(14);
        btnMixed.setAllCaps(false);
        btnMixed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine.mode = 1;
                styleMode();
            }
        });
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, dp(44), 1);
        m1.rightMargin = dp(5);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, dp(44), 1);
        m2.leftMargin = dp(5);
        rowMode.addView(btnCpu, m1);
        rowMode.addView(btnMixed, m2);
        root.addView(rowMode);

        tvProfile = text("机型识别中…", 12, SUB, false);
        tvProfile.setPadding(0, dp(12), 0, 0);
        root.addView(tvProfile);

        btnToggle = new Button(this);
        btnToggle.setText("开始烤机");
        btnToggle.setTextSize(17);
        btnToggle.setTextColor(Color.WHITE);
        btnToggle.setBackground(roundRect(HOT, dp(14)));
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lpBtn.topMargin = dp(16);
        root.addView(btnToggle, lpBtn);

        btnPerm = new Button(this);
        btnPerm.setText("授权 Shizuku");
        btnPerm.setTextSize(14);
        btnPerm.setTextColor(TEXT);
        btnPerm.setBackground(roundRect(0xFF232A35, dp(12)));
        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askShizuku();
            }
        });
        LinearLayout.LayoutParams lpPerm = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lpPerm.topMargin = dp(10);
        root.addView(btnPerm, lpPerm);

        tvMode = text("传感器：初始化中…", 12, SUB, false);
        tvMode.setPadding(0, dp(16), 0, 0);
        root.addView(tvMode);

        tvNote = text("机身发烫、电池超过 45℃ 就停下歇一会儿。", 12, SUB, false);
        tvNote.setPadding(0, dp(6), 0, 0);
        root.addView(tvNote);

        updateTargetText();
        return scroll;
    }

    private TextView tvCpuText, tvBattText, tvBattSub;

    private void toggle() {
        if (Engine.running) {
            stopService(new Intent(this, BurnService.class));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            render();
            return;
        }
        float batt = Sensors.batteryC;
        if (!Float.isNaN(batt) && batt >= 45) {
            Toast.makeText(this, "电池已经 " + String.format("%.1f", batt) + "℃，先散热", Toast.LENGTH_LONG).show();
            return;
        }
        if (Engine.target >= 48) {
            confirmHighTarget();
            return;
        }
        startBurn();
    }

    private void startBurn() {
        startForegroundService(new Intent(this, BurnService.class));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                render();
            }
        }, 300);
    }

    private void confirmHighTarget() {
        new AlertDialog.Builder(this)
                .setTitle("高温警告")
                .setMessage("目标 " + (int) Engine.target + "℃ 会让机身非常烫，可能烫伤皮肤、"
                        + "加速电池老化甚至损坏硬件。\n\n仍要继续吗？")
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startBurn();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void maybeShowDisclaimer() {
        SharedPreferences sp = getSharedPreferences("burnin", MODE_PRIVATE);
        if (sp.getBoolean("disclaimer_accepted", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("免责声明与使用须知")
                .setMessage(DISCLAIMER)
                .setCancelable(false)
                .setPositiveButton("我已阅读并同意", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        sp.edit().putBoolean("disclaimer_accepted", true).apply();
                    }
                })
                .setNegativeButton("不同意，退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        finishAffinity();
                    }
                })
                .show();
    }

    private static final String DISCLAIMER =
            "本应用通过持续制造 CPU 高负载来加热设备，属于硬件压力测试工具。\n\n"
            + "1. 使用中机身会明显发热，可能导致烫伤、电池加速老化、系统卡顿、自动关机，"
            + "极端情况下可能损坏硬件。\n"
            + "2. 请勿在无人看管、床上或易燃物附近使用；不要覆盖散热口。\n"
            + "3. 目标温度建议不超过 45℃；高于 48℃ 会二次确认，风险自负。\n"
            + "4. 出现异常发热、异味、屏幕异常请立即停止并断开充电。\n"
            + "5. 因使用本应用造成的任何直接或间接损失，作者不承担责任。\n"
            + "6. 本应用不收集、不上传任何个人数据。\n\n"
            + "点击「我已阅读并同意」表示你理解并接受以上风险。";

    private void askShizuku() {
        if (!shizukuAlive()) {
            Toast.makeText(this, "Shizuku 没有运行，先打开 Shizuku", Toast.LENGTH_LONG).show();
            return;
        }
        if (shizukuGranted()) {
            Toast.makeText(this, "已授权", Toast.LENGTH_SHORT).show();
            startSensorInit();
            render();
            return;
        }
        try {
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            Toast.makeText(this, "请求失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private boolean shizukuAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean shizukuGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateTargetText() {
        int t = (int) Engine.target;
        tvTarget.setText(String.format("目标整机温度 %d℃ · 临界 %d℃", t, (int) Engine.pauseAt()));
    }

    private void render() {
        try {
            Sensors.update();
        } catch (Throwable ignored) {
        }
        boolean on = Engine.running;
        if (on && Engine.paused) {
            tvState.setText("已暂停 · 降温中");
            tvState.setTextColor(HOT);
        } else if (on) {
            tvState.setText("运行中 · 屏幕保持常亮");
            tvState.setTextColor(0xFF3FB950);
        } else {
            tvState.setText("空闲");
            tvState.setTextColor(SUB);
        }
        double shown = Engine.metric;
        if (Double.isNaN(shown)) {
            float sk = Sensors.skinC, bt = Sensors.batteryC;
            shown = Float.isNaN(sk) ? bt : (Float.isNaN(bt) ? sk : Math.max(sk, bt));
        }
        tvMain.setText(Engine.fmt(shown) + "℃");
        tvMain.setTextColor(on ? TEXT : SUB);
        tvSub.setText("整机（机身/电池）· 目标 " + (int) Engine.target + "℃");
        tvRun.setText(String.format("运行 %s · 峰值 %s · 暂停 %d 次",
                Engine.elapsedText(), Engine.fmt((float) Engine.maxMetric), Engine.pauseCount));
        tvCpuText.setText("CPU " + Engine.fmt(Sensors.cpuC) + "℃");
        tvBattText.setText("电池 " + Engine.fmt(Sensors.batteryC) + "℃");
        tvBattSub.setText(on ? ("占空 " + Math.round(Engine.duty * 100) + "%"
                + (Engine.paused ? " · 暂停" : "")) : "负载 / 占空");

        if (shizukuGranted() && Float.isNaN(Sensors.cpuC)
                && System.currentTimeMillis() - lastInitAt > 4000) {
            startSensorInit();
        }
        int ts = Engine.thermalStatus;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 29) ts = pm.getCurrentThermalStatus();
        } catch (Throwable ignored) {
        }
        if (profile != null) {
            tvProfile.setText("机型：" + profile.brand + " " + profile.model + " · "
                    + profile.title() + "（" + profile.source + "）\n" + profile.note);
        }
        tvMode.setText("传感器：" + Sensors.mode + " · 热状态 " + (ts < 0 ? "--" : ts)
                + " · 模式 " + (Engine.mode == 1 ? "混合" : "纯计算")
                + (shizukuGranted() ? " · Shizuku 已授权" : (shizukuAlive() ? " · Shizuku 未授权" : "")));
        String note = Engine.note;
        if (note == null || note.isEmpty()) {
            note = on ? "临界自动暂停，降温后自动恢复" : "点开始后负载会逐渐拉满";
        }
        tvNote.setText(note + "\n机身发烫、电池超过 45℃ 就停下歇一会儿。");

        btnToggle.setText(on ? "停止" : "开始烤机");
        btnToggle.setAllCaps(false);
        btnToggle.setBackground(roundRect(on ? 0xFF30363D : HOT, dp(14)));
        btnPerm.setText(shizukuGranted() ? "Shizuku 已授权（可读机身温度）" : "授权 Shizuku");
        btnPerm.setAllCaps(false);
        updateTargetText();
    }

    // ---------------- 小工具 ----------------

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        l.setBackground(roundRect(CARD, dp(14)));
        l.setGravity(Gravity.START);
        return l;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
