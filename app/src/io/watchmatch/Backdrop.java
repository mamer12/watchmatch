package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.File;
import java.io.IOException;

/** Everything the home screen needs from one backdrop photo, and the screensaver's photo loading. */
final class Backdrop {
    static final int W = 1920, H = 1080;
    static final int FW = 192, FH = 108;
    static final int GW = 480, GH = 270;
    static final int WIDE_W = 1092, WIDE_H = 464;
    static final int TALL_W = 336, TALL_H = 464;
    static final int PHOTO_FROST = 6;

    final Bitmap image;
    final int[] frost, glass;
    final Bitmap wide, tall;
    final int[] light, lightGlass;
    final Bitmap lightImage;
    final int[] nightFrost, nightGlass;
    final Bitmap nightImage;

    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, Bitmap tall, int[] light, int[] lightGlass,
                     int[] nightFrost, int[] nightGlass) {
        this.image = image;
        this.frost = frost;
        this.glass = glass;
        this.wide = wide;
        this.tall = tall;
        this.light = light;
        this.lightGlass = lightGlass;
        this.nightFrost = nightFrost;
        this.nightGlass = nightGlass;
        lightImage = light == null ? null : fromPixels(light, FW, FH);
        nightImage = nightFrost == null ? null : fromPixels(nightFrost, FW, FH);
    }

    /** Consumes (recycles) src. Heavy: call off the UI thread. */
    static Backdrop make(Bitmap src, boolean forLight) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        float scale = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((W - src.getWidth() * scale) / 2, (H - src.getHeight() * scale) / 2);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        new Canvas(out).drawBitmap(src, m, p);
        src.recycle();

        if (forLight) {
            Bitmap wide = card(out, WIDE_W, WIDE_H), tall = card(out, TALL_W, TALL_H);
            int[] frost = reduce(out, FW, FH, 3, 3);
            int[] glass = reduce(out, GW, GH, 2, 2);
            int[] light = whiten(frost.clone(), 0.82f);
            int[] lightGlass = whiten(glass.clone(), 0.74f);
            int[] nightFrost = darken(frost.clone(), 0.76f);
            int[] nightGlass = darken(glass, 0.56f);
            out.recycle();
            return new Backdrop(null, frost, null, wide, tall, light, lightGlass, nightFrost, nightGlass);
        }
        scrim(out);
        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass, null, null, null, null, null, null);
    }

    /** {photo (mutable, w x h), frosted (w/6 x h/6)} for the screensaver; luminances into light[0] (caption), [1] (clock). */
    static Bitmap[] photo(File f, int w, int h, float[] light) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("unreadable image");
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
        if (src == null) throw new IOException("unreadable image");

        Bitmap cropped = crop(src, w, h);
        if (cropped != src) src.recycle();
        Bitmap photo = fit(cropped, w, h);
        if (photo != cropped) cropped.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(photo, 0f, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(photo, 0.62f, 0.04f, 0.96f, 0.36f);
        }
        int[] px = reduce(photo, w / PHOTO_FROST, h / PHOTO_FROST, 3, 2);
        Bitmap frost = fromPixels(px, w / PHOTO_FROST, h / PHOTO_FROST);
        if (!photo.isMutable()) {
            Bitmap copy = photo.copy(Bitmap.Config.ARGB_8888, true);
            photo.recycle();
            photo = copy;
        }
        return new Bitmap[] {photo, frost};
    }

    /** To GPU memory; the original is recycled unless the copy failed. */
    static Bitmap gpu(Bitmap b) {
        Bitmap copy = b.copy(Bitmap.Config.HARDWARE, false);
        if (copy == null) return b;
        b.recycle();
        return copy;
    }

    // ---- Scrims

    private static void scrim(Bitmap out) {
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        // Heavier behind the hero text when the photo is bright on the left.
        float boost = Math.max(0, Math.min(0.3f, (luminance(out, 0f, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f));
        p.setShader(new LinearGradient(0, 0, 0.68f * W, 0,
                new int[] {black(0.66f + boost), black(0.52f + 0.8f * boost), black(0.26f + 0.4f * boost), black(0.07f), black(0)},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);

        p.setShader(new LinearGradient(0, H, 0, 0.4f * H,
                new int[] {black(0.38f), black(0.30f), black(0.18f), black(0.05f), black(0)},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0.4f * H, W, H, p);

        // A flattened pool of shade under the small text above the shelf on the right.
        float cx = 0.8f * W, cy = 0.65f * H, r = 0.24f * W;
        p.setShader(new RadialGradient(cx, cy, r, new int[] {black(0.42f), black(0.18f), black(0)},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.translate(cx, cy);
        c.scale(1, 0.32f);
        c.translate(-cx, -cy);
        c.drawCircle(cx, cy, r, p);
        c.restore();

        p.setShader(new LinearGradient(0, 0, 0, 0.24f * H,
                new int[] {black(0.34f), black(0.10f), black(0)}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, 0.24f * H, p);
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    // ---- Helpers

    private static Bitmap card(Bitmap full, int w, int h) {
        Bitmap cropped = crop(full, w, h);
        Bitmap fitted = fit(cropped, w, h);
        if (cropped != full && cropped != fitted) cropped.recycle();
        // `fitted` is `full` itself only when no crop or scaling was needed: copy so the caller may recycle `full`.
        return gpu(fitted == full ? full.copy(Bitmap.Config.ARGB_8888, false) : fitted);
    }

    /** Centre crop to the aspect ratio of w:h. Returns src itself when nothing is cut; src is recycled only when replaced. */
    private static Bitmap crop(Bitmap src, int w, int h) {
        float ratio = w / (float) h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        if (cw == src.getWidth() && ch == src.getHeight()) return src;
        return Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
    }

    /** Halve while it stays at least w wide, then scale to exactly w x h. Intermediates are recycled, src never. */
    private static Bitmap fit(Bitmap src, int w, int h) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        if (cur.getWidth() != w || cur.getHeight() != h) {
            Bitmap next = Bitmap.createScaledBitmap(cur, w, h, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        return cur;
    }

    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap cur = full;
        while (cur.getWidth() / 2 >= 1.5f * w) {
            Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != full) cur.recycle();
            cur = next;
        }
        if (cur.getWidth() != w || cur.getHeight() != h) {
            Bitmap next = Bitmap.createScaledBitmap(cur, w, h, true);
            if (cur != full) cur.recycle();
            cur = next;
        }
        int[] px = new int[w * h];
        cur.getPixels(px, 0, w, 0, 0, w, h);
        if (cur != full) cur.recycle();
        int[] tmp = new int[w * h];
        for (int i = 0; i < passes; i++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** One box blur pass along a row (horizontal) or a column, edges clamped, alpha forced opaque. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int stride = horizontal ? 1 : w, step = horizontal ? w : 1;
        int window = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * step;
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int px = in[base + Math.max(0, Math.min(len - 1, k)) * stride];
                sr += (px >> 16) & 255;
                sg += (px >> 8) & 255;
                sb += px & 255;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * stride] = 0xFF000000 | (sr + window / 2) / window << 16 | (sg + window / 2) / window << 8
                        | (sb + window / 2) / window;
                int add = in[base + Math.min(len - 1, i + r + 1) * stride];
                int drop = in[base + Math.max(0, i - r) * stride];
                sr += ((add >> 16) & 255) - ((drop >> 16) & 255);
                sg += ((add >> 8) & 255) - ((drop >> 8) & 255);
                sb += (add & 255) - (drop & 255);
            }
        }
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = 0xFF000000 | lift((p >> 16) & 255, amount) << 16 | lift((p >> 8) & 255, amount) << 8 | lift(p & 255, amount);
        }
        return px;
    }

    private static int lift(int c, float amount) {
        return Math.round(c * (1 - amount) + 255 * amount);
    }

    private static int[] darken(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 11 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 11 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 11 * amount + 2 * amount);
            px[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        return px;
    }

    /** Mean luminance (0..1) of a region given in fractions of the bitmap, on a 32 x 24 grid of samples. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        double sum = 0;
        for (int j = 0; j < 24; j++) {
            int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int p = b.getPixel(x, y);
                sum += 0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255);
            }
        }
        return (float) (sum / (32 * 24 * 255));
    }

    private static Bitmap fromPixels(int[] px, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }
}
