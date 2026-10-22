package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** A link as a QR code bitmap, black on white with a four-module quiet zone (made once per link, never while drawing). */
final class QrBitmap {
    private QrBitmap() {}

    /** About `size` pixels square; null when it can't be made (the code is shown alone then). */
    static Bitmap make(String text, int size) {
        try {
            boolean[][] m = Qr.encode(text);
            if (m == null) return null;
            int quiet = 4, modules = m.length + 2 * quiet;
            int px = Math.max(1, size / modules);
            Bitmap b = Bitmap.createBitmap(modules * px, modules * px, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Canvas c = new Canvas(b);
            Paint dark = new Paint();
            dark.setColor(Color.BLACK);
            for (int y = 0; y < m.length; y++) {
                for (int x = 0; x < m.length; x++) {
                    if (m[y][x]) c.drawRect((x + quiet) * px, (y + quiet) * px, (x + quiet + 1) * px, (y + quiet + 1) * px, dark);
                }
            }
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("qr not made: " + e);
            return null;
        }
    }
}
