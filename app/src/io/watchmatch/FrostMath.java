package io.watchmatch;

/** Pure pixel maths of Frost's sky texture (no Android). */
final class FrostMath {
    private FrostMath() {}

    static float smooth(float a, float b, float x) {
        float t = (x - a) / (b - a);
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        return t * t * (3 - 2 * t);
    }

    private static float frac(float v) { return v - (float) Math.floor(v); }

    private static float hash(float x, float y) {
        float fx = frac(x * 123.34f), fy = frac(y * 456.21f);
        float d = fx * (fx + 45.32f) + fy * (fy + 45.32f);
        return frac((fx + d) * (fy + d));
    }

    static float noise(float x, float y) {
        float ix = (float) Math.floor(x), iy = (float) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        float top = a + (b - a) * sx, bot = c + (d - c) * sx;
        return top + (bot - top) * sy;
    }

    private static float fbm(float x, float y) {
        return 0.6f * noise(x, y) + 0.3f * noise(2.03f * x + 17, 2.03f * y + 17);
    }

    /** Fills w*h ARGB pixels (opaque) for the sky at time t. */
    static void fill(int[] out, int w, int h, float t, float speed, float dim, float[] pal, float aspect) {
        float tt = t * 0.035f * speed;
        float gainDim = 1 - dim;
        for (int j = 0; j < h; j++) {
            float v = (j + 0.5f) / h;
            float band = smooth(0.02f, 0.38f, v) * (1 - smooth(0.5f, 0.92f, v));
            float scrim = 1 - 0.22f * (1 - smooth(0, 0.42f, v));
            float vertical = 1 - 0.38f * smooth(0.35f, 1, v);
            for (int i = 0; i < w; i++) {
                float u = (i + 0.5f) / w;
                float px = u * aspect * 1.7f, py = v * 1.7f;
                float qx = fbm(px, py + tt), qy = fbm(px + 5.2f - tt, py + 1.3f - tt);
                float x = px + 2 * qx + 1.7f + tt, y = py + 2 * qy + 9.2f;
                float n = 0.5f * noise(x, y) + 0.25f * noise(2.03f * x + 17, 2.03f * y + 17) + 0.09375f;
                float curtain = smooth(0.35f, 0.85f, n) * (0.35f + 0.65f * band);
                float body = smooth(0.15f, 0.85f, n);
                float vig = vertical - 0.2f * smooth(0.45f, 0.95f, Math.abs(u - 0.5f) * 1.6f);
                float gain = vig * scrim * gainDim;
                int px32 = 0xFF000000;
                for (int k = 0; k < 3; k++) {
                    float c0 = pal[k], c1 = pal[3 + k], c2 = pal[6 + k];
                    float col = c0 + (c1 - c0) * body;
                    col = (col + (c2 - col) * (curtain * 0.85f)) * gain;
                    col = col < 0 ? 0 : col > 1 ? 1 : col;
                    px32 |= Math.round(col * 255) << (16 - 8 * k);
                }
                out[j * w + i] = px32;
            }
        }
    }
}
