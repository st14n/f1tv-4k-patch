package com.f1tvpatch.radio;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import java.util.Locale;

/**
 * Adds a "Grand Prix Radio" section (and the patch's update options) to F1TV's own settings screen.
 * Values live in the radio patch's preferences and apply from the next playback.
 */
public final class RadioSettings {
    private RadioSettings() { }

    private static final String TAG = RadioEngine.TAG;
    private static final String CATEGORY_KEY = "f1tvpatch_radio";
    private static final String UPDATES_KEY = "f1tvpatch_updates";
    private static final String ACCOUNT_KEY = "f1tvpatch_account";

    /** Called at the end of F1TV's {@code SettingsFragment.PrefFragment.onCreatePreferences}. */
    public static void onCreatePreferences(Object fragment) {
        try {
            if (Config.STREAM_URL.isEmpty() || !(fragment instanceof PreferenceFragment)) return;
            PreferenceFragment prefFragment = (PreferenceFragment) fragment;
            Activity activity = ((Fragment) prefFragment).getActivity();
            PreferenceScreen screen = prefFragment.getPreferenceScreen();
            if (activity == null || screen == null || screen.findPreference(CATEGORY_KEY) != null) return;
            addRadioSection(activity, screen);
            addAccountSection(activity, screen);
            if (Updater.isConfigured()) addUpdateSection(activity, screen);
        } catch (Throwable t) {
            // An F1TV update changed the settings screen: leave it as it is
            Log.w(TAG, "could not add radio settings", t);
        }
    }

    private static void addRadioSection(Context c, PreferenceScreen screen) {
        SharedPreferences prefs = Updater.prefs(c);
        PreferenceCategory category = new PreferenceCategory(c);
        category.setKey(CATEGORY_KEY);
        category.setTitle("Grand Prix Radio");
        category.setIconSpaceReserved(false);
        screen.addPreference(category);

        category.addPreference(toggle(c, prefs, "enabled", Config.DEFAULT_ENABLED,
                t("Grand Prix Radio gebruiken", "Use Grand Prix Radio"),
                t("Vervangt het F1-commentaar bij live sessies.", "Replaces the F1 commentary in live sessions.")));
        category.addPreference(toggle(c, prefs, "auto_sync", true,
                t("Automatisch synchroniseren", "Automatic sync"),
                t("Volgt hoe ver het beeld achter live loopt.", "Follows how far the picture is behind live.")));
        category.addPreference(toggle(c, prefs, "acoustic_sync", true,
                t("Afstemmen op circuitgeluid", "Match to circuit sound"),
                t("Stelt de sync bij door het circuitgeluid onder de radio te vergelijken met het F1-geluid.",
                        "Fine-tunes the sync by comparing the circuit sound under the radio with the F1 audio.")));

        final Preference correction = new Preference(c);
        correction.setPersistent(false);
        correction.setIconSpaceReserved(false);
        correction.setTitle(t("Correctie terugzetten", "Reset correction"));
        correction.setSummary(correctionSummary(prefs));
        correction.setOnPreferenceClickListener(p -> {
            prefs.edit().putLong("adjust_ms", Config.DEFAULT_ADJUST_MS).apply();
            correction.setSummary(correctionSummary(prefs));
            return true;
        });
        category.addPreference(correction);
    }

    private static void addAccountSection(Context c, PreferenceScreen screen) {
        SharedPreferences prefs = Updater.prefs(c);
        PreferenceCategory category = new PreferenceCategory(c);
        category.setKey(ACCOUNT_KEY);
        category.setTitle(t("Inloggen (patch)", "Sign-in (patch)"));
        category.setIconSpaceReserved(false);
        screen.addPreference(category);

        String[] account = LoginStore.load(c);
        String summary = account == null
                ? t("Geen account opgeslagen. Op de pc: scripts/set_login.sh (leest .env).",
                    "No account stored. On the PC: scripts/set_login.sh (reads .env).")
                : t("Logt opnieuw in als F1 TV je uitlogt: ", "Signs back in when F1 TV signs you out: ")
                    + LoginReceiver.mask(account[0]);
        String last = AutoLogin.lastResult(c);
        if (last != null) summary += t("\nLaatste poging: ", "\nLast attempt: ") + last;
        category.addPreference(toggle(c, prefs, AutoLogin.KEY_ENABLED, true,
                t("Automatisch opnieuw inloggen", "Sign in again automatically"), summary));
        category.addPreference(toggle(c, prefs, Launch.KEY_F1_LOGS, false,
                t("Diagnose-logs", "Diagnostic logs"),
                t("F1 TV's eigen logs naar logcat (vanaf de volgende start). Alleen voor foutzoeken.",
                        "F1 TV's own logs to logcat (from the next start). For troubleshooting only.")));
    }

    private static void addUpdateSection(final Activity activity, PreferenceScreen screen) {
        SharedPreferences prefs = Updater.prefs(activity);
        PreferenceCategory category = new PreferenceCategory(activity);
        category.setKey(UPDATES_KEY);
        category.setTitle(t("Updates (patch)", "Updates (patch)"));
        category.setIconSpaceReserved(false);
        screen.addPreference(category);

        category.addPreference(toggle(activity, prefs, Updater.KEY_AUTO_UPDATE, true,
                t("Automatisch op updates controleren", "Check for updates automatically"),
                t("Bij het openen van de app, hoogstens elke 6 uur.", "When the app opens, at most every 6 hours.")));
        Preference checkNow = new Preference(activity);
        checkNow.setPersistent(false);
        checkNow.setIconSpaceReserved(false);
        checkNow.setTitle(t("Nu op updates controleren", "Check for updates now"));
        checkNow.setSummary(t("Geïnstalleerd: ", "Installed: ") + Updater.currentVersionLabel());
        checkNow.setOnPreferenceClickListener(p -> {
            Updater.check(activity, true);
            return true;
        });
        category.addPreference(checkNow);
    }

    private static SwitchPreference toggle(Context c, final SharedPreferences prefs, final String key, boolean defaultValue,
                                           String title, String summary) {
        SwitchPreference preference = new SwitchPreference(c);
        // Not persisted through F1TV's own preference store: these belong to the patch
        preference.setPersistent(false);
        preference.setIconSpaceReserved(false);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setChecked(prefs.getBoolean(key, defaultValue));
        preference.setOnPreferenceChangeListener((p, value) -> {
            prefs.edit().putBoolean(key, Boolean.TRUE.equals(value)).apply();
            return true;
        });
        return preference;
    }

    private static String correctionSummary(SharedPreferences prefs) {
        long adjust = prefs.getLong("adjust_ms", Config.DEFAULT_ADJUST_MS);
        return String.format(Locale.getDefault(), t("Nu %+.2f s, terug naar %+.2f s", "Now %+.2f s, back to %+.2f s"),
                adjust / 1000.0, Config.DEFAULT_ADJUST_MS / 1000.0);
    }

    private static String t(String nl, String en) {
        return Updater.t(nl, en);
    }
}
