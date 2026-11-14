package com.f1tvpatch.radio;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The radio's log: logcat, plus a small file that survives a reboot of the TV, so what the sync did
 * during a session can still be read afterwards (logcat is gone by then):
 * {@code Android/data/<package>/files/radio-sync.log} (and {@code .1.log}, the previous part).
 * Sync measurements only; nothing about the account ever passes through here.
 */
final class L {
    private L() { }

    private static final String TAG = RadioEngine.TAG;
    private static final long MAX_BYTES = 1_500_000L;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static File file;

    static synchronized void init(Context context) {
        if (file != null || context == null) return;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            file = new File(dir, "radio-sync.log");
        } catch (Throwable t) {
            Log.w(TAG, "no log file", t);
        }
    }

    static void i(String message) {
        Log.i(TAG, message);
        append('I', message, null);
    }

    static void w(String message) {
        Log.w(TAG, message);
        append('W', message, null);
    }

    static void w(String message, Throwable t) {
        Log.w(TAG, message, t);
        append('W', message, t);
    }

    static void e(String message) {
        Log.e(TAG, message);
        append('E', message, null);
    }

    static void e(String message, Throwable t) {
        Log.e(TAG, message, t);
        append('E', message, t);
    }

    private static synchronized void append(char level, String message, Throwable t) {
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) {
                File previous = new File(file.getParentFile(), "radio-sync.1.log");
                previous.delete();
                file.renameTo(previous);
            }
            try (Writer out = new FileWriter(file, true)) {
                out.write(TIME.format(new Date()));
                out.write(' ');
                out.write(level);
                out.write(' ');
                out.write(message);
                if (t != null) {
                    out.write(" | ");
                    out.write(t.toString());
                    StackTraceElement[] stack = t.getStackTrace();
                    for (int k = 0; k < Math.min(4, stack.length); k++) {
                        out.write(" < ");
                        out.write(stack[k].toString());
                    }
                }
                out.write('\n');
            }
        } catch (Throwable ignored) {
            // A log that cannot be written must never disturb playback
        }
    }
}
