package io.watchmatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TheIntroDB (api.theintrodb.org, free, no key): where an episode's intro and credits are, by the series' TMDB id.
 * Pure Java, checked on a plain JVM by IntroDbCheck (a fake transport and cache stand in for the network and the disk).
 * 404 "no_submissions" means nothing is known: remembered for a day so a play doesn't ask again. A failure is
 * remembered for a few minutes only.
 */
final class IntroDb {
    private IntroDb() {}

    static final long MIN_INTRO_MS = 5000, MISS_MS = 24 * 3_600_000L, HIT_MS = 30 * 24 * 3_600_000L, FAIL_MS = 10 * 60_000L;

    /** One HTTP answer. */
    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    interface Http { Reply get(String url) throws IOException; }

    /** Strings by key (SharedPreferences in the app). */
    interface Cache {
        String get(String key);
        void put(String key, String value);
    }

    /** What the database knows: intros ({start, end} in ms) and where the credits start (-1: unknown). */
    static final class Found {
        final List<long[]> intros = new ArrayList<>();
        long credits = -1;

        boolean empty() {
            return intros.isEmpty() && credits < 0;
        }
    }

    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern START = Pattern.compile("\"start_ms\"\\s*:\\s*(\\d+)");
    private static final Pattern END = Pattern.compile("\"end_ms\"\\s*:\\s*(\\d+)");

    static String url(int tmdbId, int season, int episode) {
        return "https://api.theintrodb.org/v2/media?tmdb_id=" + tmdbId + "&season=" + season + "&episode=" + episode;
    }

    /** The text of the array under `key` ("" when absent or null). */
    private static String array(String body, String key) {
        int k = body.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int open = body.indexOf('[', k), colon = body.indexOf(':', k);
        if (open < 0 || colon < 0 || body.substring(colon + 1, open).trim().length() > 0) return "";
        int close = body.indexOf(']', open);
        return close < 0 ? "" : body.substring(open + 1, close);
    }

    /** Reads an answer's body: intro ranges of at least 5 s and the first credits start; null when it isn't one. */
    static Found parse(String body) {
        if (body == null || !body.trim().startsWith("{")) return null;
        Found f = new Found();
        Matcher m = OBJECT.matcher(array(body, "intro"));
        while (m.find()) {
            Matcher s = START.matcher(m.group()), e = END.matcher(m.group());
            if (!s.find() || !e.find()) continue;
            long a = Long.parseLong(s.group(1)), b = Long.parseLong(e.group(1));
            if (b - a >= MIN_INTRO_MS) f.intros.add(new long[] {a, b});
        }
        m = OBJECT.matcher(array(body, "credits"));
        while (m.find() && f.credits < 0) {
            Matcher s = START.matcher(m.group());
            if (s.find()) f.credits = Long.parseLong(s.group(1));
        }
        return f;
    }

    /**
     * The episode's entry: from the cache while it is fresh, else asked (4 s timeouts are the transport's). Null when
     * nothing is known. Throws IOException when the server can't be reached or answers oddly.
     */
    static Found lookup(int tmdbId, int season, int episode, Http http, Cache cache, long now) throws IOException {
        if (tmdbId <= 0 || season < 0 || episode <= 0) return null;
        String key = "db:" + tmdbId + ":" + season + ":" + episode;
        String saved = cache.get(key);
        if (saved != null) {
            String[] p = saved.split("\\|", 3);
            long at = p.length == 3 ? number(p[0]) : -1;
            long ttl = p.length < 3 ? -1 : "404".equals(p[1]) ? MISS_MS : "200".equals(p[1]) ? HIT_MS : FAIL_MS;
            if (at >= 0 && now >= at && now - at < ttl) {
                if (!"200".equals(p[1])) {
                    if ("0".equals(p[1])) throw new IOException("recently failed");
                    return null;
                }
                Found f = parse(p[2]);
                return f == null || f.empty() ? null : f;
            }
        }
        Reply r;
        try {
            r = http.get(url(tmdbId, season, episode));
        } catch (IOException e) {
            cache.put(key, now + "|0|");
            throw e;
        }
        if (r.code == 404) {
            cache.put(key, now + "|404|");
            return null;
        }
        Found f = r.code == 200 ? parse(r.body) : null;
        if (f == null) {
            cache.put(key, now + "|0|");
            throw new IOException("TheIntroDB HTTP " + r.code);
        }
        cache.put(key, now + "|200|" + r.body.replace('\n', ' '));
        return f.empty() ? null : f;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
