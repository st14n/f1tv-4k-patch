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
 * ClearVR mutes through the track volume, so the audio keeps flowing through here while the
 * radio plays. The tap never affects what is written.
 */
public final class AudioTap {
    private AudioTap() { }

    static final AcousticFeatures F1_AUDIO = new AcousticFeatures();

    private static AudioTrack lastTrack;
    private static long framesWritten;
    private static final AudioTimestamp TIMESTAMP = new AudioTimestamp();

    public static int write(AudioTrack track, ByteBuffer data, int size, int mode) {
        try {
            if (wanted(track)) {
                ByteBuffer slice = data.duplicate();
                slice.limit(Math.min(slice.limit(), slice.position() + size));
                if (track.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT) {
                    F1_AUDIO.addPcmFloat(slice, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
                } else if (track.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) {
                    F1_AUDIO.addPcm16(slice, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
                }
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        return counted(track, track.write(data, size, mode), bytesPerSample(track));
    }

    public static int write(AudioTrack track, byte[] data, int offset, int size) {
        try {
            if (wanted(track) && track.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) {
                ByteBuffer slice = ByteBuffer.wrap(data, offset, size).order(ByteOrder.nativeOrder());
                F1_AUDIO.addPcm16(slice, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        return counted(track, track.write(data, offset, size), bytesPerSample(track));
    }

    public static int write(AudioTrack track, short[] data, int offset, int size) {
        try {
            if (wanted(track)) {
                short[] copy = new short[size];
                System.arraycopy(data, offset, copy, 0, size);
                F1_AUDIO.addPcm16(copy, size, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        return counted(track, track.write(data, offset, size), 1);
    }

    public static int write(AudioTrack track, float[] data, int offset, int size, int mode) {
        try {
            if (wanted(track)) {
                F1_AUDIO.addPcmFloat(data, offset, size, track.getSampleRate(), track.getChannelCount(), heardAtMs(track));
            }
        } catch (Throwable ignored) {
            // Never let the tap break playback
        }
        return counted(track, track.write(data, offset, size, mode), 1);
    }

    private static boolean wanted(AudioTrack track) {
        if (!RadioPatch.isAcousticTapWanted()) return false;
        if (track != lastTrack) {
            lastTrack = track;
            framesWritten = 0L;
        }
        return true;
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
