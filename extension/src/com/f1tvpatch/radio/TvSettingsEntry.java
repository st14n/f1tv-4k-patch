package com.f1tvpatch.radio;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Puts the patch's entry on F1TV's TV settings page (AccountSettingsFragment in 3.0.x), looking and
 * focusing like F1TV's own buttons.
 *
 * That page is a ConstraintLayout of button containers chained top-to-bottom, with the contact
 * links hanging below the last one. Adding a view to the chain (or editing its constraints) broke
 * the page, so the entry reuses the last container instead: F1TV ships a fourth one, hidden unless
 * "enhanced audio" is on offer, with the same layout, focus order and spacing as the others. It is
 * shown, retitled and given our click handler. If F1TV shows it for a real feature the patch backs
 * off and leaves the page alone.
 *
 * Containers and their parts are looked up by resource name, so an F1TV redesign makes the entry
 * go missing instead of crashing the page.
 */
public final class TvSettingsEntry {
    private TvSettingsEntry() { }

    private static final String TAG = RadioEngine.TAG;
    private static final String MARKER = "f1tvpatch_settings_entry";
    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();

    /** From {@link Launch}: watches every F1TV screen and fills the slot whenever the page shows. */
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
                fill(decor, res, pkg);
            } catch (Throwable t) {
                Log.w(TAG, "could not add the settings entry", t);
            }
        });
    }

    private static void fill(View decor, Resources res, String pkg) {
        int probe = res.getIdentifier("language_layout_container", "id", pkg);
        View first = probe != 0 ? decor.findViewById(probe) : null;
        if (first == null || !(first.getParent() instanceof ViewGroup)) return;
        ViewGroup page = (ViewGroup) first.getParent();

        // The last button container in layout order
        ViewGroup slot = null;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child instanceof ViewGroup && child.getId() != View.NO_ID && child.isFocusable() && isButtonContainer(child, res)) {
                slot = (ViewGroup) child;
            }
        }
        if (slot == null) return;

        boolean ours = MARKER.equals(slot.getTag());
        if (!ours && slot.getVisibility() == View.VISIBLE) return; // F1TV uses it for a real feature

        TextView title = text(slot, res, pkg, "title");
        TextView description = text(slot, res, pkg, "description");
        TextView button = text(slot, res, pkg, "button");
        if (title == null || button == null) return;

        String titleText = Config.STREAM_URL.isEmpty() ? Updater.t("Patch-instellingen", "Patch settings") : "Custom radio";
        String descriptionText = Config.STREAM_URL.isEmpty()
                ? Updater.t("Automatisch inloggen en updates van de gepatchte app.", "Automatic sign-in and updates of the patched app.")
                : Updater.t("Radio in plaats van het F1-commentaar, synchronisatie, automatisch inloggen en updates.",
                        "Radio instead of the F1 commentary, sync, automatic sign-in and updates.");
        String buttonText = Updater.t("OPENEN", "OPEN");

        // F1TV re-applies its own texts when its data changes: put ours back whenever they differ
        boolean changed = !ours
                || slot.getVisibility() != View.VISIBLE
                || !titleText.contentEquals(title.getText())
                || !buttonText.contentEquals(button.getText())
                || (description != null && !descriptionText.contentEquals(description.getText()));
        if (!changed) return;

        slot.setTag(MARKER);
        slot.setVisibility(View.VISIBLE);
        title.setText(titleText);
        if (description != null) description.setText(descriptionText);
        button.setText(buttonText);
        setIcon(slot, res, pkg);
        View.OnClickListener open = v -> {
            Context c = v.getContext();
            c.startActivity(new Intent(c, PatchSettingsActivity.class));
        };
        slot.setOnClickListener(open);
        button.setOnClickListener(open);
        if (!ours) Log.i(TAG, "settings entry added to F1TV's settings page");
    }

    private static boolean isButtonContainer(View view, Resources res) {
        try {
            return res.getResourceEntryName(view.getId()).endsWith("_layout_container");
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    private static TextView text(View parent, Resources res, String pkg, String idName) {
        int id = res.getIdentifier(idName, "id", pkg);
        View v = id != 0 ? parent.findViewById(id) : null;
        return v instanceof TextView ? (TextView) v : null;
    }

    private static void setIcon(View parent, Resources res, String pkg) {
        int id = res.getIdentifier("icon", "id", pkg);
        View v = id != 0 ? parent.findViewById(id) : null;
        if (!(v instanceof ImageView)) return;
        for (String name : new String[]{"ic_team_radio_tv_inactive", "audio_track_icon_white", "ic_enhanced_audio"}) {
            int drawable = res.getIdentifier(name, "drawable", pkg);
            if (drawable != 0) {
                ((ImageView) v).setImageResource(drawable);
                return;
            }
        }
    }
}
