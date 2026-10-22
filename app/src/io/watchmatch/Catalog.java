package io.watchmatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's rows below the featured title: the Top 10, your YouTube (signed in), what's new and featured on
 * The catalog (its public catalog), the matches and new movies coming up on TOD (its website's featured lists) and
 * what's popular on each streaming app, made only of what the TV's streaming apps have (TMDB, by country). Fetched in the background every few hours and
 * kept on disk, so the rows show right away when the TV turns on.
 */
final class Catalog {
    static final int POSTER = 0, WIDE = 1, VIDEO = 2, COVER = 3;     // COVER: a poster without a rank

    static final class Entry {
        String title = "", sub = "", image, still, pkg, video;     // image: as the row shows it; still: 16:9
        String query;       // Library: the title to search for there (it has no links to its titles)
        String link;        // a link the app opens on this title (TOD)
        String id;          // Library: the title's number (the in-app catalog page opens it)
        String overview = "", wide;    // for the featured carousel: a line or two, and a 16:9 photo big enough
        String imdb;        // The catalog: to find a wide photo on TMDB
        String tmdb;        // "movie:123" / "tv:456": to find the title's page in its app (Links)
        String search;      // Spark: the app's search screen (a flattened component) that gets `query`
        float progress = -1;        // Continue watching: how far it was watched (0 to 1), or -1
        long at;                    // a match: its kick-off (epoch ms)
        Matches.Match match;        // Match Centre: the match this card is (drawn as crests and score, not a photo)
    }

    static final class Row {
        String key;
        int style;
        final List<Entry> items = new ArrayList<>();
    }

    interface Listener { void onCatalog(); }

    private static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final String YOUTUBE_PKG = "com.google.android.youtube.tv";
    private static final String TOD = "https://www.tod.tv/en/", TOD_PKG = "com.todtv.tod";
    private static final long TOD_EVERY = 3600_000L, RECS_EVERY = 3 * 3600_000L;
    private static final java.util.regex.Pattern IMDB_ID = java.util.regex.Pattern.compile("tt\\d{5,}");
    /** TOD's site answers only browsers (other agents get "Request Rejected"). */
    private static final String BROWSER = "Mozilla/5.0 (Linux; Android 11; Smart TV) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0.0.0 Safari/537.36";
    private static final long TMDB_EVERY = 6 * 3600_000L, YOUTUBE_EVERY = 2 * 3600_000L;
    private static final int ROW_MAX = 20, PER_SERVICE = 10;
    /** Bump when the rows change (the saved ones are fetched again). */
    private static final int VERSION = 9;

    private static Catalog instance;

    private final Context context;
    private final File file;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Row> tmdbRows = new ArrayList<>();
    private volatile List<Row> libraryRows = new ArrayList<>();
    private volatile List<Row> todRows = new ArrayList<>();
    private volatile Row ytRecs;      // signed in to YouTube: WatchMatch's picks for you (YouTubeAccount.recommended)
    private long accountAt;
    private long tmdbAt, libraryAt, todAt;
    /** A fetch failed or came back empty: tried again after a wait, the rows on disk staying meanwhile. */
    private final Backoff backoff = new Backoff();
    private final Runnable retry = () -> {
        if (!listeners.isEmpty()) refreshIfStale();     // only while the home screen is showing
    };
    private String stamp = "";
    private boolean fetching;

    static synchronized Catalog get(Context c) {
        if (instance == null) instance = new Catalog(c.getApplicationContext());
        return instance;
    }

    private Catalog(Context c) {
        context = c;
        file = new File(c.getFilesDir(), "catalog.json");
        load();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The rows in order: TOD, the Top 10, your YouTube, the catalog, then one per app; empty ones are left out. */
    List<Row> rows() {
        List<Row> out = new ArrayList<>();
        List<Row> tmdb = tmdbRows;
        if (installed(TOD_PKG)) for (Row r : todRows) if (!r.items.isEmpty()) out.add(r);
        if (!tmdb.isEmpty() && tmdb.get(0).key.equals("top")) out.add(tmdb.get(0));
        Row recs = ytRecs;
        if (recs != null && !recs.items.isEmpty() && installed(YOUTUBE_PKG)) out.add(recs);
        for (Row r : libraryRows) if (!r.items.isEmpty()) out.add(r);     // Watchme+ is always there (WatchMatch has a copy)
        for (Row r : tmdb) if (r.key.startsWith("on:") && !r.items.isEmpty()) out.add(r);
        return out;
    }

    /** Signed in or out of YouTube just now: fetch (or drop) its rows right away. */
    void accountChanged() {
        accountAt = 0;
        if (!YouTubeAccount.signedIn(context)) {
            ytRecs = null;
            save();
        }
        backoff.reset();
        refreshIfStale();
    }

    /** The catalog server changed (Provider): its rows and their pictures came from the old one, so they're fetched again now. */
    void providerChanged() {
        libraryRows = new ArrayList<>();
        libraryAt = 0;
        backoff.reset();
        refreshIfStale();
        for (Listener l : new ArrayList<>(listeners)) l.onCatalog();
    }

    /** The network came up or the clock was set: whatever failed is fetched again now. */
    void retryNow() {
        backoff.reset();
        refreshIfStale();
    }

    /**
     * The featured carousel on Home: the catalog's featured titles, YouTube picked for you (when signed in), Apple TV's
     * popular ones and TOD's upcoming matches, one from each in turn (only those with a wide photo).
     */
    List<Entry> features() {
        List<List<Entry>> lists = new ArrayList<>();
        List<Row> all = rows();
        for (String key : new String[] {"library:featured", "yt:recs", "on:appletv", "tod:games"}) {    // The catalog first
            for (Row r : all) {
                if (!r.key.equals(key)) continue;
                List<Entry> list = new ArrayList<>();
                for (Entry e : r.items) if (e.wide != null && list.size() < 4) list.add(e);
                lists.add(list);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) for (List<Entry> l : lists) if (i < l.size()) out.add(l.get(i));
        return out;
    }

    /** A row's name on screen. */
    static String title(String key) {
        if (key.startsWith("on:")) {
            String name = Recs.serviceName(key.substring(3));
            return Ui.tr("Populares no ", "Popular on ", "Populares en ") + Ui.isolate(name);
        }
        switch (key) {
            case "top": return Ui.tr("Top 10 da semana", "Top 10 this week", "Top 10 de la semana");
            case "yt:recs": return Ui.tr("Recomendados para você", "Recommended for you", "Recomendados para ti");
            case "tod:games": return Ui.tr("Próximos jogos", "Upcoming matches", "Próximos partidos");
            case "tod:new": return Ui.tr("Novidades na TOD", "New on TOD", "Nuevo en TOD");
            case "library:new": return Ui.tr("Novos no ", "New on ", "Nuevos en ") + Ui.isolate(WatchMe.NAME);
            case "library:featured": return Ui.tr("Destaques do ", "Featured on ", "Destacados en ") + Ui.isolate(WatchMe.NAME);
            default: return key;
        }
    }

    void refreshIfStale() {
        if (fetching || backoff.waiting()) return;
        long now = TrustedTime.now();
        String want = sourceStamp();
        final boolean tmdb = Recs.hasKey(context) && (!want.equals(stamp) || now - tmdbAt > TMDB_EVERY || now < tmdbAt);
        final boolean cin = !want.equals(stamp) || now - libraryAt > YOUTUBE_EVERY || now < libraryAt;
        final boolean td = installed(TOD_PKG) && (!want.equals(stamp) || now - todAt > TOD_EVERY || now < todAt);
        final boolean acct = YouTubeAccount.signedIn(context) && installed(YOUTUBE_PKG)
                && (now - accountAt > RECS_EVERY || now < accountAt);
        if (!tmdb && !cin && !td && !acct) return;
        fetching = true;
        io.execute(() -> {
            boolean failed = false;
            try {
                if (tmdb) {
                    tmdbRows = keep(fetchTmdb(), tmdbRows);
                    tmdbAt = TrustedTime.now();
                }
            } catch (Exception e) {
                failed = true;
                if (tmdb) tmdbAt = 0;      // stale: the next try fetches it again
                AppLog.w("rows: " + e);
            }
            try {
                if (acct) {
                    Row recs = new Row();
                    recs.key = "yt:recs";
                    recs.style = VIDEO;
                    recs.items.addAll(YouTubeAccount.recommended(context, Watched.list(context)));
                    if (recs.items.isEmpty() && ytRecs != null && !ytRecs.items.isEmpty()) throw new IOException("no videos");
                    ytRecs = recs;
                    accountAt = TrustedTime.now();
                }
            } catch (Exception e) {
                failed = true;
                if (acct) accountAt = 0;
                AppLog.w("YouTube account: " + e.getMessage());
            }
            try {
                if (cin) {
                    libraryRows = keep(fetchLibrary(), libraryRows);
                    libraryAt = TrustedTime.now();
                }
            } catch (Exception e) {
                failed = true;
                if (cin) libraryAt = 0;
                AppLog.w("catalog rows: " + e);
            }
            try {
                if (td) {
                    todRows = keep(fetchTod(), todRows);
                    todAt = TrustedTime.now();
                }
            } catch (Exception e) {
                failed = true;
                if (td) todAt = 0;
                AppLog.w("TOD row: " + e);
            }
            stamp = want;
            save();
            fetching = false;
            final boolean again = failed;
            main.post(() -> {
                if (again) {
                    backoff.failed();
                    main.removeCallbacks(retry);
                    main.postDelayed(retry, backoff.delay());
                } else {
                    backoff.succeeded();
                }
                for (Listener l : new ArrayList<>(listeners)) l.onCatalog();
            });
        });
    }

    /**
     * Fresh rows, an empty one replaced by the one it had (a list that didn't load mustn't blank a row). Throws when
     * all came back empty: that's a failure (the old rows stay, and it's tried again).
     */
    private static List<Row> keep(List<Row> fresh, List<Row> old) throws IOException {
        boolean any = false;
        for (Row r : fresh) any |= !r.items.isEmpty();
        if (!any) throw new IOException("no titles came back");
        List<Row> out = new ArrayList<>();
        for (Row r : fresh) {
            Row use = r;
            // Not the matches: none coming up is an answer (a failure to read them throws), and old ones are over.
            if (r.items.isEmpty() && !r.key.equals("tod:games")) {
                for (Row o : old) if (o.key.equals(r.key) && !o.items.isEmpty()) use = o;
            }
            out.add(use);
        }
        return out;
    }

    // MARK: - TMDB

    private List<Row> fetchTmdb() throws Exception {
        String region = Recs.region();
        // The TV's services by their TMDB ids in this country ("350|2": a service can have several).
        Map<String, String[]> services = new LinkedHashMap<>();
        Map<String, StringBuilder> ids = new LinkedHashMap<>();
        JSONArray providers = Recs.get("/watch/providers/movie?watch_region=" + region).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = Recs.service(o.optString("provider_name"));
            if (s == null || !installed(s[2])) continue;
            services.put(s[1], s);
            StringBuilder b = ids.get(s[1]);
            if (b == null) ids.put(s[1], new StringBuilder().append(o.getInt("provider_id")));
            else b.append('|').append(o.getInt("provider_id"));
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        List<Row> rows = new ArrayList<>();
        rows.add(top(services, ids, region));
        // A row per app: its most popular titles right now.
        for (Map.Entry<String, StringBuilder> e : ids.entrySet()) {
            String[] s = services.get(e.getKey());
            Row row = new Row();
            row.key = "on:" + s[1];
            row.style = WIDE;
            List<List<Entry>> lists = new ArrayList<>();
            lists.add(discover("movie", "", e.getValue().toString(), region, s, WIDE));
            lists.add(discover("tv", "", e.getValue().toString(), region, s, WIDE));
            addInTurns(row, lists);
            rows.add(row);
        }
        return rows;
    }

    /** The 10 most popular titles right now across the TV's services (movies and shows together). */
    private Row top(Map<String, String[]> services, Map<String, StringBuilder> ids, String region) throws Exception {
        final Map<Entry, Double> popularity = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> e : ids.entrySet()) {
            String[] s = services.get(e.getKey());
            for (String kind : new String[] {"movie", "tv"}) {
                JSONArray results = query(kind, "&vote_count.gte=50", e.getValue().toString(), region);
                for (int i = 0; i < results.length() && i < PER_SERVICE; i++) {
                    JSONObject o = results.getJSONObject(i);
                    Entry entry = entry(o, kind, s, POSTER);
                    if (entry != null) popularity.put(entry, o.optDouble("popularity", 0));
                }
            }
        }
        List<Entry> all = new ArrayList<>(popularity.keySet());
        Collections.sort(all, (a, b) -> Double.compare(popularity.get(b), popularity.get(a)));
        Row row = new Row();
        row.key = "top";
        row.style = POSTER;
        Set<String> seen = new HashSet<>();
        for (Entry entry : all) if (row.items.size() < 10 && seen.add(entry.title)) row.items.add(entry);
        return row;
    }

    private List<Entry> discover(String kind, String filter, String providerIds, String region, String[] service, int style) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray results = query(kind, filter + "&vote_count.gte=20", providerIds, region);
            for (int i = 0; i < results.length() && out.size() < PER_SERVICE; i++) {
                Entry entry = entry(results.getJSONObject(i), kind, service, style);
                if (entry != null) out.add(entry);
            }
        } catch (Exception e) {
            AppLog.w("row " + kind + filter + " on " + service[1] + ": " + e);
        }
        return out;
    }

    private static JSONArray query(String kind, String filter, String providerIds, String region) throws Exception {
        return Recs.get("/discover/" + kind + "?language=" + Recs.lang() + "&watch_region=" + region
                + "&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc"
                + "&with_watch_providers=" + URLEncoder.encode(providerIds, "UTF-8") + filter).getJSONArray("results");
    }

    /** A title with the picture the row needs (a 16:9 photo or a poster), or null without one. */
    private static Entry entry(JSONObject o, String kind, String[] service, int style) {
        String path = o.optString(style == POSTER ? "poster_path" : "backdrop_path", "");
        if (path.isEmpty() || "null".equals(path)) return null;
        Entry e = new Entry();
        e.title = o.optString(kind.equals("movie") ? "title" : "name");
        e.sub = Recs.serviceName(service[1]);
        e.image = IMAGES + (style == POSTER ? "w342" : "w780") + path;
        String still = o.optString("backdrop_path", "");
        e.still = still.isEmpty() || "null".equals(still) ? e.image : IMAGES + "w780" + still;
        e.pkg = service[2];
        e.overview = o.optString("overview", "");
        e.tmdb = kind + ":" + o.optInt("id");
        if (!still.isEmpty() && !"null".equals(still)) e.wide = IMAGES + "w1280" + still;
        return e;
    }

    /** One from each list in turn, without repeating a title, up to ROW_MAX. */
    private static void addInTurns(Row row, List<List<Entry>> lists) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; row.items.size() < ROW_MAX; i++) {
            boolean any = false;
            for (List<Entry> l : lists) {
                if (i >= l.size()) continue;
                any = true;
                if (row.items.size() < ROW_MAX && seen.add(l.get(i).title)) row.items.add(l.get(i));
            }
            if (!any) return;
        }
    }

    // MARK: - the catalog (its public catalog: no account needed)

    private List<Row> fetchLibrary() throws Exception {
        List<Row> rows = new ArrayList<>();
        Row fresh = new Row();
        fresh.key = "library:new";
        fresh.style = COVER;
        int level = libraryLevel();
        JSONArray items = new JSONArray(http(Endpoints.searchBase() + "newlyVideosItems/level/" + level + "/offset/" + ROW_MAX + "/page/0/"));
        for (int i = 0; i < items.length() && fresh.items.size() < ROW_MAX; i++) {
            Entry e = library(items.optJSONObject(i));
            if (e != null) fresh.items.add(e);
        }
        rows.add(fresh);
        // Its home page groups: Featured Movies and Featured Series, in turns.
        Row featured = new Row();
        featured.key = "library:featured";
        featured.style = COVER;
        JSONArray groups = new JSONObject(http(Endpoints.searchBase() + "videoGroups/lang/en/level/" + level + "/")).optJSONArray("groups");
        List<List<Entry>> lists = new ArrayList<>();
        for (int g = 0; groups != null && g < groups.length(); g++) {
            JSONObject group = groups.getJSONObject(g);
            if (!group.optString("title").startsWith("Featured")) continue;
            List<Entry> list = new ArrayList<>();
            JSONArray content = group.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                Entry e = library(content.optJSONObject(i));
                if (e != null) list.add(e);
            }
            lists.add(list);
        }
        addInTurns(featured, lists);
        rows.add(featured);
        // Wide photos for the featured carousel (the catalog has posters only): TMDB by the IMDb number.
        int found = 0;
        for (int i = 0; i < featured.items.size() && found < 8 && Recs.hasKey(context); i++) {
            Entry e = featured.items.get(i);
            if (e.imdb == null) continue;
            try {
                JSONObject r = Recs.get("/find/" + e.imdb + "?external_source=imdb_id");
                for (String kind : new String[] {"movie_results", "tv_results"}) {
                    JSONArray hits = r.optJSONArray(kind);
                    String path = hits == null || hits.length() == 0 ? "" : hits.getJSONObject(0).optString("backdrop_path", "");
                    if (!path.isEmpty() && !"null".equals(path)) {
                        e.wide = IMAGES + "w1280" + path;
                        if (e.overview.isEmpty()) e.overview = hits.getJSONObject(0).optString("overview", "");
                        found++;
                        break;
                    }
                }
            } catch (Exception ex) {
                AppLog.w("catalog wide photo: " + ex);
            }
        }
        return rows;
    }

    private static Entry library(JSONObject o) {
        if (o == null) return null;
        String img = o.optString("imgMediumThumbObjUrl", "");
        if (img.isEmpty() || "null".equals(img)) img = o.optString("imgObjUrl", "");
        if (img.isEmpty() || "null".equals(img)) return null;
        Entry e = new Entry();
        String en = o.optString("en_title", "").trim(), ar = o.optString("ar_title", "").trim();
        e.title = en.isEmpty() ? ar : en;
        if (e.title.isEmpty()) return null;
        e.sub = WatchMe.NAME;
        e.image = e.still = Endpoints.image(img);
        e.query = ar.isEmpty() ? en : ar;
        e.id = o.optString("nb", "");
        String about = o.optString("en_content", "").trim();
        if (about.isEmpty() || "null".equals(about)) about = o.optString("ar_content", "").trim();
        e.overview = "null".equals(about) ? "" : about;
        java.util.regex.Matcher tt = IMDB_ID.matcher(o.optString("imdbUrlRef", ""));     // ".../title/tt32268992"
        if (tt.find()) e.imdb = tt.group();
        e.pkg = Endpoints.PKG;
        return e;
    }

    // MARK: - TOD (the featured lists of its website: no account needed)

    /** Two rows: the matches coming up (soonest first), and TOD's new movies and series. */
    private List<Row> fetchTod() throws Exception {
        Row row = new Row(), fresh = new Row();
        row.key = "tod:games";
        row.style = WIDE;
        fresh.key = "tod:new";
        fresh.style = WIDE;
        long now = TrustedTime.now();
        List<JSONObject> games = new ArrayList<>();
        JSONArray sports = todSlider("sports");
        for (int i = 0; i < sports.length(); i++) {
            JSONObject it = sports.getJSONObject(i);
            long start = todTime(it.optString("matchStartTime", it.optString("programStartTime", "")));
            if (!"GAME".equals(it.optString("contentType")) || start == 0 || start < now - 2 * 3600_000L) continue;
            it.put("_start", start);
            games.add(it);
        }
        Collections.sort(games, (a, b) -> Long.compare(a.optLong("_start"), b.optLong("_start")));
        for (JSONObject g : games) {
            Entry e = todEntry(g);
            if (e == null) continue;
            e.at = g.optLong("_start");
            JSONObject comp = g.optJSONObject("competition");
            String when = new java.text.SimpleDateFormat("EEE HH:mm", Ui.locale()).format(new java.util.Date(g.optLong("_start")));
            e.sub = (comp == null ? "" : comp.optString("title").trim() + " · ") + when;
            row.items.add(e);
        }
        for (String page : new String[] {"movies", "series"}) {
            try {
                JSONArray items = todSlider(page);
                for (int i = 0; i < items.length(); i++) {
                    Entry e = todEntry(items.getJSONObject(i));
                    if (e == null) continue;
                    e.sub = "TOD · " + (page.equals("movies") ? Ui.tr("Filme", "Movie", "Película") : Ui.tr("Série", "Series", "Serie"));
                    fresh.items.add(e);
                }
            } catch (Exception ex) {
                AppLog.w("TOD " + page + ": " + ex);
            }
        }
        List<Row> rows = new ArrayList<>();
        rows.add(row);
        rows.add(fresh);
        return rows;
    }

    /** The featured list ("slider") at the top of a TOD page, from the data its site sends along with the page. */
    private static JSONArray todSlider(String page) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOD + page).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", BROWSER);
        c.setRequestProperty("RSC", "1");
        String text;
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("TOD returned HTTP " + code);
            text = read(c.getInputStream());
        } finally {
            c.disconnect();
        }
        int at = text.indexOf("{\"status\":200,\"data\":{\"railType\":\"slider\"");
        if (at < 0) throw new IOException("TOD's page changed: no featured list");
        JSONObject slider = (JSONObject) new org.json.JSONTokener(text.substring(at)).nextValue();
        JSONArray items = slider.getJSONObject("data").optJSONArray("items");
        return items == null ? new JSONArray() : items;
    }

    private static Entry todEntry(JSONObject it) {
        JSONObject images = it.optJSONObject("image");
        JSONObject pic = images == null ? null : images.optJSONObject("tile") != null ? images.optJSONObject("tile") : images.optJSONObject("wallpaper");
        String slug = it.optString("urlSlug", "");
        if (pic == null || slug.isEmpty()) return null;
        Entry e = new Entry();
        e.title = it.optString("title").trim();
        e.image = e.still = "https://img.tod.tv/resources/images/link/" + pic.optString("id") + "/" + pic.optString("mediaSettingsId")
                + "/" + pic.optString("timeStamp") + "/" + pic.optInt("x1") + ":" + pic.optInt("y1") + ":" + pic.optInt("x2") + ":"
                + pic.optInt("y2") + "/640x360/" + pic.optString("fullName");
        e.pkg = TOD_PKG;
        e.link = TOD + slug;
        e.wide = e.image.replace("/640x360/", "/1280x720/");
        return e.title.isEmpty() ? null : e;
    }

    /** "2026-10-20T19:00:00+00:00" → epoch ms (0 if unreadable). */
    private static long todTime(String iso) {
        if (iso.length() < 19) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }

    /** The family filter set in WatchMatch's the catalog (0 everything, 2 family, 3 kids): read fresh, it's another process's. */
    private int libraryLevel() {
        //noinspection deprecation
        ProfilePrefs.open(context, "settings", Context.MODE_MULTI_PROCESS);
        return CinemaPrefs.level(context);
    }

    private static String http(String url) throws IOException {
        boolean catalog = url.startsWith(Endpoints.searchBase());     // the catalog server's own requests count for Provider
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            String body = read(c.getInputStream());
            if (catalog) Provider.catalogResult(true, null);
            return body;
        } catch (IOException e) {
            if (catalog) Provider.catalogResult(false, e.getMessage());
            throw e;
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Disk

    private String sourceStamp() {
        return VERSION + "|" + libraryLevel() + '|' + Recs.region() + '|' + Ui.language + '|' + Config.TMDB_KEY.trim().hashCode();
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private void save() {
        try {
            JSONArray rows = new JSONArray();
            List<Row> all = new ArrayList<>(tmdbRows);
            all.addAll(libraryRows);
            all.addAll(todRows);
            if (ytRecs != null) all.add(ytRecs);
            for (Row r : all) {
                JSONArray items = new JSONArray();
                for (Entry e : r.items) {
                    items.put(new JSONObject().put("title", e.title).put("sub", e.sub).put("image", e.image).put("still", e.still)
                            .put("pkg", e.pkg).put("video", e.video == null ? "" : e.video)
                            .put("query", e.query == null ? "" : e.query).put("link", e.link == null ? "" : e.link)
                            .put("id", e.id == null ? "" : e.id).put("tmdb", e.tmdb == null ? "" : e.tmdb).put("overview", e.overview).put("wide", e.wide == null ? "" : e.wide).put("at", e.at));
                }
                rows.put(new JSONObject().put("key", r.key).put("style", r.style).put("items", items));
            }
            JSONObject o = new JSONObject().put("rows", rows).put("tmdbAt", tmdbAt).put("libraryAt", libraryAt).put("todAt", todAt).put("stamp", stamp);
            Durable.write(file, o.toString());
        } catch (Exception e) {
            AppLog.w("rows not saved: " + e);
        }
    }

    private void load() {
        String saved = Durable.read(file);
        if (saved == null) return;
        try {
            JSONObject o = new JSONObject(saved);
            tmdbAt = o.optLong("tmdbAt");
            libraryAt = o.optLong("libraryAt");
            todAt = o.optLong("todAt");
            stamp = o.optString("stamp");
            JSONArray rows = o.optJSONArray("rows");
            List<Row> tmdb = new ArrayList<>(), cin = new ArrayList<>(), td = new ArrayList<>();
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                Row row = new Row();
                row.key = r.optString("key");
                row.style = r.optInt("style");
                JSONArray items = r.optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) {
                    JSONObject it = items.getJSONObject(j);
                    Entry e = new Entry();
                    e.title = it.optString("title");
                    e.sub = it.optString("sub");
                    e.image = it.optString("image");
                    e.still = it.optString("still", e.image);
                    e.pkg = it.optString("pkg");
                    e.query = it.optString("query").isEmpty() ? null : it.optString("query");
                    e.link = it.optString("link").isEmpty() ? null : it.optString("link");
                    e.id = it.optString("id").isEmpty() ? null : it.optString("id");
                    e.tmdb = it.optString("tmdb").isEmpty() ? null : it.optString("tmdb");
                    e.overview = it.optString("overview");
                    e.wide = it.optString("wide").isEmpty() ? null : it.optString("wide");
                    e.at = it.optLong("at");
                    e.video = it.optString("video").isEmpty() ? null : it.optString("video");
                    row.items.add(e);
                }
                if (row.key.startsWith("library:")) cin.add(row);
                else if (row.key.startsWith("tod:")) td.add(row);
                else if (row.key.equals("yt:recs")) ytRecs = row;
                else tmdb.add(row);
            }
            tmdbRows = tmdb;
            libraryRows = cin;
            todRows = td;
        } catch (Exception e) {
            AppLog.w("rows not read: " + e);
        }
    }

    private static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
