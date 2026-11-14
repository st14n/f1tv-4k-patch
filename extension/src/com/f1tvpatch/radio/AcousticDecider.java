package com.f1tvpatch.radio;

import java.util.Locale;

/**
 * Decides when the acoustic measurements are good enough to move the radio.
 *
 * Two measurements run side by side: on the "side" signal (left minus right, commentary cancelled)
 * and on the mono mix. Each collects its correlation curves over time; a decision needs a clear,
 * stable peak in the combined curve. The side signal is preferred: on real race audio it is right
 * far more often. The mono mix is the fallback for a station whose circuit sound is mono.
 *
 * No Android types in here, so the same code runs in the test bench on recorded audio.
 */
final class AcousticDecider {
    static final int MIN_COUNT = 8;
    static final int RESYNC_MIN_COUNT = 4;
    // Chosen on a real race replay (2026-11-14 bench: two commentary tracks over the same circuit
    // sound, one MP3-coded like the radio; and pairs of unrelated stretches as the "nothing in common"
    // case). Stable combined peaks of unrelated audio reached 0.11 (side) and 0.08 (mono mix); real
    // offsets gave 0.23-0.87 (side) and 0.13-0.55 (mono mix). A wrong correction is worse than none,
    // so the bars sit clear of the unrelated-audio figures.
    static final double SIDE_MIN_SCORE = 0.18;
    static final double MONO_MIN_SCORE = 0.14;
    static final double MIN_RATIO = 1.8;
    static final double RESYNC_MIN_RATIO = 1.6;
    static final double FAR_MIN_RATIO = 2.4;
    static final long FAR_LAG_MS = 5_000L;
    /** Lags this close to the search limit are mostly edge artefacts. */
    static final long EDGE_MARGIN_MS = 1_500L;
    /**
     * The combined peak must also have stayed put this many evaluations in a row (5 s apart). The
     * windows of successive curves overlap, so one loud coincidence shows up in several of them; a
     * real offset stays where it is while new audio keeps coming in.
     */
    static final int STABLE_EVALUATIONS = 3;
    static final long STABLE_WITHIN_MS = 100L;
    /** Side and mono mix must not contradict each other by more than this. */
    static final long AGREEMENT_MS = 300L;

    static final class Decision {
        final long lagMs;
        /** "side" or "mono". */
        final String source;
        final int curves;
        final double score;
        final double ratio;

        Decision(long lagMs, String source, int curves, double score, double ratio) {
            this.lagMs = lagMs;
            this.source = source;
            this.curves = curves;
            this.score = score;
            this.ratio = ratio;
        }
    }

    private final AcousticCorrelator.Accumulator side = new AcousticCorrelator.Accumulator();
    private final AcousticCorrelator.Accumulator mono = new AcousticCorrelator.Accumulator();
    private long sideLastLag = Long.MIN_VALUE, monoLastLag = Long.MIN_VALUE;
    private int sideStable, monoStable;
    /** What the last {@link #add} saw, for the log (and the test bench). */
    String lastSummary = "";
    AcousticCorrelator.Estimate lastSideTotal, lastMonoTotal;
    int lastSideStable, lastMonoStable;

    void reset() {
        side.reset();
        mono.reset();
        sideLastLag = monoLastLag = Long.MIN_VALUE;
        sideStable = monoStable = 0;
    }

    /** Curves collected so far (the further of the two measurements). */
    int count() {
        return Math.max(side.count, mono.count);
    }

    /**
     * Adds the newest estimates (either may be null: no usable audio for it) and returns a decision
     * once one of the combined curves is convincing, else null.
     */
    Decision add(AcousticCorrelator.Estimate sideEstimate, AcousticCorrelator.Estimate monoEstimate, boolean resyncing) {
        if (sideEstimate != null) side.add(sideEstimate);
        if (monoEstimate != null) mono.add(monoEstimate);
        AcousticCorrelator.Estimate sideTotal = side.result();
        AcousticCorrelator.Estimate monoTotal = mono.result();
        lastSummary = describe("side", sideEstimate, sideTotal, side.count) + " | " + describe("mono", monoEstimate, monoTotal, mono.count);

        if (sideEstimate != null && sideTotal != null) {
            sideStable = sideLastLag != Long.MIN_VALUE && Math.abs(sideTotal.lagMs - sideLastLag) <= STABLE_WITHIN_MS ? sideStable + 1 : 1;
            sideLastLag = sideTotal.lagMs;
        }
        if (monoEstimate != null && monoTotal != null) {
            monoStable = monoLastLag != Long.MIN_VALUE && Math.abs(monoTotal.lagMs - monoLastLag) <= STABLE_WITHIN_MS ? monoStable + 1 : 1;
            monoLastLag = monoTotal.lagMs;
        }
        lastSideTotal = sideTotal;
        lastMonoTotal = monoTotal;
        lastSideStable = sideStable;
        lastMonoStable = monoStable;
        boolean sideSure = confident(sideTotal, side.count, SIDE_MIN_SCORE, resyncing) && sideStable >= STABLE_EVALUATIONS;
        boolean monoSure = confident(monoTotal, mono.count, MONO_MIN_SCORE, resyncing) && monoStable >= STABLE_EVALUATIONS;
        if (sideSure && monoSure && Math.abs(sideTotal.lagMs - monoTotal.lagMs) > AGREEMENT_MS) {
            // Both are sure of something different: trust neither, start over
            lastSummary += " | CONFLICT";
            reset();
            return null;
        }
        if (sideSure) return new Decision(sideTotal.lagMs, "side", side.count, sideTotal.score, sideTotal.peakRatio);
        if (monoSure) {
            // The side measurement has a clear opinion elsewhere, just not enough curves yet: wait for it
            if (sideTotal != null && sideTotal.score >= SIDE_MIN_SCORE && sideTotal.peakRatio >= MIN_RATIO
                    && Math.abs(sideTotal.lagMs - monoTotal.lagMs) > AGREEMENT_MS) {
                lastSummary += " | mono waits for side";
                return null;
            }
            return new Decision(monoTotal.lagMs, "mono", mono.count, monoTotal.score, monoTotal.peakRatio);
        }
        return null;
    }

    private static boolean confident(AcousticCorrelator.Estimate total, int count, double minScore, boolean resyncing) {
        if (total == null) return false;
        double minRatio = Math.abs(total.lagMs) > FAR_LAG_MS ? FAR_MIN_RATIO : (resyncing ? RESYNC_MIN_RATIO : MIN_RATIO);
        return count >= (resyncing ? RESYNC_MIN_COUNT : MIN_COUNT)
                && total.score >= minScore && total.peakRatio >= minRatio
                && Math.abs(total.lagMs) <= AcousticCorrelator.MAX_LAG_MS - EDGE_MARGIN_MS;
    }

    private static String describe(String name, AcousticCorrelator.Estimate now, AcousticCorrelator.Estimate total, int count) {
        if (total == null) return name + " -";
        String current = now == null ? "now -" : String.format(Locale.ROOT, "now lag=%d score=%.3f ratio=%.2f", now.lagMs, now.score, now.peakRatio);
        return String.format(Locale.ROOT, "%s %s, combined n=%d lag=%d score=%.3f ratio=%.2f",
                name, current, count, total.lagMs, total.score, total.peakRatio);
    }
}
