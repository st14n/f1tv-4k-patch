package com.f1tvpatch.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Compact loudness fingerprint of an audio stream: the energy in a few bands above most of the
 * speech energy, every 20 ms, stamped with the elapsed-realtime moment it is (or would be) heard.
 * The F1 audio and the radio both carry the circuit sound, so their fingerprints line up at the
 * offset between them, whatever the commentary on top.
 */
final class AcousticFeatures {
    static final long HOP_MS = 20L;
    static final int BAND_COUNT = 3;
    private static final int FFT_SIZE = 1024;
    // Engine/crowd sound carries well above the bulk of commentary energy
    private static final double[][] BANDS_HZ = {{1_000, 2_000}, {2_000, 4_000}, {4_000, 8_000}};
    private static final long MAX_HISTORY_MS = 70_000L;
    // A gap bigger than this between consecutive chunks (pause, seek, stall) starts a new run
    private static final double DISCONTINUITY_MS = 300.0;

    static final class Frame {
        final double timeMs;
        final float[] bands;

        Frame(double timeMs, float[] bands) {
            this.timeMs = timeMs;
            this.bands = bands;
        }
    }

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    // Producer-thread state
    private int sampleRate;
    private int hopSamples;
    private final double[] window = new double[FFT_SIZE];
    private int windowPos;
    private int windowFill;
    private int samplesSinceHop;
    private double nextSampleAtMs = Double.NaN;
    private final double[] re = new double[FFT_SIZE];
    private final double[] im = new double[FFT_SIZE];
    private final double[] hann = new double[FFT_SIZE];

    AcousticFeatures() {
        for (int i = 0; i < FFT_SIZE; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
    }

    /** Interleaved 16-bit PCM in {@code data}'s remaining bytes; the first frame is heard at {@code heardAtMs}. */
    void addPcm16(ByteBuffer data, int sampleRate, int channels, double heardAtMs) {
        if (channels <= 0) return;
        ByteBuffer pcm = data.duplicate().order(ByteOrder.nativeOrder());
        int frameCount = pcm.remaining() / (2 * channels);
        prepare(sampleRate, heardAtMs);
        for (int i = 0; i < frameCount; i++) {
            double mono = 0;
            for (int c = 0; c < channels; c++) mono += pcm.getShort() / 32768.0;
            push(mono / channels);
        }
    }

    /** Interleaved float PCM in {@code data}'s remaining bytes. */
    void addPcmFloat(ByteBuffer data, int sampleRate, int channels, double heardAtMs) {
        if (channels <= 0) return;
        ByteBuffer pcm = data.duplicate().order(ByteOrder.nativeOrder());
        int frameCount = pcm.remaining() / (4 * channels);
        prepare(sampleRate, heardAtMs);
        for (int i = 0; i < frameCount; i++) {
            double mono = 0;
            for (int c = 0; c < channels; c++) mono += pcm.getFloat();
            push(mono / channels);
        }
    }

    void addPcmFloat(float[] data, int offset, int length, int sampleRate, int channels, double heardAtMs) {
        if (channels <= 0) return;
        prepare(sampleRate, heardAtMs);
        for (int i = offset; i + channels <= offset + length; i += channels) {
            double mono = 0;
            for (int c = 0; c < channels; c++) mono += data[i + c];
            push(mono / channels);
        }
    }

    void addPcm16(short[] data, int length, int sampleRate, int channels, double heardAtMs) {
        if (channels <= 0) return;
        prepare(sampleRate, heardAtMs);
        for (int i = 0; i + channels <= length; i += channels) {
            double mono = 0;
            for (int c = 0; c < channels; c++) mono += data[i + c] / 32768.0;
            push(mono / channels);
        }
    }

    private void prepare(int rate, double heardAtMs) {
        if (rate != sampleRate || Double.isNaN(nextSampleAtMs) || Math.abs(heardAtMs - nextSampleAtMs) > DISCONTINUITY_MS) {
            sampleRate = rate;
            hopSamples = (int) (rate * HOP_MS / 1000L);
            windowFill = 0;
            windowPos = 0;
            samplesSinceHop = 0;
        }
        nextSampleAtMs = heardAtMs;
    }

    private void push(double sample) {
        window[windowPos] = sample;
        windowPos = (windowPos + 1) % FFT_SIZE;
        if (windowFill < FFT_SIZE) windowFill++;
        nextSampleAtMs += 1000.0 / sampleRate;
        if (++samplesSinceHop >= hopSamples && windowFill == FFT_SIZE) {
            samplesSinceHop = 0;
            add(new Frame(nextSampleAtMs, bandEnergies()));
        }
    }

    private float[] bandEnergies() {
        for (int i = 0; i < FFT_SIZE; i++) {
            re[i] = window[(windowPos + i) % FFT_SIZE] * hann[i];
            im[i] = 0;
        }
        fft(re, im);
        double binHz = (double) sampleRate / FFT_SIZE;
        float[] out = new float[BAND_COUNT];
        for (int b = 0; b < BAND_COUNT; b++) {
            double energy = 0;
            int last = Math.min((int) (BANDS_HZ[b][1] / binHz), FFT_SIZE / 2);
            for (int bin = Math.max(1, (int) (BANDS_HZ[b][0] / binHz)); bin <= last; bin++) {
                energy += re[bin] * re[bin] + im[bin] * im[bin];
            }
            out[b] = (float) Math.log(energy + 1e-9);
        }
        return out;
    }

    private synchronized void add(Frame frame) {
        frames.addLast(frame);
        while (!frames.isEmpty() && frame.timeMs - frames.peekFirst().timeMs > MAX_HISTORY_MS) {
            frames.removeFirst();
        }
    }

    synchronized void clear() {
        frames.clear();
    }

    /** Band energies on a {@link #HOP_MS} grid starting at {@code startMs}; null where there's no data. */
    synchronized float[][] gridded(double startMs, int count) {
        float[][] out = new float[count][];
        Iterator<Frame> it = frames.iterator();
        if (!it.hasNext()) return out;
        Frame current = it.next();
        Frame next = it.hasNext() ? it.next() : null;
        for (int i = 0; i < count; i++) {
            double t = startMs + i * HOP_MS;
            while (next != null && next.timeMs <= t) {
                current = next;
                next = it.hasNext() ? it.next() : null;
            }
            if (Math.abs(current.timeMs - t) <= HOP_MS * 1.5) out[i] = current.bands;
        }
        return out;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            for (int start = 0; start < n; start += len) {
                double curRe = 1;
                double curIm = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = start + k;
                    int b = a + len / 2;
                    double bRe = re[b] * curRe - im[b] * curIm;
                    double bIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - bRe;
                    im[b] = im[a] - bIm;
                    re[a] += bRe;
                    im[a] += bIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
