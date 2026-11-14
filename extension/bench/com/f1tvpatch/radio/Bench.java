package com.f1tvpatch.radio;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Runs the app's acoustic estimator over real audio with a known offset and scores it. */
public class Bench {
    static short[] readWav(String path, int[] rateOut, int[] chOut) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            byte[] head = new byte[78];
            f.readFully(head);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 12, dataPos = -1, dataLen = 0;
            while (pos + 8 <= head.length) {
                String id = new String(head, pos, 4);
                int len = h.getInt(pos + 4);
                if (id.equals("fmt ")) { chOut[0] = h.getShort(pos + 10); rateOut[0] = h.getInt(pos + 12); }
                if (id.equals("data")) { dataPos = pos + 8; dataLen = len; break; }
                pos += 8 + len + (len & 1);
            }
            if (dataPos < 0) throw new IOException("no data chunk in " + path);
            long avail = f.length() - dataPos;
            int bytes = (int) Math.min(avail, (dataLen <= 0 ? avail : (dataLen & 0xFFFFFFFFL)));
            byte[] raw = new byte[bytes];
            f.seek(dataPos);
            f.readFully(raw);
            short[] out = new short[bytes / 2];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
            return out;
        }
    }

    /** mode: 0 = mid (L+R)/2 as the app does now, 1 = side (L-R)/2. Returns mono. */
    static short[] mono(short[] interleaved, int ch, int mode) {
        if (ch == 1) return interleaved;
        short[] out = new short[interleaved.length / ch];
        for (int i = 0; i < out.length; i++) {
            int l = interleaved[i * ch], r = interleaved[i * ch + 1];
            out[i] = (short) (mode == 0 ? (l + r) / 2 : (l - r) / 2);
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        String f1Path = a[0], radioPath = a[1];
        int f1Mode = Integer.parseInt(a[2]), radioMode = Integer.parseInt(a[3]);
        long trueLag = Long.parseLong(a[4]);            // radio heard this much later than F1
        int[] r1 = new int[1], c1 = new int[1], r2 = new int[1], c2 = new int[1];
        short[] f1 = mono(readWav(f1Path, r1, c1), c1[0], f1Mode);
        short[] radio = mono(readWav(radioPath, r2, c2), c2[0], radioMode);
        double durMs = Math.min(f1.length * 1000.0 / r1[0], radio.length * 1000.0 / r2[0]);

        AcousticFeatures F = new AcousticFeatures(), R = new AcousticFeatures();
        long base = 1_000_000L;                          // sim clock origin
        int chunkMs = 20;
        int f1Chunk = r1[0] * chunkMs / 1000, rChunk = r2[0] * chunkMs / 1000;
        List<long[]> rows = new ArrayList<>();           // lagMs, score*1000, ratio*100
        List<double[]> curves = new ArrayList<>();
        long nextEst = base + 60_000L;
        for (long t = 0; t + chunkMs <= durMs; t += chunkMs) {
            int i1 = (int) (t / chunkMs) * f1Chunk, i2 = (int) (t / chunkMs) * rChunk;
            short[] b1 = Arrays.copyOfRange(f1, i1, i1 + f1Chunk);
            short[] b2 = Arrays.copyOfRange(radio, i2, i2 + rChunk);
            F.addPcm16(b1, b1.length, r1[0], 1, base + t);
            R.addPcm16(b2, b2.length, r2[0], 1, base + t + trueLag);
            long now = base + t;
            if (now >= nextEst) {
                nextEst += 5_000L;
                android.os.SystemClock.nowMs = now;
                AcousticCorrelator.Estimate e = AcousticCorrelator.estimate(F, R, 0);
                if (e != null) { rows.add(new long[]{e.lagMs, Math.round(e.score * 1000), Math.round(e.peakRatio * 100)}); curves.add(e.correlation); }
            }
        }
        // single estimates
        int n = rows.size(), ok = 0, confOk = 0, confWrong = 0;
        for (long[] r : rows) {
            boolean right = Math.abs(r[0] - trueLag) <= 200;
            if (right) ok++;
            boolean conf = r[1] >= 150 && r[2] >= 140 && Math.abs(r[0]) <= 8500;
            if (conf && right) confOk++; else if (conf) confWrong++;
        }
        System.out.printf(Locale.ROOT, "single: n=%d right=%.0f%%  passing 0.15/1.4: right=%d wrong=%d%n", n, 100.0 * ok / Math.max(1, n), confOk, confWrong);
        // the app's integration rule (decay 0.93, count>=8, score>=0.10, ratio>=1.6 / 2.2 far)
        AcousticCorrelator.Accumulator acc = new AcousticCorrelator.Accumulator();
        int decisionsRight = 0, decisionsWrong = 0;
        StringBuilder wrong = new StringBuilder();
        for (int k = 0; k < n; k++) {
            acc.add(new AcousticCorrelator.Estimate(rows.get(k)[0], rows.get(k)[1] / 1000.0, rows.get(k)[2] / 100.0, curves.get(k)));
            AcousticCorrelator.Estimate tot = acc.result();
            double minRatio = Math.abs(tot.lagMs) > 5000 ? 2.2 : 1.6;
            boolean conf = acc.count >= 8 && tot.score >= 0.10 && tot.peakRatio >= minRatio && Math.abs(tot.lagMs) <= 8500;
            if (conf) {
                if (Math.abs(tot.lagMs - trueLag) <= 200) decisionsRight++;
                else { decisionsWrong++; if (wrong.length() < 200) wrong.append(String.format(Locale.ROOT, " [lag=%d s=%.2f r=%.2f]", tot.lagMs, tot.score, tot.peakRatio)); }
                acc.reset();
            }
        }
        System.out.printf(Locale.ROOT, "integrated rule: decisions right=%d wrong=%d%s%n", decisionsRight, decisionsWrong, wrong);
        // score/ratio distribution of right vs wrong single estimates
        double sr = 0, sw = 0, rr = 0, rw = 0; int nr = 0, nw = 0;
        for (long[] r : rows) { if (Math.abs(r[0] - trueLag) <= 200) { sr += r[1]; rr += r[2]; nr++; } else { sw += r[1]; rw += r[2]; nw++; } }
        long maxWs = 0, maxWr = 0, minRs = 9999, minRr = 9999; int strongWrong = 0, strongRight = 0;
        for (long[] r : rows) { boolean rt = Math.abs(r[0] - trueLag) <= 200; boolean strong = r[1] >= 250 && r[2] >= 250;
            if (rt) { minRs = Math.min(minRs, r[1]); minRr = Math.min(minRr, r[2]); if (strong) strongRight++; } else { maxWs = Math.max(maxWs, r[1]); maxWr = Math.max(maxWr, r[2]); if (strong) strongWrong++; } }
        System.out.printf(Locale.ROOT, "wrong: max score %.3f max ratio %.2f | right: min score %.3f min ratio %.2f | strong(0.25/2.5): right=%d wrong=%d%n", maxWs / 1000.0, maxWr / 100.0, minRs / 1000.0, minRr / 100.0, strongRight, strongWrong);
        System.out.printf(Locale.ROOT, "mean score/ratio: right %.3f/%.2f (n=%d)  wrong %.3f/%.2f (n=%d)%n",
                nr > 0 ? sr / nr / 1000 : 0, nr > 0 ? rr / nr / 100 : 0, nr, nw > 0 ? sw / nw / 1000 : 0, nw > 0 ? rw / nw / 100 : 0, nw);
    }
}
