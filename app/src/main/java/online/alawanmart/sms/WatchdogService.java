package online.alawanmart.sms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * WatchdogService — runs as FOREGROUND service
 * Checks every 2 minutes if MonitorService is alive
 * If dead → restarts it immediately
 */
public class WatchdogService extends Service {

    private static final String TAG        = "AlwanMartWatchdog";
    private static final String CHANNEL_ID = "alawanmart_watchdog";
    private static final int    NOTIF_ID   = 1002;
    private static final long   INTERVAL   = 2 * 60 * 1000L;

    private Handler  handler;
    private Runnable watchRunnable;

    public static volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        handler   = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        Log.d(TAG, "WatchdogService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Run as foreground so Android cannot kill it
        startForeground(NOTIF_ID, buildNotification());
        startWatching();
        return START_STICKY;
    }

    private void startWatching() {
        if (watchRunnable != null) handler.removeCallbacks(watchRunnable);
        watchRunnable = new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = getSharedPreferences("alawanmart_sms", MODE_PRIVATE);
                boolean enabled = prefs.getBoolean("monitoring_enabled", false);

                if (enabled) {
                    // Check MonitorService
                    if (!MonitorService.isRunning) {
                        Log.w(TAG, "MonitorService DEAD — restarting!");
                        Intent i = new Intent(WatchdogService.this, MonitorService.class);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(i);
                        } else {
                            startService(i);
                        }
                    }
                    // Reschedule alarm backup
                    MonitorService.scheduleAlarm(WatchdogService.this);
                }
                handler.postDelayed(this, INTERVAL);
            }
        };
        handler.postDelayed(watchRunnable, INTERVAL);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (handler != null && watchRunnable != null) {
            handler.removeCallbacks(watchRunnable);
        }
        Log.d(TAG, "WatchdogService destroyed — restarting");
        // Restart self
        Intent restart = new Intent(this, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restart);
        } else {
            startService(restart);
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restart = new Intent(this, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restart);
        } else {
            startService(restart);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /**
     * Android 14+ (API 34): dataSync FGS has a 6h/24h limit.
     * Stop gracefully so onDestroy() can restart us cleanly.
     */
    // NOTE: no @Override — API 34 stub for this AGP version lacks this
    // method signature, but Android 14 will still call it at runtime via
    // normal virtual dispatch (annotation is compile-time only).
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "onTimeout() — 6h dataSync limit reached, restarting watchdog");
        stopSelf();
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(
            this, 0,
            new Intent(this, MainActivity.class),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AlwanMart Watchdog")
            .setContentText("Keeping SMS monitor alive 24/7")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "SMS Watchdog",
                NotificationManager.IMPORTANCE_MIN
            );
            ch.setDescription("Keeps AlwanMart SMS monitor running");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
