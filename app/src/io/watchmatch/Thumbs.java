package io.watchmatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
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
 * The home screen's pictures (row thumbnails, posters, the full-screen photo), made once in the background at the exact
 * size they are drawn: cropped to fit, corners already rounded, then moved to GPU memory. Downloads are kept on disk;
 * the ready pictures stay in memory up to a budget (the least recently drawn go first), small enough for a 2 GB TV.
 */
final class Thumbs {
    interface Listener { void onThumb(); }

    /** In memory: about what the screen shows at once, plus the rows just above and below. */
    private static final int BUDGET = 28 * 1024 * 1024;
    private static final int DISK_MAX = 400;

    private static Thumbs instance;

    private final File dir;
    /** 8 workers: downloads wait their turn at `lines` (4 on the mirror, 8 elsewhere), pictures are cut 3 at a time. */
    private final ExecutorService io = Executors.newFixedThreadPool(8);
    private final ImageRules.Gate lines = new ImageRules.Gate();
    private final java.util.concurrent.Semaphore cutting = new java.util.concurrent.Semaphore(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> pending = new HashSet<>(), failed = new HashSet<>();
    private final LruCache<String, Bitmap> ready = new LruCache<String, Bitmap>(BUDGET) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private Listener listener;
    private boolean notifyPosted;

    static synchronized Thumbs get(Context c) {
        if (instance == null) instance = new Thumbs(c.getApplicationContext());
        return instance;
    }

    private Thumbs(Context c) {
        dir = new File(c.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    void setListener(Listener l) { listener = l; }

    /** Corners of this radius (in pixels) mean the full-screen photo: no corners, darkened for the text over it. */
    static final float SCREEN = -1;
    /** Corners of this radius mean a logo (a team's crest): fit inside, the transparency kept. */
    static final float FIT = -2;

    /** A picture's name in memory; made once per card (building it on every frame would leave garbage behind). */
    static String key(String source, int w, int h, float radius) {
        String key = source + '|' + w + 'x' + h + '|' + Math.round(radius);
        return radius == SCREEN && Ui.rtl ? key + MIRRORED : key;     // right to left: darkened on the right
    }

    private static final String MIRRORED = "|rtl";

    /**
     * The picture at `source` (an https URL or a file path) at w × h with corners of `radius`, or null while it's being
     * made (the listener hears when it's ready). `key` comes from key(). Never blocks.
     */
    Bitmap get(final String key, final String source, final int w, final int h, final float radius) {
        if (key == null || source == null || w <= 0 || h <= 0) return null;
        synchronized (ready) {
            Bitmap b = ready.get(key);
            if (b != null) return b;
            if (failed.contains(key) || !pending.add(key)) return null;
        }
        io.execute(() -> {
            Bitmap b = null;
            try {
                b = make(source, w, h, radius, key.endsWith(MIRRORED));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("picture " + source + ": " + e);
            }
            synchronized (ready) {
                pending.remove(key);
                if (b != null) ready.put(key, b);
                else failed.add(key);
            }
            if (b != null) changed();
        });
        return null;
    }

    /** Pictures not drawn for a while can go (the home screen went to the background). */
    void trim() {
        synchronized (ready) {
            ready.trimToSize(BUDGET / 3);
            failed.clear();
        }
    }

    /** One redraw for a burst of pictures arriving together. */
    private void changed() {
        synchronized (this) {
            if (notifyPosted) return;
            notifyPosted = true;
        }
        main.postDelayed(() -> {
            synchronized (this) { notifyPosted = false; }
            if (listener != null) listener.onThumb();
        }, 60);
    }

    private Bitmap make(String source, int w, int h, float radius, boolean mirrored) throws IOException {
        File f = source.startsWith("http") ? download(Endpoints.image(source)) : new File(source);
        cutting.acquireUninterruptibly();
        try {
            return cut(f, w, h, radius, mirrored);
        } finally {
            cutting.release();
        }
    }

    private Bitmap cut(File f, int w, int h, float radius, boolean mirrored) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("unreadable image");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) throw new IOException("unreadable image");
        // Center crop to w × h, corners rounded in the same pass (drawing it later is then a plain bitmap).
        Bitmap out = Bitmap.createBitmap(w, h, radius > 0 || radius == FIT ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        float scale = radius == FIT ? Math.min((float) w / src.getWidth(), (float) h / src.getHeight())
                : Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (radius == SCREEN) {
            c.drawBitmap(src, m, p);
            darken(c, w, h, mirrored);
        } else if (radius > 0) {
            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            c.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        } else {
            c.drawBitmap(src, m, p);
        }
        src.recycle();
        return Backdrop.gpu(out);
    }

    /**
     * The darkening that keeps the text readable over the photo (on the left behind the title, at the bottom behind
     * the rows, a little at the top behind the clock), drawn into it once instead of over it on every frame. Right to
     * left, the side darkening is on the right, where the title is.
     */
    private static void darken(Canvas c, int w, int h, boolean mirrored) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(mirrored ? w : 0, 0, mirrored ? w * 0.28f : w * 0.72f, 0,
                new int[] {0xE6000000, 0xA6000000, 0x40000000, 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h * 0.42f, 0, h, new int[] {0, 0x8C000000, 0xF2000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.22f, 0x8C000000, 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    private File download(String url) throws IOException {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
        // Kept 30 days: the small posters are the same ones every row asks for again and again.
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ImageRules.KEEP_MS) return f;
        File part = new File(f.getPath() + ".part");
        try {
            lines.acquire(ImageRules.slots(Endpoints.active().id));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        } finally {
            if (c != null) c.disconnect();
            lines.release();
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune();
        return f;
    }

    /** Keeps the newest DISK_MAX downloads. */
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
