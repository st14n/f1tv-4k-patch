package com.f1tvpatch.radio;

import java.util.*;

/**
 * The app's own code on recorded audio: stereo PCM into the mid and side fingerprints, estimates every
 * 5 s, AcousticDecider, and the correction applied as the app would (closed loop). Reports every
 * decision and whether it was right.
 *
 * args: f1.wav radio.wav initialLagMs [radioMono]
 */
public class Bench2 {
    public static void main(String[] a) throws Exception {
        long lag = Long.parseLong(a[2]);                 // radio heard this much later than the F1 audio
        boolean radioMono = a.length > 3 && a[3].equals("radioMono");
        int[] r1 = new int[1], c1 = new int[1], r2 = new int[1], c2 = new int[1];
        short[] f1 = Bench.readWav(a[0], r1, c1);
        short[] radio = Bench.readWav(a[1], r2, c2);
        if (radioMono) for (int i = 0; i + 1 < radio.length; i += 2) { short m = (short) ((radio[i] + radio[i + 1]) / 2); radio[i] = m; radio[i + 1] = m; }
        double durMs = Math.min(f1.length / c1[0] * 1000.0 / r1[0], radio.length / c2[0] * 1000.0 / r2[0]);

        AcousticFeatures fMid = new AcousticFeatures(false), fSide = new AcousticFeatures(true);
        AcousticFeatures rMid = new AcousticFeatures(false), rSide = new AcousticFeatures(true);
        AcousticDecider decider = new AcousticDecider();
        long base = 1_000_000L, nextEst = base + 60_000L, notBefore = 0, lastApply = -1_000_000L;
        int chunkMs = 20, f1Chunk = r1[0] * chunkMs / 1000 * c1[0], rChunk = r2[0] * chunkMs / 1000 * c2[0];
        int right = 0, wrong = 0, inSync = 0, falseInSync = 0;
        long firstDecisionAt = -1;
        StringBuilder log = new StringBuilder();
        double[] sideTrue = new double[2], sideFalse = new double[2], monoTrue = new double[2], monoFalse = new double[2], sideTrueMin = {9};
        for (long t = 0; t + chunkMs <= durMs; t += chunkMs) {
            int i1 = (int) (t / chunkMs) * f1Chunk, i2 = (int) (t / chunkMs) * rChunk;
            short[] b1 = Arrays.copyOfRange(f1, i1, i1 + f1Chunk), b2 = Arrays.copyOfRange(radio, i2, i2 + rChunk);
            fMid.addPcm16(b1, b1.length, r1[0], c1[0], base + t);
            fSide.addPcm16(b1, b1.length, r1[0], c1[0], base + t);
            rMid.addPcm16(b2, b2.length, r2[0], c2[0], base + t + lag);
            rSide.addPcm16(b2, b2.length, r2[0], c2[0], base + t + lag);
            long now = base + t;
            if (now < nextEst) continue;
            nextEst += 5_000L;
            android.os.SystemClock.nowMs = now;
            AcousticCorrelator.Estimate s = AcousticCorrelator.estimate(fSide, rSide, notBefore);
            AcousticCorrelator.Estimate m = AcousticCorrelator.estimate(fMid, rMid, notBefore);
            if (s == null && m == null) continue;
            AcousticDecider.Decision d = decider.add(s, m, false);
            // strongest stable combined peaks seen (wherever they are), to choose thresholds from
            if (decider.lastSideTotal != null && decider.lastSideStable >= 3 && decider.count() >= 8) {
                boolean atTruth = Math.abs(decider.lastSideTotal.lagMs - lag) <= 200;
                double[] st = atTruth ? sideTrue : sideFalse;
                st[0] = Math.max(st[0], decider.lastSideTotal.score); st[1] = Math.max(st[1], decider.lastSideTotal.peakRatio);
                if (atTruth) { sideTrueMin[0] = Math.min(sideTrueMin[0], decider.lastSideTotal.score); }
            }
            if (decider.lastMonoTotal != null && decider.lastMonoStable >= 3 && decider.count() >= 8) {
                boolean atTruth = Math.abs(decider.lastMonoTotal.lagMs - lag) <= 200;
                double[] st = atTruth ? monoTrue : monoFalse;
                st[0] = Math.max(st[0], decider.lastMonoTotal.score); st[1] = Math.max(st[1], decider.lastMonoTotal.peakRatio);
            }
            if (d == null) continue;
            if (firstDecisionAt < 0) firstDecisionAt = t;
            boolean correct = Math.abs(d.lagMs - lag) <= 200;
            if (Math.abs(d.lagMs) < 150) {
                if (Math.abs(lag) < 350) inSync++; else falseInSync++;
                decider.reset();
                continue;
            }
            if (now - lastApply < 60_000L) continue;
            lastApply = now;
            if (correct) right++; else wrong++;
            if (log.length() < 600) log.append(String.format(Locale.ROOT, " [t=%ds %s lag=%d true=%d score=%.2f ratio=%.2f n=%d %s]",
                    t / 1000, d.source, d.lagMs, lag, d.score, d.ratio, d.curves, correct ? "ok" : "WRONG"));
            // closed loop: the radio's delay changes by the measured lag; fingerprints from before no longer apply
            lag -= d.lagMs;
            decider.reset();
            notBefore = now + 4_000L;
            rMid.clear(); rSide.clear();
        }
        System.out.printf(Locale.ROOT, "corrections right=%d wrong=%d, 'in sync' checks right=%d false=%d, first decision after %d s, residual lag=%d ms%n",
                right, wrong, inSync, falseInSync, firstDecisionAt / 1000, lag);
        System.out.printf(Locale.ROOT, "  stable combined peaks, max score/ratio: side at truth %.2f/%.1f elsewhere %.2f/%.1f | mono at truth %.2f/%.1f elsewhere %.2f/%.1f%n",
                sideTrue[0], sideTrue[1], sideFalse[0], sideFalse[1], monoTrue[0], monoTrue[1], monoFalse[0], monoFalse[1]);
        if (log.length() > 0) System.out.println("  " + log.toString().trim());
    }
}
