package io.watchmatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The value rules behind Prefs, with no Android in them so they can be checked on a plain JVM. */
final class PrefsRules {
    private PrefsRules() {}

    static String language(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (String code : new String[] {"pt", "es", "en", "ar"}) if (s.startsWith(code)) return code;
        return "auto";
    }

    static String temperature(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("F")) return "F";
        return s.startsWith("C") ? "C" : "auto";
    }

    /** `raw` if it is one of `allowed`, else `fallback`. */
    static String oneOf(String raw, String fallback, String... allowed) {
        for (String a : allowed) if (a.equals(raw)) return raw;
        return fallback;
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) {
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }

    /** The remote's buttons by key code (Prefs.KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME); the rest start empty. */
    static String keyDefault(int keyCode) {
        switch (keyCode) {
            case 4062: return "com.netflix.ninja";
            case 190: return "com.google.android.youtube.tv";
            case 4099: return "com.amazon.amazonvideo.livingroom";
            default: return "";
        }
    }

    static boolean quiet(int from, int until, int hour) {
        if (from < 0 || from == until) return false;
        return from < until ? hour >= from && hour < until : hour >= from || hour < until;
    }
}
