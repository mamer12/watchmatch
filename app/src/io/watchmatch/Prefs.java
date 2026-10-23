package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.List;

/**
 * What the person sets on the TV, in one SharedPreferences file. Those values win over the first ones compiled from
 * config.properties (Config). `version` goes up with every write, so a screen can tell when to redraw.
 */
final class Prefs {
    private Prefs() {}

    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** Settings lists index the remote's buttons by position in this array. */
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    static volatile int version;

    private static Context app;
    private static SharedPreferences store;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();
        Ui.applyLite(app);
        A11y.apply();
    }

    private static synchronized SharedPreferences sp() {
        if (store == null) store = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return store;
    }

    private static String str(String key, String def) {
        return app == null ? def : sp().getString(key, def);
    }

    private static boolean bool(String key, boolean def) {
        return app == null ? def : sp().getBoolean(key, def);
    }

    private static void put(SharedPreferences.Editor e) {
        Durable.save(sp(), e);
        version++;
    }

    private static void putString(String key, String value) {
        put(sp().edit().putString(key, value));
    }

    private static void putBool(String key, boolean value) {
        put(sp().edit().putBoolean(key, value));
    }

    // Language, units and look

    static String language() {
        return PrefsRules.language(str("language", Config.LANGUAGE));
    }

    static void setLanguage(String v) {
        putString("language", PrefsRules.language(v));
        Ui.applyLanguage();
    }

    static String temperature() {
        return PrefsRules.temperature(str("temperature", Config.TEMPERATURE));
    }

    static void setTemperature(String v) {
        putString("temperature", PrefsRules.temperature(v));
    }

    static String theme() {
        return PrefsRules.oneOf(str("theme", "auto"), "auto", "light", "dark");
    }

    static void setTheme(String v) {
        putString("theme", PrefsRules.oneOf(v, "auto", "light", "dark"));
    }

    static String digits() {
        return PrefsRules.oneOf(str("digits", "auto"), "auto", "western", "arabic");
    }

    static void setDigits(String v) {
        putString("digits", PrefsRules.oneOf(v, "auto", "western", "arabic"));
        Ui.applyDigits();
    }

    static String performance() {
        return PrefsRules.oneOf(str("performance", "auto"), "auto", "full", "lite");
    }

    static void setPerformance(String v) {
        putString("performance", PrefsRules.oneOf(v, "auto", "full", "lite"));
        Ui.applyLite(app);
    }

    /** "auto" frees memory before an app or video opens (Turbo); "off" never does. The person's choice wins over Config.TURBO. */
    static String turbo() {
        return PrefsRules.oneOf(str("turbo", Config.TURBO), "auto", "auto", "off");
    }

    static void setTurbo(String v) {
        putString("turbo", PrefsRules.oneOf(v, "auto", "auto", "off"));
    }

    static String clock() {
        return PrefsRules.oneOf(str("clock", "auto"), "auto", "auto", "12", "24");
    }

    static void setClock(String v) {
        putString("clock", PrefsRules.oneOf(v, "auto", "auto", "12", "24"));
    }

    static String accent() {
        return PrefsRules.oneOf(str("accent", Ui.ACCENTS[0]), Ui.ACCENTS[0], Ui.ACCENTS);
    }

    static void setAccent(String v) {
        putString("accent", PrefsRules.oneOf(v, Ui.ACCENTS[0], Ui.ACCENTS));
    }

    static String textSize() {
        return PrefsRules.oneOf(str("textSize", "normal"), "normal", "normal", "large", "larger");
    }

    static void setTextSize(String v) {
        putString("textSize", PrefsRules.oneOf(v, "normal", "normal", "large", "larger"));
        A11y.apply();
    }

    static boolean highContrast() {
        return bool("highContrast", false);
    }

    static void setHighContrast(boolean v) {
        putBool("highContrast", v);
        A11y.apply();
    }

    /** The mirror's parallel range downloads (Streamer). A kill switch: `adb shell am start ... --es parallel_fetch off`. */
    static boolean parallelFetch() {
        return bool("parallel_fetch", true);
    }

    static void setParallelFetch(boolean v) {
        putBool("parallel_fetch", v);
    }

    /**
     * Automatic quality (QualityController, SeamlessSwap) in the Watchme+ player. A kill switch: `--es auto_quality off`
     * (Prefs.putBool): off, the quality only changes when picked by hand, as before.
     */
    static boolean autoQuality() {
        return bool("auto_quality", true);
    }

    static void setAutoQuality(boolean v) {
        putBool("auto_quality", v);
    }

    static boolean reduceMotion() {
        return bool("reduceMotion", false);
    }

    static void setReduceMotion(boolean v) {
        putBool("reduceMotion", v);
        A11y.apply();
    }

    // Home rows

    /** The saved order (hidden rows start with "-"), or null when never edited. */
    static List<String> homeRows() {
        String s = str("homeRows", null);
        return s == null ? null : PrefsRules.split(s);
    }

    static String homePreset() {
        return str("homePreset", str("homeRows", null) == null ? "default" : "custom");
    }

    /** rows == null goes back to the built-in order. */
    static void setHomeRows(List<String> rows, String preset) {
        SharedPreferences.Editor e = sp().edit();
        if (rows == null) e.remove("homeRows"); else e.putString("homeRows", PrefsRules.join(rows));
        put(e.putString("homePreset", preset));
    }

    // Matches

    static String teams() {
        return str("teams", Config.TEAMS);
    }

    static void setTeams(List<String> names) {
        putString("teams", PrefsRules.join(names));
    }

    static String leagues() {
        return str("leagues", Config.LEAGUES);
    }

    static void setLeagues(List<String> codes) {
        putString("leagues", PrefsRules.join(codes));
    }

    static String matchAlerts() {
        return PrefsRules.oneOf(str("matchAlerts", "all"), "all", "all", "goals", "off");
    }

    static void setMatchAlerts(String v) {
        putString("matchAlerts", PrefsRules.oneOf(v, "all", "all", "goals", "off"));
    }

    static int quietFrom() {
        return app == null ? -1 : sp().getInt("quietFrom", -1);
    }

    static void setQuietFrom(int hour) {
        put(sp().edit().putInt("quietFrom", hour));
    }

    static int quietUntil() {
        return app == null ? 7 : sp().getInt("quietUntil", 7);
    }

    static void setQuietUntil(int hour) {
        put(sp().edit().putInt("quietUntil", hour));
    }

    static boolean quietNow(long nowMs) {
        int from = quietFrom(), until = quietUntil();
        if (from < 0 || from == until) return false;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        return PrefsRules.quiet(from, until, cal.get(Calendar.HOUR_OF_DAY));
    }

    // Place and home extras

    static String city() {
        return Config.CITY.trim();
    }

    static String latitude() {
        return Config.LATITUDE;
    }

    static String longitude() {
        return Config.LONGITUDE;
    }

    static boolean weather() {
        return bool("weather", true);
    }

    static void setWeather(boolean v) {
        putBool("weather", v);
    }

    static boolean quotes() {
        return bool("quotes", Config.SHOW_CRYPTO);
    }

    static void setQuotes(boolean v) {
        putBool("quotes", v);
    }

    static boolean coin(int i) {
        return bool("coin" + i, true);
    }

    static void setCoin(int i, boolean v) {
        putBool("coin" + i, v);
    }

    // Shelf and remote buttons

    /** Package names on the shelf, in order, or null when never edited. */
    static List<String> shelf() {
        String s = str("shelf", null);
        return s == null ? null : PrefsRules.split(s);
    }

    static void setShelf(List<String> packages) {
        putString("shelf", PrefsRules.join(packages));
    }

    static String keyApp(int keyCode) {
        return str("key" + keyCode, PrefsRules.keyDefault(keyCode));
    }

    static void setKeyApp(int keyCode, String pkg) {
        putString("key" + keyCode, pkg == null ? "" : pkg);
    }
}
