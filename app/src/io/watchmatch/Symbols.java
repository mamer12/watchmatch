package io.watchmatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Material Symbols from assets/symbols/*.png: white shapes tinted when drawn and sized like text. */
final class Symbols {
    /** PNG pixels per typographic point (96 dpi at 1.5x). */
    private static final float PX_PER_POINT = 144;
    private static final int MAX_FILTERS = 256;

    private static final Object LOCK = new Object();
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Map<Integer, PorterDuffColorFilter> FILTERS = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF DST = new RectF();

    private Symbols() {}

    static Bitmap get(Context c, String name) {
        synchronized (LOCK) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) b.setHasMipMap(true);
            } catch (IOException e) {
                // Missing icon: cached as null below, drawn as nothing.
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null) return;
        float w = b.getWidth() * pointSize / PX_PER_POINT, h = b.getHeight() * pointSize / PX_PER_POINT;
        synchronized (PAINT) {
            PorterDuffColorFilter f = FILTERS.get(color);
            if (f == null) {
                if (FILTERS.size() > MAX_FILTERS) FILTERS.clear();
                f = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
                FILTERS.put(color, f);
            }
            PAINT.setColorFilter(f);
            DST.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            canvas.drawBitmap(b, null, DST, PAINT);
        }
    }

    /** Open-Meteo weather code to icon name. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
