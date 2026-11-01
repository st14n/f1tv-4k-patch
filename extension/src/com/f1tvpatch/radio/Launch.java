package com.f1tvpatch.radio;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

/**
 * Called once F1TV's launch activity is created (SplashActivity.onCreate in 3.0.x, else the
 * manifest's launcher activity): starts the patch's app-wide helpers.
 */
public final class Launch {
    private Launch() { }

    static final String KEY_F1_LOGS = "f1_debug_logs";
    private static boolean f1LogsPlanted;

    public static void onLaunch(Activity launcher) {
        try {
            Updater.loadLanguage(launcher);
            seedPrivateLogin(launcher);
        } catch (Throwable t) {
            Log.w(RadioEngine.TAG, "launch setup failed", t);
        }
        try {
            if (Config.DEBUG_LOGS || Updater.prefs(launcher).getBoolean(KEY_F1_LOGS, false)) {
                enableF1Logs(launcher);
            }
        } catch (Throwable t) {
            Log.w(RadioEngine.TAG, "F1 log setup failed", t);
        }
        try {
            AutoLogin.init(launcher);
        } catch (Throwable t) {
            Log.w(RadioEngine.TAG, "auto-login setup failed", t);
        }
        try {
            TvSettingsEntry.install(launcher);
        } catch (Throwable t) {
            Log.w(RadioEngine.TAG, "settings button setup failed", t);
        }
        Updater.onLaunch(launcher);
    }

    /** Private builds carry the account (build.sh F1TV_LOGIN_*): store it once, encrypted, like set_login.sh. */
    private static void seedPrivateLogin(Context c) throws Exception {
        if (Config.LOGIN_EMAIL_B64.isEmpty() || Config.LOGIN_PASSWORD_B64.isEmpty() || LoginStore.has(c)) return;
        String email = new String(android.util.Base64.decode(Config.LOGIN_EMAIL_B64, android.util.Base64.DEFAULT), "UTF-8");
        String password = new String(android.util.Base64.decode(Config.LOGIN_PASSWORD_B64, android.util.Base64.DEFAULT), "UTF-8");
        LoginStore.save(c, email, password);
        Log.i(RadioEngine.TAG, "account from this build stored (" + LoginReceiver.mask(email) + ")");
    }

    /**
     * F1TV logs through Timber but plants no tree in release builds, so its own diagnostics (login,
     * session recovery, playback) never reach logcat. This plants {@link SafeLogTree}, which scrubs
     * passwords, tokens and keys first: F1TV logs full login requests. A missing Timber only means
     * no extra logs.
     */
    static void enableF1Logs(Context context) {
        if (f1LogsPlanted) return;
        try {
            timber.log.Timber.plant(new SafeLogTree());
            f1LogsPlanted = true;
            Log.i(RadioEngine.TAG, "F1TV diagnostic logs on (scrubbed)");
        } catch (Throwable t) {
            Log.w(RadioEngine.TAG, "could not enable F1TV logs", t);
        }
    }
}
