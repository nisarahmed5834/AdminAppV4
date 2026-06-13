package online.alawanmart.sms;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SetupActivity extends AppCompatActivity {

    SharedPreferences prefs;
    ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("alawanmart_sms", MODE_PRIVATE);

        // Already configured — go straight to MainActivity, skip setup
        if (!prefs.getString("api_token", "").isEmpty()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_setup);

        EditText urlInput    = findViewById(R.id.inputUrl);
        EditText tokenInput  = findViewById(R.id.inputToken);
        Button   saveBtn     = findViewById(R.id.btnSave);
        Button   testBtn     = findViewById(R.id.btnTest);
        TextView testResult  = findViewById(R.id.testResult);

        // Pre-fill saved values
        urlInput.setText(prefs.getString("server_url", "https://alawanmart.online"));
        tokenInput.setText(prefs.getString("api_token", ""));

        // Test connection
        testBtn.setOnClickListener(v -> {
            String url   = urlInput.getText().toString().trim();
            String token = tokenInput.getText().toString().trim();
            if (url.isEmpty() || token.isEmpty()) {
                testResult.setText("⚠️ Enter URL and token first");
                testResult.setTextColor(0xFFF59E0B);
                return;
            }
            testBtn.setText("Testing...");
            testBtn.setEnabled(false);
            testResult.setText("Connecting...");
            testResult.setTextColor(0xFF64748B);

            executor.execute(() -> {
                boolean ok = testConnection(url, token);
                runOnUiThread(() -> {
                    testBtn.setText("Test Connection");
                    testBtn.setEnabled(true);
                    if (ok) {
                        testResult.setText("✅ Connected! Tap Save & Start to continue.");
                        testResult.setTextColor(0xFF10B981);
                    } else {
                        testResult.setText("❌ Failed. Check URL and token match the admin page.");
                        testResult.setTextColor(0xFFEF4444);
                    }
                });
            });
        });

        // Save & start
        saveBtn.setOnClickListener(v -> {
            String url   = urlInput.getText().toString().trim();
            String token = tokenInput.getText().toString().trim();
            if (url.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "⚠️ Both fields required", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit()
                .putString("server_url", url)
                .putString("api_token", token)
                .putBoolean("monitoring_enabled", true)
                .apply();

            Toast.makeText(this, "✅ Saved! Monitoring enabled.", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // If already set up, go to main — don't close
        if (!prefs.getString("api_token", "").isEmpty()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        } else {
            moveTaskToBack(true);
        }
    }


    private boolean testConnection(String serverUrl, String token) {
        try {
            String endpoint = serverUrl.replaceAll("/$","") + "/api/receive_sms.php";
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            conn.setRequestProperty("X-AlawanMart-Token", token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            String params = "token=" + URLEncoder.encode(token,"UTF-8") + "&test=1";
            OutputStream os = conn.getOutputStream();
            os.write(params.getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) { return false; }
    }
}
