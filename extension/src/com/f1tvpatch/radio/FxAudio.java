package com.f1tvpatch.radio;

import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;

/**
 * Plays F1TV's "FX" audio track (circuit sound only, no commentary) while the radio carries the
 * commentary, so the acoustic sync compares the radio with clean circuit sound instead of with a
 * commentary mix. F1's own audio is muted then anyway; the user's own track is put back as soon as F1's
 * audio is needed again (radio drops, radio switched off, session over).
 *
 * Everything goes through reflection on ClearVR's public API (as F1TV itself uses it for its audio
 * menu: setDesiredAudioTrack + applyDesiredState), so a changed SDK only means "no FX track", never a crash.
 */
final class FxAudio {
    private static final String TAG = RadioEngine.TAG;
    private static final String PKG_PLAYER = "com.tiledmedia.clearvrplayer.";
    private static final long RETRY_MS = 20_000L;
    private static final int MAX_FAILURES = 3;

    /** The track the viewer had before we switched, put back by {@link #restore}. */
    private Object originalTrack;
    private boolean switched;
    private boolean tracksLogged;
    private boolean unavailable;
    private boolean noFxLogged;
    private int failures;
    private long nextAttemptAtMs;

    /** Whether FX is playing because we switched to it. */
    boolean isActive() {
        return switched;
    }

    /**
     * Makes FX the playing audio track. Returns true when this call started a switch (the audio the
     * tap hears changes from here on), false when nothing changed (already FX, none on offer, backing off).
     */
    boolean ensureFx(Object clearVr) {
        if (unavailable || clearVr == null) return false;
        long now = SystemClock.elapsedRealtime();
        if (now < nextAttemptAtMs) return false;
        try {
            Object active = activeTrack(clearVr);
            if (active == null) return false; // no audio stream yet; try again next tick
            List<?> tracks = tracksOf(active);
            if (!tracksLogged) {
                tracksLogged = true;
                StringBuilder sb = new StringBuilder("audio tracks on offer:");
                for (Object t : tracks) sb.append(" [").append(name(t)).append('/').append(language(t)).append(']');
                sb.append(" active=[").append(name(active)).append('/').append(language(active)).append(']');
                Log.i(TAG, sb.toString());
            }
            if (isFx(active)) return false;
            Object fx = findFx(tracks);
            if (fx == null) {
                // This feed has none (a camera feed, say); look again later, the next feed may have one
                nextAttemptAtMs = now + 60_000L;
                if (!noFxLogged) {
                    noFxLogged = true;
                    Log.i(TAG, "no FX audio track on offer");
                }
                return false;
            }
            originalTrack = active;
            apply(clearVr, fx);
            switched = true;
            nextAttemptAtMs = now + RETRY_MS;
            Log.i(TAG, "FX audio track selected for the sync (was [" + name(active) + "/" + language(active) + "])");
            return true;
        } catch (Throwable t) {
            failed("FX audio track not selectable", t);
            return false;
        }
    }

    /** Puts the viewer's own track back after {@link #ensureFx} switched away from it. */
    void restore(Object clearVr) {
        if (!switched) return;
        switched = false;
        Object back = originalTrack;
        originalTrack = null;
        if (back == null || clearVr == null) return;
        try {
            Object active = activeTrack(clearVr);
            if (active != null && !isFx(active)) return; // someone else already switched it back
            apply(clearVr, back);
            nextAttemptAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
            Log.i(TAG, "audio track restored to [" + name(back) + "/" + language(back) + "]");
        } catch (Throwable t) {
            failed("could not restore the audio track", t);
        }
    }

    private void failed(String what, Throwable t) {
        failures++;
        nextAttemptAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
        if (failures >= MAX_FAILURES || t instanceof LinkageError) unavailable = true;
        Log.w(TAG, what + " (" + failures + ")", t);
    }

    // ── ClearVR through reflection ───────────────────────────────────────────

    private static Object activeTrack(Object clearVr) throws Exception {
        Object stream = clearVr.getClass().getMethod("getActiveAudioStream").invoke(clearVr);
        return stream == null ? null : stream.getClass().getMethod("getAudioTrack").invoke(stream);
    }

    private static List<?> tracksOf(Object track) throws Exception {
        Object feed = track.getClass().getMethod("getFeed").invoke(track);
        Object list = feed == null ? null : feed.getClass().getMethod("getAudioTracks").invoke(feed);
        return list instanceof List ? (List<?>) list : java.util.Collections.emptyList();
    }

    private static Object findFx(List<?> tracks) {
        Object loose = null;
        for (Object t : tracks) {
            String lang = language(t).toLowerCase(Locale.ROOT);
            if (lang.equals("fx") || lang.equals("cfx")) return t;
            String name = name(t);
            if (loose == null && (name.equalsIgnoreCase("fx") || lang.contains("fx")
                    || name.toLowerCase(Locale.ROOT).contains("ambient") || name.toLowerCase(Locale.ROOT).contains("effects"))) loose = t;
        }
        return loose;
    }

    private static boolean isFx(Object track) {
        String lang = language(track).toLowerCase(Locale.ROOT);
        return lang.equals("fx") || lang.equals("cfx") || name(track).equalsIgnoreCase("fx");
    }

    private static String name(Object track) {
        return text(track, "getName");
    }

    private static String language(Object track) {
        return text(track, "getLanguage");
    }

    private static String text(Object track, String getter) {
        try {
            Object value = track.getClass().getMethod(getter).invoke(track);
            return value == null ? "" : value.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** What F1TV does for its audio menu: select the track, then apply the desired state. */
    private static void apply(Object clearVr, Object track) throws Exception {
        Class<?> selectionType = Class.forName(PKG_PLAYER + "AudioTrackSelection");
        Class<?> audioSelection = Class.forName(PKG_PLAYER + "AudioSelection");
        Constructor<?> ctor = selectionType.getConstructor(boolean.class, audioSelection);
        Object selection = ctor.newInstance(false, track);
        clearVr.getClass().getMethod("setDesiredAudioTrack", selectionType).invoke(clearVr, selection);

        Class<?> handlerType = Class.forName(PKG_PLAYER + "PlayerRequestHandler");
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                String m = method.getName();
                if (m.equals("onFailure")) Log.w(TAG, "audio track change failed: " + (args == null || args.length == 0 ? "" : args[0]));
                else if (m.equals("onSuccess")) Log.i(TAG, "audio track change applied");
                return null;
            }
        });
        Class<?> stateConfig = Class.forName("com.tiledmedia.clearvrparameters.DesiredStateConfig");
        clearVr.getClass().getMethod("applyDesiredState", stateConfig, handlerType).invoke(clearVr, null, handler);
    }
}
