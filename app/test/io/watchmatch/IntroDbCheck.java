package io.watchmatch;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Checks TheIntroDB's parsing and caching, with a fake transport, on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/IntroDb.java app/src/io/watchmatch/NextUpRules.java \
 *       app/test/io/watchmatch/IntroDbCheck.java && java -cp out/test io.watchmatch.IntroDbCheck
 */
public final class IntroDbCheck {
    private static int failures;
    private static int calls;
    private static IntroDb.Reply next;
    private static boolean boom;

    private static final IntroDb.Http HTTP = url -> {
        calls++;
        if (boom) throw new IOException("down");
        return next;
    };

    private static final String HIT = "{\"tmdb_id\":1396,\"type\":\"tv\",\"season\":1,\"episode\":1,"
            + "\"intro\":[{\"start_ms\":228664,\"end_ms\":246143},{\"start_ms\":1000,\"end_ms\":3000}],"
            + "\"credits\":[{\"start_ms\":3431000,\"end_ms\":null}]}";

    public static void main(String[] args) throws Exception {
        eq("url", "https://api.theintrodb.org/v2/media?tmdb_id=1396&season=1&episode=2", IntroDb.url(1396, 1, 2));
        eq("movie url", "https://api.theintrodb.org/v2/media?tmdb_id=603", IntroDb.movieUrl(603));
        next = new IntroDb.Reply(200, "{\"tmdb_id\":603,\"type\":\"movie\",\"credits\":[{\"start_ms\":7000000}]}");
        java.util.Map<String, String> mem = new java.util.HashMap<>();
        IntroDb.Cache mc = new IntroDb.Cache() {
            public String get(String k) { return mem.get(k); }
            public void put(String k, String v) { mem.put(k, v); }
        };
        eq("movie credits", 7000000L, IntroDb.lookupMovie(603, HTTP, mc, 1000).credits);
        int before = calls;
        eq("movie credits cached", 7000000L, IntroDb.lookupMovie(603, HTTP, mc, 2000).credits);
        eq("movie cached: no second call", before, calls);
        eq("movie bad id skips the network", null, IntroDb.lookupMovie(0, HTTP, mc, 1000));
        calls = 0;
        IntroDb.Found f = IntroDb.parse(HIT);
        eq("one intro kept (the 2 s one dropped)", 1, f.intros.size());
        eq("intro start", 228664L, f.intros.get(0)[0]);
        eq("intro end", 246143L, f.intros.get(0)[1]);
        eq("credits start", 3431000L, f.credits);
        IntroDb.Found none = IntroDb.parse("{\"intro\":null,\"credits\":[{\"start_ms\":null,\"end_ms\":null}]}");
        eq("null arrays: empty", true, none != null && none.empty());
        eq("credits only", 90000L, IntroDb.parse("{\"intro\":[],\"credits\":[{\"start_ms\":90000}]}").credits);
        eq("not json", null, IntroDb.parse("<html>"));
        eq("404 body isn't an entry", true, IntroDb.parse("{\"error\":\"no submissions for this media\",\"code\":\"no_submissions\"}").empty());

        // A hit is cached, a miss for a day, a failure for minutes.
        Map<String, String> store = new HashMap<>();
        IntroDb.Cache cache = new IntroDb.Cache() {
            @Override public String get(String k) { return store.get(k); }
            @Override public void put(String k, String v) { store.put(k, v); }
        };
        long t0 = 1_000_000_000L, h = 3_600_000L;
        next = new IntroDb.Reply(200, HIT);
        f = IntroDb.lookup(1396, 1, 1, HTTP, cache, t0);
        eq("lookup hit", 228664L, f.intros.get(0)[0]);
        IntroDb.lookup(1396, 1, 1, HTTP, cache, t0 + 5 * 24 * h);
        eq("hit cached (1 call)", 1, calls);
        eq("cached hit still parsed", 3431000L, IntroDb.lookup(1396, 1, 1, HTTP, cache, t0 + h).credits);
        IntroDb.lookup(1396, 1, 1, HTTP, cache, t0 + 31 * 24 * h);
        eq("hit asked again after 30 days", 2, calls);

        calls = 0;
        next = new IntroDb.Reply(404, "{\"error\":\"no submissions for this media\",\"code\":\"no_submissions\"}");
        eq("404 is null", null, IntroDb.lookup(5, 1, 1, HTTP, cache, t0));
        eq("miss cached 23 h", null, IntroDb.lookup(5, 1, 1, HTTP, cache, t0 + 23 * h));
        eq("miss: 1 call", 1, calls);
        IntroDb.lookup(5, 1, 1, HTTP, cache, t0 + 25 * h);
        eq("miss asked again after a day", 2, calls);
        eq("another episode asks", 3, count(5, 1, 2, cache, t0));

        calls = 0;
        boom = true;
        eq("failure throws", true, throwsIo(7, cache, t0));
        eq("failure remembered: still throws, no call", true, throwsIo(7, cache, t0 + 60_000));
        eq("failure: 1 call", 1, calls);
        boom = false;
        next = new IntroDb.Reply(200, HIT);
        eq("after 10 min asks again", 228664L, IntroDb.lookup(7, 1, 1, HTTP, cache, t0 + 11 * 60_000).intros.get(0)[0]);

        next = new IntroDb.Reply(500, "");
        eq("HTTP 500 throws", true, throwsIo(8, cache, t0));
        eq("bad ids skip the network", null, IntroDb.lookup(0, 1, 1, HTTP, cache, t0));

        // Credits from the database move the "Next episode" card, still never before 60% and never past the end margin.
        long d = 22 * 60_000L;
        eq("card from credits at 90%", 19 * 60_000L + 8_000, NextUpRules.cardAt(d, 19 * 60_000L));
        eq("card from credits too early: 60%", Math.round(0.6 * d), NextUpRules.cardAt(d, 5 * 60_000L));
        eq("no credits: estimate", NextUpRules.cardAt(d, -1), NextUpRules.cardAt(d, d + 5));
        eq("credits late: end margin", d - 15_000, NextUpRules.cardAt(d, d - 2_000));

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("IntroDbCheck passed");
    }

    private static int count(int id, int s, int e, IntroDb.Cache c, long now) throws IOException {
        IntroDb.lookup(id, s, e, HTTP, c, now);
        return calls;
    }

    private static boolean throwsIo(int id, IntroDb.Cache c, long now) {
        try {
            IntroDb.lookup(id, 1, 1, HTTP, c, now);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }
}
