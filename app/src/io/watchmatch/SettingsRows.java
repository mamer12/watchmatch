package io.watchmatch;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The settings' building blocks: a Row (its name, its value, a line on what it does, how ← → and OK change it and what
 * the side panel previews) and a Page of rows. SettingsGroups and SettingsLists make them, SettingsActivity draws them.
 * Their text is read again after every key (SettingsActivity.refresh), never while drawing.
 */
final class SettingsRows {
    private SettingsRows() {}

    /** What the side panel shows for the focused row (SettingsPreview, SettingsSketch). */
    static final int P_GROUP = 0, P_MODE = 1, P_CLOCK = 2, P_CARD = 3, P_ROWS = 4, P_SHELF = 5, P_WIDGETS = 6, P_PERF = 7,
            P_MATCH = 8, P_MOTION = 9, P_REMOTE = 10, P_SCREENSAVER = 11, P_ABOUT = 12, P_SEARCH = 13;

    /** What the pages need from the screen that shows them. */
    interface Host {
        Context context();
        void open(Page p);
        void back();
        /** A value changed (or a list loaded): read the text again, redraw. */
        void changed();
        void later(Runnable r);
        boolean showing(Page p);
        void screensaver();
        /** Another screen: ours on top of the settings (Back comes here), or another task when the intent says so. */
        void launch(Intent i);
        JSONObject state();
        JSONObject recs();
        boolean hasKey();
        boolean busy();
        boolean freeActive();
        /** The promo apps WatchMatch mode turns off: the ones it did, or the ones this TV has. */
        int promoApps();
        /** Installed TV apps after "Nothing" (what a remote button can open): packages and names, same order. */
        List<String> appPkgs();
        List<String> appLabels();
        void loadApps();
        /** The shelf's apps as {package, name}, in order (made in the background, then by the shelf editor). */
        List<String[]> shelf();
        void setShelf(List<String[]> apps);
        void requestMode(boolean free);
    }

    static final class Row {
        Supplier<String> label, value, about;
        String icon;                    // a group on the main page: its symbol
        IntConsumer change;             // ← → (-1 or 1), and OK when there's no `ok`
        Runnable ok;
        boolean opens;                  // a chevron: OK opens a page or another screen
        BooleanSupplier checked, dim, locked, warn;
        int preview = P_GROUP;
        String arg = "";                // what the preview points at (a home row, a coin, an app)
        int index = -1;
        Supplier<List<String[]>> lines; // P_GROUP: {name, value} lines that say what's inside
        // As shown (refresh): the text, ellipsized, and the flags.
        String shownLabel = "", shownValue = "", shownAbout = "", fullAbout = "";
        boolean shownChecked, shownDim, shownLocked, shownWarn;
        int stamp = -1;

        Row(Supplier<String> label) {
            this.label = label;
        }

        Row value(Supplier<String> v) { value = v; return this; }
        Row about(Supplier<String> a) { about = a; return this; }
        Row icon(String name) { icon = name; return this; }
        Row change(IntConsumer c) { change = c; return this; }
        Row ok(Runnable r) { ok = r; return this; }
        Row opens(Runnable r) { ok = r; opens = true; return this; }
        Row checked(BooleanSupplier b) { checked = b; return this; }
        Row dim(BooleanSupplier b) { dim = b; return this; }
        Row locked(BooleanSupplier b) { locked = b; return this; }
        Row warn(BooleanSupplier b) { warn = b; return this; }
        Row preview(int kind) { preview = kind; return this; }
        Row preview(int kind, String what) { preview = kind; arg = what; return this; }
        Row preview(int kind, int i) { preview = kind; index = i; return this; }
        Row lines(Supplier<List<String[]>> l) { lines = l; return this; }
        Row arg(String what) { arg = what; return this; }

        boolean isLocked() { return locked != null && locked.getAsBoolean(); }

        /** OK: opens, or steps the value on. */
        void press() {
            if (isLocked()) return;
            if (ok != null) ok.run();
            else if (change != null) change.accept(1);
        }

        /** ← →: steps the value (opening rows don't move). */
        boolean side(int dir) {
            if (change == null || isLocked()) return false;
            change.accept(dir);
            return true;
        }
    }

    /** A page: its title, the hint at the bottom, and how to make its rows (again when it comes back into view). */
    static final class Page {
        final String key;
        final Supplier<String> title, hint;
        private final Consumer<Page> build;
        final List<Row> rows = new ArrayList<>();
        int focus;
        float scroll;
        boolean main;

        Page(String key, Supplier<String> title, Supplier<String> hint, Consumer<Page> build) {
            this.key = key;
            this.title = title;
            this.hint = hint;
            this.build = build;
        }

        Page main() {
            main = true;
            return this;
        }

        Page focus(int f) {
            focus = f;
            return this;
        }

        void rebuild() {
            rows.clear();
            build.accept(this);
            focus = Math.max(0, Math.min(focus, rows.size() - 1));
        }

        Row add(Row r) {
            rows.add(r);
            return r;
        }

        Row focused() {
            return rows.isEmpty() ? null : rows.get(Math.max(0, Math.min(focus, rows.size() - 1)));
        }
    }

    // MARK: - Shared values

    static String next(String[] options, String current, int dir) {
        int at = java.util.Arrays.asList(options).indexOf(current);
        return options[((at < 0 ? 0 : at) + dir + options.length) % options.length];
    }

    static String onOff(boolean on) {
        return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
    }

    static String hour(int h) {
        return Ui.digits(String.format(Locale.US, "%02d:00", h));
    }

    static String place(int n) {
        return "#" + Ui.digits(String.valueOf(n));
    }

    /** "3 apps", "1 team"… in the language on screen. */
    static String count(int n, String ptOne, String ptMany, String enOne, String enMany, String esOne, String esMany,
            String arOne, String arMany) {
        String d = Ui.digits(String.valueOf(n));
        return Ui.tr(d + " " + (n == 1 ? ptOne : ptMany), d + " " + (n == 1 ? enOne : enMany), d + " " + (n == 1 ? esOne : esMany),
                n == 1 ? arOne : d + " " + arMany);
    }

    static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            sb.append(sb.length() == 0 ? "" : "  ·  ").append(p);
        }
        return sb.toString();
    }

    static String languageName(String lang) {
        return lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español"
                : lang.equals("ar") ? "العربية" : Ui.tr("Automático", "Automatic");
    }

    static String metric(JSONObject m, String key) {
        if (m == null || !m.has(key)) return "—";
        long v = m.optLong(key);
        if (key.equals("homeMs")) return Ui.digits(v + " ms");
        if (key.equals("memAvailableMB") && v >= 1024) return Ui.digits(String.format(Ui.locale(), "%.2f GB", v / 1024.0));
        return Ui.digits(v + " MB");
    }

    static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 1) return Ui.tr("agora", "now");
        if (minutes < 60) return Ui.digits(Ui.tr("há " + minutes + " min", minutes + " min ago", "hace " + minutes + " min",
                "منذ " + minutes + " د"));
        if (minutes < 48 * 60) return Ui.digits(Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago",
                "hace " + (minutes / 60) + " h", "منذ " + (minutes / 60) + " س"));
        return Ui.digits(Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago",
                "hace " + (minutes / 1440) + " días", "منذ " + (minutes / 1440) + " يوم"));
    }

    private static String version;

    /** This app's version (read once: the summaries ask for it after every key). */
    static String versionName(Context c) {
        if (version != null) return version;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            version = "";
        }
        return version == null ? "" : version;
    }
}
