package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each the catalog video was left: Resume, the episodes' bars and checks, and the "Continue watching" row (one card
 * per series, at its latest episode; after an episode ends, the next one waits there as "Up next").
 */
final class Progress {
    private Progress() {}

    private static final int MAX = 400;
    /** A few seconds in, it's started (it goes to Continue watching, like Netflix); past 95%, it's finished. */
    private static final long STARTED_MS = 3_000;
    private static final float FINISHED = 0.95f;

    static final class Entry {
        Library.Title title;
        String rootTitle = "", rootPoster = "";
        long position, duration, at;
        /** The episode after one that ended: not started yet, shown first in Continue watching. */
        boolean upNext;
        /** Taken out of Continue watching (the history stays, for the episodes' checks). */
        boolean hidden;
        /** The device this position came from when it was synced from another one, else "". */
        String from = "";

        float fraction() { return duration <= 0 ? 0 : Math.min(1, position / (float) duration); }
        boolean unfinished() { return position >= STARTED_MS && fraction() < FINISHED; }
        boolean finished() { return duration > 0 && fraction() >= FINISHED; }
    }

    private static SharedPreferences prefs(Context c) {
        return ProfilePrefs.open(c, "progress", Context.MODE_PRIVATE);
    }

    /** Milliseconds to start from (0 when new or finished). */
    static long resumeAt(Context c, String id) {
        Entry e = get(c, id);
        return e != null && e.unfinished() ? e.position : 0;
    }

    static Entry get(Context c, String id) {
        String raw = prefs(c).getString(id, null);
        return raw == null ? null : parse(raw);
    }

    static void save(Context c, Library.Title t, String rootTitle, String rootPoster, long position, long duration) {
        if (t == null || t.id.isEmpty() || duration <= 0) return;
        put(c, t, rootTitle, rootPoster, position, duration, false);
    }

    /** The next episode waits in Continue watching (unless it was already started). */
    static void upNext(Context c, Library.Title t, String rootTitle, String rootPoster) {
        Entry e = get(c, t.id);
        if (e != null && e.position > 0 && !e.finished()) return;
        put(c, t, rootTitle, rootPoster, 0, 0, true);
    }

    private static void put(Context c, Library.Title t, String rootTitle, String rootPoster, long position, long duration,
            boolean upNext) {
        try {
            JSONObject o = new JSONObject().put("title", Library.json(t)).put("rootTitle", rootTitle)
                    .put("rootPoster", rootPoster).put("position", position).put("duration", duration)
                    .put("upNext", upNext).put("at", stamp(c));
            store(c, t.id, o);
            Sync.nudge(c);
        } catch (Exception e) {
            AppLog.w("progress not saved: " + e);
        }
    }

    /** Writes one entry; past MAX the oldest goes, but only by a clock that's right (a wrong one would drop the newest). */
    private static void store(Context c, String id, JSONObject o) {
        SharedPreferences.Editor ed = prefs(c).edit().putString(id, o.toString());
        if (prefs(c).getAll().size() > MAX && !TrustedTime.suspect()) {
            String oldest = oldest(c);
            if (oldest != null && !oldest.equals(id)) ed.remove(oldest);
        }
        ed.apply();
    }

    /**
     * When it was watched: now, by TrustedTime. While the TV's clock is wrong and no server answered (just after a
     * power cut), at least just after the newest saved, so it still comes first in Continue watching.
     */
    private static long stamp(Context c) {
        long now = TrustedTime.now();
        if (!TrustedTime.suspect()) return now;
        String pid = ProfilePrefs.id(c);
        if (!pid.equals(newestFor)) {
            newestFor = pid;
            newest = -1;
        }
        if (newest < 0) {       // read once per profile (the player saves every few seconds)
            long n = 0;
            for (Entry e : all(c)) n = Math.max(n, e.at);
            newest = n;
        }
        newest = Math.max(now, newest + 1);
        return newest;
    }

    /** The newest stamp saved, while the clock is suspect (-1: not read yet). */
    private static long newest = -1;
    private static String newestFor = "";

    /** The saves so far, on disk now (in the background): the player calls it when it pauses, stops or closes. */
    static void flush(Context c) {
        Durable.flush(prefs(c));
        Sync.flush(c);
    }

    /** Takes the card (and its series) out of Continue watching until something of it is watched again. */
    static void hide(Context c, Entry e) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString(e.title.id, "{}")).put("hidden", true);
            Durable.save(prefs(c), prefs(c).edit().putString(e.title.id, o.toString()));
            Sync.nudge(c);
        } catch (Exception ex) {
            AppLog.w("progress not hidden: " + ex);
        }
    }

    static void clear(Context c) {
        Durable.save(prefs(c), prefs(c).edit().clear());
    }

    /** Continue watching keeps up to this many titles; nothing leaves it by age, only by being finished or removed. */
    static final int CONTINUE_MAX = 20;

    /** Continue watching: unfinished videos and up-next episodes, newest first, one per series. */
    static List<Entry> unfinished(Context c) {
        List<Entry> all = all(c);
        Collections.sort(all, (a, b) -> Long.compare(b.at, a.at));
        return select(all, CONTINUE_MAX);
    }

    /**
     * One entry per series from `newestFirst`: the newest one when it is unfinished or an up-next; when the newest is a
     * finished episode, the series' waiting up-next episode instead (it can be older: the finished one was saved after it).
     */
    static List<Entry> select(List<Entry> newestFirst, int max) {
        List<Entry> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (Entry e : newestFirst) {
            String group = e.title.rootId.isEmpty() ? e.title.id : e.title.rootId;
            if (done.contains(group)) continue;
            if (e.hidden) {
                done.add(group);
                continue;
            }
            if (e.upNext || e.unfinished()) {
                done.add(group);
                out.add(e);
            } else if (e.finished()) {
                Entry waiting = null;
                for (Entry u : newestFirst) {
                    String g = u.title.rootId.isEmpty() ? u.title.id : u.title.rootId;
                    if (u.upNext && !u.hidden && g.equals(group)) {
                        waiting = u;
                        break;
                    }
                }
                done.add(group);
                if (waiting != null) out.add(waiting);
            } else {
                done.add(group);
            }
            if (out.size() >= max) break;
        }
        return out;
    }

    /**
     * Where a series continues: the episode being watched (resumes), the one after the last finished, the waiting
     * up-next, or the first only when nothing of the series was ever watched.
     * {index in `episodes`, 1 when it resumes}; {-1, 0} for none.
     */
    static int[] nextEpisode(Context c, List<Library.Title> episodes) {
        if (episodes.isEmpty()) return new int[] {-1, 0};
        return targetFor(episodes, entriesFor(c, episodes));
    }

    /** Every saved entry of the series: by an episode's id, or by the series id (whatever its id, e.g. a newer episode). */
    static List<Entry> entriesFor(Context c, List<Library.Title> episodes) {
        List<Entry> out = new ArrayList<>();
        if (episodes.isEmpty()) return out;
        String root = episodes.get(0).rootId;
        Set<String> ids = new HashSet<>();
        for (Library.Title t : episodes) ids.add(t.id);
        for (Entry e : all(c)) {
            if (ids.contains(e.title.id) || !root.isEmpty() && (root.equals(e.title.rootId) || root.equals(e.title.id))) out.add(e);
        }
        return out;
    }

    /** True when an entry of the series is past the last episode listed (a stale list lacks the newest episode). */
    static boolean listIsStale(List<Library.Title> episodes, List<Entry> entries) {
        if (episodes.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        long last = 0;
        for (Library.Title t : episodes) {
            ids.add(t.id);
            last = Math.max(last, order(t));
        }
        for (Entry e : entries) if (!ids.contains(e.title.id) && order(e.title) >= last && order(e.title) > 0) return true;
        return false;
    }

    private static long order(Library.Title t) {
        return t.season <= 0 && t.episode <= 0 ? 0 : Math.max(0, t.season) * 100_000L + Math.max(0, t.episode);
    }

    /** Index of the entry's episode in the list: by id, else by season and number; -1 when it is not there. */
    private static int locate(List<Library.Title> episodes, Library.Title t) {
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).id.equals(t.id)) return i;
        long o = order(t);
        if (o == 0) return -1;
        for (int i = 0; i < episodes.size(); i++) if (order(episodes.get(i)) == o) return i;
        return -1;
    }

    /** The last episode listed that comes before or at the entry's position (its place when it is not in the list). */
    private static int floor(List<Library.Title> episodes, Library.Title t) {
        long o = order(t);
        if (o == 0) return episodes.size() - 1;
        int at = 0;
        for (int i = 0; i < episodes.size(); i++) if (order(episodes.get(i)) <= o) at = i;
        return at;
    }

    private static int indexOf(List<Library.Title> episodes, Entry e) {
        int i = locate(episodes, e.title);
        return i >= 0 ? i : floor(episodes, e.title);
    }

    /**
     * The choice itself, on plain data. `entries` are what was saved for the series (any id). The newest watched
     * episode wins: unfinished resumes, finished goes to the next one (the waiting up-next when it is further on; the
     * last episode, to play again, when nothing comes after); started under 3 s plays again; only an up-next goes to it.
     * The first episode only when no entry exists.
     */
    static int[] targetFor(List<Library.Title> episodes, List<Entry> entries) {
        if (episodes.isEmpty()) return new int[] {-1, 0};
        int last = episodes.size() - 1;
        Entry watched = null, waiting = null;
        for (Entry e : entries) {
            if (e.upNext) {
                if (waiting == null || e.at > waiting.at) waiting = e;
            } else if (e.position > 0 || e.duration > 0) {
                if (watched == null || e.at > watched.at) watched = e;
            }
        }
        if (watched == null && waiting == null) return new int[] {0, 0};
        int up = waiting == null ? -1 : indexOf(episodes, waiting);
        if (watched == null) return new int[] {up, 0};
        int w = locate(episodes, watched.title);
        boolean listed = w >= 0;
        if (!listed) w = floor(episodes, watched.title);
        if (watched.unfinished()) return listed ? new int[] {w, 1} : new int[] {last, 0};
        if (!watched.finished()) return new int[] {w, 0};
        int next = Math.min(w + 1, last);
        if (!listed && order(watched.title) > order(episodes.get(last))) next = last;
        if (waiting != null && up > next && up > w) next = up;
        return new int[] {next, 0};
    }

    /** Everything watched (started or finished, not just waiting as Up next), newest first. */
    static List<Entry> history(Context c) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all(c)) if (!e.upNext && e.position > 0) out.add(e);
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    /** Every saved entry (Sync compares them with what it last saw). */
    static List<Entry> everything(Context c) {
        return all(c);
    }

    /**
     * A change that came from another device (Sync): the entry is written as it says (its time the server's, not the
     * clock's), an "up next" one never replaces an episode already started here, and a row with nothing new is ignored.
     * True when something changed.
     */
    static synchronized boolean applyRemote(Context c, SyncMap.RP rp) {
        try {
            Entry old = get(c, rp.id);
            if (rp.deleted) {
                if (old == null) return false;
                Durable.save(prefs(c), prefs(c).edit().remove(rp.id));
                return true;
            }
            SyncMap.P p = rp.p;
            if (p == null || p.title.id.isEmpty()) return false;
            if (p.upNext && old != null && (!old.upNext || old.at >= p.at)) return false;     // started or finished here, or the same
            if (!p.upNext && old != null && !old.hidden && old.position == p.position && old.duration == p.duration
                    && old.at == p.at && old.from.equals(p.from)) {
                return false;
            }
            JSONObject o = new JSONObject().put("title", Library.json(SyncLocal.title(p.title))).put("rootTitle", p.rootTitle)
                    .put("rootPoster", p.rootPoster).put("position", p.position).put("duration", p.duration)
                    .put("upNext", p.upNext).put("at", p.at).put("from", p.from);
            store(c, p.title.id, o);
            Durable.flush(prefs(c));
            return true;
        } catch (Exception e) {
            AppLog.w("remote progress not applied: " + e);
            return false;
        }
    }

    private static List<Entry> all(Context c) {
        List<Entry> all = new ArrayList<>();
        for (Object v : prefs(c).getAll().values()) {
            Entry e = v instanceof String ? parse((String) v) : null;
            if (e != null) all.add(e);
        }
        return all;
    }

    private static String oldest(Context c) {
        String key = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<String, ?> kv : prefs(c).getAll().entrySet()) {
            Entry e = kv.getValue() instanceof String ? parse((String) kv.getValue()) : null;
            long when = e == null ? Long.MIN_VALUE : e.at;
            if (when < at) {
                at = when;
                key = kv.getKey();
            }
        }
        return key;
    }

    private static Entry parse(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject json = o.optJSONObject("title");
            if (json == null) return null;
            Entry e = new Entry();
            e.title = Library.fromJson(json);
            e.rootTitle = Library.s(o, "rootTitle");
            e.rootPoster = Library.s(o, "rootPoster");
            e.position = o.optLong("position");
            e.duration = o.optLong("duration");
            e.at = o.optLong("at");
            e.upNext = o.optBoolean("upNext");
            e.hidden = o.optBoolean("hidden");
            e.from = o.optString("from", "");
            return e.title.id.isEmpty() ? null : e;
        } catch (Exception ex) {
            return null;
        }
    }
}
