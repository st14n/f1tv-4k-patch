package com.f1tvpatch.radio;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Adds a "Custom radio" button to F1TV's TV settings page (AccountSettingsFragment in 3.0.x),
 * built from F1TV's own button layout and background so it looks and focuses like its neighbours.
 * It opens {@link PatchSettingsActivity}.
 *
 * The page is a ConstraintLayout of button containers chained top-to-bottom. The new button is
 * chained below the last one, and whatever hung below that (the spacer before the contact links)
 * is moved below the new button. Resources are looked up by name, and ConstraintLayout only by
 * reflection, so an F1TV redesign makes the button go missing instead of crashing the page.
 */
public final class TvSettingsEntry {
    private TvSettingsEntry() { }

    private static final String TAG = RadioEngine.TAG;
    private static final String MARKER = "f1tvpatch_settings_entry";
    private static final java.util.Map<View, Boolean> WATCHED = new java.util.WeakHashMap<>();

    /**
     * From {@link Launch}: watches every F1TV screen's layout and adds the button whenever the
     * settings page is on screen. No hook in F1TV's settings code, so renaming its classes does
     * not matter; only the button ids are looked up (by name).
     */
    static void install(android.app.Activity launcher) {
        launcher.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity a) { watch(a); }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityStopped(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        });
        watch(launcher);
    }

    private static void watch(android.app.Activity activity) {
        if (activity instanceof PatchSettingsActivity) return;
        final View decor = activity.getWindow().getDecorView();
        if (WATCHED.containsKey(decor)) return;
        WATCHED.put(decor, Boolean.TRUE);
        Resources res = activity.getResources();
        final int pageButtonId = res.getIdentifier("language_layout_container", "id", activity.getPackageName());
        if (pageButtonId == 0) return;
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            View button = decor.findViewById(pageButtonId);
            if (button != null && button.getParent() instanceof ViewGroup
                    && ((ViewGroup) button.getParent()).findViewWithTag(MARKER) == null) {
                inject(button.getRootView());
            }
        });
    }

    private static void inject(View root) {
        try {
            if (root == null || root.findViewWithTag(MARKER) != null) return;
            Context c = root.getContext();
            Resources res = c.getResources();
            String pkg = c.getPackageName();
            ViewGroup page = findPage(root, res, pkg);
            if (page == null) {
                Log.w(TAG, "settings page layout not recognised; no Custom radio button");
                return;
            }
            View last = lastButton(page, res, pkg);
            if (last == null) return;

            FrameLayout entry = new FrameLayout(c);
            entry.setId(View.generateViewId());
            entry.setTag(MARKER);
            entry.setFocusable(true);
            entry.setClickable(true);
            entry.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            entry.setBackground(last.getBackground() != null && last.getBackground().getConstantState() != null
                    ? last.getBackground().getConstantState().newDrawable(res).mutate() : null);
            int layoutId = res.getIdentifier("settings_button_layout", "layout", pkg);
            if (layoutId != 0) {
                View inner = LayoutInflater.from(c).inflate(layoutId, entry, false);
                entry.addView(inner);
                setText(inner, res, pkg, "title", "Custom radio");
                setText(inner, res, pkg, "description", Updater.t(
                        "Radio in plaats van het F1-commentaar, synchronisatie, automatisch inloggen en updates.",
                        "Radio instead of the F1 commentary, sync, automatic sign-in and updates."));
                setText(inner, res, pkg, "button", Updater.t("OPENEN", "OPEN"));
                setIcon(inner, res, pkg);
            } else {
                TextView label = new TextView(c);
                label.setText("Custom radio");
                label.setPadding(PatchSettingsActivity.dp(c, 16), PatchSettingsActivity.dp(c, 24),
                        PatchSettingsActivity.dp(c, 16), PatchSettingsActivity.dp(c, 24));
                entry.addView(label);
            }
            entry.setOnClickListener(v -> v.getContext().startActivity(new Intent(v.getContext(), PatchSettingsActivity.class)));

            ViewGroup.LayoutParams params = chainedBelow(last, entry.getId(), page);
            page.addView(entry, params);
            keepFocusChain(page, entry, res, pkg);
            Log.i(TAG, "Custom radio button added to F1TV settings");
        } catch (Throwable t) {
            Log.w(TAG, "could not add the Custom radio settings button", t);
        }
    }

    /** The ConstraintLayout holding the buttons: parent of the first "*_layout_container". */
    private static ViewGroup findPage(View root, Resources res, String pkg) {
        for (String name : new String[]{"profile_layout_container", "language_layout_container"}) {
            int id = res.getIdentifier(name, "id", pkg);
            View v = id != 0 ? root.findViewById(id) : null;
            if (v != null && v.getParent() instanceof ViewGroup) return (ViewGroup) v.getParent();
        }
        return null;
    }

    /** Last button container in layout order (visible or not: hidden ones collapse in the chain). */
    private static View lastButton(ViewGroup page, Resources res, String pkg) {
        View last = null;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child.getId() == View.NO_ID || MARKER.equals(child.getTag())) continue;
            String name;
            try {
                name = res.getResourceEntryName(child.getId());
            } catch (Resources.NotFoundException e) {
                continue;
            }
            if (name.endsWith("_layout_container") && child.isFocusable()) last = child;
        }
        return last;
    }

    /**
     * ConstraintLayout params for the new button: a copy of the last button's (width, start
     * constraint, margins) with its top tied to that button; views below the last button now hang
     * below the new one.
     */
    private static ViewGroup.LayoutParams chainedBelow(View last, int newId, ViewGroup page) throws Exception {
        ViewGroup.LayoutParams src = last.getLayoutParams();
        Constructor<?> copy = src.getClass().getConstructor(ViewGroup.LayoutParams.class);
        ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) copy.newInstance(src);
        Field topToBottom = src.getClass().getField("topToBottom");
        Field topToTop = src.getClass().getField("topToTop");
        // copy constructors differ between ConstraintLayout versions: set what matters explicitly
        for (String name : new String[]{"startToStart", "leftToLeft"}) {
            Field f = src.getClass().getField(name);
            f.setInt(params, f.getInt(src));
        }
        topToTop.setInt(params, -1);
        topToBottom.setInt(params, last.getId());
        if (params instanceof ViewGroup.MarginLayoutParams && src instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) params;
            ViewGroup.MarginLayoutParams s = (ViewGroup.MarginLayoutParams) src;
            m.setMargins(s.leftMargin, s.topMargin, s.rightMargin, s.bottomMargin);
            m.setMarginStart(s.getMarginStart());
        }
        params.width = src.width;
        params.height = src.height;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (child != last && lp != null && lp.getClass() == src.getClass() && topToBottom.getInt(lp) == last.getId()) {
                topToBottom.setInt(lp, newId);
                child.setLayoutParams(lp);
            }
        }
        return params;
    }

    /** D-pad: down from the last visible F1 button reaches ours, up from ours goes back to it. */
    private static void keepFocusChain(ViewGroup page, View entry, Resources res, String pkg) {
        Runnable link = () -> {
            View above = null;
            for (int i = 0; i < page.getChildCount(); i++) {
                View child = page.getChildAt(i);
                if (child == entry || child.getVisibility() != View.VISIBLE || !child.isFocusable()) continue;
                try {
                    if (res.getResourceEntryName(child.getId()).endsWith("_layout_container")) above = child;
                } catch (Resources.NotFoundException ignored) {
                    // not one of F1TV's buttons
                }
            }
            if (above != null) {
                above.setNextFocusDownId(entry.getId());
                entry.setNextFocusUpId(above.getId());
            }
            entry.setNextFocusDownId(entry.getId());
        };
        link.run();
        page.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (entry.getParent() == null) {
                    page.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    return;
                }
                link.run();
            }
        });
    }

    private static void setText(View inner, Resources res, String pkg, String idName, String text) {
        int id = res.getIdentifier(idName, "id", pkg);
        View v = id != 0 ? inner.findViewById(id) : null;
        if (v instanceof TextView) {
            ((TextView) v).setText(text);
            v.setVisibility(View.VISIBLE);
        }
    }

    private static void setIcon(View inner, Resources res, String pkg) {
        int id = res.getIdentifier("icon", "id", pkg);
        View v = id != 0 ? inner.findViewById(id) : null;
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
