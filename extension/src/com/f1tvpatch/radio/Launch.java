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
        Updater.onLaunch(launcher);
    }

    /**
     * F1TV logs through Timber but plants no tree in release builds, so its own diagnostics (login,
     * session recovery, playback) never reach logcat. Planting Timber's DebugTree shows them, tagged
     * with the F1TV class name. Found by name; a missing Timber only means no extra logs.
     */
    static void enableF1Logs(Context context) {
        if (f1LogsPlanted) return;
        try {
            Class<?> treeType = Class.forName("timber.log.Timber$Tree");
            Object debugTree = Class.forName("timber.log.Timber$DebugTree").getConstructor().newInstance();
            Class.forName("timber.log.Timber").getMethod("plant", treeType).invoke(null, debugTree);
            f1LogsPlanted = true;
            Log.i(RadioEngine.TAG, "F1TV diagnostic logs on (Timber DebugTree planted)");
        } catch (Throwable t) {
            Log.w(RadioEngine.TAG, "could not enable F1TV logs", t);
        }
    }
}
