package com.jacsi.gasmonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

public class MainActivity extends Activity {

    private EditText phoneInput;
    private TextView gasText, badge, statusText;
    private ProgressBar gasBar;
    private SharedPreferences prefs;
    private ScrollView scroll;
    private WebView webView;
    private boolean webShown = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            int gas = prefs.getInt("last_gas", -1);
            boolean danger = gas > MonitorService.THRESHOLD;
            gasText.setText(gas < 0 ? "-- %" : gas + " %");
            gasBar.setProgress(Math.max(gas, 0));
            int color = gas < 0 ? Color.GRAY : (danger ? Color.parseColor("#FF5252") : Color.parseColor("#00E676"));
            gasText.setTextColor(color);
            gasBar.setProgressTintList(ColorStateList.valueOf(color));
            badge.setText(gas < 0 ? "CHECKING..." : (danger ? "DANGER - GAS HIGH" : "SAFE"));
            badge.setBackground(round(color, 40, 0.25f));
            badge.setTextColor(color);
            statusText.setText(prefs.getString("last_status", "Not started"));
            handler.postDelayed(this, 2000);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp, float alpha) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setAlpha((int) (alpha * 255));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        c.setBackground(round(Color.WHITE, 24, 0.12f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, 0);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, String hexColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(Color.parseColor(hexColor), 16, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(12), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE);

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0F2027"), Color.parseColor("#203A43"), Color.parseColor("#2C5364")});

        scroll = new ScrollView(this);
        scroll.setBackground(bg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(48), dp(20), dp(32));
        scroll.addView(root);

        TextView robot = text("\uD83E\uDD16", 44, Color.WHITE, false);
        robot.setGravity(Gravity.CENTER);
        root.addView(robot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = text("SMART INDUSTRIAL\nSAFETY ROBOT", 22, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.08f);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout gasCard = card();
        TextView gasLabel = text("\uD83D\uDCA8  GAS LEVEL", 14, Color.parseColor("#B0BEC5"), true);
        gasLabel.setLetterSpacing(0.1f);
        gasCard.addView(gasLabel);

        gasText = text("-- %", 60, Color.WHITE, true);
        gasText.setGravity(Gravity.CENTER);
        gasCard.addView(gasText);

        gasBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        gasBar.setMax(100);
        gasBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#37474F")));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        barLp.setMargins(0, dp(8), 0, dp(16));
        gasCard.addView(gasBar, barLp);

        badge = text("CHECKING...", 14, Color.GRAY, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(16), dp(10), dp(16), dp(10));
        gasCard.addView(badge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusText = text("Not started", 12, Color.parseColor("#90A4AE"), false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(12), 0, 0);
        gasCard.addView(statusText);
        root.addView(gasCard);

        LinearLayout setCard = card();
        setCard.addView(text("\uD83D\uDCF1  ALERT NUMBER (SMS + CALL)", 14, Color.parseColor("#B0BEC5"), true));
        TextView hint = text("Include country code, e.g. +919876543210", 12, Color.parseColor("#90A4AE"), false);
        hint.setPadding(0, dp(4), 0, dp(8));
        setCard.addView(hint);

        phoneInput = new EditText(this);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setHint("+91XXXXXXXXXX");
        phoneInput.setHintTextColor(Color.parseColor("#78909C"));
        phoneInput.setTextColor(Color.WHITE);
        phoneInput.setText(prefs.getString("phone", ""));
        phoneInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        phoneInput.setBackground(round(Color.BLACK, 14, 0.3f));
        setCard.addView(phoneInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        Button save = button("Save & Start Monitoring", "#00B0FF");
        save.setOnClickListener(v -> saveAndStart());
        setCard.addView(save);

        Button web = button("View Live Website", "#546E7A");
        web.setOnClickListener(v -> showWeb());
        setCard.addView(web);
        Button test = button("Test Voice Alert", "#8E24AA");
        test.setOnClickListener(v -> testVoice());
        setCard.addView(test);
        root.addView(setCard);

        setContentView(scroll);
    }

    private void testVoice() {
        final TextToSpeech[] t = new TextToSpeech[1];
        t[0] = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                t[0].setLanguage(Locale.US);
                t[0].speak("Warning. Gas leakage detected. This is a test.",
                        TextToSpeech.QUEUE_FLUSH, null, "test");
            } else {
                Toast.makeText(this, "Voice engine not available. Install Google Text-to-speech.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showWeb() {
        if (webView == null) {
            webView = new WebView(this);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl(MonitorService.SITE_URL);
        } else {
            webView.reload();
        }
        webShown = true;
        setContentView(webView);
        Toast.makeText(this, "Press Back to return", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (webShown) {
            webShown = false;
            setContentView(scroll);
        } else {
            super.onBackPressed();
        }
    }

    private void saveAndStart() {
        String number = phoneInput.getText().toString().replaceAll("[\\s\\-()]", "");
        if (!number.matches("^\\+[1-9][0-9]{7,14}$")) {
            Toast.makeText(this, "Enter number with country code, e.g. +919876543210", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString("phone", number).apply();

        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.SEND_SMS);
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= 28 &&
                checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ANSWER_PHONE_CALLS);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 100);
        } else {
            startMonitor();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        startMonitor();
    }

    private void startMonitor() {
        Intent svc = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
        if (!prefs.getString("phone", "").isEmpty()
                && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            Intent svc = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }
}
