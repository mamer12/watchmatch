package io.watchmatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * This app's library (progress, My List, ratings, a few settings) as rows of the sync tables and back, with no Android
 * (SyncRulesCheck runs it on a plain JVM). The conventions are the web app's (watch-me-plus, src/sync/mapping.ts), so
 * the TV and the web read each other's rows:
 *  - watch_progress: item_key "v:<video id>", title_id = the series' id (else the video's), finished = watched to the
 *    end (its position then 0), and meta {sid, s, e, snap, ssnap, nsnap, ptr {id, up}, dev}: snapshots of the video,
 *    its series and, when the next episode is "up next", of that one; ptr is the series' pointer (the episode to continue
 *    with and whether it is up next).
 *  - my_list: title_id and meta = the title's snapshot + dev. ratings: title_id and rating 1 (dislike), 8, 10.
 *  - settings: key and value, for SETTING_KEYS only.
 * With profiles (ProfileRules) every key above is the profile's: "<id>~<key>" for progress, My List and ratings, and
 * "pref:<id>:<name>" for a setting (scope / unscope); the list itself is the settings row "profiles". A key with no
 * profile is main's.
 */
final class SyncMap {
    private SyncMap() {}

    /** A title as the app keeps it (Library.Title without Android). */
    static final class T {
        String id = "", title = "", other = "", overview = "", year = "", stars = "", genres = "";
        String poster = "", cover = "", imdb = "", rootId = "";
        boolean series;
        int season, episode, minutes;
    }

    /** One video's progress as Progress keeps it; position, duration in ms. */
    static final class P {
        T title = new T();
        String rootTitle = "", rootPoster = "";
        long position, duration, at;
        /** The episode after one that ended: not started, first in Continue watching. */
        boolean upNext;
        /** Taken out of Continue watching by hand. */
        boolean hidden;
        /** The device the position came from when it was synced from another one, else "". */
        String from = "";
    }

    /** A watch_progress row read back. */
    static final class RP {
        String id = "";
        boolean deleted;
        /** Null when the row had no snapshot of the video (it can only update an entry already here). */
        P p;
        /** The next episode, up next (from the series pointer), or null. */
        P next;
    }

    static final float FINISHED = 0.95f;
    private static final int MAX_META = 7000, CONTENT_CHARS = 120;

    static final String[] SETTING_KEYS = {"level", "titleLang", "familySkips", "autoplayNext", "subtitleLang",
        "subtitleSize", "subtitleColor", "subtitleBackground"};

    // ---- profiles: whose row it is ----------------------------------------------------------

    /**
     * The row as stored for profile `pid`: watch_progress's item_key and my_list's and ratings' title_id start with
     * "<pid>~", and a settings key becomes "pref:<pid>:<key>". (A progress row's title_id is the series' id and stays.)
     */
    static Map<String, Object> scope(String table, Map<String, Object> row, String pid) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        switch (table) {
            case "watch_progress": out.put("item_key", ProfileRules.prefixKey(pid, MiniJson.str(row.get("item_key")))); break;
            case "settings": out.put("key", ProfileRules.prefKey(pid, MiniJson.str(row.get("key")))); break;
            default: out.put("title_id", ProfileRules.prefixKey(pid, MiniJson.str(row.get("title_id"))));
        }
        return out;
    }

    /** The row with its own key back, without the profile (what the readers below understand). */
    static Map<String, Object> unscope(String table, Map<String, Object> row, String rawKey) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("watch_progress".equals(table) ? "item_key" : "settings".equals(table) ? "key" : "title_id", rawKey);
        return out;
    }

    static Map<String, Object> settingTombstone(String key) {
        return MiniJson.obj("key", key, "value", 0L, "deleted", true);
    }

    /** The profiles list as its settings row. */
    static Map<String, Object> profilesRow(java.util.List<ProfileRules.Profile> list) {
        return MiniJson.obj("key", ProfileRules.ROW_KEY, "value", ProfileRules.toJson(list), "deleted", false);
    }

    // ---- progress ---------------------------------------------------------------------------

    static boolean finished(P p) {
        return !p.upNext && p.duration > 0 && p.position / (float) p.duration >= FINISHED;
    }

    static String progressKey(String videoId) {
        return "v:" + videoId;
    }

    static String videoId(String itemKey) {
        return itemKey.startsWith("v:") ? itemKey.substring(2) : itemKey;
    }

    static Map<String, Object> progressRow(P p, String device) {
        T t = p.title;
        String sid = t.rootId.isEmpty() ? null : t.rootId;
        boolean done = finished(p) || (p.hidden && sid == null);
        Map<String, Object> ptr = null;
        if (p.upNext) ptr = MiniJson.obj("id", t.id, "up", true);
        else if (sid != null && !done) ptr = MiniJson.obj("id", t.id, "up", false);
        Map<String, Object> meta = MiniJson.obj("sid", sid, "s", t.season > 0 ? (Object) (long) t.season : null,
                "e", t.episode > 0 ? (Object) (long) t.episode : null, "snap", snapshot(t, CONTENT_CHARS),
                "ssnap", sid == null ? null : seriesSnapshot(sid, p.rootTitle, p.rootPoster), "ptr", ptr, "dev", device);
        return MiniJson.obj("item_key", progressKey(t.id), "title_id", sid == null ? t.id : sid,
                "position_ms", done || p.upNext ? 0L : p.position, "duration_ms", p.duration, "finished", done,
                "deleted", false, "meta", fitMeta(meta));
    }

    static Map<String, Object> progressTombstone(String videoId, String titleId) {
        return MiniJson.obj("item_key", progressKey(videoId), "title_id", titleId, "position_ms", 0L, "duration_ms", 0L,
                "finished", false, "deleted", true, "meta", new LinkedHashMap<String, Object>());
    }

    static RP progressFromRow(Map<String, Object> row, boolean arabicFirst) {
        RP out = new RP();
        String key = MiniJson.str(row.get("item_key"));
        out.id = videoId(key);
        out.deleted = MiniJson.bool(row.get("deleted"));
        if (out.deleted || out.id.isEmpty()) return out;
        Map<String, Object> meta = MiniJson.map(row.get("meta"));
        String sid = MiniJson.str(meta.get("sid"));
        String titleId = MiniJson.str(row.get("title_id"));
        if (sid.isEmpty() && !titleId.isEmpty() && !titleId.equals(out.id)) sid = titleId;
        long at = SyncRules.serverMs(row);
        String dev = MiniJson.str(meta.get("dev"));
        Map<String, Object> ptr = MiniJson.map(meta.get("ptr"));
        boolean ptrUp = MiniJson.bool(ptr.get("up"));
        String ptrId = MiniJson.str(ptr.get("id"));

        Map<String, Object> ssnap = MiniJson.map(meta.get("ssnap"));
        String rootTitle = ssnap.isEmpty() ? "" : fromSnapshot(ssnap, arabicFirst).title;
        String rootPoster = ssnap.isEmpty() ? "" : fromSnapshot(ssnap, arabicFirst).poster;

        Map<String, Object> snap = MiniJson.map(meta.get("snap"));
        if (!snap.isEmpty()) {
            P p = new P();
            p.title = fromSnapshot(snap, arabicFirst);
            p.title.id = out.id;
            p.title.rootId = sid;
            if (meta.get("s") instanceof Number) p.title.season = (int) MiniJson.lng(meta.get("s"));
            if (meta.get("e") instanceof Number) p.title.episode = (int) MiniJson.lng(meta.get("e"));
            p.rootTitle = rootTitle;
            p.rootPoster = rootPoster;
            p.position = Math.max(0, MiniJson.lng(row.get("position_ms")));
            p.duration = Math.max(0, MiniJson.lng(row.get("duration_ms")));
            if (MiniJson.bool(row.get("finished"))) {
                if (p.duration <= 0) p.duration = 1000;
                p.position = p.duration;
            }
            p.at = at;
            p.from = dev;
            if (ptrUp && ptrId.equals(out.id)) {      // this TV's own "up next" row
                p.upNext = true;
                p.position = 0;
                p.duration = 0;
            }
            out.p = p;
        }
        Map<String, Object> nsnap = MiniJson.map(meta.get("nsnap"));
        if (ptrUp && !ptrId.isEmpty() && !ptrId.equals(out.id) && !nsnap.isEmpty()) {
            P n = new P();
            n.title = fromSnapshot(nsnap, arabicFirst);
            n.title.id = ptrId;
            n.title.rootId = sid;
            n.rootTitle = rootTitle;
            n.rootPoster = rootPoster;
            n.upNext = true;
            n.at = at + 1;     // after the episode that just ended, so it comes first in Continue watching
            n.from = dev;
            out.next = n;
        }
        return out;
    }

    // ---- My List and ratings ----------------------------------------------------------------

    static Map<String, Object> myListRow(T t, String device) {
        Map<String, Object> meta = snapshot(t, CONTENT_CHARS);
        meta.put("dev", device);
        return MiniJson.obj("title_id", t.id, "deleted", false, "meta", fitMeta(meta));
    }

    static Map<String, Object> myListTombstone(String id) {
        return MiniJson.obj("title_id", id, "deleted", true, "meta", new LinkedHashMap<String, Object>());
    }

    /** The title in a My List row; its title is "" when the row carried no snapshot. */
    static T myListFromRow(Map<String, Object> row, boolean arabicFirst) {
        Map<String, Object> meta = MiniJson.map(row.get("meta"));
        T t = meta.isEmpty() ? new T() : fromSnapshot(meta, arabicFirst);
        t.id = MiniJson.str(row.get("title_id"));
        return t;
    }

    static final int RATE_NONE = 0, RATE_LIKE = 1, RATE_LOVE = 2, RATE_DISLIKE = -1;

    static int ratingScore(int tv) {
        return tv == RATE_LOVE ? 10 : tv == RATE_LIKE ? 8 : 1;
    }

    static int ratingFromScore(long score) {
        return score >= 9 ? RATE_LOVE : score >= 5 ? RATE_LIKE : RATE_DISLIKE;
    }

    static Map<String, Object> ratingRow(String id, int tv) {
        return MiniJson.obj("title_id", id, "rating", (long) ratingScore(tv), "deleted", false);
    }

    static Map<String, Object> ratingTombstone(String id) {
        return MiniJson.obj("title_id", id, "rating", 0L, "deleted", true);
    }

    // ---- settings ---------------------------------------------------------------------------

    static boolean isSettingKey(String k) {
        for (String s : SETTING_KEYS) if (s.equals(k)) return true;
        return false;
    }

    /** What each setting is on a fresh install (a setting at its default says nothing on the first upload). */
    static Object settingDefault(String key) {
        switch (key) {
            case "level": return 0;
            case "autoplayNext": return Boolean.TRUE;
            case "subtitleLang": return "ar";
            case "subtitleSize": return 1;
            case "subtitleBackground": return 2;
            default: return Boolean.FALSE;      // titleLang (English), familySkips, subtitleColor (white)
        }
    }

    /** The app's value (Integer, Boolean or String, as in CinemaPrefs) as the synced one. */
    static Object toRemote(String key, Object tv) {
        switch (key) {
            case "level": return (long) ((Number) tv).intValue();
            case "titleLang": return Boolean.TRUE.equals(tv) ? "ar" : "en";
            case "subtitleSize": {
                int s = ((Number) tv).intValue();
                return s <= 0 ? "small" : s == 1 ? "medium" : "large";
            }
            case "subtitleColor": return Boolean.TRUE.equals(tv) ? "yellow" : "white";
            case "subtitleBackground": {
                int s = ((Number) tv).intValue();
                return s == 1 ? "box" : s == 0 ? "none" : "shadow";
            }
            default: return tv;       // familySkips, autoplayNext, subtitleLang
        }
    }

    /** The app's value for a synced one, or null when it is invalid, not representable here or already what `current` is. */
    static Object fromRemote(String key, Object remote, Object current) {
        Object v = null;
        switch (key) {
            case "level": {
                long n = remote instanceof Number ? ((Number) remote).longValue() : -1;
                if (n == 0 || n == 2 || n == 3) v = (int) n;
                break;
            }
            case "titleLang":
                if ("ar".equals(remote) || "en".equals(remote)) v = "ar".equals(remote);
                break;
            case "familySkips":
            case "autoplayNext":
                if (remote instanceof Boolean) v = remote;
                break;
            case "subtitleLang":
                if ("ar".equals(remote) || "en".equals(remote) || "off".equals(remote)) v = remote;
                break;
            case "subtitleSize": {
                int s = "small".equals(remote) ? 0 : "medium".equals(remote) ? 1 : "large".equals(remote) ? 2 : -1;
                // "large" is both Large and Extra large here: keep the one it already is.
                if (s == 2 && current instanceof Number && ((Number) current).intValue() == 3) return null;
                if (s >= 0) v = s;
                break;
            }
            case "subtitleColor":
                if ("white".equals(remote) || "yellow".equals(remote)) v = "yellow".equals(remote);
                break;
            case "subtitleBackground":
                if ("none".equals(remote)) v = 0;
                else if ("box".equals(remote)) v = 1;
                else if ("shadow".equals(remote)) v = 2;
                break;
            default:
        }
        return v == null || v.equals(current) ? null : v;
    }

    static Map<String, Object> settingRow(String key, Object tv) {
        return MiniJson.obj("key", key, "value", toRemote(key, tv), "deleted", false);
    }

    // ---- snapshots (the web's Snapshot) -----------------------------------------------------

    static boolean hasArabic(String s) {
        for (int i = 0; s != null && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
        }
        return false;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static Object orNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Object numberOrNull(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return d > 0 ? (Object) d : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Map<String, Object> snapshot(T t, int contentChars) {
        // The app keeps one title and one other title, in whichever languages; the web wants an English and an Arabic one.
        String a = t.title, b = t.other;
        String en = !hasArabic(a) && !a.isEmpty() ? a : !hasArabic(b) && !b.isEmpty() ? b : a;
        String ar = hasArabic(a) ? a : hasArabic(b) ? b : en;
        String text = cut(t.overview, contentChars);
        List<Object> genres = new ArrayList<>();
        for (String g : t.genres.split("\\s*·\\s*")) if (!g.trim().isEmpty()) genres.add(MiniJson.obj("en", g.trim(), "ar", g.trim()));
        return MiniJson.obj("id", t.id, "kind", t.series ? "series" : "movie", "enTitle", en, "arTitle", ar,
                "enContent", text, "arContent", text, "poster", orNull(t.poster), "posterThumb", orNull(t.poster),
                "cover", orNull(t.cover), "year", numberOrNull(t.year) == null ? null : (Object) (long) MiniJson.dbl(numberOrNull(t.year)),
                "imdbId", orNull(t.imdb), "genres", genres, "stars", numberOrNull(t.stars), "filmRating", null,
                "seriesRating", null, "duration", t.minutes > 0 ? (Object) (long) (t.minutes * 60) : null,
                "season", t.season > 0 ? (Object) (long) t.season : null,
                "episode", t.episode > 0 ? (Object) (long) t.episode : null, "trailer", null);
    }

    static Map<String, Object> seriesSnapshot(String id, String name, String poster) {
        T t = new T();
        t.id = id;
        t.title = name == null ? "" : name;
        t.poster = poster == null ? "" : poster;
        t.series = true;
        Map<String, Object> m = snapshot(t, 0);
        m.put("posterThumb", orNull(t.poster));
        return m;
    }

    static T fromSnapshot(Map<String, Object> s, boolean arabicFirst) {
        T t = new T();
        t.id = MiniJson.str(s.get("id"));
        String en = MiniJson.str(s.get("enTitle")), ar = MiniJson.str(s.get("arTitle"));
        t.title = arabicFirst && !ar.isEmpty() || en.isEmpty() ? ar : en;
        t.other = t.title.equals(ar) ? en : ar;
        if (t.other.equals(t.title)) t.other = "";
        String enText = MiniJson.str(s.get("enContent")).trim(), arText = MiniJson.str(s.get("arContent")).trim();
        t.overview = (arabicFirst && !arText.isEmpty() || enText.isEmpty() ? arText : enText).replaceAll("\\s+", " ");
        if (s.get("year") instanceof Number) t.year = String.valueOf(MiniJson.lng(s.get("year")));
        if (s.get("stars") instanceof Number && MiniJson.dbl(s.get("stars")) > 0) {
            t.stars = String.format(Locale.ROOT, "%.1f", MiniJson.dbl(s.get("stars")));
        }
        StringBuilder g = new StringBuilder();
        List<Object> genres = MiniJson.list(s.get("genres"));
        for (int i = 0, n = 0; i < genres.size() && n < 2; i++) {
            String name = MiniJson.str(MiniJson.map(genres.get(i)).get("en"));
            if (name.isEmpty() || name.equalsIgnoreCase("uncategorized")) continue;
            if (g.length() > 0) g.append(" · ");
            g.append(name);
            n++;
        }
        t.genres = g.toString();
        t.poster = MiniJson.str(s.get("poster")).isEmpty() ? MiniJson.str(s.get("posterThumb")) : MiniJson.str(s.get("poster"));
        t.cover = MiniJson.str(s.get("cover"));
        t.imdb = MiniJson.str(s.get("imdbId"));
        t.series = "series".equals(s.get("kind"));
        if (s.get("season") instanceof Number) t.season = (int) MiniJson.lng(s.get("season"));
        if (s.get("episode") instanceof Number) t.episode = (int) MiniJson.lng(s.get("episode"));
        if (s.get("duration") instanceof Number) t.minutes = (int) Math.round(MiniJson.dbl(s.get("duration")) / 60);
        return t;
    }

    /** Keeps a meta object under the server's 8 KB limit: the synopses go first, then everything but dev. */
    static Map<String, Object> fitMeta(Map<String, Object> meta) {
        if (MiniJson.write(meta).length() <= MAX_META) return meta;
        Map<String, Object> lean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map && ((Map<?, ?>) v).containsKey("enContent")) {
                Map<String, Object> m = new LinkedHashMap<>(MiniJson.map(v));
                m.put("enContent", "");
                m.put("arContent", "");
                m.put("genres", new ArrayList<Object>());
                v = m;
            }
            lean.put(e.getKey(), v);
        }
        return MiniJson.write(lean).length() <= MAX_META ? lean : MiniJson.obj("dev", meta.get("dev"));
    }
}
