package io.watchmatch;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watchme+ (WatchMatch's catalog-based catalog, its own app io.watchmeplus) as the home screen sees it: what it keeps for you
 * (Continue watching, My List, its picks for you), read through its WatchData provider, and how to open a title in it.
 * Without the app installed, WatchMatch's own copy (CinemaActivity in this app) and its data. Blocking reads: off
 * the main thread.
 */
final class WatchMe {
    private WatchMe() {}

    /** The catalog's name on screen (config.properties' catalog_name). */
    static final String NAME = Config.CATALOG_NAME;

    /** Its picks for you: a row's key, name and titles. */
    static final class Picks {
        String key, title;
        final List<Library.Title> items = new ArrayList<>();
    }

    /** One title in Continue watching: how far, and the series it belongs to. */
    static final class Watching {
        Library.Title title;
        String rootTitle = "", rootPoster = "";
        float fraction;
        boolean upNext;
    }

    /** The profile that is watching in Watchme+: its name, colour, avatar key and whether it is a Kids one. */
    static final class Who {
        String id = ProfileRules.MAIN, name = ProfileRules.DEFAULT_NAME, avatar = ProfileRules.AVATARS[0];
        int color;
        boolean kids;
        /** The signed-in account's email, "" for a guest. */
        String account = "";

        /** The name is not the default "Me": worth greeting by. */
        boolean named() {
            return !name.isEmpty() && !name.equals(ProfileRules.DEFAULT_NAME);
        }
    }

    /**
     * The active Watchme+ profile, read through WatchData when the Watchme+ app is installed, else from this app's own
     * copy. Null when it can't be told. Blocking: off the main thread.
     */
    static Who activeProfile(Context c) {
        if (c.getPackageManager().resolveContentProvider(WatchData.AUTHORITY, 0) == null) {
            ProfileRules.Profile p = Profiles.active(c);
            Who w = p == null ? null : who(p.id, p.name, p.color, p.avatar, p.kids);
            if (w != null) w.account = Account.email(c);
            return w;
        }
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://" + WatchData.AUTHORITY + "/profile"),
                null, null, null, null)) {
            if (cur == null || !cur.moveToFirst()) return null;
            JSONObject o = new JSONObject(cur.getString(cur.getColumnIndexOrThrow("json")));
            Who w = who(o.optString("id"), o.optString("name"), o.optInt("color"), o.optString("avatar"), o.optBoolean("kids"));
            w.account = o.optString("account");
            return w;
        } catch (Exception e) {
            AppLog.w("profile not read: " + e);
            return null;
        }
    }

    private static Who who(String id, String name, int color, String avatar, boolean kids) {
        Who w = new Who();
        w.id = id;
        w.name = name;
        w.color = color;
        w.avatar = avatar;
        w.kids = kids;
        return w;
    }

    /** Opens Watchme+ on its "Who's watching?" screen. */
    static Intent openProfiles(Context c) {
        return CinemaActivity.launch(c).putExtra(CinemaActivity.EXTRA_PROFILES, true);
    }

    /** Opens the title's page (by its number, or a search for its name when there's none). */
    static Intent open(Context c, String id, String name) {
        Intent intent = CinemaActivity.launch(c);
        if (id != null) intent.putExtra(CinemaActivity.EXTRA_ID, id);
        return intent.putExtra(CinemaActivity.EXTRA_QUERY, name);
    }

    static List<Watching> continueWatching(Context c) {
        List<Watching> out = new ArrayList<>();
        JSONArray a = read(c, "continue");
        if (a == null) {
            // WatchMatch's own copy: its player writes from another process, so re-read the file first.
            //noinspection deprecation
            ProfilePrefs.open(c, "progress", Context.MODE_MULTI_PROCESS);
            for (Progress.Entry p : Progress.unfinished(c)) {
                Watching w = new Watching();
                w.title = p.title;
                w.rootTitle = p.rootTitle;
                w.rootPoster = p.rootPoster;
                w.fraction = p.fraction();
                w.upNext = p.upNext;
                out.add(w);
            }
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            Library.Title t = o == null ? null : Library.fromJson(o.optJSONObject("title"));
            if (t == null) continue;
            Watching w = new Watching();
            w.title = t;
            w.rootTitle = o.optString("rootTitle");
            w.rootPoster = o.optString("rootPoster");
            long position = o.optLong("position"), duration = o.optLong("duration");
            w.fraction = duration <= 0 ? 0 : Math.min(1, position / (float) duration);
            w.upNext = o.optBoolean("upNext");
            out.add(w);
        }
        return out;
    }

    /** My List, newest first. */
    static List<Library.Title> myList(Context c) {
        JSONArray a = read(c, "mylist");
        if (a == null) {
            //noinspection deprecation
            ProfilePrefs.open(c, "settings", Context.MODE_MULTI_PROCESS);
            return CinemaPrefs.myList(c);
        }
        return titles(a);
    }

    /** Its picks for you as it made them last, or null without the app (then Recommend makes them here). */
    static List<Picks> recommended(Context c) {
        JSONArray a = read(c, "recommended");
        if (a == null) return null;
        List<Picks> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Picks p = new Picks();
            p.key = o.optString("key");
            p.title = o.optString("title");
            p.items.addAll(titles(o.optJSONArray("items")));
            out.add(p);
        }
        return out;
    }

    private static List<Library.Title> titles(JSONArray a) {
        List<Library.Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            Library.Title t = Library.fromJson(a.optJSONObject(i));
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * Asks Watchme+ to pull from the sync backend (another device may have changed Continue watching or My List) and
     * waits a few seconds for it. How many rows changed (0 when sync is off, the TV is not linked or it could not be
     * reached). Blocking: off the main thread.
     */
    static int pullRemote(Context c) {
        if (!Sync.enabled()) return 0;
        String authority = c.getPackageManager().resolveContentProvider(WatchData.AUTHORITY, 0) != null
                ? WatchData.AUTHORITY : SyncProvider.AUTHORITY;
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://" + authority + "/sync"), null, null, null, null)) {
            if (cur == null || !cur.moveToFirst()) return 0;
            return new JSONObject(cur.getString(cur.getColumnIndexOrThrow("json"))).optInt("applied");
        } catch (Exception e) {
            AppLog.w("sync not asked: " + e);
            return 0;
        }
    }

    private static final AtomicInteger MISSES = new AtomicInteger();

    /**
     * Counts the reads that found the app installed but couldn't reach it (TCL keeps it from starting in the
     * background, so right after the TV boots it often can't): compare before and after reading, then try again later.
     */
    static int misses() {
        return MISSES.get();
    }

    /**
     * One of its lists ("continue", "mylist", "recommended"), or null when the app isn't there. Each answer is kept
     * on disk: while the app can't be reached, the last one (or an empty list), and misses() counts it.
     */
    private static JSONArray read(Context c, String path) {
        if (c.getPackageManager().resolveContentProvider(WatchData.AUTHORITY, 0) == null) return null;
        File copy = new File(c.getFilesDir(), "watchme-" + path + ".json");
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://" + WatchData.AUTHORITY + "/" + path),
                null, null, null, null)) {
            String json = cur == null || !cur.moveToFirst() ? null : cur.getString(cur.getColumnIndexOrThrow("json"));
            if (json != null) {
                JSONArray a = new JSONArray(json);
                if (!json.equals(Durable.read(copy))) Durable.write(copy, json);
                return a;
            }
        } catch (Exception e) {
            AppLog.w(NAME + " " + path + " not read: " + e);
        }
        MISSES.incrementAndGet();
        String saved = Durable.read(copy);
        try {
            return saved == null ? new JSONArray() : new JSONArray(saved);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // MARK: - The rows the home screen showed last (a cold boot shows them at once)

    /** Its picks for you and My List as the home screen last showed them, or null. Off the main thread. */
    static Shown lastShown(Context c) {
        String saved = Durable.read(new File(c.getFilesDir(), "home-picks.json"));
        if (saved == null) return null;
        try {
            JSONObject o = new JSONObject(saved);
            Shown out = new Shown();
            JSONArray rows = o.optJSONArray("picks");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                Picks p = new Picks();
                p.key = r.optString("key");
                p.title = r.optString("title");
                p.items.addAll(titles(r.optJSONArray("items")));
                out.picks.add(p);
            }
            out.mine.addAll(titles(o.optJSONArray("mine")));
            return out;
        } catch (Exception e) {
            AppLog.w("last picks not read: " + e);
            return null;
        }
    }

    /** Keeps what the home screen shows now for the next cold start. Off the main thread. */
    static void saveShown(Context c, List<Picks> picks, List<Library.Title> mine) {
        try {
            JSONArray rows = new JSONArray();
            for (Picks p : picks) {
                rows.put(new JSONObject().put("key", p.key).put("title", p.title).put("items", WatchData.titles(p.items)));
            }
            String json = new JSONObject().put("picks", rows).put("mine", WatchData.titles(mine)).toString();
            File f = new File(c.getFilesDir(), "home-picks.json");
            if (!json.equals(Durable.read(f))) Durable.write(f, json);
        } catch (Exception e) {
            AppLog.w("last picks not saved: " + e);
        }
    }

    static final class Shown {
        final List<Picks> picks = new ArrayList<>();
        final List<Library.Title> mine = new ArrayList<>();
    }
}
