package online.alawanmart.sms;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AlwanMart SMS Monitor — Foreground Service
 * Runs 24/7/365 — survives reboot, task killer, battery saver
 *
 * Layer 1: Foreground service with ONGOING notification (cannot be killed normally)
 * Layer 2: START_STICKY — Android MUST restart if killed
 * Layer 3: PARTIAL_WAKE_LOCK — CPU stays awake
 * Layer 4: onDestroy() restarts self immediately
 * Layer 5: onTaskRemoved() restarts when swiped from recents
 * Layer 6: AlarmManager fires every 5 min as backup restart
 * Layer 7: BootReceiver restarts after phone reboot
 * Layer 8: WatchdogService checks every 2 min and restarts if dead
 */
public class MonitorService extends Service {

    private static final String TAG           = "AlwanMartSMS";
    public  static final String CHANNEL_ID    = "alawanmart_monitor";
    private static final int    NOTIF_ID      = 1001;
    private static final long   PING_INTERVAL = 60_000L;   // Ping server every 60s
    private static final long   OTP_INTERVAL  = 10_000L;   // Check OTP queue every 10s
    private static final long   ALARM_INTERVAL = 5 * 60 * 1000L; // Alarm backup every 5 min

    private Handler           mainHandler;
    private Runnable          pingRunnable;
    private Runnable          otpRunnable;
    private ExecutorService   executor;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    public static volatile boolean isRunning = false;

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning   = true;
        executor    = Executors.newFixedThreadPool(3);
        prefs       = getSharedPreferences("alawanmart_sms", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        acquireWakeLock();
        Log.d(TAG, "MonitorService.onCreate()");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // MUST call startForeground() immediately — before any other work
        startForeground(NOTIF_ID, buildNotification(
            "🟢 AlwanMart — Monitoring Active",
            "Reading payment SMS and OTP requests 24/7"
        ));

        startPingLoop();
        startOtpLoop();
        scheduleAlarmBackup();

        Log.d(TAG, "MonitorService.onStartCommand()");
        return START_STICKY; // Android restarts this service automatically if killed
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopLoops();
        releaseWakeLock();
        Log.d(TAG, "MonitorService.onDestroy() — restarting...");

        // Layer 4: Restart self immediately
        try {
            Intent restart = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart);
            } else {
                startService(restart);
            }
        } catch (Exception e) {
            Log.e(TAG, "Self-restart failed: " + e.getMessage());
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Layer 5: User swiped app from recents — restart service
        Log.d(TAG, "onTaskRemoved — restarting service");
        Intent restart = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restart);
        } else {
            startService(restart);
        }
        scheduleAlarmBackup(); // Re-schedule alarm too
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /**
     * Layer 9 (Android 14+/API 34): dataSync foreground services have a
     * MANDATORY 6-hour execution limit per 24h. When the system calls
     * this, we MUST stop within a few seconds or risk an ANR/crash.
     * onDestroy() already restarts us via startForegroundService(),
     * so stopSelf() here triggers a clean restart and resets the timer.
     */
    // NOTE: no @Override — API 34 stub for this AGP version lacks this
    // method signature, but Android 14 will still call it at runtime via
    // normal virtual dispatch (annotation is compile-time only).
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "onTimeout() — Android 6h dataSync limit reached, restarting service");
        stopSelf();
    }

    // ─────────────────────────────────────────────────────────────
    // LOOPS
    // ─────────────────────────────────────────────────────────────

    private void startPingLoop() {
        if (pingRunnable != null) mainHandler.removeCallbacks(pingRunnable);
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                String url   = prefs.getString("server_url", "https://alawanmart.online");
                String token = prefs.getString("api_token", "");
                if (!token.isEmpty()) {
                    executor.execute(() -> {
                        SmsReceiver.sendPing(url, token);
                        // Refresh wakelock so it doesn't expire
                        refreshWakeLock();
                        // Update notification with last ping time
                        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                        updateNotification("🟢 Active — Last ping: " + time);
                    });
                }
                mainHandler.postDelayed(this, PING_INTERVAL);
            }
        };
        mainHandler.post(pingRunnable);
    }

    private void startOtpLoop() {
        if (otpRunnable != null) mainHandler.removeCallbacks(otpRunnable);
        otpRunnable = new Runnable() {
            @Override
            public void run() {
                String url   = prefs.getString("server_url", "https://alawanmart.online");
                String token = prefs.getString("api_token", "");
                if (!token.isEmpty()) {
                    executor.execute(() -> OtpService.checkAndSendOTPs(MonitorService.this));
                }
                mainHandler.postDelayed(this, OTP_INTERVAL);
            }
        };
        // Start OTP loop after 5 second delay
        mainHandler.postDelayed(otpRunnable, 5_000L);
    }

    private void stopLoops() {
        if (mainHandler != null) {
            if (pingRunnable != null) mainHandler.removeCallbacks(pingRunnable);
            if (otpRunnable  != null) mainHandler.removeCallbacks(otpRunnable);
        }
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }

    // ─────────────────────────────────────────────────────────────
    // ALARM BACKUP (Layer 6)
    // ─────────────────────────────────────────────────────────────

    public static void scheduleAlarm(Context context) {
        try {
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, AlarmReceiver.class);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
            if (alarm != null) {
                long triggerAt = System.currentTimeMillis() + ALARM_INTERVAL;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else {
                    alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
                Log.d(TAG, "Alarm backup scheduled for 5 min");
            }
        } catch (Exception e) {
            Log.e(TAG, "Alarm schedule error: " + e.getMessage());
        }
    }

    private void scheduleAlarmBackup() {
        scheduleAlarm(this);
    }

    // ─────────────────────────────────────────────────────────────
    // WAKELOCK (Layer 3)
    // ─────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AlwanMart::SMSMonitor"
                );
                // Hold for 10 minutes — refreshed by ping loop
                wakeLock.acquire(10 * 60 * 1000L);
                Log.d(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeLock acquire error: " + e.getMessage());
        }
    }

    private void refreshWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 1000L);
            }
        } catch (Exception e) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception e) {}
    }

    // ─────────────────────────────────────────────────────────────
    // NOTIFICATION
    // ─────────────────────────────────────────────────────────────

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification("AlwanMart SMS Monitor", text));
        } catch (Exception e) {}
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(
            this, 0,
            new Intent(this, MainActivity.class),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)          // Cannot be dismissed by user
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "SMS Payment Monitor",
                NotificationManager.IMPORTANCE_LOW  // LOW = silent but persistent
            );
            ch.setDescription("AlwanMart payment SMS monitoring — required for 24/7 operation");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
