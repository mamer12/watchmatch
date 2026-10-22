package io.watchmatch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SearchActivity's plain search (a name, no filters): {catalog}, the streaming apps that carry it (TMDB), YouTube. Blocking. */
final class PlainSearch {
    private PlainSearch() {}

    private static String library() { return Endpoints.searchBase() + "AdvancedSearch?level=0&page=0"; }

    static boolean exact(List<Catalog.Entry> found, String q) {
        return !found.isEmpty() && SearchRank.score(found.get(0).title, found.get(0).query, SearchRank.normalize(q)) == SearchRank.EXACT;
    }

    /** The plain search's rows: {catalog}, the streaming apps (TMDB), YouTube. */
    static Map<String, List<Catalog.Entry>> rows(Context c, String q, List<Catalog.Entry> catalog) {
        final Map<String, List<Catalog.Entry>> rows = new LinkedHashMap<>();
        rows.put(WatchMe.NAME, catalog);
        try {
            rows.putAll(streaming(c, q));
        } catch (Exception e) {
            AppLog.w("search TMDB: " + e.getMessage());
        }
        try {
            rows.put("YouTube", YouTubeAccount.search(c, q));
        } catch (Exception e) {
            AppLog.w("search YouTube: " + e.getMessage());
        }
        return rows;
    }

    /** The catalog's catalog, movies and series together, best matches first (SearchRank), the API's order otherwise. */
    static List<Catalog.Entry> watchMe(String q) {
        List<Catalog.Entry> out = new ArrayList<>();
        for (String type : new String[] {"movies", "series"}) {
            try {
                String enc = URLEncoder.encode(q, "UTF-8");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(library() + "&type=" + type
                        + "&videoTitle=" + enc + "&staffTitle=" + enc).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                JSONArray items;
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    items = new JSONArray(b.toString("UTF-8"));
                } finally {
                    c.disconnect();
                }
                for (int i = 0; i < items.length() && i < 20; i++) {
                    JSONObject o = items.getJSONObject(i);
                    String img = o.optString("imgMediumThumbObjUrl", "");
                    if (img.isEmpty() || "null".equals(img)) continue;
                    Catalog.Entry e = new Catalog.Entry();
                    String en = o.optString("en_title", "").trim(), ar = o.optString("ar_title", "").trim();
                    e.title = en.isEmpty() ? ar : en;
                    e.sub = o.optString("year", "");
                    e.image = e.still = img;
                    e.pkg = Endpoints.PKG;
                    e.id = o.optString("nb");
                    e.query = ar.isEmpty() ? en : ar;
                    out.add(e);
                }
            } catch (Exception e) {
                AppLog.w("search Watchme+: " + e.getMessage());
            }
        }
        final String want = SearchRank.normalize(q);
        final Map<Catalog.Entry, Integer> rank = new java.util.IdentityHashMap<>();
        for (Catalog.Entry e : out) rank.put(e, SearchRank.score(e.title, e.query, want));
        java.util.Collections.sort(out, (a, b) -> Integer.compare(rank.get(a), rank.get(b)));    // stable: API order otherwise
        return out.size() > 24 ? new ArrayList<>(out.subList(0, 24)) : out;
    }

    /**
     * TMDB's matches, put under each streaming app on the TV that carries them in your country: posters in the language
     * of {catalog}'s art and the year under them, like the {catalog} row and Spark's rows.
     */
    private static Map<String, List<Catalog.Entry>> streaming(Context c, String q) throws Exception {
        Map<String, List<Catalog.Entry>> out = new LinkedHashMap<>();
        if (!Recs.hasKey(c)) return out;
        JSONArray hits = Recs.get("/search/multi?include_adult=false&language=" + Recs.lang() + "&query="
                + URLEncoder.encode(q, "UTF-8")).optJSONArray("results");
        for (int i = 0; hits != null && i < hits.length() && i < 10; i++) {
            JSONObject o = hits.getJSONObject(i);
            String kind = o.optString("media_type");
            if (!kind.equals("movie") && !kind.equals("tv")) continue;
            JSONObject d = Spark.details(kind, String.valueOf(o.optInt("id")));
            String poster = Spark.poster(d);
            if (poster == null) poster = Library.s(o, "poster_path");
            if (poster.isEmpty()) continue;
            String back = Library.s(o, "backdrop_path");
            String date = Library.s(o, kind.equals("movie") ? "release_date" : "first_air_date");
            JSONObject here = Spark.streamsHere(d);
            JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
            for (int j = 0; flat != null && j < flat.length(); j++) {
                String[] s = Recs.service(flat.getJSONObject(j).optString("provider_name"));
                if (s == null || HomeApps.launchIntent(c.getPackageManager(), s[2]) == null) continue;
                String name = Spark.onApp(Recs.serviceName(s[1]));
                Catalog.Entry e = new Catalog.Entry();
                e.title = o.optString(kind.equals("movie") ? "title" : "name");
                e.sub = date.length() >= 4 ? date.substring(0, 4) : "";
                e.image = "https://image.tmdb.org/t/p/w342" + poster;
                e.still = back.isEmpty() ? e.image : "https://image.tmdb.org/t/p/w780" + back;
                e.pkg = s[2];
                e.tmdb = kind + ":" + o.optInt("id");
                List<Catalog.Entry> list = out.get(name);
                if (list == null) out.put(name, list = new ArrayList<>());
                boolean dup = false;
                for (Catalog.Entry x : list) dup |= x.title.equals(e.title);
                if (!dup) list.add(e);
            }
        }
        return out;
    }
}
