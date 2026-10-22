package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Continue watching": the titles and videos opened from the home screen (and in the catalog app), newest first. The streaming apps keep where
 * you stopped to themselves (only the TV maker's home screen may read it), so this remembers what was picked here.
 */
final class Watched {
    private Watched() {}

    private static final int MAX = 12;
    private static final long KEEP_MS = 30 * 86_400_000L;

    static List<Catalog.Entry> list(Context c) {
        List<Catalog.Entry> out = new ArrayList<>();
        // Older than a month drops out, but only by a clock that's right, and never what a wrong one stamped.
        boolean age = !TrustedTime.suspect();
        long cutoff = TrustedTime.now() - KEEP_MS;
        try {
            JSONArray a = new JSONArray(prefs(c).getString("watched", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                long at = o.optLong("at");
                if (age && TrustedTime.plausible(at) && at < cutoff) continue;
                Catalog.Entry e = new Catalog.Entry();
                e.title = o.optString("title");
                e.sub = o.optString("sub");
                e.image = e.still = o.optString("still");
                e.pkg = o.optString("pkg");
                e.video = o.optString("video").isEmpty() ? null : o.optString("video");
                e.query = o.optString("query").isEmpty() ? null : o.optString("query");
                e.link = o.optString("link").isEmpty() ? null : o.optString("link");
                e.id = o.optString("id").isEmpty() ? null : o.optString("id");
                e.tmdb = o.optString("tmdb").isEmpty() ? null : o.optString("tmdb");
                out.add(e);
            }
        } catch (Exception e) {
            AppLog.w("continue watching not read: " + e);
        }
        return out;
    }

    /** Puts the title first (moving it up if it was already there). */
    static void add(Context c, Catalog.Entry e) {
        if (e == null || e.pkg == null || e.still == null || e.title.isEmpty()) return;
        try {
            JSONArray old = new JSONArray(prefs(c).getString("watched", "[]")), out = new JSONArray();
            out.put(new JSONObject().put("title", e.title).put("sub", e.sub).put("still", e.still).put("pkg", e.pkg)
                    .put("video", e.video == null ? "" : e.video).put("query", e.query == null ? "" : e.query)
                    .put("link", e.link == null ? "" : e.link).put("id", e.id == null ? "" : e.id).put("tmdb", e.tmdb == null ? "" : e.tmdb)
                    .put("at", TrustedTime.now()));
            for (int i = 0; i < old.length() && out.length() < MAX; i++) {
                JSONObject o = old.getJSONObject(i);
                if (o.optString("title").equals(e.title) && o.optString("pkg").equals(e.pkg)) continue;
                out.put(o);
            }
            Durable.save(prefs(c), prefs(c).edit().putString("watched", out.toString()));
        } catch (Exception ex) {
            AppLog.w("continue watching not saved: " + ex);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
    }
}
