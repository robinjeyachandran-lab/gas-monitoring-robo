package com.jacsi.gasmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            String phone = context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
                    .getString("phone", "");
            if (!phone.isEmpty()) {
                Intent svc = new Intent(context, MonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
                else context.startService(svc);
            }
        }
    }
}
