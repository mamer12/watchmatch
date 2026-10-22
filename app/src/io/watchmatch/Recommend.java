package io.watchmatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Netflix-style rows from what was watched here and the welcome answers (the catalog has no recommendations of its own):
 *  - "Top picks for you": the genres liked at the welcome and watched most (finished counts more than started, My List
 *    counts, and the latest weigh more), the recent titles of each, mixed, without what was already seen;
 *  - "Because you loved …" / "watched …" / "liked …": TMDB's recommendations for the titles rated "love" first, then
 *    the ones watched last (anything started counts), then the ones liked (and picked at the welcome), kept to the
 *    ones the catalog has (by name and year).
 * The website (Watchme+ on the web) orders and words these rows the same way.
 */
final class Recommend {
    private Recommend() {}

    private static final int MAX = 30, BECAUSE_MAX = 16, BECAUSE_ROWS = 3, MIN_HISTORY = 1;
    private static final long SEARCH_FRESH_MS = 24 * 3_600_000L;
    /** The catalog's categories (mainCategories?lang=en). */
    private static final Map<String, String> GENRE_IDS = new HashMap<>();
    static {
        String[][] g = {{"Action", "84"}, {"Adventure", "56"}, {"Animation", "57"}, {"Comedy", "59"}, {"Crime", "60"},
            {"Documentary", "61"}, {"Drama", "62"}, {"Fantasy", "67"}, {"Horror", "70"}, {"Mystery", "76"},
            {"Romance", "77"}, {"Sci-Fi", "78"}, {"Sport", "79"}, {"Thriller", "80"}, {"Western", "89"}};
        for (String[] x : g) GENRE_IDS.put(x[0].toLowerCase(Locale.ROOT), x[1]);
    }

    /**
     * Netflix's Top 10 rows: what's trending this week (TMDB), kept to what the catalog has, in rank order. Keys "top-movies"
     * and "top-series"; `done` runs on the main thread (empty without a TMDB key).
     */
    static void top10(Context context, final Library.Callback<List<Library.Row>> done) {
        final Context c = context.getApplicationContext();
        final Library library = Library.get(c);
        final Handler main = new Handler(Looper.getMainLooper());
        library.background(() -> {
            List<Library.Row> out = new ArrayList<>();
            if (Recs.hasKey(c)) {
                Set<String> disliked = CinemaPrefs.disliked(c);
                out.add(trending(library, false, disliked));
                out.add(trending(library, true, disliked));
            }
            List<Library.Row> kept = new ArrayList<>();
            for (Library.Row r : out) if (r.items.size() >= 5) kept.add(r);
            main.post(() -> done.done(kept));
        });
    }

    private static Library.Row trending(Library library, boolean series, Set<String> disliked) {
        Library.Row row = new Library.Row(series ? "top-series" : "top-movies", series
                ? Ui.tr("Top 10 séries da semana", "Top 10 series this week", "Top 10 series de la semana")
                : Ui.tr("Top 10 filmes da semana", "Top 10 movies this week", "Top 10 películas de la semana"), null);
        try {
            JSONArray results = Recs.get("/trending/" + (series ? "tv" : "movie") + "/week").optJSONArray("results");
            Set<String> added = new HashSet<>();
            for (int i = 0; results != null && i < results.length() && row.items.size() < 10; i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                String name = Library.s(r, series ? "name" : "title");
                String date = Library.s(r, series ? "first_air_date" : "release_date");
                Library.Title t = match(library, name, date.length() >= 4 ? date.substring(0, 4) : "", series);
                if (t != null && !disliked.contains(t.id) && added.add(t.id)) row.items.add(t);
            }
        } catch (Exception e) {
            AppLog.w("top 10: " + e);
        }
        row.loaded = true;
        row.more = false;
        return row;
    }

    /** TMDB's genre ids by our English names: {movie, tv} (0: no such genre for series). */
    private static final Map<String, int[]> TMDB_GENRES = new HashMap<>();
    static {
        Object[][] g = {{"action", 28, 10759}, {"adventure", 12, 10759}, {"animation", 16, 16}, {"comedy", 35, 35},
            {"crime", 80, 80}, {"documentary", 99, 99}, {"drama", 18, 18}, {"fantasy", 14, 10765}, {"horror", 27, 0},
            {"mystery", 9648, 9648}, {"romance", 10749, 0}, {"sci-fi", 878, 10765}, {"thriller", 53, 0}, {"western", 37, 37}};
        for (Object[] x : g) TMDB_GENRES.put((String) x[0], new int[] {(Integer) x[1], (Integer) x[2]});
    }

    /**
     * The best-known titles of all time in these genres (TMDB's most voted, like IMDb's classics), kept to what
     * The catalog has, movies and series in turns as `kind` says ("movies", "series", "both"). Background thread; empty
     * without a TMDB key.
     */
    static List<Library.Title> popular(Context c, Library library, Set<String> genres, String kind, int max) {
        List<Library.Title> out = new ArrayList<>();
        if (!Recs.hasKey(c)) return out;
        List<List<Library.Title>> lists = new ArrayList<>();
        if (!kind.equals("series")) lists.add(mostVoted(library, genres, false, max));
        if (!kind.equals("movies")) lists.add(mostVoted(library, genres, true, max));
        Set<String> seen = new HashSet<>();
        for (int i = 0; out.size() < max && more(lists, i); i++) {
            for (List<Library.Title> l : lists) if (i < l.size() && out.size() < max && seen.add(l.get(i).id)) out.add(l.get(i));
        }
        return out;
    }

    private static List<Library.Title> mostVoted(Library library, Set<String> genres, boolean series, int max) {
        List<Library.Title> out = new ArrayList<>();
        StringBuilder ids = new StringBuilder();
        for (String g : genres) {
            int[] id = TMDB_GENRES.get(g);
            int one = id == null ? 0 : series ? id[1] : id[0];
            if (one == 0 || ids.indexOf(String.valueOf(one)) >= 0) continue;
            ids.append(ids.length() == 0 ? "" : "|").append(one);     // "|": any of them
        }
        String kind = series ? "tv" : "movie";
        for (int page = 1; page <= 3 && out.size() < max; page++) {
            try {
                JSONArray results = Recs.get("/discover/" + kind + "?sort_by=vote_count.desc&page=" + page
                        + (ids.length() == 0 ? "" : "&with_genres=" + ids)).optJSONArray("results");
                for (int i = 0; results != null && i < results.length() && out.size() < max; i++) {
                    JSONObject r = results.optJSONObject(i);
                    if (r == null) continue;
                    String name = Library.s(r, series ? "name" : "title");
                    String date = Library.s(r, series ? "first_air_date" : "release_date");
                    Library.Title t = match(library, name, date.length() >= 4 ? date.substring(0, 4) : "", series);
                    if (t != null && !t.poster.isEmpty()) out.add(t);
                }
            } catch (Exception e) {
                AppLog.w("popular titles: " + e);
                break;
            }
        }
        return out;
    }

    /** The catalog's category id for a genre's English name ("Action"), or null. */
    static String categoryId(String genre) {
        return genre == null ? null : GENRE_IDS.get(genre.trim().toLowerCase(Locale.ROOT));
    }

    /** The rows (none without a history); `done` runs on the main thread. */
    static void rows(Context context, final Library.Callback<List<Library.Row>> done) {
        final Context c = context.getApplicationContext();
        final Library library = Library.get(c);
        final Handler main = new Handler(Looper.getMainLooper());
        library.background(() -> {
            List<Library.Row> out = new ArrayList<>();
            try {
                List<Progress.Entry> history = Progress.history(c);
                List<Library.Title> myList = CinemaPrefs.myList(c), liked = CinemaPrefs.likedTitles(c);
                Set<String> genres = CinemaPrefs.likedGenres(c);
                if (history.size() + myList.size() + liked.size() + genres.size() >= MIN_HISTORY) {
                    Set<String> seen = seen(history), loved = new HashSet<>();
                    for (Library.Title t : liked) if (CinemaPrefs.rating(c, t.id) == CinemaPrefs.RATE_LOVE) loved.add(t.id);
                    for (Library.Title t : liked) seen.add(t.id);
                    seen.addAll(CinemaPrefs.disliked(c));
                    Library.Row picks = topPicks(library, history, myList, liked, loved, genres, CinemaPrefs.kind(c), seen);
                    if (picks.items.size() >= 4) out.add(picks);
                    if (Recs.hasKey(c)) {
                        for (Library.Row r : because(library, history, liked, loved, seen)) if (r.items.size() >= 3) out.add(r);
                    }
                }
            } catch (RuntimeException e) {
                AppLog.w("recommendations: " + e);
            }
            StringBuilder log = new StringBuilder("recommendations:");
            for (Library.Row r : out) log.append(" ").append(r.key).append("=").append(r.items.size());
            AppLog.w(log.length() > 16 ? log.toString() : "recommendations: none yet (nothing watched)");
            main.post(() -> done.done(out));
        });
    }

    private static Set<String> seen(List<Progress.Entry> history) {
        Set<String> seen = new HashSet<>();
        for (Progress.Entry e : history) {
            seen.add(e.title.id);
            if (!e.title.rootId.isEmpty()) seen.add(e.title.rootId);
        }
        return seen;
    }

    // MARK: - Top picks

    private static Library.Row topPicks(Library library, List<Progress.Entry> history, List<Library.Title> myList,
            List<Library.Title> liked, Set<String> loved, Set<String> likedGenres, String kind, Set<String> seen) {
        Map<String, Double> scores = new HashMap<>();
        for (String g : likedGenres) if (GENRE_IDS.containsKey(g)) scores.put(g, 3.0);
        // As on the website: a loved title's genres weigh 3, a liked one's 2.
        for (Library.Title t : liked) score(scores, t.genres, loved.contains(t.id) ? 3 : 2);
        int series = 0, movies = 0;
        double recency = 1;
        Set<String> counted = new HashSet<>();
        for (Progress.Entry e : history) {
            String group = e.title.rootId.isEmpty() ? e.title.id : e.title.rootId;
            if (!counted.add(group)) continue;     // a series counts once, at its latest episode
            score(scores, e.title.genres, (e.finished() ? 2 : 1) * recency);
            recency *= 0.85;
            if (e.title.series) series++;
            else movies++;
        }
        for (Library.Title t : myList) score(scores, t.genres, 1.5);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        Collections.sort(ranked, (a, b) -> Double.compare(b.getValue(), a.getValue()));

        // Up to three genres, more titles from the favorite; movies or series as picked at the welcome, else as watched.
        for (Library.Title t : liked) {
            if (t.series) series++;
            else movies++;
        }
        if (kind.equals("movies")) series = 0;
        if (kind.equals("series")) movies = 0;
        if (kind.equals("both") && series + movies == 0) series = movies = 1;
        String type = series > movies ? "series" : "movies";
        List<List<Library.Title>> lists = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            String id = GENRE_IDS.get(ranked.get(i).getKey());
            if (id == null) continue;
            List<Library.Title> l = new ArrayList<>(library.genreNow(id, type, 0));
            if (i == 0) l.addAll(library.genreNow(id, series > 0 && movies > 0 ? (type.equals("series") ? "movies" : "series") : type, 0));
            lists.add(l);
        }
        Library.Row row = new Library.Row("picks", Ui.tr("Escolhas para você", "Top picks for you", "Elegidos para ti"), null);
        Set<String> added = new HashSet<>(seen);
        for (int i = 0; row.items.size() < MAX && more(lists, i); i++) {
            for (List<Library.Title> l : lists) {
                if (i < l.size() && row.items.size() < MAX && added.add(l.get(i).id)) row.items.add(l.get(i));
            }
        }
        row.loaded = true;
        row.more = false;
        return row;
    }

    private static void score(Map<String, Double> scores, String genres, double weight) {
        if (genres == null || genres.isEmpty()) return;
        for (String g : genres.split("·")) {
            String key = g.trim().toLowerCase(Locale.ROOT);
            if (!GENRE_IDS.containsKey(key)) continue;
            Double was = scores.get(key);
            scores.put(key, (was == null ? 0 : was) + weight);
        }
    }

    private static boolean more(List<List<Library.Title>> lists, int i) {
        for (List<Library.Title> l : lists) if (i < l.size()) return true;
        return false;
    }

    // MARK: - Because you watched

    /**
     * Up to BECAUSE_ROWS rows that TMDB knows: for the titles loved, then the latest watched (a series once), then the
     * ones liked (and picked at the welcome). Keys "because", "because2", "because3".
     */
    private static List<Library.Row> because(Library library, List<Progress.Entry> history, List<Library.Title> liked,
            Set<String> loved, Set<String> seen) {
        List<Library.Row> out = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        List<Object[]> seeds = new ArrayList<>();       // {title, name, why: "loved" / "watched" / "liked"}
        for (Library.Title t : liked) if (loved.contains(t.id)) seeds.add(new Object[] {t, t.title, "loved"});
        for (Progress.Entry e : history) seeds.add(new Object[] {e.title, e.rootTitle.isEmpty() ? e.title.title : e.rootTitle, "watched"});
        for (Library.Title t : liked) if (!loved.contains(t.id)) seeds.add(new Object[] {t, t.title, "liked"});
        for (Object[] seed : seeds) {
            if (out.size() >= BECAUSE_ROWS) break;
            Library.Title t = (Library.Title) seed[0];
            String group = t.rootId.isEmpty() ? t.id : t.rootId;
            if (t.imdb.isEmpty() || !groups.add(group)) continue;
            List<String[]> similar = tmdbSimilar(t.imdb, t.series);
            if (similar.isEmpty()) continue;
            String because = "loved".equals(seed[2]) ? Ui.tr("Porque você amou ", "Because you loved ", "Porque te encantó ")
                    : "watched".equals(seed[2]) ? Ui.tr("Porque você assistiu ", "Because you watched ", "Porque viste ")
                    : Ui.tr("Porque você gostou de ", "Because you liked ", "Porque te gustó ");
            Library.Row row = new Library.Row(out.isEmpty() ? "because" : "because" + (out.size() + 1), because + Ui.isolate(String.valueOf(seed[1])), null);
            Set<String> added = new HashSet<>(seen);
            for (String[] s : similar) {
                if (row.items.size() >= BECAUSE_MAX) break;
                Library.Title found = match(library, s[0], s[1], t.series);
                if (found != null && added.add(found.id)) row.items.add(found);
            }
            row.loaded = true;
            row.more = false;
            out.add(row);
        }
        return out;
    }

    /** TMDB's recommendations for the title: {name, year}, best first. */
    private static List<String[]> tmdbSimilar(String imdb, boolean series) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONObject found = Recs.get("/find/" + imdb + "?external_source=imdb_id");
            JSONArray a = found.optJSONArray(series ? "tv_results" : "movie_results");
            boolean tv = series;
            if (a == null || a.length() == 0) {
                a = found.optJSONArray(series ? "movie_results" : "tv_results");
                tv = !series;
            }
            JSONObject first = a == null ? null : a.optJSONObject(0);
            if (first == null) return out;
            String kind = tv ? "tv" : "movie";
            JSONArray results = Recs.get("/" + kind + "/" + first.optLong("id") + "/recommendations").optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                String name = Library.s(r, tv ? "name" : "title");
                String date = Library.s(r, tv ? "first_air_date" : "release_date");
                if (!name.isEmpty()) out.add(new String[] {name, date.length() >= 4 ? date.substring(0, 4) : ""});
            }
        } catch (Exception e) {
            AppLog.w("tmdb recommendations: " + e);
        }
        return out;
    }

    /** The the catalog title with this name (and year, within one), or null. */
    private static Library.Title match(Library library, String name, String year, boolean series) {
        List<Library.Title> found = library.searchNow(name, series ? "series" : "movies", SEARCH_FRESH_MS);
        String want = normalize(name);
        for (Library.Title t : found) {
            if (!normalize(t.title).equals(want) && !normalize(t.other).equals(want)) continue;
            if (year.isEmpty() || t.year.isEmpty() || Math.abs(number(t.year) - number(year)) <= 1) return t;
        }
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
