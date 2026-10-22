package io.watchmatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * One subtitle on screen, as Netflix draws them: white (or yellow) letters with a soft shadow, a thin edge to stay
 * readable on bright pictures, centered, up to three lines. Outline and background box are the other styles
 * (CinemaPrefs). Used by the player and by the previews, so both look the same.
 */
final class SubtitleText {
    private final Context context;
    /** The layout draws with `paint`; `fill` and `edge` are what it's set to for each pass. */
    private TextPaint paint, fill, edge;
    private StaticLayout layout;
    private String text;
    private float k = 1, width;
    private int style;
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    SubtitleText(Context c) {
        context = c;
        box.setColor(0xB3000000);
    }

    /** Sizes and the style from the settings (after a change there too); `widthPx`: the widest a line gets. */
    void configure(float k, float widthPx) {
        this.k = k;
        width = widthPx;
        style = CinemaPrefs.subtitleStyle(context);
        float size = CinemaPrefs.subtitlePoints(CinemaPrefs.subtitleSize(context)) * k;
        int color = CinemaPrefs.subtitleYellow(context) ? 0xFFFFE14D : 0xFFFFFFFF;
        fill = new TextPaint(Ui.text(Ui.semibold, size, color));
        if (style == CinemaPrefs.STYLE_SHADOW) fill.setShadowLayer(size * 0.14f, 0, size * 0.05f, 0xE6000000);
        edge = new TextPaint(fill);
        edge.clearShadowLayer();
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.ROUND);
        edge.setStrokeWidth(size * (style == CinemaPrefs.STYLE_OUTLINE ? 0.15f : 0.05f));
        edge.setColor(style == CinemaPrefs.STYLE_OUTLINE ? 0xE6000000 : 0x8C000000);
        paint = new TextPaint(fill);
        String was = text;
        text = null;
        layout = null;
        set(was);
    }

    /** The text to show (null for none); true when what's on screen changes. */
    boolean set(String t) {
        if (t == null ? text == null : t.equals(text)) return false;
        text = t;
        layout = t == null || paint == null ? null : StaticLayout.Builder.obtain(t, 0, t.length(), paint, Math.max(1, Math.round(width)))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build();
        return true;
    }

    /** Centered on `cx`, its last line resting on `bottom`. */
    void draw(Canvas c, float cx, float bottom) {
        if (layout == null) return;
        float top = bottom - layout.getHeight(), left = cx - layout.getWidth() / 2f;
        if (style == CinemaPrefs.STYLE_BOX) {
            float lw = 0;
            for (int i = 0; i < layout.getLineCount(); i++) lw = Math.max(lw, layout.getLineWidth(i));
            float pad = 18 * k;
            rect.set(cx - lw / 2 - pad, top - pad / 2, cx + lw / 2 + pad, bottom + pad / 2);
            c.drawRoundRect(rect, 10 * k, 10 * k, box);
        }
        c.save();
        c.translate(left, top);
        if (style != CinemaPrefs.STYLE_BOX) {
            paint.set(edge);       // the edge first, then the letters (with their shadow) over it
            layout.draw(c);
        }
        paint.set(fill);
        layout.draw(c);
        c.restore();
    }
}
