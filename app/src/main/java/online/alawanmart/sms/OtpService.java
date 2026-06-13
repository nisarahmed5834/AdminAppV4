package online.alawanmart.sms;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * OtpService — polls server for pending OTPs, sends SMS to customers
 * Called every 10 seconds from MonitorService
 */
public class OtpService {

    private static final String TAG     = "AlwanMartOTP";
    private static final int    TIMEOUT = 10_000;

    public static void checkAndSendOTPs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("alawanmart_sms", Context.MODE_PRIVATE);
        String serverUrl = prefs.getString("server_url", "https://alawanmart.online");
        String token     = prefs.getString("api_token", "");
        if (token.isEmpty()) return;

        try {
            // Poll for pending OTPs
            String endpoint = serverUrl.replaceAll("/$", "") + "/api/get_otp_queue.php";
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-AlawanMart-Token", token);
            conn.setRequestProperty("X-AlwanMart-Token", token);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);

            if (conn.getResponseCode() != 200) { conn.disconnect(); return; }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            JSONObject response = new JSONObject(sb.toString());
            if (!response.optBoolean("success", false)) return;

            JSONArray pending = response.optJSONArray("pending");
            if (pending == null || pending.length() == 0) return;

            Log.d(TAG, "Found " + pending.length() + " pending OTP(s)");

            for (int i = 0; i < pending.length(); i++) {
                JSONObject item  = pending.getJSONObject(i);
                String otpId    = item.optString("id");
                String phone    = item.optString("customer_phone");
                String otpCode  = item.optString("otp");

                if (otpId.isEmpty() || phone.isEmpty() || otpCode.isEmpty()) continue;

                Log.d(TAG, "Sending OTP " + otpCode + " to " + phone);
                boolean sent = sendOtpSms(phone, otpCode);
                markOtpStatus(serverUrl, token, otpId, sent ? "sent" : "failed");
            }

        } catch (Exception e) {
            Log.e(TAG, "OTP check error: " + e.getMessage());
        }
    }

    private static boolean sendOtpSms(String phone, String otpCode) {
        try {
            // Normalize to Pakistani format 03XXXXXXXXX
            String normalized = phone.replaceAll("[^0-9]", "");
            if (normalized.length() == 10)  normalized = "0" + normalized;
            if (normalized.startsWith("92") && normalized.length() == 12)
                normalized = "0" + normalized.substring(2);
            if (normalized.startsWith("0092") && normalized.length() == 14)
                normalized = "0" + normalized.substring(4);

            String message = "AlwanMart OTP: " + otpCode
                + "\nYour order confirmation code. Valid 5 min. Do not share.";

            SmsManager sms = SmsManager.getDefault();
            sms.sendTextMessage(normalized, null, message, null, null);
            Log.d(TAG, "OTP SMS sent to " + normalized);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "OTP SMS failed: " + e.getMessage());
            return false;
        }
    }

    private static void markOtpStatus(String serverUrl, String token, String otpId, String status) {
        try {
            String endpoint = serverUrl.replaceAll("/$", "") + "/api/mark_otp_sent.php";
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-AlawanMart-Token", token);
            conn.setRequestProperty("X-AlwanMart-Token", token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);

            String params = "otp_id=" + URLEncoder.encode(otpId, "UTF-8")
                          + "&status=" + URLEncoder.encode(status, "UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes("UTF-8"));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "markOtpStatus error: " + e.getMessage());
        }
    }
}
