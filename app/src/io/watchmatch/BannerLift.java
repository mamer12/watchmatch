package io.watchmatch;

/**
 * Lifts a logo off its Android-TV banner: guesses the banner's background from its four edges (gradients included)
 * and keeps what differs from it, with soft edges. Works on plain pixel arrays (no Android) so it can be checked on a
 * JVM; HomeApps does the drawing in and out.
 */
final class BannerLift {
    private BannerLift() {}

    /** The lifted logo: ARGB pixels (not premultiplied), trimmed to the ink. */
    static final class Result {
        final int[] pixels;
        final int width, height;

        Result(int[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    private static final float EDGE_BAND = 0.03f, ALPHA_FROM = 0.07f, ALPHA_TO = 0.22f, MIN_ALPHA = 0.004f;
    private static final int SIDE_WINDOW = 25, TOP_WINDOW = 41;

    /** `mode` is "play" (fill the enclosed play triangle), "tint" (recolour white to the brand) or null. */
    static Result lift(int[] argb, int w, int h, String mode) {
        float[][] left = new float[h][3], right = new float[h][3], top = new float[w][3], bottom = new float[w][3];
        edges(argb, w, h, left, right, top, bottom);
        smooth(left, SIDE_WINDOW);
        smooth(right, SIDE_WINDOW);
        smooth(top, TOP_WINDOW);
        smooth(bottom, TOP_WINDOW);

        float[] bg = new float[3], avg = new float[3];
        int[] out = new int[w * h];
        float[] alpha = new float[w * h];
        // First pass: the background under every pixel and its average, which the colour rules need.
        float[] field = new float[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                background(left, right, top, bottom, x, y, w, h, bg);
                int i = (y * w + x) * 3;
                for (int k = 0; k < 3; k++) {
                    field[i + k] = bg[k];
                    avg[k] += bg[k];
                }
            }
        }
        for (int k = 0; k < 3; k++) avg[k] /= w * h;
        float bgLum = lum(avg[0], avg[1], avg[2]), bgSat = sat(avg[0], avg[1], avg[2]);
        boolean lightBg = bgLum > 0.6f;
        boolean tint = "tint".equals(mode) && bgLum > 0.1f && bgLum <= 0.6f && bgSat > 0.35f;
        float[] hsv = rgbToHsv(avg[0], avg[1], avg[2]);

        int opaque = 0;
        for (int p = 0; p < w * h; p++) {
            int c = argb[p];
            float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
            float br = field[p * 3], bgc = field[p * 3 + 1], bb = field[p * 3 + 2];
            float d = (float) Math.sqrt(((r - br) * (r - br) + (g - bgc) * (g - bgc) + (b - bb) * (b - bb)) / 3);
            float a = smoothstep(ALPHA_FROM, ALPHA_TO, d);
            if (a <= MIN_ALPHA) continue;
            // Take the background out of the blend, so no halo is left around the shapes.
            r = clamp((r - br * (1 - a)) / a);
            g = clamp((g - bgc * (1 - a)) / a);
            b = clamp((b - bb * (1 - a)) / a);
            float l = lum(r, g, b), s = sat(r, g, b);
            if (lightBg && l < 0.42f && s < 0.3f) {
                r = g = b = 1;
            } else if (tint && l > 0.7f && s < 0.25f) {
                int rgb = hsvToRgb(hsv[0], Math.max(hsv[1], 0.55f), 1);
                r = ((rgb >> 16) & 255) / 255f;
                g = ((rgb >> 8) & 255) / 255f;
                b = (rgb & 255) / 255f;
            }
            alpha[p] = a;
            if (a > 0.5f) opaque++;
            out[p] = pack(a, r, g, b);
        }
        float share = (float) opaque / (w * h);
        if (share < 0.01f || share > 0.6f) return null;
        if ("play".equals(mode)) fillEnclosed(out, alpha, w, h);
        return trim(out, w, h);
    }

    // Edges and background

    private static void edges(int[] px, int w, int h, float[][] left, float[][] right, float[][] top, float[][] bottom) {
        int bw = Math.max(1, Math.round(w * EDGE_BAND)), bh = Math.max(1, Math.round(h * EDGE_BAND));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < bw; x++) {
                add(left[y], px[y * w + x]);
                add(right[y], px[y * w + w - 1 - x]);
            }
            scale(left[y], 1f / bw);
            scale(right[y], 1f / bw);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < bh; y++) {
                add(top[x], px[y * w + x]);
                add(bottom[x], px[(h - 1 - y) * w + x]);
            }
            scale(top[x], 1f / bh);
            scale(bottom[x], 1f / bh);
        }
    }

    private static void add(float[] sum, int c) {
        sum[0] += ((c >> 16) & 255) / 255f;
        sum[1] += ((c >> 8) & 255) / 255f;
        sum[2] += (c & 255) / 255f;
    }

    private static void scale(float[] v, float f) {
        v[0] *= f;
        v[1] *= f;
        v[2] *= f;
    }

    /** Moving average along the array (a window shorter at the ends). */
    private static void smooth(float[][] v, int window) {
        int n = v.length, half = window / 2;
        float[][] src = new float[n][];
        for (int i = 0; i < n; i++) src[i] = v[i].clone();
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - half), to = Math.min(n - 1, i + half);
            for (int k = 0; k < 3; k++) {
                float s = 0;
                for (int j = from; j <= to; j++) s += src[j][k];
                v[i][k] = s / (to - from + 1);
            }
        }
    }

    /** Left-right blend plus top-bottom blend, less the corners' own blend (counted twice otherwise). */
    private static void background(float[][] left, float[][] right, float[][] top, float[][] bottom,
                                   int x, int y, int w, int h, float[] out) {
        float u = w > 1 ? (float) x / (w - 1) : 0, v = h > 1 ? (float) y / (h - 1) : 0;
        int last = h - 1;
        for (int k = 0; k < 3; k++) {
            float horizontal = left[y][k] * (1 - u) + right[y][k] * u;
            float vertical = top[x][k] * (1 - v) + bottom[x][k] * v;
            float cornersTop = left[0][k] * (1 - u) + right[0][k] * u;
            float cornersBottom = left[last][k] * (1 - u) + right[last][k] * u;
            out[k] = clamp(horizontal + vertical - (cornersTop * (1 - v) + cornersBottom * v));
        }
    }

    // Holes

    /** A transparent region closed in by colour (YouTube's play triangle) and small becomes white. */
    private static void fillEnclosed(int[] out, float[] alpha, int w, int h) {
        int n = w * h;
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            tail = seed(alpha, seen, queue, tail, x);
            tail = seed(alpha, seen, queue, tail, (h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            tail = seed(alpha, seen, queue, tail, y * w);
            tail = seed(alpha, seen, queue, tail, y * w + w - 1);
        }
        while (head < tail) {
            int p = queue[head++];
            tail = grow(out, alpha, seen, queue, tail, p, w, h, null);
        }
        int[] region = new int[n];
        for (int start = 0; start < n; start++) {
            if (seen[start] || alpha[start] > MIN_ALPHA) continue;
            int size = 0, rh = 0, rt = 0;
            float[] borders = new float[2];   // count, saturation sum
            seen[start] = true;
            region[rt++] = start;
            while (rh < rt) {
                int p = region[rh++];
                size++;
                rt = grow(out, alpha, seen, region, rt, p, w, h, borders);
            }
            if (size < n * 0.05f && borders[0] > 0 && borders[1] / borders[0] > 0.45f) {
                for (int i = 0; i < rt; i++) {
                    out[region[i]] = 0xFFFFFFFF;
                    alpha[region[i]] = 1;
                }
            }
        }
    }

    private static int seed(float[] alpha, boolean[] seen, int[] queue, int tail, int p) {
        if (seen[p] || alpha[p] > MIN_ALPHA) return tail;
        seen[p] = true;
        queue[tail++] = p;
        return tail;
    }

    /** Queues the transparent neighbours of p; solid ones add their saturation to `borders` when it is given. */
    private static int grow(int[] out, float[] alpha, boolean[] seen, int[] queue, int tail, int p, int w, int h, float[] borders) {
        int x = p % w, y = p / w;
        int[] around = {x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1};
        for (int q : around) {
            if (q < 0) continue;
            if (alpha[q] > MIN_ALPHA) {
                if (borders != null) {
                    borders[0]++;
                    int c = out[q];
                    borders[1] += sat(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
                }
                continue;
            }
            if (seen[q]) continue;
            seen[q] = true;
            queue[tail++] = q;
        }
        return tail;
    }

    // Trim

    private static Result trim(int[] px, int w, int h) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) == 0) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) return null;
        int pad = Math.max(2, Math.round(Math.max(x1 - x0 + 1, y1 - y0 + 1) * 0.02f));
        int ow = x1 - x0 + 1 + 2 * pad, oh = y1 - y0 + 1 + 2 * pad;
        int[] out = new int[ow * oh];
        for (int y = y0; y <= y1; y++) System.arraycopy(px, y * w + x0, out, (y - y0 + pad) * ow + pad, x1 - x0 + 1);
        return new Result(out, ow, oh);
    }

    // Colour

    private static int pack(float a, float r, float g, float b) {
        return (Math.round(a * 255) << 24) | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float smoothstep(float from, float to, float v) {
        float t = clamp((v - from) / (to - from));
        return t * t * (3 - 2 * t);
    }

    static float lum(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    static float sat(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max <= 0 ? 0 : (max - min) / max;
    }

    private static float[] rgbToHsv(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, hue = 0;
        if (d > 0) {
            if (max == r) hue = ((g - b) / d) % 6;
            else if (max == g) hue = (b - r) / d + 2;
            else hue = (r - g) / d + 4;
            hue *= 60;
            if (hue < 0) hue += 360;
        }
        return new float[] {hue, max <= 0 ? 0 : d / max, max};
    }

    private static int hsvToRgb(float hue, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((hue / 60) % 2 - 1)), m = v - c, r, g, b;
        int sector = (int) (hue / 60) % 6;
        switch (sector) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x;
        }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }
}
