package com.fritzink.isavemoney;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInstaller;
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
    private static final int REQ_FILE = 7;
    private static final String RELEASES = "https://api.github.com/repos/itsfritz2k6/money-tiles/releases/latest";
    private static final String ACTION_INSTALLED = "com.fritzink.isavemoney.UPDATE_RESULT";
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private String pendingUpdate;      // APK url waiting on the "install unknown apps" permission
    private boolean waitingPerm;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient());
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
        if (waitingPerm) {
            waitingPerm = false;
            if (canInstall()) downloadAndInstall(pendingUpdate);
            else js("onUpdateStage('fail','Money Tiles needs permission to install its own updates.')");
        }
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

    /** Streams the APK straight into an install session, then hands Android the confirm step. */
    private void downloadAndInstall(String url) {
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "MoneyTiles");
                int len = c.getContentLength();
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                int sid = pi.createSession(params);
                session = pi.openSession(sid);
                try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("base.apk", 0, len > 0 ? len : -1)) {
                    byte[] buf = new byte[16384];
                    long got = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int pct = len > 0 ? (int) (got * 100 / len) : 0;
                        if (pct != lastPct) { lastPct = pct; jsRaw("window.onUpdateProgress&&onUpdateProgress(" + pct + ")"); }
                    }
                    session.fsync(out);
                }
                Intent done = new Intent(this, MainActivity.class).setAction(ACTION_INSTALLED);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent cb = PendingIntent.getActivity(this, 1, done, flags);
                session.commit(cb.getIntentSender());
                session.close();
                js("onUpdateStage('install')");
            } catch (Exception e) {
                if (session != null) session.abandon();
                js("onUpdateStage('fail'," + JSONObject.quote("The download didn't finish. Check the internet and try again.") + ")");
            }
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
            new Thread(() -> {
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
                jsRaw("window.onUpdateInfo&&onUpdateInfo(" + out + ")");
            }).start();
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
