package com.fritzink.isavemoney;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub every few hours, even with the app closed, and posts a notification when a newer version is out. */
public class UpdateJob extends JobService {
    static final String RELEASES = "https://api.github.com/repos/itsfritz2k6/money-tiles/releases/latest";
    static final String CHANNEL = "updates";
    static final int NOTE_ID = 7;
    private static final int JOB_ID = 42;

    /** Newest release as {ok, tag, url, notes}. Network call: never on the UI thread. */
    static JSONObject latest() {
        JSONObject out = new JSONObject();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "MoneyTiles");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            JSONObject j = new JSONObject(b.toString("UTF-8"));
            String apk = "";
            JSONArray assets = j.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
            }
            out.put("ok", !apk.isEmpty()).put("tag", j.optString("tag_name")).put("url", apk).put("notes", j.optString("body"));
        } catch (Exception e) {
            try { out.put("ok", false); } catch (Exception ignored) { }
        }
        return out;
    }

    static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    /** True when version a ("v2.6.0" or "2.6.0") is newer than b. */
    static boolean newer(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("\\."), y = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < 3; i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int parse(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; } }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(4 * 3600 * 1000L)
                .setPersisted(true)
                .build());
    }

    static void cancelNote(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTE_ID);
    }

    private void notifyUpdate(String tag) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Tells you when a new version of Money Tiles is ready");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nm.notify(NOTE_ID, nb.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Money Tiles needs an update")
                .setContentText("Version " + tag.replaceFirst("^v", "") + " is ready. Tap to update; your entries stay.")
                .setContentIntent(pi)
                .setOngoing(true)        // stays put until she opens the app (which then makes her update)
                .setAutoCancel(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            JSONObject r = latest();
            String tag = r.optString("tag");
            if (r.optBoolean("ok") && newer(tag, installed(this))) {
                SharedPreferences sp = getSharedPreferences("updates", MODE_PRIVATE);
                if (!tag.equals(sp.getString("notified", ""))) { notifyUpdate(tag); sp.edit().putString("notified", tag).apply(); }
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
