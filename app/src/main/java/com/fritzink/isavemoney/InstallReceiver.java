package com.fritzink.isavemoney;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONObject;

/**
 * Gets the result of an in-app update. Using a receiver (not the activity) means Android's callbacks never
 * restart the app halfway through; failures are remembered so the page can explain them and offer another way.
 */
public class InstallReceiver extends BroadcastReceiver {
    static final String ACTION = "com.fritzink.isavemoney.INSTALL_STATUS";

    @Override
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) { c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().remove("fail").apply(); return; }
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String why = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "The update was cancelled."
                : status == PackageInstaller.STATUS_FAILURE_BLOCKED ? "The phone blocked the update."
                : status == PackageInstaller.STATUS_FAILURE_CONFLICT ? "Android said it conflicts with the installed app."
                : status == PackageInstaller.STATUS_FAILURE_STORAGE ? "Not enough space on the phone."
                : "The update didn't install.";
        if (msg != null && !msg.isEmpty()) why += " (" + msg + ")";
        c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("fail", why).putLong("failAt", System.currentTimeMillis()).apply();
        MainActivity.tellPage("onUpdateStage('fail'," + JSONObject.quote(why) + ")");
    }
}
