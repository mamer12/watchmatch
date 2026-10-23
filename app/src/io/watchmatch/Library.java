package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The catalog server (free on its own network), for our own player. Their TV app
 * stops at an empty screen when a field it expects comes back null; here every field may be missing or null, every
 * request is tried three times, and the last good answer is kept on disk, so the screen is never empty.
 */
final class Library {
    interface Callback<T> { void done(T result); }
    private static final String BACKDROPS = "https://image.tmdb.org/t/p/w1280", LOGOS = "https://image.tmdb.org/t/p/w500";
    private static final long FRESH_MS = 30 * 60_000L, FILES_FRESH_MS = 10 * 60_000L;
    private static final int TRIES = 3;
    private static final Pattern IMDB = Pattern.compile("tt\\d+");

    /** A movie, a series or one episode. */
    static final class Title {
        String id = "", title = "", other = "", overview = "", year = "", stars = "", genres = "";
        /** The catalog's language number of the title ("9" Arabic, "7" English…), "" when the list did not say. */
        String language = "";
        String poster = "", backdrop;     // backdrop: null until asked, "" when TMDB has none
        /** The title's logo (TMDB, transparent), "" when none; the catalog's wide cover art (banner titles), "" when none. */
        String logo = "", cover = "";
        String imdb = "", rootId = "";
        /** All the catalog's genre names of a title that came from a list (the English ones); empty when read from a copy. */
        List<String> categories = Collections.emptyList();
        boolean series;
        int season, episode, minutes;

        /** "2024 · ★ 7.1 · Action · Drama · 1h 52m" */
        String meta() {
            StringBuilder b = new StringBuilder();
            add(b, year);
            if (!stars.isEmpty() && !stars.startsWith("0")) add(b, "★ " + stars);
            add(b, genres);
            if (series) add(b, Ui.tr("Série", "Series", "Serie"));
            else if (minutes > 0) add(b, Ui.ar ? Arabic.duration(minutes) : minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m");
            return b.toString();
        }

        private static void add(StringBuilder b, String s) {
            if (s == null || s.isEmpty()) return;
            if (b.length() > 0) b.append("  ·  ");
            b.append(s);
        }
    }

    /** One row of titles. `path` loads it ("{page}" in it: more pages as the row is scrolled). */
    static final class Row {
        final String key, title, path;
        final List<Title> items = new ArrayList<>();
        boolean loading, loaded, more = true;
        int page;

        Row(String key, String title, String path) {
            this.key = key;
            this.title = title;
            this.path = path;
        }
    }

    /** A playable file at one resolution. */
    static final class Source {
        String label = "", url = "";
        int height;
    }

    /** Subtitle files for one video: Arabic and English (either can be ""). */
    static final class Subtitles {
        String arabic = "", english = "";
    }

    /** Reset: forgets the wide photos and logos found for titles. */
    void forgetArt() {
        art.edit().clear().commit();
    }

    /**
     * Gets playback ready ahead of Play (the mirror only): the signed link of the file the player will start with is
     * resolved now (Streamer keeps it until it nears its expiry), so Play doesn't wait for the redirect.
     */
    void warmPlayback(final Title t) {
        if (!QualityRules.adaptive(Endpoints.active().id) || t == null || t.id.isEmpty() || t.series && t.rootId.isEmpty()) return;
        io.execute(() -> {
            try {
                List<Source> list = sources(t.id);
                if (!list.isEmpty()) Streamer.probe(PlayerActivity.start(context, list).url);
            } catch (Exception e) {
                AppLog.w("warm playback: " + e.getMessage());
            }
        });
    }

    private static Library instance;

    private final Context context;
    private final File dir;
    private final SharedPreferences art;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    /** Titles and stories in Arabic first (CinemaPrefs.arabicTitles), read by the parser. */
    static volatile boolean arabicFirst;

    private Library(Context c) {
        context = c;
        arabicFirst = CinemaPrefs.arabicTitles(c);
        dir = new File(c.getCacheDir(), "library");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        art = LegacyPrefs.open(c, "art", Context.MODE_PRIVATE);
    }

    /** The family filter, as the catalog's "level". */
    private String level() {
        return String.valueOf(CinemaPrefs.level(context));
    }

    // MARK: - Rows for each page

    static final String HOME = "home", MOVIES = "movies", SERIES = "series";
    private static final String[][] GENRES = {
        {"84", "Ação", "Action", "Acción"}, {"59", "Comédia", "Comedy", "Comedia"}, {"62", "Drama", "Drama", "Drama"},
        {"80", "Suspense", "Thriller", "Suspense"}, {"60", "Crime", "Crime", "Crimen"},
        {"78", "Ficção científica", "Sci-Fi", "Ciencia ficción"}, {"70", "Terror", "Horror", "Terror"},
        {"77", "Romance", "Romance", "Romance"}, {"56", "Aventura", "Adventure", "Aventura"},
        {"57", "Animação", "Animation", "Animación"}, {"67", "Fantasia", "Fantasy", "Fantasía"},
        {"76", "Mistério", "Mystery", "Misterio"}, {"61", "Documentários", "Documentaries", "Documentales"},
    };

    /** Movies and Series: the newest, then one row per genre (recent years first, the way Netflix fills them). */
    static List<Row> rows(String page) {
        List<Row> out = new ArrayList<>();
        if (HOME.equals(page)) return out;   // the home page comes from the server's own groups (home())
        boolean movies = MOVIES.equals(page);
        String type = movies ? "movies" : "series";
        out.add(new Row(page + "-new", movies ? Ui.tr("Filmes novos", "New movies", "Películas nuevas")
                : Ui.tr("Séries novas", "New series", "Series nuevas"),
                (movies ? "latestMovies" : "latestSeries") + "/level/{level}/itemsPerPage/24/page/{page}/"));
        for (String[] g : GENRES) {
            out.add(new Row(page + "-" + g[0], Ui.tr(g[1], g[2], g[3]),
                    "AdvancedSearch?level={level}&type=" + type + "&category_id=" + g[0] + "&year=2010,2030&page={page}"));
        }
        return out;
    }

    /** The home page: the server's curated groups, then the newest movies and series. */
    void home(final Callback<List<Row>> done) {
        io.execute(() -> {
            List<Row> out = new ArrayList<>();
            try {
                JSONObject o = new JSONObject(load("videoGroups/lang/" + (arabicFirst ? "ar" : "en") + "/level/" + level(), FRESH_MS));
                JSONArray groups = o.optJSONArray("groups");
                for (int i = 0; groups != null && i < groups.length(); i++) {
                    JSONObject g = groups.optJSONObject(i);
                    if (g == null) continue;
                    Row r = new Row("group-" + s(g, "groupsID"), s(g, "title").trim(), null);
                    r.items.addAll(titles(g.optJSONArray("content")));
                    r.loaded = true;
                    r.more = false;
                    if (!r.items.isEmpty()) out.add(r);
                }
            } catch (Exception e) {
                AppLog.w("catalog groups: " + e);
            }
            for (Row r : rows(MOVIES)) if (r.key.endsWith("-new")) out.add(Math.min(1, out.size()), r);
            for (Row r : rows(SERIES)) if (r.key.endsWith("-new")) out.add(Math.min(2, out.size()), r);
            main.post(() -> done.done(out));
        });
    }

    /** The row's next page; `done` hears whether anything was added. */
    void more(final Row r, final Callback<Boolean> done) {
        if (r.loading || !r.more || r.path == null) return;
        r.loading = true;
        final int page = r.page;
        io.execute(() -> {
            List<Title> got = null;
            try {
                got = titles(new JSONArray(load(r.path.replace("{page}", String.valueOf(page)).replace("{level}", level()), FRESH_MS)));
            } catch (Exception e) {
                AppLog.w("catalog row " + r.key + ": " + e);
            }
            final List<Title> list = got;
            main.post(() -> {
                r.loading = false;
                r.loaded = true;
                if (list == null) {
                    done.done(false);
                    return;
                }
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Title t : r.items) seen.add(t.id);
                int before = r.items.size();
                for (Title t : list) if (seen.add(t.id)) r.items.add(t);
                r.page = page + 1;
                r.more = r.items.size() > before && r.items.size() < 240;
                done.done(r.items.size() > before);
            });
        });
    }

    void search(final String query, final Callback<List<Title>> done) {
        io.execute(() -> {
            List<Title> out = new ArrayList<>();
            try {
                String q = URLEncoder.encode(query.trim(), "UTF-8");
                List<Title> movies = titles(new JSONArray(load("AdvancedSearch?level=" + level() + "&videoTitle=" + q + "&type=movies&page=0", 0)));
                List<Title> series = titles(new JSONArray(load("AdvancedSearch?level=" + level() + "&videoTitle=" + q + "&type=series&page=0", 0)));
                // In turns, so the best match of each kind is near the start.
                for (int i = 0; i < Math.max(movies.size(), series.size()); i++) {
                    if (i < movies.size()) out.add(movies.get(i));
                    if (i < series.size()) out.add(series.get(i));
                }
                // Then the best matches first, the same Arabic spelled any way (SearchRank); stable, so the turns stay.
                final String want = SearchRank.normalize(query);
                final java.util.Map<Title, Integer> rank = new java.util.IdentityHashMap<>();
                for (Title t : out) rank.put(t, SearchRank.score(t.title, t.other, want));
                Collections.sort(out, (a, b) -> Integer.compare(rank.get(a), rank.get(b)));
            } catch (Exception e) {
                AppLog.w("catalog search: " + e);
            }
            main.post(() -> done.done(out));
        });
    }

    /** One title by its the catalog id (null when it can't be found). */
    void title(final String id, final Callback<Title> done) {
        io.execute(() -> {
            Title t = null;
            try {
                t = title(new JSONObject(load("allVideoInfo/id/" + id, FRESH_MS)));
                if (t.id.isEmpty()) t.id = id;
            } catch (Exception e) {
                AppLog.w("catalog title " + id + ": " + e);
            }
            final Title found = t;
            main.post(() -> done.done(found));
        });
    }

    /** Titles matching `query` (type "movies" or "series"), on a background thread; empty when it fails. */
    List<Title> searchNow(String query, String type, long freshMs) {
        try {
            String q = URLEncoder.encode(query.trim(), "UTF-8");
            return titles(new JSONArray(load("AdvancedSearch?level=" + level() + "&videoTitle=" + q + "&type=" + type + "&page=0", freshMs)));
        } catch (Exception e) {
            AppLog.w("catalog search: " + e);
            return new ArrayList<>();
        }
    }

    /** AdvancedSearch for `params` ("type=movies&category_id=84&page=0"), the family level added; background thread, empty on failure. */
    List<Title> advancedNow(String params, long freshMs) {
        try {
            return titles(new JSONArray(load("AdvancedSearch?level=" + level() + "&" + params, freshMs)));
        } catch (Exception e) {
            AppLog.w("catalog advanced: " + e);
            return new ArrayList<>();
        }
    }

    /** One page of a genre (the catalog's category id), newest years first; background thread. */
    List<Title> genreNow(String categoryId, String type, int page) {
        try {
            return titles(new JSONArray(load("AdvancedSearch?level=" + level() + "&type=" + type + "&category_id=" + categoryId
                    + "&year=2012,2030&page=" + page, FRESH_MS)));
        } catch (Exception e) {
            AppLog.w("catalog genre: " + e);
            return new ArrayList<>();
        }
    }

    /** Runs `work` on the catalog's background threads. */
    void background(Runnable work) {
        io.execute(work);
    }

    /** A series' episodes, by season and number. */
    void episodes(final String seriesId, final Callback<List<Title>> done) {
        io.execute(() -> {
            final List<Title> list = episodesNow(seriesId);
            main.post(() -> done.done(list));
        });
    }

    private final Map<String, Long> staleAsked = new HashMap<>();

    /** True at most once in 10 minutes per series (a list that really lacks the episode is not asked for every time). */
    private synchronized boolean refreshedAt(String seriesId) {
        long now = System.currentTimeMillis();
        Long was = staleAsked.get(seriesId);
        if (was != null && now - was < 10 * 60_000L) return false;
        staleAsked.put(seriesId, now);
        return true;
    }

    /** The same, on the caller's (background) thread; empty when it fails. */
    List<Title> episodesNow(String seriesId) {
        List<Title> out = new ArrayList<>();
        try {
            out = titles(new JSONArray(load("videoSeason/id/" + seriesId, FRESH_MS)));
            Collections.sort(out, (a, b) -> a.season != b.season ? a.season - b.season : a.episode - b.episode);
            for (Title t : out) t.rootId = seriesId;
            // Something of the series was saved past the last listed episode: the cached list is old, ask again.
            if (Progress.listIsStale(out, Progress.entriesFor(context, out)) && refreshedAt(seriesId)) {
                List<Title> fresh = titles(new JSONArray(load("videoSeason/id/" + seriesId, 0)));
                Collections.sort(fresh, (a, b) -> a.season != b.season ? a.season - b.season : a.episode - b.episode);
                for (Title t : fresh) t.rootId = seriesId;
                if (!fresh.isEmpty()) out = fresh;
            }
        } catch (Exception e) {
            AppLog.w("catalog episodes: " + e);
        }
        return out;
    }

    // MARK: - Playing (called on a background thread by the player)

    /** The video's files, lowest resolution first. */
    List<Source> sources(String id) throws IOException, JSONException {
        // Kept a few minutes: the preview just asked, and the player asks again on Play (the links last days).
        JSONArray a = new JSONArray(load("transcoddedFiles/id/" + id, FILES_FRESH_MS));
        List<Source> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || s(o, "videoUrl").isEmpty()) continue;
            Source src = new Source();
            src.url = s(o, "videoUrl");
            src.label = s(o, "resolution");
            src.height = number(src.label.replaceAll("\\D", ""));
            out.add(src);
        }
        Collections.sort(out, (x, y) -> x.height - y.height);
        return out;
    }

    Subtitles subtitles(String id) {
        Subtitles out = new Subtitles();
        try {
            JSONObject o = new JSONObject(load("translationFiles/id/" + id, FILES_FRESH_MS));
            out.arabic = s(o, "arTranslationFilePath");
            out.english = s(o, "enTranslationFilePath");
        } catch (Exception e) {
            AppLog.w("catalog subtitles: " + e);
        }
        return out;
    }

    /** What the title list doesn't carry: age rating, cast, trailer, and the parts to skip. */
    static final class Info {
        String rating = "", cast = "", director = "", trailer = "";
        /** {start, end} in ms: intros ("Skip intro"), and the scenes the catalog marked for families. */
        final List<long[]> intros = new ArrayList<>(), scenes = new ArrayList<>();
        /** Where the credits start (ms), when TheIntroDB knows; -1 otherwise (NextUpRules then estimates). */
        long credits = -1;
    }

    private static final String[] FILM_RATINGS = {"", "G", "PG", "PG-13", "R", "NC-17", "", ""};
    private static final String[] SERIES_RATINGS = {"", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14", "TV-MA"};

    /** The title's details (on a background thread); an empty Info when they can't be read. */
    Info info(String id) {
        Info out = new Info();
        try {
            JSONObject o = new JSONObject(load("allVideoInfo/id/" + id, FRESH_MS));
            boolean series = "2".equals(s(o, "kind"));
            int r = number(s(o, series ? "seriesRating" : "filmRating"));
            String[] names = series ? SERIES_RATINGS : FILM_RATINGS;
            out.rating = r > 0 && r < names.length ? names[r] : "";
            out.cast = names(o.optJSONArray("actorsInfo"), 4);
            out.director = names(o.optJSONArray("directorsInfo"), 2);
            String trailer = s(o, "trailer");
            out.trailer = trailer.startsWith("http") ? trailer : "";
            JSONArray a = o.optJSONArray("introSkipping");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x != null) range(out.intros, s(x, "start"), s(x, "end"), 5000);
            }
            JSONObject skip = o.optJSONObject("skippingDurations");
            JSONArray starts = skip == null ? null : skip.optJSONArray("start"), ends = skip == null ? null : skip.optJSONArray("end");
            for (int i = 0; starts != null && ends != null && i < Math.min(starts.length(), ends.length()); i++) {
                range(out.scenes, starts.optString(i), ends.optString(i), 500);
            }
        } catch (Exception e) {
            AppLog.w("catalog info " + id + ": " + e);
        }
        return out;
    }

    void info(final String id, final Callback<Info> done) {
        io.execute(() -> {
            final Info i = info(id);
            main.post(() -> done.done(i));
        });
    }

    private static void range(List<long[]> out, String start, String end, long minMs) {
        long a = Math.round(decimal(start) * 1000), b = Math.round(decimal(end) * 1000);
        if (b - a >= minMs) out.add(new long[] {a, b});
    }

    /** "Aaron Paul, Bryan Cranston" (the first `max`). */
    private static String names(JSONArray a, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0, n = 0; a != null && i < a.length() && n < max; i++) {
            JSONObject p = a.optJSONObject(i);
            String name = p == null ? "" : s(p, "name").trim();
            if (name.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(name);
            n++;
        }
        return b.toString();
    }

    // MARK: - Wide photos (TMDB, through the IMDb id; the catalog only has posters)

    /** Fills t.backdrop ("" when there's none) in the background; `done` runs only when it changed. */
    void backdrop(final Title t, final Runnable done) {
        if (t.backdrop != null) return;
        if (t.imdb.isEmpty() || !Recs.hasKey(context)) {
            t.backdrop = "";
            return;
        }
        final String key = "v2|" + (arabicFirst ? "ar" : "en") + "|" + t.imdb;
        String known = art.getString(key, null);
        if (known != null) {
            setArt(t, known);
            return;
        }
        t.backdrop = "";      // asked once at a time
        io.execute(() -> {
            String found;
            try {
                found = tmdbArt(t);
            } catch (Exception e) {
                AppLog.w("catalog art " + t.imdb + ": " + e);
                return;   // asked again next time
            }
            art.edit().putString(key, found).apply();
            main.post(() -> {
                setArt(t, found);
                if (!t.backdrop.isEmpty() || !t.logo.isEmpty()) done.run();
            });
        });
    }

    /** "backdrop\nlogo" (either can be ""). */
    private static void setArt(Title t, String stored) {
        int nl = stored.indexOf('\n');
        t.backdrop = nl < 0 ? stored : stored.substring(0, nl);
        t.logo = nl < 0 ? "" : stored.substring(nl + 1);
    }

    /**
     * TMDB's wide photo without text (the largest) and the title's logo in the language of the titles (else English,
     * else any), found through the IMDb id: "backdrop\nlogo".
     */
    private static String tmdbArt(Title t) throws IOException, JSONException {
        JSONObject o = Recs.get("/find/" + t.imdb + "?external_source=imdb_id");
        boolean tv = t.series;
        JSONArray a = o.optJSONArray(tv ? "tv_results" : "movie_results");
        if (a == null || a.length() == 0) {
            tv = !tv;
            a = o.optJSONArray(tv ? "tv_results" : "movie_results");
        }
        JSONObject first = a == null ? null : a.optJSONObject(0);
        if (first == null) return "\n";
        String backdrop = s(first, "backdrop_path"), logo = "";
        try {
            JSONObject images = Recs.get("/" + (tv ? "tv" : "movie") + "/" + first.optLong("id")
                    + "/images?include_image_language=" + (arabicFirst ? "ar,en" : "en,ar") + ",null");
            String better = bestBackdrop(images.optJSONArray("backdrops"));
            if (!better.isEmpty()) backdrop = better;
            logo = bestLogo(images.optJSONArray("logos"));
        } catch (Exception e) {
            AppLog.w("catalog images " + t.imdb + ": " + e);
        }
        return (backdrop.isEmpty() ? "" : BACKDROPS + backdrop) + "\n" + (logo.isEmpty() ? "" : LOGOS + logo);
    }

    private static String bestBackdrop(JSONArray list) {
        String best = "";
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || s(b, "file_path").isEmpty()) continue;
            double score = (b.isNull("iso_639_1") ? 1e7 : 0) + Math.min(b.optInt("width"), 3840) * 100 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = s(b, "file_path");
            }
        }
        return best;
    }

    /** The logo in the titles' language first, then English; PNGs (SVGs can't be drawn here), the best rated. */
    private static String bestLogo(JSONArray list) {
        String want = arabicFirst ? "ar" : "en", best = "";
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            String path = l == null ? "" : s(l, "file_path");
            if (!path.endsWith(".png")) continue;
            String lang = s(l, "iso_639_1");
            double score = (lang.equals(want) ? 2e3 : lang.equals("en") ? 1e3 : 0) + l.optDouble("vote_average", 0) * 10
                    + l.optInt("vote_count");
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    /** The catalog's banner titles (its website's carousel), with their wide cover art. */
    void banner(final Callback<List<Title>> done) {
        io.execute(() -> {
            List<Title> out = new ArrayList<>();
            try {
                out = titles(new JSONArray(load("banner/level/" + level(), FRESH_MS)));
            } catch (Exception e) {
                AppLog.w("catalog banner: " + e);
            }
            final List<Title> list = out;
            main.post(() -> done.done(list));
        });
    }

    // MARK: - Parsing (anything may be missing or null)

    static List<Title> titles(JSONArray a) {
        List<Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Title t = title(o);
            if (!t.id.isEmpty() && !t.title.isEmpty()) out.add(t);
        }
        return out;
    }

    static Title title(JSONObject o) {
        Title t = new Title();
        t.id = s(o, "nb");
        String en = s(o, "en_title").trim(), ar = s(o, "ar_title").trim();
        t.title = arabicFirst && !ar.isEmpty() || en.isEmpty() ? ar : en;
        t.other = t.title.equals(ar) ? en : ar;
        String enText = s(o, "en_content").trim(), arText = s(o, "ar_content").trim();
        t.overview = (arabicFirst && !arText.isEmpty() || enText.isEmpty() ? arText : enText).replaceAll("\\s+", " ");
        t.year = s(o, "year");
        t.stars = s(o, "stars");
        t.language = s(o, "language_lighttigerNb");
        t.series = "2".equals(s(o, "kind"));
        t.season = number(s(o, "season"));
        t.episode = number(s(o, "episodeNummer"));
        t.minutes = (int) Math.round(decimal(s(o, "duration")) / 60);
        String img = s(o, "imgObjUrl"), medium = s(o, "imgMediumThumbObjUrl"), tiny = s(o, "imgThumbObjUrl");
        if (img.contains("cover-images")) {
            // A banner title: its "image" is the wide cover art; the poster is a path on the same host.
            t.cover = absolute(img);
            t.poster = absolute(first(medium, tiny));
        } else {
            // Cards take the small poster on the mirror (about 30 KB, not 285), the full one elsewhere.
            t.poster = absolute(ImageRules.cardPoster(Endpoints.active().id, img, medium, tiny));
        }
        Matcher m = IMDB.matcher(s(o, "imdbUrlRef"));
        t.imdb = m.find() ? m.group() : "";
        StringBuilder genres = new StringBuilder();
        JSONArray cats = o.optJSONArray("categories");
        for (int i = 0, n = 0; cats != null && i < cats.length() && n < 2; i++) {
            JSONObject c = cats.optJSONObject(i);
            String name = c == null ? "" : s(c, "en_title");
            if (name.isEmpty() || name.equalsIgnoreCase("uncategorized")) continue;
            if (genres.length() > 0) genres.append(" · ");
            genres.append(name);
            n++;
        }
        t.genres = genres.toString();
        List<String> all = new ArrayList<>();
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject c = cats.optJSONObject(i);
            String name = c == null ? "" : s(c, "en_title");
            if (!name.isEmpty() && !name.equalsIgnoreCase("uncategorized")) all.add(name);
        }
        t.categories = Collections.unmodifiableList(all);
        return t;
    }

    /** Our own copy of a title (the player's playlist, the saved progress). */
    static JSONObject json(Title t) throws JSONException {
        return new JSONObject().put("id", t.id).put("title", t.title).put("other", t.other).put("overview", t.overview)
                .put("year", t.year).put("stars", t.stars).put("genres", t.genres).put("poster", t.poster)
                .put("backdrop", t.backdrop == null ? "" : t.backdrop).put("logo", t.logo).put("cover", t.cover)
                .put("imdb", t.imdb).put("root", t.rootId)
                .put("series", t.series).put("season", t.season).put("episode", t.episode).put("minutes", t.minutes);
    }

    static Title fromJson(JSONObject o) {
        Title t = new Title();
        t.id = s(o, "id");
        t.title = s(o, "title");
        t.other = s(o, "other");
        t.overview = s(o, "overview");
        t.year = s(o, "year");
        t.stars = s(o, "stars");
        t.genres = s(o, "genres");
        t.poster = Endpoints.image(s(o, "poster"));
        String backdrop = s(o, "backdrop");
        t.backdrop = backdrop.isEmpty() ? null : backdrop;
        t.logo = s(o, "logo");
        t.cover = Endpoints.image(s(o, "cover"));
        t.imdb = s(o, "imdb");
        t.rootId = s(o, "root");
        t.series = o.optBoolean("series");
        t.season = o.optInt("season");
        t.episode = o.optInt("episode");
        t.minutes = o.optInt("minutes");
        return t;
    }

    /** An artwork link as the provider in use serves it; a bare path goes on its image host. */
    private static String absolute(String path) {
        if (path.isEmpty()) return path;
        if (path.startsWith("http")) return Endpoints.image(path);
        String host = Endpoints.active().imageHost();
        return host.isEmpty() ? path : "https://" + host + "/" + path;
    }

    /** The field as text: "" when it's missing or null (optString would give "null"). */
    static String s(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        Object v = o.opt(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double decimal(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // MARK: - Network: three tries, then the last good answer on disk

    /** The answer for `path`: from disk while younger than `freshMs`, else the network, else whatever is on disk. */
    private String load(String path, long freshMs) throws IOException {
        File f = new File(dir, Integer.toHexString(path.hashCode()) + "-" + path.length() + ".json");
        if (freshMs > 0 && f.exists() && System.currentTimeMillis() - f.lastModified() < freshMs) {
            String cached = readFile(f);
            if (cached != null) return cached;
        }
        IOException last = null;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            if (attempt > 0) pause(attempt * 700L);
            try {
                String body = fetch(Endpoints.apiBase() + path);
                writeFile(f, body);
                return body;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) Provider.catalogResult(false, last.getMessage());
        String stale = f.exists() ? readFile(f) : null;
        if (stale != null) {
            AppLog.w("catalog offline, last answer for " + path);
            return stale;
        }
        throw last != null ? last : new IOException("no answer");
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            String body = read(c.getInputStream());
            // An empty or HTML answer (the server sometimes sends one) is a failure, not an empty list.
            String t = body.trim();
            if (t.isEmpty() || (t.charAt(0) != '[' && t.charAt(0) != '{')) throw new IOException("not JSON");
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File f, String body) {
        File part = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(f);
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
