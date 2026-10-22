package io.watchmatch;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Counts crashes of the :home process; the 3rd inside 5 minutes asks for the TV's own home screen back. */
final class Watchdog {
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final int LIMIT = 3, MAX_FILE = 4096;
    private static final String FILE = "home-crashes.txt";
    private static boolean installed;

    private Watchdog() {}

    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (record(app, System.currentTimeMillis()) >= LIMIT) HomeMode.requestRevertHome(app);
            } catch (Throwable ignored) {
                // bookkeeping must never hide the real crash
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static boolean crashedTooOften(Context c) {
        return recent(read(c), System.currentTimeMillis()).size() >= LIMIT;
    }

    static void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
    }

    /** Timestamps inside the window; later ones (a clock that went back) don't count. */
    static List<Long> recent(List<Long> all, long now) {
        List<Long> keep = new ArrayList<>();
        for (long at : all) if (now - at < WINDOW_MS && at <= now) keep.add(at);
        return keep;
    }

    static List<Long> parse(String text, long now) {
        List<Long> all = new ArrayList<>();
        for (String line : text.split("\n")) {
            try {
                all.add(Long.parseLong(line.trim()));
            } catch (NumberFormatException ignored) {
                // skip junk lines
            }
        }
        return recent(all, now);
    }

    private static List<Long> read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[MAX_FILE];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return parse(new String(buf, 0, n, StandardCharsets.UTF_8), System.currentTimeMillis());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static synchronized int record(Context c, long now) throws IOException {
        List<Long> list = recent(read(c), now);
        list.add(now);
        StringBuilder text = new StringBuilder();
        for (long at : list) text.append(at).append('\n');
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        return list.size();
    }
}
