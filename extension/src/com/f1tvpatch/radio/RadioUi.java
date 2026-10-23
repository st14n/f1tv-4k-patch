package com.f1tvpatch.radio;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-screen UI for the custom radio, drawn over the F1 player:
 * <ul>
 *   <li>a compact bar at the top that appears together with F1's own playback controls (which sit
 *       at the bottom) — status plus 0.25 s nudges; reached with D-pad up from F1's controls;</li>
 *   <li>notices at the top right for what happens automatically;</li>
 *   <li>a side panel ("More options") with the full status and switches.</li>
 * </ul>
 * Long-press OK opens the bar from anywhere. Built in code: the patch ships no resources.
 */
final class RadioUi {
    enum Notice { IN_SYNC, ACOUSTIC_ADJUSTED, BUFFER_EXCEEDED }

    private static final int BG = 0xF20D0E12;
    private static final int CARD = 0xFF1C1F26;
    private static final int BUTTON = 0xFF262A33;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFFB4B9C2;
    private static final int DIM = 0xFF9AA0AB;
    private static final int TRACK = 0xFF3A3F4A;
    private static final int GREEN = 0xFF3DD68C;
    private static final int AMBER = 0xFFFFB547;
    private static final int DARK = 0xFF0D0E12;

    private static final long BAR_LINGER_MS = 1_500L;
    private static final long BAR_FORCED_MS = 8_000L;
    private static final long NOTICE_MS = 5_000L;

    private final WeakReference<Activity> activityRef;
    private final RadioPatch radio;
    private FrameLayout root;

    // Bar (B)
    private LinearLayout bar;
    private View barDot;
    private TextView barStatus;
    private TextView barValue;
    private TextView barValueLabel;
    private TextView barLater;
    private long barVisibleUntilMs;

    // Notices (C)
    private LinearLayout notices;
    private final List<NoticeCard> activeNotices = new ArrayList<>();
    private NoticeCard countdownCard;
    private RadioPatch.SyncState lastState;

    // Panel (A)
    private LinearLayout panel;
    private View panelDot;
    private TextView panelStatusTitle;
    private TextView panelStatusText;
    private TimelineView timeline;
    private TextView panelValue;
    private TextView panelEarlier;
    private SwitchRow autoRow;
    private SwitchRow acousticRow;
    private TextView resumeAcoustic;
    private TextView radioRow;

    private WeakReference<View> controlsRef = new WeakReference<>(null);
    private Method controlsVisibleMethod;
    private boolean controlsLookupFailed;

    RadioUi(Activity activity, RadioPatch radio) {
        this.activityRef = new WeakReference<>(activity);
        this.radio = radio;
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    boolean handleKey(KeyEvent event) {
        Activity activity = activityRef.get();
        if (activity == null) return false;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (isPanelOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (down && event.getRepeatCount() == 0) closePanel();
                return true;
            }
            return false; // the panel's focused views handle the rest
        }

        boolean longSelect = (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                && down && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
        boolean opener = down && event.getRepeatCount() == 0 && (code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_PROG_RED || code == KeyEvent.KEYCODE_PROG_GREEN
                || code == KeyEvent.KEYCODE_PROG_YELLOW || code == KeyEvent.KEYCODE_PROG_BLUE);
        if (longSelect || opener) {
            showBar(true);
            return true;
        }

        View focus = activity.getCurrentFocus();
        boolean focusInBar = bar != null && isDescendant(focus, bar);
        if (focusInBar) {
            barVisibleUntilMs = SystemClock.elapsedRealtime() + BAR_FORCED_MS;
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (down && event.getRepeatCount() == 0) {
                    focus.clearFocus();
                    barVisibleUntilMs = SystemClock.elapsedRealtime() + BAR_LINGER_MS;
                }
                return true;
            }
            return false;
        }

        // D-pad up from the top of F1's controls moves into the bar
        if (down && code == KeyEvent.KEYCODE_DPAD_UP && bar != null && bar.getVisibility() == View.VISIBLE && focus != null) {
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) activity.getWindow().getDecorView(), focus, View.FOCUS_UP);
            if (next == null || isDescendant(next, bar)) {
                barLater.requestFocus();
                return true;
            }
        }
        return false;
    }

    // ── Refresh (every radio tick) ───────────────────────────────────────────

    void update() {
        Activity activity = activityRef.get();
        if (activity == null) return;
        ensureViews(activity);
        long now = SystemClock.elapsedRealtime();
        RadioPatch.SyncState state = radio.getSyncState();
        boolean radioMatters = radio.isEnabled() && radio.isLive();

        // Bar: with F1's controls, while it has focus, or for a while after long-press OK
        View focus = activity.getCurrentFocus();
        boolean controlsVisible = areF1ControlsVisible(activity);
        if (controlsVisible) barVisibleUntilMs = Math.max(barVisibleUntilMs, now + BAR_LINGER_MS);
        boolean showBar = !isPanelOpen() && (radioMatters || isDescendant(focus, bar))
                && (now < barVisibleUntilMs || isDescendant(focus, bar));
        bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) refreshBar(state);

        if (isPanelOpen()) refreshPanel(state);

        refreshNotices(state, now, showBar);
        lastState = state;
    }

    void notify(Notice notice, Long valueMs) {
        Activity activity = activityRef.get();
        if (activity == null) return;
        ensureViews(activity);
        switch (notice) {
            case IN_SYNC:
                addNotice(Icon.CHECK, t("Radio synchroon met het beeld", "Radio in sync with the picture"),
                        t("Lang OK voor radio-instellingen.", "Long-press OK for radio settings."));
                break;
            case ACOUSTIC_ADJUSTED:
                long ms = valueMs == null ? 0L : valueMs;
                addNotice(Icon.WAVE, t("Afgestemd op het circuitgeluid", "Matched to the circuit sound"),
                        String.format(Locale.getDefault(), t("Radio %.2f s %s gezet.", "Radio set %.2f s %s."),
                                Math.abs(ms) / 1000.0, ms < 0 ? t("eerder", "earlier") : t("later", "later")));
                break;
            case BUFFER_EXCEEDED:
                addNotice(Icon.WARN, t("Te ver achter live voor de radio", "Too far behind live for the radio"),
                        t("Je hoort weer het F1-geluid tot je dichter bij live kijkt.",
                                "You hear the F1 audio again until you watch closer to live."));
                break;
            default:
                break;
        }
    }

    void dispose() {
        if (root != null && root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        root = null;
        bar = null;
        panel = null;
        notices = null;
        activeNotices.clear();
        countdownCard = null;
    }

    // ── Bar ──────────────────────────────────────────────────────────────────

    private void showBar(boolean focus) {
        Activity activity = activityRef.get();
        if (activity == null) return;
        ensureViews(activity);
        barVisibleUntilMs = SystemClock.elapsedRealtime() + BAR_FORCED_MS;
        bar.setVisibility(View.VISIBLE);
        refreshBar(radio.getSyncState());
        if (focus) barLater.requestFocus();
    }

    private void refreshBar(RadioPatch.SyncState state) {
        setDot(barDot, state);
        barStatus.setText(shortStatus(state));
        boolean auto = radio.isAutoSync();
        barValue.setText(formatSeconds(auto ? radio.getAdjustMs() : radio.getTargetDelayMs(), auto));
        barValueLabel.setText(auto ? t("correctie", "correction") : t("vertraging", "delay"));
    }

    private LinearLayout buildBar(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(rounded(BG, dp(c, 14)));
        b.setPadding(dp(c, 18), 0, dp(c, 12), 0);

        LinearLayout titleColumn = new LinearLayout(c);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(c, "Grand Prix Radio", 21, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        titleColumn.addView(title);
        LinearLayout statusRow = new LinearLayout(c);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        barDot = dot(c, GREEN, 8);
        statusRow.addView(barDot);
        barStatus = text(c, "", 11, MUTED, false);
        barStatus.setPadding(dp(c, 6), 0, 0, 0);
        statusRow.addView(barStatus);
        titleColumn.addView(statusRow);
        b.addView(titleColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Left = more delay (radio later), right = less delay (radio earlier)
        barLater = button(c, "◀  " + t("Meer vertraging", "More delay"), true);
        barLater.setOnClickListener(v -> nudge(RadioPatch.STEP_MS));
        b.addView(barLater, buttonParams(c, ViewGroup.LayoutParams.WRAP_CONTENT, 42));

        LinearLayout valueColumn = new LinearLayout(c);
        valueColumn.setOrientation(LinearLayout.VERTICAL);
        valueColumn.setGravity(Gravity.CENTER);
        barValue = text(c, "", 19, TEXT, true);
        barValue.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        valueColumn.addView(barValue);
        barValueLabel = text(c, "", 9, DIM, false);
        valueColumn.addView(barValueLabel);
        b.addView(valueColumn, new LinearLayout.LayoutParams(dp(c, 92), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView earlier = button(c, t("Minder vertraging", "Less delay") + "  ▶", true);
        earlier.setOnClickListener(v -> nudge(-RadioPatch.STEP_MS));
        b.addView(earlier, buttonParams(c, ViewGroup.LayoutParams.WRAP_CONTENT, 42));

        View divider = new View(c);
        divider.setBackgroundColor(0xFF343945);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(c, 1), dp(c, 36));
        dividerParams.setMargins(dp(c, 10), 0, dp(c, 4), 0);
        b.addView(divider, dividerParams);

        TextView more = button(c, t("Meer opties", "More options"), false);
        more.setOnClickListener(v -> openPanel());
        b.addView(more, buttonParams(c, ViewGroup.LayoutParams.WRAP_CONTENT, 42));
        return b;
    }

    private void nudge(long deltaMs) {
        radio.nudge(deltaMs);
        barVisibleUntilMs = SystemClock.elapsedRealtime() + BAR_FORCED_MS;
        refreshBar(radio.getSyncState());
        if (isPanelOpen()) refreshPanel(radio.getSyncState());
    }

    // ── Panel ────────────────────────────────────────────────────────────────

    private boolean isPanelOpen() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    private void openPanel() {
        bar.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        refreshPanel(radio.getSyncState());
        panelEarlier.requestFocus();
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        showBar(true);
    }

    private void refreshPanel(RadioPatch.SyncState state) {
        setDot(panelDot, state);
        panelStatusTitle.setText(longStatusTitle(state));
        panelStatusText.setText(longStatusText(state));
        timeline.setLags(radio.getVideoLagMs(), radio.getTargetDelayMs());
        boolean auto = radio.isAutoSync();
        panelValue.setText(auto
                ? t("correctie ", "correction ") + formatSeconds(radio.getAdjustMs(), true)
                : t("vaste vertraging ", "fixed delay ") + formatSeconds(radio.getTargetDelayMs(), false));
        autoRow.setChecked(auto);
        acousticRow.setChecked(radio.isAcoustic() && !radio.isAcousticSuspended());
        acousticRow.setEnabledLook(auto);
        acousticRow.setSubtitle(!auto ? t("alleen bij automatisch", "auto-sync only")
                : radio.isAcousticSuspended() ? t("gepauzeerd, zelf bijgesteld", "paused, adjusted by hand") : null);
        resumeAcoustic.setVisibility(auto && radio.isAcoustic() && radio.isAcousticSuspended() ? View.VISIBLE : View.GONE);
        radioRow.setText(radio.isEnabled() ? t("Terug naar F1-commentaar", "Back to F1 commentary")
                : t("Grand Prix Radio gebruiken", "Use Grand Prix Radio"));
    }

    private LinearLayout buildPanel(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(BG);
        p.setPadding(dp(c, 30), dp(c, 30), dp(c, 30), dp(c, 24));

        p.addView(text(c, t("AUDIO", "AUDIO"), 10, DIM, true));
        TextView title = text(c, "Grand Prix Radio", 30, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.addView(title);
        p.addView(text(c, t("Live · vervangt het F1-commentaar", "Live · replaces the F1 commentary"), 11, DIM, false));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, dp(c, 10)));
        card.setPadding(dp(c, 15), dp(c, 14), dp(c, 15), dp(c, 14));
        LinearLayout statusRow = new LinearLayout(c);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        panelDot = dot(c, GREEN, 9);
        statusRow.addView(panelDot);
        panelStatusTitle = text(c, "", 15, TEXT, true);
        panelStatusTitle.setPadding(dp(c, 8), 0, 0, 0);
        statusRow.addView(panelStatusTitle);
        card.addView(statusRow);
        panelStatusText = text(c, "", 11, MUTED, false);
        panelStatusText.setPadding(0, dp(c, 6), 0, dp(c, 8));
        card.addView(panelStatusText);
        timeline = new TimelineView(c);
        card.addView(timeline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 46)));
        p.addView(card, marginTop(c, 18));

        LinearLayout adjustHeader = new LinearLayout(c);
        adjustHeader.addView(text(c, t("Bijstellen", "Adjust"), 11, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panelValue = text(c, "", 11, DIM, false);
        adjustHeader.addView(panelValue);
        p.addView(adjustHeader, marginTop(c, 18));

        LinearLayout nudges = new LinearLayout(c);
        TextView later = button(c, "◀  " + t("Meer vertraging", "More delay"), true);
        later.setOnClickListener(v -> nudge(RadioPatch.STEP_MS));
        panelEarlier = button(c, t("Minder vertraging", "Less delay") + "  ▶", true);
        panelEarlier.setOnClickListener(v -> nudge(-RadioPatch.STEP_MS));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(c, 42), 1f);
        nudges.addView(later, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(c, 42), 1f);
        half2.setMargins(dp(c, 9), 0, 0, 0);
        nudges.addView(panelEarlier, half2);
        p.addView(nudges, marginTop(c, 8));

        autoRow = new SwitchRow(c, t("Automatisch synchroniseren", "Automatic sync"));
        autoRow.setOnClickListener(v -> {
            radio.setAutoSync(!radio.isAutoSync());
            refreshPanel(radio.getSyncState());
        });
        p.addView(autoRow, marginTop(c, 14));
        acousticRow = new SwitchRow(c, t("Afstemmen op circuitgeluid", "Match to circuit sound"));
        acousticRow.setOnClickListener(v -> {
            if (!radio.isAutoSync()) return;
            if (radio.isAcousticSuspended()) radio.resumeAcoustic();
            else radio.setAcoustic(!radio.isAcoustic());
            refreshPanel(radio.getSyncState());
        });
        p.addView(acousticRow);
        resumeAcoustic = button(c, t("Weer automatisch afstemmen", "Match automatically again"), true);
        resumeAcoustic.setOnClickListener(v -> {
            radio.resumeAcoustic();
            refreshPanel(radio.getSyncState());
        });
        p.addView(resumeAcoustic, sized(c, ViewGroup.LayoutParams.WRAP_CONTENT, 38, 4));
        radioRow = button(c, "", false);
        radioRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        radioRow.setOnClickListener(v -> {
            radio.setEnabled(!radio.isEnabled());
            refreshPanel(radio.getSyncState());
        });
        p.addView(radioRow, sized(c, ViewGroup.LayoutParams.MATCH_PARENT, 40, 2));

        View spacer = new View(c);
        p.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        p.addView(text(c, t("Terug = sluiten · OK lang ingedrukt opent de radiobalk",
                "Back = close · long-press OK opens the radio bar"), 10, DIM, false));
        return p;
    }

    // ── Notices ──────────────────────────────────────────────────────────────

    private enum Icon { CHECK, WAVE, WARN, COUNTDOWN }

    private final class NoticeCard {
        final View view;
        final IconView icon;
        final TextView title;
        final TextView body;
        long hideAtMs;

        NoticeCard(Context c, Icon kind, String titleText, String bodyText) {
            LinearLayout card = new LinearLayout(c);
            card.setBackground(rounded(BG, dp(c, 12)));
            card.setPadding(dp(c, 15), dp(c, 13), dp(c, 15), dp(c, 13));
            icon = new IconView(c, kind);
            card.addView(icon, new LinearLayout.LayoutParams(dp(c, 33), dp(c, 33)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(c, 12), 0, 0, 0);
            title = text(c, titleText, 13, TEXT, true);
            body = text(c, bodyText, 11, MUTED, false);
            texts.addView(title);
            texts.addView(body);
            card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            view = card;
        }
    }

    private void addNotice(Icon kind, String title, String body) {
        Activity activity = activityRef.get();
        if (activity == null || notices == null) return;
        NoticeCard card = new NoticeCard(activity, kind, title, body);
        card.hideAtMs = SystemClock.elapsedRealtime() + NOTICE_MS;
        activeNotices.add(card);
        while (activeNotices.size() > 2) {
            NoticeCard old = activeNotices.remove(0);
            notices.removeView(old.view);
            if (old == countdownCard) countdownCard = null;
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(activity, 12));
        notices.addView(card.view, params);
    }

    private void refreshNotices(RadioPatch.SyncState state, long now, boolean barVisible) {
        // Start-up countdown, kept on screen while the radio buffers up to its delay
        long remaining = radio.getBufferingRemainingMs();
        if (state == RadioPatch.SyncState.BUFFERING && remaining > 1_000L && radio.isLive()) {
            if (countdownCard == null) {
                addNotice(Icon.COUNTDOWN, "", t("Tot dan hoor je het F1-geluid. De radio valt synchroon in.",
                        "Until then you hear the F1 audio. The radio comes in in sync."));
                countdownCard = activeNotices.get(activeNotices.size() - 1);
            }
            long seconds = (remaining + 999L) / 1000L;
            countdownCard.title.setText(String.format(Locale.getDefault(),
                    t("Grand Prix Radio start over %d s", "Grand Prix Radio starts in %d s"), seconds));
            countdownCard.icon.setCountdown(seconds, Math.max(seconds, countdownCard.icon.countdownTotal));
            countdownCard.hideAtMs = now + 1_000L;
        }
        if (state == RadioPatch.SyncState.BUFFER_EXCEEDED && lastState != RadioPatch.SyncState.BUFFER_EXCEEDED) {
            notify(Notice.BUFFER_EXCEEDED, null);
        }
        for (int i = activeNotices.size() - 1; i >= 0; i--) {
            NoticeCard card = activeNotices.get(i);
            if (now > card.hideAtMs) {
                activeNotices.remove(i);
                notices.removeView(card.view);
                if (card == countdownCard) countdownCard = null;
            }
        }
        Activity activity = activityRef.get();
        if (activity == null) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) notices.getLayoutParams();
        int top = barVisible ? dp(activity, 24 + 72 + 12) : dp(activity, 24);
        if (params.topMargin != top) {
            params.topMargin = top;
            notices.setLayoutParams(params);
        }
        notices.setVisibility(isPanelOpen() || activeNotices.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Status texts ─────────────────────────────────────────────────────────

    private String shortStatus(RadioPatch.SyncState state) {
        switch (state) {
            case IN_SYNC:
                if (radio.isAutoSync() && radio.isAcoustic() && !radio.isAcousticSuspended() && radio.getLastAcousticCheckAtMs() > 0) {
                    return t("Synchroon · afgestemd op circuitgeluid", "In sync · matched to circuit sound");
                }
                return radio.isAutoSync() ? t("Synchroon met het beeld", "In sync with the picture")
                        : t("Handmatige vertraging", "Manual delay");
            case BUFFERING: return t("Radio laadt, je hoort nu F1-geluid", "Radio loading, F1 audio for now");
            case STARTING: return t("Verbinden…", "Connecting…");
            case VIDEO_AHEAD: return t("Beeld loopt voor op de radio", "Picture is ahead of the radio");
            case BUFFER_EXCEEDED: return t("Te ver achter live — F1-geluid", "Too far behind live — F1 audio");
            case PAUSED: return t("Gepauzeerd", "Paused");
            case NOT_LIVE: return t("Alleen bij live sessies", "Live sessions only");
            case OFF: return t("Uit — F1-commentaar", "Off — F1 commentary");
            default: return t("Fout — radio gestopt", "Error — radio stopped");
        }
    }

    private String longStatusTitle(RadioPatch.SyncState state) {
        switch (state) {
            case IN_SYNC: return radio.isAutoSync() ? t("Synchroon met het beeld", "In sync with the picture")
                    : t("Vaste vertraging", "Fixed delay");
            case BUFFERING:
            case STARTING: return t("Radio start", "Radio starting");
            case VIDEO_AHEAD: return t("Beeld loopt voor", "Picture is ahead");
            case BUFFER_EXCEEDED: return t("Te ver achter live", "Too far behind live");
            case PAUSED: return t("Gepauzeerd", "Paused");
            case NOT_LIVE: return t("Geen live sessie", "Not a live session");
            case OFF: return t("Radio staat uit", "Radio is off");
            default: return t("Radio gestopt na een fout", "Radio stopped after an error");
        }
    }

    private String longStatusText(RadioPatch.SyncState state) {
        switch (state) {
            case IN_SYNC:
                if (!radio.isAutoSync()) return t("De radio volgt het beeld niet automatisch.", "The radio does not follow the picture automatically.");
                if (radio.isAcoustic() && !radio.isAcousticSuspended()) {
                    long check = radio.getLastAcousticCheckAtMs();
                    if (check <= 0) return t("Volgt het beeld; meet nu het circuitgeluid.", "Following the picture; measuring the circuit sound.");
                    long minutes = (SystemClock.elapsedRealtime() - check) / 60_000L;
                    return String.format(Locale.getDefault(), t("Afgestemd op het circuitgeluid, %d min geleden gecontroleerd.",
                            "Matched to the circuit sound, checked %d min ago."), minutes);
                }
                return t("Volgt hoe ver het beeld achter live loopt.", "Follows how far the picture is behind live.");
            case BUFFERING:
                long remaining = radio.getBufferingRemainingMs();
                return remaining > 0 ? String.format(Locale.getDefault(), t("In sync over ~%d s. Tot dan hoor je het F1-geluid.",
                        "In sync in ~%d s. Until then you hear the F1 audio."), (remaining + 999) / 1000)
                        : t("Bijna klaar.", "Almost ready.");
            case VIDEO_AHEAD: return t("Geef de radio minder vertraging.", "Give the radio less delay.");
            case BUFFER_EXCEEDED: return t("Je hoort het F1-geluid tot je dichter bij live kijkt.", "You hear the F1 audio until you watch closer to live.");
            case NOT_LIVE: return t("Bij herhalingen hoor je het F1-geluid.", "Replays use the F1 audio.");
            default: return "";
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void ensureViews(Activity activity) {
        if (root != null && root.getParent() != null) return;
        root = new FrameLayout(activity);
        root.setClipChildren(false);

        bar = buildBar(activity);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 72), Gravity.TOP);
        barParams.setMargins(dp(activity, 36), dp(activity, 24), dp(activity, 36), 0);
        root.addView(bar, barParams);
        bar.setVisibility(View.GONE);

        notices = new LinearLayout(activity);
        notices.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(dp(activity, 315), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        noticeParams.setMargins(0, dp(activity, 24), dp(activity, 36), 0);
        root.addView(notices, noticeParams);

        panel = buildPanel(activity);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(activity, 366), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(panel, panelParams);
        panel.setVisibility(View.GONE);

        ((ViewGroup) activity.getWindow().getDecorView()).addView(root,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** F1's TiledPlayerPlaybackControls.isControlsVisible(), found by class name so a rename only disables auto-show. */
    private boolean areF1ControlsVisible(Activity activity) {
        if (controlsLookupFailed) return false;
        try {
            View controls = controlsRef.get();
            if (controls == null || controls.getWindowToken() == null) {
                controls = findByClassName(activity.getWindow().getDecorView(), "TiledPlayerPlaybackControls");
                if (controls == null) return false;
                controlsRef = new WeakReference<>(controls);
                controlsVisibleMethod = controls.getClass().getMethod("isControlsVisible");
            }
            return Boolean.TRUE.equals(controlsVisibleMethod.invoke(controls));
        } catch (Throwable t) {
            controlsLookupFailed = true;
            return false;
        }
    }

    private View findByClassName(View view, String simpleName) {
        if (view == root) return null;
        if (view.getClass().getSimpleName().equals(simpleName)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByClassName(group.getChildAt(i), simpleName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isDescendant(View view, View ancestor) {
        if (view == null || ancestor == null) return false;
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) return true;
        }
        return false;
    }

    private void setDot(View dot, RadioPatch.SyncState state) {
        int color = state == RadioPatch.SyncState.IN_SYNC ? (radio.isAutoSync() && radio.isAcousticSuspended() ? AMBER : GREEN)
                : state == RadioPatch.SyncState.OFF || state == RadioPatch.SyncState.NOT_LIVE ? DIM : AMBER;
        ((GradientDrawable) dot.getBackground()).setColor(color);
    }

    private static String formatSeconds(long ms, boolean signed) {
        if (ms < 0 && !signed) return "–";
        return String.format(Locale.getDefault(), signed ? "%+.2f s" : "%.2f s", ms / 1000.0).replace('-', '−');
    }

    private static String t(String nl, String en) {
        return "nl".equals(Locale.getDefault().getLanguage()) ? nl : en;
    }

    private static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static TextView text(Context c, String value, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** A D-pad focusable button: white with an amber ring when focused. */
    private static TextView button(Context c, String label, boolean filled) {
        TextView b = text(c, label, 13, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = rounded(TEXT, dp(c, 9));
        focused.setStroke(dp(c, 2.5f), AMBER);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, rounded(filled ? BUTTON : 0x00000000, dp(c, 9)));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{DARK, TEXT}));
        return b;
    }

    private static LinearLayout.LayoutParams buttonParams(Context c, int width, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, dp(c, heightDp));
        p.setMargins(dp(c, 6), 0, dp(c, 6), 0);
        return p;
    }

    private static LinearLayout.LayoutParams sized(Context c, int width, int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, dp(c, heightDp));
        p.setMargins(0, dp(c, topDp), 0, 0);
        return p;
    }

    private static LinearLayout.LayoutParams marginTop(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(c, topDp), 0, 0);
        return p;
    }

    /** A focusable row with a label (and optional subtitle) and an on/off pill. */
    private static final class SwitchRow extends LinearLayout {
        private final TextView subtitle;
        private final PillView pill;
        private final TextView label;

        SwitchRow(Context c, String text) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(c, 12), 0, dp(c, 12), 0);
            setMinimumHeight(dp(c, 40));
            setFocusable(true);
            setClickable(true);
            StateListDrawable bg = new StateListDrawable();
            GradientDrawable focused = rounded(TEXT, dp(c, 9));
            focused.setStroke(dp(c, 2.5f), AMBER);
            bg.addState(new int[]{android.R.attr.state_focused}, focused);
            bg.addState(new int[]{}, rounded(0x00000000, dp(c, 9)));
            setBackground(bg);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            ColorStateList textColors = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{DARK, TEXT});
            label = text(c, text, 12, TEXT, false);
            label.setTextColor(textColors);
            label.setDuplicateParentStateEnabled(true);
            texts.addView(label);
            subtitle = text(c, "", 10, AMBER, false);
            subtitle.setVisibility(GONE);
            texts.addView(subtitle);
            addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pill = new PillView(c);
            addView(pill, new LayoutParams(dp(c, 35), dp(c, 20)));
        }

        void setChecked(boolean checked) { pill.setChecked(checked); }

        void setEnabledLook(boolean enabled) { setAlpha(enabled ? 1f : 0.5f); }

        void setSubtitle(String text) {
            subtitle.setText(text == null ? "" : text);
            subtitle.setVisibility(text == null ? GONE : VISIBLE);
        }
    }

    private static final class PillView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean checked;

        PillView(Context c) { super(c); }

        void setChecked(boolean value) {
            if (checked != value) {
                checked = value;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight();
            rect.set(0, 0, getWidth(), h);
            paint.setColor(checked ? GREEN : TRACK);
            canvas.drawRoundRect(rect, h / 2, h / 2, paint);
            paint.setColor(checked ? DARK : DIM);
            float r = h / 2 - h * 0.12f;
            canvas.drawCircle(checked ? getWidth() - h / 2 : h / 2, h / 2, r, paint);
        }
    }

    /** Live marker on the right; the picture (white dot) and radio (green ring) delays to its left. */
    private static final class TimelineView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long videoLagMs = -1L;
        private long radioDelayMs = -1L;

        TimelineView(Context c) {
            super(c);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, c.getResources().getDisplayMetrics()));
        }

        void setLags(long video, long radio) {
            videoLagMs = video;
            radioDelayMs = radio;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float density = getResources().getDisplayMetrics().density;
            float lineY = 14 * density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TRACK);
            canvas.drawRect(0, lineY - density, w, lineY + density, paint);
            paint.setColor(TEXT);
            canvas.drawRect(w - 2 * density, lineY - 8 * density, w, lineY + 8 * density, paint);
            canvas.drawText("LIVE", w - 30 * density, lineY - 10 * density, paint);
            float scale = Math.max(60_000f, Math.max(videoLagMs, radioDelayMs) * 1.25f);
            float r = 6 * density;
            if (videoLagMs >= 0) {
                float x = w - (videoLagMs / scale) * w;
                canvas.drawCircle(x, lineY, r, paint);
            }
            if (radioDelayMs >= 0) {
                float x = w - (radioDelayMs / scale) * w;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f * density);
                paint.setColor(GREEN);
                canvas.drawCircle(x, lineY, r, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(MUTED);
            float textY = lineY + 24 * density;
            canvas.drawText(videoLagMs >= 0 ? String.format(Locale.getDefault(), t("Beeld %.1f s achter live", "Picture %.1f s behind live"), videoLagMs / 1000.0) : "", 0, textY, paint);
            String radioText = radioDelayMs >= 0 ? String.format(Locale.getDefault(), t("Radio %.1f s", "Radio %.1f s"), radioDelayMs / 1000.0) : "";
            canvas.drawText(radioText, w - paint.measureText(radioText), textY, paint);
        }
    }

    private static final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Icon kind;
        private final RectF rect = new RectF();
        long countdown;
        long countdownTotal;

        IconView(Context c, Icon kind) {
            super(c);
            this.kind = kind;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }

        void setCountdown(long seconds, long total) {
            countdown = seconds;
            countdownTotal = total;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float s = getWidth();
            float d = getResources().getDisplayMetrics().density;
            float c = s / 2;
            paint.setStyle(Paint.Style.FILL);
            switch (kind) {
                case COUNTDOWN: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(3 * d);
                    rect.set(3 * d, 3 * d, s - 3 * d, s - 3 * d);
                    paint.setColor(0xFF343945);
                    canvas.drawOval(rect, paint);
                    paint.setColor(AMBER);
                    float fraction = countdownTotal > 0 ? (float) countdown / countdownTotal : 0f;
                    canvas.drawArc(rect, -90, 360 * fraction, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(TEXT);
                    paint.setTextSize(13 * d);
                    canvas.drawText(String.valueOf(countdown), c, c + 4.5f * d, paint);
                    break;
                }
                case CHECK: {
                    paint.setColor(0xFF1E3A2C);
                    canvas.drawCircle(c, c, c, paint);
                    paint.setColor(GREEN);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2.4f * d);
                    canvas.drawLine(c - 6 * d, c, c - 2 * d, c + 4 * d, paint);
                    canvas.drawLine(c - 2 * d, c + 4 * d, c + 6.5f * d, c - 4.5f * d, paint);
                    break;
                }
                case WAVE: {
                    paint.setColor(BUTTON);
                    canvas.drawCircle(c, c, c, paint);
                    paint.setColor(TEXT);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2 * d);
                    float[] heights = {2, 8, 14, 6, 2};
                    for (int i = 0; i < heights.length; i++) {
                        float x = c + (i - 2) * 4 * d;
                        canvas.drawLine(x, c - heights[i] / 2 * d, x, c + heights[i] / 2 * d, paint);
                    }
                    break;
                }
                case WARN: {
                    paint.setColor(0xFF3A2A12);
                    canvas.drawCircle(c, c, c, paint);
                    paint.setColor(AMBER);
                    paint.setTextSize(17 * d);
                    canvas.drawText("!", c, c + 6 * d, paint);
                    break;
                }
                default:
                    break;
            }
        }
    }
}
