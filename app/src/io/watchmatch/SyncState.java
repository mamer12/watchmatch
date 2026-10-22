package io.watchmatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything sync keeps between runs, as one JSON file (Sync writes it atomically): the space this TV is in, the
 * cursors, the outbox and what was last seen of the library (so a change is told from what sync itself applied). The
 * session's tokens are kept apart, in private prefs. No Android here (SyncRulesCheck).
 */
final class SyncState {
    String space = "", device = "";
    final Map<String, String> cursors = new LinkedHashMap<>();
    List<SyncRules.Item> outbox = new ArrayList<>();
    boolean firstSyncDone, baselined;
    /** What of the profiles list was last queued or applied (ProfileRules.signature), "" before the first look. */
    String profilesSeen = "";
    /**
     * What each profile's library was when last queued or applied. Every key starts with the profile's id and "~"
     * ("main~12"). Progress: video id to "at" (+ "h" when hidden).
     */
    final Map<String, String> pushed = new LinkedHashMap<>();
    final List<String> listSeen = new ArrayList<>();
    final Map<String, Integer> rateSeen = new LinkedHashMap<>();
    /** The synced settings as last queued or applied, in the app's values. */
    final Map<String, Object> setSeen = new LinkedHashMap<>();
    long lastSyncAt;

    boolean linked() {
        return !space.isEmpty();
    }

    String toJson() {
        List<Object> items = new ArrayList<>();
        for (SyncRules.Item i : outbox) items.add(MiniJson.obj("t", i.table, "k", i.key, "row", i.row, "ts", i.ts));
        Map<String, Object> m = MiniJson.obj("v", 2L, "space", space, "device", device, "cursors", cursors,
                "outbox", items, "first", firstSyncDone, "baselined", baselined, "pushed", pushed, "list", listSeen,
                "rate", rateSeen, "set", setSeen, "last", lastSyncAt, "profiles", profilesSeen);
        return MiniJson.write(m);
    }

    /** What toJson() wrote; an empty (unlinked) state for anything else. */
    static SyncState fromJson(String text) {
        SyncState s = new SyncState();
        if (text == null) return s;
        try {
            Map<String, Object> m = MiniJson.map(MiniJson.parse(text));
            long version = MiniJson.lng(m.get("v"));
            if (version != 1 && version != 2) return s;
            // Version 1 (before profiles): everything it knew was main's.
            String legacy = version == 1 ? ProfileRules.MAIN + "~" : "";
            s.space = MiniJson.str(m.get("space"));
            s.device = MiniJson.str(m.get("device"));
            for (Map.Entry<String, Object> e : MiniJson.map(m.get("cursors")).entrySet()) s.cursors.put(e.getKey(), MiniJson.str(e.getValue()));
            for (Object o : MiniJson.list(m.get("outbox"))) {
                Map<String, Object> i = MiniJson.map(o);
                String t = MiniJson.str(i.get("t")), k = MiniJson.str(i.get("k"));
                Map<String, Object> row = MiniJson.map(i.get("row"));
                if (version == 1) {
                    row = SyncMap.scope(t, row, ProfileRules.MAIN);
                    k = SyncRules.keyOf(t, row) == null ? k : SyncRules.keyOf(t, row);
                }
                if (!t.isEmpty() && !k.isEmpty()) s.outbox.add(new SyncRules.Item(t, k, row, MiniJson.lng(i.get("ts"))));
            }
            s.firstSyncDone = MiniJson.bool(m.get("first"));
            s.baselined = MiniJson.bool(m.get("baselined"));
            for (Map.Entry<String, Object> e : MiniJson.map(m.get("pushed")).entrySet()) s.pushed.put(legacy + e.getKey(), MiniJson.str(e.getValue()));
            for (Object o : MiniJson.list(m.get("list"))) s.listSeen.add(legacy + MiniJson.str(o));
            for (Map.Entry<String, Object> e : MiniJson.map(m.get("rate")).entrySet()) s.rateSeen.put(legacy + e.getKey(), (int) MiniJson.lng(e.getValue()));
            for (Map.Entry<String, Object> e : MiniJson.map(m.get("set")).entrySet()) {
                Object v = e.getValue();
                s.setSeen.put(legacy + e.getKey(), v instanceof Long ? (Object) (int) (long) (Long) v : v);
            }
            s.lastSyncAt = MiniJson.lng(m.get("last"));
            s.profilesSeen = MiniJson.str(m.get("profiles"));
        } catch (RuntimeException e) {
            return new SyncState();
        }
        return s;
    }
}
