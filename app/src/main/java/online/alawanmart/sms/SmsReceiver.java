package online.alawanmart.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SmsReceiver — reads every incoming SMS
 * Sends payment SMS to server immediately
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG      = "AlwanMartSMS";
    private static final int    TIMEOUT  = 15_000; // 15s timeout for server calls
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("alawanmart_sms", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("monitoring_enabled", false)) return;

        String serverUrl = prefs.getString("server_url", "https://alawanmart.online");
        String apiToken  = prefs.getString("api_token", "");
        if (apiToken.isEmpty()) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus  = (Object[]) bundle.get("pdus");
        String   format = bundle.getString("format");
        if (pdus == null) return;

        for (Object pdu : pdus) {
            SmsMessage msg;
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    msg = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (msg == null) continue;
            } catch (Exception e) {
                Log.e(TAG, "SMS parse error: " + e.getMessage());
                continue;
            }

            String sender = msg.getDisplayOriginatingAddress();
            String body   = msg.getMessageBody();
            long   ts     = msg.getTimestampMillis();

            Log.d(TAG, "SMS from: " + sender + " | " + body.substring(0, Math.min(50, body.length())));

            final String fSender = sender;
            final String fBody   = body;
            final long   fTs     = ts;
            executor.execute(() -> sendToServer(serverUrl, apiToken, fSender, fBody, fTs));
        }
    }

    private void sendToServer(String serverUrl, String token, String sender, String body, long ts) {
        try {
            String endpoint = serverUrl.replaceAll("/$", "") + "/api/receive_sms.php";
            HttpURLConnection conn = openConnection(endpoint, token, TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            String params = "token="  + URLEncoder.encode(token,  "UTF-8")
                          + "&sender=" + URLEncoder.encode(sender, "UTF-8")
                          + "&body="   + URLEncoder.encode(body,   "UTF-8")
                          + "&ts="     + ts;

            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "SMS sent to server: HTTP " + code);
            conn.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "sendToServer error: " + e.getMessage());
        }
    }

    /**
     * Called from MonitorService every 60 seconds to update last_ping
     */
    public static void sendPing(String serverUrl, String token) {
        try {
            String endpoint = serverUrl.replaceAll("/$", "") + "/api/receive_sms.php";
            HttpURLConnection conn = openConnection(endpoint, token, 8_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            String params = "token=" + URLEncoder.encode(token, "UTF-8") + "&ping=1";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes("UTF-8"));
            }

            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Ping error: " + e.getMessage());
        }
    }

    private static HttpURLConnection openConnection(String endpoint, String token, int timeout) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("X-AlawanMart-Token", token);
        conn.setRequestProperty("X-AlwanMart-Token", token); // both spellings
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        return conn;
    }
}
