package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** The choices behind HomeApps that need no Android: which apps, in which order, and what a device name means. */
final class HomeAppsRules {
    private HomeAppsRules() {}

    static final int MAX_SHELF = 8;
    static final String LIBRARY = Endpoints.PKG, WATCHME_STANDALONE = "io.watchmeplus";

    /** key, name, package, brand colour, banner-lift mode ("" = none), logo size tweak; default shelf order. */
    static final String[][] KNOWN = {
        {"netflix", "Netflix", "com.netflix.ninja", "E50914", "", "1"},
        {"youtube", "YouTube", "com.google.android.youtube.tv", "FF0033", "play", "1"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint", "0.96"},
        {"disney", "Disney+", "com.disney.disneyplus", "1838C8", "", "1"},
        {"max", "Max", "com.wbd.stream", "6C3BE0", "", "1.32"},
        {"appletv", "Apple TV", "com.apple.atve.androidtv.appletv", "B8BCC6", "", "1.1"},
        {"hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", "", "1"},
        {"paramount", "Paramount+", "com.cbs.ott", "0064FF", "", "1"},
        {"peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", "", "1"},
        {"globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint", "1"},
        {"spotify", "Spotify", "com.spotify.tv.android", "1DB954", "", "1"},
    };

    static final String[] LIVE_TV_APPS = {"com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter",
        "com.sony.dtv.tvx"};

    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList("com.google.android.play.games", "com.tcl.t_solo",
            "com.tcl.waterfall.overseas", "com.tcl.dashboard", "com.tcl.ocean.instructions", "com.tcl.guard",
            "com.tcl.exhibit"));

    private static final String[][] DEVICE_WORDS = {
        {"gamepad", "playstation", "ps5", "ps4", "ps3", "xbox", "nintendo", "switch", "steam deck", "console", "game"},
        {"play", "apple tv", "chromecast", "fire tv", "firetv", "roku", "shield", "mi box", "tv box", "tvbox", "stick",
            "streaming", "google tv", "android tv"},
        {"speaker", "speaker", "soundbar", "sound bar", "receiver", "denon", "yamaha", "marantz", "onkyo", "bose",
            "sonos", "jbl", "audio", "home theater"},
        {"tv", "tv", "cable", "set-top", "decoder", "decodificador", "tuner", "sky", "claro", "vivo"},
    };

    static String[] known(String pkg) {
        for (String[] k : KNOWN) if (k[2].equals(pkg)) return k;
        return null;
    }

    /** The icon name that suits a device called `name` (an HDMI source), "hdmi" when nothing does. */
    static String deviceSymbol(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String[] group : DEVICE_WORDS) {
            for (int i = 1; i < group.length; i++) if (n.contains(group[i])) return group[0];
        }
        return "hdmi";
    }

    /**
     * The shelf's packages. A saved list stays as it is (the catalog's package becomes Watchme+ standalone when only that
     * one can be opened); without one, the known apps that are installed, up to MAX_SHELF.
     */
    static List<String> shelfPackages(List<String> saved, Predicate<String> launchable) {
        List<String> out = new ArrayList<>();
        if (saved != null) {
            out.addAll(saved);
            int at = out.indexOf(LIBRARY);
            if (at >= 0 && !launchable.test(LIBRARY) && launchable.test(WATCHME_STANDALONE)
                    && !out.contains(WATCHME_STANDALONE)) {
                out.set(at, WATCHME_STANDALONE);
            }
            return out;
        }
        for (String[] k : KNOWN) {
            if (out.size() >= MAX_SHELF) break;
            if (launchable.test(k[2])) out.add(k[2]);
        }
        return out;
    }

    /** Whether an app may appear among the recent ones (it still needs a TV launch intent). */
    static boolean recentAllowed(String pkg, String own) {
        return !HIDDEN.contains(pkg) && !Arrays.asList(LIVE_TV_APPS).contains(pkg) && !pkg.equals(own)
                && !pkg.equals("com.android.tv.settings");
    }

    /**
     * The packages used in the last days, most recent first: `lastUsed` holds the newest use per package, only
     * for those with foreground time.
     */
    static List<String> recentOrder(Map<String, Long> lastUsed, String own, Predicate<String> leanback, int max) {
        final Map<String, Long> times = lastUsed;
        List<String> pkgs = new ArrayList<>(times.keySet());
        Collections.sort(pkgs, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Long.compare(times.get(b), times.get(a));
            }
        });
        List<String> out = new ArrayList<>();
        for (String p : pkgs) {
            if (out.size() >= max) break;
            if (recentAllowed(p, own) && leanback.test(p)) out.add(p);
        }
        return out;
    }

    /** Share of the pixels with real ink (alpha above half), sampled on a grid; 0.4 with no samples, at least 0.08. */
    static float ink(int[] argb, int w, int h) {
        int stepY = Math.max(1, h / 48), stepX = Math.max(1, w / 96), total = 0, inked = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                total++;
                if ((argb[y * w + x] >>> 24) > 128) inked++;
            }
        }
        if (total == 0) return 0.4f;
        return Math.max(0.08f, (float) inked / total);
    }
}
