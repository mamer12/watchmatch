package io.watchmatch;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Checks the pure rules behind Prefs, HomeApps, Recs and the banner lift, on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/{PrefsRules,Endpoints,ProviderRules,HomeAppsRules,RecsRules,BannerLift}.java \
 *       app/test/io/watchmatch/RulesCheck.java && java -cp out/test io.watchmatch.RulesCheck
 */
public final class RulesCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) {
        prefs();
        shelf();
        recent();
        symbols();
        recs();
        lift();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void prefs() {
        eq("lang pt-BR", "pt", PrefsRules.language(" PT-br "));
        eq("lang es", "es", PrefsRules.language("es_MX"));
        eq("lang ar", "ar", PrefsRules.language("ar"));
        eq("lang junk", "auto", PrefsRules.language("klingon"));
        eq("lang null", "auto", PrefsRules.language(null));
        eq("temp f", "F", PrefsRules.temperature(" fahrenheit"));
        eq("temp c", "C", PrefsRules.temperature("c"));
        eq("temp junk", "auto", PrefsRules.temperature("x"));
        eq("theme dark", "dark", PrefsRules.oneOf("dark", "auto", "light", "dark"));
        eq("theme junk", "auto", PrefsRules.oneOf("sepia", "auto", "light", "dark"));
        eq("clock 24", "24", PrefsRules.oneOf("24", "auto", "auto", "12", "24"));
        eq("clock junk", "auto", PrefsRules.oneOf("30", "auto", "auto", "12", "24"));
        eq("split", Arrays.asList("a", "-b", "c"), PrefsRules.split("a,,-b,c,"));
        eq("split empty", Collections.emptyList(), PrefsRules.split(""));
        eq("join", "a,b", PrefsRules.join(Arrays.asList("a", "b")));
        eq("key netflix", "com.netflix.ninja", PrefsRules.keyDefault(4062));
        eq("key youtube", "com.google.android.youtube.tv", PrefsRules.keyDefault(190));
        eq("key prime", "com.amazon.amazonvideo.livingroom", PrefsRules.keyDefault(4099));
        eq("key tcl", "", PrefsRules.keyDefault(4020));
        eq("key other", "", PrefsRules.keyDefault(7));
        eq("quiet none", false, PrefsRules.quiet(-1, 7, 3));
        eq("quiet same", false, PrefsRules.quiet(5, 5, 5));
        eq("quiet day in", true, PrefsRules.quiet(9, 17, 9));
        eq("quiet day end", false, PrefsRules.quiet(9, 17, 17));
        eq("quiet wrap late", true, PrefsRules.quiet(22, 7, 23));
        eq("quiet wrap early", true, PrefsRules.quiet(22, 7, 6));
        eq("quiet wrap out", false, PrefsRules.quiet(22, 7, 7));
    }

    private static Predicate<String> installed(String... pkgs) {
        final Set<String> set = new HashSet<>(Arrays.asList(pkgs));
        return set::contains;
    }

    private static void shelf() {
        List<String> def = HomeAppsRules.shelfPackages(null,
                installed("com.disney.disneyplus", "com.netflix.ninja", "com.other"));
        eq("default order", Arrays.asList("com.netflix.ninja", "com.disney.disneyplus"), def);
        String[] all = new String[HomeAppsRules.KNOWN.length];
        for (int i = 0; i < all.length; i++) all[i] = HomeAppsRules.KNOWN[i][2];
        eq("default cap", HomeAppsRules.MAX_SHELF, HomeAppsRules.shelfPackages(null, installed(all)).size());
        List<String> saved = Arrays.asList("b", "a", "a2", "c", "d", "e", "f", "g", "h", "i");
        eq("saved kept whole", saved, HomeAppsRules.shelfPackages(saved, installed()));
        List<String> cin = Arrays.asList("x", HomeAppsRules.LIBRARY, "y");
        eq("library migrates", Arrays.asList("x", "io.watchmeplus", "y"),
                HomeAppsRules.shelfPackages(cin, installed("io.watchmeplus")));
        eq("library stays if usable", cin,
                HomeAppsRules.shelfPackages(cin, installed("io.watchmeplus", HomeAppsRules.LIBRARY)));
        eq("library stays if no replacement", cin, HomeAppsRules.shelfPackages(cin, installed()));
        List<String> both = Arrays.asList(HomeAppsRules.LIBRARY, "io.watchmeplus");
        eq("no duplicate", both, HomeAppsRules.shelfPackages(both, installed("io.watchmeplus")));
    }

    private static void recent() {
        Map<String, Long> last = new HashMap<>();
        last.put("a.old", 10L);
        last.put("a.new", 30L);
        last.put("com.tcl.guard", 40L);
        last.put("com.google.android.tv", 50L);
        last.put("io.watchmatch", 60L);
        last.put("com.android.tv.settings", 70L);
        last.put("a.system", 80L);
        last.put("a.mid", 20L);
        Predicate<String> lean = p -> !p.equals("a.system");
        eq("recent order and filter", Arrays.asList("a.new", "a.mid", "a.old"),
                HomeAppsRules.recentOrder(last, "io.watchmatch", lean, 6));
        eq("recent max", Arrays.asList("a.new", "a.mid"), HomeAppsRules.recentOrder(last, "io.watchmatch", lean, 2));
    }

    private static void symbols() {
        eq("ps5", "gamepad", HomeAppsRules.deviceSymbol("PS5"));
        eq("apple tv", "play", HomeAppsRules.deviceSymbol("Apple TV"));
        eq("soundbar", "speaker", HomeAppsRules.deviceSymbol("Samsung Soundbar"));
        eq("cable", "tv", HomeAppsRules.deviceSymbol("Cable box"));
        eq("unknown", "hdmi", HomeAppsRules.deviceSymbol("Thing"));
        eq("null", "hdmi", HomeAppsRules.deviceSymbol(null));
        int[] none = new int[4 * 4];
        eq("ink floor", 0.08f, HomeAppsRules.ink(none, 4, 4));
        int[] full = new int[4 * 4];
        Arrays.fill(full, 0xFFFFFFFF);
        eq("ink full", 1f, HomeAppsRules.ink(full, 4, 4));
        eq("ink empty", 0.4f, HomeAppsRules.ink(new int[0], 0, 0));
    }

    private static void recs() {
        eq("morning", RecsRules.MORNING, RecsRules.period(5));
        eq("before morning", RecsRules.NIGHT, RecsRules.period(4));
        eq("afternoon", RecsRules.AFTERNOON, RecsRules.period(12));
        eq("night", RecsRules.NIGHT, RecsRules.period(18));
        eq("whole word max", "max", RecsRules.service("HBO Max")[1]);
        eq("max inside word", null, RecsRules.service("Maxdome"));
        eq("amazon prime", "prime", RecsRules.service("Amazon Prime Video")[1]);
        eq("substring", "disney", RecsRules.service("Disney Plus")[1]);
        eq("franchise colon", "dune", RecsRules.franchise("Dune: Part Two"));
        eq("franchise dash", "star wars", RecsRules.franchise("Star Wars - Andor"));
        eq("name", "Disney+", RecsRules.serviceName("disney"));
        eq("name null", "", RecsRules.serviceName(null));
        String[] titles = {"A", "A: Two", "B", "C", "D"};
        String[] apps = {"x", "y", "x", "z", "x"};
        // hour 0: window starts at 0: A (x), skip "A: Two" (franchise), B skipped (x used), C (z), D skipped (x)
        // second pass fills with B.
        eq("choose h0", Arrays.asList(0, 3, 2), RecsRules.choose(titles, apps, 0));
        eq("choose none", Collections.emptyList(), RecsRules.choose(new String[0], new String[0], 5));
        eq("choose few", Arrays.asList(0), RecsRules.choose(new String[] {"Only"}, new String[] {null}, 7));
        eq("interleave", Arrays.asList(1, 4, 2, 5, 3),
                RecsRules.interleave(Arrays.asList(Arrays.asList(1, 2, 3), Arrays.asList(4, 5))));
        eq("genre pt", "Ação e Aventura", RecsRules.translateGenre("action & adventure", "pt"));
        eq("genre en", "Kids", RecsRules.translateGenre("Kids", "en"));
        eq("genre other", "Drama", RecsRules.translateGenre("Drama", "es"));
    }

    private static void lift() {
        int w = 200, h = 112;
        int[] img = new int[w * h];
        Arrays.fill(img, 0xFFFFFFFF);
        for (int y = 30; y < 80; y++) for (int x = 70; x < 130; x++) img[y * w + x] = 0xFFE50914;
        BannerLift.Result r = BannerLift.lift(img, w, h, null);
        eq("lift found", true, r != null);
        if (r == null) return;
        eq("lift width", true, r.width >= 60 && r.width <= 70);
        eq("lift height", true, r.height >= 50 && r.height <= 60);
        int mid = (r.height / 2) * r.width + r.width / 2;
        eq("lift center opaque red", 0xFFE50914, r.pixels[mid]);
        int[] flat = new int[w * h];
        Arrays.fill(flat, 0xFF202020);
        eq("flat banner rejected", null, BannerLift.lift(flat, w, h, null));
        // A gradient background alone is not a logo.
        int[] grad = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = 40 + x * 60 / w;
            grad[y * w + x] = 0xFF000000 | v << 16 | v << 8 | v;
        }
        eq("gradient rejected", null, BannerLift.lift(grad, w, h, null));
        // A play triangle hole inside a red box becomes white.
        int[] box = new int[w * h];
        Arrays.fill(box, 0xFFFFFFFF);
        for (int y = 30; y < 80; y++) for (int x = 60; x < 140; x++) box[y * w + x] = 0xFFFF0033;
        for (int y = 45; y < 65; y++) for (int x = 95; x < 108; x++) box[y * w + x] = 0xFFFFFFFF;
        BannerLift.Result p = BannerLift.lift(box, w, h, "play");
        eq("play found", true, p != null);
        if (p != null) {
            int pad = (p.width - 80) / 2, padY = (p.height - 50) / 2;
            int hole = (padY + 25) * p.width + pad + 40;
            eq("hole filled white", 0xFFFFFFFF, p.pixels[hole]);
        }
    }
}
