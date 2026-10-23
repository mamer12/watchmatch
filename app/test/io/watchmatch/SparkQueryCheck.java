package io.watchmatch;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Spark's parser, checked with plain Java (no Android, no test framework). From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/SparkQuery.java app/src/io/watchmatch/SearchRank.java \
 *       app/test/io/watchmatch/SparkQueryCheck.java && java -cp out/test io.watchmatch.SparkQueryCheck
 *
 * Prints each sentence and what it became; exits 1 when one doesn't match.
 */
public final class SparkQueryCheck {
    private static final int YEAR = 2026;
    private static int failed, passed;

    public static void main(String[] args) {
        // The brief's examples, in both languages.
        check("افلام عصابات جديده", "movie", list("crime"), "new", 2023, 0, null, false, null, "");
        check("new gangster movies", "movie", list("crime"), "new", 2023, 0, null, false, null, "");
        check("last 5 years comedy movies", "movie", list("comedy"), "new", 2021, 0, null, false, null, "");
        check("افلام عربيه قبل 2000", "movie", list(), "before", 0, 1999, "ar", false, null, "");
        check("مسلسل بريكينج باد", "tv", list(), null, 0, 0, null, false, null, "بريكينج باد");
        check("breaking bad series", "tv", list(), null, 0, 0, null, false, null, "breaking bad");
        check("افلام كوميديه اجنبيه", "movie", list("comedy"), null, 0, 0, "foreign", false, null, "");
        check("foreign comedy movies", "movie", list("comedy"), null, 0, 0, "foreign", false, null, "");
        check("افلام عربيه قديمه", "movie", list(), "old", 0, 1999, "ar", false, null, "");
        check("old Arabic movies", "movie", list(), "old", 0, 1999, "ar", false, null, "");
        // Spelling: hamza, ة / ه, ى / ي, the article and "and" in front.
        check("أفلام أكشن جديدة", "movie", list("action"), "new", 2023, 0, null, false, null, "");
        check("الأفلام الكوميدية والرومانسية", "movie", list("comedy", "romance"), null, 0, 0, null, false, null, "");
        check("افلام الاعلى تقييما", "movie", list(), null, 0, 0, null, false, "top", "");
        check("المسلسلات الأكثر مشاهدة", "tv", list(), null, 0, 0, null, false, "popular", "");
        // Decades, years and ranges.
        check("افلام رعب من التسعينات", "movie", list("horror"), "decade", 1990, 1999, null, false, null, "");
        check("90s horror movies", "movie", list("horror"), "decade", 1990, 1999, null, false, null, "");
        check("افلام الثمانينات", "movie", list(), "decade", 1980, 1989, null, false, null, "");
        check("comedy movies 2010-2015", "movie", list("comedy"), "range", 2010, 2015, null, false, null, "");
        check("افلام اكشن من ٢٠١٠ الى ٢٠١٥", "movie", list("action"), "range", 2010, 2015, null, false, null, "");
        check("movies after 2020", "movie", list(), "after", 2020, 0, null, false, null, "");
        check("افلام 2024", "movie", list(), "year", 2024, 2024, null, false, null, "");
        // Origins.
        check("مسلسلات تركية", "tv", list(), null, 0, 0, "tr", false, null, "");
        check("best korean series", "tv", list(), null, 0, 0, "ko", false, "top", "");
        check("k-drama romance", "tv", list("romance"), null, 0, 0, "ko", false, null, "");
        check("افلام هنديه", "movie", list(), null, 0, 0, "hi", false, null, "");
        check("انمي ياباني", null, list("animation"), null, 0, 0, "ja", false, null, "");
        check("فلم مصري قديم", "movie", list(), "old", 0, 1999, "eg", false, null, "");
        check("مسلسلات خليجيه", "tv", list(), null, 0, 0, "gulf", false, null, "");
        check("american war movies", "movie", list("war"), null, 0, 0, "us", false, null, "");
        // Genres and moods.
        check("افلام خيال علمي", "movie", list("scifi"), null, 0, 0, null, false, null, "");
        check("sci-fi movies", "movie", list("scifi"), null, 0, 0, null, false, null, "");
        check("افلام كرتون عائليه", "movie", list("animation", "family"), null, 0, 0, null, true, null, "");
        check("وثائقي تاريخي", null, list("documentary", "history"), null, 0, 0, null, false, null, "");
        check("افلام حروب", "movie", list("war"), null, 0, 0, null, false, null, "");
        check("افلام اثارة وتشويق", "movie", list("thriller"), null, 0, 0, null, false, null, "");
        check("مسلسلات غموض", "tv", list("mystery"), null, 0, 0, null, false, null, "");
        check("افلام مافيا", "movie", list("crime"), null, 0, 0, null, false, null, "");
        // Iraqi colloquial around it.
        check("ابي افلام عربيه قديمه", "movie", list(), "old", 0, 1999, "ar", false, null, "");
        check("شغللي مسلسل بريكنج باد", "tv", list(), null, 0, 0, null, false, null, "بريكنج باد");
        check("show me new gangster movies please", "movie", list("crime"), "new", 2023, 0, null, false, null, "");
        // A plain title stays a plain title.
        check("Inception", null, list(), null, 0, 0, null, false, null, "Inception");
        check("الكيف", null, list(), null, 0, 0, null, false, null, "الكيف");
        check("game of thrones series", "tv", list(), null, 0, 0, null, false, null, "game of thrones");

        SparkQuery q = SparkQuery.parse("افلام عصابات جديده", YEAR);
        expect("isSpark", q.isSpark() && q.typed);
        expect("plain title is not Spark", !SparkQuery.parse("Inception", YEAR).isSpark());
        SparkQuery off = q.without("genre:crime").without("era");
        expect("chip off: " + off, off.genres.isEmpty() && off.era == null && "movie".equals(off.kind));
        expect("original unchanged", q.genres.equals(list("crime")) && "new".equals(q.era));
        expect("parts " + q.parts(), q.parts().equals(Arrays.asList("kind", "genre:crime", "era")));
        try {
            q.genres.add("x");
            expect("genres unmodifiable", false);
        } catch (UnsupportedOperationException e) {
            expect("genres unmodifiable", true);
        }

        System.out.println();
        System.out.println(failed == 0 ? "OK: " + passed + " checks passed" : "FAILED: " + failed + " of " + (passed + failed));
        if (failed > 0) System.exit(1);
    }

    private static List<String> list(String... s) {
        return s.length == 0 ? Collections.<String>emptyList() : Arrays.asList(s);
    }

    private static void check(String text, String kind, List<String> genres, String era, int from, int to, String origin,
                              boolean family, String sort, String title) {
        SparkQuery q = SparkQuery.parse(text, YEAR);
        boolean ok = eq(kind, q.kind) && genres.equals(q.genres) && eq(era, q.era) && from == q.from && to == q.to
                && eq(origin, q.origin) && (family || genres.contains("family")) == q.family && eq(sort, q.sort)
                && title.equals(q.title);
        System.out.println((ok ? "pass  " : "FAIL  ") + text + "  →  " + q);
        if (ok) passed++;
        else failed++;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "pass  " : "FAIL  ") + what);
        if (ok) passed++;
        else failed++;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
