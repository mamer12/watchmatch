package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Runs the real sync code (SyncClient, SyncMap, SyncRules) against the real backend, on a plain JVM with only
 * HttpsURLConnection: two anonymous devices pair with a code, A writes progress, My List, a rating and a setting, B reads
 * them, B updates, A reads, an old offline replay loses, then two profiles sync through the same engine (the profiles row,
 * prefixed rows, rows from before profiles as main's, a PIN hash, deleting with the synced data), and then both leave the space (which cleans up after itself).
 * Prints PASS or FAIL lines and exits 1 on any FAIL. The URL and the anon key are public values (they can be on the
 * command line); the tokens it gets are never printed. From the repository's root (android.jar only so that Backoff
 * compiles; nothing of Android runs):
 *
 *   AJ=$(ls "$ANDROID_HOME"/platforms/android-*\/android.jar | sort -V | tail -n 1)
 *   mkdir -p out/test && javac -cp "$AJ" -d out/test app/src/io/watchmatch/{MiniJson,Backoff,PinRules,ProfileRules,SyncRules,SyncMap,SyncState,SyncClient,SyncEngine}.java \
 *       app/test/io/watchmatch/{ProfileTestKit,SyncLiveCheck}.java
 *   java -cp out/test io.watchmatch.SyncLiveCheck "$(sed -n 's/^supabase_url=//p' config.properties)" \
 *       "$(sed -n 's/^supabase_anon_key=//p' config.properties)"
 */
public final class SyncLiveCheck {
    private static int failures;

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (!ok && detail != null && !detail.isEmpty() ? "  (" + detail + ")" : ""));
    }

    private static final class Mem implements SyncClient.Store {
        SyncClient.Session s;

        @Override public SyncClient.Session load() {
            return s;
        }

        @Override public void save(SyncClient.Session x) {
            s = x;
        }
    }

    private static final class Dev {
        final String label;
        final Mem store = new Mem();
        final SyncClient client;

        Dev(String label, String url, String key) {
            this.label = label;
            client = new SyncClient(url, key, store, System::currentTimeMillis);
        }
    }

    private static SyncMap.T title(String id, String name) {
        SyncMap.T t = new SyncMap.T();
        t.id = id;
        t.title = name;
        t.overview = "Live check";
        t.year = "2024";
        t.stars = "7.5";
        t.genres = "Drama · Action";
        t.poster = "https://example.invalid/" + id + ".jpg";
        t.minutes = 45;
        return t;
    }

    private static SyncRules.Item progress(String video, String root, long position, String device, long ts) {
        SyncMap.P p = new SyncMap.P();
        p.title = title(video, "Live Show");
        p.title.rootId = root;
        p.title.series = true;
        p.title.season = 2;
        p.title.episode = 4;
        p.rootTitle = "Live Show";
        p.rootPoster = "https://example.invalid/root.jpg";
        p.position = position;
        p.duration = 2_700_000;
        return new SyncRules.Item("watch_progress", SyncMap.progressKey(video), SyncMap.progressRow(p, device), ts);
    }

    private static void push(Dev d, String space, SyncRules.Item... items) throws Exception {
        List<SyncRules.Item> list = Arrays.asList(items);
        for (String table : SyncRules.TABLES) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SyncRules.Item i : list) if (i.table.equals(table)) rows.add(SyncRules.toRow(space, i));
            d.client.upsert(table, rows);
        }
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, String key, String table) {
        for (Map<String, Object> r : rows) if (key.equals(SyncRules.keyOf(table, r))) return r;
        return null;
    }

    /** The sync engine's way to the real backend. */
    private static SyncEngine.Api api(final Dev d) {
        return new SyncEngine.Api() {
            @Override public String userId() {
                return d.client.userId();
            }

            @Override public void upsert(String table, List<Map<String, Object>> rows) throws SyncClient.HttpException {
                d.client.upsert(table, rows);
            }

            @Override public List<Map<String, Object>> pull(String table, String space, String cursor, int limit) throws SyncClient.HttpException {
                return d.client.pull(table, space, cursor, limit);
            }
        };
    }

    private static final class Node {
        final ProfileTestKit.Lib lib = new ProfileTestKit.Lib();
        final SyncState state = new SyncState();
        final SyncEngine engine;

        Node(Dev d, String space, String name) {
            state.space = space;
            state.device = name;
            engine = new SyncEngine(state, api(d), lib, s -> { });
        }

        void sync() throws SyncClient.HttpException {
            engine.reconcile(System.currentTimeMillis(), state.device);
            engine.pullAll();
            engine.pushAll();
        }
    }

    private static boolean live(List<Map<String, Object>> rows, String table, String key) {
        Map<String, Object> r = find(rows, key, table);
        return r != null && !MiniJson.bool(r.get("deleted"));
    }

    /** Profiles through the real backend: the profiles row, prefixed rows, old rows as main's, PIN, and deleting with the data. */
    private static void profiles(Dev da, Dev db, String space) throws Exception {
        Node a = new Node(da, space, "Live Check A"), b = new Node(db, space, "Live Check B");
        // The rows the sections above wrote have no profile: they are main's on a device that knows profiles.
        a.sync();      // (B wrote the newest of those rows, so A is the one that reads them)
        check("rows from before profiles are main's (progress)", a.lib.of("main").progress.containsKey("live-456")
                && a.lib.of("main").progress.get("live-456").position == 900_000, "");
        check("and the untouched default list was not uploaded", !live(da.client.pull("settings", space, null, 1000), "settings", "profiles"), "");

        ProfileRules.Profile zoe = a.lib.addProfile("lvzoe001", "Zoe", true);
        zoe.pin = PinRules.hash("2468");
        zoe.avatar = "panda";
        a.lib.profiles = ProfileRules.update(a.lib.profiles, zoe);
        a.lib.of("lvzoe001").list.add(ProfileTestKit.title("live-2", "Live Cartoon"));
        a.lib.of("lvzoe001").progress.put("live-30", ProfileTestKit.progress("live-30", "Live Kids", 5000, System.currentTimeMillis()));
        a.lib.of("lvzoe001").ratings.put("live-2", SyncMap.RATE_LOVE);
        a.lib.of("lvzoe001").settings.put("subtitleLang", "en");
        a.lib.of("main").list.add(ProfileTestKit.title("live-1", "Live Mine"));
        a.sync();
        List<Map<String, Object>> sRows = db.client.pull("settings", space, null, 1000);
        check("the profiles row is on the server as an array", find(sRows, "profiles", "settings") != null
                && MiniJson.list(find(sRows, "profiles", "settings").get("value")).size() == 2, "");
        check("Zoe's setting row has a pref: key", live(sRows, "settings", "pref:lvzoe001:subtitleLang"), "");
        check("Zoe's rows are prefixed", live(db.client.pull("my_list", space, null, 1000), "my_list", "lvzoe001~live-2")
                && live(db.client.pull("watch_progress", space, null, 1000), "watch_progress", "lvzoe001~v:live-30")
                && live(db.client.pull("ratings", space, null, 1000), "ratings", "lvzoe001~live-2"), "");

        b.sync();
        ProfileRules.Profile got = ProfileRules.byId(b.lib.profiles, "lvzoe001");
        check("B has Zoe, as Kids, with her avatar", got != null && got.kids && "panda".equals(got.avatar), "");
        check("her synced PIN hash works on B", got != null && PinRules.verify("2468", got.pin) && !PinRules.verify("1357", got.pin), "");
        check("Zoe's data is in Zoe on B", b.lib.of("lvzoe001").listIds().contains("live-2") && b.lib.of("lvzoe001").progress.containsKey("live-30")
                && Integer.valueOf(SyncMap.RATE_LOVE).equals(b.lib.of("lvzoe001").ratings.get("live-2"))
                && "en".equals(b.lib.of("lvzoe001").settings.get("subtitleLang")), "");
        check("and not in main", !b.lib.of("main").listIds().contains("live-2") && !b.lib.of("main").progress.containsKey("live-30"), "");
        check("main's own title reached B's main", b.lib.of("main").listIds().contains("live-1"), "");

        // B changes Zoe's data while another profile is the active one: it still goes out.
        b.lib.active = "main";
        b.lib.of("lvzoe001").ratings.put("live-2", SyncMap.RATE_DISLIKE);
        b.sync();
        a.sync();
        check("a change to a non-active profile reaches A", Integer.valueOf(SyncMap.RATE_DISLIKE).equals(a.lib.of("lvzoe001").ratings.get("live-2")), "");

        // Delete with the synced data: the rows are deleted on the server, the profile is a tombstone.
        a.lib.delete("lvzoe001", true);
        a.sync();
        List<Map<String, Object>> after = db.client.pull("my_list", space, null, 1000);
        check("deleting with the synced data deletes Zoe's rows", !live(after, "my_list", "lvzoe001~live-2")
                && !live(db.client.pull("watch_progress", space, null, 1000), "watch_progress", "lvzoe001~v:live-30")
                && !live(db.client.pull("ratings", space, null, 1000), "ratings", "lvzoe001~live-2")
                && !live(db.client.pull("settings", space, null, 1000), "settings", "pref:lvzoe001:subtitleLang"), "");
        check("main's rows are untouched", live(after, "my_list", "main~live-1"), "");
        b.sync();
        ProfileRules.Profile gone = ProfileRules.byId(b.lib.profiles, "lvzoe001");
        check("B sees the tombstone and drops the data", gone != null && gone.deleted && !b.lib.data.containsKey("lvzoe001"), "");
        check("nothing is left queued on either device", a.state.outbox.isEmpty() && b.state.outbox.isEmpty(), "");
    }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0].trim() : System.getenv("SUPABASE_URL");
        String key = args.length > 1 ? args[1].trim() : System.getenv("SUPABASE_ANON_KEY");
        if (url == null || key == null || url.isEmpty() || key.isEmpty()) {
            System.out.println("FAIL  pass the project URL and the public anon key (arguments or SUPABASE_URL, SUPABASE_ANON_KEY)");
            System.exit(1);
        }
        Dev a = new Dev("A", url, key), b = new Dev("B", url, key);
        String space = null;
        try {
            // Two anonymous accounts (the first call of each signs up).
            Object pinged = a.client.rpc("ping", null, true);
            check("A signs in anonymously", a.client.signedIn(), "");
            b.client.rpc("ping", null, true);
            check("B signs in anonymously", b.client.signedIn() && !a.client.userId().equals(b.client.userId()), "");

            Object created = a.client.rpc("create_space", MiniJson.obj("device_name", "Live Check A"), false);
            space = created instanceof String ? (String) created : null;
            check("A creates a space", space != null && !space.isEmpty(), String.valueOf(created));
            check("create_space is idempotent", space != null && space.equals(a.client.rpc("create_space", MiniJson.obj("device_name", "Live Check A"), false)), "");

            Object code = a.client.rpc("create_pairing_code", null, false);
            check("A gets an 8-character pairing code", code instanceof String && SyncRules.isCompleteCode((String) code), "");
            String codeText = String.valueOf(code);
            check("the join link carries the code", SyncRules.joinUrl(codeText).endsWith("?join=" + codeText), "");

            check("a wrong code returns null", b.client.rpc("redeem_pairing_code", MiniJson.obj("code", "ZZZZZZZZ", "device_name", "Live Check B"), false) == null, "");
            String sloppy = codeText.substring(0, 4).toLowerCase() + " - " + codeText.substring(4);
            Object joined = b.client.rpc("redeem_pairing_code", MiniJson.obj("code", SyncRules.normalizeCode(sloppy), "device_name", "Live Check B"), false);
            check("B joins with a sloppily typed code", space != null && space.equals(joined), String.valueOf(joined));
            check("a used code cannot be used again", b.client.rpc("redeem_pairing_code", MiniJson.obj("code", codeText, "device_name", "Live Check B"), false) == null, "");

            List<Object> devices = MiniJson.list(a.client.rpc("list_devices", null, false));
            int mine = 0;
            boolean hasB = false;
            for (Object o : devices) {
                Map<String, Object> d = MiniJson.map(o);
                if (MiniJson.bool(d.get("is_me"))) mine++;
                if ("Live Check B".equals(d.get("device_name"))) hasB = true;
            }
            check("list_devices shows both, A marked", devices.size() == 2 && mine == 1 && hasB, String.valueOf(devices.size()));

            // A writes in the app's format.
            long now = System.currentTimeMillis();
            SyncMap.T dune = title("live-77", "Live Dune");
            push(a, space,
                    progress("live-456", "live-99", 734_000, "Live Check A", now),
                    new SyncRules.Item("my_list", "live-77", SyncMap.myListRow(dune, "Live Check A"), now),
                    new SyncRules.Item("ratings", "live-77", SyncMap.ratingRow("live-77", SyncMap.RATE_LOVE), now),
                    new SyncRules.Item("settings", "titleLang", SyncMap.settingRow("titleLang", true), now));
            check("A writes progress, My List, a rating and a setting", true, "");

            // B reads them and maps them back.
            List<Map<String, Object>> bProgress = b.client.pull("watch_progress", space, null, 1000);
            Map<String, Object> prow = find(bProgress, "v:live-456", "watch_progress");
            SyncMap.RP rp = prow == null ? null : SyncMap.progressFromRow(prow, false);
            check("B reads A's progress", rp != null && rp.p != null && rp.p.position == 734_000 && rp.p.duration == 2_700_000, "");
            check("with the series and episode", rp != null && "live-99".equals(rp.p.title.rootId) && rp.p.title.season == 2 && rp.p.title.episode == 4, "");
            check("with the title and series name", rp != null && "Live Show".equals(rp.p.title.title) && "Live Show".equals(rp.p.rootTitle), "");
            check("and where it came from", rp != null && "Live Check A".equals(rp.p.from) && a.client.userId().equals(prow.get("updated_by")), "");
            check("the server stamped the row", prow != null && SyncRules.serverMs(prow) > 0, "");
            Map<String, Object> lrow = find(b.client.pull("my_list", space, null, 1000), "live-77", "my_list");
            SyncMap.T seen = lrow == null ? null : SyncMap.myListFromRow(lrow, false);
            check("B reads A's My List title", seen != null && "Live Dune".equals(seen.title) && "Drama · Action".equals(seen.genres), "");
            Map<String, Object> rrow = find(b.client.pull("ratings", space, null, 1000), "live-77", "ratings");
            check("B reads A's rating as a love", rrow != null && SyncMap.ratingFromScore(MiniJson.lng(rrow.get("rating"))) == SyncMap.RATE_LOVE, "");
            Map<String, Object> srow = find(b.client.pull("settings", space, null, 1000), "titleLang", "settings");
            check("B reads A's setting", srow != null && Boolean.TRUE.equals(SyncMap.fromRemote("titleLang", srow.get("value"), false)), "");

            // B updates; A reads through a delta pull from the cursor.
            String cursor = MiniJson.str(prow.get("updated_at"));
            push(b, space,
                    progress("live-456", "live-99", 900_000, "Live Check B", System.currentTimeMillis()),
                    new SyncRules.Item("my_list", "live-77", SyncMap.myListTombstone("live-77"), System.currentTimeMillis()),
                    new SyncRules.Item("ratings", "live-77", SyncMap.ratingRow("live-77", SyncMap.RATE_DISLIKE), System.currentTimeMillis()));
            List<Map<String, Object>> delta = a.client.pull("watch_progress", space, cursor, 1000);
            Map<String, Object> drow = find(delta, "v:live-456", "watch_progress");
            SyncMap.RP back = drow == null ? null : SyncMap.progressFromRow(drow, false);
            check("A sees B's newer position through a delta pull", back != null && back.p.position == 900_000 && "Live Check B".equals(back.p.from)
                    && b.client.userId().equals(drow.get("updated_by")), "");
            Map<String, Object> tomb = find(a.client.pull("my_list", space, null, 1000), "live-77", "my_list");
            check("the My List tombstone reaches A", tomb != null && MiniJson.bool(tomb.get("deleted")), "");
            Map<String, Object> drate = find(a.client.pull("ratings", space, null, 1000), "live-77", "ratings");
            check("B's new rating reaches A", drate != null && SyncMap.ratingFromScore(MiniJson.lng(drate.get("rating"))) == SyncMap.RATE_DISLIKE, "");

            // An old offline replay (older client_ts) must lose.
            push(a, space, progress("live-456", "live-99", 1_000, "Live Check A", now - 60_000));
            Map<String, Object> after = find(b.client.pull("watch_progress", space, null, 1000), "v:live-456", "watch_progress");
            check("a stale offline replay is ignored", after != null && MiniJson.lng(after.get("position_ms")) == 900_000, after == null ? "" : String.valueOf(after.get("position_ms")));
            check("the first call of ping answered", pinged == null || pinged instanceof String || pinged instanceof Map || pinged instanceof List || pinged instanceof Boolean, "");
            profiles(a, b, space);
        } catch (Exception e) {
            check("ran without an unexpected error", false, e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            // Clean-up: both leave; the empty space and its data go with the last one.
            for (Dev d : new Dev[] {a, b}) {
                if (!d.client.signedIn()) continue;
                try {
                    d.client.rpc("leave_space", null, false);
                    check(d.label + " leaves the space", true, "");
                } catch (Exception e) {
                    check(d.label + " leaves the space", false, e.getMessage());
                }
            }
            if (space != null && a.client.signedIn()) {
                try {
                    List<Map<String, Object>> gone = a.client.pull("watch_progress", space, null, 10);
                    check("after leaving, the data is no longer readable", gone.isEmpty(), String.valueOf(gone.size()));
                } catch (Exception e) {
                    check("after leaving, the data is no longer readable", false, e.getMessage());
                }
            }
        }
        System.out.println(failures == 0 ? "\nAll checks passed." : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
