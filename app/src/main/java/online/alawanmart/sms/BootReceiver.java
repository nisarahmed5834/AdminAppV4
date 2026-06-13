package online.alawanmart.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver — Layer 7
 * Starts everything after phone reboot / app update
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AlwanMartBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Boot/update received: " + action);

        SharedPreferences prefs = context.getSharedPreferences("alawanmart_sms", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("monitoring_enabled", false)) {
            Log.d(TAG, "Monitoring disabled — not starting");
            return;
        }

        // Start MonitorService
        Intent monitorIntent = new Intent(context, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(monitorIntent);
        } else {
            context.startService(monitorIntent);
        }

        // Start WatchdogService
        Intent watchdogIntent = new Intent(context, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(watchdogIntent);
        } else {
            context.startService(watchdogIntent);
        }

        // Schedule alarm backup
        MonitorService.scheduleAlarm(context);

        Log.d(TAG, "All services started after boot");
    }
}
