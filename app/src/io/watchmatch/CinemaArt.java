package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * The picture behind the catalog's pages: the title's wide photo over the whole screen (TMDB, darkened on the left and at
 * the bottom by Thumbs), or, without one, its poster standing on the right and fading into black, like key art (right
 * to left: the darkening on the right and the poster on the left).
 * A new picture fades in over the last one. When a preview plays (CinemaPreview, under this view), the picture fades
 * out over it and only the darkening stays, for the text.
 */
final class CinemaArt {
    static final int BLACK = 0xFF0B0B0D;
    private static final int WIDE_W = 1280, WIDE_H = 720, POSTER_W = 720, POSTER_H = 1080;
    private static final long FADE_MS = 600;

    private static final class Art {
        String key, source;
        boolean wide;
        Bitmap bitmap;
    }

    private final Thumbs thumbs;
    private Art current, previous;
    private long fadeAt;
    private int w, h;
    private final Paint fill = new Paint(Paint.DITHER_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private Shader posterLeft, posterBottom, videoLeft, videoBottom, videoTop;

    CinemaArt(Thumbs thumbs) {
        this.thumbs = thumbs;
    }

    void size(int width, int height) {
        w = width;
        h = height;
        float x = w - POSTER_W * (h / (float) POSTER_H);
        posterLeft = Ui.rtl
                ? new LinearGradient(w - x, 0, w - x - w * 0.2f, 0, BLACK, BLACK & 0x00FFFFFF, Shader.TileMode.CLAMP)
                : new LinearGradient(x, 0, x + w * 0.2f, 0, BLACK, BLACK & 0x00FFFFFF, Shader.TileMode.CLAMP);
        posterBottom = new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0xF20B0B0D, Shader.TileMode.CLAMP);
        // As Thumbs darkens the wide photos: the left behind the title, the bottom behind the rows.
        videoLeft = new LinearGradient(Ui.rtl ? w : 0, 0, Ui.rtl ? w * 0.28f : w * 0.72f, 0,
                new int[] {0xEB000000, 0xD0000000, 0x70000000, 0}, new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        videoBottom = new LinearGradient(0, h * 0.42f, 0, h, new int[] {0, 0x8C000000, 0xF2000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        // Films come with their black bars in the picture: the top one melts into the page instead of a hard edge.
        videoTop = new LinearGradient(0, 0, 0, h * 0.3f, new int[] {0xFF0B0B0D, 0xCC0B0B0D, 0}, new float[] {0, 0.45f, 1},
                Shader.TileMode.CLAMP);
    }

    /** The title's picture: its cover or wide photo once known, else its poster. Nothing changes while it's the same one. */
    void show(Library.Title t, String poster) {
        // The catalog's own cover (banner titles) first, then TMDB's wide photo.
        String backdrop = t == null ? "" : !t.cover.isEmpty() ? t.cover : t.backdrop == null ? "" : t.backdrop;
        boolean wide = !backdrop.isEmpty();
        String source = wide ? backdrop : poster;
        if (source == null || source.isEmpty()) return;
        if (current != null && source.equals(current.source)) return;
        Art a = new Art();
        a.source = source;
        a.wide = wide;
        a.key = wide ? Thumbs.key(source, WIDE_W, WIDE_H, Thumbs.SCREEN) : Thumbs.key(source, POSTER_W, POSTER_H, 0);
        if (current != null && current.bitmap != null) previous = current;
        current = a;
        fadeAt = 0;
    }

    /**
     * Draws the picture; true while a fade is running (draw again next frame). `videoAt`: when a preview started
     * showing under the view (0 for none): the picture fades out over it. `shade`: how much of the darkening for the
     * text stays over the preview (less as it goes full screen).
     */
    boolean draw(Canvas c, long videoAt, float shade) {
        long up = SystemClock.uptimeMillis();
        float video = videoAt == 0 ? 0 : Ui.clamp((up - videoAt) / (float) FADE_MS);
        if (video > 0) {
            // `shade` < 1: full-screen preview, the darkening goes too.
            int a = Math.round(255 * video * Math.max(0.15f, shade));
            fill.setShader(videoLeft);
            fill.setAlpha(a);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(videoBottom);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(videoTop);
            c.drawRect(0, 0, w, h * 0.3f, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            if (video >= 1) return false;
        }
        int visible = Math.round(255 * (1 - video));
        fill.setShader(null);
        fill.setColor(BLACK);
        fill.setAlpha(visible);
        c.drawRect(0, 0, w, h, fill);
        fill.setAlpha(255);
        if (current != null && current.bitmap == null) {
            current.bitmap = current.wide ? thumbs.get(current.key, current.source, WIDE_W, WIDE_H, Thumbs.SCREEN)
                    : thumbs.get(current.key, current.source, POSTER_W, POSTER_H, 0);
            if (current.bitmap != null) fadeAt = up;
        }
        float fade = fadeAt == 0 ? 0 : Ui.clamp((up - fadeAt) / (float) FADE_MS);
        if (previous != null && fade < 1) drawArt(c, previous, visible);
        if (fade >= 1) previous = null;
        if (current != null && current.bitmap != null) drawArt(c, current, Math.round(visible * fade));
        return (current != null && current.bitmap != null && fade < 1) || (video > 0 && video < 1);
    }

    private void drawArt(Canvas c, Art a, int alpha) {
        image.setAlpha(alpha);
        if (a.wide) {
            rect.set(0, 0, w, h);
            c.drawBitmap(a.bitmap, null, rect, image);
        } else {
            float pw = POSTER_W * (h / (float) POSTER_H);
            if (Ui.rtl) rect.set(0, 0, pw, h);
            else rect.set(w - pw, 0, w, h);
            c.drawBitmap(a.bitmap, null, rect, image);
            fill.setAlpha(alpha);
            fill.setShader(posterLeft);
            c.drawRect(rect, fill);
            fill.setShader(posterBottom);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        image.setAlpha(255);
    }
}
