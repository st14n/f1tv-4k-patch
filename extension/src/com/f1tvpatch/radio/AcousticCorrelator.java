package com.f1tvpatch.radio;

import android.os.SystemClock;

/**
 * Finds how much later the shared circuit sound is heard through the radio than through the F1
 * audio, by cross-correlating their {@link AcousticFeatures} fingerprints.
 */
final class AcousticCorrelator {
    private AcousticCorrelator() { }

    /** {@code lagMs} > 0: the radio is heard that much later than the F1 audio. */
    static final class Estimate {
        final long lagMs;
        final double score;
        final double peakRatio;
        /** The whole correlation curve over the lags (null for an integrated result). */
        final double[] correlation;

        Estimate(long lagMs, double score, double peakRatio, double[] correlation) {
            this.lagMs = lagMs;
            this.score = score;
            this.peakRatio = peakRatio;
            this.correlation = correlation;
        }
    }

    /**
     * Adds successive correlation curves. The shared circuit sound gives the same peak every time,
     * chance matches land on different lags and average out, so the combined curve is far more
     * reliable than any single estimate. Recent curves weigh more (decay), so a drift still shows.
     */
    static final class Accumulator {
        private static final double DECAY = 0.93;
        private double[] sum;
        private double weight;
        int count;

        void reset() {
            sum = null;
            weight = 0;
            count = 0;
        }

        void add(Estimate estimate) {
            if (estimate.correlation == null) return;
            if (sum == null || sum.length != estimate.correlation.length) {
                sum = new double[estimate.correlation.length];
                weight = 0;
                count = 0;
            }
            for (int i = 0; i < sum.length; i++) sum[i] = sum[i] * DECAY + estimate.correlation[i];
            weight = weight * DECAY + 1;
            count++;
        }

        /** The peak of the combined curve, or null when nothing was added yet. */
        Estimate result() {
            if (sum == null || count == 0) return null;
            double[] mean = new double[sum.length];
            for (int i = 0; i < sum.length; i++) mean[i] = sum[i] / weight;
            return analyze(mean, AcousticFeatures.HOP_MS, (sum.length - 1) / 2, null);
        }
    }

    static final long WINDOW_MS = 30_000L;
    /** Why the last call gave no estimate (for the log): settling after a change, or too little audio. */
    static volatile String lastSkip = "";
    static final long MAX_LAG_MS = 10_000L;
    // Leave the newest audio out: part of it hasn't been heard yet
    private static final long END_MARGIN_MS = 2_000L;
    // Removes slow level changes so short events (cars passing, gear changes) dominate
    private static final int DETREND_FRAMES = 50;
    private static final double MIN_COVERAGE = 0.9;
    private static final long SECOND_PEAK_EXCLUSION_MS = 600L;

    static Estimate estimate(AcousticFeatures f1, AcousticFeatures radio, double notBeforeMs) {
        long hop = AcousticFeatures.HOP_MS;
        int maxLag = (int) (MAX_LAG_MS / hop);
        int n = (int) (WINDOW_MS / hop);
        double nowMs = SystemClock.elapsedRealtime();
        double f1Start = nowMs - END_MARGIN_MS - MAX_LAG_MS - WINDOW_MS;
        double radioStart = f1Start - MAX_LAG_MS;
        if (radioStart < notBeforeMs) {
            lastSkip = String.format(java.util.Locale.ROOT, "settling: %d s until the first usable window", Math.round((notBeforeMs - radioStart) / 1000.0));
            return null;
        }

        float[][] f1Grid = f1.gridded(f1Start, n);
        float[][] radioGrid = radio.gridded(radioStart, n + 2 * maxLag);
        double f1Coverage = coverage(f1Grid);
        double radioCoverage = coverage(radioGrid);
        if (f1Coverage < MIN_COVERAGE || radioCoverage < MIN_COVERAGE) {
            lastSkip = String.format(java.util.Locale.ROOT, "too little audio: F1 %.0f%%, radio %.0f%% (need %.0f%%)",
                    f1Coverage * 100, radioCoverage * 100, MIN_COVERAGE * 100);
            return null;
        }

        int bands = AcousticFeatures.BAND_COUNT;
        double[] correlation = new double[2 * maxLag + 1];
        for (int b = 0; b < bands; b++) {
            double[] m = normalize(column(f1Grid, b));
            double[] r = normalize(column(radioGrid, b));
            for (int lag = -maxLag; lag <= maxLag; lag++) {
                // f1[t] lines up with radio[t + lag]; radio index 0 is maxLag steps before f1's
                double sum = 0;
                int count = 0;
                for (int t = 0; t < n; t++) {
                    double a = m[t];
                    double c = r[t + maxLag + lag];
                    if (!Double.isNaN(a) && !Double.isNaN(c)) {
                        sum += a * c;
                        count++;
                    }
                }
                if (count > 0) correlation[lag + maxLag] += sum / count / bands;
            }
        }

        return analyze(correlation, hop, maxLag, correlation);
    }

    /** Peak position (with sub-hop interpolation), its height and how much it stands out. */
    static Estimate analyze(double[] correlation, long hop, int maxLag, double[] keep) {
        int best = 0;
        for (int i = 0; i < correlation.length; i++) if (correlation[i] > correlation[best]) best = i;
        int exclusion = (int) (SECOND_PEAK_EXCLUSION_MS / hop);
        double second = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < correlation.length; i++) {
            if (Math.abs(i - best) > exclusion && correlation[i] > second) second = correlation[i];
        }
        double offset = 0;
        if (best > 0 && best < correlation.length - 1) {
            double y0 = correlation[best - 1];
            double y1 = correlation[best];
            double y2 = correlation[best + 1];
            double denominator = y0 - 2 * y1 + y2;
            if (denominator != 0) offset = Math.max(-0.5, Math.min(0.5, 0.5 * (y0 - y2) / denominator));
        }
        long lagMs = Math.round((best - maxLag + offset) * hop);
        double ratio = second > 0 ? correlation[best] / second : Double.POSITIVE_INFINITY;
        return new Estimate(lagMs, correlation[best], ratio, keep);
    }

    private static double coverage(float[][] grid) {
        int present = 0;
        for (float[] f : grid) if (f != null) present++;
        return grid.length == 0 ? 0 : (double) present / grid.length;
    }

    private static double[] column(float[][] grid, int band) {
        double[] out = new double[grid.length];
        for (int i = 0; i < grid.length; i++) out[i] = grid[i] == null ? Double.NaN : grid[i][band];
        return out;
    }

    /** Detrend with a moving average, then z-normalise; gaps stay NaN. */
    private static double[] normalize(double[] values) {
        double[] out = new double[values.length];
        int half = DETREND_FRAMES / 2;
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                out[i] = Double.NaN;
                continue;
            }
            double sum = 0;
            int count = 0;
            for (int k = Math.max(0, i - half); k <= Math.min(values.length - 1, i + half); k++) {
                if (!Double.isNaN(values[k])) {
                    sum += values[k];
                    count++;
                }
            }
            out[i] = values[i] - sum / count;
        }
        double mean = 0;
        int count = 0;
        for (double v : out) if (!Double.isNaN(v)) { mean += v; count++; }
        if (count == 0) return out;
        mean /= count;
        double variance = 0;
        for (double v : out) if (!Double.isNaN(v)) variance += (v - mean) * (v - mean);
        double std = Math.sqrt(variance / count);
        if (std < 1e-9) return out;
        for (int i = 0; i < out.length; i++) if (!Double.isNaN(out[i])) out[i] = (out[i] - mean) / std;
        return out;
    }
}
