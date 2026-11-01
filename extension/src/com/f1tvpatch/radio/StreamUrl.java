package com.f1tvpatch.radio;

import android.content.Context;

/**
 * The radio stream in use: the one the user entered in the settings screen, else the one this build
 * was made with (empty in the no-radio variant, which then has no radio until a URL is entered).
 */
final class StreamUrl {
    private StreamUrl() { }

    static final String KEY = "stream_url";
    private static volatile String current = Config.STREAM_URL;

    static void load(Context c) {
        String saved = Updater.prefs(c).getString(KEY, null);
        current = saved == null || saved.isEmpty() ? Config.STREAM_URL : saved;
    }

    static String get() { return current; }

    static boolean has() { return !current.isEmpty(); }

    /** The user's own URL, or "" when the build's is used. */
    static String custom(Context c) { return Updater.prefs(c).getString(KEY, ""); }

    static boolean isValid(String url) {
        return url.matches("(?i)https?://[^\\s\"<>]{4,500}");
    }

    /** An empty value goes back to the build's stream. The running radio restarts on the new one. */
    static void set(Context c, String url) {
        Updater.prefs(c).edit().putString(KEY, url.trim()).apply();
        load(c);
        RadioPatch.onStreamUrlChanged();
    }
}
