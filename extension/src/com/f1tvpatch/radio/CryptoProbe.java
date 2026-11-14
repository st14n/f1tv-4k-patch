package com.f1tvpatch.radio;

import android.media.MediaCodec;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * Diagnostic builds only (F1TV_CRYPTO_PROBE=1): logs how the official player hands protected samples
 * to the decoder (the sample layout MediaCodec gets: clear/protected byte counts, IV, pattern, flags
 * and the clear bytes the sample starts with), then passes the call on unchanged. No keys or decrypted
 * content are involved: these are the parameters every player passes to {@code queueSecureInputBuffer}.
 * Used to compare, on the same TV and the same stream, with what the Media3 player in the other app
 * passes, where UHD HEVC decodes to a green picture.
 */
public final class CryptoProbe {
    private CryptoProbe() { }

    private static final String TAG = "F1CryptoProbe";
    private static final IdentityHashMap<MediaCodec, int[]> COUNTS = new IdentityHashMap<>();

    public static void queueSecureInputBuffer(MediaCodec codec, int index, int offset, MediaCodec.CryptoInfo info,
                                              long presentationTimeUs, int flags) {
        try {
            log(codec, index, offset, info, presentationTimeUs, flags);
        } catch (Throwable t) {
            Log.w(TAG, "probe failed", t);
        }
        codec.queueSecureInputBuffer(index, offset, info, presentationTimeUs, flags);
    }

    private static synchronized void log(MediaCodec codec, int index, int offset, MediaCodec.CryptoInfo info,
                                         long presentationTimeUs, int flags) {
        int[] count = COUNTS.get(codec);
        if (count == null) {
            if (COUNTS.size() > 16) COUNTS.clear();
            count = new int[1];
            COUNTS.put(codec, count);
        }
        int n = count[0]++;
        // The start of every decoder in full, then a sample now and then
        if (n >= 40 && n % 500 != 0) return;

        int total = 0;
        for (int k = 0; k < info.numSubSamples; k++) {
            total += info.numBytesOfClearData == null ? 0 : info.numBytesOfClearData[k];
            total += info.numBytesOfEncryptedData == null ? 0 : info.numBytesOfEncryptedData[k];
        }
        String pattern = "?";
        try {
            Object p = MediaCodec.CryptoInfo.class.getMethod("getPattern").invoke(info);
            if (p != null) {
                pattern = p.getClass().getMethod("getEncryptBlocks").invoke(p) + ":" + p.getClass().getMethod("getSkipBlocks").invoke(p);
            }
        } catch (Throwable ignored) {
            // before Android 12 the pattern cannot be read back
        }
        String head = "";
        try {
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer != null) {
                ByteBuffer view = buffer.duplicate();
                view.position(offset);
                byte[] first = new byte[Math.min(64, Math.max(0, Math.min(total, view.remaining())))];
                view.get(first);
                head = hex(first);
            }
        } catch (Throwable ignored) {
            // the input buffer is not always readable; the layout is what matters
        }
        Log.i(TAG, "codec=" + codec.getName() + " #" + n + " pts=" + presentationTimeUs + " flags=" + flags
                + " offset=" + offset + " bytes=" + total + " mode=" + info.mode + " pattern=" + pattern
                + " subsamples=" + info.numSubSamples
                + " clear=" + Arrays.toString(info.numBytesOfClearData)
                + " protected=" + Arrays.toString(info.numBytesOfEncryptedData)
                + " iv=" + hex(info.iv) + " kid=" + hex(info.key) + " head=" + head);
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
