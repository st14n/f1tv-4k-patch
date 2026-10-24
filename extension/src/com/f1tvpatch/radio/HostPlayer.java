package com.f1tvpatch.radio;

import android.util.Log;

import com.tiledmedia.clearvrplayer.TiledmediaPlayer;
import com.tiledmedia.clearvrview.TiledmediaView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * F1TV's player object (TiledPlayer in 3.0.x), reached by reflection instead of compiled-in names.
 *
 * Only the ClearVR SDK types (com.tiledmedia.*) are known up front; F1TV's own class, its fields and
 * its Kotlin accessors (access$getPlayer, access$getViewsHolder$p) are found by the types they
 * return. A rename in an F1TV update then still finds them, and if one really is gone only that
 * feature stops instead of the radio throwing NoSuchMethodError.
 */
final class HostPlayer {
    private static final String TAG = RadioEngine.TAG;

    // Weak: the radio controller holds this adapter for as long as it runs, but must never keep
    // F1TV's player (and with it the activity) alive after F1TV is done with it.
    private final WeakReference<Object> playerRef;
    private final Class<?> type;

    private Method liveMethod;
    private Method playingMethod;
    private boolean liveResolved;
    private boolean playingResolved;

    // ClearVR player: a static accessor, a field holding it, or a kotlin.Lazy around it
    private Method clearVrAccessor;
    private Field clearVrField;
    private final List<Field> lazyFields = new ArrayList<>();
    private Field lazyPlayerField;
    private boolean clearVrResolved;

    // Main video view: field -> (optional) holder method that returns the TiledmediaView
    private Field viewField;
    private Method viewMethod;

    HostPlayer(Object player) {
        this.playerRef = new WeakReference<>(player);
        this.type = player.getClass();
    }

    /** F1TV released its player (activity gone): the controller should detach. */
    boolean isGone() {
        return playerRef.get() == null;
    }

    private Object player() {
        Object p = playerRef.get();
        if (p == null) throw new IllegalStateException("F1TV player released");
        return p;
    }

    /** Whether this session is live, or null when F1TV no longer has a recognisable live check. */
    Boolean isLive() throws Exception {
        if (!liveResolved) {
            liveResolved = true;
            liveMethod = findBooleanGetter("isLiveContent", "live");
            Log.i(TAG, "host live check: " + describe(liveMethod));
        }
        return liveMethod == null ? null : (Boolean) liveMethod.invoke(player());
    }

    /** Whether the video is playing. Unknown method = assume playing. */
    boolean isPlaying() throws Exception {
        if (!playingResolved) {
            playingResolved = true;
            playingMethod = findBooleanGetter("isPlaying", "playing");
            Log.i(TAG, "host playing check: " + describe(playingMethod));
        }
        return playingMethod == null || Boolean.TRUE.equals(playingMethod.invoke(player()));
    }

    /**
     * ClearVR's player, or null when it is not created (yet). Never creates one: F1TV builds it in a
     * lazy initializer, and calling that initializer (an R8 "$r8$lambda$" / "lambda$" bridge with the
     * same signature as the accessor) would make a fresh player on every call.
     *
     * Order: a field holding the player, or a kotlin.Lazy whose (initialized) value is the player;
     * only without such a field, the Kotlin accessor access$getPlayer (or another "access$" bridge).
     */
    TiledmediaPlayer clearVrPlayer() throws Exception {
        if (!clearVrResolved) {
            clearVrResolved = true;
            for (Field f : allFields(type)) {
                if (TiledmediaPlayer.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    clearVrField = f;
                    break;
                }
                if (isKotlinLazy(f.getType())) {
                    f.setAccessible(true);
                    lazyFields.add(f);
                }
            }
            if (clearVrField == null && lazyFields.isEmpty()) {
                for (Method m : allMethods(type)) {
                    Class<?>[] params = m.getParameterTypes();
                    String name = m.getName();
                    if (Modifier.isStatic(m.getModifiers()) && params.length == 1 && params[0].isAssignableFrom(type)
                            && TiledmediaPlayer.class.isAssignableFrom(m.getReturnType())
                            && name.startsWith("access$") && !name.contains("lambda")) {
                        m.setAccessible(true);
                        clearVrAccessor = m;
                        if (name.equals("access$getPlayer")) break;
                    }
                }
            }
            Log.i(TAG, "host ClearVR player: " + (clearVrField != null ? describe(clearVrField)
                    : clearVrAccessor != null ? describe(clearVrAccessor)
                    : lazyFields.size() + " lazy field(s), initialized value checked"));
        }
        Object target = player();
        if (clearVrField != null) {
            Object value = clearVrField.get(target);
            return value instanceof TiledmediaPlayer ? (TiledmediaPlayer) value : null;
        }
        if (lazyPlayerField != null) {
            TiledmediaPlayer value = initializedLazyValue(lazyPlayerField.get(target));
            if (value != null) return value;
        }
        for (Field f : lazyFields) {
            TiledmediaPlayer value = initializedLazyValue(f.get(target));
            if (value != null) {
                lazyPlayerField = f;
                return value;
            }
        }
        if (clearVrAccessor != null) {
            Object value = clearVrAccessor.invoke(null, target);
            return value instanceof TiledmediaPlayer ? (TiledmediaPlayer) value : null;
        }
        return null;
    }

    /** The value of an already-initialized kotlin.Lazy if it is a ClearVR player; never initializes it. */
    private static TiledmediaPlayer initializedLazyValue(Object lazy) throws Exception {
        if (lazy == null || !isKotlinLazy(lazy.getClass())) return null;
        if (!Boolean.TRUE.equals(lazy.getClass().getMethod("isInitialized").invoke(lazy))) return null;
        Object value = lazy.getClass().getMethod("getValue").invoke(lazy);
        return value instanceof TiledmediaPlayer ? (TiledmediaPlayer) value : null;
    }

    /** The ClearVR view showing the main feed, or null if there is none right now. */
    TiledmediaView mainView() throws Exception {
        if (viewField != null) {
            TiledmediaView view = viewVia(viewField, viewMethod);
            if (view != null) return view;
        }
        // (Re)discover: the holder may be set only once content loads
        for (Field f : allFields(type)) {
            if (f.getType().isPrimitive() || f.getType().getName().startsWith("java.")) continue;
            f.setAccessible(true);
            if (TiledmediaView.class.isAssignableFrom(f.getType())) {
                TiledmediaView view = viewVia(f, null);
                if (view != null) return remember(f, null, view);
                continue;
            }
            Object holder = f.get(player());
            if (holder == null) continue;
            Method best = null;
            for (Method m : holder.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0 || !TiledmediaView.class.isAssignableFrom(m.getReturnType())) continue;
                // Several views (multi-feed): prefer the main one
                if (best == null || m.getName().toLowerCase(Locale.ROOT).contains("main")) best = m;
            }
            if (best != null) {
                TiledmediaView view = viewVia(f, best);
                if (view != null) return remember(f, best, view);
            }
        }
        return null;
    }

    private TiledmediaView remember(Field f, Method m, TiledmediaView view) {
        if (viewField != f || viewMethod != m) {
            Log.i(TAG, "host main view: " + describe(f) + (m != null ? " -> " + m.getName() + "()" : ""));
        }
        viewField = f;
        viewMethod = m;
        return view;
    }

    private TiledmediaView viewVia(Field f, Method m) throws Exception {
        Object value = f.get(player());
        if (value != null && m != null) value = m.invoke(value);
        return value instanceof TiledmediaView ? (TiledmediaView) value : null;
    }

    /** Exact name first, then a no-arg boolean whose name contains the hint. */
    private Method findBooleanGetter(String name, String hint) {
        Method fallback = null;
        for (Method m : allMethods(type)) {
            if (m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
            String lower = m.getName().toLowerCase(Locale.ROOT);
            if (fallback == null && lower.startsWith("is") && lower.contains(hint)) fallback = m;
        }
        if (fallback != null) fallback.setAccessible(true);
        return fallback;
    }

    private static boolean isKotlinLazy(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals("kotlin.Lazy")) return true;
            for (Class<?> i : k.getInterfaces()) if (i.getName().equals("kotlin.Lazy")) return true;
        }
        return false;
    }

    private static List<Method> allMethods(Class<?> c) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) out.add(m);
        }
        return out;
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) out.add(f);
            }
        }
        return out;
    }

    private static String describe(Object member) {
        if (member instanceof Method) return ((Method) member).getDeclaringClass().getSimpleName() + "." + ((Method) member).getName() + "()";
        if (member instanceof Field) return ((Field) member).getDeclaringClass().getSimpleName() + "." + ((Field) member).getName();
        return "not found (assumed)";
    }
}
