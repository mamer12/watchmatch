package io.watchmatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's picks (This morning / This afternoon / Tonight): the week's trending titles and recent releases
 * available on the streaming services whose TV apps are installed, from TMDB with the person's own key. The list, the
 * details and the downloaded images are kept on disk. Also the app's shared TMDB client (get, lang, region, service).
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;
        String title, backdrop;
        String meta = "", overview = "";
        String app, pkg;
        boolean detailed;
    }

    interface Listener { void onRecs(); }

    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_AR = {"لهذا الصباح", "لما بعد الظهر", "لهذه الليلة"};

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_TTL = 6 * 3_600_000L;
    private static final int CACHE_VERSION = 5, NEW_PER_SERVICE = 6, TRENDING_KEPT = 12, LIST_MAX = 12 + 4 * 6;
    private static final int TRENDING_SCAN = 20, IMAGE_FILES = 24;

    private static Recs instance;

    private final Context app;
    private final File imageDir, statusFile;
    private final android.content.SharedPreferences store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Backoff backoff = new Backoff();
    private final Map<String, Backdrop> ready = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();

    private volatile List<Pick> picks = new ArrayList<>();
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;
    private int period = -1;
    private long listAt, keyStamp;
    private String listLanguage = "";

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private Recs(Context c) {
        app = c;
        imageDir = new File(c.getCacheDir(), "recs");
        statusFile = new File(c.getFilesDir(), "recs-status.json");
        store = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        load();
    }

    // Key, region, language

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    static String region() {
        return Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
    }

    static String lang() {
        if (Ui.es) return "ES".equals(Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (Ui.ar) return "ar-SA";
        return Ui.en ? "en-US" : "pt-BR";
    }

    static String[] service(String providerName) {
        return RecsRules.service(providerName);
    }

    static String serviceName(String appKey) {
        return RecsRules.serviceName(appKey);
    }

    static String serviceName(Pick p) {
        return p == null ? "" : RecsRules.serviceName(p.app);
    }

    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    static String periodLabel() {
        int p = RecsRules.period(hourOfDay(TrustedTime.now()));
        return (Ui.ar ? PERIOD_LABEL_AR : Ui.es ? PERIOD_LABEL_ES : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)[p];
    }

    private static int hourOfDay(long at) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    String error() {
        return error;
    }

    /** What the last refresh left ({count, error, at}), read from the file so another process can ask. */
    static JSONObject status(Context c) {
        try {
            if (!hasKey(c)) return new JSONObject();
            File f = new File(c.getFilesDir(), "recs-status.json");
            if (!f.exists()) return new JSONObject();
            return new JSONObject(new String(readAll(new java.io.FileInputStream(f)), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    // TMDB

    static JSONObject get(String path) throws IOException, JSONException {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");
        String url = API + path;
        if (!bearer) url += (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // Disk cache

    private void load() {
        try {
            if (store.getInt("version", 0) != CACHE_VERSION) {
                store.edit().clear().putInt("version", CACHE_VERSION).apply();
                return;
            }
            JSONArray a = new JSONArray(store.getString("picks", "[]"));
            List<Pick> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Pick p = new Pick();
                p.id = o.getInt("id");
                p.kind = o.getString("kind");
                p.title = o.optString("title");
                p.backdrop = o.optString("backdrop");
                p.meta = o.optString("meta");
                p.overview = o.optString("overview");
                p.app = emptyToNull(o.optString("app"));
                p.pkg = emptyToNull(o.optString("pkg"));
                p.detailed = o.optBoolean("detailed");
                list.add(p);
            }
            picks = list;
            period = store.getInt("period", -1);
            listAt = store.getLong("listAt", 0);
            keyStamp = store.getLong("keyStamp", 0);
            listLanguage = store.getString("language", store.getBoolean("english", false) ? "en" : "pt");
        } catch (JSONException | RuntimeException e) {
            picks = new ArrayList<>();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title)
                        .put("backdrop", p.backdrop).put("meta", p.meta).put("overview", p.overview)
                        .put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            store.edit().putInt("version", CACHE_VERSION).putString("picks", a.toString()).putInt("period", period)
                    .putLong("listAt", listAt).putString("language", listLanguage).putLong("keyStamp", keyStamp).apply();
        } catch (JSONException ignored) {
            // Cannot happen with these values.
        }
    }

    private void writeStatus() {
        try {
            JSONObject o = new JSONObject().put("count", picks.size()).put("error", error).put("at", TrustedTime.now());
            try (FileOutputStream out = new FileOutputStream(statusFile)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException ignored) {
            // The settings screen shows no status then.
        }
    }

    // Listeners

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onRecs();
        });
    }

    // Refresh

    void retryNow() {
        backoff.reset();
        refreshIfStale();
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(app.getPackageManager(), pkg) != null;
    }

    private long stamp() {
        long h = Config.TMDB_KEY.trim().hashCode() * 31L + region().hashCode();
        for (String[] s : RecsRules.SERVICES) h = h * 31 + (installed(s[2]) ? s[2].hashCode() : 0);
        return h;
    }

    void refreshIfStale() {
        if (!hasKey(app) || fetching) return;
        long stamp = stamp();
        boolean newKey = stamp != keyStamp;
        if (!newKey && backoff.waiting()) return;
        long now = TrustedTime.now();
        final int current = RecsRules.period(hourOfDay(now));
        boolean stale = newKey || period != current || now - listAt > LIST_TTL || now < listAt || picks.isEmpty()
                || !Ui.language.equals(listLanguage);
        if (!stale && !missing(wanted())) return;
        keyStamp = stamp;
        final boolean fetchList = stale;
        fetching = true;
        worker.execute(() -> refresh(fetchList, current));
    }

    private boolean missing(List<Pick> list) {
        for (Pick p : list) if (!p.detailed || !imageFile(p).exists()) return true;
        return false;
    }

    private void refresh(boolean fetchList, int current) {
        String failure = null;
        try {
            if (fetchList) fetchList(current);
            failure = fill(wanted());
            save();
        } catch (IOException | JSONException | RuntimeException e) {
            failure = String.valueOf(e.getMessage());
        }
        finish(failure);
    }

    /** Details and images for `list`; the last message when every one of them failed, else null. */
    private String fill(List<Pick> list) {
        int tried = 0, failed = 0;
        String last = null;
        for (Pick p : list) {
            tried++;
            try {
                if (!p.detailed) fetchDetails(p);
                if (p.pkg != null) download(p);
            } catch (IOException | JSONException | RuntimeException e) {
                failed++;
                last = String.valueOf(e.getMessage());
            }
        }
        return tried > 0 && failed == tried ? last : null;
    }

    private void finish(String failure) {
        if (failure == null) {
            error = "";
            backoff.succeeded();
        } else {
            error = failure;
            backoff.failed();
            AppLog.w("recommendations: " + failure);
            main.postDelayed(() -> {
                if (!listeners.isEmpty()) refreshIfStale();
            }, backoff.delay());
        }
        fetching = false;
        writeStatus();
        notifyListeners();
    }

    /** The screensaver: details and images for every pick, in one pass. */
    void prefetchAll() {
        if (!hasKey(app) || fetching || backoff.waiting()) return;
        final List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !imageFile(p).exists())) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        worker.execute(() -> {
            String failure = fill(todo);
            save();
            finish(failure);
        });
    }

    // The list

    private static String day(long at) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(at));
    }

    private void fetchList(int current) throws IOException, JSONException {
        String lang = lang(), region = region();
        JSONArray providers = get("/watch/providers/movie?language=" + lang + "&watch_region=" + region)
                .optJSONArray("results");
        Map<String, StringBuilder> ids = new LinkedHashMap<>();
        Map<String, String> packages = new HashMap<>();
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s == null || !installed(s[2])) continue;
            StringBuilder b = ids.get(s[1]);
            if (b == null) ids.put(s[1], b = new StringBuilder());
            if (b.length() > 0) b.append('|');
            b.append(o.optInt("provider_id"));
            packages.put(s[1], s[2]);
        }
        if (ids.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");

        List<Pick> trendingMovies = trending("movie"), trendingShows = trending("tv");
        long now = TrustedTime.now();
        String today = day(now), movieSince = day(now - 365L * 86_400_000L), showSince = day(now - 90L * 86_400_000L);
        List<List<Pick>> movieLists = new ArrayList<>(), showLists = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> e : ids.entrySet()) {
            String common = "&language=" + lang + "&watch_region=" + region
                    + "&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc"
                    + "&with_watch_providers=" + URLEncoder.encode(e.getValue().toString(), "UTF-8");
            String app = e.getKey(), pkg = packages.get(app);
            movieLists.add(released("/discover/movie?vote_count.gte=10&primary_release_date.gte=" + movieSince
                    + "&primary_release_date.lte=" + today + common, "movie", app, pkg));
            showLists.add(released("/discover/tv?air_date.gte=" + showSince + "&air_date.lte=" + today + common,
                    "tv", app, pkg));
        }

        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        alternate(out, seen, trendingMovies, trendingShows, TRENDING_KEPT);
        alternate(out, seen, RecsRules.interleave(movieLists), RecsRules.interleave(showLists), LIST_MAX);
        if (out.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = out;
        period = current;
        listAt = TrustedTime.now();
        listLanguage = Ui.language;
    }

    /** Takes from a and b in turn into out (no repeats) until out holds `limit`. */
    private static void alternate(List<Pick> out, Set<String> seen, List<Pick> a, List<Pick> b, int limit) {
        int ia = 0, ib = 0;
        boolean fromA = true;
        while (out.size() < limit && (ia < a.size() || ib < b.size())) {
            List<Pick> src = fromA ? (ia < a.size() ? a : b) : (ib < b.size() ? b : a);
            int at = src == a ? ia++ : ib++;
            Pick p = src.get(at);
            if (seen.add(key(p))) out.add(p);
            fromA = !fromA;
        }
    }

    private List<Pick> trending(String kind) throws IOException, JSONException {
        JSONArray results = get("/trending/" + kind + "/week?language=" + lang()).optJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; results != null && i < results.length() && i < TRENDING_SCAN; i++) {
            Pick p = pick(results.getJSONObject(i), kind);
            if (p == null) continue;
            try {
                fetchDetails(p);
            } catch (IOException | JSONException e) {
                continue;
            }
            if (p.pkg != null) out.add(p);
        }
        return out;
    }

    private List<Pick> released(String path, String kind, String appKey, String pkg) {
        List<Pick> out = new ArrayList<>();
        try {
            JSONArray results = get(path).optJSONArray("results");
            for (int i = 0; results != null && i < results.length() && out.size() < NEW_PER_SERVICE; i++) {
                Pick p = pick(results.getJSONObject(i), kind);
                if (p == null) continue;
                p.app = appKey;
                p.pkg = pkg;
                out.add(p);
            }
        } catch (IOException | JSONException e) {
            AppLog.w("picks from " + appKey + ": " + e.getMessage());
        }
        return out;
    }

    /** A pick from a TMDB result, or null when it has no backdrop to show. */
    private static Pick pick(JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || backdrop.equals("null")) return null;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.overview = o.optString("overview");
        return p;
    }

    private void fetchDetails(Pick p) throws IOException, JSONException {
        JSONObject d = get("/" + p.kind + "/" + p.id + "?language=" + lang()
                + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en");
        String overview = d.optString("overview");
        if (!overview.isEmpty()) p.overview = overview;
        String best = bestBackdrop(d.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        if (p.app == null) resolveService(p, d);

        List<String> parts = new ArrayList<>();
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        JSONArray genres = d.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            String g = genres.getJSONObject(0).optString("name");
            if (!g.isEmpty()) parts.add(Ui.en ? g : RecsRules.translateGenre(g, Ui.language));
        }
        if (p.kind.equals("movie")) {
            int min = d.optInt("runtime");
            if (min > 0) parts.add(duration(min));
        } else {
            int seasons = d.optInt("number_of_seasons");
            if (seasons > 0) parts.add(seasons(seasons));
        }
        if (p.app != null) parts.add(Ui.isolate(serviceName(p.app)));
        StringBuilder meta = new StringBuilder();
        for (String s : parts) meta.append(meta.length() > 0 ? " · " : "").append(s);
        p.meta = meta.toString();
        p.detailed = true;
    }

    private static String duration(int min) {
        if (Ui.ar) return Arabic.duration(min);
        if (Ui.en) return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    private static String seasons(int n) {
        if (Ui.en) return n == 1 ? "1 season" : n + " seasons";
        if (Ui.ar) return n == 1 ? "موسم واحد" : n + " مواسم";
        return n == 1 ? "1 temporada" : n + " temporadas";
    }

    /** Textless first, then the widest, then the best voted. */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double top = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || o.optString("file_path", "").isEmpty()) continue;
            double score = (o.isNull("iso_639_1") ? 10_000_000 : 0) + Math.min(o.optInt("width"), 3840) * 100
                    + o.optDouble("vote_average", 0);
            if (score > top) {
                top = score;
                best = o.optString("file_path");
            }
        }
        return best;
    }

    private void resolveService(Pick p, JSONObject details) {
        JSONObject wp = details.optJSONObject("watch/providers");
        JSONObject results = wp == null ? null : wp.optJSONObject("results");
        JSONObject here = results == null ? null : results.optJSONObject(region());
        JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length(); i++) {
            JSONObject o = flat.optJSONObject(i);
            String[] s = o == null ? null : service(o.optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
                return;
            }
        }
    }

    // What to show

    List<Pick> now() {
        return now(null);
    }

    List<Pick> now(String kind) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(app)) return out;
        Map<String, Boolean> checked = new HashMap<>();
        List<Pick> candidates = new ArrayList<>();
        for (Pick p : picks) {
            if (kind != null && !kind.equals(p.kind)) continue;
            boolean ok = !p.detailed;
            if (!ok && p.pkg != null) {
                Boolean b = checked.get(p.pkg);
                if (b == null) checked.put(p.pkg, b = installed(p.pkg));
                ok = b;
            }
            if (ok) candidates.add(p);
        }
        String[] titles = new String[candidates.size()], apps = new String[candidates.size()];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = candidates.get(i).title;
            apps[i] = candidates.get(i).app;
        }
        for (int at : RecsRules.choose(titles, apps, TrustedTime.now() / 3_600_000L)) out.add(candidates.get(at));
        return out;
    }

    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Pick> l : Arrays.asList(now(null), now("movie"), now("tv"))) {
            for (Pick p : l) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(app)) return out;
        Set<String> franchises = new HashSet<>();
        for (Pick p : wanted()) {
            if (imageFile(p).exists() && franchises.add(RecsRules.franchise(p.title))) out.add(p);
        }
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && installed(p.pkg) && imageFile(p).exists()
                    && franchises.add(RecsRules.franchise(p.title))) {
                out.add(p);
            }
        }
        return out;
    }

    // Images

    private File imageFile(Pick p) {
        return new File(imageDir, p.backdrop.replace("/", ""));
    }

    File readyImage(Pick p) {
        if (p == null || p.backdrop == null) return null;
        File f = imageFile(p);
        return f.exists() ? f : null;
    }

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        imageDir.mkdirs();
        File part = new File(imageDir, f.getName() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) throw new IOException("image HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) {
            part.delete();
            throw new IOException("image not saved");
        }
        trimImages();
    }

    private void trimImages() {
        File[] files = imageDir.listFiles();
        if (files == null || files.length <= IMAGE_FILES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = IMAGE_FILES; i < files.length; i++) files[i].delete();
    }

    /** The built image for the pick, or null while it loads in the background (listeners hear when it is ready). */
    Backdrop backdrop(final Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String path = p.backdrop;
        final boolean forLight;
        synchronized (ready) {
            Backdrop b = ready.get(path);
            if (b != null) return b;
            if (!pending.add(path)) return null;
            forLight = light;
        }
        imageWorker.execute(() -> build(p, path, forLight));
        return null;
    }

    private void build(Pick p, String path, boolean forLight) {
        try {
            File f = imageFile(p);
            if (!f.exists()) download(p);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= Backdrop.W) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
            if (bmp == null) throw new IOException("image can't be read");
            Backdrop made = Backdrop.make(bmp, forLight);
            synchronized (ready) {
                if (forLight == light) {
                    ready.put(path, made);
                    int cap = light ? 10 : 4;
                    while (ready.size() > cap) ready.remove(ready.keySet().iterator().next());
                }
            }
            notifyListeners();
        } catch (IOException | RuntimeException e) {
            imageFile(p).delete();
            AppLog.w("image " + path + ": " + e.getMessage());
        } finally {
            synchronized (ready) {
                pending.remove(path);
            }
        }
    }

    void setLight(boolean v) {
        synchronized (ready) {
            if (light == v) return;
            light = v;
            ready.clear();
        }
    }
}
