package io.watchmatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Watchme+ profiles without Android (ProfileRulesCheck runs it on a plain JVM). The model is shared with the web app
 * (watch-me-plus): a profile is {id, name, color, avatar, kids, pin, order, created}; 'main' is the first one (and where
 * everything saved before profiles belongs); at most 6; the list syncs as one settings row, key "profiles", whose
 * value is the array (a deleted profile stays as {id, deleted: true}). Each profile's rows carry its id: "<id>~<key>"
 * for progress, My List and ratings, "pref:<id>:<name>" for its settings; a key with no profile is main's.
 */
final class ProfileRules {
    private ProfileRules() {}

    static final String MAIN = "main", DEFAULT_NAME = "Me", ROW_KEY = "profiles";
    static final int MAX = 6, NAME_MAX = 20, COLORS = 8;
    static final String[] AVATARS = {"fox", "owl", "bear", "cat", "dog", "panda", "lion", "rocket", "star", "moon", "sun", "wave"};
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    static final class Profile {
        String id = "", name = "", avatar = AVATARS[0];
        int color, order;
        boolean kids, deleted;
        /** {salt, iter, hash} (PinRules) or null. */
        Map<String, Object> pin;
        long created;

        Profile copy() {
            Profile p = new Profile();
            p.id = id;
            p.name = name;
            p.avatar = avatar;
            p.color = color;
            p.order = order;
            p.kids = kids;
            p.deleted = deleted;
            p.pin = pin == null ? null : new LinkedHashMap<>(pin);
            p.created = created;
            return p;
        }

        boolean hasPin() {
            return !deleted && pin != null;
        }
    }

    // ---- creating and changing --------------------------------------------------------------

    /** The first profile: what a person who had Watchme+ before profiles starts as, all their saved data in it. */
    static Profile main(long now) {
        Profile p = new Profile();
        p.id = MAIN;
        p.name = DEFAULT_NAME;
        p.created = now;
        return p;
    }

    static boolean isDefaultOnly(List<Profile> list) {
        if (list.size() != 1) return false;
        Profile p = list.get(0);
        return p.id.equals(MAIN) && !p.deleted && p.name.equals(DEFAULT_NAME) && !p.kids && p.pin == null && p.color == 0
                && p.avatar.equals(AVATARS[0]);
    }

    /** 8 random characters [a-z0-9], not one in use (a deleted profile's id is never reused either). */
    static String newId(Random r, List<Profile> list) {
        for (;;) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(ID_CHARS.charAt(r.nextInt(ID_CHARS.length())));
            String id = b.toString();
            if (!id.equals(MAIN) && byId(list, id) == null) return id;
        }
    }

    /** Trimmed, spaces collapsed, at most 20 characters; "" when nothing is left. */
    static String cleanName(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (t.length() > NAME_MAX) t = t.substring(0, NAME_MAX).trim();
        return t;
    }

    static boolean validName(String s) {
        return !cleanName(s).isEmpty();
    }

    static boolean validAvatar(String a) {
        for (String x : AVATARS) if (x.equals(a)) return true;
        return false;
    }

    static List<Profile> live(List<Profile> list) {
        List<Profile> out = new ArrayList<>();
        for (Profile p : list) if (!p.deleted) out.add(p);
        sort(out);
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private static void sort(List<Profile> l) {
        Collections.sort(l, new Comparator<Profile>() {
            @Override public int compare(Profile a, Profile b) {
                if (a.deleted != b.deleted) return a.deleted ? 1 : -1;      // tombstones last (they carry no order)
                if (a.deleted) return a.id.compareTo(b.id);
                if (a.order != b.order) return Integer.compare(a.order, b.order);
                if (a.created != b.created) return Long.compare(a.created, b.created);
                return a.id.compareTo(b.id);
            }
        });
    }

    static Profile byId(List<Profile> list, String id) {
        for (Profile p : list) if (p.id.equals(id)) return p;
        return null;
    }

    static boolean canAdd(List<Profile> list) {
        return live(list).size() < MAX;
    }

    /** The list with `p` added (last in the order), or the same list when it can't be (full, bad name, id taken). */
    static List<Profile> add(List<Profile> list, Profile p) {
        if (!canAdd(list) || !validName(p.name) || p.id.isEmpty() || byId(list, p.id) != null) return list;
        List<Profile> out = copyAll(list);
        Profile n = p.copy();
        n.name = cleanName(n.name);
        n.color = clampColor(n.color);
        if (!validAvatar(n.avatar)) n.avatar = AVATARS[0];
        int last = -1;
        for (Profile x : list) last = Math.max(last, x.order);
        n.order = last + 1;
        out.add(n);
        return out;
    }

    /** The list with the profile of `p`'s id replaced (its order and creation time stay); a deleted one can't come back. */
    static List<Profile> update(List<Profile> list, Profile p) {
        Profile old = byId(list, p.id);
        if (old == null || old.deleted || !validName(p.name)) return list;
        List<Profile> out = new ArrayList<>();
        for (Profile x : list) {
            if (!x.id.equals(p.id)) {
                out.add(x.copy());
                continue;
            }
            Profile n = p.copy();
            n.name = cleanName(n.name);
            n.color = clampColor(n.color);
            if (!validAvatar(n.avatar)) n.avatar = old.avatar;
            n.order = old.order;
            n.created = old.created;
            n.deleted = false;
            out.add(n);
        }
        return out;
    }

    /** The profile as a tombstone; the last live one can't be deleted (the list comes back unchanged). */
    static List<Profile> delete(List<Profile> list, String id) {
        Profile old = byId(list, id);
        if (old == null || old.deleted || live(list).size() <= 1) return list;
        List<Profile> out = new ArrayList<>();
        for (Profile x : list) {
            if (!x.id.equals(id)) {
                out.add(x.copy());
                continue;
            }
            Profile t = new Profile();
            t.id = id;
            t.deleted = true;
            t.order = x.order;
            t.created = x.created;
            out.add(t);
        }
        return out;
    }

    private static int clampColor(int c) {
        return Math.max(0, Math.min(COLORS - 1, c));
    }

    private static List<Profile> copyAll(List<Profile> list) {
        List<Profile> out = new ArrayList<>();
        for (Profile p : list) out.add(p.copy());
        return out;
    }

    // ---- sync: the list as one row ----------------------------------------------------------

    static List<Object> toJson(List<Profile> list) {
        List<Profile> sorted = copyAll(list);
        sort(sorted);
        List<Object> out = new ArrayList<>();
        for (Profile p : sorted) {
            if (p.deleted) {
                out.add(MiniJson.obj("id", p.id, "deleted", true));
                continue;
            }
            out.add(MiniJson.obj("id", p.id, "name", p.name, "color", (long) p.color, "avatar", p.avatar, "kids", p.kids,
                    "pin", p.pin, "order", (long) p.order, "created", p.created));
        }
        return out;
    }

    /** The profiles in a synced value (anything unusable skipped). */
    static List<Profile> fromJson(Object value) {
        List<Profile> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Object o : MiniJson.list(value)) {
            Map<String, Object> m = MiniJson.map(o);
            String id = MiniJson.str(m.get("id"));
            if (!validId(id) || !seen.add(id)) continue;
            Profile p = new Profile();
            p.id = id;
            p.order = (int) Math.max(0, Math.min(1000, MiniJson.lng(m.get("order"))));
            p.created = MiniJson.lng(m.get("created"));
            if (MiniJson.bool(m.get("deleted"))) {
                p.deleted = true;
                out.add(p);
                continue;
            }
            p.name = cleanName(MiniJson.str(m.get("name")));
            if (p.name.isEmpty()) continue;
            p.color = clampColor((int) MiniJson.lng(m.get("color")));
            p.avatar = validAvatar(MiniJson.str(m.get("avatar"))) ? MiniJson.str(m.get("avatar")) : AVATARS[0];
            p.kids = MiniJson.bool(m.get("kids"));
            Map<String, Object> pin = MiniJson.map(m.get("pin"));
            p.pin = !pin.isEmpty() && PinRules.wellFormed(pin) ? pin : null;
            out.add(p);
        }
        return out;
    }

    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 8) return false;
        for (int i = 0; i < id.length(); i++) if (ID_CHARS.indexOf(id.charAt(i)) < 0) return false;
        return true;
    }

    /** The list as text in a fixed order, to tell whether it changed since it was last sent or received. */
    static String signature(List<Profile> list) {
        return MiniJson.write(toJson(list));
    }

    /**
     * The list after a synced one arrives: what the server has wins for each profile, a profile only this device has
     * stays (and goes out with the next push), and a tombstone on either side wins (a deleted profile never returns).
     */
    static List<Profile> mergeRemote(List<Profile> local, List<Profile> remote) {
        Map<String, Profile> out = new LinkedHashMap<>();
        for (Profile r : remote) out.put(r.id, r.copy());
        for (Profile l : local) {
            Profile r = out.get(l.id);
            if (r == null || (l.deleted && !r.deleted)) out.put(l.id, l.copy());
        }
        List<Profile> list = new ArrayList<>(out.values());
        boolean anyLive = false;
        for (Profile p : list) anyLive |= !p.deleted;
        if (!anyLive) return copyAll(local);      // never lose every profile
        sort(list);
        return list;
    }

    // ---- keys -------------------------------------------------------------------------------

    static String prefixKey(String pid, String key) {
        return pid + "~" + key;
    }

    /** {profile id, key}: "k3j9x2ab~v:12" is that profile's "v:12"; a key with no profile is main's (written before profiles). */
    static String[] splitKey(String key) {
        int i = key.indexOf('~');
        if (i > 0 && validId(key.substring(0, i))) return new String[] {key.substring(0, i), key.substring(i + 1)};
        return new String[] {MAIN, key};
    }

    static String prefKey(String pid, String name) {
        return "pref:" + pid + ":" + name;
    }

    /** {profile id, setting name} of a settings row key; main's for a plain name; null for the profiles row. */
    static String[] splitSettingKey(String key) {
        if (ROW_KEY.equals(key)) return null;
        if (key.startsWith("pref:")) {
            int i = key.indexOf(':', 5);
            if (i > 5 && validId(key.substring(5, i)) && i < key.length() - 1) return new String[] {key.substring(5, i), key.substring(i + 1)};
            return null;
        }
        return new String[] {MAIN, key};
    }

    /** The name of the saved-data file for a profile: main keeps the names used before profiles, so nothing moves. */
    static String storeName(String base, String pid) {
        return MAIN.equals(pid) ? base : base + "-" + pid;
    }

    // ---- kids -------------------------------------------------------------------------------

    private static final int LEVEL_TEENS = 2, LEVEL_KIDS = 3;

    /** The family filter in force: a Kids profile is at least "family" (2); a stricter saved one (3) stays. */
    static int effectiveLevel(boolean kids, int saved) {
        return kids ? (saved == LEVEL_KIDS ? LEVEL_KIDS : LEVEL_TEENS) : saved;
    }

    static boolean effectiveSkip(boolean kids, boolean saved) {
        return kids || saved;
    }

    static final int F_SYNC = 0, F_RESET = 1, F_PROFILES = 2, F_FILTER = 3, F_REMOTE_BUTTONS = 4, F_ACCOUNT = 5;

    /** Whether a Kids profile may see or change this; an adult may everything. */
    static boolean allowed(boolean kids, int feature) {
        return !kids;
    }

    // ---- PINs and who may do what -----------------------------------------------------------

    /** The adult (not Kids) profiles that have a PIN. */
    static List<Profile> adultsWithPin(List<Profile> list) {
        List<Profile> out = new ArrayList<>();
        for (Profile p : live(list)) if (!p.kids && p.hasPin()) out.add(p);
        return out;
    }

    static final int GATE_NONE = 0, GATE_TARGET = 1, GATE_ADULT = 2;

    /**
     * What a person must enter to move from `active` to `target`: the target's own PIN when it has one (an adult's
     * covers leaving Kids too); else, leaving a Kids profile for an adult one needs an adult's PIN, when any has one.
     */
    static int gateForSwitch(List<Profile> list, Profile active, Profile target) {
        if (active != null && active.id.equals(target.id)) return GATE_NONE;
        if (target.hasPin()) return GATE_TARGET;
        boolean leavingKids = active != null && active.kids && !target.kids;
        return leavingKids && !adultsWithPin(list).isEmpty() ? GATE_ADULT : GATE_NONE;
    }

    /** Opening profile management (or anything that undoes a Kids limit): an adult's PIN, when any adult has one. */
    static int gateForManage(List<Profile> list, boolean adultVerifiedThisSession) {
        return !adultVerifiedThisSession && !adultsWithPin(list).isEmpty() ? GATE_ADULT : GATE_NONE;
    }

    // ---- starting Watchme+ ------------------------------------------------------------------

    static final int START_PICKER = 0, START_OPEN = 1, START_PIN = 2;

    static final class Start {
        final int kind;
        /** The profile to open (START_OPEN, START_PIN). */
        final String id;

        Start(int kind, String id) {
            this.kind = kind;
            this.id = id;
        }
    }

    /**
     * What Watchme+ does when it opens: "Who's watching?" when there is more than one profile and the person wants it
     * every time; else the last profile (the first one when it is gone), asking for its PIN when it has one.
     */
    static Start startPlan(List<Profile> list, boolean askEveryStart, String lastId) {
        List<Profile> live = live(list);
        if (live.isEmpty()) return new Start(START_OPEN, MAIN);
        if (live.size() > 1 && askEveryStart) return new Start(START_PICKER, "");
        Profile target = byId(live, lastId);
        if (target == null) target = live.get(0);
        return new Start(target.hasPin() ? START_PIN : START_OPEN, target.id);
    }

    /** The profile that is active: the saved id when it is live, else the first live one. */
    static Profile resolveActive(List<Profile> list, String savedId) {
        List<Profile> live = live(list);
        Profile p = byId(live, savedId);
        return p != null ? p : live.isEmpty() ? null : live.get(0);
    }
}
