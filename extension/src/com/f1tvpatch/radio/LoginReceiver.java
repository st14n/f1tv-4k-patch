package com.f1tvpatch.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * Receives the F1 account from scripts/set_login.sh over adb. The manifest entry requires
 * android.permission.DUMP, which only the adb shell and the system hold, so no other app can
 * send (or overwrite) it. Values arrive base64-encoded so any password survives the shell.
 */
public final class LoginReceiver extends BroadcastReceiver {
    static final String ACTION = "com.f1tvpatch.SET_LOGIN";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent.getBooleanExtra("clear", false)) {
                LoginStore.clear(context);
                Log.i(RadioEngine.TAG, "auto-login account removed");
                setResultCode(1);
                return;
            }
            // Tuning: am broadcast ... --el adjust_ms <ms> sets the running session's sync adjustment
            if (intent.hasExtra("adjust_ms")) {
                long adjust = intent.getLongExtra("adjust_ms", 0L);
                Updater.prefs(context).edit().putLong("adjust_ms", adjust).apply();
                RadioPatch.setAdjustFromAdb(adjust);
                setResultCode(1);
                return;
            }
            // Device id (F1_DEVICE_ID in .env): same registered device across reinstalls and keys
            String deviceId = intent.getStringExtra("device_id");
            if (deviceId != null && deviceId.matches("[A-Za-z0-9._-]{8,64}")
                    && !deviceId.equals(DeviceId.current(context))) {
                DeviceId.set(context, deviceId);
            }
            setResultData("device_id=" + DeviceId.current(context));
            if (intent.getBooleanExtra("device_only", false)) {
                setResultCode(1);
                return;
            }
            String email = decode(intent.getStringExtra("email_b64"));
            String password = decode(intent.getStringExtra("password_b64"));
            if (email.isEmpty() || password.isEmpty()) {
                Log.w(RadioEngine.TAG, "auto-login account not stored: email or password missing");
                setResultCode(2);
                return;
            }
            LoginStore.save(context, email, password);
            Log.i(RadioEngine.TAG, "auto-login account stored (" + mask(email) + ")");
            // Signed out right now (app open on the sign-in screen)? Then sign in straight away.
            if (intent.getBooleanExtra("login_now", false) && AutoLogin.loginNow()) {
                Log.i(RadioEngine.TAG, "signing in now");
            }
            setResultCode(1);
        } catch (Throwable t) {
            Log.e(RadioEngine.TAG, "auto-login account not stored", t);
            setResultCode(3);
        }
    }

    private static String decode(String b64) {
        if (b64 == null) return "";
        return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at > 1 ? email.charAt(0) + "***" + email.substring(at) : "***";
    }
}
