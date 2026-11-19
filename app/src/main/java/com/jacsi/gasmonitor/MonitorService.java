package com.jacsi.gasmonitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.telecom.TelecomManager;
import android.os.PowerManager;
import android.telephony.SmsManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MonitorService extends Service {

    static final String PREFS = "gas_prefs";
    static final String SITE_URL = "https://iot-projects.eweb.org.in/JACSI/Gas/";
    static final int THRESHOLD = 70;               // alert when gas level is ABOVE this
    static final long POLL_MS = 5000;              // check every 5 seconds
    static final long COOLDOWN_MS = 5 * 60 * 1000; // repeat alert at most every 5 min while still high

    static final long CALL_WAIT_MS = 12000;   // wait after dialing before speaking
    static final long CALL_END_MS = 60000;    // hang up this long after dialing

    private TextToSpeech tts;

    private static final String CH_MONITOR = "gas_monitor";
    private static final String CH_ALERT = "gas_alert";

    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;
    private long lastAlertTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CH_MONITOR)
                .setContentTitle("Gas Monitor running")
                .setContentText("Watching gas level (alert above " + THRESHOLD + "%)")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }

        if (!running) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gasmonitor:wl");
            wakeLock.acquire();
            worker = new Thread(this::loop, "gas-worker");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            try {
                int gas = fetchGas();
                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                if (gas >= 0) {
                    prefs.edit().putInt("last_gas", gas)
                            .putString("last_status", "Last update " + time).apply();
                    handleGas(gas);
                } else {
                    prefs.edit().putString("last_status", "Could not read gas value (" + time + ")").apply();
                }
            } catch (Exception e) {
                prefs.edit().putString("last_status", "Network error: " + e.getMessage()).apply();
            }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException ignored) { }
        }
    }

    private void handleGas(int gas) {
        if (gas > THRESHOLD) {
            long now = System.currentTimeMillis();
            if (now - lastAlertTime >= COOLDOWN_MS) {
                lastAlertTime = now;
                showAlert(gas);
                sendSms(gas);
                placeAlertCall(gas);
            }
        } else {
            lastAlertTime = 0; // gas back to normal -> next crossing alerts immediately
        }
    }

    private int fetchGas() throws Exception {
        URL url = new URL(SITE_URL + "?t=" + System.currentTimeMillis());
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = c.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return parseGas(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    /** Finds the number shown after "GAS LEVEL" (e.g. "71 %"). Returns -1 if not found. */
    static int parseGas(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>", " ")
                       .replaceAll("(?is)<style.*?</style>", " ")
                       .replaceAll("(?s)<[^>]*>", " ")
                       .replace("&nbsp;", " ");
        int idx = s.toUpperCase(Locale.ROOT).indexOf("GAS LEVEL");
        if (idx < 0) return -1;
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(s.substring(idx));
        if (m.find()) return (int) Math.round(Double.parseDouble(m.group(1)));
        return -1;
    }

    private void showAlert(int gas) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setContentTitle("GAS ALERT!")
                .setContentText("Gas level is " + gas + "% (above " + THRESHOLD + "%)")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(2, n);
    }

    private void sendSms(int gas) {
        String phone = prefs.getString("phone", "");
        if (phone.isEmpty()) return;
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString("last_status", "SMS permission not granted").apply();
            return;
        }
        try {
            SmsManager sms = Build.VERSION.SDK_INT >= 31
                    ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            String text = "GAS ALERT: Gas level is " + gas + "% (above " + THRESHOLD
                    + "%). - Smart Industrial Safety Robot";
            ArrayList<String> parts = sms.divideMessage(text);
            sms.sendMultipartTextMessage(phone, null, parts, null, null);
            prefs.edit().putString("last_status", "SMS sent to " + phone).apply();
        } catch (Exception e) {
            prefs.edit().putString("last_status", "SMS failed: " + e.getMessage()).apply();
        }
    }

    private void placeAlertCall(int gas) {
        String phone = prefs.getString("phone", "");
        if (phone.isEmpty()) return;
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString("last_status", "Call permission not granted").apply();
            return;
        }
        Uri uri = Uri.fromParts("tel", phone, null);
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            tm.placeCall(uri, new Bundle());
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_CALL, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e2) {
                prefs.edit().putString("last_status", "Call failed: " + e2.getMessage()).apply();
                return;
            }
        }
        prefs.edit().putString("last_status", "Calling " + phone).apply();
        speakDuringCall(gas);
    }

    private void speakDuringCall(final int gas) {
        new Thread(() -> {
            try {
                Thread.sleep(CALL_WAIT_MS);
                final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                am.setSpeakerphoneOn(true);
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                        am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);

                final String msg = "Warning. Gas leakage detected. Gas level is " + gas
                        + " percent. Please take immediate action.";
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    tts = new TextToSpeech(getApplicationContext(), status -> {
                        if (status == TextToSpeech.SUCCESS) {
                            tts.setLanguage(Locale.US);
                            tts.setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                            for (int i = 0; i < 3; i++) {
                                tts.speak(msg, TextToSpeech.QUEUE_ADD, null, "gas" + i);
                            }
                        }
                    });
                });

                Thread.sleep(Math.max(CALL_END_MS - CALL_WAIT_MS, 10000));
                if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
                if (Build.VERSION.SDK_INT >= 28 && checkSelfPermission(
                        Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    ((TelecomManager) getSystemService(TELECOM_SERVICE)).endCall();
                }
                am.setSpeakerphoneOn(false);
            } catch (Exception ignored) { }
        }, "gas-call").start();
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_MONITOR, "Monitoring",
                NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Gas alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        nm.createNotificationChannel(alert);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
