package android.os;
public final class SystemClock {
    public static volatile long nowMs;
    public static long elapsedRealtime() { return nowMs; }
    public static long elapsedRealtimeNanos() { return nowMs * 1_000_000L; }
}
