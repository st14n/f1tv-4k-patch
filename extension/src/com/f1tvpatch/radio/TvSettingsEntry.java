package com.f1tvpatch.radio;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Puts the patch's entry next to F1TV's TV settings page (AccountSettingsFragment in 3.0.x).
 *
 * That page is a ConstraintLayout of full-width button rows chained top to bottom, with the contact
 * links hanging below the last one. Adding to that chain broke the page, and reusing its hidden
 * fourth row clashed with F1TV's own "enhanced audio" row once that shows up. So nothing of F1TV's
 * layout is touched: a card is overlaid in the empty area to the right of the rows, level with the
 * first one. D-pad right from a row reaches it, left goes back. The card is only visible while that
 * page is, found by its row ids (by name), so an F1TV redesign makes the card go missing instead of
 * crashing anything.
 */
public final class TvSettingsEntry {
    private TvSettingsEntry() { }

    private static final String TAG = RadioEngine.TAG;
    private static final Map<View, View> CARDS = new WeakHashMap<>();
    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();

    /** From {@link Launch}: watches every F1TV screen for the settings page. */
    static void install(Activity launcher) {
        launcher.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) { watch(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
        watch(launcher);
    }

    private static void watch(Activity activity) {
        if (activity instanceof PatchSettingsActivity) return;
        final View decor = activity.getWindow().getDecorView();
        if (WATCHED.containsKey(decor)) return;
        WATCHED.put(decor, Boolean.TRUE);
        final Resources res = activity.getResources();
        final String pkg = activity.getPackageName();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            try {
                update(activity, decor, res, pkg);
            } catch (Throwable t) {
                Log.w(TAG, "settings entry failed", t);
            }
        });
    }

    private static void update(Activity activity, View decor, Resources res, String pkg) {
        int rowId = res.getIdentifier("profile_layout_container", "id", pkg);
        View row = rowId != 0 ? decor.findViewById(rowId) : null;
        boolean pageShown = row != null && row.isShown() && row.getWidth() > 0;
        View card = CARDS.get(decor);
        if (!pageShown) {
            if (card != null) card.setVisibility(View.GONE);
            return;
        }
        FrameLayout host = decor.findViewById(android.R.id.content);
        if (host == null) return;
        if (card == null || card.getParent() != host) {
            card = build(activity);
            CARDS.put(decor, card);
            host.addView(card, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            Log.i(TAG, "settings card added next to F1TV's settings page");
        }
        // Level with the first row, right of the rows, inside the screen
        int[] rowPos = new int[2];
        int[] hostPos = new int[2];
        row.getLocationOnScreen(rowPos);
        host.getLocationOnScreen(hostPos);
        int left = rowPos[0] - hostPos[0] + row.getWidth() + dp(activity, 24);
        int width = Math.min(dp(activity, 300), host.getWidth() - left - dp(activity, 24));
        if (width < dp(activity, 180)) {
            card.setVisibility(View.GONE); // no room on this screen size
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        int top = rowPos[1] - hostPos[1];
        if (lp.leftMargin != left || lp.topMargin != top || lp.width != width) {
            lp.leftMargin = left;
            lp.topMargin = top;
            lp.width = width;
            card.setLayoutParams(lp);
        }
        card.setVisibility(View.VISIBLE);
    }

    private static View build(Activity activity) {
        final Context c = activity;
        boolean radio = !Config.STREAM_URL.isEmpty();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));

        TextView title = text(c, radio ? "CUSTOM RADIO" : t("PATCH-INSTELLINGEN", "PATCH SETTINGS"), 15, 0xFFF2F3F5, true);
        TextView body = text(c, radio
                ? t("Radio i.p.v. het F1-commentaar, synchronisatie, inloggen en updates.",
                        "Radio instead of the F1 commentary, sync, sign-in and updates.")
                : t("Automatisch inloggen en updates van de gepatchte app.",
                        "Automatic sign-in and updates of the patched app."), 12, 0xFFB4B9C2, false);
        body.setPadding(0, dp(c, 8), 0, dp(c, 12));
        TextView open = text(c, t("OPENEN", "OPEN"), 13, 0xFFFFFFFF, true);
        open.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFFE10600);
        pill.setCornerRadius(dp(c, 8));
        open.setBackground(pill);
        card.addView(title);
        card.addView(body);
        card.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 38)));

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFF2A2E38);
        focused.setCornerRadius(dp(c, 10));
        focused.setStroke(dp(c, 3), 0xFFF2F3F5);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0xFF1C1F26);
        idle.setCornerRadius(dp(c, 10));
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, idle);
        card.setBackground(bg);
        card.setOnClickListener(v -> v.getContext().startActivity(new Intent(v.getContext(), PatchSettingsActivity.class)));
        return card;
    }

    private static TextView text(Context c, String value, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    private static String t(String nl, String en) {
        return Updater.t(nl, en);
    }
}
