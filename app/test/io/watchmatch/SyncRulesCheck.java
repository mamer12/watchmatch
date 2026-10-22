package io.watchmatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the sync logic on a plain JVM, with no network: pairing codes, row times, the outbox (coalescing, order, what
 * stays after a send), the retry steps, the progress throttle, merge rules (self echoes, newer local changes, the video
 * playing, tombstones, the union of My List on first link), the mapping to and from the web app's rows (including rows
 * written the way the web app writes them), the saved state, the REST client against a fake transport, and two
 * devices syncing through a fake server. From the repository's root (android.jar only so that Backoff compiles; nothing
 * of Android runs):
 *
 *   AJ=$(ls "$ANDROID_HOME"/platforms/android-*\/android.jar | sort -V | tail -n 1)
 *   mkdir -p out/test && javac -cp "$AJ" -d out/test app/src/io/watchmatch/{MiniJson,Backoff,PinRules,ProfileRules,SyncRules,SyncMap,SyncState,SyncClient,SyncEngine}.java \
 *       app/test/io/watchmatch/SyncRulesCheck.java && java -cp out/test io.watchmatch.SyncRulesCheck
 */
public final class SyncRulesCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) throws Exception {
        codes();
        times();
        outbox();
        retries();
        throttle();
        mapping();
        webRows();
        settings();
        state();
        client();
        devices();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static Map<String, Object> json(String text) {
        return MiniJson.map(MiniJson.parse(text));
    }

    private static SyncRules.Item item(String table, String key, long ts) {
        return new SyncRules.Item(table, key, MiniJson.obj("k", key, "ts", ts), ts);
    }

    private static SyncMap.T title(String id, String name) {
        SyncMap.T t = new SyncMap.T();
        t.id = id;
        t.title = name;
        t.other = "";
        t.overview = "A story about " + name;
        t.year = "2021";
        t.stars = "7.4";
        t.genres = "Drama · Action";
        t.poster = "https://img.example/" + id + ".jpg";
        t.imdb = "tt0000" + id;
        t.minutes = 45;
        return t;
    }

    private static SyncMap.P progress(String id, String name, long position, long duration, long at) {
        SyncMap.P p = new SyncMap.P();
        p.title = title(id, name);
        p.position = position;
        p.duration = duration;
        p.at = at;
        return p;
    }

    private static SyncMap.P episode(String id, String rootId, int season, int ep, long position, long duration, long at) {
        SyncMap.P p = progress(id, "Show", position, duration, at);
        p.title.rootId = rootId;
        p.title.series = true;
        p.title.season = season;
        p.title.episode = ep;
        p.rootTitle = "Show";
        p.rootPoster = "https://img.example/root.jpg";
        return p;
    }

    // ---- pairing codes ----------------------------------------------------------------------

    private static void codes() {
        eq("typed with a dash", "K7M2QX4P", SyncRules.normalizeCode("k7m2-qx4p "));
        eq("typed with a space", "K7M2QX4P", SyncRules.normalizeCode("k7m2 qx4p"));
        eq("extra characters are cut", "K7M2QX4P", SyncRules.normalizeCode("k7m2qx4pzz99"));
        eq("null is empty", "", SyncRules.normalizeCode(null));
        eq("a whole code", true, SyncRules.isCompleteCode("K7M2QX4P"));
        eq("short is not whole", false, SyncRules.isCompleteCode("K7M2"));
        eq("a 0 is not in the alphabet", false, SyncRules.isCompleteCode("K7M2QX40"));
        eq("an O is not either", false, SyncRules.isCompleteCode("K7M2QX4O"));
        eq("read aloud", "K7M2-QX4P", SyncRules.formatCode("K7M2QX4P"));
        eq("the join link", "https://watch-me-plus.vercel.app/?join=K7M2QX4P", SyncRules.joinUrl("K7M2QX4P"));
        eq("the alphabet has 31 characters", 31, SyncRules.CODE_ALPHABET.length());
        eq("wrong code error", "wrong", SyncRules.joinError(200, "wrong"));
        eq("too many tries error", "too_many_attempts", SyncRules.joinError(400, "too_many_attempts"));
        eq("no network error", "network", SyncRules.joinError(0, "timeout"));
        eq("any other error", "error", SyncRules.joinError(500, "boom"));
    }

    // ---- times ------------------------------------------------------------------------------

    private static void times() {
        long base = 1_792_664_190_000L;       // 2026-10-22T10:16:30Z
        eq("a server time with a short fraction", base + 500, SyncRules.parseTime("2026-10-22T10:16:30.5+00:00"));
        eq("a server time with microseconds", base + 123, SyncRules.parseTime("2026-10-22T10:16:30.123456+00:00"));
        eq("a Z time", base, SyncRules.parseTime("2026-10-22T10:16:30Z"));
        eq("an offset", base, SyncRules.parseTime("2026-10-22T12:16:30+02:00"));
        eq("junk is 0", 0L, SyncRules.parseTime("yesterday"));
        eq("null is 0", 0L, SyncRules.parseTime(null));
        eq("iso round trip", base + 42, SyncRules.parseTime(SyncRules.isoTime(base + 42)));
        Map<String, Object> row = MiniJson.obj("client_ts", "2026-10-22T09:00:00Z", "updated_at", "2026-10-22T21:00:00+00:00");
        eq("client_ts wins over updated_at", SyncRules.parseTime("2026-10-22T09:00:00Z"), SyncRules.rowTs(row));
        row.remove("client_ts");
        eq("else updated_at", SyncRules.parseTime("2026-10-22T21:00:00+00:00"), SyncRules.rowTs(row));
        eq("keys", "v:1", SyncRules.keyOf("watch_progress", MiniJson.obj("item_key", "v:1")));
        eq("keys of a setting", "level", SyncRules.keyOf("settings", MiniJson.obj("key", "level")));
        eq("keys of My List", "7", SyncRules.keyOf("my_list", MiniJson.obj("title_id", "7")));
        eq("no key", null, SyncRules.keyOf("ratings", MiniJson.obj("rating", 1L)));
    }

    // ---- the outbox -------------------------------------------------------------------------

    private static void outbox() {
        List<SyncRules.Item> q = SyncRules.coalesce(new ArrayList<SyncRules.Item>(), Arrays.asList(
                item("watch_progress", "v:1", 100), item("my_list", "9", 110), item("watch_progress", "v:2", 120)));
        eq("three queued", 3, q.size());
        q = SyncRules.coalesce(q, Arrays.asList(item("watch_progress", "v:1", 200)));
        eq("a newer change replaces the older", 3, q.size());
        eq("and keeps its place", "v:1", q.get(0).key);
        eq("with the newer time", 200L, q.get(0).ts);
        q = SyncRules.coalesce(q, Arrays.asList(item("watch_progress", "v:1", 150)));
        eq("an older one is ignored", 200L, q.get(0).ts);
        eq("the same key in another table is another row", 3, SyncRules.coalesce(q, Arrays.asList(item("ratings", "v:1", 1))).size() - 1);

        List<SyncRules.Item> batch = SyncRules.nextBatch(q);
        eq("a batch is one table, in queue order", Arrays.asList("v:1", "v:2"), keys(batch));
        List<SyncRules.Item> many = new ArrayList<>();
        for (int i = 0; i < 450; i++) many.add(item("ratings", "r" + i, i));
        eq("a batch has at most 200", 200, SyncRules.nextBatch(many).size());

        // v:1 goes out, then the person changes it again while the request is in flight.
        List<SyncRules.Item> afterSend = SyncRules.removeSent(SyncRules.coalesce(q, Arrays.asList(item("watch_progress", "v:1", 300))), batch);
        eq("a newer change stays queued after the older one was sent", Arrays.asList("v:1", "9"), keys(afterSend));
        eq("what was sent leaves the queue", Arrays.asList("9"), keys(SyncRules.removeSent(q, batch)));

        Map<String, Object> sent = SyncRules.toRow("space-1", item("my_list", "9", 0));
        eq("a row carries the space", "space-1", sent.get("space_id"));
        eq("and the time of the change", "1970-01-01T00:00:00Z", sent.get("client_ts"));

        // Merge rules
        long t10 = SyncRules.parseTime("2026-10-22T10:00:00Z"), t09 = SyncRules.parseTime("2026-10-22T09:00:00Z");
        Map<String, Object> remote = MiniJson.obj("updated_at", "2026-10-22T10:00:00Z", "updated_by", "other");
        eq("a remote row wins by default", true, SyncRules.shouldApply(remote, "me", null, false));
        eq("a row this device wrote itself is skipped", false, SyncRules.shouldApply(MiniJson.obj("updated_at", "2026-10-22T10:00:00Z", "updated_by", "me"), "me", null, false));
        eq("a newer queued local change wins", false, SyncRules.shouldApply(remote, "me", t10 + 1, false));
        eq("an older queued local change loses", true, SyncRules.shouldApply(remote, "me", t09, false));
        eq("a first-link upload (time 0) loses to the server", true, SyncRules.shouldApply(remote, "me", 0L, false));
        eq("the video playing here is left alone", false, SyncRules.shouldApply(remote, "me", null, true));
    }

    private static List<String> keys(List<SyncRules.Item> items) {
        List<String> out = new ArrayList<>();
        for (SyncRules.Item i : items) out.add(i.key);
        return out;
    }

    // ---- retries and the throttle -----------------------------------------------------------

    private static void retries() {
        eq("no failure, no wait", 0L, SyncRules.retryDelay(0));
        eq("first retry", 5_000L, SyncRules.retryDelay(1));
        eq("second", 15_000L, SyncRules.retryDelay(2));
        eq("third", 45_000L, SyncRules.retryDelay(3));
        eq("fourth", 120_000L, SyncRules.retryDelay(4));
        eq("then every ten minutes", 600_000L, SyncRules.retryDelay(5));
        eq("and never more", 600_000L, SyncRules.retryDelay(50));
        long prev = 0;
        boolean grows = true;
        for (int i = 1; i < 10; i++) {
            if (SyncRules.retryDelay(i) < prev) grows = false;
            prev = SyncRules.retryDelay(i);
        }
        eq("the wait never shrinks", true, grows);
    }

    /** Progress saved every 5 s for 45 s: at most four pushes, none closer than 15 s. */
    private static void throttle() {
        List<Long> pushes = new ArrayList<>();
        long last = -SyncRules.PROGRESS_PUSH_MS, due = -1;
        for (long now = 0; now <= 60_000; now += 500) {
            if (due >= 0 && now >= due) {
                pushes.add(now);
                last = now;
                due = -1;
            }
            if (now <= 45_000 && now % 5_000 == 0) {
                long d = now + SyncRules.pushDelay(now, last, true);
                if (due < 0 || d < due) due = d;
            }
        }
        eq("at most four pushes in 45 s of saves", true, pushes.size() <= 4 && pushes.size() >= 3);
        boolean spaced = true;
        for (int i = 1; i < pushes.size(); i++) if (pushes.get(i) - pushes.get(i - 1) < SyncRules.PROGRESS_PUSH_MS) spaced = false;
        eq("none closer than 15 s", true, spaced);
        eq("the first save goes after the debounce", SyncRules.PUSH_DEBOUNCE_MS, SyncRules.pushDelay(0, -SyncRules.PROGRESS_PUSH_MS, true));
        eq("My List goes after the debounce whatever progress did", SyncRules.PUSH_DEBOUNCE_MS, SyncRules.pushDelay(1000, 900, false));
        eq("progress right after a push waits", 15_000L - 1_000, SyncRules.pushDelay(1_000, 0, true));
    }

    // ---- mapping: the app's library to rows and back ----------------------------------------

    private static void mapping() {
        // A movie halfway.
        SyncMap.P movie = progress("10", "Heat", 3_000_000, 6_000_000, 1000);
        Map<String, Object> row = SyncMap.progressRow(movie, "TV Box");
        eq("movie item_key", "v:10", row.get("item_key"));
        eq("movie title_id is itself", "10", row.get("title_id"));
        eq("position in ms", 3_000_000L, row.get("position_ms"));
        eq("not finished", false, row.get("finished"));
        eq("no series pointer for a movie", null, MiniJson.map(row.get("meta")).get("ptr"));
        eq("the device name", "TV Box", MiniJson.map(row.get("meta")).get("dev"));
        Map<String, Object> snap = MiniJson.map(MiniJson.map(row.get("meta")).get("snap"));
        eq("snapshot kind", "movie", snap.get("kind"));
        eq("snapshot title", "Heat", snap.get("enTitle"));
        eq("snapshot year is a number", 2021L, snap.get("year"));
        eq("snapshot genres are objects", "Drama", MiniJson.map(MiniJson.list(snap.get("genres")).get(0)).get("en"));
        eq("snapshot duration is in seconds", 2700L, snap.get("duration"));
        eq("snapshot has every key the web reads", true, snap.keySet().containsAll(Arrays.asList("id", "kind", "enTitle", "arTitle",
                "enContent", "arContent", "poster", "posterThumb", "cover", "year", "imdbId", "genres", "stars", "filmRating",
                "seriesRating", "duration", "season", "episode", "trailer")));

        // Round trip through JSON text, as the server stores it.
        Map<String, Object> wire = json(MiniJson.write(row));
        wire.put("updated_at", "2026-10-22T10:16:02.5+00:00");
        SyncMap.RP back = SyncMap.progressFromRow(wire, false);
        eq("round trip id", "10", back.id);
        eq("round trip position", 3_000_000L, back.p.position);
        eq("round trip duration", 6_000_000L, back.p.duration);
        eq("round trip title", "Heat", back.p.title.title);
        eq("round trip year", "2021", back.p.title.year);
        eq("round trip stars", "7.4", back.p.title.stars);
        eq("round trip genres", "Drama · Action", back.p.title.genres);
        eq("round trip minutes", 45, back.p.title.minutes);
        eq("round trip poster", "https://img.example/10.jpg", back.p.title.poster);
        eq("round trip imdb", "tt000010", back.p.title.imdb);
        eq("it came from the device in dev", "TV Box", back.p.from);
        eq("its time is the server's", SyncRules.parseTime("2026-10-22T10:16:02.5+00:00"), back.p.at);

        // An episode: the series is the title_id, and the pointer says where to continue.
        SyncMap.P ep = episode("456", "99", 2, 4, 734_000, 2_700_000, 2000);
        Map<String, Object> er = SyncMap.progressRow(ep, "TV Box");
        eq("episode title_id is the series", "99", er.get("title_id"));
        Map<String, Object> meta = MiniJson.map(er.get("meta"));
        eq("sid", "99", meta.get("sid"));
        eq("season", 2L, meta.get("s"));
        eq("episode", 4L, meta.get("e"));
        eq("pointer to this episode", "456", MiniJson.map(meta.get("ptr")).get("id"));
        eq("not up next", false, MiniJson.map(meta.get("ptr")).get("up"));
        eq("series snapshot", "series", MiniJson.map(meta.get("ssnap")).get("kind"));
        eq("series snapshot name", "Show", MiniJson.map(meta.get("ssnap")).get("enTitle"));
        Map<String, Object> ew = json(MiniJson.write(er));
        ew.put("updated_at", "2026-10-22T10:16:02+00:00");
        SyncMap.RP eb = SyncMap.progressFromRow(ew, false);
        eq("episode comes back with its series", "99", eb.p.title.rootId);
        eq("season and episode", 2, eb.p.title.season);
        eq("series name", "Show", eb.p.rootTitle);
        eq("series poster", "https://img.example/root.jpg", eb.p.rootPoster);

        // Finished: the position goes back to 0 in the row (as the web does) and comes back as the end.
        SyncMap.P done = progress("11", "Se7en", 5_900_000, 6_000_000, 3000);
        Map<String, Object> dr = SyncMap.progressRow(done, "TV");
        eq("finished flag", true, dr.get("finished"));
        eq("finished position is 0", 0L, dr.get("position_ms"));
        Map<String, Object> dw = json(MiniJson.write(dr));
        dw.put("updated_at", "2026-10-22T10:00:00Z");
        SyncMap.P dback = SyncMap.progressFromRow(dw, false).p;
        eq("finished comes back at the end", dback.duration, dback.position);
        eq("and counts as finished", true, SyncMap.finished(dback));

        // Up next: no progress, the pointer says up.
        SyncMap.P next = episode("457", "99", 2, 5, 0, 0, 4000);
        next.upNext = true;
        Map<String, Object> nr = SyncMap.progressRow(next, "TV");
        eq("up next position", 0L, nr.get("position_ms"));
        eq("up next pointer", true, MiniJson.map(MiniJson.map(nr.get("meta")).get("ptr")).get("up"));
        Map<String, Object> nw = json(MiniJson.write(nr));
        nw.put("updated_at", "2026-10-22T10:00:00Z");
        eq("up next comes back as up next", true, SyncMap.progressFromRow(nw, false).p.upNext);

        // A taken-out movie counts as finished; a taken-out series sends nothing (SyncEngine) and stays a pointer.
        SyncMap.P hidden = progress("12", "Hidden", 1000, 6_000_000, 5000);
        hidden.hidden = true;
        eq("a hidden movie is sent as finished", true, SyncMap.progressRow(hidden, "TV").get("finished"));

        // My List and ratings
        SyncMap.T t = title("77", "Dune");
        Map<String, Object> lr = SyncMap.myListRow(t, "TV");
        eq("My List row", "77", lr.get("title_id"));
        eq("not deleted", false, lr.get("deleted"));
        Map<String, Object> lw = json(MiniJson.write(lr));
        SyncMap.T lb = SyncMap.myListFromRow(lw, false);
        eq("My List title comes back", "Dune", lb.title);
        eq("with its id from title_id", "77", lb.id);
        eq("tombstone", true, SyncMap.myListTombstone("77").get("deleted"));
        eq("a love is 10", 10, SyncMap.ratingScore(SyncMap.RATE_LOVE));
        eq("a like is 8", 8, SyncMap.ratingScore(SyncMap.RATE_LIKE));
        eq("a dislike is 1", 1, SyncMap.ratingScore(SyncMap.RATE_DISLIKE));
        eq("10 is love", SyncMap.RATE_LOVE, SyncMap.ratingFromScore(10));
        eq("9 is love", SyncMap.RATE_LOVE, SyncMap.ratingFromScore(9));
        eq("5 is like", SyncMap.RATE_LIKE, SyncMap.ratingFromScore(5));
        eq("8 is like", SyncMap.RATE_LIKE, SyncMap.ratingFromScore(8));
        eq("4 is dislike", SyncMap.RATE_DISLIKE, SyncMap.ratingFromScore(4));
        eq("rating row", 8L, SyncMap.ratingRow("77", SyncMap.RATE_LIKE).get("rating"));

        // Arabic titles are told by their letters, and the preference picks which one shows.
        SyncMap.T ar = title("5", "الفيل الأزرق");
        ar.other = "The Blue Elephant";
        Map<String, Object> as = SyncMap.snapshot(ar, 120);
        eq("Arabic title goes to arTitle", "الفيل الأزرق", as.get("arTitle"));
        eq("English title goes to enTitle", "The Blue Elephant", as.get("enTitle"));
        eq("English first", "The Blue Elephant", SyncMap.fromSnapshot(as, false).title);
        eq("Arabic first", "الفيل الأزرق", SyncMap.fromSnapshot(as, true).title);
        eq("the other one is kept", "The Blue Elephant", SyncMap.fromSnapshot(as, true).other);

        // The meta stays under the server's limit.
        SyncMap.T big = title("8", "Big");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("word ");
        big.overview = sb.toString();
        SyncMap.P bp = episode("8", "7", 1, 1, 10_000, 100_000, 1);
        bp.title.overview = sb.toString();
        for (int i = 0; i < 400; i++) bp.title.genres += " · genre" + i;
        Map<String, Object> bm = SyncMap.fitMeta(MiniJson.map(SyncMap.progressRow(bp, "TV").get("meta")));
        eq("meta under 8 KB", true, MiniJson.write(bm).length() <= 7000);
    }

    /** Rows written the way the web app's mapping.ts writes them (and read back the way it reads them). */
    private static void webRows() {
        String web = "{\"space_id\":\"s1\",\"item_key\":\"v:456\",\"title_id\":\"99\",\"position_ms\":734000,\"duration_ms\":2700000,"
                + "\"finished\":false,\"deleted\":false,\"meta\":{\"sid\":\"99\",\"s\":2,\"e\":4,"
                + "\"snap\":{\"id\":\"456\",\"kind\":\"series\",\"enTitle\":\"Show\",\"arTitle\":\"مسلسل\",\"enContent\":\"Plot\","
                + "\"arContent\":\"حبكة\",\"poster\":\"https://img/p.jpg\",\"posterThumb\":\"https://img/t.jpg\",\"cover\":null,"
                + "\"year\":2021,\"imdbId\":\"tt1234567\",\"genres\":[{\"en\":\"Drama\",\"ar\":\"دراما\"},{\"en\":\"Action\",\"ar\":\"أكشن\"}],"
                + "\"stars\":7.4,\"filmRating\":null,\"seriesRating\":8.1,\"duration\":2700,\"season\":2,\"episode\":4,\"trailer\":null},"
                + "\"ssnap\":{\"id\":\"99\",\"kind\":\"series\",\"enTitle\":\"Show\",\"arTitle\":\"مسلسل\",\"enContent\":\"\",\"arContent\":\"\","
                + "\"poster\":\"https://img/root.jpg\",\"posterThumb\":null,\"cover\":null,\"year\":null,\"imdbId\":null,\"genres\":[],"
                + "\"stars\":null,\"filmRating\":null,\"seriesRating\":null,\"duration\":null,\"season\":null,\"episode\":null,\"trailer\":null},"
                + "\"ptr\":{\"id\":\"456\",\"up\":false},\"dev\":\"Laptop\"},\"client_ts\":\"2026-10-22T10:15:00.123Z\","
                + "\"updated_at\":\"2026-10-22T10:16:02.5+00:00\",\"updated_by\":\"web-user\"}";
        SyncMap.RP rp = SyncMap.progressFromRow(json(web), false);
        eq("web: video id", "456", rp.id);
        eq("web: series id", "99", rp.p.title.rootId);
        eq("web: season", 2, rp.p.title.season);
        eq("web: episode", 4, rp.p.title.episode);
        eq("web: position", 734_000L, rp.p.position);
        eq("web: title", "Show", rp.p.title.title);
        eq("web: Arabic title kept as the other", "مسلسل", rp.p.title.other);
        eq("web: genres", "Drama · Action", rp.p.title.genres);
        eq("web: year", "2021", rp.p.title.year);
        eq("web: stars", "7.4", rp.p.title.stars);
        eq("web: minutes", 45, rp.p.title.minutes);
        eq("web: imdb", "tt1234567", rp.p.title.imdb);
        eq("web: series poster", "https://img/root.jpg", rp.p.rootPoster);
        eq("web: from", "Laptop", rp.p.from);
        eq("web: not up next", false, rp.p.upNext);
        eq("web: Arabic first", "مسلسل", SyncMap.progressFromRow(json(web), true).p.title.title);

        // A finished episode with the next one up next: the pointer and the next snapshot.
        String fin = "{\"item_key\":\"v:456\",\"title_id\":\"99\",\"position_ms\":0,\"duration_ms\":2700000,\"finished\":true,\"deleted\":false,"
                + "\"meta\":{\"sid\":\"99\",\"s\":2,\"e\":4,\"snap\":{\"id\":\"456\",\"kind\":\"series\",\"enTitle\":\"Show\",\"arTitle\":\"Show\",\"season\":2,\"episode\":4},"
                + "\"ssnap\":{\"id\":\"99\",\"kind\":\"series\",\"enTitle\":\"Show\",\"arTitle\":\"Show\",\"poster\":\"https://img/root.jpg\"},"
                + "\"nsnap\":{\"id\":\"457\",\"kind\":\"series\",\"enTitle\":\"Show\",\"arTitle\":\"Show\",\"season\":2,\"episode\":5},"
                + "\"ptr\":{\"id\":\"457\",\"up\":true},\"dev\":\"Phone\"},\"updated_at\":\"2026-10-22T11:00:00+00:00\"}";
        SyncMap.RP f = SyncMap.progressFromRow(json(fin), false);
        eq("web finished: counts as finished", true, SyncMap.finished(f.p));
        eq("web finished: next episode id", "457", f.next.title.id);
        eq("web finished: next is up next", true, f.next.upNext);
        eq("web finished: next belongs to the series", "99", f.next.title.rootId);
        eq("web finished: next episode number", 5, f.next.title.episode);
        eq("web finished: next comes just after", f.p.at + 1, f.next.at);

        // A movie, My List and rating rows as the web writes them.
        String movie = "{\"item_key\":\"v:10\",\"title_id\":\"10\",\"position_ms\":1000,\"duration_ms\":6000000,\"finished\":false,\"deleted\":false,"
                + "\"meta\":{\"sid\":null,\"s\":null,\"e\":null,\"snap\":{\"id\":\"10\",\"kind\":\"movie\",\"enTitle\":\"Heat\",\"arTitle\":\"Heat\"},\"ptr\":null,\"dev\":\"Phone\"},"
                + "\"updated_at\":\"2026-10-22T11:00:00Z\"}";
        SyncMap.RP m = SyncMap.progressFromRow(json(movie), false);
        eq("web movie: no series", "", m.p.title.rootId);
        eq("web movie: not a series", false, m.p.title.series);
        eq("web movie: no up next", null, m.next);
        String listRow = "{\"title_id\":\"77\",\"deleted\":false,\"meta\":{\"id\":\"77\",\"kind\":\"movie\",\"enTitle\":\"Dune\",\"arTitle\":\"Dune\",\"genres\":[],\"dev\":\"Phone\"},\"updated_at\":\"2026-10-22T11:00:00Z\"}";
        eq("web My List: title", "Dune", SyncMap.myListFromRow(json(listRow), false).title);
        String noMeta = "{\"title_id\":\"78\",\"deleted\":false,\"meta\":{},\"updated_at\":\"2026-10-22T11:00:00Z\"}";
        eq("web My List without a snapshot has no title", "", SyncMap.myListFromRow(json(noMeta), false).title);
        eq("a web tombstone", true, SyncMap.progressFromRow(json("{\"item_key\":\"v:3\",\"deleted\":true,\"updated_at\":\"2026-10-22T11:00:00Z\"}"), false).deleted);
    }

    private static void settings() {
        eq("level to the web", 2L, SyncMap.toRemote("level", 2));
        eq("English titles", "en", SyncMap.toRemote("titleLang", false));
        eq("Arabic titles", "ar", SyncMap.toRemote("titleLang", true));
        eq("family skips as is", true, SyncMap.toRemote("familySkips", true));
        eq("small subtitles", "small", SyncMap.toRemote("subtitleSize", 0));
        eq("medium subtitles", "medium", SyncMap.toRemote("subtitleSize", 1));
        eq("large subtitles", "large", SyncMap.toRemote("subtitleSize", 2));
        eq("huge subtitles are large for the web", "large", SyncMap.toRemote("subtitleSize", 3));
        eq("yellow", "yellow", SyncMap.toRemote("subtitleColor", true));
        eq("white", "white", SyncMap.toRemote("subtitleColor", false));
        eq("shadow", "shadow", SyncMap.toRemote("subtitleBackground", 2));
        eq("box", "box", SyncMap.toRemote("subtitleBackground", 1));
        eq("outline is none", "none", SyncMap.toRemote("subtitleBackground", 0));
        eq("level from the web", 3, SyncMap.fromRemote("level", 3L, 0));
        eq("an invalid level", null, SyncMap.fromRemote("level", 1L, 0));
        eq("the same value changes nothing", null, SyncMap.fromRemote("level", 2L, 2));
        eq("Arabic titles from the web", true, SyncMap.fromRemote("titleLang", "ar", false));
        eq("a wrong type is ignored", null, SyncMap.fromRemote("autoplayNext", "yes", true));
        eq("subtitle language", "off", SyncMap.fromRemote("subtitleLang", "off", "ar"));
        eq("an unknown language is ignored", null, SyncMap.fromRemote("subtitleLang", "fr", "ar"));
        eq("large keeps huge", null, SyncMap.fromRemote("subtitleSize", "large", 3));
        eq("large from medium", 2, SyncMap.fromRemote("subtitleSize", "large", 1));
        eq("cyan is not here", null, SyncMap.fromRemote("subtitleColor", "cyan", false));
        eq("box from the web", 1, SyncMap.fromRemote("subtitleBackground", "box", 2));
        eq("only shared settings sync", false, SyncMap.isSettingKey("quality"));
        eq("volume stays on the device", false, SyncMap.isSettingKey("volume"));
        eq("family filter syncs", true, SyncMap.isSettingKey("level"));
        eq("eight settings", 8, SyncMap.SETTING_KEYS.length);
    }

    private static void state() {
        SyncState s = new SyncState();
        s.space = "sp";
        s.device = "TV Box";
        s.cursors.put("my_list", "2026-10-22T10:16:02.5+00:00");
        s.outbox.add(new SyncRules.Item("ratings", "5", MiniJson.obj("title_id", "5", "rating", 8L, "deleted", false), 123456L));
        s.firstSyncDone = true;
        s.baselined = true;
        s.pushed.put("main~10", "1000h");
        s.listSeen.add("main~77");
        s.rateSeen.put("main~5", 1);
        s.setSeen.put("main~level", 2);
        s.setSeen.put("main~titleLang", true);
        s.setSeen.put("main~subtitleLang", "ar");
        s.profilesSeen = "[]";
        s.lastSyncAt = 99;
        SyncState back = SyncState.fromJson(s.toJson());
        eq("space", "sp", back.space);
        eq("device", "TV Box", back.device);
        eq("cursor", "2026-10-22T10:16:02.5+00:00", back.cursors.get("my_list"));
        eq("outbox size", 1, back.outbox.size());
        eq("outbox key", "5", back.outbox.get(0).key);
        eq("outbox time", 123456L, back.outbox.get(0).ts);
        eq("outbox row", 8L, back.outbox.get(0).row.get("rating"));
        eq("flags", true, back.firstSyncDone && back.baselined && back.linked());
        eq("pushed", "1000h", back.pushed.get("main~10"));
        eq("list seen", Arrays.asList("main~77"), back.listSeen);
        eq("ratings seen", 1, back.rateSeen.get("main~5"));
        eq("an int setting stays an int", Integer.valueOf(2), back.setSeen.get("main~level"));
        eq("a boolean setting", Boolean.TRUE, back.setSeen.get("main~titleLang"));
        eq("a string setting", "ar", back.setSeen.get("main~subtitleLang"));
        eq("last sync", 99L, back.lastSyncAt);
        eq("garbage is an empty state", false, SyncState.fromJson("{not json").linked());
        eq("null is an empty state", false, SyncState.fromJson(null).linked());
        eq("another version is an empty state", false, SyncState.fromJson("{\"v\":3,\"space\":\"x\"}").linked());
    }

    // ---- the REST client against a fake transport -------------------------------------------

    private static final class Wire implements SyncClient.Transport {
        final List<String> calls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<SyncClient.Response> answers = new ArrayList<>();
        boolean offline;

        @Override public SyncClient.Response send(String method, String url, Map<String, String> h, String body) throws IOException {
            if (offline) throw new IOException("no route");
            calls.add(method + " " + url);
            headers.add(h);
            bodies.add(body);
            return answers.isEmpty() ? new SyncClient.Response(200, "[]") : answers.remove(0);
        }
    }

    private static final class Mem implements SyncClient.Store {
        SyncClient.Session s;
        int saves;

        @Override public SyncClient.Session load() {
            return s;
        }

        @Override public void save(SyncClient.Session x) {
            s = x;
            saves++;
        }
    }

    private static String auth(String access, String refresh, long expiresIn) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"expires_in\":" + expiresIn + ",\"user\":{\"id\":\"u1\"}}";
    }

    private static void client() throws Exception {
        final long[] now = {1_000_000L};
        Wire w = new Wire();
        Mem store = new Mem();
        SyncClient c = new SyncClient("https://x.supabase.co/", "ANON", store, () -> now[0], w);

        w.answers.add(new SyncClient.Response(200, auth("a1", "r1", 3600)));
        w.answers.add(new SyncClient.Response(200, "\"space-1\""));
        Object id = c.rpc("create_space", MiniJson.obj("device_name", "TV"), true);
        eq("the first call signs up", "POST https://x.supabase.co/auth/v1/signup", w.calls.get(0));
        eq("sign-up sends an empty object", "{}", w.bodies.get(0));
        eq("the key goes with every call", "ANON", w.headers.get(0).get("apikey"));
        eq("then the rpc", "POST https://x.supabase.co/rest/v1/rpc/create_space", w.calls.get(1));
        eq("with the bearer token", "Bearer a1", w.headers.get(1).get("Authorization"));
        eq("the rpc result", "space-1", id);
        eq("the session was saved", "r1", store.s.refresh);
        eq("with the user", "u1", c.userId());
        eq("expiry from expires_in", now[0] + 3_600_000L, store.s.expiresAt);

        // Refreshed before it expires, and the new refresh token saved.
        now[0] += 3_600_000L - 30_000L;
        w.calls.clear();
        w.answers.add(new SyncClient.Response(200, auth("a2", "r2", 3600)));
        w.answers.add(new SyncClient.Response(200, "[]"));
        c.pull("my_list", "space-1", null, 1000);
        eq("a token about to expire is refreshed first", "POST https://x.supabase.co/auth/v1/token?grant_type=refresh_token", w.calls.get(0));
        eq("with the refresh token", "{\"refresh_token\":\"r1\"}", w.bodies.get(w.bodies.size() - 2));
        eq("the new refresh token is kept", "r2", store.s.refresh);
        eq("the pull is first of all unfiltered", "GET https://x.supabase.co/rest/v1/my_list?space_id=eq.space-1&order=updated_at.asc&limit=1000", w.calls.get(1));

        // A cursor is sent with gte and an encoded +.
        w.calls.clear();
        c.pull("watch_progress", "space-1", "2026-10-22T10:16:02.5+00:00", 1000);
        eq("a delta pull uses gte and encodes the +", "GET https://x.supabase.co/rest/v1/watch_progress?space_id=eq.space-1&updated_at=gte.2026-10-22T10%3A16%3A02.5%2B00%3A00&order=updated_at.asc&limit=1000",
                w.calls.get(0));

        // Upserts merge duplicates and conflict on the key.
        w.calls.clear();
        w.headers.clear();
        c.upsert("watch_progress", Arrays.asList(MiniJson.obj("space_id", "space-1", "item_key", "v:1")));
        eq("upsert conflicts on the key", "POST https://x.supabase.co/rest/v1/watch_progress?on_conflict=space_id%2Citem_key", w.calls.get(0));
        eq("upsert merges duplicates", "resolution=merge-duplicates,return=minimal,missing=default", w.headers.get(0).get("Prefer"));
        w.calls.clear();
        c.upsert("settings", Arrays.asList(MiniJson.obj("key", "level")));
        eq("settings conflict on key", "POST https://x.supabase.co/rest/v1/settings?on_conflict=space_id%2Ckey", w.calls.get(0));
        w.calls.clear();
        c.upsert("ratings", new ArrayList<Map<String, Object>>());
        eq("nothing to send, nothing sent", 0, w.calls.size());

        // A 401 refreshes once and retries.
        w.calls.clear();
        w.answers.add(new SyncClient.Response(401, "{\"message\":\"JWT expired\"}"));
        w.answers.add(new SyncClient.Response(200, auth("a3", "r3", 3600)));
        w.answers.add(new SyncClient.Response(200, "[{\"title_id\":\"1\"}]"));
        List<Map<String, Object>> rows = c.pull("ratings", "space-1", null, 10);
        eq("401: refresh then retry", 3, w.calls.size());
        eq("the retry has the new token", "Bearer a3", w.headers.get(w.headers.size() - 1).get("Authorization"));
        eq("the rows come back", 1, rows.size());

        // A lost session is cleared, and syncing never makes a new account.
        w.calls.clear();
        now[0] += 7_200_000L;
        w.answers.add(new SyncClient.Response(400, "{\"error_description\":\"Invalid Refresh Token\"}"));
        try {
            c.pull("ratings", "space-1", null, 10);
            eq("a lost session throws", true, false);
        } catch (SyncClient.HttpException e) {
            eq("a lost session is auth_lost", "auth_lost", e.getMessage());
        }
        eq("and the session is forgotten", null, store.s);
        w.calls.clear();
        try {
            c.pull("ratings", "space-1", null, 10);
            eq("no session throws", true, false);
        } catch (SyncClient.HttpException e) {
            eq("without a session, syncing does not sign up", "no_session", e.getMessage());
        }
        eq("and sends nothing", 0, w.calls.size());

        // Errors and the network.
        w.answers.add(new SyncClient.Response(200, auth("a4", "r4", 3600)));
        w.answers.add(new SyncClient.Response(400, "{\"message\":\"too_many_attempts\"}"));
        try {
            c.rpc("redeem_pairing_code", MiniJson.obj("code", "K7M2QX4P"), true);
            eq("too many attempts throws", true, false);
        } catch (SyncClient.HttpException e) {
            eq("the message is passed on", true, e.getMessage().contains("too_many_attempts"));
            eq("and maps to its own error", "too_many_attempts", SyncRules.joinError(e.status, e.getMessage()));
        }
        w.answers.add(new SyncClient.Response(200, "null"));
        eq("a wrong code is null", null, c.rpc("redeem_pairing_code", MiniJson.obj("code", "ZZZZZZZZ"), true));
        w.offline = true;
        try {
            c.pull("ratings", "space-1", null, 10);
            eq("offline throws", true, false);
        } catch (SyncClient.HttpException e) {
            eq("offline is status 0", 0, e.status);
        }
    }

    // ---- two devices through a fake server --------------------------------------------------

    /** A stand-in for the backend: rows per table and key, server times that only go up, client_ts respected. */
    private static final class Server {
        final Map<String, Map<String, Map<String, Object>>> tables = new LinkedHashMap<>();
        int clock;

        Server() {
            for (String t : SyncRules.TABLES) tables.put(t, new LinkedHashMap<String, Map<String, Object>>());
        }

        String stamp() {
            clock++;
            return String.format("2026-10-22T10:%02d:%02d.000000+00:00", clock / 60, clock % 60);
        }
    }

    private static class FakeApi implements SyncEngine.Api {
        final Server server;
        final String uid;
        int upserts, pulls;
        SyncClient.HttpException fail;
        final List<String> order = new ArrayList<>();

        FakeApi(Server s, String uid) {
            this.server = s;
            this.uid = uid;
        }

        @Override public String userId() {
            return uid;
        }

        @Override public void upsert(String table, List<Map<String, Object>> rows) throws SyncClient.HttpException {
            if (fail != null) throw fail;
            upserts++;
            order.add(table + ":" + rows.size());
            for (Map<String, Object> r : rows) {
                String key = SyncRules.keyOf(table, r);
                Map<String, Object> old = server.tables.get(table).get(key);
                if (old != null && old.get("client_ts") != null && r.get("client_ts") != null
                        && SyncRules.parseTime(old.get("client_ts")) > SyncRules.parseTime(r.get("client_ts"))) continue;
                Map<String, Object> row = new LinkedHashMap<>(r);
                row.put("updated_at", server.stamp());
                row.put("updated_by", uid);
                server.tables.get(table).put(key, row);
            }
        }

        @Override public List<Map<String, Object>> pull(String table, String space, String cursor, int limit) throws SyncClient.HttpException {
            if (fail != null) throw fail;
            pulls++;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : server.tables.get(table).values()) {
                if (cursor == null || MiniJson.str(r.get("updated_at")).compareTo(cursor) >= 0) out.add(new LinkedHashMap<>(r));
            }
            Collections.sort(out, (a, b) -> MiniJson.str(a.get("updated_at")).compareTo(MiniJson.str(b.get("updated_at"))));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }
    }

    /** A device's library, as Local and Sink. */
    private static final class Lib implements SyncEngine.Local, SyncEngine.Sink {
        final Map<String, SyncMap.P> progress = new LinkedHashMap<>();
        final List<SyncMap.T> list = new ArrayList<>();
        final Map<String, Integer> ratings = new LinkedHashMap<>();
        final Map<String, Object> settings = new LinkedHashMap<>();
        String playing;
        boolean arabic;

        Lib() {
            for (String k : SyncMap.SETTING_KEYS) settings.put(k, SyncMap.settingDefault(k));
        }

        @Override public Map<String, SyncEngine.Snapshot> snapshots() {
            SyncEngine.Snapshot s = new SyncEngine.Snapshot();
            s.progress.addAll(progress.values());
            s.myList.addAll(list);
            s.ratings.putAll(ratings);
            s.settings.putAll(settings);
            Map<String, SyncEngine.Snapshot> out = new LinkedHashMap<>();
            out.put(ProfileRules.MAIN, s);
            return out;
        }

        @Override public List<ProfileRules.Profile> profiles() {
            return new ArrayList<>(Arrays.asList(ProfileRules.main(1)));
        }

        @Override public boolean deleted(String pid) {
            return false;
        }

        @Override public void wiped(java.util.Set<String> pids) { }

        @Override public boolean profiles(List<ProfileRules.Profile> remote) {
            return false;
        }

        @Override public SyncEngine.Sink sink() {
            return this;
        }

        @Override public String playing() {
            return playing == null ? null : "main~" + playing;
        }

        @Override public boolean arabicFirst() {
            return arabic;
        }

        @Override public boolean progress(String pid, SyncMap.RP rp) {
            if (rp.deleted) return progress.remove(rp.id) != null;
            SyncMap.P p = rp.p;
            if (p == null) return false;
            SyncMap.P old = progress.get(rp.id);
            if (p.upNext && old != null) return false;
            if (old != null && old.position == p.position && old.at == p.at && !old.hidden) return false;
            progress.put(rp.id, p);
            return true;
        }

        @Override public boolean myList(String pid, String id, SyncMap.T t) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(id)) {
                    if (t != null) return false;
                    list.remove(i);
                    return true;
                }
            }
            if (t == null) return false;
            list.add(0, t);
            return true;
        }

        @Override public boolean rating(String pid, String id, int tv) {
            Integer old = ratings.get(id);
            if (tv == 0) return ratings.remove(id) != null;
            if (old != null && old == tv) return false;
            ratings.put(id, tv);
            return true;
        }

        @Override public Object setting(String pid, String key, Object remote) {
            Object now = SyncMap.fromRemote(key, remote, settings.get(key));
            if (now != null) settings.put(key, now);
            return now;
        }

        List<String> listIds() {
            List<String> out = new ArrayList<>();
            for (SyncMap.T t : list) out.add(t.id);
            return out;
        }
    }

    private static final class Device {
        final Lib lib = new Lib();
        final FakeApi api;
        final SyncState state = new SyncState();
        final SyncEngine engine;
        String saved;

        Device(Server server, String uid, String name) {
            api = new FakeApi(server, uid);
            state.space = "space-1";
            state.device = name;
            engine = new SyncEngine(state, api, lib, s -> saved = s.toJson());
        }

        void sync(long now) throws SyncClient.HttpException {
            engine.reconcile(now, state.device);
            engine.pullAll();
            engine.pushAll();
        }
    }

    private static void devices() throws Exception {
        Server server = new Server();
        Device a = new Device(server, "uidA", "Phone");
        Device b = new Device(server, "uidB", "TV Box");
        long t = SyncRules.parseTime("2026-10-22T10:00:00Z");

        // The phone watches, lists and rates; the TV joins with its own library (union on first link).
        a.lib.progress.put("456", episode("456", "99", 2, 4, 734_000, 2_700_000, t));
        a.lib.list.add(title("77", "Dune"));
        a.lib.ratings.put("77", SyncMap.RATE_LOVE);
        a.lib.settings.put("titleLang", true);
        a.sync(t + 1000);
        eq("first sync pushes everything", true, a.state.outbox.isEmpty());
        eq("progress, list, rating and the setting went", 4, a.api.upserts);
        eq("an upload goes a table at a time, in queue order", Arrays.asList("watch_progress:1", "my_list:1", "ratings:1", "settings:1"), a.api.order);
        eq("a default setting is not sent", true, server.tables.get("settings").size() == 1);

        b.lib.list.add(title("88", "Only on the TV"));
        b.lib.ratings.put("88", SyncMap.RATE_LIKE);
        b.sync(t + 2000);
        eq("the TV got the episode", true, b.lib.progress.containsKey("456"));
        eq("at the phone's position", 734_000L, b.lib.progress.get("456").position);
        eq("marked as from the phone", "Phone", b.lib.progress.get("456").from);
        eq("the series pointer data came along", "99", b.lib.progress.get("456").title.rootId);
        eq("My List is the union", new java.util.HashSet<>(Arrays.asList("77", "88")), new java.util.HashSet<>(b.lib.listIds()));
        eq("the phone's list item came first", "77", b.lib.list.get(0).id);
        eq("the rating arrived", SyncMap.RATE_LOVE, b.lib.ratings.get("77"));
        eq("the setting arrived", true, b.lib.settings.get("titleLang"));
        eq("the TV's own rating stayed", SyncMap.RATE_LIKE, b.lib.ratings.get("88"));
        eq("the first sync brought items in", true, b.engine.firstApplied > 0);
        eq("and says where from", "Phone", b.engine.firstFrom);
        eq("what was applied is not sent back", 0, countFor(server, "watch_progress", "uidB"));
        b.engine.reconcile(t + 3000, "TV Box");
        eq("applied changes are not seen as local ones", 0, b.state.outbox.size());

        // The phone sees the TV-only items; then the TV keeps watching.
        a.sync(t + 4000);
        eq("the phone got the TV's list item", true, a.lib.listIds().contains("88"));
        b.lib.progress.put("456", episode("456", "99", 2, 4, 1_500_000, 2_700_000, t + 10_000));
        b.engine.reconcile(t + 10_000, "TV Box");
        eq("a new position is queued", 1, b.state.outbox.size());
        b.engine.pushAll();
        a.sync(t + 11_000);
        eq("the phone resumes at the TV's position", 1_500_000L, a.lib.progress.get("456").position);
        eq("from the TV", "TV Box", a.lib.progress.get("456").from);

        // The video playing here is not moved.
        a.lib.playing = "456";
        b.lib.progress.put("456", episode("456", "99", 2, 4, 2_000_000, 2_700_000, t + 20_000));
        b.sync(t + 20_000);
        a.sync(t + 21_000);
        eq("a playing video's position stays", 1_500_000L, a.lib.progress.get("456").position);
        a.lib.playing = null;

        // Tombstones.
        b.lib.list.removeIf(x -> x.id.equals("77"));
        b.sync(t + 30_000);
        a.sync(t + 31_000);
        eq("a removal reaches the other device", false, a.lib.listIds().contains("77"));
        eq("and the rest stays", true, a.lib.listIds().contains("88"));
        b.lib.ratings.remove("88");
        b.sync(t + 32_000);
        a.sync(t + 33_000);
        eq("an unrated title is unrated elsewhere", false, a.lib.ratings.containsKey("88"));

        // A newer local change still queued beats the server's older row.
        a.api.fail = new SyncClient.HttpException(500, "down");
        a.lib.ratings.put("77", SyncMap.RATE_DISLIKE);
        try {
            a.sync(t + 40_000);
            eq("a server error throws", true, false);
        } catch (SyncClient.HttpException e) {
            eq("the error is the server's", 500, e.status);
        }
        eq("the change stays queued", 1, a.state.outbox.size());
        eq("and was saved to disk", true, a.saved.contains("main~77"));
        b.lib.ratings.put("77", SyncMap.RATE_LIKE);
        b.sync(t + 35_000);      // older than the phone's change
        a.api.fail = null;
        a.sync(t + 41_000);
        eq("the local change was kept", SyncMap.RATE_DISLIKE, a.lib.ratings.get("77"));
        b.sync(t + 42_000);
        eq("and sent: the TV now has it", SyncMap.RATE_DISLIKE, b.lib.ratings.get("77"));

        // An old offline replay loses on the server (client_ts).
        a.lib.ratings.put("90", SyncMap.RATE_LIKE);
        a.engine.reconcile(t + 50_000, "Phone");
        b.lib.ratings.put("90", SyncMap.RATE_LOVE);
        b.engine.reconcile(t + 60_000, "TV Box");
        b.engine.pushAll();
        a.engine.pushAll();      // the phone's older change arrives later
        eq("the server keeps the newer rating", 10L, server.tables.get("ratings").get("main~90").get("rating"));

        // A poisoned row is dropped and does not block the rest.
        Device c = new Device(server, "uidC", "Laptop");
        c.state.outbox.add(new SyncRules.Item("ratings", "bad", MiniJson.obj("title_id", "bad", "rating", 8L, "deleted", false), t));
        c.state.outbox.add(new SyncRules.Item("ratings", "ok", MiniJson.obj("title_id", "ok", "rating", 8L, "deleted", false), t + 1));
        c.state.baselined = true;
        FakeApi picky = new FakeApi(server, "uidC") {
            @Override public void upsert(String table, List<Map<String, Object>> rows) throws SyncClient.HttpException {
                for (Map<String, Object> r : rows) if ("bad".equals(r.get("title_id"))) throw new SyncClient.HttpException(422, "bad row");
                super.upsert(table, rows);
            }
        };
        SyncEngine ce = new SyncEngine(c.state, picky, c.lib, s -> { });
        ce.pushAll();
        eq("the bad row is dropped", 1, ce.dropped);
        eq("the good one went", true, server.tables.get("ratings").containsKey("ok"));
        eq("nothing is left queued", 0, c.state.outbox.size());

        // Going on offline: the queue stays and survives a restart (JSON).
        Device d = new Device(server, "uidD", "Tablet");
        d.api.fail = new SyncClient.HttpException(0, "offline");
        d.lib.list.add(title("55", "Offline add"));
        try {
            d.sync(t + 70_000);
        } catch (SyncClient.HttpException e) {
            eq("offline is status 0", 0, e.status);
        }
        SyncState restored = SyncState.fromJson(d.saved);
        eq("the queued add survives a restart", "main~55", restored.outbox.get(0).key);
        Device d2 = new Device(server, "uidD", "Tablet");
        d2.state.outbox.addAll(restored.outbox);
        d2.state.baselined = restored.baselined;
        d2.state.listSeen.addAll(restored.listSeen);
        d2.lib.list.add(title("55", "Offline add"));
        d2.sync(t + 80_000);
        eq("and goes out once online", true, server.tables.get("my_list").containsKey("main~55"));
        eq("its time was kept (first-link adds are time 0)", "1970-01-01T00:00:00Z", server.tables.get("my_list").get("main~55").get("client_ts"));

        // Re-pulling the boundary row (gte) changes nothing.
        int before = a.lib.progress.size();
        a.engine.takeApplied();
        a.engine.pullAll();
        a.engine.takeApplied();
        a.engine.pullAll();
        eq("pulling again is idempotent", before, a.lib.progress.size());
        eq("and applies nothing new", 0, a.engine.takeApplied());

        // A hidden series stays local; a hidden movie goes as finished.
        Device e = new Device(server, "uidE", "Den");
        SyncMap.P hs = episode("600", "6", 1, 1, 10_000, 100_000, t);
        hs.hidden = true;
        SyncMap.P hm = progress("601", "Movie", 10_000, 100_000, t);
        hm.hidden = true;
        e.lib.progress.put("600", hs);
        e.lib.progress.put("601", hm);
        e.engine.reconcile(t, "Den");
        eq("only the movie is queued", 1, e.state.outbox.size());
        eq("as finished", true, e.state.outbox.get(0).row.get("finished"));
    }

    private static int countFor(Server server, String table, String uid) {
        int n = 0;
        for (Map<String, Object> r : server.tables.get(table).values()) if (uid.equals(r.get("updated_by"))) n++;
        return n;
    }
}
