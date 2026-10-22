package io.watchmatch;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Supplier;

/** Fonts, text drawing, right-to-left mirroring, language, digits, clock and accent colours. */
final class Ui {
    private Ui() {}

    static final boolean LEGACY = Build.VERSION.SDK_INT < 31;
    static volatile boolean lite = Build.VERSION.SDK_INT <= 30;
    static volatile int digitMode;
    static final Locale PT = new Locale("pt", "BR");
    static volatile String language = language();
    static volatile boolean en, es, ar;
    static volatile boolean rtl;
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;

    static final String[] ACCENTS = {"white", "blue", "green", "orange", "pink", "purple", "yellow"};
    private static final int[] ACCENT_COLORS = {0xFFFFFFFF, 0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F,
            0xFFBF5AF2, 0xFFFFD60A};
    private static final long WEAK_RAM = 2_684_354_560L;
    private static final int ONCE_MAX = 200;

    private static boolean ready;
    private static Boolean weak;
    private static Locale arabicLocale;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();
    private static final HashMap<String, String> ONCE = new HashMap<>();

    static {
        applyLanguage();
    }

    // ---- setup -----------------------------------------------------------------------------

    static synchronized void init(Context c) {
        if (ready) return;
        AssetManager am = c.getApplicationContext().getAssets();
        thin = face(am, 32, 200);
        light = face(am, 32, 300);
        displayMedium = face(am, 32, 500);
        displaySemibold = face(am, 32, 600);
        regular = face(am, 14, 400);
        medium = face(am, 14, 500);
        semibold = face(am, 14, 600);
        ready = true;
    }

    private static Typeface face(AssetManager am, int opsz, int weight) {
        try {
            return new Typeface.Builder(am, "fonts/InterVariable.ttf")
                    .setWeight(weight)
                    .setFontVariationSettings("'opsz' " + opsz + ", 'wght' " + weight)
                    .build();
        } catch (RuntimeException e) {
            return Typeface.create(Typeface.DEFAULT, weight, false);
        }
    }

    private static String language() {
        return UiRules.resolveLanguage(Prefs.language(), Locale.getDefault().getLanguage());
    }

    static void applyLanguage() {
        String l = language();
        language = l;
        en = l.equals("en");
        es = l.equals("es");
        ar = l.equals("ar");
        rtl = ar;
        applyDigits();
    }

    static void applyDigits() {
        digitMode = UiRules.digitMode(Prefs.digits(), language);
    }

    static synchronized void applyLite(Context c) {
        if (weak == null) weak = probeWeak(c);
        String p = Prefs.performance();
        lite = "lite".equals(p) || ("auto".equals(p) && weak);
    }

    private static boolean probeWeak(Context c) {
        if (Build.VERSION.SDK_INT <= 30) return true;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am.isLowRamDevice()) return true;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem > 0 && mi.totalMem <= WEAK_RAM;
        } catch (RuntimeException e) {
            AppLog.w("lite probe: " + e.getMessage());
            return false;
        }
    }

    // ---- paints and measuring --------------------------------------------------------------

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    static float halfCap(Paint p) { return cap(p) / 2; }

    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) { return UiRules.alpha(color, a); }

    static float clamp(float v) { return UiRules.clamp(v); }

    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270, false, stroke);
    }

    // ---- right to left ---------------------------------------------------------------------

    static int dpad(int keyCode) { return UiRules.dpad(rtl, keyCode); }

    static int along(int dx) { return rtl ? -dx : dx; }

    static float mx(float x, float size, float total) { return UiRules.mx(rtl, x, size, total); }

    static void text(Canvas c, String s, float x, float y, Paint p, float total) {
        if (!rtl) {
            c.drawText(s, x, y, p);
            return;
        }
        Paint.Align old = p.getTextAlign();
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, total - x, y, p);
        p.setTextAlign(old);
    }

    static void textAt(Canvas c, String s, float x, float y, Paint p) {
        if (!rtl) {
            c.drawText(s, x, y, p);
            return;
        }
        Paint.Align old = p.getTextAlign();
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, x, y, p);
        p.setTextAlign(old);
    }

    static float lineRight(Layout l) {
        float best = 0;
        for (int i = 0; i < l.getLineCount(); i++) best = Math.max(best, l.getLineRight(i));
        return best;
    }

    static float layoutX(float x, float right, float total) { return UiRules.layoutX(rtl, x, right, total); }

    static StaticLayout layout(String text, TextPaint paint, float width, int lines) {
        StaticLayout.Builder b = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, Math.round(width)))
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 1.04f);
        if (!rtl) b.setTextDirection(TextDirectionHeuristics.LTR);
        return b.build();
    }

    // ---- localisation ----------------------------------------------------------------------

    static String tr(String pt, String english) {
        return brand(ar ? Arabic.text(english) : es ? Spanish.text(english) : en ? english : pt);
    }

    static String tr(String pt, String english, String spanish) {
        return brand(ar ? Arabic.text(english) : es ? spanish : en ? english : pt);
    }

    static String tr(String pt, String english, String spanish, String arabic) {
        return brand(ar ? arabic : es ? spanish : en ? english : pt);
    }

    static String brand(String s) {
        return UiRules.brand(s, Config.APP_NAME, Config.CATALOG_NAME, Config.SPARK_NAME, ar);
    }

    static String isolate(String s) { return UiRules.isolate(s, ar); }

    static String digits(String s) { return UiRules.digits(s, digitMode); }

    static Locale locale() {
        if (ar) return arabic();
        if (es) {
            Locale d = Locale.getDefault();
            return d.getLanguage().equals("es") ? d : new Locale("es", "ES");
        }
        return en ? Locale.US : PT;
    }

    private static synchronized Locale arabic() {
        if (arabicLocale == null) {
            try {
                Locale d = Locale.getDefault();
                Locale base = d.getLanguage().equals("ar") ? d : new Locale("ar");
                arabicLocale = new Locale.Builder().setLocale(base).setUnicodeLocaleKeyword("nu", "latn").build();
            } catch (RuntimeException e) {
                arabicLocale = new Locale("ar");
            }
        }
        return arabicLocale;
    }

    static String clock(long now) {
        boolean twelve = UiRules.twelveHour(Prefs.clock(), en);
        return digits(new SimpleDateFormat(UiRules.clockPattern(twelve), en ? Locale.US : locale())
                .format(new Date(now)));
    }

    static String date(long now) {
        return digits(new SimpleDateFormat(UiRules.datePattern(language), en ? Locale.US : locale())
                .format(new Date(now)));
    }

    static String time(long at) {
        return once("time", at, () -> new SimpleDateFormat(UiRules.timePattern(en), en ? Locale.US : locale())
                .format(new Date(at)));
    }

    static String once(String kind, double value, Supplier<String> format) {
        String key = UiRules.onceKey(kind, language, digitMode, value);
        synchronized (ONCE) {
            String hit = ONCE.get(key);
            if (hit != null) return hit;
        }
        String made = digits(format.get());
        synchronized (ONCE) {
            if (ONCE.size() > ONCE_MAX) ONCE.clear();
            ONCE.put(key, made);
        }
        return made;
    }

    // ---- accent ----------------------------------------------------------------------------

    static int accent() {
        String a = Prefs.accent();
        for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i].equals(a)) return ACCENT_COLORS[i];
        return ACCENT_COLORS[0];
    }

    static String accentName(String a) {
        if ("blue".equals(a)) return tr("Azul", "Blue", "Azul", "أزرق");
        if ("green".equals(a)) return tr("Verde", "Green", "Verde", "أخضر");
        if ("orange".equals(a)) return tr("Laranja", "Orange", "Naranja", "برتقالي");
        if ("pink".equals(a)) return tr("Rosa", "Pink", "Rosa", "وردي");
        if ("purple".equals(a)) return tr("Roxo", "Purple", "Morado", "بنفسجي");
        if ("yellow".equals(a)) return tr("Amarelo", "Yellow", "Amarillo", "أصفر");
        return tr("Branco", "White", "Blanco", "أبيض");
    }
}
