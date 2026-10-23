package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where "Skip intro" can come from besides the catalog: TheIntroDB (IntroDb), found by the series' TMDB id (looked up
 * from its title once and remembered), and the person's own mark for a series (IntroMark). Off the main thread, 4 s
 * timeouts, failures silent (one line in the log).
 */
final class IntroSources {
    private IntroSources() {}

    private static final int TIMEOUT_MS = 4000;
    private static final long ID_MISS_MS = 24 * 3_600_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    interface Done { void found(IntroDb.Found found); }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("introdb", Context.MODE_PRIVATE);
    }

    // The person's own mark

    static long[] mark(Context c, String seriesKey) {
        return seriesKey.isEmpty() ? null : IntroMark.decode(sp(c).getString("mark:" + seriesKey, null));
    }

    static void setMark(Context c, String seriesKey, long[] range) {
        SharedPreferences.Editor e = sp(c).edit();
        if (range == null) e.remove("mark:" + seriesKey);
        else e.putString("mark:" + seriesKey, IntroMark.encode(range));
        Durable.save(sp(c), e);
    }

    // TheIntroDB

    /** Looks the episode up in the background; `done` runs on the worker thread with null when nothing is known. */
    static void lookup(final Context c, final String seriesKey, final String title, final String other, final String year,
                       final int season, final int episode, final Done done) {
        if (!Recs.hasKey(c) || seriesKey.isEmpty() || title.isEmpty() || season < 0 || episode <= 0) return;
        WORKER.execute(() -> {
            IntroDb.Found found = null;
            try {
                int id = tmdbId(c, seriesKey, title, other, year, false);
                if (id > 0) found = IntroDb.lookup(id, season, episode, IntroSources::http, new PrefsCache(sp(c)), System.currentTimeMillis());
            } catch (Exception e) {
                AppLog.w("theintrodb " + seriesKey + ": " + e);
            }
            done.found(found);
        });
    }

    /** A film's credits start from TheIntroDB, in the background; `done` runs on the worker thread (null: nothing known). */
    static void lookupMovie(final Context c, final String movieKey, final String title, final String other, final String year,
                            final Done done) {
        if (!Recs.hasKey(c) || movieKey.isEmpty() || title.isEmpty()) return;
        WORKER.execute(() -> {
            IntroDb.Found found = null;
            try {
                int id = tmdbId(c, "movie:" + movieKey, title, other, year, true);
                if (id > 0) found = IntroDb.lookupMovie(id, IntroSources::http, new PrefsCache(sp(c)), System.currentTimeMillis());
            } catch (Exception e) {
                AppLog.w("theintrodb movie " + movieKey + ": " + e);
            }
            done.found(found);
        });
    }

    private static final class PrefsCache implements IntroDb.Cache {
        private final SharedPreferences sp;

        PrefsCache(SharedPreferences sp) {
            this.sp = sp;
        }

        @Override public String get(String key) {
            return sp.getString(key, null);
        }

        @Override public void put(String key, String value) {
            sp.edit().putString(key, value).apply();
        }
    }

    /** The series' TMDB id from its title (and year), remembered; 0 when TMDB doesn't know it (asked again after a day). */
    private static int tmdbId(Context c, String key, String title, String other, String year, boolean movie) throws Exception {
        SharedPreferences sp = sp(c);
        int saved = sp.getInt("id:" + key, 0);
        if (saved > 0) return saved;
        long missAt = sp.getLong("idmiss:" + key, 0), now = System.currentTimeMillis();
        if (missAt > 0 && now >= missAt && now - missAt < ID_MISS_MS) return 0;
        String[] names = other.isEmpty() || other.equals(title) ? new String[] {title} : new String[] {title, other};
        int id = 0;
        for (String name : names) {
            id = search(name, year, movie);
            if (id == 0 && !year.isEmpty()) id = search(name, "", movie);
            if (id > 0) break;
        }
        if (id > 0) sp.edit().putInt("id:" + key, id).apply();
        else sp.edit().putLong("idmiss:" + key, now).apply();
        return id;
    }

    private static int search(String name, String year, boolean movie) throws Exception {
        String path = (movie ? "/search/movie" : "/search/tv") + "?include_adult=false&query=" + URLEncoder.encode(name, "UTF-8")
                + (year.matches("\\d{4}") ? (movie ? "&year=" : "&first_air_date_year=") + year : "");
        JSONArray hits = Recs.get(path, TIMEOUT_MS).optJSONArray("results");
        JSONObject first = hits == null ? null : hits.optJSONObject(0);
        return first == null ? 0 : first.optInt("id");
    }

    private static IntroDb.Reply http(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code == 200 ? c.getInputStream() : null;
            if (in == null) return new IntroDb.Reply(code, "");
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0 && out.size() < 262_144; ) out.write(buf, 0, n);
                return new IntroDb.Reply(code, new String(out.toByteArray(), StandardCharsets.UTF_8));
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }
}
