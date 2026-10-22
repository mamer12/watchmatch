package io.watchmatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Titles' logos (TMDB's transparent PNGs) for the box art: kept whole (fitted, never cropped) with their transparency,
 * at most MAX_W wide, on disk and, up to a budget, in memory. Thumbs crops and drops the alpha, so these come here.
 */
final class CinemaLogos {
    private static final int BUDGET = 10 * 1024 * 1024, MAX_W = 520, DISK_MAX = 300;
    private static CinemaLogos instance;

    private final File dir;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> pending = new HashSet<>(), failed = new HashSet<>();
    private final LruCache<String, Bitmap> ready = new LruCache<String, Bitmap>(BUDGET) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private Runnable listener;

    static synchronized CinemaLogos get(Context c) {
        if (instance == null) instance = new CinemaLogos(c.getApplicationContext());
        return instance;
    }

    private CinemaLogos(Context c) {
        dir = new File(c.getCacheDir(), "logos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Runs (on the main thread) when logos arrive. */
    void setListener(Runnable r) {
        listener = r;
    }

    /** The logo, or null while it's loading (the listener hears when it's there). Never blocks. */
    Bitmap get(final String url) {
        if (url == null || !url.startsWith("http")) return null;     // none (or not a link at all)
        synchronized (ready) {
            Bitmap b = ready.get(url);
            if (b != null) return b;
            if (failed.contains(url) || !pending.add(url)) return null;
        }
        io.execute(() -> {
            Bitmap b = null;
            try {
                b = decode(download(url));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("logo: " + e);
            }
            synchronized (ready) {
                pending.remove(url);
                if (b != null) ready.put(url, b);
                else failed.add(url);
            }
            if (b != null) main.post(() -> {
                if (listener != null) listener.run();
            });
        });
        return null;
    }

    private static Bitmap decode(File f) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0) throw new IOException("unreadable logo");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= MAX_W) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) throw new IOException("unreadable logo");
        return b;
    }

    private File download(String url) throws IOException {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length() + ".png");
        if (f.exists()) return f;
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the logo");
        prune();
        return f;
    }

    private void prune() {
        File[] all = dir.listFiles();
        if (all == null || all.length <= DISK_MAX) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = DISK_MAX; i < all.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            all[i].delete();
        }
    }
}
