package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What ProfileRulesCheck and SyncLiveCheck share: several profiles' libraries as the sync engine's Local and Sink (no
 * Android), and a stand-in for the backend that keeps rows per table and key with times that only go up.
 */
final class ProfileTestKit {
    private ProfileTestKit() {}

    static SyncMap.T title(String id, String name) {
        SyncMap.T t = new SyncMap.T();
        t.id = id;
        t.title = name;
        t.overview = "A story about " + name;
        t.year = "2021";
        t.stars = "7.4";
        t.genres = "Drama";
        t.poster = "https://img.example/" + id + ".jpg";
        t.minutes = 45;
        return t;
    }

    static SyncMap.P progress(String id, String name, long position, long at) {
        SyncMap.P p = new SyncMap.P();
        p.title = title(id, name);
        p.position = position;
        p.duration = 2_700_000;
        p.at = at;
        return p;
    }

    /** One profile's library. */
    static final class Data {
        final Map<String, SyncMap.P> progress = new LinkedHashMap<>();
        final List<SyncMap.T> list = new ArrayList<>();
        final Map<String, Integer> ratings = new LinkedHashMap<>();
        final Map<String, Object> settings = new LinkedHashMap<>();

        Data() {
            for (String k : SyncMap.SETTING_KEYS) settings.put(k, SyncMap.settingDefault(k));
        }

        List<String> listIds() {
            List<String> out = new ArrayList<>();
            for (SyncMap.T t : list) out.add(t.id);
            return out;
        }
    }

    /** A device: its profiles, each one's library, and which profile is active (sync does not care). */
    static final class Lib implements SyncEngine.Local, SyncEngine.Sink {
        final Map<String, Data> data = new LinkedHashMap<>();
        List<ProfileRules.Profile> profiles = new ArrayList<>(Arrays.asList(ProfileRules.main(1)));
        final Set<String> wipe = new LinkedHashSet<>();
        String active = ProfileRules.MAIN, playing;
        boolean arabic;

        Lib() {
            data.put(ProfileRules.MAIN, new Data());
        }

        Data of(String pid) {
            Data d = data.get(pid);
            if (d == null) {
                d = new Data();
                data.put(pid, d);
            }
            return d;
        }

        ProfileRules.Profile addProfile(String id, String name, boolean kids) {
            ProfileRules.Profile p = new ProfileRules.Profile();
            p.id = id;
            p.name = name;
            p.kids = kids;
            p.created = 100 + profiles.size();
            profiles = ProfileRules.add(profiles, p);
            of(id);
            return ProfileRules.byId(profiles, id);
        }

        void delete(String pid, boolean wipeSynced) {
            profiles = ProfileRules.delete(profiles, pid);
            data.remove(pid);
            if (wipeSynced) wipe.add(pid);
        }

        @Override public Map<String, SyncEngine.Snapshot> snapshots() {
            Map<String, SyncEngine.Snapshot> out = new LinkedHashMap<>();
            for (ProfileRules.Profile p : ProfileRules.live(profiles)) {
                Data d = of(p.id);
                SyncEngine.Snapshot s = new SyncEngine.Snapshot();
                s.progress.addAll(d.progress.values());
                s.myList.addAll(d.list);
                s.ratings.putAll(d.ratings);
                s.settings.putAll(d.settings);
                out.put(p.id, s);
            }
            for (String pid : wipe) {
                SyncEngine.Snapshot s = new SyncEngine.Snapshot();
                s.wipe = true;
                out.put(pid, s);
            }
            return out;
        }

        @Override public List<ProfileRules.Profile> profiles() {
            List<ProfileRules.Profile> out = new ArrayList<>();
            for (ProfileRules.Profile p : profiles) out.add(p.copy());
            return out;
        }

        @Override public SyncEngine.Sink sink() {
            return this;
        }

        @Override public String playing() {
            return playing == null ? null : active + "~" + playing;
        }

        @Override public boolean arabicFirst() {
            return arabic;
        }

        @Override public boolean deleted(String pid) {
            ProfileRules.Profile p = ProfileRules.byId(profiles, pid);
            return p != null && p.deleted;
        }

        @Override public void wiped(Set<String> pids) {
            wipe.removeAll(pids);
        }

        @Override public boolean progress(String pid, SyncMap.RP rp) {
            Data d = of(pid);
            if (rp.deleted) return d.progress.remove(rp.id) != null;
            SyncMap.P p = rp.p;
            if (p == null) return false;
            SyncMap.P old = d.progress.get(rp.id);
            if (p.upNext && old != null) return false;
            if (old != null && old.position == p.position && old.at == p.at && !old.hidden) return false;
            d.progress.put(rp.id, p);
            return true;
        }

        @Override public boolean myList(String pid, String id, SyncMap.T t) {
            Data d = of(pid);
            for (int i = 0; i < d.list.size(); i++) {
                if (d.list.get(i).id.equals(id)) {
                    if (t != null) return false;
                    d.list.remove(i);
                    return true;
                }
            }
            if (t == null) return false;
            d.list.add(0, t);
            return true;
        }

        @Override public boolean rating(String pid, String id, int tv) {
            Data d = of(pid);
            Integer old = d.ratings.get(id);
            if (tv == 0) return d.ratings.remove(id) != null;
            if (old != null && old == tv) return false;
            d.ratings.put(id, tv);
            return true;
        }

        @Override public Object setting(String pid, String key, Object remote) {
            Data d = of(pid);
            Object now = SyncMap.fromRemote(key, remote, d.settings.get(key));
            if (now != null) d.settings.put(key, now);
            return now;
        }

        @Override public boolean profiles(List<ProfileRules.Profile> remote) {
            List<ProfileRules.Profile> merged = ProfileRules.mergeRemote(profiles, remote);
            if (ProfileRules.signature(merged).equals(ProfileRules.signature(profiles))) return false;
            for (ProfileRules.Profile p : merged) {
                ProfileRules.Profile was = ProfileRules.byId(profiles, p.id);
                if (p.deleted && (was == null || !was.deleted)) data.remove(p.id);
            }
            profiles = merged;
            return true;
        }
    }

    /** A stand-in for the backend: rows per table and key, server times that only go up, client_ts respected. */
    static final class Server {
        final Map<String, Map<String, Map<String, Object>>> tables = new LinkedHashMap<>();
        int clock;

        Server() {
            for (String t : SyncRules.TABLES) tables.put(t, new LinkedHashMap<String, Map<String, Object>>());
        }

        String stamp() {
            clock++;
            return String.format("2026-10-22T10:%02d:%02d.000000+00:00", clock / 60, clock % 60);
        }

        Map<String, Object> row(String table, String key) {
            return tables.get(table).get(key);
        }

        boolean has(String table, String key) {
            Map<String, Object> r = row(table, key);
            return r != null && !MiniJson.bool(r.get("deleted"));
        }

        /** A row written the way another client does, outside any engine. */
        void put(String table, String key, Map<String, Object> row, String uid) {
            Map<String, Object> r = new LinkedHashMap<>(row);
            r.put("updated_at", stamp());
            r.put("updated_by", uid);
            tables.get(table).put(key, r);
        }
    }

    static final class Api implements SyncEngine.Api {
        final Server server;
        final String uid;

        Api(Server s, String uid) {
            this.server = s;
            this.uid = uid;
        }

        @Override public String userId() {
            return uid;
        }

        @Override public void upsert(String table, List<Map<String, Object>> rows) {
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

        @Override public List<Map<String, Object>> pull(String table, String space, String cursor, int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : server.tables.get(table).values()) {
                if (cursor == null || MiniJson.str(r.get("updated_at")).compareTo(cursor) >= 0) out.add(new LinkedHashMap<>(r));
            }
            Collections.sort(out, (a, b) -> MiniJson.str(a.get("updated_at")).compareTo(MiniJson.str(b.get("updated_at"))));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }
    }

    static final class Device {
        final Lib lib = new Lib();
        final SyncState state = new SyncState();
        final SyncEngine engine;

        Device(Server server, String uid, String name) {
            this(server, uid, name, false);
        }

        /** `account`: the user id is shared by every device of the account, so the engine cannot know its own rows by it (SyncClient.userId). */
        Device(Server server, String uid, String name, boolean account) {
            state.space = "space-1";
            state.device = name;
            final Api api = new Api(server, uid);
            SyncEngine.Api seen = !account ? api : new SyncEngine.Api() {
                @Override public String userId() {
                    return null;
                }

                @Override public void upsert(String table, List<Map<String, Object>> rows) {
                    api.upsert(table, rows);
                }

                @Override public List<Map<String, Object>> pull(String table, String space, String cursor, int limit) {
                    return api.pull(table, space, cursor, limit);
                }
            };
            engine = new SyncEngine(state, seen, lib, s -> { });
        }

        void sync(long now) throws SyncClient.HttpException {
            engine.reconcile(now, state.device);
            engine.pullAll();
            engine.pushAll();
        }
    }
}
