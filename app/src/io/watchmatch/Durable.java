package io.watchmatch;

import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes that survive the TV being unplugged: SharedPreferences put on disk right away (in the background, never on
 * the UI thread), and files replaced whole or not at all.
 */
final class Durable {
    private Durable() {}

    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();

    /** apply() (readers see it at once), then a commit in the background: on disk and synced moments later. */
    static void save(SharedPreferences sp, SharedPreferences.Editor edit) {
        edit.apply();
        flush(sp);
    }

    /** Whatever apply() left pending in `sp`, written and synced now (in the background). */
    static void flush(final SharedPreferences sp) {
        DISK.execute(() -> {
            // An empty commit waits for the pending apply() writes and writes them itself if they haven't started.
            if (!sp.edit().commit()) AppLog.w("settings not written to disk");
        });
    }

    /** Replaces `f` with `text` in one go (the old file stays whole if the power goes during the write). */
    static void write(File f, String text) throws IOException {
        AtomicFile file = new AtomicFile(f);
        FileOutputStream out = file.startWrite();
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | RuntimeException e) {
            file.failWrite(out);
            throw e;
        }
    }

    /** What write() left in `f` (or the copy before an interrupted write), or null when there's none. */
    static String read(File f) {
        AtomicFile file = new AtomicFile(f);
        try {
            return new String(file.readFully(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
