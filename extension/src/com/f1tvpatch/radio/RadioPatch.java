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
    private static final String KEY_ACOUSTIC = "acoustic_sync2";
    private static final String KEY_FX_SYNC = "fx_sync";
    private static final String KEY_AUTO_SYNC = "auto_sync";
    private static final String KEY_MANUAL_DELAY_MS = "manual_delay_ms";
    private static final String KEY_BASE_LAG_MS = "base_lag_ms";

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
    // commentary-only stretches reached 0.13 / 1.34 and, at the 10 s search edge, moved the radio 10 s
    // off (2026-10-24 quali). Thresholds of 0.25 / 2.0 on top of that never let it correct at all
    // (2026-11-01 race). So: a moderate bar, no edge lags, two independent agreeing estimates, and a
    // clearly strong estimate is applied on its own. Logged so these can be tuned further.
    private static final double ACOUSTIC_MIN_SCORE = 0.15;
    private static final double ACOUSTIC_MIN_PEAK_RATIO = 1.4;
    private static final double ACOUSTIC_STRONG_SCORE = 0.30;
    private static final double ACOUSTIC_STRONG_PEAK_RATIO = 2.5;
    // Lags this close to the search limit are mostly edge artefacts
    private static final long ACOUSTIC_EDGE_MARGIN_MS = 1_500L;
    // Two estimates must agree this closely before the adjustment is changed...
    private static final long ACOUSTIC_AGREEMENT_MS = 250L;
    private static final long ACOUSTIC_PENDING_MAX_MS = 90_000L;
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
    private boolean mutedViaTap;
    private boolean disabledByError;
    private int consecutiveFailures;
    private boolean live;
    private boolean radioCarrying;
    private boolean announcedSync;
    private boolean acoustic;
    /** Listen to F1's FX (circuit sound only) track instead of the commentary mix while measuring. */
    private boolean fxSync;
    private final FxAudio fxAudio = new FxAudio();
    /** The user nudged the sync by hand this session; acoustic sync then leaves the adjustment alone. */
    private boolean acousticSuspended;
    private long lastAlignmentChangeAtMs;
    private long lastTargetDelayMs = -1L;
    private long nextAcousticAtMs;
    private boolean acousticInFlight;
    private AcousticCorrelator.Estimate pendingEstimate;
    private long pendingEstimateAtMs;
    private long lastAcousticCheckAtMs;
    /** User asked "sync again": until then one confident acoustic estimate is applied at once. */
    private long resyncUntilMs;
    private static final long RESYNC_WINDOW_MS = 90_000L;
    private String lagSource = "none";
    private boolean resyncNoticePending;
    private long nextStatusLogAtMs;

    private final Runnable tick = this::tick;

    private RadioPatch(HostPlayer player, Activity activity) {
        host = player;
        activityRef = new WeakReference<>(activity);
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        enabled = prefs.getBoolean(KEY_ENABLED, Config.DEFAULT_ENABLED);
        autoSync = true; // the radio always follows the picture; the switch only turns it off for this session
        adjustMs = prefs.getLong(KEY_ADJUST_MS, Config.DEFAULT_ADJUST_MS);
        manualDelayMs = prefs.getLong(KEY_MANUAL_DELAY_MS, Config.FALLBACK_DELAY_MS);
        acoustic = prefs.getBoolean(KEY_ACOUSTIC, true);
        fxSync = prefs.getBoolean(KEY_FX_SYNC, true);
        ui = new RadioUi(activity, this);
    }

    /** Whether the F1 and radio audio should be fingerprinted right now (see {@link AudioTap}). */
    static boolean isAcousticTapWanted() {
        RadioPatch controller = current;
        // Also during a "sync again" with the setting off: that measurement needs the audio just the same
        return controller != null && controller.enabled && controller.live && controller.autoSync
                && (controller.acoustic || SystemClock.elapsedRealtime() < controller.resyncUntilMs)
                && !controller.disabledByError;
    }

    // ── Smali hook entry points ──────────────────────────────────────────────

    /** Called at the end of F1TV's player constructor ({@code TiledPlayer.<init>} in 3.0.x). */
    public static void onPlayerCreated(Object hostPlayer, Activity activity) {
        try {
            if (!StreamUrl.has() || hostPlayer == null || activity == null) return;
            L.init(activity.getApplicationContext());
            registerLifecycle(activity);
            if (current != null) current.detach();
            MAIN.removeCallbacks(RELEASE_ENGINE);
            current = new RadioPatch(new HostPlayer(hostPlayer), activity);
            MAIN.post(current.tick);
            L.i("attached to " + hostPlayer.getClass().getName() + " enabled=" + current.enabled + " autoSync=" + current.autoSync
                    + " adjustMs=" + current.adjustMs + " acoustic=" + current.acoustic);
        } catch (Throwable t) {
            L.e("onPlayerCreated failed; radio disabled", t);
        }
    }

    /** Called first thing in the player activity's {@code dispatchKeyEvent}; true = consumed. */
    public static boolean onKeyEvent(Activity activity, KeyEvent event) {
        try {
            RadioPatch controller = current;
            if (controller == null || controller.activityRef.get() != activity) return false;
            return controller.ui.handleKey(event);
        } catch (Throwable t) {
            L.e("onKeyEvent failed", t);
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
        AudioTap.silence = false;
        if (engine != null) engine.setPlaying(false);
        ui.dispose();
        if (current == this) current = null;
        // Keep the stream (and its delay buffer) alive briefly so switching sessions is instant.
        MAIN.removeCallbacks(RELEASE_ENGINE);
        MAIN.postDelayed(RELEASE_ENGINE, ENGINE_IDLE_RELEASE_MS);
        L.i("detached");
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
            L.w("sync loop failed (" + consecutiveFailures + ")", t);
            if (t instanceof LinkageError || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                L.e("radio disabled for this session");
                disabledByError = true;
                setF1Muted(false);
                if (engine != null) engine.setPlaying(false);
            }
        }
        try {
            ui.update();
        } catch (Throwable t) {
            L.w("radio UI update failed", t);
        }
        if (current == this) MAIN.postDelayed(tick, TICK_MS);
    }

    private boolean startMuteChecked;

    /**
     * Once per player: F1's audio must not start muted. If ClearVR reports muted although this patch
     * did not mute it (a mute left over from an earlier session; the mute state lives in ClearVR's
     * core), say so in the log and unmute, so a replay is not silent. The viewer's own mute button
     * still works afterwards.
     */
    private void checkStartMute(HostPlayer player) {
        if (startMuteChecked || f1MutedByUs) return;
        try {
            TiledmediaPlayer clearVr = player.clearVrPlayer();
            if (clearVr == null) return;
            startMuteChecked = true;
            boolean muted = clearVr.getIsAudioMuted();
            L.i("ClearVR audio at start: muted=" + muted + " (patch has not muted it)");
            if (muted) {
                clearVr.muteAudio(false);
                L.i("ClearVR audio was muted at start: unmuted; now muted=" + clearVr.getIsAudioMuted());
            }
        } catch (Throwable t) {
            startMuteChecked = true;
            L.w("start mute check failed", t);
        }
    }

    private void step(HostPlayer player) throws Exception {
        if (disabledByError) return;
        // isLiveContent() only reads a field; isPlaying() goes through F1TV's lazy ClearVR player
        // getter, so it must not be called before content is loaded or we'd create the player early.
        Boolean hostLive = player.isLive();
        // No live check in this F1TV version: live = the frame on screen is a recent wall-clock time
        live = hostLive != null ? hostLive : isRecentWallclock(player);
        checkStartMute(player);
        if (!enabled || !live) {
            if (engine != null) engine.setPlaying(false);
            setF1Muted(false);
            wasVideoPlaying = false;
            radioCarrying = false;
            return;
        }
        boolean videoPlaying = player.isPlaying();
        if (engine == null) {
            engine = new RadioEngine(StreamUrl.get());
            engine.start();
        }

        if (videoPlaying && !wasVideoPlaying) lagSamples.clear();
        wasVideoPlaying = videoPlaying;

        long sample = sampleVideoLag(player);
        videoLagMs = sample < 0 ? -1L : smoothLag(sample);
        logStatus(videoPlaying);
        if (resyncNoticePending && SystemClock.elapsedRealtime() >= resyncUntilMs) {
            resyncNoticePending = false;
            if (resyncUntilMs != 0L) {
                ui.notify(RadioUi.Notice.RESYNC_FAILED, null);
                resyncUntilMs = 0L;
            }
        }
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
            manageFxTrack(player);
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

        TimingReport timeshift = view.getCurrentTimingReport(TimingType.TIMESHIFT);
        long shiftMs = timeshift != null && timeshift.getIsSuccess() ? Math.abs(timeshift.getTimeShiftInMilliseconds()) : -1L;
        TimingReport wallclock = view.getCurrentTimingReport(TimingType.WALLCLOCK_TIME);
        if (wallclock != null && wallclock.getIsSuccess() && wallclock.isWallclockTimeSourceBased()) {
            long frameEpochMs = wallclock.getCurrentPositionInMilliseconds();
            if (frameEpochMs > 1_000_000_000_000L) {
                lagSource = "wallclock";
                long lagMs = System.currentTimeMillis() - frameEpochMs;
                if (shiftMs >= 0) learnBaseLag(lagMs - shiftMs);
                return lagMs;
            }
        }
        lagSource = "fallback";

        // No usable wall clock: how far the viewer is behind the live edge, plus the base lag of that
        // edge, remembered from the last stream that did have a clock (the fixed 20 s before any).
        return baseLagMs() + Math.max(0L, shiftMs);
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

    /**
     * Silences F1's own audio while the radio carries the commentary. Preferably in the audio tap
     * (it writes silence to the track after fingerprinting the real samples), so the fingerprint does
     * not depend on how ClearVR mutes; ClearVR's own mute only when the tap sees no audio.
     */
    private void setF1Muted(boolean mute) {
        if (!mute && !f1MutedByUs) {
            AudioTap.silence = false;
            return;
        }
        boolean viaTap = mute && AudioTap.isAlive();
        AudioTap.silence = viaTap;
        HostPlayer player = host.isGone() ? null : host;
        if (player == null) {
            if (!mute) f1MutedByUs = false;
            return;
        }
        try {
            TiledmediaPlayer clearVr = player.clearVrPlayer();
            if (clearVr == null) return;
            if (!mute) fxAudio.restore(clearVr);
            // The tap does the muting: ClearVR itself stays unmuted so it keeps decoding real samples.
            // muteAudio is ignored until ClearVR is preparing playout, so re-assert every tick.
            boolean clearVrMute = mute && !viaTap;
            if (clearVr.getIsAudioMuted() != clearVrMute) clearVr.muteAudio(clearVrMute);
            if (f1MutedByUs != mute || mutedViaTap != viaTap) {
                L.i("F1 audio " + (mute ? "silenced via " + (viaTap ? "the audio tap" : "ClearVR mute") : "back"));
            }
            f1MutedByUs = mute;
            mutedViaTap = viaTap;
        } catch (Throwable t) {
            L.w("could not " + (mute ? "mute" : "unmute") + " F1 audio", t);
        }
    }

    /**
     * While the radio carries the commentary (F1's own audio is muted), F1 plays its FX track, the
     * circuit sound without commentary, so the acoustic sync has clean sound to compare the radio with.
     * Back to the viewer's own track when the measuring stops (or F1's audio is needed again).
     */
    private void manageFxTrack(HostPlayer player) {
        try {
            TiledmediaPlayer clearVr = player.clearVrPlayer();
            if (clearVr == null) return;
            if (fxSync && acoustic && autoSync && !acousticSuspended) {
                // The audio the tap hears changes: what was measured before does not apply
                if (fxAudio.ensureFx(clearVr)) markAlignmentChanged();
            } else {
                fxAudio.restore(clearVr);
            }
        } catch (Throwable t) {
            L.w("FX audio track handling failed", t);
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
            L.i("acoustic sync suspended for this session (manual nudge)");
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
        L.i("auto-sync " + (value ? "on" : "off"));
    }

    void setAcoustic(boolean value) {
        acoustic = value;
        if (value) acousticSuspended = false;
        prefs.edit().putBoolean(KEY_ACOUSTIC, value).apply();
        markAlignmentChanged();
        L.i("acoustic sync " + (value ? "on" : "off"));
    }

    /**
     * "Sync again" from the bar: forget what the sync has learned this session (lag window, pending
     * and paused acoustic state) and measure afresh; the next confident acoustic estimate is applied
     * at once, even with the circuit-sound option off. Gives up with a notice after 90 s.
     */
    void resync() {
        long now = SystemClock.elapsedRealtime();
        acousticSuspended = false;
        lagSamples.clear();
        lagAboveCount = 0;
        pendingEstimate = null;
        lastAcousticCheckAtMs = 0L;
        nextAcousticAtMs = 0L;
        resyncUntilMs = now + RESYNC_WINDOW_MS;
        resyncNoticePending = true;
        markAlignmentChanged();
        L.i("resync requested");
        ui.notify(RadioUi.Notice.RESYNC_STARTED, null);
    }

    /**
     * "That's right": keep the sound as it is now. The correction is stored (it already is, on every
     * change), and acoustic matching stays out of it for this session, so nothing moves it afterwards.
     */
    void lockSync() {
        acousticSuspended = true;
        pendingEstimate = null;
        resyncUntilMs = 0L;
        resyncNoticePending = false;
        prefs.edit().putLong(KEY_ADJUST_MS, adjustMs).apply();
        L.i("sync locked adjustMs=" + adjustMs);
        ui.notify(RadioUi.Notice.LOCKED, adjustMs);
    }

    boolean isResyncing() { return SystemClock.elapsedRealtime() < resyncUntilMs; }

    /** "Reconnect radio": drop the radio stream and its buffer and start it over (F1 audio meanwhile). */
    void reconnect() {
        if (engine != null) {
            engine.release();
            engine = null;
        }
        announcedSync = false;
        radioCarrying = false;
        setF1Muted(false);
        L.i("radio reconnect requested");
    }

    void resumeAcoustic() {
        acousticSuspended = false;
        markAlignmentChanged();
        L.i("acoustic sync resumed");
    }

    void setEnabled(boolean value) {
        enabled = value;
        disabledByError = false;
        announcedSync = false;
        prefs.edit().putBoolean(KEY_ENABLED, value).apply();
        L.i("radio " + (value ? "enabled" : "disabled"));
    }

    /** A different stream was chosen in the settings: drop the old one; the next tick starts the new one. */
    static void onStreamUrlChanged() {
        MAIN.post(() -> {
            if (engine != null) {
                engine.release();
                engine = null;
            }
            L.i("radio stream changed");
        });
    }

    private long lastBaseLagSavedAtMs;

    private long baseLagMs() {
        return prefs.getLong(KEY_BASE_LAG_MS, Config.FALLBACK_DELAY_MS);
    }

    /** A slow average of "wall-clock lag minus distance behind the live edge" for the no-clock fallback. */
    private void learnBaseLag(long valueMs) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBaseLagSavedAtMs < 60_000L || valueMs < 0L || valueMs > 90_000L) return;
        lastBaseLagSavedAtMs = now;
        prefs.edit().putLong(KEY_BASE_LAG_MS, (baseLagMs() * 3 + valueMs) / 4).apply();
    }

    /** How the acoustic measurement is doing, for the panel: collected curves and the combined result. */
    String getAcousticProgressText() {
        AcousticCorrelator.Estimate total = accumulator.result();
        if (total == null) return "";
        return String.format(Locale.ROOT, "%d/%d", Math.min(accumulator.count, INTEGRATED_MIN_COUNT), INTEGRATED_MIN_COUNT);
    }

    /** Every 10 s while live: what the sync is based on, for tuning from the logs. */
    private void logStatus(boolean videoPlaying) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextStatusLogAtMs) return;
        nextStatusLogAtMs = now + 10_000L;
        L.i("sync status videoLagMs=" + videoLagMs + " (" + lagSource + ") adjustMs=" + adjustMs
                + " targetMs=" + targetDelayMs + " autoSync=" + autoSync + " playing=" + videoPlaying
                + " radioCarrying=" + radioCarrying + " f1Muted=" + f1MutedByUs + " state=" + getSyncState()
                + " acoustic=" + acoustic + " suspended=" + acousticSuspended + " tapWrites=" + AudioTap.writes
                + " tapMute=" + mutedViaTap + String.format(Locale.ROOT, " f1Level=%.4f", AudioTap.level)
                + " fx=" + fxAudio.isActive() + " curves=" + accumulator.count);
    }

    /** From adb (LoginReceiver, DUMP-protected): set the sync adjustment of the running session. */
    static void setAdjustFromAdb(long value) {
        MAIN.post(() -> {
            RadioPatch controller = current;
            if (controller != null) {
                controller.setAdjust(value);
                L.i("adjustMs set over adb: " + controller.adjustMs);
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
        accumulator.reset();
    }

    private long nextAcousticLogAtMs;

    /** Why no acoustic estimate is being made, at most every 20 s. */
    private void logAcousticIdle(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextAcousticLogAtMs) return;
        nextAcousticLogAtMs = now + 20_000L;
        L.i(reason);
    }

    private void maybeRunAcousticEstimate() {
        long now = SystemClock.elapsedRealtime();
        boolean resyncing = now < resyncUntilMs;
        if (!autoSync || !(acoustic || resyncing) || acousticSuspended || acousticInFlight || now < nextAcousticAtMs) {
            if (acousticSuspended || !acoustic) {
                logAcousticIdle("acoustic idle: " + (acousticSuspended ? "paused after a manual change (use 'Sync again')" : "switched off"));
            }
            return;
        }
        nextAcousticAtMs = now + ACOUSTIC_INTERVAL_MS;
        final double notBeforeMs = lastAlignmentChangeAtMs;
        acousticInFlight = true;
        ACOUSTIC_EXECUTOR.execute(() -> {
            AcousticCorrelator.Estimate estimate = null;
            try {
                estimate = AcousticCorrelator.estimate(AudioTap.F1_AUDIO, RadioEngine.RADIO_AUDIO, notBeforeMs);
            } catch (Throwable t) {
                L.w("acoustic estimate failed", t);
            }
            final AcousticCorrelator.Estimate result = estimate;
            MAIN.post(() -> {
                acousticInFlight = false;
                if (result == null) logAcousticIdle("acoustic idle: " + AcousticCorrelator.lastSkip);
                if (result != null && current == this) onAcousticEstimate(result, notBeforeMs);
            });
        });
    }

    private final AcousticCorrelator.Accumulator accumulator = new AcousticCorrelator.Accumulator();
    private long lastAcousticApplyAtMs;
    // Combined evidence needed before moving the radio: this many curves, a clear peak that stands
    // out, nowhere near the search edge; far corrections need more. A "sync again" asks for less.
    private static final int INTEGRATED_MIN_COUNT = 8;
    private static final int RESYNC_MIN_COUNT = 4;
    private static final double INTEGRATED_MIN_SCORE = 0.10;
    private static final double INTEGRATED_MIN_RATIO = 1.6;
    private static final double RESYNC_MIN_RATIO = 1.4;
    private static final double FAR_MIN_RATIO = 2.2;
    private static final long FAR_LAG_MS = 5_000L;
    private static final long MIN_APPLY_INTERVAL_MS = 60_000L;

    private void onAcousticEstimate(AcousticCorrelator.Estimate estimate, double notBeforeMs) {
        // The alignment changed while this was computing: it describes audio that no longer applies
        if (lastAlignmentChangeAtMs != notBeforeMs || acousticSuspended || !autoSync) return;
        long now = SystemClock.elapsedRealtime();
        boolean resyncing = now < resyncUntilMs;
        accumulator.add(estimate);
        AcousticCorrelator.Estimate total = accumulator.result();
        if (total == null) return;
        L.i(String.format(Locale.ROOT, "acoustic now lag=%d score=%.3f ratio=%.2f | combined n=%d lag=%d score=%.3f ratio=%.2f",
                estimate.lagMs, estimate.score, estimate.peakRatio, accumulator.count, total.lagMs, total.score, total.peakRatio));
        long lag = total.lagMs;
        double minRatio = Math.abs(lag) > FAR_LAG_MS ? FAR_MIN_RATIO : (resyncing ? RESYNC_MIN_RATIO : INTEGRATED_MIN_RATIO);
        boolean confident = accumulator.count >= (resyncing ? RESYNC_MIN_COUNT : INTEGRATED_MIN_COUNT)
                && total.score >= INTEGRATED_MIN_SCORE && total.peakRatio >= minRatio
                && Math.abs(lag) <= AcousticCorrelator.MAX_LAG_MS - ACOUSTIC_EDGE_MARGIN_MS;
        if (!confident) return;
        if (Math.abs(lag) < ACOUSTIC_DEADBAND_MS) {
            accumulator.reset();
            lastAcousticCheckAtMs = now;
            if (resyncing) {
                resyncUntilMs = 0L;
                resyncNoticePending = false;
                ui.notify(RadioUi.Notice.RESYNC_OK, lag);
            }
            return;
        }
        if (!resyncing && now - lastAcousticApplyAtMs < MIN_APPLY_INTERVAL_MS) return;
        resyncUntilMs = 0L;
        resyncNoticePending = false;
        // Radio heard lag ms after the F1 audio: take that much off its delay
        setAdjust(adjustMs - lag);
        lastAcousticApplyAtMs = now;
        lastAcousticCheckAtMs = now;
        L.i("acoustic correction lagMs=" + lag + " adjustMs=" + adjustMs + " (combined of " + accumulator.count + ")");
        if (Math.abs(lag) >= 500L) ui.notify(RadioUi.Notice.ACOUSTIC_ADJUSTED, -lag);
    }
}
