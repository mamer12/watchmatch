package io.watchmatch;

import android.util.Log;

/** The app's log (adb logcat -s WatchMatch). Never logs keys. An identical line repeats at most once a second. */
final class AppLog {
    private AppLog() {}

    private static final long SAME_LINE_MS = 1000;
    private static String last = "";
    private static long lastAt, held;
    private static boolean firstLogged;

    static void w(String message) {
        i(message);
    }

    static void i(String message) {
        long now = System.nanoTime() / 1_000_000L;
        long skipped;
        synchronized (AppLog.class) {
            if (message.equals(last) && now - lastAt < SAME_LINE_MS) {
                held++;
                return;
            }
            skipped = held;
            held = 0;
            last = message;
            lastAt = now;
        }
        Log.i("WatchMatch", skipped > 0 ? message + " (+" + skipped + " more)" : message);
    }

    /** The first error of a session, under its own tag so it outlives the rest when the buffer rolls over. */
    static void first(String message) {
        synchronized (AppLog.class) {
            if (firstLogged) return;
            firstLogged = true;
        }
        Log.w("WatchMatchFirst", message);
    }
}
