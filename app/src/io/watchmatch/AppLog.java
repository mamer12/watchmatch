package io.watchmatch;

import android.util.Log;

/** The app's log (adb logcat -s WatchMatch). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("WatchMatch", message);
    }
}
