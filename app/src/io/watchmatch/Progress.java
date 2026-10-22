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
     * Where a series continues: the episode being watched (resumes), the one after the last finished, or the first.
     * {index in `episodes`, 1 when it resumes}; {-1, 0} for none.
     */
    static int[] nextEpisode(Context c, List<Library.Title> episodes) {
        if (episodes.isEmpty()) return new int[] {-1, 0};
        Entry latest = null;
        int at = -1;
        for (int i = 0; i < episodes.size(); i++) {
            Entry e = get(c, episodes.get(i).id);
            if (e != null && !e.upNext && (latest == null || e.at > latest.at)) {
                latest = e;
                at = i;
            }
        }
        if (latest == null) return new int[] {0, 0};
        if (latest.unfinished()) return new int[] {at, 1};
        return new int[] {Math.min(at + 1, episodes.size() - 1), 0};
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
