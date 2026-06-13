package online.alawanmart.sms;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int    PERM_REQUEST   = 100;
    private static final int    BATTERY_REQUEST = 101;
    private static final long   STATS_INTERVAL = 30_000L;

    private SharedPreferences prefs;
    private Handler           handler = new Handler(Looper.getMainLooper());
    private Runnable          statsRunnable;
    private ExecutorService   executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("alawanmart_sms", MODE_PRIVATE);

        // Redirect to setup if not configured
        if (prefs.getString("api_token", "").isEmpty()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        setupUI();
        requestBatteryOptimizationExemption();
        checkBatteryStatus();
        startStatsPoll();
    }

    private void setupUI() {
        Switch   toggle    = findViewById(R.id.toggleMonitoring);
        TextView serverUrl = findViewById(R.id.serverUrl);
        TextView btnSettings = findViewById(R.id.btnSettings);

        String url = prefs.getString("server_url", "https://alawanmart.online");
        serverUrl.setText(url.replace("https://", "").replace("http://", ""));

        boolean enabled = prefs.getBoolean("monitoring_enabled", false);
        toggle.setChecked(enabled);
        updateStatus(enabled);

        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));

        toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("monitoring_enabled", isChecked).apply();
            updateStatus(isChecked);
            if (isChecked) {
                requestPermissionsAndStart();
            } else {
                stopService(new Intent(this, MonitorService.class));
                stopService(new Intent(this, WatchdogService.class));
                Toast.makeText(this, "⚫ Monitoring stopped", Toast.LENGTH_SHORT).show();
            }
        });

        if (enabled) requestPermissionsAndStart();
    }

    private void updateStatus(boolean enabled) {
        TextView icon  = findViewById(R.id.statusIcon);
        TextView title = findViewById(R.id.statusTitle);
        TextView sub   = findViewById(R.id.statusSub);
        if (enabled) {
            icon.setText("🟢");
            title.setText("Monitoring Active");
            title.setTextColor(0xFF10B981);
            sub.setText("Reading payment SMS + sending OTPs 24/7");
        } else {
            icon.setText("⚫");
            title.setText("Monitoring Off");
            title.setTextColor(0xFF94A3B8);
            sub.setText("Tap the toggle to start");
        }
    }

    private void requestPermissionsAndStart() {
        // Build permissions list
        java.util.List<String> needed = new java.util.ArrayList<>();
        String[] required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? new String[]{
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.POST_NOTIFICATIONS
              }
            : new String[]{
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS
              };

        for (String p : required) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        if (needed.isEmpty()) {
            startAllServices();
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int reqCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(reqCode, permissions, results);
        if (reqCode == PERM_REQUEST) {
            boolean allGranted = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            if (allGranted) {
                startAllServices();
            } else {
                Toast.makeText(this, "⚠️ SMS permission required!", Toast.LENGTH_LONG).show();
                ((Switch) findViewById(R.id.toggleMonitoring)).setChecked(false);
                prefs.edit().putBoolean("monitoring_enabled", false).apply();
                updateStatus(false);
            }
        }
    }

    private void startAllServices() {
        // Start MonitorService
        Intent monitorIntent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(monitorIntent);
        } else {
            startService(monitorIntent);
        }
        // Start WatchdogService
        Intent watchdogIntent = new Intent(this, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(watchdogIntent);
        } else {
            startService(watchdogIntent);
        }
        // Schedule alarm backup
        MonitorService.scheduleAlarm(this);

        Toast.makeText(this, "🟢 Monitoring started!", Toast.LENGTH_SHORT).show();
    }

    /**
     * Ask Android to ignore battery optimizations for this app
     * This is the #1 reason apps get killed
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    // Directly open battery optimization settings for this app
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception e) {
                // Fallback: open general battery settings
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e2) {
                    // ignore
                }
            }
        }
    }

    /**
     * Check if battery optimization is still enabled — warn user
     */
    private void checkBatteryStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                // Show warning banner
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
                builder.setTitle("⚠️ Battery Optimization Active");
                builder.setMessage(
                    "Android is killing this app when you press back.\n\n" +
                    "To keep it running 24/7:\n\n" +
                    "1. Tap 'Fix Now' below\n" +
                    "2. Select 'Don\'t optimize' or 'Unrestricted'\n\n" +
                    "This is required for SMS monitoring to work."
                );
                builder.setPositiveButton("Fix Now", (d, w) -> requestBatteryOptimizationExemption());
                builder.setNegativeButton("Later", null);
                builder.setCancelable(true);
                builder.show();
            }
        }
    }

    // ── Stats polling ─────────────────────────────────────────────

    private void startStatsPoll() {
        statsRunnable = new Runnable() {
            @Override
            public void run() {
                String serverUrl = prefs.getString("server_url", "https://alawanmart.online");
                String token     = prefs.getString("api_token", "");
                if (!token.isEmpty()) {
                    executor.execute(() -> fetchStats(serverUrl, token));
                }
                handler.postDelayed(this, STATS_INTERVAL);
            }
        };
        handler.post(statsRunnable);
    }

    private void fetchStats(String serverUrl, String token) {
        try {
            URL url = new URL(serverUrl.replaceAll("/$", "") + "/api/app_status.php");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-AlawanMart-Token", token);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            if (conn.getResponseCode() != 200) { conn.disconnect(); return; }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            String resp   = sb.toString();
            int total     = extractInt(resp, "\"total\":");
            int matched   = extractInt(resp, "\"matched\":");
            int today     = extractInt(resp, "\"today\":");

            runOnUiThread(() -> {
                TextView tvTotal   = findViewById(R.id.statTotal);
                TextView tvMatched = findViewById(R.id.statMatched);
                TextView tvToday   = findViewById(R.id.statToday);
                if (tvTotal   != null) tvTotal.setText(String.valueOf(total));
                if (tvMatched != null) tvMatched.setText(String.valueOf(matched));
                if (tvToday   != null) tvToday.setText(String.valueOf(today));
            });
        } catch (Exception e) { /* ignore network errors */ }
    }

    private int extractInt(String json, String key) {
        try {
            int idx = json.indexOf(key);
            if (idx < 0) return 0;
            String sub = json.substring(idx + key.length()).trim();
            int end = 0;
            while (end < sub.length() && Character.isDigit(sub.charAt(end))) end++;
            return end > 0 ? Integer.parseInt(sub.substring(0, end)) : 0;
        } catch (Exception e) { return 0; }
    }

    // ── Back button → minimize app, do NOT close ─────────────────
    @Override
    public void onBackPressed() {
        // Move app to background instead of closing
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && statsRunnable != null) handler.removeCallbacks(statsRunnable);
        // Ensure service keeps running even if activity is destroyed
        startAllServices();
    }
}
