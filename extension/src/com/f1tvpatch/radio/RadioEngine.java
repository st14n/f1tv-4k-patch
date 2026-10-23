package com.f1tvpatch.radio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * Plays an Icecast/SHOUTcast-style MP3 or ADTS-AAC radio stream with a controllable delay.
 *
 * Decoded PCM goes into a ring buffer. Every frame is mapped to the local time it would have
 * arrived at if the stream had no initial burst or network jitter ("live arrival time"), using
 * the lower envelope of (arrival time - media time). Playout then picks the frame whose live
 * arrival time is exactly {@code targetDelayMs} before the moment it will be heard, so the delay
 * can be changed at any time without reconnecting.
 */
final class RadioEngine {
    static final String TAG = "F1Radio";
    /** Fingerprint of the radio audio as it is heard, for acoustic sync against the F1 audio. */
    static final AcousticFeatures RADIO_AUDIO = new AcousticFeatures();

    enum State { CONNECTING, BUFFERING, PLAYING, VIDEO_AHEAD, BUFFER_EXCEEDED, PAUSED, ERROR }

    private static final int MAX_BUFFER_SECONDS = 150;
    private static final int CHUNK_MS = 20;
    private static final double HARD_JUMP_SECONDS = 0.25;
    private static final double DEADBAND_SECONDS = 0.02;
    private static final double MAX_SPEED_CORRECTION = 0.01;

    private final String url;
    private volatile boolean running;
    private Thread networkThread;
    private Thread playoutThread;
    private volatile PcmRing ring;

    private volatile long targetDelayMs = -1L;
    private volatile boolean playRequested;

    private volatile State state = State.CONNECTING;
    private volatile long bufferingRemainingMs;
    private volatile long audibleSinceMs;
    private volatile String lastError;
    private volatile String formatDescription = "";

    RadioEngine(String url) {
        this.url = url;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        networkThread = new Thread(this::networkLoop, "f1radio-net");
        networkThread.setDaemon(true);
        networkThread.start();
        playoutThread = new Thread(this::playoutLoop, "f1radio-playout");
        playoutThread.setDaemon(true);
        playoutThread.start();
        Log.i(TAG, "engine started url=" + url);
    }

    synchronized void release() {
        if (!running) return;
        running = false;
        if (networkThread != null) networkThread.interrupt();
        if (playoutThread != null) playoutThread.interrupt();
        networkThread = null;
        playoutThread = null;
        ring = null;
        Log.i(TAG, "engine released");
    }

    /** Delay between a radio frame's live arrival and when it is heard. Negative = no target yet. */
    void setTargetDelayMs(long delayMs) {
        targetDelayMs = delayMs;
    }

    void setPlaying(boolean playing) {
        playRequested = playing;
    }

    State getState() {
        return state;
    }

    long getBufferingRemainingMs() {
        return bufferingRemainingMs;
    }

    /** Elapsed-realtime since which the radio has been continuously audible and in sync, or 0. */
    long getAudibleSinceMs() {
        return audibleSinceMs;
    }

    String getLastError() {
        return lastError;
    }

    String getFormatDescription() {
        return formatDescription;
    }

    // ── Network + decode ─────────────────────────────────────────────────────

    private void networkLoop() {
        long backoffMs = 1_000L;
        while (running) {
            try {
                state = ring == null ? State.CONNECTING : state;
                streamOnce();
                backoffMs = 1_000L;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                if (!running) return;
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.w(TAG, "stream failed, retrying in " + backoffMs + "ms", t);
            }
            PcmRing current = ring;
            if (current != null) current.markDiscontinuity();
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 10_000L);
        }
    }

    private void streamOnce() throws IOException, InterruptedException {
        HttpURLConnection conn = open(url);
        MediaCodec decoder = null;
        try {
            String contentType = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase(Locale.ROOT);
            if (contentType.contains("mpegurl") || url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
                throw new IOException("HLS playlists are not supported; use a direct Icecast/SHOUTcast MP3 or AAC URL");
            }
            int metaInt = conn.getHeaderFieldInt("icy-metaint", 0);
            InputStream in = new BufferedInputStream(conn.getInputStream(), 32 * 1024);
            if (metaInt > 0) in = new IcyStrippingInputStream(in, metaInt);

            FrameReader reader = new FrameReader(in, FrameReader.codecFromContentType(contentType));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long ptsUs = 0L;
            lastError = null;
            Log.i(TAG, "connected contentType=" + contentType + " icy-metaint=" + metaInt);

            while (running) {
                if (Thread.interrupted()) throw new InterruptedException();
                FrameReader.Frame frame = reader.next();
                if (decoder == null) {
                    decoder = createDecoder(frame);
                    formatDescription = frame.describe();
                    Log.i(TAG, "decoder configured for " + formatDescription);
                }
                queueInput(decoder, frame, ptsUs, info);
                ptsUs += frame.samplesPerFrame * 1_000_000L / frame.sampleRate;
                drainOutput(decoder, info, frame);
            }
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Throwable ignored) { }
                try { decoder.release(); } catch (Throwable ignored) { }
            }
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String startUrl) throws IOException {
        String current = startUrl;
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) F1TV-Radio-Patch");
            conn.setRequestProperty("Icy-MetaData", "0");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("redirect without Location");
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new IOException("HTTP " + code);
            }
            return conn;
        }
        throw new IOException("too many redirects");
    }

    private static MediaCodec createDecoder(FrameReader.Frame frame) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(frame.mimeType, frame.sampleRate, frame.channels);
        if (frame.codecSpecificData != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(frame.codecSpecificData));
        }
        MediaCodec decoder = MediaCodec.createDecoderByType(frame.mimeType);
        decoder.configure(format, null, null, 0);
        decoder.start();
        return decoder;
    }

    private void queueInput(MediaCodec decoder, FrameReader.Frame frame, long ptsUs, MediaCodec.BufferInfo info)
            throws InterruptedException {
        while (running) {
            int index = decoder.dequeueInputBuffer(10_000);
            if (index >= 0) {
                ByteBuffer buffer = decoder.getInputBuffer(index);
                buffer.clear();
                buffer.put(frame.payload, 0, frame.payloadLength);
                decoder.queueInputBuffer(index, 0, frame.payloadLength, ptsUs, 0);
                return;
            }
            drainOutput(decoder, info, frame);
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    private void drainOutput(MediaCodec decoder, MediaCodec.BufferInfo info, FrameReader.Frame inputFrame) {
        while (true) {
            int index = decoder.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out = decoder.getOutputFormat();
                ensureRing(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                continue;
            }
            if (index < 0) return;
            if (info.size > 0) {
                PcmRing target = ensureRing(-1, -1);
                if (target == null) target = ensureRing(inputFrame.sampleRate, inputFrame.channels);
                ByteBuffer buffer = decoder.getOutputBuffer(index);
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                ShortBuffer samples = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                target.write(samples, SystemClock.elapsedRealtime());
            }
            decoder.releaseOutputBuffer(index, false);
        }
    }

    /** Returns the current ring, (re)creating it if a different output format is given. */
    private synchronized PcmRing ensureRing(int sampleRate, int channels) {
        PcmRing current = ring;
        if (sampleRate <= 0) return current;
        if (current != null && current.sampleRate == sampleRate && current.channels == channels) return current;
        if (channels < 1 || channels > 2) throw new IllegalStateException("unsupported channel count " + channels);
        ring = new PcmRing(sampleRate, channels, MAX_BUFFER_SECONDS);
        Log.i(TAG, "pcm ring " + sampleRate + "Hz x" + channels + " (" + MAX_BUFFER_SECONDS + "s)");
        return ring;
    }

    // ── Playout ──────────────────────────────────────────────────────────────

    private void playoutLoop() {
        AudioTrack track = null;
        PcmRing trackRing = null;
        short[] chunk = null;
        long framesWritten = 0L;
        boolean trackPlaying = false;
        double readPos = Double.NaN;
        AudioTimestamp timestamp = new AudioTimestamp();

        try {
            while (running) {
                PcmRing source = ring;
                long delayMs = targetDelayMs;
                if (source == null || delayMs < 0L || !playRequested) {
                    if (trackPlaying) {
                        track.pause();
                        track.flush();
                        trackPlaying = false;
                        framesWritten = 0L;
                    }
                    readPos = Double.NaN;
                    audibleSinceMs = 0L;
                    if (source != null && !playRequested) state = State.PAUSED;
                    Thread.sleep(30);
                    continue;
                }

                if (source != trackRing) {
                    if (track != null) track.release();
                    track = createTrack(source.sampleRate, source.channels);
                    trackRing = source;
                    chunk = new short[source.sampleRate * CHUNK_MS / 1000 * source.channels];
                    framesWritten = 0L;
                    trackPlaying = false;
                    readPos = Double.NaN;
                }

                int sampleRate = source.sampleRate;
                int chunkFrames = chunk.length / source.channels;
                double heardAtMs = estimateHeardAtMs(track, timestamp, framesWritten, sampleRate, trackPlaying);
                double wantedPos = source.frameForLiveTime(heardAtMs - delayMs);

                if (Double.isNaN(wantedPos)) {
                    readPos = Double.NaN;
                } else if (Double.isNaN(readPos) || Math.abs(readPos - wantedPos) > HARD_JUMP_SECONDS * sampleRate) {
                    readPos = wantedPos;
                }

                double speed = 1.0;
                if (!Double.isNaN(readPos)) {
                    double errorSeconds = (readPos - wantedPos) / sampleRate;
                    if (Math.abs(errorSeconds) > DEADBAND_SECONDS) {
                        // Ahead → play slower, behind → faster; converge over ~3 s.
                        speed = 1.0 - clamp(errorSeconds / 3.0, -MAX_SPEED_CORRECTION, MAX_SPEED_CORRECTION);
                    }
                }

                int audible = Double.isNaN(readPos) ? 0 : source.read(readPos, speed, chunk, chunkFrames);
                if (audible < 0) {
                    // Wanted audio is older than the ring holds (e.g. a long pause or big timeshift).
                    state = State.BUFFER_EXCEEDED;
                } else if (audible == 0) {
                    long newest = source.newestFrame();
                    if (!Double.isNaN(readPos) && readPos >= newest) {
                        state = State.VIDEO_AHEAD;
                    } else {
                        state = State.BUFFERING;
                        bufferingRemainingMs = Double.isNaN(wantedPos)
                                ? delayMs
                                : (long) Math.max(0, (source.oldestFrame() - wantedPos) * 1000.0 / sampleRate);
                    }
                } else {
                    state = State.PLAYING;
                }
                if (audible > 0 && audible == chunkFrames) {
                    if (audibleSinceMs == 0L) audibleSinceMs = SystemClock.elapsedRealtime();
                } else {
                    audibleSinceMs = 0L;
                }
                if (!Double.isNaN(readPos)) readPos += chunkFrames * speed;

                if (!trackPlaying) {
                    track.play();
                    trackPlaying = true;
                }
                if (audible == chunkFrames && RadioPatch.isAcousticTapWanted()) {
                    RADIO_AUDIO.addPcm16(chunk, chunk.length, sampleRate, source.channels, heardAtMs);
                }
                int written = track.write(chunk, 0, chunk.length);
                if (written > 0) framesWritten += written / source.channels;
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            state = State.ERROR;
            Log.e(TAG, "playout failed", t);
        } finally {
            audibleSinceMs = 0L;
            if (track != null) {
                try { track.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private static AudioTrack createTrack(int sampleRate, int channels) {
        int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuffer * 2, sampleRate * channels * 2 / 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** Elapsed-realtime (ms) at which the next frame written to {@code track} will be heard. */
    private static double estimateHeardAtMs(AudioTrack track, AudioTimestamp ts, long framesWritten,
                                            int sampleRate, boolean trackPlaying) {
        double nowMs = SystemClock.elapsedRealtimeNanos() / 1e6;
        if (!trackPlaying) return nowMs;
        if (track.getTimestamp(ts)) {
            double monotonicToElapsedMs = (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1e6;
            double presentedAtMs = ts.nanoTime / 1e6 + monotonicToElapsedMs;
            return presentedAtMs + (framesWritten - ts.framePosition) * 1000.0 / sampleRate;
        }
        long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        return nowMs + (framesWritten - head) * 1000.0 / sampleRate;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── PCM ring ─────────────────────────────────────────────────────────────

    static final class PcmRing {
        /** How fast the arrival envelope may drift up (ms per ms) to follow encoder/device clock skew. */
        private static final double ENVELOPE_CREEP = 0.002;

        final int sampleRate;
        final int channels;
        private final int capacityFrames;
        private final ShortBuffer data;
        private long writtenFrames;
        private double liveOffsetMs = Double.NaN;
        private long lastArrivalMs;
        private boolean discontinuity;

        PcmRing(int sampleRate, int channels, int seconds) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.capacityFrames = sampleRate * seconds;
            this.data = ByteBuffer.allocateDirect(capacityFrames * channels * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();
        }

        synchronized void write(ShortBuffer samples, long arrivalMs) {
            int frames = samples.remaining() / channels;
            for (int i = 0; i < frames * channels; i++) {
                int slot = (int) (((writtenFrames * channels) + i) % ((long) capacityFrames * channels));
                data.put(slot, samples.get());
            }
            writtenFrames += frames;

            double offset = arrivalMs - writtenFrames * 1000.0 / sampleRate;
            if (Double.isNaN(liveOffsetMs) || discontinuity) {
                liveOffsetMs = offset;
                discontinuity = false;
            } else {
                liveOffsetMs += (arrivalMs - lastArrivalMs) * ENVELOPE_CREEP;
                liveOffsetMs = Math.min(liveOffsetMs, offset);
            }
            lastArrivalMs = arrivalMs;
        }

        /** After a reconnect the stream restarts with a fresh burst; re-anchor the live mapping. */
        synchronized void markDiscontinuity() {
            discontinuity = true;
        }

        /** Fractional frame index whose live arrival time is {@code liveTimeMs}, or NaN if unknown. */
        synchronized double frameForLiveTime(double liveTimeMs) {
            if (Double.isNaN(liveOffsetMs)) return Double.NaN;
            return (liveTimeMs - liveOffsetMs) * sampleRate / 1000.0;
        }

        synchronized long oldestFrame() {
            return Math.max(0L, writtenFrames - capacityFrames);
        }

        synchronized long newestFrame() {
            return writtenFrames;
        }

        /**
         * Fills {@code out} with {@code frames} frames starting at {@code position}, stepping
         * {@code speed} source frames per output frame (linear interpolation). Missing frames are
         * written as silence. Returns the number of real frames, or -1 if everything requested
         * has already been overwritten.
         */
        synchronized int read(double position, double speed, short[] out, int frames) {
            long oldest = Math.max(0L, writtenFrames - capacityFrames);
            if (position + frames * speed < oldest) {
                java.util.Arrays.fill(out, 0, frames * channels, (short) 0);
                return -1;
            }
            int real = 0;
            long ringSamples = (long) capacityFrames * channels;
            for (int i = 0; i < frames; i++) {
                double pos = position + i * speed;
                long base = (long) Math.floor(pos);
                double frac = pos - base;
                boolean available = base >= oldest && base + 1 < writtenFrames;
                for (int c = 0; c < channels; c++) {
                    short value = 0;
                    if (available) {
                        int a = data.get((int) ((base * channels + c) % ringSamples));
                        int b = data.get((int) (((base + 1) * channels + c) % ringSamples));
                        value = (short) Math.round(a + (b - a) * frac);
                    }
                    out[i * channels + c] = value;
                }
                if (available) real++;
            }
            return real;
        }
    }

    // ── Stream parsing ───────────────────────────────────────────────────────

    /** Removes SHOUTcast/Icecast in-band metadata blocks when the server sends them anyway. */
    static final class IcyStrippingInputStream extends FilterInputStream {
        private final int metaInt;
        private int untilMeta;

        IcyStrippingInputStream(InputStream in, int metaInt) {
            super(in);
            this.metaInt = metaInt;
            this.untilMeta = metaInt;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (untilMeta == 0) {
                int length = in.read();
                if (length < 0) return -1;
                long skip = length * 16L;
                while (skip > 0) {
                    long skipped = in.skip(skip);
                    if (skipped <= 0) {
                        if (in.read() < 0) return -1;
                        skipped = 1;
                    }
                    skip -= skipped;
                }
                untilMeta = metaInt;
            }
            int n = in.read(b, off, Math.min(len, untilMeta));
            if (n > 0) untilMeta -= n;
            return n;
        }
    }

    /** Splits an MPEG audio (MP3) or ADTS-AAC elementary stream into frames. */
    static final class FrameReader {
        enum Codec { UNKNOWN, MPEG, ADTS }

        static final class Frame {
            String mimeType;
            int sampleRate;
            int channels;
            int samplesPerFrame;
            byte[] codecSpecificData;
            final byte[] payload = new byte[8192];
            int payloadLength;

            String describe() {
                return mimeType + " " + sampleRate + "Hz x" + channels;
            }
        }

        private static final int[] ADTS_RATES = {
                96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        };
        private static final int[][] MPEG_RATES = {
                {11025, 12000, 8000},   // MPEG 2.5
                {0, 0, 0},              // reserved
                {22050, 24000, 16000},  // MPEG 2
                {44100, 48000, 32000},  // MPEG 1
        };
        private static final int[] BITRATE_V1_L1 = {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};
        private static final int[] BITRATE_V1_L2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
        private static final int[] BITRATE_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        private static final int[] BITRATE_V2_L1 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256};
        private static final int[] BITRATE_V2_L23 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

        private final InputStream in;
        private Codec codec;
        private final byte[] buf = new byte[64 * 1024];
        private int start;
        private int end;
        private final Frame frame = new Frame();

        FrameReader(InputStream in, Codec codec) {
            this.in = in;
            this.codec = codec;
        }

        static Codec codecFromContentType(String contentType) {
            if (contentType.contains("aac") || contentType.contains("mp4a")) return Codec.ADTS;
            if (contentType.contains("mpeg") || contentType.contains("mp3")) return Codec.MPEG;
            return Codec.UNKNOWN;
        }

        Frame next() throws IOException {
            while (true) {
                ensure(10);
                Codec candidate = codecAt(start);
                int length = candidate == null ? -1 : frameLength(start, candidate);
                if (length > 0) {
                    // Confirm by checking the following header to avoid false syncs in payload data.
                    ensure(length + 10);
                    int next = start + length;
                    if (codecAt(next) == candidate && frameLength(next, candidate) > 0) {
                        codec = candidate;
                        fillFrame(length);
                        start += length;
                        return frame;
                    }
                }
                start++;
            }
        }

        private void ensure(int bytes) throws IOException {
            if (end - start >= bytes) return;
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, end - start);
                end -= start;
                start = 0;
            }
            while (end < bytes) {
                int n = in.read(buf, end, buf.length - end);
                if (n < 0) throw new EOFException("stream ended");
                end += n;
            }
        }

        /** Codec of the sync word at {@code pos} (absolute index in buf), or null if none / mismatched. */
        private Codec codecAt(int pos) {
            int b0 = buf[pos] & 0xFF;
            int b1 = buf[pos + 1] & 0xFF;
            if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return null;
            Codec detected = (b1 & 0x06) == 0 ? Codec.ADTS : Codec.MPEG;
            return codec == Codec.UNKNOWN || codec == detected ? detected : null;
        }

        /** Frame length of the header at {@code pos}, or -1 if it is not a valid header. */
        private int frameLength(int pos, Codec candidate) {
            return candidate == Codec.ADTS ? adtsLength(pos) : mpegLength(pos);
        }

        private int adtsLength(int pos) {
            int b1 = buf[pos + 1] & 0xFF;
            if ((b1 & 0xF0) != 0xF0) return -1;
            int b2 = buf[pos + 2] & 0xFF;
            int b3 = buf[pos + 3] & 0xFF;
            int rateIndex = (b2 >> 2) & 0x0F;
            if (rateIndex >= ADTS_RATES.length) return -1;
            int length = ((b3 & 0x03) << 11) | ((buf[pos + 4] & 0xFF) << 3) | ((buf[pos + 5] & 0xFF) >> 5);
            int headerLength = (b1 & 0x01) == 1 ? 7 : 9;
            return length > headerLength && length - headerLength <= frame.payload.length ? length : -1;
        }

        private int mpegLength(int pos) {
            int b1 = buf[pos + 1] & 0xFF;
            int b2 = buf[pos + 2] & 0xFF;
            int version = (b1 >> 3) & 0x03;
            int layer = (b1 >> 1) & 0x03;
            int bitrateIndex = (b2 >> 4) & 0x0F;
            int rateIndex = (b2 >> 2) & 0x03;
            if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return -1;
            int sampleRate = MPEG_RATES[version][rateIndex];
            int padding = (b2 >> 1) & 0x01;
            int bitrate = mpegBitrate(version, layer, bitrateIndex) * 1000;
            int length;
            if (layer == 3) {
                length = (12 * bitrate / sampleRate + padding) * 4;
            } else if (layer == 1 && version != 3) {
                length = 72 * bitrate / sampleRate + padding;
            } else {
                length = 144 * bitrate / sampleRate + padding;
            }
            return length > 4 && length <= frame.payload.length ? length : -1;
        }

        private static int mpegBitrate(int version, int layer, int index) {
            if (version == 3) {
                return layer == 3 ? BITRATE_V1_L1[index] : layer == 2 ? BITRATE_V1_L2[index] : BITRATE_V1_L3[index];
            }
            return layer == 3 ? BITRATE_V2_L1[index] : BITRATE_V2_L23[index];
        }

        private void fillFrame(int length) {
            int b1 = buf[start + 1] & 0xFF;
            int b2 = buf[start + 2] & 0xFF;
            int b3 = buf[start + 3] & 0xFF;
            if (codec == Codec.ADTS) {
                int headerLength = (b1 & 0x01) == 1 ? 7 : 9;
                int objectType = ((b2 >> 6) & 0x03) + 1;
                int rateIndex = (b2 >> 2) & 0x0F;
                int channelConfig = ((b2 & 0x01) << 2) | ((b3 >> 6) & 0x03);
                frame.mimeType = MediaFormat.MIMETYPE_AUDIO_AAC;
                frame.sampleRate = ADTS_RATES[rateIndex];
                frame.channels = Math.max(1, channelConfig);
                frame.samplesPerFrame = 1024;
                frame.codecSpecificData = new byte[]{
                        (byte) ((objectType << 3) | (rateIndex >> 1)),
                        (byte) (((rateIndex & 0x01) << 7) | (channelConfig << 3))
                };
                frame.payloadLength = length - headerLength;
                System.arraycopy(buf, start + headerLength, frame.payload, 0, frame.payloadLength);
            } else {
                int version = (b1 >> 3) & 0x03;
                int layer = (b1 >> 1) & 0x03;
                frame.mimeType = layer == 1 ? MediaFormat.MIMETYPE_AUDIO_MPEG
                        : layer == 2 ? "audio/mpeg-L2" : "audio/mpeg-L1";
                frame.sampleRate = MPEG_RATES[version][(b2 >> 2) & 0x03];
                frame.channels = ((b3 >> 6) & 0x03) == 3 ? 1 : 2;
                frame.samplesPerFrame = layer == 3 ? 384 : (layer == 1 && version != 3) ? 576 : 1152;
                frame.codecSpecificData = null;
                frame.payloadLength = length;
                System.arraycopy(buf, start, frame.payload, 0, length);
            }
        }
    }
}
