package io.watchmatch;

import android.graphics.RuntimeShader;

import java.util.Calendar;

/** AGSL programs for the sky and the glass (Android 13+), and the sky's time-of-day colours. */
final class Shaders {
    private Shaders() {}

    // ---- Palettes

    /** Three RGB tones, 0..1: background, middle, highlight. */
    static final class Palette {
        final float[] c = new float[9];

        Palette set(int a, int b, int d) {
            int[] in = {a, b, d};
            for (int i = 0; i < 3; i++) {
                c[i * 3] = ((in[i] >> 16) & 255) / 255f;
                c[i * 3 + 1] = ((in[i] >> 8) & 255) / 255f;
                c[i * 3 + 2] = (in[i] & 255) / 255f;
            }
            return this;
        }

        int color(int i) {
            return 0xFF000000 | Math.round(c[i * 3] * 255) << 16 | Math.round(c[i * 3 + 1] * 255) << 8
                    | Math.round(c[i * 3 + 2] * 255);
        }

        float brightest() {
            float best = 0;
            for (int i = 0; i < 3; i++) {
                best = Math.max(best, 0.2126f * c[i * 3] + 0.7152f * c[i * 3 + 1] + 0.0722f * c[i * 3 + 2]);
            }
            return best;
        }

        void apply(RuntimeShader s) {
            s.setFloatUniform("c0", c[0], c[1], c[2]);
            s.setFloatUniform("c1", c[3], c[4], c[5]);
            s.setFloatUniform("c2", c[6], c[7], c[8]);
        }
    }

    private static final int[] NIGHT = {0x070B1E, 0x16225A, 0x4B2F8F};
    private static final int[] DAWN = {0x24183F, 0xE89A55, 0xF6D59A};
    private static final int[] DAY = {0x1E57C8, 0x4FB9EC, 0x9EE2EE};
    private static final int[] SUNSET = {0x2E1650, 0xE2546C, 0xF39A4F};
    private static final int[] DUSK = {0x10173A, 0x36348A, 0x8A4BB0};
    private static final int[][] STOPS = {NIGHT, DAWN, DAY, DAY, SUNSET, DUSK, NIGHT};
    private static final long MIN = 60_000L;
    // Minutes from sunrise (first three) and from sunset (the rest) at which each palette is exact.
    private static final long[] AT = {-60, -15, 45, -45, 0, 45, 105};

    /** The sky for `now`; sunrise and sunset only lend their clock time (0 = unknown). */
    static void sky(Palette out, long now, long sunrise, long sunset) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        long rise = midnight + timeOfDay(sunrise, 5 * 60 * MIN + 30 * MIN);
        long set = midnight + timeOfDay(sunset, 17 * 60 * MIN + 40 * MIN);
        long[] times = new long[7];
        for (int i = 0; i < 7; i++) times[i] = (i < 3 ? rise : set) + AT[i] * MIN;
        if (now <= times[0] || now >= times[6]) {
            night(out);
            return;
        }
        int seg = 0;
        while (seg < 5 && now >= times[seg + 1]) seg++;
        float f = (now - times[seg]) / (float) (times[seg + 1] - times[seg]);
        int[] a = STOPS[seg], b = STOPS[seg + 1], mixed = new int[3];
        for (int i = 0; i < 3; i++) mixed[i] = blend(a[i], b[i], f);
        out.set(mixed[0], mixed[1], mixed[2]);
    }

    /** Milliseconds since local midnight of `at`, to the second; `fallback` when `at` is 0. */
    static long timeOfDay(long at, long fallback) {
        if (at == 0) return fallback;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(at);
        return (cal.get(Calendar.HOUR_OF_DAY) * 3600L + cal.get(Calendar.MINUTE) * 60L + cal.get(Calendar.SECOND)) * 1000L;
    }

    static void dream(Palette out) {
        night(out);
    }

    private static void night(Palette out) {
        out.set(NIGHT[0], NIGHT[1], NIGHT[2]);
    }

    private static int blend(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return r << 16 | g << 8 | bl;
    }

    // ---- AGSL

    private static final String HASH =
            "float hash(float2 p) {\n"
            + "  p = fract(p * float2(123.34, 456.21));\n"
            + "  p += dot(p, p + 45.32);\n"
            + "  return fract(p.x * p.y);\n"
            + "}\n";

    private static final String SDF =
            "float boxDist(float2 d, float2 halfSize, float radius) {\n"
            + "  float2 q = abs(d) - halfSize + radius;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "}\n";

    private static final String SKY =
            "uniform float2 res;\n"
            + "uniform float t, speed, dim;\n"
            + "uniform half3 c0, c1, c2;\n"
            + HASH
            + "float noise(float2 p) {\n"
            + "  float2 i = floor(p);\n"
            + "  float2 f = fract(p);\n"
            + "  float2 u = f * f * (3.0 - 2.0 * f);\n"
            + "  float a = hash(i);\n"
            + "  float b = hash(i + float2(1.0, 0.0));\n"
            + "  float c = hash(i + float2(0.0, 1.0));\n"
            + "  float d = hash(i + float2(1.0, 1.0));\n"
            + "  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);\n"
            + "}\n"
            + "float fbm2(float2 p) {\n"
            + "  return 0.6 * noise(p) + 0.3 * noise(p * 2.03 + 17.0);\n"
            + "}\n"
            + "float fbm(float2 p0, float detail) {\n"
            + "  float2 p = p0;\n"
            + "  float s = 0.5 * noise(p);\n"
            + "  p = p * 2.03 + 17.0;\n"
            + "  s += 0.25 * noise(p);\n"
            + "  if (detail < 0.5) return s + 0.09375;\n"
            + "  p = p * 2.03 + 17.0;\n"
            + "  s += 0.125 * noise(p);\n"
            + "  p = p * 2.03 + 17.0;\n"
            + "  return s + 0.0625 * noise(p);\n"
            + "}\n"
            + "float3 aurora(float2 uv, float detail) {\n"
            + "  float2 p = float2(uv.x * res.x / res.y, uv.y) * 1.7;\n"
            + "  float tt = t * 0.035 * speed;\n"
            + "  float2 q = float2(fbm2(p + float2(0.0, tt)), fbm2(p + float2(5.2, 1.3) - tt));\n"
            + "  float n = fbm(p + 2.0 * q + float2(1.7 + tt, 9.2), detail);\n"
            + "  float band = smoothstep(0.02, 0.38, uv.y) * (1.0 - smoothstep(0.5, 0.92, uv.y));\n"
            + "  float curtain = smoothstep(0.35, 0.85, n) * (0.35 + 0.65 * band);\n"
            + "  float3 col = mix(float3(c0), float3(c1), smoothstep(0.15, 0.85, n));\n"
            + "  col = mix(col, float3(c2), curtain * 0.85);\n"
            + "  float vig = 1.0 - 0.38 * smoothstep(0.35, 1.0, uv.y)\n"
            + "      - 0.2 * smoothstep(0.45, 0.95, abs(uv.x - 0.5) * 1.6);\n"
            + "  float scrim = 1.0 - 0.22 * (1.0 - smoothstep(0.0, 0.42, uv.y));\n"
            + "  return col * vig * scrim * (1.0 - dim);\n"
            + "}\n";

    static final String AURORA = SKY
            + "uniform float2 layer;\n"
            + "half4 main(float2 xy) {\n"
            + "  float3 col = aurora(xy / layer, 1.0) + (hash(xy) - 0.5) / 255.0;\n"
            + "  return half4(half3(col), 1.0);\n"
            + "}\n";

    static final String GLASS =
            "uniform shader frost;\n"
            + "uniform float2 frostScale, offset, center, halfSize, invHalf;\n"
            + "uniform float radius, top, lift, body;\n"
            + "uniform float4 depth;\n"
            + "uniform half4 tint, glow;\n"
            + HASH + SDF
            + "half4 main(float2 xy) {\n"
            + "  float2 d = xy - center;\n"
            + "  float sd = boxDist(d, halfSize, radius);\n"
            + "  float aa = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "  if (aa <= 0.0) return half4(0.0);\n"
            + "  float2 dir = d * invHalf;\n"
            + "  float ty = xy.y - top;\n"
            + "  float rim = 1.0 - clamp(-sd * 0.4545, 0.0, 1.0);\n"
            + "  float light = clamp(-dir.y * 0.75 - dir.x * 0.25 + 0.3, 0.0, 1.0);\n"
            + "  float sheen = (1.0 - clamp(ty * depth.z, 0.0, 1.0)) * 0.06;\n"
            + "  float hl = rim * (0.08 + 0.5 * light) + sheen * body;\n"
            + "  float3 col = float3(0.0);\n"
            + "  if (body > 0.0) {\n"
            + "    float edge = 1.0 - clamp(-sd * depth.x, 0.0, 1.0);\n"
            + "    float2 sxy = xy - dir * (edge * edge * depth.y);\n"
            + "    float3 bg = frost.eval((sxy + offset) * frostScale).rgb * 1.12 + 0.02;\n"
            + "    col = mix(bg, float3(tint.rgb), float(tint.a));\n"
            + "    col = mix(col, float3(glow.rgb), float(glow.a) * smoothstep(0.1, 1.0, ty * depth.w));\n"
            + "    col += lift;\n"
            + "    col *= 1.0 - 0.2 * (1.0 - clamp(-sd * 0.0625, 0.0, 1.0)) * clamp(dir.y, 0.0, 1.0);\n"
            + "    col += (hash(xy) - 0.5) * 0.0039;\n"
            + "  }\n"
            + "  float a = body + hl * (1.0 - body);\n"
            + "  return half4(half3((col * body + hl) * aa), half(a * aa));\n"
            + "}\n";

    static final String LIQUID =
            "uniform shader imageA, imageB;\n"
            + "uniform float mixB, radius, lift, band, refraction, specular;\n"
            + "uniform float2 imageScale, offset, center, halfSize;\n"
            + "uniform half4 tint;\n"
            + HASH + SDF
            + "float3 bgAt(float2 p) {\n"
            + "  float2 s = p * imageScale;\n"
            + "  float3 a = imageA.eval(s).rgb;\n"
            + "  if (mixB > 0.0) a = mix(a, imageB.eval(s).rgb, mixB);\n"
            + "  return a;\n"
            + "}\n"
            + "half4 main(float2 xy) {\n"
            + "  float2 d = xy - center;\n"
            + "  float sd = boxDist(d, halfSize, radius);\n"
            + "  float aa = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "  if (aa <= 0.0) return half4(0.0);\n"
            + "  float2 sg = float2(d.x < 0.0 ? -1.0 : 1.0, d.y < 0.0 ? -1.0 : 1.0);\n"
            + "  float2 q = abs(d) - halfSize + radius;\n"
            + "  float2 n;\n"
            + "  if (q.x > 0.0 && q.y > 0.0) n = normalize(q) * sg;\n"
            + "  else n = q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y);\n"
            + "  float inner = -sd;\n"
            + "  float e = clamp(inner / band, 0.0, 1.0);\n"
            + "  float bend = (1.0 - e) * (1.0 - e);\n"
            + "  float2 p = xy + offset - n * (bend * refraction);\n"
            + "  float3 bg;\n"
            + "  if (bend > 0.01) {\n"
            + "    float2 ca = n * (bend * refraction * 0.22);\n"
            + "    bg = float3(bgAt(p + ca).r, bgAt(p).g, bgAt(p - ca).b);\n"
            + "  } else {\n"
            + "    bg = bgAt(p);\n"
            + "  }\n"
            + "  float l = dot(bg, float3(0.2126, 0.7152, 0.0722));\n"
            + "  bg = mix(float3(l), bg, 1.18) * 1.06;\n"
            + "  float3 col = mix(bg, float3(tint.rgb), float(tint.a)) + lift;\n"
            + "  float ny = d.y / halfSize.y;\n"
            + "  col *= 1.0 - 0.12 * smoothstep(0.1, 1.0, ny);\n"
            + "  col += 0.045 * (1.0 - smoothstep(-1.0, -0.15, ny));\n"
            + "  float2 L = float2(-0.4, -0.917);\n"
            + "  float facing = clamp(dot(n, L), 0.0, 1.0);\n"
            + "  float back = clamp(-dot(n, L), 0.0, 1.0);\n"
            + "  float rim = 1.0 - smoothstep(0.0, 2.4, inner);\n"
            + "  float halo = 1.0 - smoothstep(0.0, 11.0, inner);\n"
            + "  float spec = rim * (0.22 + 0.78 * facing + 0.4 * back) + halo * (0.12 * facing + 0.05 * back);\n"
            + "  col += spec * specular;\n"
            + "  col += (hash(xy) - 0.5) * 0.0039;\n"
            + "  return half4(half3(col * aa), half(aa));\n"
            + "}\n";

    static final String GLASS_TEXT =
            "uniform shader imageA, imageB, slope, cover;\n"
            + "uniform float3 placeA, placeB;\n"
            + "uniform float mixB, refraction, specular, alpha;\n"
            + "uniform float2 origin, ink;\n"
            + "float3 photo(float2 p) {\n"
            + "  float3 a = imageA.eval((p - placeA.xy) / placeA.z).rgb;\n"
            + "  if (mixB > 0.0) a = mix(a, imageB.eval((p - placeB.xy) / placeB.z).rgb, mixB);\n"
            + "  return a;\n"
            + "}\n"
            + "half4 main(float2 xy) {\n"
            + "  float2 local = xy - origin;\n"
            + "  float a = cover.eval(local).a;\n"
            + "  if (a <= 0.0) return half4(0.0);\n"
            + "  float3 s = slope.eval(local).rgb;\n"
            // R and G are both centred on 0.5 (flat), so both are re-centred here.
            + "  float2 n = (s.rg - 0.5) * 2.0;\n"
            + "  float bend = length(n);\n"
            + "  float height = s.b;\n"
            + "  float2 p = xy + n * refraction;\n"
            + "  float3 bg;\n"
            + "  if (bend > 0.02) {\n"
            + "    float2 ca = n * (refraction * 0.2);\n"
            + "    bg = float3(photo(p + ca).r, photo(p).g, photo(p - ca).b);\n"
            + "  } else {\n"
            + "    bg = photo(p);\n"
            + "  }\n"
            + "  float l = dot(bg, float3(0.2126, 0.7152, 0.0722));\n"
            + "  bg = mix(float3(l), bg, 1.15);\n"
            + "  float ty = clamp((local.y - ink.x) / ink.y, 0.0, 1.0);\n"
            + "  float3 col = bg * 0.86 + (0.07 + 0.11 * (1.0 - ty));\n"
            + "  float2 dir = n / max(bend, 0.001);\n"
            + "  float2 L = float2(-0.447, -0.894);\n"
            + "  float facing = clamp(-dot(dir, L), 0.0, 1.0);\n"
            + "  float back = clamp(dot(dir, L), 0.0, 1.0);\n"
            + "  float edge = smoothstep(0.05, 0.35, bend);\n"
            + "  float rim = (1.0 - smoothstep(0.5, 0.6, height)) * edge;\n"
            + "  float glow = (1.0 - smoothstep(0.5, 0.86, height)) * edge;\n"
            + "  col += (rim * (0.28 + 0.9 * facing + 0.45 * back) + glow * (0.05 + 0.1 * facing)) * specular;\n"
            + "  col *= 1.0 - 0.1 * back * bend * (1.0 - rim);\n"
            + "  float k = a * alpha;\n"
            + "  return half4(half3(col * k), half(k));\n"
            + "}\n";
}
