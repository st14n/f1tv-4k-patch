package com.f1tvpatch.radio;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Logs back in by itself when F1TV drops the session.
 *
 * F1TV restores a session at launch with a "silent login" (stored authentication key); when the
 * server rejects that key it wipes the session and shows the sign-in screen. With an account
 * stored (scripts/set_login.sh) this does what the user would do on that screen: the app's own
 * password login, as the same device (F1TV's device id is stable per install, so no extra device
 * slot is used). Then it restarts the app's launch flow so the home screen loads as usual.
 *
 * Every attempt is rate-limited, so a rejected password or a server problem never loops.
 */
public final class AutoLogin {
    private AutoLogin() { }

    private static final String TAG = RadioEngine.TAG;
    static final String KEY_ENABLED = "auto_login";
    private static final String KEY_LAST_ATTEMPT = "auto_login_last_attempt";
    private static final String KEY_LAST_RESULT = "auto_login_last_result";
    private static final long MIN_INTERVAL_MS = 10 * 60_000L;
    private static final long RELAUNCH_DELAY_MS = 1_500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Object> authRef = new WeakReference<>(null);
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static Context appContext;
    private static boolean attemptInFlight;
    /** Signed in while the app was in the background: restart its launch flow once it is back. */
    private static boolean relaunchWhenResumed;

    /** From {@link Launch}: remembers the app and which screen is in front. */
    static void init(Activity launcher) {
        if (appContext != null) return;
        appContext = launcher.getApplicationContext();
        launcher.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                resumed = new WeakReference<>(a);
                if (relaunchWhenResumed) {
                    relaunchWhenResumed = false;
                    MAIN.post(AutoLogin::relaunch);
                }
            }
            @Override public void onActivityPaused(Activity a) {
                if (resumed.get() == a) resumed = new WeakReference<>(null);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    // ── Smali hook ───────────────────────────────────────────────────────────

    /** Called first thing in F1TV's AuthenticationUseCaseImpl.onNextState(event). Never throws. */
    public static void onAuthState(Object auth, Object event) {
        try {
            authRef = new WeakReference<>(auth);
            String state = stateName(event);
            if (state == null) return;
            if (Config.DEBUG_LOGS) Log.d(TAG, "F1 auth state: " + state);
            switch (state) {
                case "SILENT_LOGIN_ERROR":
                    Log.i(TAG, "F1TV dropped the session (silent login rejected)");
                    // Not from inside onNextState: let F1TV finish emitting this state first (and
                    // the launch activity register us, if the session check ran before it)
                    MAIN.postDelayed(() -> {
                        if (shouldAttempt()) {
                            attemptInFlight = true;
                            login(auth);
                        }
                    }, 300L);
                    break;
                case "LOGIN_SUCCESS":
                    if (attemptInFlight) {
                        attemptInFlight = false;
                        record("ok");
                        Log.i(TAG, "auto-login succeeded");
                        MAIN.postDelayed(AutoLogin::relaunch, RELAUNCH_DELAY_MS);
                    }
                    break;
                case "LOGIN_ERROR":
                    if (attemptInFlight) {
                        attemptInFlight = false;
                        String detail = describe(event);
                        record("error " + detail);
                        Log.w(TAG, "auto-login failed: " + detail);
                        toast(isDeviceLimit(detail)
                                ? "F1 TV: maximum aantal apparaten bereikt. Verwijder oude apparaten op account.formula1.com."
                                : "F1 TV: automatisch inloggen mislukt.");
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "auto-login hook failed", t);
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private static boolean shouldAttempt() {
        Context c = appContext;
        if (c == null || attemptInFlight) return false;
        if (!Updater.prefs(c).getBoolean(KEY_ENABLED, true) || !LoginStore.has(c)) {
            Log.i(TAG, "no auto-login: " + (LoginStore.has(c) ? "switched off" : "no account stored (scripts/set_login.sh)"));
            return false;
        }
        long since = System.currentTimeMillis() - Updater.prefs(c).getLong(KEY_LAST_ATTEMPT, 0L);
        if (since < MIN_INTERVAL_MS) {
            Log.w(TAG, "no auto-login: last attempt " + since / 1000 + " s ago");
            return false;
        }
        return true;
    }

    private static void login(Object auth) {
        Context c = appContext;
        String[] account = c == null ? null : LoginStore.load(c);
        if (auth == null || account == null) {
            attemptInFlight = false;
            Log.w(TAG, "auto-login skipped: account unreadable");
            return;
        }
        Updater.prefs(c).edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply();
        try {
            // performLogin only starts from the "enter credentials" state, as on the sign-in screen
            Method reset = findMethod(auth, "resetToLoginDefaultState");
            if (reset != null) reset.invoke(auth);
            Method login = auth.getClass().getMethod("performLogin", String.class, String.class);
            Log.i(TAG, "auto-login as " + LoginReceiver.mask(account[0]));
            login.invoke(auth, account[0], account[1]);
        } catch (Throwable t) {
            attemptInFlight = false;
            record("error " + t);
            Log.e(TAG, "auto-login could not start", t);
        }
    }

    /**
     * Back through F1TV's own launch flow, which restores the new session and opens home. Only while
     * F1TV is in front: never pull it over another app the user switched to.
     */
    private static void relaunch() {
        Context c = appContext;
        if (c == null) return;
        Activity front = resumed.get();
        if (front == null || front.isFinishing()) {
            relaunchWhenResumed = true;
            Log.i(TAG, "signed in while in the background; home loads when F1 TV is opened");
            return;
        }
        Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(c.getPackageName());
        if (launch == null) launch = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        front.startActivity(launch);
    }

    /** Settings: try right now (e.g. after storing an account while on the sign-in screen). */
    static boolean loginNow() {
        Object auth = authRef.get();
        if (auth == null || appContext == null || !LoginStore.has(appContext) || attemptInFlight) return false;
        attemptInFlight = true;
        MAIN.post(() -> login(auth));
        return true;
    }

    static String lastResult(Context c) {
        return Updater.prefs(c).getString(KEY_LAST_RESULT, null);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void record(String result) {
        if (appContext != null) Updater.prefs(appContext).edit().putString(KEY_LAST_RESULT, result).apply();
    }

    private static String stateName(Object event) throws Exception {
        if (event == null) return null;
        Method getState = findMethod(event, "getState");
        Object state = getState != null ? getState.invoke(event) : null;
        return state instanceof Enum ? ((Enum<?>) state).name() : null;
    }

    /** Error code/message the event carries, if any (keys differ per F1TV version, so try a few). */
    private static String describe(Object event) {
        StringBuilder out = new StringBuilder();
        for (String key : new String[]{"error.code", "error.message", "error", "fault", "code", "message"}) {
            try {
                Object value = event.getClass().getMethod("get", String.class).invoke(event, key);
                if (value != null) out.append(key).append('=').append(value).append(' ');
            } catch (Throwable ignored) {
                // no such key / accessor
            }
        }
        return out.length() == 0 ? "(no details)" : out.toString().trim();
    }

    private static boolean isDeviceLimit(String detail) {
        String d = detail.toLowerCase(java.util.Locale.ROOT);
        return d.contains("device") && (d.contains("limit") || d.contains("max"));
    }

    private static Method findMethod(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static void toast(String text) {
        Context c = appContext;
        if (c != null) MAIN.post(() -> Toast.makeText(c, text, Toast.LENGTH_LONG).show());
    }
}
