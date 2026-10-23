package io.watchmatch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.app.SearchManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Spark's results for a SparkQuery, across the TV: {catalog} (the catalog's AdvancedSearch: type, genre and years on the
 * server; language, more genres and the family filter here), then a row per installed app that streams the matches
 * (TMDB's discover or search, and where each title streams in your country), then the rest of TMDB, then the TV's
 * other apps that take a search. Blocking: call run() off the main thread, and only from one thread at a time.
 */
final class Spark {
    private Spark() {}

    static final int POSTERS = 0, WIDE = 1, APPS = 2;

    static final class Row {
        final String title;
        final int style;
        final List<Catalog.Entry> items;

        Row(String title, int style, List<Catalog.Entry> items) {
            this.title = title;
            this.style = style;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    static final class Result {
        /** The title as Spark found it ("Breaking Bad" for "بريكينج باد"), or the query's own. */
        final String title;
        final List<Row> rows;
        /** The apps' TV art (banner, else icon), by package; `icons` are the ones that are icons. */
        final Map<String, Drawable> art;
        final Set<String> icons;

        Result(String title, List<Row> rows, Map<String, Drawable> art, Set<String> icons) {
            this.title = title;
            this.rows = Collections.unmodifiableList(rows);
            this.art = Collections.unmodifiableMap(art);
            this.icons = Collections.unmodifiableSet(icons);
        }
    }

    private static String library() { return Endpoints.searchBase() + "AdvancedSearch?"; }
    private static final String POSTER = "https://image.tmdb.org/t/p/w342", BACKDROP = "https://image.tmdb.org/t/p/w780";
    private static final int ROW_MAX = 30, TMDB_MAX = 24, APPS_MAX = 14, CATALOG_PAGES = 2, FILTERED_PAGES = 5;
    private static final long WAIT_S = 25;
    private static final String KIDS_LEVEL = "2", ALL_LEVEL = "0";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    /** The catalog's category ids (checked against AdvancedSearch's answers) and names, by genre key. */
    private static final Map<String, String[]> CATALOG_GENRES = new HashMap<>();
    /** TMDB's genre ids, movie and TV ("" when TV has none). */
    private static final Map<String, String[]> TMDB_GENRES = new HashMap<>();
    /** The catalog's language numbers (language_lighttigerNb), by origin. */
    private static final Map<String, String> CATALOG_LANGUAGE = new HashMap<>();
    static {
        String[][] g = {
            // key, the catalog id, the catalog name, TMDB movie, TMDB TV
            {"action", "84", "Action", "28", "10759"}, {"comedy", "59", "Comedy", "35", "35"},
            {"drama", "62", "Drama", "18", "18"}, {"horror", "70", "Horror", "27", ""},
            {"romance", "77", "Romance", "10749", ""}, {"crime", "60", "Crime", "80", "80"},
            {"thriller", "80", "Thriller", "53", ""}, {"war", "81", "War", "10752", "10768"},
            {"scifi", "78", "Sci-Fi", "878", "10765"}, {"fantasy", "67", "Fantasy", "14", "10765"},
            {"animation", "57", "Animation", "16", "16"}, {"documentary", "61", "Documentary", "99", "99"},
            {"family", "65", "Family", "10751", "10751"}, {"history", "68", "History", "36", ""},
            {"mystery", "76", "Mystery", "9648", "9648"}, {"adventure", "56", "Adventure", "12", "10759"},
        };
        for (String[] r : g) {
            CATALOG_GENRES.put(r[0], new String[] {r[1], r[2]});
            TMDB_GENRES.put(r[0], new String[] {r[3], r[4]});
        }
        CATALOG_LANGUAGE.put(SparkQuery.ARABIC, "9");
        CATALOG_LANGUAGE.put(SparkQuery.EGYPTIAN, "9");
        CATALOG_LANGUAGE.put(SparkQuery.GULF, "9");
        CATALOG_LANGUAGE.put(SparkQuery.TURKISH, "25");
        CATALOG_LANGUAGE.put(SparkQuery.KOREAN, "23");
        CATALOG_LANGUAGE.put(SparkQuery.HINDI, "10");
        CATALOG_LANGUAGE.put(SparkQuery.JAPANESE, "22");
        CATALOG_LANGUAGE.put(SparkQuery.AMERICAN, "7");
    }

    /** Streaming services Recs doesn't know, by part of TMDB's name: {name, display name, TV package}. */
    private static final String[][] MORE_APPS = {
        {"shahid", "Shahid", "net.mbc.shahidTV"},
        {"starz", "STARZPLAY", "com.parsifal.starz"},
        {"youtube", "YouTube", "com.google.android.youtube.tv"},
    };
    /** Apps TMDB knows nothing about, offered under "Search in other apps" even without a search screen. */
    private static final String[] NO_TMDB = {"iq.almanasa.tvandroid", "com.google.android.youtube.tv"};

    // MARK: - Running a query

    static Result run(Context c, SparkQuery q) {
        return run(c, q, null);
    }

    /**
     * Ask Spark's results by app: `catalog` is the first row, already found (its own filters are stricter than this
     * query's); with it, only the rows of the apps that stream the matches follow (no TMDB rest, YouTube or other apps).
     */
    static Result run(Context c, SparkQuery q, List<Catalog.Entry> catalogRow) {
        boolean own = catalogRow != null;
        PackageManager pm = c.getPackageManager();
        List<Hit> hits = new ArrayList<>();
        if (Recs.hasKey(c)) {
            try {
                hits = q.hasTitle() ? searchTmdb(q) : discover(pm, q);
            } catch (Exception e) {
                AppLog.w("spark TMDB: " + e.getMessage());
            }
        }
        // The title in Latin letters, from TMDB, for the catalog (whose Arabic names are often the English ones).
        List<String> names = new ArrayList<>();
        String title = q.title;
        if (q.hasTitle()) {
            names.add(q.title);
            Set<String> said = new HashSet<>();
            said.add(SearchRank.normalize(q.title));
            for (int i = 0; i < hits.size() && names.size() < 3; i++) {
                String latin = hits.get(i).latin();
                if (latin != null && said.add(SearchRank.normalize(latin))) names.add(latin);
            }
            if (arabic(q.title) && names.size() > 1) title = names.get(1);
        }
        List<Catalog.Entry> catalog = own ? catalogRow : catalog(q, names);
        providers(pm, hits);

        List<Row> rows = new ArrayList<>();
        if (!catalog.isEmpty()) rows.add(new Row(WatchMe.NAME, POSTERS, catalog));
        Set<String> inCatalog = new HashSet<>();
        for (Catalog.Entry e : catalog) {
            inCatalog.add(SearchRank.normalize(e.title));
            if (e.query != null) inCatalog.add(SearchRank.normalize(e.query));
        }
        Map<String, List<Catalog.Entry>> byApp = new LinkedHashMap<>();
        Map<String, String> appNames = new HashMap<>();
        List<Catalog.Entry> rest = new ArrayList<>();
        Map<String, String> searches = new HashMap<>();
        for (Hit h : hits) {
            for (String[] app : h.apps) {
                List<Catalog.Entry> list = byApp.get(app[1]);
                if (list == null) {
                    byApp.put(app[1], list = new ArrayList<>());
                    appNames.put(app[1], app[0]);
                    searches.put(app[1], searchComponent(pm, app[1]));
                }
                if (list.size() >= ROW_MAX) continue;
                Catalog.Entry e = h.entry(app[1], h.year);
                e.search = searches.get(app[1]);      // apps without title links: their search, with the title
                list.add(e);
            }
            boolean listed = inCatalog.contains(SearchRank.normalize(h.title)) || inCatalog.contains(SearchRank.normalize(h.original));
            if (h.apps.isEmpty() && !listed && !own) {
                // Its year first, as on every card, then where it streams (apps not on this TV).
                List<String> sub = new ArrayList<>();
                if (!h.year.isEmpty()) sub.add(h.year);
                sub.addAll(h.elsewhere);
                rest.add(h.entry(Endpoints.PKG, join(sub)));
            }
        }
        for (Map.Entry<String, List<Catalog.Entry>> e : byApp.entrySet()) {
            rows.add(new Row(onApp(appNames.get(e.getKey())), POSTERS, e.getValue()));
        }
        if (q.hasTitle() && !own) {
            try {
                List<Catalog.Entry> videos = YouTubeAccount.search(c, title);
                if (!videos.isEmpty()) rows.add(new Row("YouTube", WIDE, videos));
            } catch (Exception e) {
                AppLog.w("spark YouTube: " + e.getMessage());
            }
        }
        if (!rest.isEmpty()) rows.add(new Row(Ui.tr("Mais no TMDB", "More from TMDB"), POSTERS, rest));

        Map<String, Drawable> art = new HashMap<>();
        Set<String> icons = new HashSet<>();
        List<Catalog.Entry> apps = own ? new ArrayList<Catalog.Entry>() : otherApps(c, q.hasTitle() ? title : q.text, byApp.keySet(), art, icons);
        if (!apps.isEmpty()) rows.add(new Row(Ui.tr("Buscar em outros apps", "Search in other apps"), APPS, apps));
        return new Result(title, rows, art, icons);
    }

    // MARK: - Watchme+ (the catalog)

    /** One title from AdvancedSearch, with what the filters here look at. */
    private static final class Item {
        Catalog.Entry entry;
        double stars;
        int year, rank;
    }

    private static List<Catalog.Entry> catalog(final SparkQuery q, List<String> names) {
        List<String> types = new ArrayList<>();
        if (!SparkQuery.TV.equals(q.kind)) types.add("movies");
        if (!SparkQuery.MOVIE.equals(q.kind)) types.add("series");
        String category = null;
        final List<String> extra = new ArrayList<>();       // genres checked here (the API takes one)
        for (String g : q.genres) {
            String[] cg = CATALOG_GENRES.get(g);
            if (cg == null) continue;
            if (category == null) category = cg[0];
            else extra.add(cg[1]);
        }
        StringBuilder filters = new StringBuilder("level=").append(q.family ? KIDS_LEVEL : ALL_LEVEL);
        if (category != null) filters.append("&category_id=").append(category);
        if (q.era != null) {
            int thisYear = Calendar.getInstance().get(Calendar.YEAR);
            filters.append("&year=").append(q.from > 0 ? q.from : 1900).append(',').append(q.to > 0 ? q.to : thisYear + 1);
        }
        boolean local = q.origin != null || !extra.isEmpty() || q.family;
        List<Callable<List<Item>>> calls = new ArrayList<>();
        for (String type : types) {
            if (!names.isEmpty()) {
                for (String name : names) {
                    calls.add(page(filters + "&type=" + type + "&videoTitle=" + enc(name) + "&staffTitle=" + enc(name) + "&page=0", q, extra));
                }
            } else {
                for (int p = 0; p < (local ? FILTERED_PAGES : CATALOG_PAGES); p++) {
                    calls.add(page(filters + "&type=" + type + "&page=" + p, q, extra));
                }
            }
        }
        // In turns (movies, series), each title once.
        List<List<Item>> pages = all(calls);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; ; i++) {
            boolean any = false;
            for (List<Item> list : pages) {
                if (i >= list.size()) continue;
                any = true;
                Item it = list.get(i);
                if (seen.add(it.entry.id)) items.add(it);
            }
            if (!any) break;
        }
        if (!names.isEmpty()) {
            List<String> wanted = new ArrayList<>();
            for (String n : names) wanted.add(SearchRank.normalize(n));
            for (Item it : items) {
                it.rank = SearchRank.NONE;
                for (String w : wanted) it.rank = Math.min(it.rank, SearchRank.score(it.entry.title, it.entry.query, w));
            }
            Collections.sort(items, (a, b) -> Integer.compare(a.rank, b.rank));
        } else if (SparkQuery.TOP.equals(q.sort)) {
            Collections.sort(items, (a, b) -> Double.compare(b.stars, a.stars));
        } else if (SparkQuery.NEW.equals(q.era)) {
            Collections.sort(items, (a, b) -> a.year != b.year ? b.year - a.year : Double.compare(b.stars, a.stars));
        }
        List<Catalog.Entry> out = new ArrayList<>();
        for (int i = 0; i < items.size() && out.size() < ROW_MAX; i++) out.add(items.get(i).entry);
        return out;
    }

    private static Callable<List<Item>> page(final String params, final SparkQuery q, final List<String> extra) {
        return () -> {
            List<Item> out = new ArrayList<>();
            JSONArray a = new JSONArray(http(library() + params));
            String language = q.origin == null ? null : CATALOG_LANGUAGE.get(q.origin);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String lang = Library.s(o, "language_lighttigerNb");
                if (SparkQuery.FOREIGN.equals(q.origin) ? "9".equals(lang) : language != null && !language.equals(lang)) continue;
                Set<String> cats = new HashSet<>();
                JSONArray ca = o.optJSONArray("categories");
                for (int j = 0; ca != null && j < ca.length(); j++) {
                    JSONObject cat = ca.optJSONObject(j);
                    if (cat != null) cats.add(Library.s(cat, "en_title"));
                }
                if (!cats.containsAll(extra) || q.family && cats.contains("Horror")) continue;
                String img = Library.s(o, "imgMediumThumbObjUrl");
                if (img.isEmpty()) continue;
                Catalog.Entry e = new Catalog.Entry();
                String en = Library.s(o, "en_title").trim(), ar = Library.s(o, "ar_title").trim();
                e.title = en.isEmpty() ? ar : en;
                e.sub = Library.s(o, "year");
                e.image = e.still = img;
                e.pkg = Endpoints.PKG;
                e.id = Library.s(o, "nb");
                e.query = ar.isEmpty() ? en : ar;
                Item it = new Item();
                it.entry = e;
                it.stars = number(Library.s(o, "stars"));
                it.year = (int) number(e.sub);
                out.add(it);
            }
            return out;
        };
    }

    // MARK: - TMDB

    /** A TMDB title, and where it streams here: installed apps {name, package}, and the others' names. */
    private static final class Hit {
        String kind, id, title = "", original = "", year = "", poster, backdrop, lang = "";
        final List<String[]> apps = new ArrayList<>();
        final List<String> elsewhere = new ArrayList<>();

        /** The name in Latin letters (the original when it's in them, else the translated one), or null. */
        String latin() {
            if (!original.isEmpty() && !arabic(original) && latinOnly(original)) return original;
            return !title.isEmpty() && latinOnly(title) ? title : null;
        }

        Catalog.Entry entry(String pkg, String sub) {
            Catalog.Entry e = new Catalog.Entry();
            e.title = title;
            e.sub = sub;
            e.image = poster != null ? POSTER + poster : BACKDROP + backdrop;
            e.still = backdrop != null ? BACKDROP + backdrop : e.image;
            e.pkg = pkg;
            e.tmdb = kind + ":" + id;
            String latin = latin();
            e.query = latin != null ? latin : title;
            return e;
        }
    }

    /** A title said: TMDB's search, the Arabic spelled a few common ways ("بريكينج" is "بريكنغ" there). */
    private static List<Hit> searchTmdb(SparkQuery q) throws Exception {
        String kind = q.kind == null ? "multi" : q.kind;
        boolean arabic = arabic(q.title);
        // Arabic: TMDB finds it by its Arabic name and answers in English, which the catalog knows.
        String lang = arabic ? "en-US" : Recs.lang();
        List<Hit> hits = new ArrayList<>();
        for (String text : arabic ? spellings(q.title) : java.util.Collections.singletonList(q.title)) {
            hits = hits(Recs.get("/search/" + kind + "?include_adult=false&language=" + lang + "&query=" + enc(text))
                    .optJSONArray("results"), q.kind);
            if (!hits.isEmpty()) break;
        }
        if (hits.isEmpty() && arabic) {
            hits = hits(Recs.get("/search/" + kind + "?include_adult=false&language=ar&query=" + enc(q.title))
                    .optJSONArray("results"), q.kind);
        }
        // The other filters, when they leave something (a title said with a year: "Dune 2021").
        List<Hit> kept = new ArrayList<>();
        for (Hit h : hits) if (fits(h, q)) kept.add(h);
        return kept.isEmpty() ? hits : kept;
    }

    private static boolean fits(Hit h, SparkQuery q) {
        int y = (int) number(h.year);
        if (q.from > 0 && y > 0 && y < q.from || q.to > 0 && y > q.to) return false;
        return !SparkQuery.FOREIGN.equals(q.origin) || !"ar".equals(h.lang);
    }

    /** Filters only: TMDB's discover, then the same limited to the apps on this TV (so their rows fill). */
    private static List<Hit> discover(PackageManager pm, SparkQuery q) throws Exception {
        List<String> kinds = new ArrayList<>();
        if (!SparkQuery.TV.equals(q.kind)) kinds.add(SparkQuery.MOVIE);
        if (!SparkQuery.MOVIE.equals(q.kind)) kinds.add(SparkQuery.TV);
        List<List<Hit>> lists = new ArrayList<>();
        for (String kind : kinds) {
            String path = discoverPath(q, kind);
            if (path == null) continue;
            lists.add(hits(Recs.get(path).optJSONArray("results"), kind));
            String ids = installedProviders(pm, kind);
            if (!ids.isEmpty()) {
                lists.add(hits(Recs.get(path + "&watch_region=" + Recs.region() + "&with_watch_providers=" + enc(ids)
                        + "&with_watch_monetization_types=" + enc("flatrate|free|ads")).optJSONArray("results"), kind));
            }
        }
        List<Hit> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; out.size() < TMDB_MAX; i++) {
            boolean any = false;
            for (List<Hit> l : lists) {
                if (i >= l.size()) continue;
                any = true;
                Hit h = l.get(i);
                if (fits(h, q) && seen.add(h.kind + h.id)) out.add(h);
            }
            if (!any) break;
        }
        return out;
    }

    /** /discover/movie or /discover/tv with the query's filters; null when a genre has no TV counterpart. */
    private static String discoverPath(SparkQuery q, String kind) {
        boolean movie = SparkQuery.MOVIE.equals(kind);
        StringBuilder p = new StringBuilder("/discover/").append(kind).append("?include_adult=false&language=").append(Recs.lang());
        List<String> genres = new ArrayList<>();
        for (String g : q.genres) {
            String[] ids = TMDB_GENRES.get(g);
            String id = ids == null ? "" : ids[movie ? 0 : 1];
            if (id.isEmpty()) {
                if (q.kind == null) return null;     // both asked: this genre is for movies only
                continue;
            }
            if (!genres.contains(id)) genres.add(id);
        }
        if (!genres.isEmpty()) p.append("&with_genres=").append(join(genres, ","));
        String date = movie ? "primary_release_date" : "first_air_date";
        if (q.from > 0) p.append('&').append(date).append(".gte=").append(q.from).append("-01-01");
        if (q.to > 0) p.append('&').append(date).append(".lte=").append(q.to).append("-12-31");
        else if (q.era != null) p.append('&').append(date).append(".lte=").append(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
        if (q.origin != null) {
            switch (q.origin) {
                case SparkQuery.AMERICAN: p.append("&with_origin_country=US"); break;
                case SparkQuery.EGYPTIAN: p.append("&with_origin_country=EG"); break;
                case SparkQuery.GULF: p.append("&with_original_language=ar&with_origin_country=").append(enc("SA|AE|KW|QA|BH|OM")); break;
                case SparkQuery.FOREIGN: break;      // not Arabic: dropped here (fits)
                default: p.append("&with_original_language=").append(q.origin); break;
            }
        }
        if (q.maxMinutes > 0 && movie) p.append("&with_runtime.lte=").append(q.maxMinutes);
        if (q.family) {
            if (movie) p.append("&certification_country=US&certification.lte=PG-13&without_genres=27");
            else p.append("&without_genres=").append(enc("10768"));
        }
        boolean top = SparkQuery.TOP.equals(q.sort);
        p.append("&sort_by=").append(top ? "vote_average.desc" : "popularity.desc");
        // Rated by enough people (fewer for Arabic, Turkish… titles, which have fewer votes on TMDB).
        if (top) p.append("&vote_count.gte=").append(q.origin != null ? 20 : movie ? 300 : 100);
        return p.toString();
    }

    private static List<Hit> hits(JSONArray a, String kind) {
        List<Hit> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && out.size() < TMDB_MAX; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String k = kind != null ? kind : o.optString("media_type");
            if (!SparkQuery.MOVIE.equals(k) && !SparkQuery.TV.equals(k)) continue;
            boolean movie = SparkQuery.MOVIE.equals(k);
            Hit h = new Hit();
            h.kind = k;
            h.id = String.valueOf(o.optInt("id"));
            h.title = Library.s(o, movie ? "title" : "name");
            h.original = Library.s(o, movie ? "original_title" : "original_name");
            String date = Library.s(o, movie ? "release_date" : "first_air_date");
            h.year = date.length() >= 4 ? date.substring(0, 4) : "";
            h.lang = Library.s(o, "original_language");
            String poster = Library.s(o, "poster_path"), back = Library.s(o, "backdrop_path");
            h.poster = poster.isEmpty() ? null : poster;
            h.backdrop = back.isEmpty() ? null : back;
            if (h.poster == null && h.backdrop == null || h.title.isEmpty()) continue;
            out.add(h);
        }
        return out;
    }

    /**
     * Where each title streams here (subscription, free or with ads), a few at a time; and its poster in the language
     * of the {catalog} row's art (poster()).
     */
    private static void providers(final PackageManager pm, List<Hit> hits) {
        List<Callable<Void>> calls = new ArrayList<>();
        for (final Hit h : hits) {
            calls.add(() -> {
                JSONObject d = details(h.kind, h.id);
                String poster = poster(d);
                if (poster != null) h.poster = poster;
                JSONObject here = streamsHere(d);
                Set<String> seen = new HashSet<>();
                for (String type : new String[] {"flatrate", "free", "ads"}) {
                    JSONArray list = here == null ? null : here.optJSONArray(type);
                    for (int i = 0; list != null && i < list.length(); i++) {
                        String name = list.getJSONObject(i).optString("provider_name");
                        String[] app = app(name);
                        if (app != null && installed(pm, app[1])) {
                            if (seen.add(app[1])) h.apps.add(app);
                        } else if (seen.add(name)) {
                            h.elsewhere.add(name);
                        }
                    }
                }
                return null;
            });
        }
        all(calls);
    }

    /**
     * A title's services and posters in one call, the posters asked in the languages {catalog} asks TMDB for its art
     * (the catalog.tmdbArt): the lists' own poster follows the language of the titles, so in Arabic it was often an Arabic
     * one beside {catalog}'s English posters.
     */
    static JSONObject details(String kind, String id) throws Exception {
        return Recs.get("/" + kind + "/" + id + "?append_to_response=watch/providers,images"
                + "&include_image_language=" + (Library.arabicFirst ? "ar,en" : "en,ar") + ",null");
    }

    /** From details(): where it streams in your country ({"flatrate": [...], ...}), or null. */
    static JSONObject streamsHere(JSONObject details) {
        JSONObject wp = details == null ? null : details.optJSONObject("watch/providers");
        JSONObject all = wp == null ? null : wp.optJSONObject("results");
        return all == null ? null : all.optJSONObject(Recs.region());
    }

    /** A streaming app's row, here and in the plain search: "On Netflix". */
    static String onApp(String app) {
        return Ui.tr("No ", "On ") + Ui.isolate(app);
    }

    /** From details(): the poster in the first language asked, else the second, else without text; the best rated. */
    static String poster(JSONObject details) {
        JSONObject images = details == null ? null : details.optJSONObject("images");
        JSONArray list = images == null ? null : images.optJSONArray("posters");
        String first = Library.arabicFirst ? "ar" : "en", second = Library.arabicFirst ? "en" : "ar", best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            String path = Library.s(p, "file_path");
            if (path.isEmpty()) continue;
            String lang = Library.s(p, "iso_639_1");
            double score = (lang.equals(first) ? 2e4 : lang.equals(second) ? 1e4 : 0)
                    + p.optDouble("vote_average", 0) * 100 + Math.min(p.optInt("vote_count"), 99);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    private static final Map<String, String> PROVIDER_IDS = new HashMap<>();

    /** TMDB's ids of the services whose apps are on this TV ("8|337"), from its list for your country (kept). */
    private static String installedProviders(PackageManager pm, String kind) {
        synchronized (PROVIDER_IDS) {
            String ids = PROVIDER_IDS.get(kind);
            if (ids != null) return ids;
        }
        List<String> ids = new ArrayList<>();
        try {
            JSONArray list = Recs.get("/watch/providers/" + kind + "?watch_region=" + Recs.region()).optJSONArray("results");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.getJSONObject(i);
                String[] app = app(p.optString("provider_name"));
                if (app != null && installed(pm, app[1])) ids.add(String.valueOf(p.optInt("provider_id")));
            }
        } catch (Exception e) {
            AppLog.w("spark providers: " + e.getMessage());
            return "";
        }
        String joined = join(ids, "|");
        synchronized (PROVIDER_IDS) {
            PROVIDER_IDS.put(kind, joined);
        }
        return joined;
    }

    /** The TV app for a TMDB service name: {display name, package}, or null. */
    static String[] app(String providerName) {
        String n = providerName.toLowerCase(Locale.ROOT);
        for (String[] a : MORE_APPS) if (n.contains(a[0])) return new String[] {a[1], a[2]};
        String[] s = Recs.service(providerName);
        return s == null ? null : new String[] {Recs.serviceName(s[1]), s[2]};
    }

    private static boolean installed(PackageManager pm, String pkg) {
        return HomeApps.launchIntent(pm, pkg) != null;
    }

    // MARK: - Other apps

    /** The TV's apps that take a search (and the ones TMDB doesn't know), each a tile that sends them `text`. */
    private static List<Catalog.Entry> otherApps(Context c, String text, Set<String> shown, Map<String, Drawable> art,
                                                 Set<String> icons) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>(shown);
        skip.add(c.getPackageName());
        skip.add("io.watchmatch");
        skip.add("io.watchmeplus");
        skip.add(Endpoints.PKG);
        List<String> pkgs = new ArrayList<>();
        try {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                String pkg = r.activityInfo.packageName;
                if (!skip.contains(pkg) && !pkgs.contains(pkg)) pkgs.add(pkg);
            }
        } catch (RuntimeException e) {
            AppLog.w("spark apps: " + e.getMessage());
        }
        List<Catalog.Entry> out = new ArrayList<>();
        for (String pkg : pkgs) {
            if (out.size() >= APPS_MAX) break;
            String search = searchComponent(pm, pkg);
            boolean known = java.util.Arrays.asList(NO_TMDB).contains(pkg);
            if (search == null && !known) continue;
            Catalog.Entry e = new Catalog.Entry();
            try {
                e.title = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException ex) {
                continue;
            }
            e.pkg = pkg;
            e.query = text;
            e.search = search;
            e.sub = "";
            boolean[] icon = new boolean[1];
            Drawable d = tileArt(pm, pkg, icon);
            if (d != null) art.put(pkg, d);
            if (icon[0]) icons.add(pkg);
            out.add(e);
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    /** The app's activity for ACTION_SEARCH, else the one with android.app.searchable, flattened; null when none. */
    static String searchComponent(PackageManager pm, String pkg) {
        try {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH).setPackage(pkg), 0)) {
                if (r.activityInfo.exported) return new ComponentName(pkg, r.activityInfo.name).flattenToString();
            }
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            for (int i = 0; info.activities != null && i < info.activities.length; i++) {
                ActivityInfo a = info.activities[i];
                if (a.exported && a.metaData != null && a.metaData.containsKey("android.app.searchable")) {
                    return new ComponentName(pkg, a.name).flattenToString();
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            AppLog.w("spark search " + pkg + ": " + e.getMessage());
        }
        return null;
    }

    /** The search `text` sent to an app's search screen (`component` from searchComponent). */
    static Intent searchIntent(String component, String text) {
        ComponentName cn = component == null ? null : ComponentName.unflattenFromString(component);
        if (cn == null || text == null) return null;
        return new Intent(Intent.ACTION_SEARCH).setComponent(cn).putExtra(SearchManager.QUERY, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The app's TV banner, else its icon (`icon[0]` says so). */
    private static Drawable tileArt(PackageManager pm, String pkg, boolean[] icon) {
        try {
            Intent launch = HomeApps.launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                if (ai.getBannerResource() != 0) {
                    Drawable d = pm.getResourcesForApplication(ai.applicationInfo)
                            .getDrawableForDensity(ai.getBannerResource(), DisplayMetrics.DENSITY_XHIGH, null);
                    if (d != null) return d;
                }
            }
            Drawable d = pm.getApplicationBanner(pkg);
            if (d != null) return d;
            icon[0] = true;
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    // MARK: - Helpers

    /** Runs the calls on the pool and waits; a call that fails gives nothing (its error goes to the log). */
    private static <T> List<T> all(List<Callable<T>> calls) {
        List<T> out = new ArrayList<>();
        try {
            for (Future<T> f : POOL.invokeAll(calls, WAIT_S, TimeUnit.SECONDS)) {
                try {
                    T t = f.get();
                    if (t != null) out.add(t);
                } catch (Exception e) {
                    AppLog.w("spark: " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /** "بريكينج باد" as said, and with the g sound written غ or ج, and "ينج" as "نج" (TMDB's Arabic names vary). */
    private static List<String> spellings(String text) {
        List<String> out = new ArrayList<>();
        out.add(text);
        String shorter = text.replace("ينج", "نج").replace("ينغ", "نغ");
        for (String base : new String[] {text, shorter}) {
            for (String g : new String[] {"غ", "ج"}) {
                String s = base.replaceAll("[جغكق](?=\\s|$)", g);
                if (!out.contains(s) && out.size() < 4) out.add(s);
            }
            if (!out.contains(base) && out.size() < 4) out.add(base);
        }
        return out;
    }

    private static boolean arabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '؀' && ch <= 'ۿ') return true;
        }
        return false;
    }

    /** Only Latin letters, digits and punctuation (no Arabic, Korean, Cyrillic…). */
    private static boolean latinOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetter(ch) && ch > 'ɏ') return false;
        }
        return true;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String join(List<String> parts) {
        return join(parts, " · ");
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) b.append(b.length() == 0 ? "" : sep).append(p);
        return b.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
