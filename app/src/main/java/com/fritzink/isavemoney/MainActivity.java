package com.fritzink.isavemoney;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** The whole app is the offline page in assets/index.html; this activity hosts it and gives it file saving and self-updates. */
public class MainActivity extends Activity {
    private static MainActivity live;   // the visible page, for update results that arrive in the background
    private static final int REQ_FILE = 7;
    private static final String ACTION_INSTALLED = "com.fritzink.isavemoney.UPDATE_RESULT";
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private String pendingUpdate;      // APK url waiting on the "install unknown apps" permission
    private boolean waitingPerm;

    /** Writes any crash to a file so the next launch can show it (and she can send it). */
    private void catchCrashes() {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof CrashLog) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashLog(getApplicationContext(), prev));
    }

    static class CrashLog implements Thread.UncaughtExceptionHandler {
        private final android.content.Context ctx;
        private final Thread.UncaughtExceptionHandler prev;
        CrashLog(android.content.Context c, Thread.UncaughtExceptionHandler p) { ctx = c; prev = p; }
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try (java.io.PrintWriter w = new java.io.PrintWriter(new FileOutputStream(new File(ctx.getFilesDir(), "crash.txt")))) {
                w.println("Money Tiles " + UpdateJob.installed(ctx) + " on Android " + Build.VERSION.RELEASE + " (" + Build.MANUFACTURER + " " + Build.MODEL + ")");
                w.println(new java.util.Date());
                e.printStackTrace(w);
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        catchCrashes();
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            // If Android's web engine crashes, don't let it take the whole app down: start the page again.
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                try {
                    android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
                    if (parent != null) parent.removeView(view);
                    view.destroy();
                } catch (Exception ignored) { }
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose a backup file"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        // background check + notification for new versions; the page itself blocks use until she updates
        live = this;
        // none of these extras may ever stop the app from opening
        try { UpdateJob.schedule(this); } catch (Throwable ignored) { }
        try { UpdateJob.cancelNote(this); } catch (Throwable ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        } catch (Throwable ignored) { }
        if (saved != null) web.restoreState(saved);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            fileCb.onReceiveValue(u == null ? null : new Uri[]{u});
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { UpdateJob.cancelNote(this); } catch (Throwable ignored) { }
        if (waitingPerm) {
            waitingPerm = false;
            if (canInstall()) downloadAndInstall(pendingUpdate);
            else js("onUpdateStage('fail','Money Tiles needs permission to install its own updates.')");
        }
    }

    @Override
    protected void onDestroy() {
        if (live == this) live = null;
        super.onDestroy();
    }

    static void tellPage(String code) {
        MainActivity a = live;
        if (a != null) a.runOnUiThread(() -> a.web.evaluateJavascript("window.onUpdateStage&&(" + code + ")", null));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!ACTION_INSTALLED.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android shows its own "Update this app?" screen; the user has to tap Update.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            js("onUpdateStage('fail'," + JSONObject.quote("The update didn't install" + (msg == null ? "." : ": " + msg)) + ")");
        }
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript("window.onUpdateStage&&(" + code + ")", null));
    }

    private void jsRaw(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    /**
     * The phone's own download manager fetches the new version (with its usual notification), then Android's
     * standard "Update this app?" screen opens from here, while the app is in front. That is the most dependable
     * way to update an app that isn't from the Play Store.
     */
    private void downloadAndInstall(String url) {
        DownloadManager dm = getSystemService(DownloadManager.class);
        File old = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MoneyTiles-update.apk");
        if (old.exists()) old.delete();
        long id;
        try {
            id = dm.enqueue(new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Money Tiles update")
                    .setDescription("Downloading the new version")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "MoneyTiles-update.apk"));
        } catch (Exception e) {
            js("onUpdateStage('fail'," + JSONObject.quote("The phone couldn't start the download.") + ")");
            return;
        }
        new Thread(() -> {
            int lastPct = -1;
            for (int tries = 0; tries < 1200; tries++) {        // up to 10 minutes
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c == null || !c.moveToFirst()) break;
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long all = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    int pct = all > 0 ? (int) (got * 100 / all) : 0;
                    if (pct != lastPct) { lastPct = pct; jsRaw("window.onUpdateProgress&&onUpdateProgress(" + pct + ")"); }
                    if (st == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri apk = dm.getUriForDownloadedFile(id);
                        runOnUiThread(() -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(apk, "application/vnd.android.package-archive")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                                js("onUpdateStage('install')");
                            } catch (Exception e) {
                                js("onUpdateStage('fail'," + JSONObject.quote("The phone wouldn't open the installer. Use the browser download below.") + ")");
                            }
                        });
                        return;
                    }
                    if (st == DownloadManager.STATUS_FAILED) break;
                } catch (Exception e) { break; }
            }
            js("onUpdateStage('fail'," + JSONObject.quote("The download didn't finish. Check the internet and try again.") + ")");
        }).start();
    }

    @Override
    public void onBackPressed() {
        // The page closes its own sheets and returns to Home first; only then does back leave the app.
        web.evaluateJavascript("(window.appBack&&appBack())?'1':'0'", r -> {
            if (!"\"1\"".equals(r)) finish();
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void save(String name, String text, String mime) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            String where;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                    where = "Downloads";
                } else {
                    File f = new File(getExternalFilesDir(null), name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    where = f.getParent();
                }
                toast("Saved " + name + " to " + where);
            } catch (Exception e) {
                toast("Could not save: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public String versionName() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
        }

        /** Asks GitHub for the newest release; answers through window.onUpdateInfo({ok, tag, url, notes}). */
        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> jsRaw("window.onUpdateInfo&&onUpdateInfo(" + UpdateJob.latest() + ")")).start();
        }

        /** The report from the last crash, once (then it's cleared). */
        @JavascriptInterface
        public String lastCrash() {
            File f = new File(getFilesDir(), "crash.txt");
            if (!f.exists()) return "";
            try (java.util.Scanner sc = new java.util.Scanner(f, "UTF-8").useDelimiter("\\A")) {
                String s = sc.hasNext() ? sc.next() : "";
                f.delete();
                return s.length() > 6000 ? s.substring(0, 6000) : s;
            } catch (Exception e) { f.delete(); return ""; }
        }

        /** Android's share sheet (text it, email it...). */
        @JavascriptInterface
        public void share(String subject, String text) {
            runOnUiThread(() -> {
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), "Send to Fritz"));
                } catch (Exception e) { toast("Couldn't open sharing"); }
            });
        }

        /** Why the last in-app update failed, if it did (cleared when one succeeds). */
        @JavascriptInterface
        public String lastInstall() {
            return getSharedPreferences("updates", MODE_PRIVATE).getString("fail", "");
        }

        /** Fallback: let the browser download the new version and Android's own installer handle it. */
        @JavascriptInterface
        public void openDownload(String url) {
            if (!url.startsWith("https://github.com/itsfritz2k6/money-tiles/")) return;
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("No browser found"); } });
        }

        @JavascriptInterface
        public void installUpdate(String url) {
            if (!url.startsWith("https://github.com/itsfritz2k6/money-tiles/")) return;   // only ever our own releases
            pendingUpdate = url;
            if (!canInstall()) {
                waitingPerm = true;
                js("onUpdateStage('perm')");
                runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
                return;
            }
            downloadAndInstall(url);
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
