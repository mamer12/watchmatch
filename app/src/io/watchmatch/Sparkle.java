package io.watchmatch;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Spark's sparkle (two four-pointed stars with hollow sides), shared by Watchme+'s top bar and the home screen's. */
final class Sparkle {
    private Sparkle() {}

    /** The pair centred on (cx, cy), 28 * k wide at most; the caller owns the path and the paint's colour. No allocation. */
    static void draw(Canvas c, Paint fill, Path path, float cx, float cy, float k) {
        star(c, fill, path, cx, cy, 14 * k);
        star(c, fill, path, cx + 11 * k, cy - 13 * k, 6.5f * k);
    }

    private static void star(Canvas c, Paint fill, Path path, float cx, float cy, float r) {
        float in = r * 0.18f;
        path.reset();
        path.moveTo(cx, cy - r);
        path.quadTo(cx + in, cy - in, cx + r, cy);
        path.quadTo(cx + in, cy + in, cx, cy + r);
        path.quadTo(cx - in, cy + in, cx - r, cy);
        path.quadTo(cx - in, cy - in, cx, cy - r);
        path.close();
        c.drawPath(path, fill);
    }
}
