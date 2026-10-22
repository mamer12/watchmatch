package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Checks Watchme+'s profiles on a plain JVM, with no Android and no network: the model and its limits (6, names, ids,
 * avatars, colours), what the legacy data becomes (everything is main's, under the file names it already had), isolation
 * between profiles, Kids forcing, the PIN (hash, verify, constant-time compare, the 5-try lockout and its clock), key
 * prefixing and the mapping of keys from before profiles, tombstones and merging a synced list, who must enter which
 * PIN, what opens at start (picker or auto-reopen), a saved sync state from before profiles, and two devices syncing
 * several profiles through a fake backend (each profile's rows, the profiles row, deletion with and without the synced
 * data). From the repository's root (android.jar only so that Backoff compiles; nothing of Android runs):
 *
 *   AJ=$(ls "$ANDROID_HOME"/platforms/android-*\/android.jar | sort -V | tail -n 1)
 *   mkdir -p out/test && javac -cp "$AJ" -d out/test app/src/io/watchmatch/{MiniJson,Backoff,PinRules,ProfileRules,SyncRules,SyncMap,SyncState,SyncClient,SyncEngine}.java \
 *       app/test/io/watchmatch/{ProfileTestKit,ProfileRulesCheck}.java && java -cp out/test io.watchmatch.ProfileRulesCheck
 */
public final class ProfileRulesCheck {
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
        model();
        migration();
        isolation();
        kids();
        pins();
        lockout();
        keys();
        tombstonesAndMerge();
        gates();
        start();
        oldState();
        devices();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static ProfileRules.Profile profile(String id, String name, boolean kids, String pin) {
        ProfileRules.Profile p = new ProfileRules.Profile();
        p.id = id;
        p.name = name;
        p.kids = kids;
        p.created = 10;
        p.pin = pin == null ? null : PinRules.hash(pin);
        return p;
    }

    private static List<ProfileRules.Profile> listOf(ProfileRules.Profile... ps) {
        List<ProfileRules.Profile> out = new ArrayList<>();
        for (ProfileRules.Profile p : ps) out = ProfileRules.add(out, p);
        return out;
    }

    // ---- the model --------------------------------------------------------------------------

    private static void model() {
        ProfileRules.Profile main = ProfileRules.main(5);
        eq("the first profile is main", "main", main.id);
        eq("named Me", "Me", main.name);
        eq("not kids, no PIN", true, !main.kids && main.pin == null);
        eq("twelve avatars", 12, ProfileRules.AVATARS.length);
        eq("the avatar set", Arrays.asList("fox", "owl", "bear", "cat", "dog", "panda", "lion", "rocket", "star", "moon", "sun", "wave"),
                Arrays.asList(ProfileRules.AVATARS));
        eq("eight colours", 8, ProfileRules.COLORS);
        eq("names are trimmed", "Sam", ProfileRules.cleanName("  Sam \n"));
        eq("spaces collapse", "Mary Ann", ProfileRules.cleanName("Mary    Ann"));
        eq("names are cut at 20", 20, ProfileRules.cleanName("abcdefghijklmnopqrstuvwxyz").length());
        eq("an empty name is not valid", false, ProfileRules.validName("   "));
        eq("one character is", true, ProfileRules.validName("A"));
        eq("an Arabic name", "سارة", ProfileRules.cleanName(" سارة "));

        Random r = new Random(7);
        List<ProfileRules.Profile> list = ProfileRules.add(new ArrayList<ProfileRules.Profile>(), main);
        for (int i = 0; i < 200; i++) {
            String id = ProfileRules.newId(r, list);
            eq("an id of 8 characters [a-z0-9]", true, id.matches("[a-z0-9]{8}") && !id.equals("main"));
        }
        // At most 6.
        for (int i = 1; i < 6; i++) list = ProfileRules.add(list, profile("p" + i, "P" + i, false, null));
        eq("six profiles", 6, ProfileRules.live(list).size());
        eq("no more room", false, ProfileRules.canAdd(list));
        eq("a seventh is refused", 6, ProfileRules.live(ProfileRules.add(list, profile("p9", "Nine", false, null))).size());
        eq("a bad name is refused", 6, ProfileRules.add(list, profile("pz", " ", false, null)).size());
        List<ProfileRules.Profile> five = ProfileRules.delete(list, "p3");
        eq("after a delete there is room", true, ProfileRules.canAdd(five));
        eq("the deleted one stays as a tombstone", true, ProfileRules.byId(five, "p3").deleted);
        eq("its id can't be added again", 5, ProfileRules.live(ProfileRules.add(five, profile("p3", "Again", false, null))).size());
        eq("the order keeps adding up", 5, ProfileRules.byId(list, "p5").order);

        // Edits keep the id, order and creation time; a bad avatar falls back.
        ProfileRules.Profile edit = ProfileRules.byId(list, "p2").copy();
        edit.name = "  Renamed ";
        edit.avatar = "nope";
        edit.color = 99;
        edit.order = 77;
        List<ProfileRules.Profile> edited = ProfileRules.update(list, edit);
        eq("renamed", "Renamed", ProfileRules.byId(edited, "p2").name);
        eq("a bad avatar keeps the old one", ProfileRules.byId(list, "p2").avatar, ProfileRules.byId(edited, "p2").avatar);
        eq("colours are clamped", 7, ProfileRules.byId(edited, "p2").color);
        eq("the order stays", 2, ProfileRules.byId(edited, "p2").order);
        eq("an empty name is not saved", list, ProfileRules.update(list, profile("p2", "", false, null)));
        eq("the last profile can't be deleted", 1, ProfileRules.live(ProfileRules.delete(ProfileRules.add(new ArrayList<ProfileRules.Profile>(), main), "main")).size());
        eq("main can go when there are others", true, ProfileRules.byId(ProfileRules.delete(list, "main"), "main").deleted);

        // JSON, as it travels in the one settings row.
        ProfileRules.Profile kid = profile("kid00001", "Zoe", true, "1234");
        kid.avatar = "panda";
        kid.color = 5;
        List<ProfileRules.Profile> two = ProfileRules.add(ProfileRules.add(new ArrayList<ProfileRules.Profile>(), main), kid);
        two = ProfileRules.delete(ProfileRules.add(two, profile("gone0001", "Gone", false, null)), "gone0001");
        List<Object> json = ProfileRules.toJson(two);
        eq("the row is an array", 3, json.size());
        eq("a tombstone is {id, deleted}", MiniJson.obj("id", "gone0001", "deleted", true), json.get(2));
        List<ProfileRules.Profile> back = ProfileRules.fromJson(MiniJson.parse(MiniJson.write(json)));
        eq("round trip", ProfileRules.signature(two), ProfileRules.signature(back));
        eq("the PIN hash comes back and works", true, PinRules.verify("1234", ProfileRules.byId(back, "kid00001").pin));
        eq("kids and avatar", true, ProfileRules.byId(back, "kid00001").kids && "panda".equals(ProfileRules.byId(back, "kid00001").avatar));
        eq("junk entries are skipped", 1, ProfileRules.fromJson(MiniJson.parse(
                "[{\"id\":\"BAD ID\",\"name\":\"x\"},{\"id\":\"ok1\",\"name\":\"Fine\",\"color\":3,\"avatar\":\"fox\"},{\"id\":\"ok2\"},7,null]")).size());
        eq("a broken PIN is dropped, not trusted", null, ProfileRules.fromJson(MiniJson.parse(
                "[{\"id\":\"ok1\",\"name\":\"Fine\",\"pin\":{\"salt\":\"!!\",\"hash\":\"\",\"iter\":0}}]")).get(0).pin);
        eq("only the untouched default list says nothing", true, ProfileRules.isDefaultOnly(ProfileRules.add(new ArrayList<ProfileRules.Profile>(), main)));
        ProfileRules.Profile renamed = main.copy();
        renamed.name = "Sam";
        eq("a renamed one is not the default", false, ProfileRules.isDefaultOnly(Arrays.asList(renamed)));
    }

    // ---- migration into main ----------------------------------------------------------------

    private static void migration() {
        // Main keeps the saved-data names used before profiles: nothing has to be moved or rewritten.
        eq("main's progress is the file it was", "progress", ProfileRules.storeName("progress", "main"));
        eq("main's settings too", "settings", ProfileRules.storeName("settings", "main"));
        eq("another profile has its own", "progress-k3j9x2ab", ProfileRules.storeName("progress", "k3j9x2ab"));
        // A device from before profiles: its files are keyed by the legacy names.
        Map<String, Map<String, Object>> files = new HashMap<>();
        Map<String, Object> legacyProgress = new HashMap<>();
        legacyProgress.put("456", "{progress}");
        files.put("progress", legacyProgress);
        Map<String, Object> legacySettings = new HashMap<>();
        legacySettings.put("myList", "[77]");
        legacySettings.put("rate:77", 10);
        legacySettings.put("level", 2);
        legacySettings.put("likedGenres", "drama");
        legacySettings.put("welcomed", 1);
        legacySettings.put("autoplay", false);
        legacySettings.put("subtitles", "en");
        files.put("settings", legacySettings);
        eq("the first run's profile is main", "main", ProfileRules.main(1).id);
        eq("its progress is there", "{progress}", files.get(ProfileRules.storeName("progress", ProfileRules.MAIN)).get("456"));
        Map<String, Object> mainSettings = files.get(ProfileRules.storeName("settings", ProfileRules.MAIN));
        eq("its My List, rating, taste, welcome and viewing prefs are there",
                Arrays.asList("[77]", 10, "drama", 1, false, "en", 2),
                Arrays.asList(mainSettings.get("myList"), mainSettings.get("rate:77"), mainSettings.get("likedGenres"),
                        mainSettings.get("welcomed"), mainSettings.get("autoplay"), mainSettings.get("subtitles"), mainSettings.get("level")));
        eq("a new profile starts empty", null, files.get(ProfileRules.storeName("settings", "k3j9x2ab")));
    }

    // ---- isolation --------------------------------------------------------------------------

    private static void isolation() {
        ProfileTestKit.Lib lib = new ProfileTestKit.Lib();
        lib.addProfile("zoe00001", "Zoe", true);
        lib.of("main").list.add(ProfileTestKit.title("1", "Mine"));
        lib.of("zoe00001").list.add(ProfileTestKit.title("2", "Zoe's"));
        lib.of("main").progress.put("9", ProfileTestKit.progress("9", "Show", 1000, 5));
        lib.of("main").ratings.put("1", SyncMap.RATE_LOVE);
        lib.of("zoe00001").settings.put("autoplayNext", false);
        eq("each list is its own", Arrays.asList("1"), lib.of("main").listIds());
        eq("and Zoe's", Arrays.asList("2"), lib.of("zoe00001").listIds());
        eq("progress is not shared", true, lib.of("zoe00001").progress.isEmpty());
        eq("ratings are not shared", true, lib.of("zoe00001").ratings.isEmpty());
        eq("viewing prefs are not shared", Boolean.TRUE, lib.of("main").settings.get("autoplayNext"));
        Map<String, SyncEngine.Snapshot> snaps = lib.snapshots();
        eq("the sync sees both", 2, snaps.size());
        eq("main's snapshot", 1, snaps.get("main").progress.size());
        eq("Zoe's snapshot", 0, snaps.get("zoe00001").progress.size());
    }

    // ---- Kids -------------------------------------------------------------------------------

    private static void kids() {
        eq("a Kids profile is at least family", 2, ProfileRules.effectiveLevel(true, 0));
        eq("whatever was saved", 2, ProfileRules.effectiveLevel(true, 2));
        eq("a stricter saved one stays", 3, ProfileRules.effectiveLevel(true, 3));
        eq("an adult keeps the saved one", 0, ProfileRules.effectiveLevel(false, 0));
        eq("an adult's family filter", 2, ProfileRules.effectiveLevel(false, 2));
        eq("a Kids profile always skips sensitive scenes", true, ProfileRules.effectiveSkip(true, false));
        eq("an adult's choice stands", false, ProfileRules.effectiveSkip(false, false));
        eq("an adult's yes stands", true, ProfileRules.effectiveSkip(false, true));
        for (int f : new int[] {ProfileRules.F_SYNC, ProfileRules.F_RESET, ProfileRules.F_PROFILES, ProfileRules.F_FILTER, ProfileRules.F_REMOTE_BUTTONS}) {
            eq("Kids can't reach feature " + f, false, ProfileRules.allowed(true, f));
            eq("an adult can reach feature " + f, true, ProfileRules.allowed(false, f));
        }
    }

    // ---- PINs -------------------------------------------------------------------------------

    private static void pins() {
        eq("4 digits", true, PinRules.validFormat("1234"));
        eq("6 digits", true, PinRules.validFormat("012345"));
        eq("3 is too short", false, PinRules.validFormat("123"));
        eq("7 is too long", false, PinRules.validFormat("1234567"));
        eq("letters are not digits", false, PinRules.validFormat("12a4"));
        eq("null", false, PinRules.validFormat(null));
        Map<String, Object> h = PinRules.hash("4821");
        eq("salt, iterations and hash", true, h.containsKey("salt") && h.containsKey("hash") && MiniJson.lng(h.get("iter")) == 20000);
        eq("a 16-byte salt", 16, java.util.Base64.getDecoder().decode(MiniJson.str(h.get("salt"))).length);
        eq("a 32-byte hash", 32, java.util.Base64.getDecoder().decode(MiniJson.str(h.get("hash"))).length);
        eq("the right PIN", true, PinRules.verify("4821", h));
        eq("a wrong PIN", false, PinRules.verify("4822", h));
        eq("a malformed entry", false, PinRules.verify("abc", h));
        eq("no hash on file", false, PinRules.verify("4821", null));
        eq("garbage on file", false, PinRules.verify("4821", MiniJson.obj("salt", "!!", "hash", "??", "iter", 20000L)));
        eq("two hashes of one PIN differ (random salt)", false, PinRules.hash("4821").get("hash").equals(PinRules.hash("4821").get("hash")));
        // A known vector (PBKDF2-HMAC-SHA256, 20000 iterations): the web app must agree.
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) i;
        Map<String, Object> fixed = PinRules.hash("1234", salt);
        eq("the same salt gives the same hash", fixed.get("hash"), PinRules.hash("1234", salt).get("hash"));
        eq("and it verifies", true, PinRules.verify("1234", fixed));
        eq("the stored form survives JSON", true, PinRules.verify("1234", MiniJson.map(MiniJson.parse(MiniJson.write(fixed)))));
    }

    private static void lockout() {
        Map<String, Object> h = PinRules.hash("1234");
        PinRules.Lock lock = PinRules.Lock.NONE;
        long t = 1_000_000;
        for (int i = 1; i <= 4; i++) {
            PinRules.Attempt a = PinRules.attempt("0000", h, lock, t);
            eq("wrong try " + i + " is just wrong", PinRules.WRONG, a.result);
            lock = a.lock;
            eq("tries left after " + i, PinRules.MAX_TRIES - i, PinRules.triesLeft(lock));
        }
        PinRules.Attempt fifth = PinRules.attempt("0000", h, lock, t);
        eq("the 5th wrong try locks", PinRules.LOCKED, fifth.result);
        eq("for 5 minutes", t + 5 * 60_000L, fifth.lock.until);
        eq("locked", true, PinRules.locked(fifth.lock, t + 1000));
        eq("the right PIN is not even checked while locked", PinRules.LOCKED, PinRules.attempt("1234", h, fifth.lock, t + 1000).result);
        eq("still locked a moment before the end", true, PinRules.locked(fifth.lock, t + 5 * 60_000L - 1));
        eq("free at the end", false, PinRules.locked(fifth.lock, t + 5 * 60_000L));
        eq("the right PIN then opens it", PinRules.OK, PinRules.attempt("1234", h, fifth.lock, t + 5 * 60_000L + 1).result);
        eq("and clears the count", PinRules.Lock.NONE, PinRules.attempt("1234", h, new PinRules.Lock(3, 0), t).lock);
        // After the lock ends, the count starts again from zero.
        PinRules.Attempt again = PinRules.attempt("0000", h, fifth.lock, t + 5 * 60_000L + 1);
        eq("after the lock, a wrong try is the first again", PinRules.MAX_TRIES - 1, PinRules.triesLeft(again.lock));
        // Persisted: a Lock is two numbers, and it still locks after a restart.
        PinRules.Lock restored = new PinRules.Lock(fifth.lock.fails, fifth.lock.until);
        eq("a restored lock still locks", true, PinRules.locked(restored, t + 60_000));
        // The clock (TrustedTime): set back, the wait is never more than 5 minutes.
        eq("a clock set back can't lock for longer", PinRules.LOCK_MS, PinRules.remaining(fifth.lock, t - 3_600_000L));
        eq("remaining counts down", 4 * 60_000L, PinRules.remaining(fifth.lock, t + 60_000));
        // The screens hash off the UI thread, then apply the outcome.
        eq("result() applies a match", PinRules.OK, PinRules.result(true, new PinRules.Lock(2, 0), t).result);
        eq("and a miss", PinRules.WRONG, PinRules.result(false, new PinRules.Lock(2, 0), t).result);
        eq("a match while locked still fails", PinRules.LOCKED, PinRules.result(true, fifth.lock, t + 1).result);
    }

    // ---- keys -------------------------------------------------------------------------------

    private static void keys() {
        eq("a prefixed key", "k3j9x2ab~v:12", ProfileRules.prefixKey("k3j9x2ab", "v:12"));
        eq("split back", Arrays.asList("k3j9x2ab", "v:12"), Arrays.asList(ProfileRules.splitKey("k3j9x2ab~v:12")));
        eq("main's own prefix", Arrays.asList("main", "77"), Arrays.asList(ProfileRules.splitKey("main~77")));
        eq("a key from before profiles is main's", Arrays.asList("main", "v:12"), Arrays.asList(ProfileRules.splitKey("v:12")));
        eq("a title id from before profiles", Arrays.asList("main", "77"), Arrays.asList(ProfileRules.splitKey("77")));
        eq("an id that can't be one is no prefix", Arrays.asList("main", "BAD~x"), Arrays.asList(ProfileRules.splitKey("BAD~x")));
        eq("a setting key", "pref:k3j9x2ab:subtitleLang", ProfileRules.prefKey("k3j9x2ab", "subtitleLang"));
        eq("split back", Arrays.asList("k3j9x2ab", "subtitleLang"), Arrays.asList(ProfileRules.splitSettingKey("pref:k3j9x2ab:subtitleLang")));
        eq("a plain setting is main's", Arrays.asList("main", "level"), Arrays.asList(ProfileRules.splitSettingKey("level")));
        eq("the profiles row is not a setting", null, ProfileRules.splitSettingKey("profiles"));
        eq("a malformed pref key is nothing", null, ProfileRules.splitSettingKey("pref:xx"));
        Map<String, Object> row = SyncMap.scope("watch_progress", MiniJson.obj("item_key", "v:5", "title_id", "99"), "k3j9x2ab");
        eq("progress: item_key prefixed", "k3j9x2ab~v:5", row.get("item_key"));
        eq("progress: the series id stays", "99", row.get("title_id"));
        eq("My List: title_id prefixed", "k3j9x2ab~7", SyncMap.scope("my_list", MiniJson.obj("title_id", "7"), "k3j9x2ab").get("title_id"));
        eq("ratings: title_id prefixed", "k3j9x2ab~7", SyncMap.scope("ratings", MiniJson.obj("title_id", "7", "rating", 8L), "k3j9x2ab").get("title_id"));
        eq("settings: key becomes pref:", "pref:k3j9x2ab:level", SyncMap.scope("settings", MiniJson.obj("key", "level", "value", 2L), "k3j9x2ab").get("key"));
        eq("unscope puts the key back", "v:5", SyncMap.unscope("watch_progress", row, "v:5").get("item_key"));
        eq("every key fits the server's 64 characters", true, ProfileRules.prefKey("k3j9x2ab", "subtitleBackground").length() <= 64);
    }

    // ---- tombstones and merging a synced list -----------------------------------------------

    private static void tombstonesAndMerge() {
        ProfileRules.Profile a = profile("main", "Me", false, null), b = profile("bbbb0001", "Bea", false, null);
        List<ProfileRules.Profile> local = listOf(a, b);
        List<ProfileRules.Profile> remote = ProfileRules.delete(listOf(a, b), "bbbb0001");
        List<ProfileRules.Profile> merged = ProfileRules.mergeRemote(local, remote);
        eq("a deletion elsewhere reaches this device", true, ProfileRules.byId(merged, "bbbb0001").deleted);
        eq("the rest stays", 1, ProfileRules.live(merged).size());
        List<ProfileRules.Profile> localDeleted = ProfileRules.delete(listOf(a, b), "bbbb0001");
        eq("a deletion here beats a live copy on the server", true, ProfileRules.byId(ProfileRules.mergeRemote(localDeleted, listOf(a, b)), "bbbb0001").deleted);
        ProfileRules.Profile renamed = b.copy();
        renamed.name = "Beatrice";
        eq("the server decides a field", "Beatrice", ProfileRules.byId(ProfileRules.mergeRemote(local, listOf(a, renamed)), "bbbb0001").name);
        ProfileRules.Profile onlyHere = profile("cccc0001", "Cy", false, null);
        eq("a profile only this device has stays", 3, ProfileRules.live(ProfileRules.mergeRemote(listOf(a, b, onlyHere), listOf(a, b))).size());
        eq("a profile only the server has arrives", 3, ProfileRules.live(ProfileRules.mergeRemote(listOf(a), listOf(a, b, onlyHere))).size());
        eq("never every profile gone", 1, ProfileRules.live(ProfileRules.mergeRemote(listOf(a), ProfileRules.fromJson(MiniJson.parse("[{\"id\":\"main\",\"deleted\":true}]")))).size());
        eq("a tombstone's value is just {id, deleted}", MiniJson.obj("id", "bbbb0001", "deleted", true), ProfileRules.toJson(remote).get(1));
        eq("the signature is stable", ProfileRules.signature(local), ProfileRules.signature(ProfileRules.fromJson(MiniJson.parse(MiniJson.write(ProfileRules.toJson(local))))));
    }

    // ---- who enters which PIN ---------------------------------------------------------------

    private static void gates() {
        ProfileRules.Profile mum = profile("main", "Mum", false, "1111"), dad = profile("dad00001", "Dad", false, null);
        ProfileRules.Profile kid = profile("kid00001", "Kid", true, null), kid2 = profile("kid00002", "Kid2", true, "2222");
        List<ProfileRules.Profile> list = listOf(mum, dad, kid, kid2);
        eq("entering a PIN-protected profile needs its PIN", ProfileRules.GATE_TARGET, ProfileRules.gateForSwitch(list, dad, mum));
        eq("a profile without one needs nothing", ProfileRules.GATE_NONE, ProfileRules.gateForSwitch(list, mum, dad));
        eq("leaving Kids for an adult without a PIN needs an adult's PIN", ProfileRules.GATE_ADULT, ProfileRules.gateForSwitch(list, kid, dad));
        eq("leaving Kids for an adult with a PIN needs that PIN (once)", ProfileRules.GATE_TARGET, ProfileRules.gateForSwitch(list, kid, mum));
        eq("Kids to Kids is free", ProfileRules.GATE_NONE, ProfileRules.gateForSwitch(list, kid, kid));
        eq("Kids to another Kids needs its own PIN", ProfileRules.GATE_TARGET, ProfileRules.gateForSwitch(list, kid, kid2));
        eq("adult to Kids is free", ProfileRules.GATE_NONE, ProfileRules.gateForSwitch(list, mum, kid));
        eq("staying where you are needs nothing", ProfileRules.GATE_NONE, ProfileRules.gateForSwitch(list, mum, mum));
        List<ProfileRules.Profile> noAdultPin = listOf(profile("main", "A", false, null), kid);
        eq("with no adult PIN, leaving Kids has no prompt", ProfileRules.GATE_NONE,
                ProfileRules.gateForSwitch(noAdultPin, kid, ProfileRules.byId(noAdultPin, "main")));
        eq("a Kids profile's PIN is not an adult's", 1, ProfileRules.adultsWithPin(listOf(profile("main", "A", false, "1111"), kid2)).size());
        eq("only Kids have PINs: no adult gate", 0, ProfileRules.adultsWithPin(listOf(profile("main", "A", false, null), kid2)).size());
        eq("managing needs an adult's PIN when one exists", ProfileRules.GATE_ADULT, ProfileRules.gateForManage(list, false));
        eq("unless one was entered this session", ProfileRules.GATE_NONE, ProfileRules.gateForManage(list, true));
        eq("and nothing when none has one", ProfileRules.GATE_NONE, ProfileRules.gateForManage(noAdultPin, false));
    }

    // ---- what opens at start ----------------------------------------------------------------

    private static void start() {
        ProfileRules.Profile a = profile("main", "A", false, null), b = profile("bbbb0001", "B", false, null);
        ProfileRules.Profile locked = profile("cccc0001", "C", false, "1234");
        eq("one profile: straight in", ProfileRules.START_OPEN, ProfileRules.startPlan(listOf(a), true, "main").kind);
        eq("several and ask on every start: the picker", ProfileRules.START_PICKER, ProfileRules.startPlan(listOf(a, b), true, "main").kind);
        ProfileRules.Start reopen = ProfileRules.startPlan(listOf(a, b), false, "bbbb0001");
        eq("not asking: the last profile", true, reopen.kind == ProfileRules.START_OPEN && reopen.id.equals("bbbb0001"));
        ProfileRules.Start gone = ProfileRules.startPlan(listOf(a, b), false, "zzzzzzzz");
        eq("a last profile that is gone: the first", "main", gone.id);
        eq("the last one with a PIN asks for it", ProfileRules.START_PIN, ProfileRules.startPlan(listOf(a, locked), false, "cccc0001").kind);
        eq("the only one with a PIN asks too", ProfileRules.START_PIN, ProfileRules.startPlan(listOf(locked), true, "cccc0001").kind);
        eq("a deleted last profile is not reopened", "main", ProfileRules.startPlan(ProfileRules.delete(listOf(a, b), "bbbb0001"), false, "bbbb0001").id);
        eq("the picker also wins over a PIN", ProfileRules.START_PICKER, ProfileRules.startPlan(listOf(a, locked), true, "cccc0001").kind);
        eq("the active profile is the saved one", "bbbb0001", ProfileRules.resolveActive(listOf(a, b), "bbbb0001").id);
        eq("or the first when it is gone", "main", ProfileRules.resolveActive(ProfileRules.delete(listOf(a, b), "bbbb0001"), "bbbb0001").id);
    }

    // ---- a saved sync state from before profiles --------------------------------------------

    private static void oldState() {
        String v1 = "{\"v\":1,\"space\":\"sp\",\"device\":\"TV\",\"cursors\":{},"
                + "\"outbox\":[{\"t\":\"my_list\",\"k\":\"77\",\"row\":{\"title_id\":\"77\",\"deleted\":false,\"meta\":{}},\"ts\":5},"
                + "{\"t\":\"watch_progress\",\"k\":\"v:9\",\"row\":{\"item_key\":\"v:9\",\"title_id\":\"9\",\"deleted\":false},\"ts\":6},"
                + "{\"t\":\"settings\",\"k\":\"level\",\"row\":{\"key\":\"level\",\"value\":2,\"deleted\":false},\"ts\":7}],"
                + "\"first\":true,\"baselined\":true,\"pushed\":{\"9\":\"6\"},\"list\":[\"77\"],\"rate\":{\"5\":2},\"set\":{\"level\":2},\"last\":3}";
        SyncState s = SyncState.fromJson(v1);
        eq("a state from before profiles loads", true, s.linked() && s.baselined);
        eq("its list item is main's", "main~77", s.outbox.get(0).key);
        eq("and the row says so", "main~77", s.outbox.get(0).row.get("title_id"));
        eq("its progress", "main~v:9", s.outbox.get(1).key);
        eq("with its row key", "main~v:9", s.outbox.get(1).row.get("item_key"));
        eq("the series id of a progress row stays", "9", s.outbox.get(1).row.get("title_id"));
        eq("its setting", "pref:main:level", s.outbox.get(2).key);
        eq("and the row's key", "pref:main:level", s.outbox.get(2).row.get("key"));
        eq("the baselines are main's", "6", s.pushed.get("main~9"));
        eq("list seen", Arrays.asList("main~77"), s.listSeen);
        eq("ratings seen", Integer.valueOf(2), s.rateSeen.get("main~5"));
        eq("settings seen", Integer.valueOf(2), s.setSeen.get("main~level"));
        eq("written again it is version 2 and loads the same", "main~77", SyncState.fromJson(s.toJson()).outbox.get(0).key);
        // Nothing changed on the device: the migrated baselines mean nothing new is queued.
        ProfileTestKit.Device d = new ProfileTestKit.Device(new ProfileTestKit.Server(), "u1", "TV");
        d.state.baselined = true;
        d.state.pushed.putAll(s.pushed);
        d.state.listSeen.addAll(s.listSeen);
        d.state.rateSeen.putAll(s.rateSeen);
        d.state.setSeen.putAll(s.setSeen);
        d.state.setSeen.put("main~titleLang", false);
        d.lib.of("main").progress.put("9", ProfileTestKit.progress("9", "Show", 1000, 6));
        d.lib.of("main").list.add(ProfileTestKit.title("77", "Dune"));
        d.lib.of("main").ratings.put("5", 2);
        d.lib.of("main").settings.put("level", 2);
        d.state.profilesSeen = "";
        d.engine.reconcile(100, "TV");
        eq("the migrated device queues nothing for what it already sent", 0, d.state.outbox.size());
    }

    // ---- two devices, several profiles ------------------------------------------------------

    private static void devices() throws Exception {
        ProfileTestKit.Server server = new ProfileTestKit.Server();
        ProfileTestKit.Device tv = new ProfileTestKit.Device(server, "uidTV", "TV");
        ProfileTestKit.Device phone = new ProfileTestKit.Device(server, "uidPhone", "Phone");
        long t = SyncRules.parseTime("2026-10-22T10:00:00Z");

        // An untouched default list says nothing: another device's list is not overwritten by it.
        tv.lib.of("main").list.add(ProfileTestKit.title("1", "Mine"));
        tv.sync(t);
        eq("the untouched default list is not uploaded", false, server.tables.get("settings").containsKey("profiles"));
        eq("main's list item went, prefixed", true, server.has("my_list", "main~1"));
        eq("not under the plain key", false, server.tables.get("my_list").containsKey("1"));

        // The TV adds a Kids profile with its own data.
        ProfileRules.Profile zoe = tv.lib.addProfile("zoe00001", "Zoe", true);
        zoe.pin = PinRules.hash("2468");
        tv.lib.profiles = ProfileRules.update(tv.lib.profiles, zoe);
        tv.lib.of("zoe00001").list.add(ProfileTestKit.title("2", "Cartoon"));
        tv.lib.of("zoe00001").progress.put("30", ProfileTestKit.progress("30", "Kids show", 5000, t + 10));
        tv.lib.of("zoe00001").ratings.put("2", SyncMap.RATE_LOVE);
        tv.lib.of("zoe00001").settings.put("subtitleLang", "en");
        tv.lib.of("main").settings.put("subtitleLang", "off");
        tv.sync(t + 1000);
        eq("the profiles row is one settings row", true, server.has("settings", "profiles"));
        eq("whose value is the array", 2, MiniJson.list(server.row("settings", "profiles").get("value")).size());
        eq("Zoe's list item", true, server.has("my_list", "zoe00001~2"));
        eq("Zoe's progress", true, server.has("watch_progress", "zoe00001~v:30"));
        eq("Zoe's rating", true, server.has("ratings", "zoe00001~2"));
        eq("Zoe's setting", true, server.has("settings", "pref:zoe00001:subtitleLang"));
        eq("main's setting", true, server.has("settings", "pref:main:subtitleLang"));
        eq("the progress row's title_id stays the title's", "30", server.row("watch_progress", "zoe00001~v:30").get("title_id"));

        // The phone joins: it gets both profiles, each with its own data.
        phone.lib.of("main").list.add(ProfileTestKit.title("3", "Phone only"));
        phone.sync(t + 2000);
        eq("the phone has Zoe", true, ProfileRules.byId(phone.lib.profiles, "zoe00001") != null && ProfileRules.byId(phone.lib.profiles, "zoe00001").kids);
        eq("with her PIN, which works there", true, PinRules.verify("2468", ProfileRules.byId(phone.lib.profiles, "zoe00001").pin));
        eq("Zoe's list is Zoe's", Arrays.asList("2"), phone.lib.of("zoe00001").listIds());
        eq("main's list is the union", true, phone.lib.of("main").listIds().containsAll(Arrays.asList("1", "3")));
        eq("main did not get Zoe's title", false, phone.lib.of("main").listIds().contains("2"));
        eq("Zoe's progress arrived in Zoe", true, phone.lib.of("zoe00001").progress.containsKey("30"));
        eq("and not in main", false, phone.lib.of("main").progress.containsKey("30"));
        eq("Zoe's rating", Integer.valueOf(SyncMap.RATE_LOVE), phone.lib.of("zoe00001").ratings.get("2"));
        eq("main has no rating of 2", null, phone.lib.of("main").ratings.get("2"));
        eq("Zoe's setting is hers", "en", phone.lib.of("zoe00001").settings.get("subtitleLang"));
        eq("main's is main's", "off", phone.lib.of("main").settings.get("subtitleLang"));
        phone.engine.reconcile(t + 3000, "Phone");
        eq("what was applied is not sent back", 0, phone.state.outbox.size());

        // The outbox is right for a profile that is not the active one.
        phone.lib.active = "main";
        phone.lib.of("zoe00001").list.add(0, ProfileTestKit.title("4", "Zoe adds on the phone"));
        phone.sync(t + 4000);
        eq("Zoe's change goes out even though main is active", true, server.has("my_list", "zoe00001~4"));
        tv.sync(t + 5000);
        eq("the TV has it in Zoe", true, tv.lib.of("zoe00001").listIds().contains("4"));
        eq("and not in main", false, tv.lib.of("main").listIds().contains("4"));

        // Rows written before profiles (by an older client) are main's.
        server.put("my_list", "55", MiniJson.obj("title_id", "55", "deleted", false, "meta",
                SyncMap.snapshot(ProfileTestKit.title("55", "Old client"), 20)), "uidOld");
        server.put("settings", "autoplayNext", MiniJson.obj("key", "autoplayNext", "value", false, "deleted", false), "uidOld");
        server.put("watch_progress", "v:66", SyncMap.progressRow(ProfileTestKit.progress("66", "Old show", 4000, t + 20), "Old"), "uidOld");
        tv.sync(t + 6000);
        eq("an old client's My List title is main's", true, tv.lib.of("main").listIds().contains("55"));
        eq("not Zoe's", false, tv.lib.of("zoe00001").listIds().contains("55"));
        eq("its setting is main's", Boolean.FALSE, tv.lib.of("main").settings.get("autoplayNext"));
        eq("Zoe's is untouched", Boolean.TRUE, tv.lib.of("zoe00001").settings.get("autoplayNext"));
        eq("its progress is main's", true, tv.lib.of("main").progress.containsKey("66"));
        eq("and Zoe has none of it", false, tv.lib.of("zoe00001").progress.containsKey("66"));

        // A rename goes through the one row; the server's row decides.
        ProfileRules.Profile me = ProfileRules.byId(tv.lib.profiles, "main").copy();
        me.name = "Sam";
        tv.lib.profiles = ProfileRules.update(tv.lib.profiles, me);
        tv.sync(t + 7000);
        phone.sync(t + 8000);
        eq("the rename reaches the phone", "Sam", ProfileRules.byId(phone.lib.profiles, "main").name);

        // Delete a profile, keeping its synced data: the rows stay on the server.
        ProfileRules.Profile temp = tv.lib.addProfile("tmp00001", "Temp", false);
        tv.lib.of("tmp00001").list.add(ProfileTestKit.title("8", "Temp title"));
        tv.sync(t + 9000);
        phone.sync(t + 10_000);
        eq("the phone has the new profile and its data", true, phone.lib.of("tmp00001").listIds().contains("8"));
        tv.lib.delete("tmp00001", false);
        tv.sync(t + 11_000);
        eq("a tombstone in the list", true, MiniJson.bool(MiniJson.map(MiniJson.list(server.row("settings", "profiles").get("value")).get(
                MiniJson.list(server.row("settings", "profiles").get("value")).size() - 1)).get("deleted")) || tombstoned(server, "tmp00001"));
        eq("its rows stay on the server", true, server.has("my_list", "tmp00001~8"));
        phone.sync(t + 12_000);
        eq("the phone drops the profile", true, ProfileRules.byId(phone.lib.profiles, "tmp00001").deleted);
        eq("and its data", false, phone.lib.data.containsKey("tmp00001"));
        eq("a later row of a deleted profile is ignored", true, applyIgnored(phone, server, t + 13_000));

        // Delete with "also delete synced data": its rows become tombstones.
        tv.lib.addProfile("wip00001", "Wipe", false);
        tv.lib.of("wip00001").list.add(ProfileTestKit.title("9", "Wipe title"));
        tv.lib.of("wip00001").progress.put("90", ProfileTestKit.progress("90", "Wipe show", 1000, t + 14_000));
        tv.lib.of("wip00001").ratings.put("9", SyncMap.RATE_LIKE);
        tv.lib.of("wip00001").settings.put("autoplayNext", false);
        tv.sync(t + 14_500);
        eq("it was on the server", true, server.has("my_list", "wip00001~9") && server.has("watch_progress", "wip00001~v:90")
                && server.has("ratings", "wip00001~9") && server.has("settings", "pref:wip00001:autoplayNext"));
        tv.lib.delete("wip00001", true);
        tv.sync(t + 15_000);
        eq("its My List row is deleted", false, server.has("my_list", "wip00001~9"));
        eq("its progress", false, server.has("watch_progress", "wip00001~v:90"));
        eq("its rating", false, server.has("ratings", "wip00001~9"));
        eq("its setting", false, server.has("settings", "pref:wip00001:autoplayNext"));
        eq("and the wipe is done, once", true, tv.lib.wipe.isEmpty());
        eq("nothing is left queued", 0, tv.state.outbox.size());
        eq("the other profiles' rows are untouched", true, server.has("my_list", "zoe00001~2") && server.has("my_list", "main~1"));

        // A device with profiles of its own links to a space that already has a list: the union, and the server's row gets ours.
        ProfileTestKit.Device tablet = new ProfileTestKit.Device(server, "uidTablet", "Tablet");
        tablet.lib.addProfile("tab00001", "Tab", false);
        tablet.lib.of("tab00001").list.add(ProfileTestKit.title("12", "Tablet title"));
        tablet.sync(t + 16_000);
        eq("the tablet got the shared profiles", true, ProfileRules.byId(tablet.lib.profiles, "zoe00001") != null);
        eq("and kept its own", true, ProfileRules.byId(tablet.lib.profiles, "tab00001") != null && !ProfileRules.byId(tablet.lib.profiles, "tab00001").deleted);
        eq("a deleted one stays deleted there", true, ProfileRules.byId(tablet.lib.profiles, "wip00001").deleted);
        tablet.sync(t + 17_000);
        boolean onServer = false;
        for (Object o : MiniJson.list(server.row("settings", "profiles").get("value"))) if ("tab00001".equals(MiniJson.map(o).get("id"))) onServer = true;
        eq("the server's list now has the tablet's profile", true, onServer);
        eq("its data went to its own rows", true, server.has("my_list", "tab00001~12"));
        tv.sync(t + 18_000);
        eq("the TV gets it", true, ProfileRules.byId(tv.lib.profiles, "tab00001") != null && tv.lib.of("tab00001").listIds().contains("12"));

        // Max 6 holds on a device that receives a longer list.
        eq("never more than 6 live", true, ProfileRules.live(ProfileRules.mergeRemote(tv.lib.profiles, sevenProfiles())).size() <= 6);
    }

    private static boolean tombstoned(ProfileTestKit.Server server, String pid) {
        for (Object o : MiniJson.list(server.row("settings", "profiles").get("value"))) {
            Map<String, Object> m = MiniJson.map(o);
            if (pid.equals(m.get("id")) && MiniJson.bool(m.get("deleted"))) return true;
        }
        return false;
    }

    /** A row of a profile the device deleted arrives: nothing is stored for it. */
    private static boolean applyIgnored(ProfileTestKit.Device d, ProfileTestKit.Server server, long now) throws Exception {
        server.put("my_list", "tmp00001~99", SyncMap.scope("my_list", MiniJson.obj("title_id", "99", "deleted", false,
                "meta", SyncMap.snapshot(ProfileTestKit.title("99", "Late"), 20)), "tmp00001"), "uidPhone2");
        d.sync(now);
        return !d.lib.data.containsKey("tmp00001");
    }

    private static List<ProfileRules.Profile> sevenProfiles() {
        List<ProfileRules.Profile> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ProfileRules.Profile p = profile("seven00" + i, "S" + i, false, null);
            p.order = 20 + i;
            out.add(p);
        }
        return out;
    }
}
