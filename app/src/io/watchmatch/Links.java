package io.watchmatch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A title's own page in its streaming app (Apple TV: tv.apple.com, Netflix: netflix.com/title), which those apps open
 * straight on that title. TMDB has no such links; JustWatch's public catalog does (the same data behind TMDB's "where to
 * watch"). Looked up when a title is opened, then kept for the session. Blocking: call it off the main thread.
 */
final class Links {
    private Links() {}

    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String QUERY = "query Find($country: Country!, $language: Language!, $q: String!) {"
            + " popularTitles(country: $country, first: 6, filter: {searchQuery: $q}) { edges { node {"
            + " content(country: $country, language: $language) { externalIds { tmdbId } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL package { clearName } } } } } }";
    private static final Map<String, String> FOUND = new HashMap<>();

    /** Streaming apps whose title links open the app on that title: package → JustWatch's name for the service. */
    static String service(String pkg) {
        if ("com.apple.atve.androidtv.appletv".equals(pkg)) return "apple tv";
        if ("com.netflix.ninja".equals(pkg)) return "netflix";
        return null;
    }

    /** The title's page in that app ("movie:123" or "tv:456" for `tmdb`), or null when there's none to be found. */
    static String find(String title, String tmdb, String pkg) {
        String service = service(pkg);
        if (service == null || tmdb == null || title == null) return null;
        String key = pkg + '|' + tmdb;
        synchronized (FOUND) {
            if (FOUND.containsKey(key)) return FOUND.get(key);
        }
        String link = null;
        try {
            String id = tmdb.substring(tmdb.indexOf(':') + 1);
            JSONObject vars = new JSONObject().put("country", Recs.region()).put("language", "en").put("q", title);
            JSONObject body = post(new JSONObject().put("query", QUERY).put("variables", vars).toString());
            JSONArray edges = body.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
            for (int i = 0; i < edges.length() && link == null; i++) {
                JSONObject node = edges.getJSONObject(i).getJSONObject("node");
                JSONObject ids = node.getJSONObject("content").optJSONObject("externalIds");
                if (ids == null || !id.equals(ids.optString("tmdbId"))) continue;
                JSONArray offers = node.optJSONArray("offers");
                for (int j = 0; offers != null && j < offers.length(); j++) {
                    JSONObject o = offers.getJSONObject(j);
                    String name = o.getJSONObject("package").optString("clearName").toLowerCase(Locale.ROOT);
                    if (name.startsWith(service) && "FLATRATE".equals(o.optString("monetizationType"))) {
                        String url = o.optString("standardWebURL");
                        link = url.isEmpty() ? null : url;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("title link for " + title + ": " + e);
        }
        synchronized (FOUND) {
            FOUND.put(key, link);
        }
        return link;
    }

    private static JSONObject post(String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(6000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) b.write(buffer, 0, n);
                return new JSONObject(b.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            c.disconnect();
        }
    }
}
