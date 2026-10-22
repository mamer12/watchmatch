package io.watchmatch;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Watchme+'s profiles on this TV (the rules are ProfileRules and PinRules): the list, which one is active, whether to ask
 * who is watching at every start, the PIN lockouts, and the profiles whose synced data is still to be deleted. One small
 * file (profiles.json, written whole or not at all) that every process of the app reads, so the home screen (another
 * process) sees the active profile too. Each profile's own data is in its own saved-data files (ProfilePrefs); main's are
 * the ones Watchme+ used before profiles, so nothing had to be moved.
 */
final class Profiles {
    private Profiles() {}

    private static final String FILE = "profiles.json";
    /** Another process's change is looked for at most this often. */
    private static final long CHECK_MS = 400;

    private static final class State {
        List<ProfileRules.Profile> list = new ArrayList<>();
        String active = ProfileRules.MAIN;
        boolean ask = true;
        final Map<String, PinRules.Lock> locks = new LinkedHashMap<>();
        final Set<String> wipe = new LinkedHashSet<>();
    }

    private static State cache;
    private static long cacheStamp, cacheLen, checkedAt;
    /** An adult profile's PIN was entered in this process: opening the management again needs nothing more. */
    private static volatile boolean adultVerified;

    // ---- reading ----------------------------------------------------------------------------

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    private static synchronized State state(Context c) {
        long up = SystemClock.elapsedRealtime();
        if (cache != null && up - checkedAt < CHECK_MS) return cache;
        File f = file(c);
        long stamp = f.lastModified(), len = f.length();
        if (cache != null && stamp == cacheStamp && len == cacheLen) {
            checkedAt = up;
            return cache;
        }
        State s = f.exists() ? parse(Durable.read(f)) : null;
        if (s == null) {
            s = fresh();
            if (!f.exists()) write(c, s);      // the first run: a profile named Me that holds everything saved so far
        }
        cache = s;
        cacheStamp = f.lastModified();
        cacheLen = f.length();
        checkedAt = up;
        return s;
    }

    private static State fresh() {
        State s = new State();
        s.list.add(ProfileRules.main(TrustedTime.now()));
        return s;
    }

    private static State parse(String text) {
        if (text == null) return null;
        try {
            Map<String, Object> m = MiniJson.map(MiniJson.parse(text));
            State s = new State();
            s.list = ProfileRules.fromJson(m.get("list"));
            if (ProfileRules.live(s.list).isEmpty()) return null;
            s.active = MiniJson.str(m.get("active"));
            s.ask = !m.containsKey("ask") || MiniJson.bool(m.get("ask"));
            for (Map.Entry<String, Object> e : MiniJson.map(m.get("locks")).entrySet()) {
                Map<String, Object> l = MiniJson.map(e.getValue());
                s.locks.put(e.getKey(), new PinRules.Lock((int) MiniJson.lng(l.get("f")), MiniJson.lng(l.get("u"))));
            }
            for (Object o : MiniJson.list(m.get("wipe"))) s.wipe.add(MiniJson.str(o));
            return s;
        } catch (RuntimeException e) {
            AppLog.w("profiles not read: " + e);
            return null;
        }
    }

    private static void write(Context c, State s) {
        Map<String, Object> locks = new LinkedHashMap<>();
        for (Map.Entry<String, PinRules.Lock> e : s.locks.entrySet()) {
            locks.put(e.getKey(), MiniJson.obj("f", (long) e.getValue().fails, "u", e.getValue().until));
        }
        try {
            Durable.write(file(c), MiniJson.write(MiniJson.obj("v", 1L, "list", ProfileRules.toJson(s.list), "active", s.active,
                    "ask", s.ask, "locks", locks, "wipe", new ArrayList<Object>(s.wipe))));
        } catch (Exception e) {
            AppLog.w("profiles not saved: " + e);
        }
    }

    /** Runs `edit` on a fresh read of the file and saves the result. */
    private static synchronized void change(Context c, java.util.function.Consumer<State> edit) {
        cache = null;
        State s = state(c);
        edit.accept(s);
        write(c, s);
        cache = s;
        cacheStamp = file(c).lastModified();
        cacheLen = file(c).length();
        checkedAt = SystemClock.elapsedRealtime();
    }

    // ---- who is who -------------------------------------------------------------------------

    /** Every profile, deleted ones as tombstones (what sync sends). */
    static List<ProfileRules.Profile> all(Context c) {
        List<ProfileRules.Profile> out = new ArrayList<>();
        for (ProfileRules.Profile p : state(c).list) out.add(p.copy());
        return out;
    }

    static List<ProfileRules.Profile> live(Context c) {
        return ProfileRules.live(state(c).list);
    }

    static ProfileRules.Profile active(Context c) {
        State s = state(c);
        return ProfileRules.resolveActive(s.list, s.active);
    }

    static String activeId(Context c) {
        ProfileRules.Profile p = active(c);
        return p == null ? ProfileRules.MAIN : p.id;
    }

    /** A live profile by id, or null. */
    static ProfileRules.Profile get(Context c, String id) {
        ProfileRules.Profile p = ProfileRules.byId(state(c).list, id);
        return p == null || p.deleted ? null : p;
    }

    static boolean isDeleted(Context c, String id) {
        ProfileRules.Profile p = ProfileRules.byId(state(c).list, id);
        return p != null && p.deleted;
    }

    static boolean kidsActive(Context c) {
        ProfileRules.Profile p = active(c);
        return p != null && p.kids;
    }

    static boolean ask(Context c) {
        return state(c).ask;
    }

    static void setAsk(Context c, boolean ask) {
        change(c, s -> s.ask = ask);
    }

    // ---- changing the list ------------------------------------------------------------------

    /** Makes `list` the profiles (after an edit here) and tells sync. */
    static void setList(Context c, List<ProfileRules.Profile> list) {
        change(c, s -> {
            s.list = list;
            if (ProfileRules.resolveActive(list, s.active) == null) s.active = ProfileRules.MAIN;
        });
        Sync.nudge(c);
    }

    static ProfileRules.Profile create(Context c, String name, int color, String avatar, boolean kids, Map<String, Object> pin) {
        List<ProfileRules.Profile> all = all(c);
        if (!ProfileRules.canAdd(all)) return null;
        ProfileRules.Profile p = new ProfileRules.Profile();
        p.id = ProfileRules.newId(new Random(), all);
        p.name = name;
        p.color = color;
        p.avatar = avatar;
        p.kids = kids;
        p.pin = pin;
        p.created = TrustedTime.now();
        List<ProfileRules.Profile> next = ProfileRules.add(all, p);
        if (next == all) return null;
        setList(c, next);
        return get(c, p.id);
    }

    static void update(Context c, ProfileRules.Profile p) {
        setList(c, ProfileRules.update(all(c), p));
    }

    /**
     * Deletes a profile and everything this TV kept for it; with `wipeSynced` its synced rows are deleted on the
     * server too (sync does it at its next turn). The last profile can't be deleted.
     */
    static boolean delete(Context c, String id, boolean wipeSynced) {
        List<ProfileRules.Profile> all = all(c);
        List<ProfileRules.Profile> next = ProfileRules.delete(all, id);
        if (next == all) return false;
        change(c, s -> {
            s.list = next;
            if (wipeSynced) s.wipe.add(id);
            if (s.active.equals(id)) s.active = ProfileRules.live(next).get(0).id;
        });
        forgetData(c, id);
        Sync.nudge(c);
        return true;
    }

    private static void forgetData(Context c, String id) {
        if (ProfileRules.MAIN.equals(id)) {
            ProfilePrefs.with(id, () -> {
                Progress.clear(c);
                CinemaPrefs.clearProfile(c);
                return null;
            });
            return;
        }
        for (String base : new String[] {"progress", "settings"}) {
            c.getApplicationContext().deleteSharedPreferences("catalog-" + ProfileRules.storeName(base, id));
        }
    }

    /** A synced list arrived: merged into this one. True when something here changed. */
    static boolean applyRemote(Context c, List<ProfileRules.Profile> remote) {
        List<ProfileRules.Profile> now = all(c), merged = ProfileRules.mergeRemote(now, remote);
        if (ProfileRules.signature(merged).equals(ProfileRules.signature(now))) return false;
        List<String> gone = new ArrayList<>();
        for (ProfileRules.Profile p : merged) {
            ProfileRules.Profile was = ProfileRules.byId(now, p.id);
            if (p.deleted && (was == null || !was.deleted)) gone.add(p.id);
        }
        change(c, s -> {
            s.list = merged;
            if (ProfileRules.resolveActive(merged, s.active) == null) s.active = ProfileRules.MAIN;
            else s.active = ProfileRules.resolveActive(merged, s.active).id;
        });
        for (String id : gone) forgetData(c, id);
        return true;
    }

    /** Profiles deleted with "also delete synced data" that sync has not yet cleared on the server. */
    static Set<String> wipePending(Context c) {
        return new LinkedHashSet<>(state(c).wipe);
    }

    static void wipeDone(Context c, Set<String> ids) {
        if (ids.isEmpty()) return;
        change(c, s -> s.wipe.removeAll(ids));
    }

    // ---- switching --------------------------------------------------------------------------

    /** Makes `id` the active profile: what is queued for sync goes first, then the person's data is the new profile's. */
    static void switchTo(Context c, String id) {
        if (get(c, id) == null || id.equals(activeId(c))) return;
        Sync.flush(c);
        change(c, s -> s.active = id);
        Library.arabicFirst = CinemaPrefs.arabicTitles(c);
        Sync.nudge(c);
    }

    // ---- PINs -------------------------------------------------------------------------------

    static PinRules.Lock lock(Context c, String key) {
        PinRules.Lock l = state(c).locks.get(key);
        return l == null ? PinRules.Lock.NONE : l;
    }

    static void setLock(Context c, String key, PinRules.Lock l) {
        change(c, s -> {
            if (l.equals(PinRules.Lock.NONE)) s.locks.remove(key);
            else s.locks.put(key, l);
        });
    }

    static boolean adultVerified() {
        return adultVerified;
    }

    static void setAdultVerified(boolean v) {
        adultVerified = v;
    }

    // ---- reset ------------------------------------------------------------------------------

    /** Reset Watchme+ data: every profile but a fresh "Me" goes, with its data (main's is cleared by the reset itself). */
    static void resetAll(Context c) {
        for (ProfileRules.Profile p : all(c)) if (!p.id.equals(ProfileRules.MAIN) && !p.deleted) forgetData(c, p.id);
        change(c, s -> {
            s.list = new ArrayList<>();
            s.list.add(ProfileRules.main(TrustedTime.now()));
            s.active = ProfileRules.MAIN;
            s.ask = true;
            s.locks.clear();
            s.wipe.clear();
        });
        adultVerified = false;
    }
}
