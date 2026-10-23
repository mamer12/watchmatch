package io.watchmatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filters to real titles: only what {catalog} has (its AdvancedSearch, through a Source), ranked by the person's taste
 * and by what they asked for tonight, each with a one-line reason built from what actually matched (never invented).
 * Port of the web app's features/tonight/resolver.ts. Pure Java (no Android): the Source does the network.
 */
final class TonightResolver {
    private TonightResolver() {}

    static final int RESULT_LIMIT = 12, PAGES = 2, GENRE_QUERIES = 2, WIDE_PAGES = 3;
    private static final double GENRE_MATCH_WEIGHT = 4, KEYWORD_WEIGHT = 1, KEYWORD_MAX = 3, UNKNOWN_STARS = 5, MIN_STARS = 5.5,
            STARS_WEIGHT = 0.6, HIGH_STARS = 7, AFFINITY_CAP = 5, UNASKED_ANIMATION = 2.5, FRESH_BONUS = 1, RECENT_BONUS = 0.5;
    private static final int FRESH_YEARS = 2, RECENT_YEARS = 8;

    /** The catalog's category ids by the server's genre names (music and western have none). */
    private static final Map<String, String> CATEGORY = new HashMap<>();
    /** The catalog's own names for genres, as lower case. */
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        String[][] c = {{"action", "84"}, {"comedy", "59"}, {"drama", "62"}, {"horror", "70"}, {"romance", "77"},
                {"crime", "60"}, {"thriller", "80"}, {"war", "81"}, {"sci-fi", "78"}, {"fantasy", "67"},
                {"animation", "57"}, {"documentary", "61"}, {"family", "65"}, {"history", "68"}, {"mystery", "76"},
                {"adventure", "56"}};
        for (String[] r : c) CATEGORY.put(r[0], r[1]);
        ALIAS.put("science fiction", "sci-fi");
        ALIAS.put("scifi", "sci-fi");
        ALIAS.put("sci fi", "sci-fi");
        ALIAS.put("kids", "family");
        ALIAS.put("musical", "music");
    }

    /** A title of the catalog, as the ranking needs it. `genres`: lower case English names. year, minutes: 0 when unknown. */
    static final class Item {
        final String id, title, other, text;
        final int year, minutes;
        final double stars;
        final boolean series;
        final List<String> genres;

        Item(String id, String title, String other, String text, int year, double stars, int minutes, boolean series,
             List<String> genres) {
            this.id = id;
            this.title = title;
            this.other = other;
            this.text = text == null ? "" : text.toLowerCase(Locale.ROOT);
            this.year = year;
            this.stars = stars;
            this.minutes = minutes;
            this.series = series;
            this.genres = Collections.unmodifiableList(new ArrayList<>(genres));
        }
    }

    /** One page of the catalog: type "movies" or "series", a category id ("" for any), years (0 open). */
    static final class Query {
        final String type, category;
        final int from, to, page;

        Query(String type, String category, int from, int to, int page) {
            this.type = type;
            this.category = category;
            this.from = from;
            this.to = to;
            this.page = page;
        }

        @Override public String toString() {
            return type + "/" + category + "/" + from + "-" + to + "/" + page;
        }
    }

    /** Runs the queries (in parallel if it can); one list per query, in order, empty when it failed. Blocking. */
    interface Source {
        List<List<Item>> fetch(List<Query> queries);
    }

    static final class Deps {
        final Source source;
        final Map<String, Double> affinity;
        /** Ids never to offer: finished, in My List, rated down. */
        final Set<String> exclude;
        final int thisYear;
        final TonightText.Tr tr;
        final boolean arabic;

        Deps(Source source, Map<String, Double> affinity, Set<String> exclude, int thisYear, TonightText.Tr tr, boolean arabic) {
            this.source = source;
            this.affinity = affinity;
            this.exclude = exclude;
            this.thisYear = thisYear;
            this.tr = tr;
            this.arabic = arabic;
        }
    }

    static final class Pick {
        final Item item;
        final String reason;

        Pick(Item item, String reason) {
            this.item = item;
            this.reason = reason;
        }
    }

    /** The catalog's genre name as the server's ("Sci-Fi" is "sci-fi"). */
    static String genreName(String catalogName) {
        String n = catalogName == null ? "" : catalogName.trim().toLowerCase(Locale.ROOT);
        String alias = ALIAS.get(n);
        return alias != null ? alias : n;
    }

    /** The pages to ask for these filters. */
    static List<Query> queries(TonightProtocol.Filters f, int thisYear) {
        List<String> types = new ArrayList<>();
        if (!TonightProtocol.TV.equals(f.type)) types.add("movies");
        if (!TonightProtocol.MOVIE.equals(f.type)) types.add("series");
        List<String> categories = new ArrayList<>();
        for (String g : f.genres) {
            String id = CATEGORY.get(g);
            if (id != null && !categories.contains(id) && categories.size() < GENRE_QUERIES) categories.add(id);
        }
        int from = f.minYear > 0 || f.maxYear > 0 ? (f.minYear > 0 ? f.minYear : 1900) : 0;
        int to = f.minYear > 0 || f.maxYear > 0 ? (f.maxYear > 0 ? f.maxYear : thisYear + 1) : 0;
        boolean wide = categories.isEmpty();
        if (wide) categories.add("");
        List<Query> out = new ArrayList<>();
        for (String type : types) {
            for (String category : categories) {
                for (int page = 0; page < (wide ? WIDE_PAGES : PAGES); page++) out.add(new Query(type, category, from, to, page));
            }
        }
        return out;
    }

    /** Hard filters. Runtime applies to films only (a series' duration is one episode). */
    static List<Item> filter(List<Item> items, TonightProtocol.Filters f, Set<String> exclude) {
        Set<String> seen = new HashSet<>(), names = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (Item t : items) {
            if (exclude.contains(t.id) || !seen.add(t.id)) continue;
            if (!names.add(sameTitle(t))) continue;                  // the same film under two numbers
            if (!f.genres.isEmpty() && !sharesGenre(t, f.genres)) continue;     // at least one of what was asked for
            if (TonightProtocol.MOVIE.equals(f.type) && t.series) continue;
            if (TonightProtocol.TV.equals(f.type) && !t.series) continue;
            boolean banned = false;
            for (String g : t.genres) if (f.excludeGenres.contains(g)) banned = true;
            if (banned) continue;
            if (f.minYear > 0 && t.year > 0 && t.year < f.minYear) continue;
            if (f.maxYear > 0 && t.year > 0 && t.year > f.maxYear) continue;
            if (f.maxMinutes > 0 && !t.series && t.minutes > 0 && t.minutes > f.maxMinutes) continue;
            if (t.stars > 0 && t.stars < MIN_STARS) continue;       // rated by the catalog's own viewers, and low
            out.add(t);
        }
        return out;
    }

    private static boolean sharesGenre(Item t, List<String> wanted) {
        for (String g : t.genres) if (wanted.contains(g)) return true;
        return false;
    }

    /** A title and year as one key, so the same film listed twice shows once. */
    static String sameTitle(Item t) {
        return t.title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "") + "|" + t.year;
    }

    /**
     * Higher is better: every wanted genre it has (4 each), what the catalog's viewers rate it, how new it is, the
     * person's taste (their genres, capped so it never outweighs what they asked for tonight) and the keywords found
     * in its text. A cartoon nobody asked for or liked is held back.
     */
    static double score(Item t, TonightProtocol.Filters f, Map<String, Double> affinity, int thisYear) {
        double s = (t.stars > 0 ? t.stars : UNKNOWN_STARS) * STARS_WEIGHT;
        if (t.year > 0 && t.year >= thisYear - FRESH_YEARS) s += FRESH_BONUS;
        else if (t.year > 0 && t.year >= thisYear - RECENT_YEARS) s += RECENT_BONUS;
        double taste = 0;
        for (String g : t.genres) {
            Double a = affinity.get(g);
            if (a != null) taste += a;
            if (f.genres.contains(g)) s += GENRE_MATCH_WEIGHT;
        }
        s += Math.max(-AFFINITY_CAP, Math.min(AFFINITY_CAP, taste));
        double words = 0;
        for (String k : f.keywords) if (!k.isEmpty() && t.text.contains(k)) words += KEYWORD_WEIGHT;
        s += Math.min(KEYWORD_MAX, words);
        if (t.genres.contains("animation") && !f.genres.contains("animation")) {
            Double a = affinity.get("animation");
            if (a == null || a <= 0) s -= UNASKED_ANIMATION;
        }
        return s;
    }

    /** Best first; a tie keeps the catalog's order. */
    static List<Item> rank(List<Item> items, final TonightProtocol.Filters f, final Map<String, Double> affinity, final int thisYear) {
        final Map<Item, Double> scores = new java.util.IdentityHashMap<>();
        for (Item t : items) scores.put(t, score(t, f, affinity, thisYear));
        List<Item> out = new ArrayList<>(items);
        Collections.sort(out, (a, b) -> Double.compare(scores.get(b), scores.get(a)));       // stable
        return out;
    }

    private static String genreLabel(TonightText.Tr tr, String genre) {
        return TonightText.get(tr, "g:" + genre);
    }

    /** A short line from what actually matched: the genres it has that were asked for, and its stars when they are high. */
    static String reason(Item t, TonightProtocol.Filters f, Map<String, Double> affinity, TonightText.Tr tr, boolean arabic) {
        String stars = t.stars >= HIGH_STARS ? " · ★ " + String.format(Locale.US, "%.1f", t.stars) : "";
        List<String> hit = new ArrayList<>();
        for (String g : t.genres) if (f.genres.contains(g) && hit.size() < 2) hit.add(g);
        if (!hit.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String g : hit) names.append(names.length() == 0 ? "" : " · ").append(genreLabel(tr, g));
            return names + stars;
        }
        String liked = null;
        for (String g : t.genres) {
            Double a = affinity.get(g);
            if (a != null && a > 0 && (liked == null || a > affinity.get(liked))) liked = g;
        }
        if (liked != null) return TonightText.get(tr, "youLike") + genreLabel(tr, liked);
        if (f.maxMinutes > 0 && !t.series && t.minutes > 0) return TonightText.get(tr, "fitsTime") + t.minutes + TonightText.get(tr, "min");
        if (t.stars >= HIGH_STARS) return TonightText.get(tr, "highlyRated") + String.format(Locale.US, "%.1f", t.stars);
        return TonightText.get(tr, "popular");
    }

    /** Loosens the filters a step at a time when nothing matched: limits first, then exclusions, then genres. Null at the end. */
    static TonightProtocol.Filters relax(TonightProtocol.Filters f) {
        if (f.hasLimits()) return f.withoutLimits();
        if (!f.excludeGenres.isEmpty()) return f.withoutExclusions();
        if (!f.genres.isEmpty() || !f.keywords.isEmpty()) return f.withoutGenres();
        return null;
    }

    /** Real titles for the filters, best first, each with its reason; relaxes the filters when nothing matched. Blocking. */
    static List<Pick> resolve(TonightProtocol.Filters filters, Deps d) {
        for (TonightProtocol.Filters f = filters; f != null; f = relax(f)) {
            List<Item> all = new ArrayList<>();
            for (List<Item> l : d.source.fetch(queries(f, d.thisYear))) all.addAll(l);
            List<Item> good = filter(all, f, d.exclude);
            if (good.isEmpty()) continue;
            List<Item> ranked = rank(good, f, d.affinity, d.thisYear);
            List<Pick> out = new ArrayList<>();
            for (int i = 0; i < ranked.size() && i < RESULT_LIMIT; i++) {
                out.add(new Pick(ranked.get(i), reason(ranked.get(i), f, d.affinity, d.tr, d.arabic)));
            }
            return out;
        }
        return Collections.emptyList();
    }
}
