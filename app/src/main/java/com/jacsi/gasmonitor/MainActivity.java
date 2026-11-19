package com.jacsi.gasmonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText phoneInput;
    private TextView gasText, statusText;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            int gas = prefs.getInt("last_gas", -1);
            gasText.setText(gas < 0 ? "-- %" : gas + " %");
            gasText.setTextColor(gas > MonitorService.THRESHOLD ? Color.RED : Color.parseColor("#2E7D32"));
            statusText.setText(prefs.getString("last_status", "Not started"));
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Smart Industrial Safety Robot");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView label = new TextView(this);
        label.setText("GAS LEVEL");
        label.setTextSize(16);
        label.setPadding(0, 48, 0, 0);
        root.addView(label);

        gasText = new TextView(this);
        gasText.setTextSize(56);
        gasText.setText("-- %");
        root.addView(gasText);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        TextView hint = new TextView(this);
        hint.setText("\nAlert mobile number (with country code, e.g. +919876543210):");
        root.addView(hint);

        phoneInput = new EditText(this);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setHint("+91XXXXXXXXXX");
        phoneInput.setText(prefs.getString("phone", ""));
        root.addView(phoneInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("Save & Start Monitoring");
        save.setOnClickListener(v -> saveAndStart());
        root.addView(save);

        setContentView(root);
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
        startMonitor(); // start anyway; SMS/notification only work if permitted
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
        // Resume monitoring automatically if number already saved
        if (!prefs.getString("phone", "").isEmpty()
                && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            startMonitor();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }
}
