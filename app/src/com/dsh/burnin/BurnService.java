package com.dsh.burnin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.graphics.drawable.Icon;

/** 前台服务：持有烤机引擎，退到后台也不会被冻结。 */
public class BurnService extends Service {

    private static final String CHANNEL = "burnin";
    private static final int NID = 7;
    private static final String ACTION_STOP = "com.dsh.burnin.STOP";

    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver batteryRx;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!Engine.running) {   // 引擎自己急停后，服务跟着结束
                stopSelf();
                return;
            }
            updateNotification();
            if (handler != null) handler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "烤机", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        batteryRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                readBattery(intent);
            }
        };
        Intent sticky = registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) readBattery(sticky);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "burnin:load");
            wakeLock.setReferenceCounted(false);
        }
        handler = new Handler(Looper.getMainLooper());
        startForeground(NID, buildNotification("准备中"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏的「停止」按钮
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 进程被杀后系统可能用 null intent 重启服务：不要自动再烧
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Engine.running) return START_NOT_STICKY;
        boolean aggr = intent.getBooleanExtra("aggressive", false);
        Engine.start(this, aggr);
        if (wakeLock != null) wakeLock.acquire(2 * 60 * 60 * 1000L);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Engine.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (handler != null) handler.removeCallbacks(ticker);
        if (batteryRx != null) {
            try {
                unregisterReceiver(batteryRx);
            } catch (Throwable ignored) {
            }
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void readBattery(Intent it) {
        int t = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        if (t > 0) Sensors.batteryC = t / 10f;
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("烤机进行中")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "停止",
                        PendingIntent.getService(this, 1,
                                new Intent(this, BurnService.class).setAction(ACTION_STOP),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                        .build())
                .build();
    }

    private void updateNotification() {
        String text = String.format("整机 %s℃ · CPU %s℃ · 负载 %.0f%%",
                Engine.fmt((float) Engine.metric),
                Engine.fmt(Sensors.cpuC),
                Engine.duty * 100);
        if (Engine.paused) text = "已暂停 · " + text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NID, buildNotification(text));
    }
}
