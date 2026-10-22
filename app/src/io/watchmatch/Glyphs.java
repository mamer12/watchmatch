package io.watchmatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import java.util.HashMap;

/** Flat one-colour coin logos for the price strip, rasterised once per size. */
final class Glyphs {
    private Glyphs() {}

    private static final int EMPTY_ALPHA = 8;
    private static final HashMap<String, Bitmap> BITMAPS = new HashMap<>();
    private static final HashMap<Integer, PorterDuffColorFilter> FILTERS = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    static void coin(Context context, Canvas c, String coin, float cx, float cy, float cap, int color) {
        Bitmap b = bitmap(context, coin, cap);
        if (b == null) return;
        float left = Math.round(cx - b.getWidth() / 2f), top = Math.round(cy - b.getHeight() / 2f);
        synchronized (FILTERS) {
            PorterDuffColorFilter f = FILTERS.get(color);
            if (f == null) {
                if (FILTERS.size() > 256) FILTERS.clear();
                f = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
                FILTERS.put(color, f);
            }
            PAINT.setColorFilter(f);
            c.drawBitmap(b, left, top, PAINT);
        }
    }

    static float width(Context context, String coin, float cap) {
        Bitmap b = bitmap(context, coin, cap);
        return b == null ? 0 : b.getWidth();
    }

    private static int resource(String coin) {
        if ("BTC".equals(coin)) return R.drawable.coin_btc;
        if ("ETH".equals(coin)) return R.drawable.coin_eth;
        if ("SOL".equals(coin)) return R.drawable.coin_sol;
        return R.drawable.coin_usdt;
    }

    private static Bitmap bitmap(Context context, String coin, float cap) {
        int res = resource(coin);
        int frame = Math.max(12, Math.round(cap * 1.75f));
        String key = res + ":" + frame;
        synchronized (BITMAPS) {
            if (BITMAPS.containsKey(key)) return BITMAPS.get(key);
            Bitmap b = render(context, res, frame);
            BITMAPS.put(key, b);
            return b;
        }
    }

    private static Bitmap render(Context context, int res, int frame) {
        Drawable d = context.getDrawable(res);
        if (d == null) return null;
        Bitmap full = Bitmap.createBitmap(frame, frame, Bitmap.Config.ARGB_8888);
        d = d.mutate();
        d.setBounds(0, 0, frame, frame);
        d.draw(new Canvas(full));
        int first = -1, last = -1;
        int[] col = new int[frame];
        for (int x = 0; x < frame; x++) {
            full.getPixels(col, 0, 1, x, 0, 1, frame);
            boolean ink = false;
            for (int p : col) if ((p >>> 24) > EMPTY_ALPHA) { ink = true; break; }
            if (!ink) continue;
            if (first < 0) first = x;
            last = x;
        }
        if (first < 0) return full;
        Bitmap trimmed = Bitmap.createBitmap(full, first, 0, last - first + 1, frame);
        if (trimmed != full) full.recycle();
        return trimmed;
    }
}
