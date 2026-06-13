# AlwanMart SMS Monitor — Android App

## What it does
- Runs 24/7 as a foreground service
- Reads every incoming SMS automatically
- Sends SMS data to your AlwanMart server
- Pings server every 90 seconds (so dashboard shows "Connected — Live")
- Survives phone reboot (auto-restarts)
- ONE toggle only: Enable / Disable monitoring

## How to build (Android Studio)
1. Open Android Studio
2. File → Open → select this folder (AlwanMartSMS)
3. Wait for Gradle sync
4. Build → Generate Signed APK → or just Run on device

## First time setup on phone
1. Install APK
2. Open app
3. Enter: Server URL = https://alawanmart.online
4. Enter: API Token = (copy from Admin → SMS App page → copy token)
5. Tap "Test Connection" → should say ✅ Connected
6. Tap "Save & Continue"
7. Enable the toggle

## Files
- SetupActivity.java  — first-run setup screen (URL + token)
- MainActivity.java   — the single toggle screen
- SmsReceiver.java    — catches every SMS and sends to server
- MonitorService.java — foreground service (keeps app alive 24/7)
- BootReceiver.java   — auto-restarts on phone reboot

## Important: Android battery saver
On some phones (Xiaomi, Samsung, Huawei) you must:
- Go to phone Settings → Battery → App Battery Saver → AlwanMart SMS → No restrictions
- Or: Settings → Apps → AlwanMart SMS → Battery → Unrestricted

This prevents the OS from killing the app.
