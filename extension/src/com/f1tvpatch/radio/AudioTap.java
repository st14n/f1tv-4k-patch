package com.f1tvpatch.radio;

import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Smali hook for ClearVR's audio output: every {@code AudioTrack.write(...)} call in ClearVR's
 * classes is redirected here (patch.sh finds them by signature, whatever the class is called in a
 * given F1TV version), so the decoded F1 audio can be fingerprinted for acoustic radio sync.
 *
 * The tap is also where the F1 audio is silenced while the radio carries the commentary: the samples
 * are fingerprinted as decoded and then silence is written to the track in their place. That way the
 * fingerprint never depends on how ClearVR itself implements "mute" (track volume, or zeroed samples,
 * which would leave nothing to compare the radio with).
 */
public final class AudioTap {
    private AudioTap() { }

    static final AcousticFeatures F1_AUDIO = new AcousticFeatures();
    /** Writes seen while the tap was wanted (for the sync status log: 0 = the hook gets no audio). */
    static volatile long writes;
    /** Elapsed-realtime of the last write of any kind: tells whether the hook is alive at all. */
    static volatile long lastWriteAtMs;
    /** Write silence instead of the F1 audio (set by {@link RadioPatch} while the radio carries). */
    static volatile boolean silence;
    /** Smoothed level of the decoded F1 audio (mean |sample|, 0..1), for the status log. */
    static volatile float level;

    private static AudioTrack lastTrack;
    private static long framesWritten;
    private static final AudioTimestamp TIMESTAMP = new AudioTimestamp();
    private static ByteBuffer zeroBytes = ByteBuffer.allocateDirect(0);
    private static short[] zeroShorts = new short[0];
    private static float[] zeroFloats = new float[0];
    private static byte[] zeroByteArray = new byte[0];

    /** True when the hook saw audio recently, i.e. {@link #silence} really silences F1TV. */
    static boolean isAlive() {
        return SystemClock.elapsedRealtime() - lastWriteAtMs < 2_000L;
    }

    public static int write(AudioTrack track, ByteBuffer data, int size, int mode) {
        boolean mute = false;
        try {
            seen(track);
            mute = silence;
            if (wanted()) {
                ByteBuffer slice = data.duplicate();
                slice.limit(Math.min(slice.limit(), slice.position() + size));
                if (track.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT) {
                    measureFloat(slice);
                    F1_AUDIO.addPcmFloat(slice, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
                } else if (track.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) {
                    measure16(slice);
                    F1_AUDIO.addPcm16(slice, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
                }
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        if (mute) {
            try {
                int count = Math.min(size, data.remaining());
                if (zeroBytes.capacity() < count) zeroBytes = ByteBuffer.allocateDirect(count);
                zeroBytes.clear();
                zeroBytes.limit(count);
                int written = track.write(zeroBytes, count, mode);
                // As AudioTrack.write does: the caller's buffer moves on by what was accepted
                if (written > 0) data.position(data.position() + written);
                return counted(track, written, bytesPerSample(track));
            } catch (Throwable t) {
                // fall through to the normal write
            }
        }
        return counted(track, track.write(data, size, mode), bytesPerSample(track));
    }

    public static int write(AudioTrack track, byte[] data, int offset, int size) {
        boolean mute = false;
        try {
            seen(track);
            mute = silence;
            if (wanted() && track.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) {
                ByteBuffer slice = ByteBuffer.wrap(data, offset, size).order(ByteOrder.nativeOrder());
                measure16(slice);
                F1_AUDIO.addPcm16(slice, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        if (mute) {
            if (zeroByteArray.length < size) zeroByteArray = new byte[size];
            return counted(track, track.write(zeroByteArray, 0, size), bytesPerSample(track));
        }
        return counted(track, track.write(data, offset, size), bytesPerSample(track));
    }

    public static int write(AudioTrack track, short[] data, int offset, int size) {
        boolean mute = false;
        try {
            seen(track);
            mute = silence;
            if (wanted()) {
                short[] copy = new short[size];
                System.arraycopy(data, offset, copy, 0, size);
                F1_AUDIO.addPcm16(copy, size, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        if (mute) {
            if (zeroShorts.length < size) zeroShorts = new short[size];
            return counted(track, track.write(zeroShorts, 0, size), 1);
        }
        return counted(track, track.write(data, offset, size), 1);
    }

    public static int write(AudioTrack track, float[] data, int offset, int size, int mode) {
        boolean mute = false;
        try {
            seen(track);
            mute = silence;
            if (wanted()) {
                F1_AUDIO.addPcmFloat(data, offset, size, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        if (mute) {
            if (zeroFloats.length < size) zeroFloats = new float[size];
            return counted(track, track.write(zeroFloats, 0, size, mode), 1);
        }
        return counted(track, track.write(data, offset, size, mode), 1);
    }

    /**
     * Every write counts, wanted or not: {@link #heardAtMs} needs the number of frames written to
     * this track since it was created. Counting only while the fingerprint was wanted stamped the
     * F1 audio too early by however long the tap had been off (radio switched on mid-session).
     */
    private static void seen(AudioTrack track) {
        lastWriteAtMs = SystemClock.elapsedRealtime();
        if (track != lastTrack) {
            lastTrack = track;
            framesWritten = 0L;
        }
    }

    private static boolean wanted() {
        if (!RadioPatch.isAcousticTapWanted()) return false;
        writes++;
        return true;
    }

    private static void measure16(ByteBuffer pcm) {
        ByteBuffer b = pcm.duplicate().order(ByteOrder.nativeOrder());
        int n = Math.min(b.remaining() / 2, 256);
        if (n == 0) return;
        long sum = 0;
        for (int i = 0; i < n; i++) sum += Math.abs(b.getShort());
        level = level * 0.95f + 0.05f * (sum / (float) n / 32768f);
    }

    private static void measureFloat(ByteBuffer pcm) {
        ByteBuffer b = pcm.duplicate().order(ByteOrder.nativeOrder());
        int n = Math.min(b.remaining() / 4, 256);
        if (n == 0) return;
        float sum = 0;
        for (int i = 0; i < n; i++) sum += Math.abs(b.getFloat());
        level = level * 0.95f + 0.05f * (sum / n);
    }

    /** Keeps the frame count for {@link #heardAtMs}; {@code unit} = bytes per sample (1 for array writes counted in samples). */
    private static int counted(AudioTrack track, int written, int unit) {
        if (written > 0 && track == lastTrack) {
            framesWritten += written / ((long) unit * Math.max(1, track.getChannelCount()));
        }
        return written;
    }

    private static int bytesPerSample(AudioTrack track) {
        return track.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
    }

    /** Elapsed-realtime (ms) at which the next frame written to {@code track} will be heard. */
    private static double heardAtMs(AudioTrack track) {
        double nowMs = SystemClock.elapsedRealtimeNanos() / 1e6;
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) return nowMs;
        if (track.getTimestamp(TIMESTAMP)) {
            double monotonicToElapsedMs = (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1e6;
            return TIMESTAMP.nanoTime / 1e6 + monotonicToElapsedMs
                    + (framesWritten - TIMESTAMP.framePosition) * 1000.0 / track.getSampleRate();
        }
        long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        return nowMs + (framesWritten - head) * 1000.0 / track.getSampleRate();
    }
}
