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

    static final int RESULT_LIMIT = 24, PAGES = 2, GENRE_QUERIES = 2, WIDE_PAGES = 3;
    /** A language filter reads deeper (the API cannot filter by language): more pages per round and more rounds. */
    /** The catalog's category of foreign titles dubbed into Arabic: its language number says Arabic, they are not. */
    static final String DUBBED = "arabic dubbed";
    static final int LANG_PAGES = 6, LANG_ROUNDS = 5, ROUNDS = 2;
    private static final double GENRE_MATCH_WEIGHT = 4, KEYWORD_WEIGHT = 1, KEYWORD_MAX = 3, UNKNOWN_STARS = 5, MIN_STARS = 5.5,
            STARS_WEIGHT = 0.6, HIGH_STARS = 7, AFFINITY_CAP = 5, UNASKED_ANIMATION = 4, FRESH_BONUS = 1, RECENT_BONUS = 0.5;
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

    /** The catalog's language numbers (language_lighttigerNb), by two-letter code: the API cannot filter by them. */
    private static final Map<String, List<String>> LANGUAGE = new HashMap<>();
    static {
        String[][] l = {{"ar", "9"}, {"en", "7"}, {"hi", "10"}, {"fr", "11"}, {"de", "12"}, {"es", "14"}, {"zh", "21", "52"},
                {"ja", "22"}, {"ko", "23"}, {"ru", "24"}, {"tr", "25"}};
        for (String[] r : l) LANGUAGE.put(r[0], java.util.Arrays.asList(java.util.Arrays.copyOfRange(r, 1, r.length)));
    }

    /** Can the catalog be filtered by this language code? */
    static boolean knowsLanguage(String code) {
        return LANGUAGE.containsKey(code);
    }

    /** Is a title of the catalog's language number `nb` in the language `code`? An unknown number is not. */
    static boolean inLanguage(String code, String nb) {
        List<String> ids = LANGUAGE.get(code);
        return ids != null && nb != null && ids.contains(nb);
    }

    /** A title of the catalog, as the ranking needs it. `genres`: lower case English names. year, minutes: 0 when unknown. */
    static final class Item {
        final String id, title, other, text;
        /** The catalog's language number ("" unknown). */
        final String lang;
        final int year, minutes;
        final double stars;
        final boolean series;
        final List<String> genres;

        Item(String id, String title, String other, String text, int year, double stars, int minutes, boolean series,
             List<String> genres) {
            this(id, title, other, text, year, stars, minutes, series, genres, "");
        }

        Item(String id, String title, String other, String text, int year, double stars, int minutes, boolean series,
             List<String> genres, String lang) {
            this.lang = lang == null ? "" : lang;
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

    /** The first round of pages for these filters. */
    static List<Query> queries(TonightProtocol.Filters f, int thisYear) {
        return queries(f, thisYear, 0);
    }

    /** How many rounds of pages may be read: a language reads deeper, as the API cannot filter by it. */
    static int rounds(TonightProtocol.Filters f) {
        return f.lang.isEmpty() ? ROUNDS : LANG_ROUNDS;
    }

    /** The pages of one round (0, 1…) to ask for these filters; the era is the server's, the language is checked here. */
    static List<Query> queries(TonightProtocol.Filters f, int thisYear, int round) {
        List<String> types = new ArrayList<>();
        if (!TonightProtocol.TV.equals(f.type)) types.add("movies");
        if (!TonightProtocol.MOVIE.equals(f.type)) types.add("series");
        List<String> categories = new ArrayList<>();
        int most = f.lang.isEmpty() ? GENRE_QUERIES : 1;
        for (String g : f.genres) {
            String id = CATEGORY.get(g);
            if (id != null && !categories.contains(id) && categories.size() < most) categories.add(id);
        }
        int from = f.minYear > 0 || f.maxYear > 0 ? (f.minYear > 0 ? f.minYear : 1900) : 0;
        int to = f.minYear > 0 || f.maxYear > 0 ? (f.maxYear > 0 ? f.maxYear : thisYear + 1) : 0;
        boolean wide = categories.isEmpty();
        if (wide) categories.add("");
        int pages = !f.lang.isEmpty() ? LANG_PAGES : wide ? WIDE_PAGES : PAGES;
        List<Query> out = new ArrayList<>();
        for (String type : types) {
            for (String category : categories) {
                for (int page = round * pages; page < (round + 1) * pages; page++) out.add(new Query(type, category, from, to, page));
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
            if (!f.lang.isEmpty() && !inLanguage(f.lang, t.lang)) continue;
            if ("ar".equals(f.lang) && t.genres.contains(DUBBED)) continue;      // foreign titles dubbed into Arabic (Remi, Lady Oscar)     // never loosened: the era and language are asked
            if (!f.genres.isEmpty() && !sharesGenre(t, f.genres)) continue;     // at least one of what was asked for
            if (TonightProtocol.MOVIE.equals(f.type) && t.series) continue;
            if (TonightProtocol.TV.equals(f.type) && !t.series) continue;
            boolean banned = false;
            for (String g : t.genres) if (f.excludeGenres.contains(g)) banned = true;
            if (banned) continue;
            if (f.minYear > 0 && (t.year == 0 || t.year < f.minYear)) continue;       // no year: not known to fit
            if (f.maxYear > 0 && (t.year == 0 || t.year > f.maxYear)) continue;
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

    /** The genre's name in the TV's language; a catalog genre the app has no line for (Biography...) keeps its own name. */
    static String genreLabel(TonightText.Tr tr, String genre) {
        if (!TonightText.ids().contains("g:" + genre)) return genre.isEmpty() ? genre : Character.toUpperCase(genre.charAt(0)) + genre.substring(1);
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

    /**
     * Loosens the filters a step at a time when nothing matched: the time limit first, then exclusions, then genres. Null
     * at the end. The language and the era are never loosened: they are what the person asked for.
     */
    static TonightProtocol.Filters relax(TonightProtocol.Filters f) {
        if (f.maxMinutes > 0) return f.without("minutes");
        if (!f.excludeGenres.isEmpty()) return f.withoutExclusions();
        if (!f.genres.isEmpty() || !f.keywords.isEmpty()) return f.withoutGenres();
        return null;
    }

    /** What was found: the picks, the filters that gave them and whether any genre, exclusion or time limit was dropped. */
    static final class Found {
        final List<Pick> picks;
        final TonightProtocol.Filters used;
        final boolean relaxed;

        Found(List<Pick> picks, TonightProtocol.Filters used, boolean relaxed) {
            this.picks = Collections.unmodifiableList(new ArrayList<>(picks));
            this.used = used;
            this.relaxed = relaxed;
        }
    }

    /** Real titles for the filters, best first, each with its reason; relaxes the filters when nothing matched. Blocking. */
    static List<Pick> resolve(TonightProtocol.Filters filters, Deps d) {
        return search(filters, d).picks;
    }

    /**
     * Reads rounds of pages until enough titles fit (a language or an era is rare in the catalog's order) or the rounds
     * or the catalog end; the language and the era are filters, never relaxed.
     */
    static Found search(TonightProtocol.Filters filters, Deps d) {
        for (TonightProtocol.Filters f = filters; f != null; f = relax(f)) {
            List<Item> all = new ArrayList<>(), good = new ArrayList<>();
            for (int round = 0; round < rounds(f); round++) {
                boolean any = false;
                for (List<Item> l : d.source.fetch(queries(f, d.thisYear, round))) {
                    all.addAll(l);
                    any |= !l.isEmpty();
                }
                good = filter(all, f, d.exclude);
                if (!any || good.size() >= RESULT_LIMIT) break;
            }
            if (good.isEmpty()) continue;
            List<Item> ranked = rank(good, f, d.affinity, d.thisYear);
            List<Pick> out = new ArrayList<>();
            for (int i = 0; i < ranked.size() && i < RESULT_LIMIT; i++) {
                out.add(new Pick(ranked.get(i), reason(ranked.get(i), f, d.affinity, d.tr, d.arabic)));
            }
            return new Found(out, f, f != filters);
        }
        return new Found(Collections.<Pick>emptyList(), filters, false);
    }
}
