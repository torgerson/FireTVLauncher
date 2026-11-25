package com.torgcrafted.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the launcher when the TV finishes starting up. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            HomeWatchService.ensureEnabled(context);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
        HomeWatchService.ensureEnabled(context);
        Intent i = new Intent(context, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
