package io.watchmatch;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * The profile avatars: a coloured disc with a simple animal or sign drawn on it with paths (no pictures). The twelve
 * keys are ProfileRules.AVATARS. Everything is drawn in a unit space (the disc's radius is 1) with shared paints and
 * paths, so drawing allocates nothing; the UI thread is the only caller.
 */
final class Avatars {
    private Avatars() {}

    /** The eight profile colours. */
    static final int[] COLORS = {0xFFE50914, 0xFF2D7DD2, 0xFF2BA84A, 0xFFF4A300, 0xFF8E44AD, 0xFFE91E8C, 0xFF0AA5A5, 0xFFF26B3A};
    private static final int LIGHT = 0xFFFAF7F2, INK = 0xFF16161A, SHADE = 0x33000000, WARM = 0xFFFFC14D;

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG), LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path(), PATH2 = new Path();
    private static final RectF OVAL = new RectF();

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        LINE.setStrokeJoin(Paint.Join.ROUND);
    }

    static int color(int index) {
        return COLORS[Math.max(0, Math.min(COLORS.length - 1, index))];
    }

    /** The avatar centred at (cx, cy) with radius r; `ring` draws a white ring around it (the focused tile). */
    static void draw(Canvas c, String key, int colorIndex, float cx, float cy, float r, boolean ring) {
        int bg = color(colorIndex);
        if (ring) {
            FILL.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r + r * 0.09f, FILL);
        }
        FILL.setColor(bg);
        c.drawCircle(cx, cy, r, FILL);
        int save = c.save();
        PATH.reset();
        PATH.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(PATH);
        c.translate(cx, cy);
        c.scale(r, r);
        art(c, key, bg);
        c.restoreToCount(save);
    }

    private static void art(Canvas c, String key, int bg) {
        switch (key) {
            case "owl": owl(c); break;
            case "bear": bear(c); break;
            case "cat": cat(c); break;
            case "dog": dog(c); break;
            case "panda": panda(c); break;
            case "lion": lion(c); break;
            case "rocket": rocket(c); break;
            case "star": star(c); break;
            case "moon": moon(c, bg); break;
            case "sun": sun(c); break;
            case "wave": wave(c); break;
            default: fox(c);
        }
    }

    // ---- helpers (unit space) ---------------------------------------------------------------

    private static void paint(int color) {
        FILL.setColor(color);
    }

    private static void circle(Canvas c, float x, float y, float r, int color) {
        paint(color);
        c.drawCircle(x, y, r, FILL);
    }

    private static void oval(Canvas c, float x, float y, float rx, float ry, int color) {
        paint(color);
        OVAL.set(x - rx, y - ry, x + rx, y + ry);
        c.drawOval(OVAL, FILL);
    }

    private static void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        paint(color);
        PATH.reset();
        PATH.moveTo(x1, y1);
        PATH.lineTo(x2, y2);
        PATH.lineTo(x3, y3);
        PATH.close();
        c.drawPath(PATH, FILL);
    }

    private static void line(Canvas c, float x1, float y1, float x2, float y2, float width, int color) {
        LINE.setColor(color);
        LINE.setStrokeWidth(width);
        c.drawLine(x1, y1, x2, y2, LINE);
    }

    private static void eyes(Canvas c, float x, float y, float r) {
        circle(c, -x, y, r, INK);
        circle(c, x, y, r, INK);
        circle(c, -x + r * 0.35f, y - r * 0.35f, r * 0.34f, LIGHT);
        circle(c, x + r * 0.35f, y - r * 0.35f, r * 0.34f, LIGHT);
    }

    private static void smile(Canvas c, float y, float w) {
        LINE.setColor(INK);
        LINE.setStrokeWidth(0.05f);
        PATH.reset();
        PATH.moveTo(-w, y);
        PATH.quadTo(0, y + w * 0.9f, w, y);
        c.drawPath(PATH, LINE);
    }

    // ---- the twelve -------------------------------------------------------------------------

    private static void fox(Canvas c) {
        int orange = 0xFFFF8A3D;
        tri(c, -0.74f, -0.18f, -0.6f, -0.92f, -0.1f, -0.5f, orange);
        tri(c, 0.74f, -0.18f, 0.6f, -0.92f, 0.1f, -0.5f, orange);
        tri(c, -0.6f, -0.3f, -0.55f, -0.7f, -0.28f, -0.48f, INK);
        tri(c, 0.6f, -0.3f, 0.55f, -0.7f, 0.28f, -0.48f, INK);
        paint(orange);
        PATH.reset();
        PATH.moveTo(-0.78f, -0.3f);
        PATH.quadTo(0f, -0.72f, 0.78f, -0.3f);
        PATH.quadTo(0.7f, 0.35f, 0f, 0.86f);
        PATH.quadTo(-0.7f, 0.35f, -0.78f, -0.3f);
        c.drawPath(PATH, FILL);
        paint(LIGHT);
        PATH.reset();
        PATH.moveTo(-0.7f, 0.08f);
        PATH.quadTo(-0.3f, 0.1f, 0f, 0.5f);
        PATH.quadTo(0.3f, 0.1f, 0.7f, 0.08f);
        PATH.quadTo(0.6f, 0.45f, 0f, 0.86f);
        PATH.quadTo(-0.6f, 0.45f, -0.7f, 0.08f);
        c.drawPath(PATH, FILL);
        eyes(c, 0.3f, -0.1f, 0.09f);
        circle(c, 0f, 0.7f, 0.1f, INK);
    }

    private static void owl(Canvas c) {
        int brown = 0xFF8B5E3C;
        tri(c, -0.62f, -0.5f, -0.5f, -0.92f, -0.2f, -0.6f, brown);
        tri(c, 0.62f, -0.5f, 0.5f, -0.92f, 0.2f, -0.6f, brown);
        oval(c, 0f, 0.05f, 0.72f, 0.82f, brown);
        oval(c, 0f, 0.42f, 0.42f, 0.4f, 0xFFD9B88F);
        circle(c, -0.3f, -0.2f, 0.3f, LIGHT);
        circle(c, 0.3f, -0.2f, 0.3f, LIGHT);
        circle(c, -0.3f, -0.2f, 0.14f, INK);
        circle(c, 0.3f, -0.2f, 0.14f, INK);
        circle(c, -0.26f, -0.25f, 0.045f, LIGHT);
        circle(c, 0.34f, -0.25f, 0.045f, LIGHT);
        tri(c, -0.1f, 0.02f, 0.1f, 0.02f, 0f, 0.22f, WARM);
    }

    private static void bear(Canvas c) {
        int brown = 0xFF9A6B45;
        circle(c, -0.5f, -0.55f, 0.24f, brown);
        circle(c, 0.5f, -0.55f, 0.24f, brown);
        circle(c, -0.5f, -0.55f, 0.12f, 0xFFD9B88F);
        circle(c, 0.5f, -0.55f, 0.12f, 0xFFD9B88F);
        circle(c, 0f, 0.05f, 0.68f, brown);
        oval(c, 0f, 0.3f, 0.32f, 0.26f, 0xFFD9B88F);
        eyes(c, 0.27f, -0.12f, 0.08f);
        oval(c, 0f, 0.2f, 0.11f, 0.08f, INK);
        smile(c, 0.34f, 0.12f);
    }

    private static void cat(Canvas c) {
        int grey = 0xFFB9B3AB;
        tri(c, -0.66f, -0.05f, -0.6f, -0.88f, -0.14f, -0.5f, grey);
        tri(c, 0.66f, -0.05f, 0.6f, -0.88f, 0.14f, -0.5f, grey);
        tri(c, -0.54f, -0.2f, -0.52f, -0.66f, -0.25f, -0.46f, 0xFFF4A9B4);
        tri(c, 0.54f, -0.2f, 0.52f, -0.66f, 0.25f, -0.46f, 0xFFF4A9B4);
        oval(c, 0f, 0.1f, 0.72f, 0.62f, grey);
        eyes(c, 0.3f, 0f, 0.09f);
        tri(c, -0.08f, 0.2f, 0.08f, 0.2f, 0f, 0.3f, 0xFFF4A9B4);
        for (int s = -1; s <= 1; s += 2) {
            line(c, s * 0.3f, 0.3f, s * 0.82f, 0.22f, 0.03f, LIGHT);
            line(c, s * 0.3f, 0.38f, s * 0.82f, 0.42f, 0.03f, LIGHT);
        }
        smile(c, 0.34f, 0.1f);
    }

    private static void dog(Canvas c) {
        int tan = 0xFFD9A066, dark = 0xFF8A5A33;
        oval(c, -0.66f, -0.1f, 0.2f, 0.46f, dark);
        oval(c, 0.66f, -0.1f, 0.2f, 0.46f, dark);
        oval(c, 0f, 0f, 0.58f, 0.7f, tan);
        oval(c, 0f, 0.34f, 0.34f, 0.28f, LIGHT);
        eyes(c, 0.26f, -0.12f, 0.08f);
        oval(c, 0f, 0.2f, 0.13f, 0.09f, INK);
        line(c, 0f, 0.28f, 0f, 0.42f, 0.04f, INK);
        oval(c, 0f, 0.56f, 0.1f, 0.12f, 0xFFF4A9B4);
    }

    private static void panda(Canvas c) {
        circle(c, -0.52f, -0.56f, 0.24f, INK);
        circle(c, 0.52f, -0.56f, 0.24f, INK);
        circle(c, 0f, 0.05f, 0.7f, LIGHT);
        PATH2.reset();
        oval(c, -0.28f, -0.08f, 0.17f, 0.22f, INK);
        oval(c, 0.28f, -0.08f, 0.17f, 0.22f, INK);
        circle(c, -0.27f, -0.1f, 0.06f, LIGHT);
        circle(c, 0.27f, -0.1f, 0.06f, LIGHT);
        oval(c, 0f, 0.22f, 0.11f, 0.08f, INK);
        smile(c, 0.36f, 0.1f);
    }

    private static void lion(Canvas c) {
        int mane = 0xFFB5651D;
        circle(c, 0f, 0.02f, 0.9f, mane);
        circle(c, 0f, 0.02f, 0.9f, SHADE);
        circle(c, 0f, 0.02f, 0.78f, mane);
        circle(c, -0.5f, -0.5f, 0.15f, 0xFFE0A24C);
        circle(c, 0.5f, -0.5f, 0.15f, 0xFFE0A24C);
        circle(c, 0f, 0.08f, 0.56f, 0xFFE0A24C);
        oval(c, 0f, 0.3f, 0.26f, 0.2f, LIGHT);
        eyes(c, 0.24f, -0.1f, 0.075f);
        tri(c, -0.1f, 0.14f, 0.1f, 0.14f, 0f, 0.26f, INK);
        smile(c, 0.36f, 0.09f);
    }

    private static void rocket(Canvas c) {
        tri(c, -0.2f, 0.25f, -0.62f, 0.78f, -0.2f, 0.62f, 0xFFFF5A5F);
        tri(c, 0.2f, 0.25f, 0.62f, 0.78f, 0.2f, 0.62f, 0xFFFF5A5F);
        tri(c, -0.14f, 0.68f, 0.14f, 0.68f, 0f, 1.05f, WARM);
        paint(LIGHT);
        PATH.reset();
        PATH.moveTo(0f, -0.9f);
        PATH.cubicTo(0.46f, -0.5f, 0.34f, 0.1f, 0.26f, 0.7f);
        PATH.lineTo(-0.26f, 0.7f);
        PATH.cubicTo(-0.34f, 0.1f, -0.46f, -0.5f, 0f, -0.9f);
        c.drawPath(PATH, FILL);
        circle(c, 0f, -0.2f, 0.17f, INK);
        circle(c, 0f, -0.2f, 0.12f, 0xFF7DD3FC);
        paint(0xFFFF5A5F);
        PATH.reset();
        PATH.moveTo(0f, -0.9f);
        PATH.cubicTo(0.2f, -0.74f, 0.28f, -0.6f, 0.3f, -0.5f);
        PATH.lineTo(-0.3f, -0.5f);
        PATH.cubicTo(-0.28f, -0.6f, -0.2f, -0.74f, 0f, -0.9f);
        c.drawPath(PATH, FILL);
    }

    private static void star(Canvas c) {
        paint(WARM);
        PATH.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? 0.86f : 0.38f;
            float x = (float) Math.cos(a) * rr, y = (float) Math.sin(a) * rr + 0.04f;
            if (i == 0) PATH.moveTo(x, y);
            else PATH.lineTo(x, y);
        }
        PATH.close();
        c.drawPath(PATH, FILL);
        eyes(c, 0.16f, 0.02f, 0.05f);
        smile(c, 0.2f, 0.1f);
    }

    private static void moon(Canvas c, int bg) {
        PATH.reset();
        PATH.addCircle(-0.06f, 0f, 0.66f, Path.Direction.CW);
        PATH2.reset();
        PATH2.addCircle(0.28f, -0.14f, 0.6f, Path.Direction.CW);
        PATH.op(PATH2, Path.Op.DIFFERENCE);
        paint(0xFFFFE9A8);
        c.drawPath(PATH, FILL);
        circle(c, 0.5f, 0.42f, 0.06f, LIGHT);
        circle(c, 0.64f, -0.5f, 0.045f, LIGHT);
        circle(c, 0.3f, 0.7f, 0.035f, LIGHT);
    }

    private static void sun(Canvas c) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            line(c, (float) Math.cos(a) * 0.56f, (float) Math.sin(a) * 0.56f, (float) Math.cos(a) * 0.82f, (float) Math.sin(a) * 0.82f, 0.1f, 0xFFFFF1B8);
        }
        circle(c, 0f, 0f, 0.44f, 0xFFFFD84D);
        eyes(c, 0.15f, -0.04f, 0.05f);
        smile(c, 0.1f, 0.14f);
    }

    private static void wave(Canvas c) {
        for (int layer = 0; layer < 2; layer++) {
            float y = 0.05f + layer * 0.34f;
            paint(layer == 0 ? 0xCCFFFFFF : 0xFFFFFFFF);
            PATH.reset();
            PATH.moveTo(-1.1f, y);
            PATH.cubicTo(-0.7f, y - 0.4f, -0.4f, y - 0.4f, -0.1f, y - 0.05f);
            PATH.cubicTo(0.2f, y + 0.3f, 0.5f, y + 0.3f, 1.1f, y - 0.2f);
            PATH.lineTo(1.1f, 1.1f);
            PATH.lineTo(-1.1f, 1.1f);
            PATH.close();
            c.drawPath(PATH, FILL);
        }
        circle(c, 0.4f, -0.5f, 0.13f, 0xFFFFF1B8);
    }
}
