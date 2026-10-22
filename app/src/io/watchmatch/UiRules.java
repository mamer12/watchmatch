package io.watchmatch;

/** Pure (no Android) helpers behind Ui, so they can be checked on a plain JVM. */
final class UiRules {
    private UiRules() {}

    static final char FSI = '⁨', PDI = '⁩';
    static final int KEY_LEFT = 21, KEY_RIGHT = 22; // KeyEvent.KEYCODE_DPAD_LEFT / RIGHT

    /** "pt","es","ar" or "en" from the setting, else from the TV's own language code. */
    static String resolveLanguage(String setting, String deviceLanguage) {
        if (setting != null && !setting.equals("auto")) return setting;
        if ("pt".equals(deviceLanguage) || "es".equals(deviceLanguage) || "ar".equals(deviceLanguage)) {
            return deviceLanguage;
        }
        return "en";
    }

    static int digitMode(String setting, String language) {
        if ("arabic".equals(setting) || ("auto".equals(setting) && "ar".equals(language))) return 2;
        return "western".equals(setting) ? 1 : 0;
    }

    static boolean twelveHour(String setting, boolean english) {
        return "12".equals(setting) || ("auto".equals(setting) && english);
    }

    static String isolate(String s, boolean arabic) {
        if (!arabic || s == null || s.isEmpty()) return s;
        return FSI + s + PDI;
    }

    /** Fills {app}/{catalog}/{spark}; the same instance comes back when there is no '{'. */
    static String brand(String s, String app, String catalog, String spark, boolean arabic) {
        if (s == null || s.indexOf('{') < 0) return s;
        return s.replace("{app}", isolate(app, arabic))
                .replace("{catalog}", isolate(catalog, arabic))
                .replace("{spark}", isolate(spark, arabic));
    }

    /** Converts every Western, Arabic-Indic and Persian digit to the target script (mode 1 or 2). */
    static String digits(String s, int mode) {
        if (mode == 0 || s == null) return s;
        char zero = mode == 2 ? '٠' : '0';
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v = -1;
            if (ch >= '0' && ch <= '9') v = ch - '0';
            else if (ch >= '٠' && ch <= '٩') v = ch - '٠';
            else if (ch >= '۰' && ch <= '۹') v = ch - '۰';
            if (v < 0) continue;
            char to = (char) (zero + v);
            if (to == ch) continue;
            if (out == null) out = new StringBuilder(s);
            out.setCharAt(i, to);
        }
        return out == null ? s : out.toString();
    }

    /** Picks the text for a language: arabic is null to look the English up instead. */
    static int pickIndex(boolean ar, boolean es, boolean en) {
        return ar ? 3 : es ? 2 : en ? 1 : 0; // 0 pt, 1 en, 2 es, 3 ar
    }

    static float mx(boolean rtl, float x, float size, float total) {
        return rtl ? total - x - size : x;
    }

    static int dpad(boolean rtl, int keyCode) {
        if (!rtl) return keyCode;
        if (keyCode == KEY_LEFT) return KEY_RIGHT;
        if (keyCode == KEY_RIGHT) return KEY_LEFT;
        return keyCode;
    }

    static float layoutX(boolean rtl, float x, float right, float total) {
        return rtl ? total - x - right : x;
    }

    static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    static int alpha(int color, float a) {
        int na = Math.round(((color >>> 24) & 255) * clamp(a));
        return (na << 24) | (color & 0xFFFFFF);
    }

    static String clockPattern(boolean twelve) { return twelve ? "h:mm" : "HH:mm"; }

    static String datePattern(String language) {
        if ("ar".equals(language)) return "EEEE، d MMMM";
        if ("en".equals(language)) return "EEEE, MMMM d";
        return "EEEE, d 'de' MMMM";
    }

    static String timePattern(boolean english) { return english ? "h:mm a" : "H:mm"; }

    static String onceKey(String kind, String language, int digitMode, double value) {
        return kind + '|' + language + digitMode + '|' + value;
    }
}
