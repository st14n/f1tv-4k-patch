package com.f1tvpatch.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Keeps F1TV's device id the same for good.
 *
 * F1TV registers itself with the account as a device identified by ANDROID_ID. Android derives
 * ANDROID_ID from the app's signing key (and resets it on a factory reset), so a new keystore, a
 * reinstall after a key change or the Play Store app all look like another device, and F1 refuses
 * logins once "the limited number of active associated devices" is reached.
 *
 * The first id this install sees is pinned in the patch's preferences and used from then on. An
 * id delivered with scripts/set_login.sh (F1_DEVICE_ID in .env) takes precedence, so even a fresh
 * install with another key comes back as the same, already registered device.
 */
public final class DeviceId {
    private DeviceId() { }

    static final String KEY_PINNED = "pinned_device_id";
    private static volatile String pinned;

    /** Called right after F1TV's Application reads ANDROID_ID; returns the id F1TV should use. */
    public static String pin(Context context, String androidId) {
        try {
            SharedPreferences prefs = Updater.prefs(context);
            String stored = prefs.getString(KEY_PINNED, null);
            if (stored == null || stored.isEmpty()) {
                if (androidId == null || androidId.isEmpty()) return androidId;
                stored = androidId;
                prefs.edit().putString(KEY_PINNED, stored).apply();
                Log.i(RadioEngine.TAG, "F1 device id pinned: " + stored);
            } else if (!stored.equals(androidId)) {
                Log.i(RadioEngine.TAG, "F1 device id kept at " + stored + " (ANDROID_ID is now " + androidId + ")");
            }
            pinned = stored;
            return stored;
        } catch (Throwable t) {
            Log.w(RadioEngine.TAG, "device id pin failed; using ANDROID_ID", t);
            return androidId;
        }
    }

    /** Other ANDROID_ID reads in F1TV code (no Context at hand): same id once the app pinned it. */
    public static String pinned(String androidId) {
        String id = pinned;
        return id != null ? id : androidId;
    }

    static String current(Context context) {
        return Updater.prefs(context).getString(KEY_PINNED, null);
    }

    /** From set_login.sh: use this id from the next app start. */
    static void set(Context context, String id) {
        Updater.prefs(context).edit().putString(KEY_PINNED, id).apply();
        Log.i(RadioEngine.TAG, "F1 device id set to " + id + " (applies from the next app start)");
    }
}
