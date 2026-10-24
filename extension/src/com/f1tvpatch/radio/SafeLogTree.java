package com.f1tvpatch.radio;

import android.util.Log;

import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * Sends F1TV's Timber logs to logcat with every secret scrubbed out.
 *
 * F1TV's network layer logs whole request and response bodies (login with the password,
 * authentication keys, tokens), so its logs must never reach logcat as they are. Anything that
 * looks like a credential, token, key, JWT or e-mail address is replaced before printing; long
 * bodies are cut short as well.
 */
final class SafeLogTree extends Timber.Tree {
    private static final String TAG_PREFIX = "F1TV/";
    private static final int MAX_LENGTH = 600;

    // "Password":"…", "authenticationKey":"…", "subscriptionToken":"…", "Login":"…" and the like
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(\"[A-Za-z_]*(?i:password|passwd|pwd|login|email|username|token|key|secret|session|cookie|authorization|credential|pin)[A-Za-z_]*\"\\s*:\\s*)\"[^\"]*\"");
    // header / query / form style: token=…, Authorization: …, ascendontoken: …
    private static final Pattern PAIR_SECRET = Pattern.compile(
            "(?i)((?:password|passwd|token|key|secret|session|cookie|authorization|ascendontoken|entitlementtoken|x-d-token|login|email)[\\w-]*\\s*[=:]\\s*)[^\\s,&;\"]+");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]{8,}\\.[\\w-]{8,}\\.[\\w-]{8,}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    // opaque keys/tokens outside JSON fields (no "/" or ".", so URLs and hosts stay readable)
    private static final Pattern LONG_OPAQUE = Pattern.compile("[A-Za-z0-9+=_-]{32,}");

    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
        String text = scrub(message);
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH) + "… (" + text.length() + " chars)";
        Log.println(priority, TAG_PREFIX + (tag != null ? tag : callerTag()), text);
    }

    static String scrub(String message) {
        if (message == null) return "";
        String text = JSON_SECRET.matcher(message).replaceAll("$1\"***\"");
        text = PAIR_SECRET.matcher(text).replaceAll("$1***");
        text = JWT.matcher(text).replaceAll("<jwt>");
        text = EMAIL.matcher(text).replaceAll("<email>");
        return LONG_OPAQUE.matcher(text).replaceAll("<redacted>");
    }

    /** Simple class name of the F1TV code that logged, like Timber's DebugTree tags. */
    private static String callerTag() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cls = e.getClassName();
            if (cls.startsWith("timber.log.") || cls.startsWith("com.f1tvpatch.")) continue;
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            int inner = simple.indexOf('$');
            return inner > 0 ? simple.substring(0, inner) : simple;
        }
        return "F1TV";
    }
}
