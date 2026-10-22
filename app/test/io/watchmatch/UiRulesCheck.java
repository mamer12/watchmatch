package io.watchmatch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Plain-Java self-checks for UiRules and FrostMath. */
public final class UiRulesCheck {
    private static int failures;

    private static void eq(String name, Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + name + ": want <" + want + "> got <" + got + ">");
        }
    }

    public static void main(String[] a) {
        // language resolution
        eq("lang explicit", "es", UiRules.resolveLanguage("es", "ar"));
        eq("lang auto ar", "ar", UiRules.resolveLanguage("auto", "ar"));
        eq("lang auto pt", "pt", UiRules.resolveLanguage("auto", "pt"));
        eq("lang auto other", "en", UiRules.resolveLanguage("auto", "fr"));
        // tr order
        eq("pick ar", 3, UiRules.pickIndex(true, true, true));
        eq("pick es", 2, UiRules.pickIndex(false, true, true));
        eq("pick en", 1, UiRules.pickIndex(false, false, true));
        eq("pick pt", 0, UiRules.pickIndex(false, false, false));
        // digits
        eq("mode ar auto", 2, UiRules.digitMode("auto", "ar"));
        eq("mode en auto", 0, UiRules.digitMode("auto", "en"));
        eq("mode western", 1, UiRules.digitMode("western", "ar"));
        eq("mode arabic", 2, UiRules.digitMode("arabic", "en"));
        eq("digits arabic", "١٢:٠٥", UiRules.digits("12:05", 2));
        eq("digits western from arabic", "12", UiRules.digits("١٢", 1));
        eq("digits persian", "7", UiRules.digits("۷", 1));
        eq("digits mode0", "12", UiRules.digits("12", 0));
        String same = "abc";
        if (UiRules.digits(same, 1) != same) { failures++; System.out.println("FAIL digits identity"); }
        String west = "a1";
        if (UiRules.digits(west, 1) != west) { failures++; System.out.println("FAIL digits identity west"); }
        eq("digits null", null, UiRules.digits(null, 2));
        // brand / isolate
        eq("brand en", "Hi WatchMatch / Watchme+ / Spark", UiRules.brand("Hi {app} / {catalog} / {spark}", "WatchMatch", "Watchme+", "Spark", false));
        eq("brand ar", "⁨App⁩ x", UiRules.brand("{app} x", "App", "C", "S", true));
        String plain = "none";
        if (UiRules.brand(plain, "A", "B", "C", true) != plain) { failures++; System.out.println("FAIL brand identity"); }
        eq("brand null", null, UiRules.brand(null, "A", "B", "C", true));
        eq("isolate empty", "", UiRules.isolate("", true));
        eq("isolate null", null, UiRules.isolate(null, true));
        eq("isolate off", "x", UiRules.isolate("x", false));
        // clock and date
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        long t = 1_760_814_420_000L; // 2025-10-18 19:07 UTC, a Saturday
        eq("clock 24", "19:07", new SimpleDateFormat(UiRules.clockPattern(false), Locale.US).format(new Date(t)));
        eq("clock 12", "7:07", new SimpleDateFormat(UiRules.clockPattern(true), Locale.US).format(new Date(t)));
        eq("twelve auto en", true, UiRules.twelveHour("auto", true));
        eq("twelve auto pt", false, UiRules.twelveHour("auto", false));
        eq("twelve 12", true, UiRules.twelveHour("12", false));
        eq("twelve 24", false, UiRules.twelveHour("24", true));
        eq("date en", "Saturday, October 18", new SimpleDateFormat(UiRules.datePattern("en"), Locale.US).format(new Date(t)));
        eq("date pt", "sábado, 18 de outubro", new SimpleDateFormat(UiRules.datePattern("pt"), new Locale("pt", "BR")).format(new Date(t)).replace("-feira", ""));
        eq("date ar pattern", "EEEE، d MMMM", UiRules.datePattern("ar"));
        eq("time en", "h:mm a", UiRules.timePattern(true));
        eq("once key", "brl|pt0|1.5", UiRules.onceKey("brl", "pt", 0, 1.5));
        // mirroring
        eq("mx ltr", 10f, UiRules.mx(false, 10, 30, 100));
        eq("mx rtl", 60f, UiRules.mx(true, 10, 30, 100));
        eq("layoutX rtl", 55f, UiRules.layoutX(true, 10, 35, 100));
        eq("dpad rtl L", UiRules.KEY_RIGHT, UiRules.dpad(true, UiRules.KEY_LEFT));
        eq("dpad rtl R", UiRules.KEY_LEFT, UiRules.dpad(true, UiRules.KEY_RIGHT));
        eq("dpad ltr", UiRules.KEY_LEFT, UiRules.dpad(false, UiRules.KEY_LEFT));
        eq("dpad other", 19, UiRules.dpad(true, 19));
        eq("alpha", 0x40FFFFFF, UiRules.alpha(0x80FFFFFF, 0.5f));
        eq("alpha clamp", 0x80123456, UiRules.alpha(0x80123456, 3f));
        eq("alpha zero", 0x00123456, UiRules.alpha(0x80123456, -1f));
        // frost
        float[] pal = {0.03f, 0.04f, 0.1f, 0.1f, 0.15f, 0.35f, 0.3f, 0.2f, 0.55f};
        int[] p1 = new int[48 * 27], p2 = new int[48 * 27], p3 = new int[48 * 27];
        FrostMath.fill(p1, 48, 27, 12.5f, 0.4f, 0.3f, pal, 16f / 9);
        FrostMath.fill(p2, 48, 27, 12.5f, 0.4f, 0.3f, pal, 16f / 9);
        FrostMath.fill(p3, 48, 27, 400f, 0.4f, 0.3f, pal, 16f / 9);
        eq("frost deterministic", true, java.util.Arrays.equals(p1, p2));
        eq("frost animates", false, java.util.Arrays.equals(p1, p3));
        boolean opaque = true, varied = false;
        for (int p : p1) { opaque &= (p >>> 24) == 255; varied |= p != p1[0]; }
        eq("frost opaque", true, opaque);
        eq("frost varied", true, varied);
        int[] dark = new int[48 * 27];
        FrostMath.fill(dark, 48, 27, 12.5f, 0.4f, 1f, pal, 16f / 9);
        eq("frost dim 1 is black", 0xFF000000, dark[500]);

        System.out.println(failures == 0 ? "UiRulesCheck: all passed" : "UiRulesCheck: " + failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
