package com.f1tvpatch.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from the patch fork's GitHub releases. A release counts as new when its tag differs
 * from the one this build was made for, or when its notes carry a different CI run ("run:&lt;id&gt;"),
 * i.e. the patch was rebuilt for the same F1TV version. Installs through PackageInstaller; every
 * build is signed with the same key, so it installs over this one and keeps the user's login.
 */
public final class Updater {
    private Updater() { }

    private static final String TAG = RadioEngine.TAG;
    private static final String PREFS = "f1tv_radio_patch";
    static final String KEY_AUTO_UPDATE = "auto_update";
    private static final String KEY_LAST_CHECK = "update_last_check";
    private static final String KEY_SKIP_RELEASE = "update_skip_release";
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final String ACTION_INSTALL_STATUS = "com.f1tvpatch.radio.INSTALL_STATUS";
    private static final Pattern RUN_MARKER = Pattern.compile("run:(\\d+)");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "patch-updater");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean lifecycleRegistered;
    private static boolean checkedThisProcess;
    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);

    static final class Release {
        final String tag;
        final String name;
        final String run;
        final String apkUrl;
        final long apkSize;

        Release(String tag, String name, String run, String apkUrl, long apkSize) {
            this.tag = tag;
            this.name = name;
            this.run = run;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }

        String id() { return tag + "#" + run; }
    }

    static boolean isConfigured() {
        return !Config.UPDATE_REPO.isEmpty();
    }

    static String currentVersionLabel() {
        return Config.RELEASE_TAG.isEmpty() ? "lokale build" : Config.RELEASE_TAG
                + (Config.BUILD_RUN.isEmpty() ? "" : " (run " + Config.BUILD_RUN + ")");
    }

    // ── Smali hook ───────────────────────────────────────────────────────────

    /** Called from F1TV's {@code SplashActivity.onCreate}: checks once a screen is in front. */
    public static void onLaunch(Activity launcher) {
        try {
            if (!isConfigured() || lifecycleRegistered) return;
            lifecycleRegistered = true;
            Application app = launcher.getApplication();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity a) {
                    resumedActivity = new WeakReference<>(a);
                    maybeAutoCheck(a);
                }
                @Override public void onActivityPaused(Activity a) {
                    if (resumedActivity.get() == a) resumedActivity = new WeakReference<>(null);
                }
                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable t) {
            Log.e(TAG, "updater init failed", t);
        }
    }

    private static void maybeAutoCheck(Activity activity) {
        if (checkedThisProcess) return;
        SharedPreferences prefs = prefs(activity);
        if (!prefs.getBoolean(KEY_AUTO_UPDATE, true)) return;
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return;
        checkedThisProcess = true;
        check(activity, false);
    }

    // ── Check ────────────────────────────────────────────────────────────────

    /** Looks for a new release; {@code userInitiated} also reports "up to date" and ignores skips. */
    static void check(Activity activity, boolean userInitiated) {
        final Context app = activity.getApplicationContext();
        WORKER.execute(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchLatest();
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.w(TAG, "update check failed", t);
            }
            prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            final Release found = release;
            final String failure = error;
            MAIN.post(() -> {
                Activity target = resumedActivity.get() != null ? resumedActivity.get() : activity;
                if (target == null || target.isFinishing()) return;
                if (found == null) {
                    if (userInitiated) toast(target, t("Controleren mislukt: ", "Check failed: ") + failure);
                    return;
                }
                boolean newer = isNewer(found);
                Log.i(TAG, "update check: latest=" + found.id() + " current=" + Config.RELEASE_TAG + "#" + Config.BUILD_RUN + " newer=" + newer);
                if (!newer) {
                    if (userInitiated) toast(target, t("Je hebt de nieuwste versie.", "You're on the latest version."));
                    return;
                }
                if (!userInitiated && found.id().equals(prefs(target).getString(KEY_SKIP_RELEASE, ""))) return;
                offer(target, found);
            });
        });
    }

    private static boolean isNewer(Release release) {
        if (!release.tag.equals(Config.RELEASE_TAG)) return true;
        return !release.run.isEmpty() && !release.run.equals(Config.BUILD_RUN);
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + Config.UPDATE_REPO + "/releases/latest").openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "f1tv-patch-updater");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        try {
            if (conn.getResponseCode() != 200) throw new IllegalStateException("HTTP " + conn.getResponseCode());
            JSONObject json = new JSONObject(readAll(conn.getInputStream()));
            String body = json.optString("body", "");
            Matcher run = RUN_MARKER.matcher(body);
            JSONArray assets = json.optJSONArray("assets");
            String apkUrl = null;
            long size = 0L;
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name", "").endsWith("-universal.apk")) {
                    apkUrl = asset.optString("browser_download_url", null);
                    size = asset.optLong("size", 0L);
                }
            }
            if (apkUrl == null) throw new IllegalStateException("release has no universal APK");
            return new Release(json.optString("tag_name", ""), json.optString("name", ""),
                    run.find() ? run.group(1) : "", apkUrl, size);
        } finally {
            conn.disconnect();
        }
    }

    // ── Offer + install ──────────────────────────────────────────────────────

    private static void offer(Activity activity, Release release) {
        new AlertDialog.Builder(activity)
                .setTitle(t("Update voor F1 TV (patch)", "F1 TV (patched) update"))
                .setMessage(String.format(Locale.getDefault(),
                        t("%s is beschikbaar.\nNu geïnstalleerd: %s.\n\nJe blijft ingelogd en je radio-instellingen blijven bewaard.",
                                "%s is available.\nInstalled now: %s.\n\nYou stay signed in and keep your radio settings."),
                        release.name.isEmpty() ? release.tag : release.name, currentVersionLabel()))
                .setPositiveButton(t("Installeren", "Install"), (d, w) -> install(activity, release))
                .setNeutralButton(t("Deze overslaan", "Skip this one"),
                        (d, w) -> prefs(activity).edit().putString(KEY_SKIP_RELEASE, release.id()).apply())
                .setNegativeButton(t("Later", "Later"), null)
                .show();
    }

    private static void install(Activity activity, Release release) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle(t("Toestemming nodig", "Permission needed"))
                    .setMessage(t("Sta F1 TV eenmalig toe om apps te installeren. Kies daarna opnieuw Installeren.",
                            "Allow F1 TV to install apps once, then choose Install again."))
                    .setPositiveButton(t("Naar instellingen", "Open settings"), (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Throwable t) {
                            toast(activity, t("Kon de instellingen niet openen.", "Couldn't open settings."));
                        }
                    })
                    .setNegativeButton(t("Annuleren", "Cancel"), null)
                    .show();
            return;
        }
        final Context app = activity.getApplicationContext();
        toast(activity, t("Update downloaden…", "Downloading update…"));
        WORKER.execute(() -> {
            try {
                File apk = new File(app.getCacheDir(), "patch-update.apk");
                download(release.apkUrl, apk);
                if (release.apkSize > 0 && apk.length() != release.apkSize) {
                    throw new IllegalStateException("download incomplete (" + apk.length() + "/" + release.apkSize + ")");
                }
                MAIN.post(() -> commitInstall(app, apk));
            } catch (Throwable t) {
                Log.e(TAG, "update download failed", t);
                MAIN.post(() -> toast(app, t("Download mislukt: ", "Download failed: ") + t.getMessage()));
            }
        });
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "f1tv-patch-updater");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } finally {
            conn.disconnect();
        }
    }

    private static void commitInstall(Context app, File apk) {
        try {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.getPackageName());
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    session.fsync(out);
                }
                registerStatusReceiver(app);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent status = PendingIntent.getBroadcast(app, sessionId,
                        new Intent(ACTION_INSTALL_STATUS).setPackage(app.getPackageName()), flags);
                session.commit(status.getIntentSender());
            }
        } catch (Throwable t) {
            Log.e(TAG, "update install failed", t);
            toast(app, t("Installeren mislukt: ", "Install failed: ") + t.getMessage());
        }
    }

    private static boolean receiverRegistered;

    private static void registerStatusReceiver(Context app) {
        if (receiverRegistered) return;
        receiverRegistered = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(confirm);
                    }
                } else if (status != PackageInstaller.STATUS_SUCCESS) {
                    String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Log.w(TAG, "update install status=" + status + " " + message);
                    toast(context, t("Installeren mislukt: ", "Install failed: ") + message);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toString("UTF-8");
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    static String t(String nl, String en) {
        return "nl".equals(Locale.getDefault().getLanguage()) ? nl : en;
    }
}
