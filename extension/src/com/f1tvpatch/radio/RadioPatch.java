package com.f1tvpatch.radio;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.tiledmedia.clearvrenums.TimingType;
import com.tiledmedia.clearvrparameters.TimingReport;
import com.tiledmedia.clearvrplayer.TiledmediaPlayer;
import com.tiledmedia.clearvrview.TiledmediaView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry points called from the patched F1TV smali, plus the controller that keeps the custom
 * radio in sync with the ClearVR video.
 *
 * Sync model (auto-sync): radio delay = video lag + adjustment. Video lag is "now minus the
 * wall-clock time of the frame on screen" from ClearVR's WALLCLOCK_TIME timing report, so it
 * follows stream start-up latency, pauses, rewinds and timeshift. The adjustment covers the
 * radio's own latency; acoustic sync keeps it right by matching the circuit sound under the radio
 * commentary to the F1 audio. With auto-sync off the radio plays at a fixed, manual delay.
 *
 * Every call into F1TV/ClearVR is wrapped: if an app update renames something, the radio turns
 * itself off instead of crashing the player. F1TV's own player is only reached through
 * {@link HostPlayer}, which finds its members by type, so most renames do not even get that far.
 */
public final class RadioPatch {
    private static final String TAG = RadioEngine.TAG;
    private static final String PREFS = "f1tv_radio_patch";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ADJUST_MS = "adjust_ms";
    private static final String KEY_ACOUSTIC = "acoustic_sync";
    private static final String KEY_AUTO_SYNC = "auto_sync";
    private static final String KEY_MANUAL_DELAY_MS = "manual_delay_ms";

    static final long STEP_MS = 250L;
    private static final long TICK_MS = 100L;
    private static final long LAG_WINDOW_MS = 1_500L;
    private static final long LAG_JUMP_MS = 600L;
    private static final long MUTE_AFTER_AUDIBLE_MS = 500L;
    private static final long ENGINE_IDLE_RELEASE_MS = 3 * 60_000L;
    private static final long MAX_DELAY_MS = 145_000L;
    private static final int MAX_CONSECUTIVE_FAILURES = 50;
    private static final long ACOUSTIC_INTERVAL_MS = 5_000L;
    // Correlation strength / peak-vs-runner-up needed before trusting an acoustic estimate.
    // Real matches on live sessions score 0.27-0.52 with a peak ratio of 2-7; chance matches on
    // commentary-only stretches reached 0.13 / 1.34 and moved the radio 10 s off. Logged with every
    // estimate so these can be tuned further.
    private static final double ACOUSTIC_MIN_SCORE = 0.25;
    private static final double ACOUSTIC_MIN_PEAK_RATIO = 2.0;
    // Lags this close to the search limit are mostly edge artefacts of the correlation
    private static final long ACOUSTIC_EDGE_MARGIN_MS = 1_500L;
    // Two estimates must agree this closely before the adjustment is changed...
    private static final long ACOUSTIC_AGREEMENT_MS = 250L;
    // ...and come from different audio: estimates run every 5 s over a 30 s window, so neighbours
    // share most of their input and agree by construction. Require a full window between them.
    private static final long ACOUSTIC_INDEPENDENT_MS = AcousticCorrelator.WINDOW_MS;
    // Smaller offsets are within what the measurement itself can resolve
    private static final long ACOUSTIC_DEADBAND_MS = 150L;
    // After a delay change the playout converges over ~3 s; only measure audio from after that
    private static final long ALIGNMENT_SETTLE_MS = 4_000L;
    private static final long TARGET_JUMP_MS = 500L;
    private static final ExecutorService ACOUSTIC_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "radio-acoustic-sync");
        thread.setDaemon(true);
        return thread;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static RadioPatch current;
    private static RadioEngine engine;
    private static boolean lifecycleRegistered;
    private static final Runnable RELEASE_ENGINE = () -> {
        if (engine != null && current == null) {
            engine.release();
            engine = null;
        }
    };

    /** What the on-screen UI shows. */
    enum SyncState { OFF, NOT_LIVE, STARTING, BUFFERING, IN_SYNC, VIDEO_AHEAD, BUFFER_EXCEEDED, PAUSED, ERROR }

    /** Held strongly: HostPlayer itself only weakly references F1TV's player. */
    private final HostPlayer host;
    private final WeakReference<Activity> activityRef;
    private final SharedPreferences prefs;
    private final ArrayDeque<long[]> lagSamples = new ArrayDeque<>();
    private final RadioUi ui;
    private boolean enabled;
    private boolean autoSync;
    private long adjustMs;
    private long manualDelayMs;
    private boolean wasVideoPlaying;
    private int lagAboveCount;
    private long videoLagMs = -1L;
    private long targetDelayMs = -1L;
    private boolean f1MutedByUs;
    private boolean disabledByError;
    private int consecutiveFailures;
    private boolean live;
    private boolean radioCarrying;
    private boolean announcedSync;
    private boolean acoustic;
    /** The user nudged the sync by hand this session; acoustic sync then leaves the adjustment alone. */
    private boolean acousticSuspended;
    private long lastAlignmentChangeAtMs;
    private long lastTargetDelayMs = -1L;
    private long nextAcousticAtMs;
    private boolean acousticInFlight;
    private AcousticCorrelator.Estimate pendingEstimate;
    private long pendingEstimateAtMs;
    private long lastAcousticCheckAtMs;
    private String lagSource = "none";
    private long nextStatusLogAtMs;

    private final Runnable tick = this::tick;

    private RadioPatch(HostPlayer player, Activity activity) {
        host = player;
        activityRef = new WeakReference<>(activity);
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        enabled = prefs.getBoolean(KEY_ENABLED, Config.DEFAULT_ENABLED);
        autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true);
        adjustMs = prefs.getLong(KEY_ADJUST_MS, Config.DEFAULT_ADJUST_MS);
        manualDelayMs = prefs.getLong(KEY_MANUAL_DELAY_MS, Config.FALLBACK_DELAY_MS);
        acoustic = prefs.getBoolean(KEY_ACOUSTIC, true);
        ui = new RadioUi(activity, this);
    }

    /** Whether the F1 and radio audio should be fingerprinted right now (see {@link AudioTap}). */
    static boolean isAcousticTapWanted() {
        RadioPatch controller = current;
        return controller != null && controller.enabled && controller.live && controller.autoSync
                && controller.acoustic && !controller.disabledByError;
    }

    // ── Smali hook entry points ──────────────────────────────────────────────

    /** Called at the end of F1TV's player constructor ({@code TiledPlayer.<init>} in 3.0.x). */
    public static void onPlayerCreated(Object hostPlayer, Activity activity) {
        try {
            if (Config.STREAM_URL.isEmpty() || hostPlayer == null || activity == null) return;
            registerLifecycle(activity);
            if (current != null) current.detach();
            MAIN.removeCallbacks(RELEASE_ENGINE);
            current = new RadioPatch(new HostPlayer(hostPlayer), activity);
            MAIN.post(current.tick);
            Log.i(TAG, "attached to " + hostPlayer.getClass().getName() + " enabled=" + current.enabled + " autoSync=" + current.autoSync
                    + " adjustMs=" + current.adjustMs + " acoustic=" + current.acoustic);
        } catch (Throwable t) {
            Log.e(TAG, "onPlayerCreated failed; radio disabled", t);
        }
    }

    /** Called first thing in the player activity's {@code dispatchKeyEvent}; true = consumed. */
    public static boolean onKeyEvent(Activity activity, KeyEvent event) {
        try {
            RadioPatch controller = current;
            if (controller == null || controller.activityRef.get() != activity) return false;
            return controller.ui.handleKey(event);
        } catch (Throwable t) {
            Log.e(TAG, "onKeyEvent failed", t);
            return false;
        }
    }

    private static void registerLifecycle(Activity activity) {
        if (lifecycleRegistered) return;
        lifecycleRegistered = true;
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityDestroyed(Activity a) {
                if (current != null && current.activityRef.get() == a) current.detach();
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        });
    }

    private void detach() {
        MAIN.removeCallbacks(tick);
        setF1Muted(false);
        if (engine != null) engine.setPlaying(false);
        ui.dispose();
        if (current == this) current = null;
        // Keep the stream (and its delay buffer) alive briefly so switching sessions is instant.
        MAIN.removeCallbacks(RELEASE_ENGINE);
        MAIN.postDelayed(RELEASE_ENGINE, ENGINE_IDLE_RELEASE_MS);
        Log.i(TAG, "detached");
    }

    // ── Sync loop ────────────────────────────────────────────────────────────

    private void tick() {
        if (current != this) return;
        try {
            HostPlayer player = host.isGone() ? null : host;
            Activity activity = activityRef.get();
            if (player == null || activity == null || activity.isFinishing()) {
                detach();
                return;
            }
            step(player);
            consecutiveFailures = 0;
        } catch (Throwable t) {
            // LinkageError = F1TV/ClearVR API changed: give up at once. Anything else may be a
            // transient state during start-up/teardown, so only give up if it keeps happening.
            consecutiveFailures++;
            Log.w(TAG, "sync loop failed (" + consecutiveFailures + ")", t);
            if (t instanceof LinkageError || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.e(TAG, "radio disabled for this session");
                disabledByError = true;
                setF1Muted(false);
                if (engine != null) engine.setPlaying(false);
            }
        }
        try {
            ui.update();
        } catch (Throwable t) {
            Log.w(TAG, "radio UI update failed", t);
        }
        if (current == this) MAIN.postDelayed(tick, TICK_MS);
    }

    private void step(HostPlayer player) throws Exception {
        if (disabledByError) return;
        // isLiveContent() only reads a field; isPlaying() goes through F1TV's lazy ClearVR player
        // getter, so it must not be called before content is loaded or we'd create the player early.
        Boolean hostLive = player.isLive();
        // No live check in this F1TV version: live = the frame on screen is a recent wall-clock time
        live = hostLive != null ? hostLive : isRecentWallclock(player);
        if (!enabled || !live) {
            if (engine != null) engine.setPlaying(false);
            setF1Muted(false);
            wasVideoPlaying = false;
            radioCarrying = false;
            return;
        }
        boolean videoPlaying = player.isPlaying();
        if (engine == null) {
            engine = new RadioEngine(Config.STREAM_URL);
            engine.start();
        }

        if (videoPlaying && !wasVideoPlaying) lagSamples.clear();
        wasVideoPlaying = videoPlaying;

        long sample = sampleVideoLag(player);
        videoLagMs = sample < 0 ? -1L : smoothLag(sample);
        logStatus(videoPlaying);
        if (autoSync) {
            targetDelayMs = videoLagMs < 0 ? -1L : clampDelay(videoLagMs + adjustMs);
        } else {
            targetDelayMs = manualDelayMs;
        }
        if (targetDelayMs >= 0 && (lastTargetDelayMs < 0 || Math.abs(targetDelayMs - lastTargetDelayMs) > TARGET_JUMP_MS)) {
            markAlignmentChanged();
        }
        if (targetDelayMs >= 0) lastTargetDelayMs = targetDelayMs;
        engine.setTargetDelayMs(targetDelayMs);
        engine.setPlaying(videoPlaying);

        long audibleSince = engine.getAudibleSinceMs();
        radioCarrying = audibleSince > 0 && SystemClock.elapsedRealtime() - audibleSince >= MUTE_AFTER_AUDIBLE_MS;
        if (radioCarrying) {
            setF1Muted(true);
            if (!announcedSync) {
                announcedSync = true;
                ui.notify(RadioUi.Notice.IN_SYNC, null);
            }
            if (videoPlaying) maybeRunAcousticEstimate();
        } else if (videoPlaying && audibleSince == 0) {
            // Radio can't cover this moment (buffering, outside buffer, stream down): F1 audio back.
            setF1Muted(false);
        }
    }

    private static boolean isRecentWallclock(HostPlayer player) throws Exception {
        TiledmediaView view = player.mainView();
        if (view == null) return false;
        TimingReport wallclock = view.getCurrentTimingReport(TimingType.WALLCLOCK_TIME);
        if (wallclock == null || !wallclock.getIsSuccess() || !wallclock.isWallclockTimeSourceBased()) return false;
        long lagMs = System.currentTimeMillis() - wallclock.getCurrentPositionInMilliseconds();
        return lagMs >= 0 && lagMs <= MAX_DELAY_MS;
    }

    /** Raw "how far behind real time is the picture" sample in ms, or -1 if unknown. */
    private long sampleVideoLag(HostPlayer player) throws Exception {
        TiledmediaView view = player.mainView();
        if (view == null) return -1L;

        TimingReport wallclock = view.getCurrentTimingReport(TimingType.WALLCLOCK_TIME);
        if (wallclock != null && wallclock.getIsSuccess() && wallclock.isWallclockTimeSourceBased()) {
            long frameEpochMs = wallclock.getCurrentPositionInMilliseconds();
            if (frameEpochMs > 1_000_000_000_000L) {
                lagSource = "wallclock";
                return System.currentTimeMillis() - frameEpochMs;
            }
        }
        lagSource = "fallback";

        // No usable wall clock: fixed base delay plus however far the viewer is behind live.
        TimingReport timeshift = view.getCurrentTimingReport(TimingType.TIMESHIFT);
        long shiftMs = timeshift != null && timeshift.getIsSuccess() ? Math.abs(timeshift.getTimeShiftInMilliseconds()) : 0L;
        return Config.FALLBACK_DELAY_MS + shiftMs;
    }

    /**
     * ClearVR refreshes the timing report every ~250 ms, so raw samples saw-tooth upwards between
     * refreshes. The lower envelope over a short window is the actual lag. Real jumps (seek,
     * go-live, rebuffer) reset the window so the radio follows within a few hundred ms.
     */
    private long smoothLag(long sample) {
        long now = SystemClock.elapsedRealtime();
        if (!lagSamples.isEmpty()) {
            long currentMin = minLag();
            if (sample < currentMin - LAG_JUMP_MS) {
                lagSamples.clear();
            } else if (sample > currentMin + LAG_JUMP_MS) {
                if (++lagAboveCount >= 3) {
                    lagSamples.clear();
                    lagAboveCount = 0;
                }
            } else {
                lagAboveCount = 0;
            }
        }
        lagSamples.addLast(new long[]{now, sample});
        while (!lagSamples.isEmpty() && now - lagSamples.peekFirst()[0] > LAG_WINDOW_MS) {
            lagSamples.removeFirst();
        }
        return minLag();
    }

    private long minLag() {
        long min = Long.MAX_VALUE;
        for (long[] s : lagSamples) min = Math.min(min, s[1]);
        return min;
    }

    private void setF1Muted(boolean mute) {
        if (!mute && !f1MutedByUs) return;
        HostPlayer player = host.isGone() ? null : host;
        if (player == null) return;
        try {
            TiledmediaPlayer clearVr = player.clearVrPlayer();
            if (clearVr == null) return;
            // muteAudio is ignored until ClearVR is preparing playout, so re-assert every tick.
            if (clearVr.getIsAudioMuted() != mute) clearVr.muteAudio(mute);
            f1MutedByUs = mute;
        } catch (Throwable t) {
            Log.w(TAG, "could not " + (mute ? "mute" : "unmute") + " F1 audio", t);
        }
    }

    private static long clampDelay(long delayMs) {
        return Math.max(0L, Math.min(MAX_DELAY_MS, delayMs));
    }

    // ── Actions (from the UI) ────────────────────────────────────────────────

    /** Remote nudge: positive = more delay (radio later). Moves the adjustment, or the manual delay. */
    void nudge(long deltaMs) {
        if (autoSync && acoustic && !acousticSuspended) {
            acousticSuspended = true;
            Log.i(TAG, "acoustic sync suspended for this session (manual nudge)");
        }
        if (autoSync) {
            setAdjust(adjustMs + deltaMs);
        } else {
            manualDelayMs = clampDelay(manualDelayMs + deltaMs);
            prefs.edit().putLong(KEY_MANUAL_DELAY_MS, manualDelayMs).apply();
            markAlignmentChanged();
        }
    }

    void setAutoSync(boolean value) {
        if (autoSync == value) return;
        // Switching to manual keeps the current delay, so nothing jumps
        if (!value && targetDelayMs >= 0) {
            manualDelayMs = targetDelayMs;
            prefs.edit().putLong(KEY_MANUAL_DELAY_MS, manualDelayMs).apply();
        }
        autoSync = value;
        prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply();
        markAlignmentChanged();
        Log.i(TAG, "auto-sync " + (value ? "on" : "off"));
    }

    void setAcoustic(boolean value) {
        acoustic = value;
        if (value) acousticSuspended = false;
        prefs.edit().putBoolean(KEY_ACOUSTIC, value).apply();
        markAlignmentChanged();
        Log.i(TAG, "acoustic sync " + (value ? "on" : "off"));
    }

    void resumeAcoustic() {
        acousticSuspended = false;
        markAlignmentChanged();
        Log.i(TAG, "acoustic sync resumed");
    }

    void setEnabled(boolean value) {
        enabled = value;
        disabledByError = false;
        announcedSync = false;
        prefs.edit().putBoolean(KEY_ENABLED, value).apply();
        Log.i(TAG, "radio " + (value ? "enabled" : "disabled"));
    }

    /** Every 10 s while live: what the sync is based on, for tuning from the logs. */
    private void logStatus(boolean videoPlaying) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextStatusLogAtMs) return;
        nextStatusLogAtMs = now + 10_000L;
        Log.i(TAG, "sync status videoLagMs=" + videoLagMs + " (" + lagSource + ") adjustMs=" + adjustMs
                + " targetMs=" + targetDelayMs + " autoSync=" + autoSync + " playing=" + videoPlaying
                + " radioCarrying=" + radioCarrying + " f1Muted=" + f1MutedByUs + " state=" + getSyncState());
    }

    /** From adb (LoginReceiver, DUMP-protected): set the sync adjustment of the running session. */
    static void setAdjustFromAdb(long value) {
        MAIN.post(() -> {
            RadioPatch controller = current;
            if (controller != null) {
                controller.setAdjust(value);
                Log.i(TAG, "adjustMs set over adb: " + controller.adjustMs);
            }
        });
    }

    private void setAdjust(long value) {
        adjustMs = Math.max(-120_000L, Math.min(120_000L, value));
        prefs.edit().putLong(KEY_ADJUST_MS, adjustMs).apply();
        markAlignmentChanged();
    }

    // ── State (for the UI) ───────────────────────────────────────────────────

    boolean isEnabled() { return enabled; }
    boolean isLive() { return live; }
    boolean isAutoSync() { return autoSync; }
    boolean isAcoustic() { return acoustic; }
    boolean isAcousticSuspended() { return acousticSuspended; }
    long getAdjustMs() { return adjustMs; }
    long getVideoLagMs() { return videoLagMs; }
    long getTargetDelayMs() { return targetDelayMs; }
    /** Elapsed-realtime of the last acoustic check that found the radio in sync, 0 if none yet. */
    long getLastAcousticCheckAtMs() { return lastAcousticCheckAtMs; }

    long getBufferingRemainingMs() {
        RadioEngine e = engine;
        return e == null ? -1L : e.getBufferingRemainingMs();
    }

    SyncState getSyncState() {
        if (disabledByError) return SyncState.ERROR;
        if (!enabled) return SyncState.OFF;
        if (!live) return SyncState.NOT_LIVE;
        RadioEngine e = engine;
        if (e == null) return SyncState.STARTING;
        switch (e.getState()) {
            case CONNECTING: return SyncState.STARTING;
            case BUFFERING: return SyncState.BUFFERING;
            case PLAYING: return radioCarrying ? SyncState.IN_SYNC : SyncState.BUFFERING;
            case VIDEO_AHEAD: return SyncState.VIDEO_AHEAD;
            case BUFFER_EXCEEDED: return SyncState.BUFFER_EXCEEDED;
            case PAUSED: return SyncState.PAUSED;
            case ERROR: return SyncState.ERROR;
            default: return SyncState.STARTING;
        }
    }

    // ── Acoustic sync ────────────────────────────────────────────────────────

    private void markAlignmentChanged() {
        lastAlignmentChangeAtMs = SystemClock.elapsedRealtime() + ALIGNMENT_SETTLE_MS;
        pendingEstimate = null;
    }

    private void maybeRunAcousticEstimate() {
        long now = SystemClock.elapsedRealtime();
        if (!autoSync || !acoustic || acousticSuspended || acousticInFlight || now < nextAcousticAtMs) return;
        nextAcousticAtMs = now + ACOUSTIC_INTERVAL_MS;
        final double notBeforeMs = lastAlignmentChangeAtMs;
        acousticInFlight = true;
        ACOUSTIC_EXECUTOR.execute(() -> {
            AcousticCorrelator.Estimate estimate = null;
            try {
                estimate = AcousticCorrelator.estimate(AudioTap.F1_AUDIO, RadioEngine.RADIO_AUDIO, notBeforeMs);
            } catch (Throwable t) {
                Log.w(TAG, "acoustic estimate failed", t);
            }
            final AcousticCorrelator.Estimate result = estimate;
            MAIN.post(() -> {
                acousticInFlight = false;
                if (result != null && current == this) onAcousticEstimate(result, notBeforeMs);
            });
        });
    }

    private void onAcousticEstimate(AcousticCorrelator.Estimate estimate, double notBeforeMs) {
        // The alignment changed while this was computing: it describes audio that no longer applies
        if (lastAlignmentChangeAtMs != notBeforeMs || acousticSuspended || !autoSync) return;
        boolean confident = estimate.score >= ACOUSTIC_MIN_SCORE && estimate.peakRatio >= ACOUSTIC_MIN_PEAK_RATIO
                && Math.abs(estimate.lagMs) <= AcousticCorrelator.MAX_LAG_MS - ACOUSTIC_EDGE_MARGIN_MS;
        Log.i(TAG, String.format(Locale.ROOT, "acoustic estimate lagMs=%d score=%.3f peakRatio=%.2f confident=%b",
                estimate.lagMs, estimate.score, estimate.peakRatio, confident));
        if (!confident) return;
        if (Math.abs(estimate.lagMs) < ACOUSTIC_DEADBAND_MS) {
            pendingEstimate = null;
            lastAcousticCheckAtMs = SystemClock.elapsedRealtime();
            return;
        }
        AcousticCorrelator.Estimate previous = pendingEstimate;
        long now = SystemClock.elapsedRealtime();
        if (previous == null || Math.abs(previous.lagMs - estimate.lagMs) > ACOUSTIC_AGREEMENT_MS) {
            pendingEstimate = estimate;
            pendingEstimateAtMs = now;
            return;
        }
        // Agrees, but measured on (largely) the same audio: wait for an independent confirmation
        if (now - pendingEstimateAtMs < ACOUSTIC_INDEPENDENT_MS) return;
        pendingEstimate = null;
        long lagMs = (previous.lagMs + estimate.lagMs) / 2;
        // Radio heard lagMs after the F1 audio: take that much off its delay
        setAdjust(adjustMs - lagMs);
        lastAcousticCheckAtMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "acoustic correction lagMs=" + lagMs + " adjustMs=" + adjustMs);
        if (Math.abs(lagMs) >= 500L) ui.notify(RadioUi.Notice.ACOUSTIC_ADJUSTED, -lagMs);
    }
}
