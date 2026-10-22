package io.watchmatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sync cycle without Android (SyncRulesCheck drives it with fakes): what changed locally becomes outbox items,
 * delta pulls bring remote rows in, and the outbox goes out in batches. Local first: nothing here waits for the network
 * except the calls on Api, and Sync runs them on its own thread. Not thread safe: one thread at a time.
 */
final class SyncEngine {
    interface Api {
        String userId();
        void upsert(String table, List<Map<String, Object>> rows) throws SyncClient.HttpException;
        List<Map<String, Object>> pull(String table, String space, String cursor, int limit) throws SyncClient.HttpException;
    }

    /** One profile's library as it is now. */
    static final class Snapshot {
        final List<SyncMap.P> progress = new ArrayList<>();
        /** Newest first. */
        final List<SyncMap.T> myList = new ArrayList<>();
        /** Title id to RATE_* (never 0). */
        final Map<String, Integer> ratings = new LinkedHashMap<>();
        /** The synced settings, in the app's values (SyncMap.SETTING_KEYS). */
        final Map<String, Object> settings = new LinkedHashMap<>();
        /** Empty on purpose: this profile was deleted with its synced data, so what was synced for it is deleted. */
        boolean wipe;
    }

    /**
     * Writes remote changes into the app's stores (without feeding them back to the outbox), into the profile each row
     * belongs to. Each returns whether it changed anything.
     */
    interface Sink {
        boolean progress(String pid, SyncMap.RP rp);
        /** title null: take the id out of My List. */
        boolean myList(String pid, String id, SyncMap.T title);
        /** tv RATE_NONE: take the rating away. */
        boolean rating(String pid, String id, int tv);
        /** The app's value now, or null when it was not applied. */
        Object setting(String pid, String key, Object remote);
        /** The profiles list from the server (merged with this one's, see ProfileRules.mergeRemote). */
        boolean profiles(List<ProfileRules.Profile> remote);
    }

    interface Local {
        /** Every live profile's library (by profile id), and an empty one for each whose synced rows are to be deleted. */
        Map<String, Snapshot> snapshots();
        /** The profiles list, deleted ones as tombstones. */
        List<ProfileRules.Profile> profiles();
        Sink sink();
        /** The video playing here now as "<profile id>~<video id>", or null. */
        String playing();
        boolean arabicFirst();
        /** The profile was deleted: no row of it is applied. */
        boolean deleted(String pid);
        /** The deletion of these profiles' synced rows was queued. */
        void wiped(Set<String> pids);
    }

    interface Persist {
        void save(SyncState s);
    }

    private final SyncState st;
    private final Api api;
    private final Local local;
    private final Persist persist;
    /** Where warnings go (Sync points it at AppLog). */
    static java.util.function.Consumer<String> log = s -> { };
    /** Rows that could never be accepted and were dropped. */
    int dropped;
    /** Items the first sync brought in, and the device of the newest. */
    int firstApplied;
    String firstFrom = "";
    /** Rows applied since the last takeApplied(): the screens redraw when it is not 0. */
    private int applied;

    SyncEngine(SyncState st, Api api, Local local, Persist persist) {
        this.st = st;
        this.api = api;
        this.local = local;
        this.persist = persist;
    }

    SyncState state() {
        return st;
    }

    int takeApplied() {
        int n = applied;
        applied = 0;
        return n;
    }

    // ---- local changes to the outbox --------------------------------------------------------

    static String sig(SyncMap.P p) {
        return p.at + (p.hidden ? "h" : "");
    }

    /** The key of a baseline entry: the profile's id and "~" first. */
    private static String pk(String pid, String key) {
        return ProfileRules.prefixKey(pid, key);
    }

    private static boolean mine(String key, String pid) {
        return key.startsWith(pid + "~");
    }

    private static String pidOf(String baselineKey) {
        int i = baselineKey.indexOf('~');
        return i < 0 ? ProfileRules.MAIN : baselineKey.substring(0, i);
    }

    /**
     * Compares every profile's library (and the profiles list) with what sync last saw and queues the differences, each
     * stamped with the time of the change (`now`). The first time (after linking) it queues everything on the TV:
     * progress with its own time, My List, ratings and non-default settings with time 0, so whatever is already on the
     * server wins.
     */
    int reconcile(long now, String device) {
        Map<String, Snapshot> cur = local.snapshots();
        boolean initial = !st.baselined;
        List<SyncRules.Item> items = new ArrayList<>();

        // The profiles list: one settings row. A list that is still the untouched default says nothing.
        List<ProfileRules.Profile> plist = local.profiles();
        String sig = ProfileRules.signature(plist);
        if (!sig.equals(st.profilesSeen)) {
            if (!st.profilesSeen.isEmpty() || !ProfileRules.isDefaultOnly(plist)) {
                items.add(new SyncRules.Item("settings", ProfileRules.ROW_KEY, SyncMap.profilesRow(plist), initial ? 0 : now));
            }
            st.profilesSeen = sig;
        }

        Set<String> pids = new LinkedHashSet<>(cur.keySet());
        for (String k : st.pushed.keySet()) pids.add(pidOf(k));
        for (String k : st.listSeen) pids.add(pidOf(k));
        for (String k : st.rateSeen.keySet()) pids.add(pidOf(k));
        for (String k : st.setSeen.keySet()) pids.add(pidOf(k));
        Set<String> wiped = new LinkedHashSet<>();
        for (String pid : pids) {
            Snapshot s = cur.get(pid);
            if (s == null) {
                forget(pid);      // deleted here, its synced data kept: nothing is sent
                continue;
            }
            if (s.wipe) wiped.add(pid);
            reconcileProfile(pid, s, initial, now, device, items);
        }

        st.baselined = true;
        if (!items.isEmpty()) st.outbox = SyncRules.coalesce(st.outbox, items);
        persist.save(st);
        if (!wiped.isEmpty()) local.wiped(wiped);
        return items.size();
    }

    private void forget(String pid) {
        st.pushed.keySet().removeIf(k -> mine(k, pid));
        st.listSeen.removeIf(k -> mine(k, pid));
        st.rateSeen.keySet().removeIf(k -> mine(k, pid));
        st.setSeen.keySet().removeIf(k -> mine(k, pid));
    }

    /** Nothing of this profile was ever seen here (a profile just made): it says everything it has, like the first link. */
    private boolean unseen(String pid) {
        for (String k : st.pushed.keySet()) if (mine(k, pid)) return false;
        for (String k : st.listSeen) if (mine(k, pid)) return false;
        for (String k : st.rateSeen.keySet()) if (mine(k, pid)) return false;
        for (String k : st.setSeen.keySet()) if (mine(k, pid)) return false;
        return true;
    }

    private void reconcileProfile(String pid, Snapshot cur, boolean linking, long now, String device, List<SyncRules.Item> items) {
        boolean initial = linking || unseen(pid);
        Set<String> ids = new HashSet<>();
        for (SyncMap.P p : cur.progress) {
            String id = p.title.id, key = pk(pid, id);
            ids.add(key);
            String sig = sig(p);
            if (sig.equals(st.pushed.get(key))) continue;
            st.pushed.put(key, sig);
            if (p.hidden && !p.title.rootId.isEmpty()) continue;      // a series taken out of Continue watching stays here
            Map<String, Object> row = SyncMap.scope("watch_progress", SyncMap.progressRow(p, device), pid);
            items.add(new SyncRules.Item("watch_progress", pk(pid, SyncMap.progressKey(id)), row, p.at));
        }
        for (String key : new ArrayList<>(st.pushed.keySet())) {
            if (!mine(key, pid) || ids.contains(key)) continue;
            st.pushed.remove(key);
            if (cur.wipe) {
                String id = key.substring(pid.length() + 1);
                Map<String, Object> row = SyncMap.scope("watch_progress", SyncMap.progressTombstone(id, id), pid);
                items.add(new SyncRules.Item("watch_progress", pk(pid, SyncMap.progressKey(id)), row, now));
            }
        }

        long at = linking ? 0 : now;
        Set<String> list = new HashSet<>();
        for (SyncMap.T t : cur.myList) {
            String key = pk(pid, t.id);
            list.add(key);
            if (!st.listSeen.contains(key)) {
                items.add(new SyncRules.Item("my_list", key, SyncMap.scope("my_list", SyncMap.myListRow(t, device), pid), at));
            }
        }
        for (String key : new ArrayList<>(st.listSeen)) {
            if (!mine(key, pid) || list.contains(key)) continue;
            String id = key.substring(pid.length() + 1);
            items.add(new SyncRules.Item("my_list", key, SyncMap.scope("my_list", SyncMap.myListTombstone(id), pid), now));
        }
        st.listSeen.removeIf(k -> mine(k, pid));
        st.listSeen.addAll(list);

        Map<String, Integer> rated = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : cur.ratings.entrySet()) {
            String key = pk(pid, e.getKey());
            rated.put(key, e.getValue());
            if (!e.getValue().equals(st.rateSeen.get(key))) {
                items.add(new SyncRules.Item("ratings", key, SyncMap.scope("ratings", SyncMap.ratingRow(e.getKey(), e.getValue()), pid), at));
            }
        }
        for (String key : st.rateSeen.keySet()) {
            if (!mine(key, pid) || rated.containsKey(key)) continue;
            String id = key.substring(pid.length() + 1);
            items.add(new SyncRules.Item("ratings", key, SyncMap.scope("ratings", SyncMap.ratingTombstone(id), pid), now));
        }
        st.rateSeen.keySet().removeIf(k -> mine(k, pid));
        st.rateSeen.putAll(rated);

        if (cur.wipe) {
            for (String key : new ArrayList<>(st.setSeen.keySet())) {
                if (!mine(key, pid)) continue;
                String name = key.substring(pid.length() + 1);
                items.add(new SyncRules.Item("settings", ProfileRules.prefKey(pid, name),
                        SyncMap.settingTombstone(ProfileRules.prefKey(pid, name)), now));
                st.setSeen.remove(key);
            }
            return;
        }
        for (String name : SyncMap.SETTING_KEYS) {
            Object v = cur.settings.get(name);
            if (v == null) continue;
            String key = pk(pid, name);
            boolean known = st.setSeen.containsKey(key);
            if (known ? !v.equals(st.setSeen.get(key)) : initial && !v.equals(SyncMap.settingDefault(name))) {
                items.add(new SyncRules.Item("settings", ProfileRules.prefKey(pid, name),
                        SyncMap.scope("settings", SyncMap.settingRow(name, v), pid), at));
            }
            st.setSeen.put(key, v);
        }
    }

    // ---- pull -------------------------------------------------------------------------------

    /** Every table's rows since its cursor, applied oldest first. */
    void pullAll() throws SyncClient.HttpException {
        String space = st.space;
        if (space.isEmpty()) return;
        for (String table : SyncRules.TABLES) {
            for (int page = 0; page < 100; page++) {
                String cursor = st.cursors.get(table);
                List<Map<String, Object>> rows = api.pull(table, space, cursor, SyncRules.PAGE_SIZE);
                if (!space.equals(st.space)) return;      // left or switched meanwhile
                if (!rows.isEmpty()) applyRows(table, rows);
                String last = rows.isEmpty() ? "" : MiniJson.str(rows.get(rows.size() - 1).get("updated_at"));
                if (!last.isEmpty() && !last.equals(cursor)) {
                    st.cursors.put(table, last);
                    persist.save(st);
                }
                if (rows.size() < SyncRules.PAGE_SIZE || last.isEmpty() || last.equals(cursor)) break;
            }
        }
    }

    /** Applies rows (oldest first) under the merge rules, each into the profile it belongs to; returns how many changed something. */
    int applyRows(String table, List<Map<String, Object>> rows) {
        Map<String, Long> pending = new HashMap<>();
        for (SyncRules.Item i : st.outbox) pending.put(i.id(), i.ts);
        Sink sink = local.sink();
        boolean arabic = local.arabicFirst();
        String self = api.userId(), playing = local.playing();
        int changed = 0;
        String from = "";
        Set<String> superseded = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String key = SyncRules.keyOf(table, row);
            if (key == null) continue;
            boolean profilesRow = "settings".equals(table) && ProfileRules.ROW_KEY.equals(key);
            String[] part = "settings".equals(table) ? ProfileRules.splitSettingKey(key) : ProfileRules.splitKey(key);
            if (part == null && !profilesRow) continue;
            String pid = profilesRow ? "" : part[0], raw = profilesRow ? key : part[1];
            // The key as this device queues it ("<id>~<key>"; a key from before profiles is main's).
            String full = profilesRow ? key : "settings".equals(table) ? ProfileRules.prefKey(pid, raw) : ProfileRules.prefixKey(pid, raw);
            if (!profilesRow && local.deleted(pid)) continue;
            Long pend = pending.get(table + "|" + full);
            boolean playingNow = "watch_progress".equals(table) && playing != null
                    && playing.equals(ProfileRules.prefixKey(pid, SyncMap.videoId(raw)));
            if (!SyncRules.shouldApply(row, self, pend, playingNow)) continue;
            if (pend != null) superseded.add(table + "|" + full);
            Map<String, Object> plain = SyncMap.unscope(table, row, raw);
            String dev = MiniJson.str(MiniJson.map(row.get("meta")).get("dev"));
            boolean did = false;
            boolean deleted = MiniJson.bool(row.get("deleted"));
            try {
                switch (table) {
                    case "watch_progress": did = applyProgress(sink, pid, plain, arabic); break;
                    case "my_list": did = applyList(sink, pid, plain, raw, deleted, arabic); break;
                    case "ratings": did = applyRating(sink, pid, plain, raw, deleted); break;
                    default: did = profilesRow ? applyProfiles(sink, row, deleted) : applySetting(sink, pid, plain, raw, deleted);
                }
            } catch (RuntimeException e) {
                log.accept("sync row skipped: " + e);
            }
            if (did) {
                changed++;
                if (!dev.isEmpty()) from = dev;
            }
        }
        if (!superseded.isEmpty()) {
            List<SyncRules.Item> keep = new ArrayList<>();
            for (SyncRules.Item i : st.outbox) if (!superseded.contains(i.id())) keep.add(i);
            st.outbox = keep;
        }
        if (!st.firstSyncDone) {
            firstApplied += changed;
            if (!from.isEmpty()) firstFrom = from;
        }
        applied += changed;
        if (changed > 0 || !superseded.isEmpty()) persist.save(st);
        return changed;
    }

    private boolean applyProfiles(Sink sink, Map<String, Object> row, boolean deleted) {
        if (deleted) return false;
        List<ProfileRules.Profile> remote = ProfileRules.fromJson(row.get("value"));
        if (remote.isEmpty()) return false;
        boolean did = sink.profiles(remote);
        // What the server has is what was seen: a merged list that differs from it goes out at the next look.
        st.profilesSeen = ProfileRules.signature(remote);
        return did;
    }

    private boolean applyProgress(Sink sink, String pid, Map<String, Object> row, boolean arabic) {
        SyncMap.RP rp = SyncMap.progressFromRow(row, arabic);
        if (rp.id.isEmpty()) return false;
        if (rp.deleted) {
            st.pushed.remove(pk(pid, rp.id));
            return sink.progress(pid, rp);
        }
        boolean did = sink.progress(pid, rp);
        if (did && rp.p != null) st.pushed.put(pk(pid, rp.id), sig(rp.p));
        if (rp.next != null) {
            SyncMap.RP n = new SyncMap.RP();
            n.id = rp.next.title.id;
            n.p = rp.next;
            if (sink.progress(pid, n)) {
                st.pushed.put(pk(pid, n.id), sig(n.p));
                did = true;
            }
        }
        return did;
    }

    private boolean applyList(Sink sink, String pid, Map<String, Object> row, String id, boolean deleted, boolean arabic) {
        String key = pk(pid, id);
        if (deleted) {
            st.listSeen.remove(key);
            return sink.myList(pid, id, null);
        }
        SyncMap.T t = SyncMap.myListFromRow(row, arabic);
        if (t.title.isEmpty()) return false;
        boolean did = sink.myList(pid, id, t);
        if (!st.listSeen.contains(key)) st.listSeen.add(key);
        return did;
    }

    private boolean applyRating(Sink sink, String pid, Map<String, Object> row, String id, boolean deleted) {
        String key = pk(pid, id);
        if (deleted) {
            st.rateSeen.remove(key);
            return sink.rating(pid, id, SyncMap.RATE_NONE);
        }
        int tv = SyncMap.ratingFromScore(MiniJson.lng(row.get("rating")));
        boolean did = sink.rating(pid, id, tv);
        st.rateSeen.put(key, tv);
        return did;
    }

    private boolean applySetting(Sink sink, String pid, Map<String, Object> row, String name, boolean deleted) {
        if (deleted || !SyncMap.isSettingKey(name)) return false;
        Object now = sink.setting(pid, name, row.get("value"));
        if (now == null) return false;
        st.setSeen.put(pk(pid, name), now);
        return true;
    }

    // ---- push -------------------------------------------------------------------------------

    /** Sends the outbox, a table at a time; a row the server can never accept is dropped so it does not block the rest. */
    void pushAll() throws SyncClient.HttpException {
        for (int guard = 0; guard < 1000 && !st.outbox.isEmpty(); guard++) {
            pushBatch(SyncRules.nextBatch(st.outbox));
        }
    }

    private void pushBatch(List<SyncRules.Item> batch) throws SyncClient.HttpException {
        if (batch.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SyncRules.Item i : batch) rows.add(SyncRules.toRow(st.space, i));
        try {
            api.upsert(batch.get(0).table, rows);
            st.outbox = SyncRules.removeSent(st.outbox, batch);
            persist.save(st);
        } catch (SyncClient.HttpException e) {
            boolean poison = e.status == 400 || e.status == 409 || e.status == 422;
            if (!poison) throw e;
            if (batch.size() == 1) {
                dropped++;
                st.outbox = SyncRules.removeSent(st.outbox, batch);
                persist.save(st);
                return;
            }
            for (SyncRules.Item one : batch) pushBatch(java.util.Collections.singletonList(one));
        }
    }

    /** The first sync is over: the number of items it brought in and where from, once. */
    String takeFirstSyncMessage() {
        if (st.firstSyncDone) return null;
        st.firstSyncDone = true;
        persist.save(st);
        int n = firstApplied;
        String from = firstFrom;
        firstApplied = 0;
        firstFrom = "";
        return n > 0 ? n + "|" + from : null;
    }
}
