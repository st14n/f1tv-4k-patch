package com.f1tvpatch.radio;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The patch's own settings screen (custom radio, sign-in, updates), opened from the
 * "Custom radio" button the patch adds to F1TV's settings page. Built in code with framework
 * widgets only, so it does not depend on F1TV's layouts or themes. D-pad: up/down to move,
 * OK to toggle or run, BACK to close. Changes apply from the next playback.
 */
public final class PatchSettingsActivity extends Activity {
    private static final int BG = 0xFF0D0E12;
    private static final int ROW = 0xFF1C1F26;
    private static final int ROW_FOCUSED = 0xFFF2F3F5;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int TEXT_ON_FOCUS = 0xFF0D0E12;
    private static final int MUTED = 0xFFB4B9C2;
    private static final int MUTED_ON_FOCUS = 0xFF3A3F4A;
    private static final int GREEN = 0xFF3DD68C;
    private static final int RED = 0xFFE10600;

    /** One row: title, summary and an optional on/off state. */
    private final class Row {
        final LinearLayout view;
        final TextView title;
        final TextView summary;
        final TextView state;
        Runnable onClick;
        java.util.concurrent.Callable<String> summarySource;
        String prefKey;
        boolean prefDefault;

        Row(String titleText) {
            view = new LinearLayout(PatchSettingsActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setFocusable(true);
            view.setClickable(true);
            view.setPadding(dp(20), dp(14), dp(20), dp(14));
            LinearLayout texts = new LinearLayout(PatchSettingsActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = text(titleText, 18, TEXT, true);
            summary = text("", 14, MUTED, false);
            texts.addView(title);
            texts.addView(summary);
            view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            state = text("", 16, GREEN, true);
            state.setGravity(Gravity.END);
            view.addView(state, new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));
            view.setOnClickListener(v -> {
                if (prefKey != null) {
                    SharedPreferences prefs = Updater.prefs(PatchSettingsActivity.this);
                    prefs.edit().putBoolean(prefKey, !prefs.getBoolean(prefKey, prefDefault)).apply();
                }
                if (onClick != null) onClick.run();
                refreshAll();
            });
            view.setOnFocusChangeListener((v, focused) -> paint(focused));
            paint(false);
        }

        void refresh() {
            if (prefKey != null) {
                boolean on = Updater.prefs(PatchSettingsActivity.this).getBoolean(prefKey, prefDefault);
                state.setText(on ? t("AAN", "ON") : t("UIT", "OFF"));
                state.setTag(on);
            }
            if (summarySource != null) {
                try {
                    String s = summarySource.call();
                    summary.setText(s == null ? "" : s);
                    summary.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
                } catch (Exception e) {
                    summary.setVisibility(View.GONE);
                }
            }
            paint(view.isFocused());
        }

        void paint(boolean focused) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(focused ? ROW_FOCUSED : ROW);
            view.setBackground(bg);
            title.setTextColor(focused ? TEXT_ON_FOCUS : TEXT);
            summary.setTextColor(focused ? MUTED_ON_FOCUS : MUTED);
            boolean on = Boolean.TRUE.equals(state.getTag());
            state.setTextColor(on ? (focused ? 0xFF1E8A57 : GREEN) : (focused ? MUTED_ON_FOCUS : MUTED));
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(64), dp(40), dp(64), dp(40));
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView heading = text("Custom radio", 30, TEXT, true);
        heading.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        list.addView(heading);
        View accent = new View(this);
        accent.setBackgroundColor(RED);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(48), dp(4));
        accentParams.setMargins(0, dp(6), 0, dp(8));
        list.addView(accent, accentParams);
        list.addView(text(t("Wijzigingen gelden vanaf de volgende keer dat je iets afspeelt. Tijdens live kijken: "
                        + "MENU, een gekleurde toets of OK ingedrukt houden opent het radiopaneel.",
                "Changes apply from the next playback. While watching live: MENU, a colour key or holding OK opens the radio panel."),
                14, MUTED, false));

        section(t("Radio", "Radio"));
        toggle(t("Custom radio gebruiken", "Use custom radio"), "enabled", Config.DEFAULT_ENABLED,
                t("Vervangt het F1-commentaar bij live sessies.", "Replaces the F1 commentary in live sessions."));
        toggle(t("Automatisch synchroniseren", "Automatic sync"), "auto_sync", true,
                t("Volgt hoe ver het beeld achter live loopt.", "Follows how far the picture is behind live."));
        toggle(t("Afstemmen op circuitgeluid", "Match to circuit sound"), "acoustic_sync", true,
                t("Stelt de sync bij met het circuitgeluid onder de radio.", "Fine-tunes the sync using the circuit sound under the radio."));
        Row correction = row(t("Correctie terugzetten", "Reset correction"));
        correction.summarySource = () -> {
            long adjust = Updater.prefs(this).getLong("adjust_ms", Config.DEFAULT_ADJUST_MS);
            return String.format(Locale.getDefault(), t("Nu %+.2f s, terug naar %+.2f s", "Now %+.2f s, back to %+.2f s"),
                    adjust / 1000.0, Config.DEFAULT_ADJUST_MS / 1000.0);
        };
        correction.onClick = () -> Updater.prefs(this).edit().putLong("adjust_ms", Config.DEFAULT_ADJUST_MS).apply();

        section(t("Inloggen", "Sign-in"));
        Row autoLogin = toggle(t("Automatisch opnieuw inloggen", "Sign in again automatically"), AutoLogin.KEY_ENABLED, true, null);
        autoLogin.summarySource = () -> {
            String[] account = LoginStore.load(this);
            StringBuilder s = new StringBuilder(account == null
                    ? t("Geen account opgeslagen. Op de pc: scripts/set_login.sh (leest .env).",
                        "No account stored. On the PC: scripts/set_login.sh (reads .env).")
                    : t("Als F1 TV je uitlogt, als ", "When F1 TV signs you out, as ") + LoginReceiver.mask(account[0]));
            String deviceId = DeviceId.current(this);
            if (deviceId != null) s.append(t("\nF1-apparaat-id: ", "\nF1 device id: ")).append(deviceId);
            String last = AutoLogin.lastResult(this);
            if (last != null) s.append(t("\nLaatste poging: ", "\nLast attempt: ")).append(last);
            return s.toString();
        };
        toggle(t("Diagnose-logs", "Diagnostic logs"), Launch.KEY_F1_LOGS, false,
                t("F1 TV's eigen logs (zonder wachtwoorden of tokens) naar logcat, vanaf de volgende start.",
                        "F1 TV's own logs (without passwords or tokens) to logcat, from the next start."));

        if (Updater.isConfigured()) {
            section(t("Updates", "Updates"));
            toggle(t("Automatisch op updates controleren", "Check for updates automatically"), Updater.KEY_AUTO_UPDATE, true,
                    t("Bij het openen van de app, hoogstens elke 6 uur.", "When the app opens, at most every 6 hours."));
            Row check = row(t("Nu op updates controleren", "Check for updates now"));
            check.summarySource = () -> t("Geïnstalleerd: ", "Installed: ") + Updater.currentVersionLabel();
            check.onClick = () -> Updater.check(this, true);
        }

        refreshAll();
        if (!rows.isEmpty()) rows.get(0).view.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        for (Row r : rows) r.refresh();
    }

    private void section(String title) {
        TextView v = text(title.toUpperCase(Locale.getDefault()), 13, MUTED, true);
        v.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(4), dp(28), 0, dp(8));
        list.addView(v, p);
    }

    private Row row(String title) {
        Row r = new Row(title);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(4), 0, dp(4));
        list.addView(r.view, p);
        rows.add(r);
        return r;
    }

    private Row toggle(String title, String key, boolean defaultValue, String summary) {
        Row r = row(title);
        r.prefKey = key;
        r.prefDefault = defaultValue;
        if (summary != null) r.summarySource = () -> summary;
        return r;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private int dp(float value) {
        return dp(this, value);
    }

    static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    private static String t(String nl, String en) {
        return Updater.t(nl, en);
    }
}
