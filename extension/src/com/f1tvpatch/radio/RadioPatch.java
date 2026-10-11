package com.f1tvpatch.radio;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.avs.f1.ui.tiledmediaplayer.TiledPlayer;
import com.tiledmedia.clearvrenums.TimingType;
import com.tiledmedia.clearvrparameters.TimingReport;
import com.tiledmedia.clearvrplayer.TiledmediaPlayer;
import com.tiledmedia.clearvrview.TiledmediaView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Entry points called from the patched F1TV smali, plus the controller that keeps the custom
 * radio in sync with the ClearVR video.
 *
 * Sync model: radio delay = video lag + user adjustment. Video lag is "now minus the wall-clock
 * time of the frame on screen" from ClearVR's WALLCLOCK_TIME timing report, so it automatically
 * follows stream start-up latency, pauses, rewinds and timeshift. The user adjustment absorbs the
 * radio's own broadcast latency and only needs calibrating once per station.
 *
 * Every call into F1TV/ClearVR is wrapped: if an app update renames something, the radio turns
 * itself off instead of crashing the player.
 */
public final class RadioPatch {
    private static final String TAG = RadioEngine.TAG;
    private static final String PREFS = "f1tv_radio_patch";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ADJUST_MS = "adjust_ms";

    private static final long TICK_MS = 100L;
    private static final long LAG_WINDOW_MS = 1_500L;
    private static final long LAG_JUMP_MS = 600L;
    private static final long MUTE_AFTER_AUDIBLE_MS = 500L;
    private static final long ENGINE_IDLE_RELEASE_MS = 3 * 60_000L;
    private static final long OVERLAY_TIMEOUT_MS = 8_000L;
    private static final long OVERLAY_PASSIVE_MS = 5_000L;
    private static final long FINE_STEP_MS = 250L;
    private static final long COARSE_STEP_MS = 1_000L;
    private static final int MAX_CONSECUTIVE_FAILURES = 50;

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

    private final WeakReference<TiledPlayer> playerRef;
    private final WeakReference<Activity> activityRef;
    private final SharedPreferences prefs;
    private final ArrayDeque<long[]> lagSamples = new ArrayDeque<>();
    private boolean enabled;
    private long adjustMs;
    private boolean wasVideoPlaying;
    private int lagAboveCount;
    private long videoLagMs = -1L;
    private boolean lagFromWallclock;
    private boolean f1MutedByUs;
    private boolean disabledByError;
    private int consecutiveFailures;
    private boolean live;
    private boolean announcedStart;
    private boolean announcedSync;

    private TextView overlay;
    private boolean overlayInteractive;
    private long overlayHideAtMs;

    private final Runnable tick = this::tick;

    private RadioPatch(TiledPlayer player, Activity activity) {
        playerRef = new WeakReference<>(player);
        activityRef = new WeakReference<>(activity);
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        enabled = prefs.getBoolean(KEY_ENABLED, Config.DEFAULT_ENABLED);
        adjustMs = prefs.getLong(KEY_ADJUST_MS, Config.DEFAULT_ADJUST_MS);
    }

    // ── Smali hook entry points ──────────────────────────────────────────────

    /** Called at the end of {@code TiledPlayer.<init>}. */
    public static void onPlayerCreated(Object tiledPlayer, Activity activity) {
        try {
            if (Config.STREAM_URL.isEmpty() || !(tiledPlayer instanceof TiledPlayer)) return;
            registerLifecycle(activity);
            if (current != null) current.detach();
            MAIN.removeCallbacks(RELEASE_ENGINE);
            current = new RadioPatch((TiledPlayer) tiledPlayer, activity);
            MAIN.post(current.tick);
            Log.i(TAG, "attached to TiledPlayer enabled=" + current.enabled + " adjustMs=" + current.adjustMs);
        } catch (Throwable t) {
            Log.e(TAG, "onPlayerCreated failed; radio disabled", t);
        }
    }

    /** Called first thing in {@code TiledPlayerActivityTv.dispatchKeyEvent}; true = consumed. */
    public static boolean onKeyEvent(Activity activity, KeyEvent event) {
        try {
            RadioPatch controller = current;
            if (controller == null || controller.activityRef.get() != activity) return false;
            return controller.handleKey(event);
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
        removeOverlay();
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
            TiledPlayer player = playerRef.get();
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
        updateOverlay();
        if (current == this) MAIN.postDelayed(tick, TICK_MS);
    }

    private void step(TiledPlayer player) {
        if (disabledByError) return;
        // isLiveContent() only reads a field; isPlaying() goes through F1TV's lazy ClearVR player
        // getter, so it must not be called before content is loaded or we'd create the player early.
        live = player.isLiveContent();
        if (!enabled || !live) {
            if (engine != null) engine.setPlaying(false);
            setF1Muted(false);
            wasVideoPlaying = false;
            return;
        }
        boolean videoPlaying = player.isPlaying();
        if (engine == null) {
            engine = new RadioEngine(Config.STREAM_URL);
            engine.start();
        }
        if (!announcedStart) {
            announcedStart = true;
            showOverlay(false);
        }

        if (videoPlaying && !wasVideoPlaying) lagSamples.clear();
        wasVideoPlaying = videoPlaying;

        long sample = sampleVideoLag(player);
        videoLagMs = sample < 0 ? -1L : smoothLag(sample);
        engine.setTargetDelayMs(videoLagMs < 0 ? -1L : Math.max(0L, videoLagMs + adjustMs));
        engine.setPlaying(videoPlaying);

        long audibleSince = engine.getAudibleSinceMs();
        boolean radioCarrying = audibleSince > 0 && SystemClock.elapsedRealtime() - audibleSince >= MUTE_AFTER_AUDIBLE_MS;
        if (radioCarrying) {
            setF1Muted(true);
            if (!announcedSync) {
                announcedSync = true;
                showOverlay(false);
            }
        } else if (videoPlaying && audibleSince == 0) {
            // Radio can't cover this moment (buffering, outside buffer, stream down): F1 audio back.
            setF1Muted(false);
        }
    }

    /** Raw "how far behind real time is the picture" sample in ms, or -1 if unknown. */
    private long sampleVideoLag(TiledPlayer player) {
        TiledPlayer.ViewsHolder holder = TiledPlayer.access$getViewsHolder$p(player);
        TiledmediaView view = holder == null ? null : holder.getMainView();
        if (view == null) return -1L;

        TimingReport wallclock = view.getCurrentTimingReport(TimingType.WALLCLOCK_TIME);
        if (wallclock != null && wallclock.getIsSuccess() && wallclock.isWallclockTimeSourceBased()) {
            long frameEpochMs = wallclock.getCurrentPositionInMilliseconds();
            if (frameEpochMs > 1_000_000_000_000L) {
                lagFromWallclock = true;
                return System.currentTimeMillis() - frameEpochMs;
            }
        }

        // No usable wall clock: fixed base delay plus however far the viewer is behind live.
        lagFromWallclock = false;
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
        TiledPlayer player = playerRef.get();
        if (player == null) return;
        try {
            TiledmediaPlayer clearVr = TiledPlayer.access$getPlayer(player);
            if (clearVr == null) return;
            // muteAudio is ignored until ClearVR is preparing playout, so re-assert every tick.
            if (clearVr.getIsAudioMuted() != mute) clearVr.muteAudio(mute);
            f1MutedByUs = mute;
        } catch (Throwable t) {
            Log.w(TAG, "could not " + (mute ? "mute" : "unmute") + " F1 audio", t);
        }
    }

    // ── Remote control + overlay ─────────────────────────────────────────────

    private boolean handleKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (!overlayInteractive) {
            boolean opener = code == KeyEvent.KEYCODE_MENU
                    || code == KeyEvent.KEYCODE_PROG_RED || code == KeyEvent.KEYCODE_PROG_GREEN
                    || code == KeyEvent.KEYCODE_PROG_YELLOW || code == KeyEvent.KEYCODE_PROG_BLUE;
            boolean longSelect = (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                    && down && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
            if ((opener && down && event.getRepeatCount() == 0) || longSelect) {
                showOverlay(true);
                return true;
            }
            return false;
        }

        // Overlay is open: it owns every key until closed.
        if (!down) return true;
        boolean firstPress = event.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: changeAdjust(FINE_STEP_MS); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: changeAdjust(-FINE_STEP_MS); break;
            case KeyEvent.KEYCODE_DPAD_UP: changeAdjust(COARSE_STEP_MS); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: changeAdjust(-COARSE_STEP_MS); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (firstPress) setEnabled(!enabled);
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_PROG_BLUE:
                if (firstPress) hideOverlay();
                return true;
            default:
                break;
        }
        overlayHideAtMs = SystemClock.elapsedRealtime() + OVERLAY_TIMEOUT_MS;
        updateOverlay();
        return true;
    }

    private void changeAdjust(long deltaMs) {
        adjustMs = Math.max(-120_000L, Math.min(120_000L, adjustMs + deltaMs));
        prefs.edit().putLong(KEY_ADJUST_MS, adjustMs).apply();
    }

    private void setEnabled(boolean value) {
        enabled = value;
        disabledByError = false;
        prefs.edit().putBoolean(KEY_ENABLED, value).apply();
        Log.i(TAG, "radio " + (value ? "enabled" : "disabled"));
    }

    private void showOverlay(boolean interactive) {
        Activity activity = activityRef.get();
        if (activity == null) return;
        if (overlay == null) {
            overlay = new TextView(activity);
            overlay.setTextColor(Color.WHITE);
            overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            overlay.setBackgroundColor(0xCC101010);
            int pad = dp(activity, 14);
            overlay.setPadding(pad, pad, pad, pad);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(activity, 32), dp(activity, 48), 0);
            ((ViewGroup) activity.getWindow().getDecorView()).addView(overlay, lp);
        }
        overlayInteractive = overlayInteractive || interactive;
        overlayHideAtMs = SystemClock.elapsedRealtime() + (interactive ? OVERLAY_TIMEOUT_MS : OVERLAY_PASSIVE_MS);
        overlay.bringToFront();
        updateOverlay();
    }

    private void hideOverlay() {
        removeOverlay();
    }

    private void removeOverlay() {
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
        overlayInteractive = false;
    }

    private void updateOverlay() {
        if (overlay == null) return;
        if (SystemClock.elapsedRealtime() > overlayHideAtMs) {
            removeOverlay();
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("Custom radio: ").append(enabled ? "ON" : "OFF");
        if (overlayInteractive) text.append("   (OK = toggle)");
        text.append('\n').append(String.format(Locale.US, "Sync adjust: %+.2f s", adjustMs / 1000.0));
        if (overlayInteractive) text.append("   ◀ ▶ 0.25 s   ▼ ▲ 1 s");
        if (enabled) {
            text.append('\n').append(statusLine());
            if (videoLagMs >= 0) {
                text.append('\n').append(String.format(Locale.US, "Video lag %.1f s (%s) → radio delay %.1f s",
                        videoLagMs / 1000.0, lagFromWallclock ? "wall clock" : "fallback",
                        Math.max(0L, videoLagMs + adjustMs) / 1000.0));
            }
        }
        if (overlayInteractive) text.append("\n◀ radio earlier · later ▶   BACK = close");
        overlay.setText(text);
    }

    private String statusLine() {
        if (disabledByError) return "Status: stopped after an error (see logcat F1Radio)";
        if (!live) return "Status: replay/VOD — using F1 audio";
        RadioEngine e = engine;
        if (e == null) return "Status: starting";
        String error = e.getLastError();
        switch (e.getState()) {
            case CONNECTING: return "Status: connecting" + (error != null ? " (" + error + ")" : "");
            case BUFFERING: return String.format(Locale.US, "Status: buffering, in sync in ~%d s", e.getBufferingRemainingMs() / 1000);
            case PLAYING: return "Status: in sync · " + e.getFormatDescription();
            case VIDEO_AHEAD: return "Status: video is closer to live than the radio — lower the adjust";
            case BUFFER_EXCEEDED: return "Status: too far behind live for the radio buffer — F1 audio";
            case PAUSED: return "Status: paused";
            case ERROR: return "Status: error " + error;
            default: return "Status: " + e.getState();
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
