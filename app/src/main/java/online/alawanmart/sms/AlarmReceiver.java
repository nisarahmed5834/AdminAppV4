package online.alawanmart.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * AlarmReceiver — Layer 6
 * Fires every 5 minutes via AlarmManager
 * Restarts MonitorService and WatchdogService if not running
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlwanMartAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm fired — checking services");

        SharedPreferences prefs = context.getSharedPreferences("alawanmart_sms", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("monitoring_enabled", false);

        if (enabled) {
            // Restart MonitorService if not running
            if (!MonitorService.isRunning) {
                Log.w(TAG, "MonitorService dead — restarting via alarm");
                Intent monitorIntent = new Intent(context, MonitorService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(monitorIntent);
                } else {
                    context.startService(monitorIntent);
                }
            }

            // Always restart Watchdog (lightweight, no harm)
            Intent watchdogIntent = new Intent(context, WatchdogService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(watchdogIntent);
            } else {
                context.startService(watchdogIntent);
            }
        }

        // Reschedule next alarm
        MonitorService.scheduleAlarm(context);
    }
}
