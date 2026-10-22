package io.watchmatch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What Watchme+ shares with WatchMatch's home screen (WatchMePlus.apk only; reading needs an app signed with the
 * same key): content://io.watchmeplus.data/continue, /mylist and /recommended, each one row with a "json" column.
 *  - continue: [{title, rootTitle, rootPoster, position, duration, upNext}] (Progress.unfinished)
 *  - mylist: [title] (CinemaPrefs.myList)
 *  - recommended: [{key, title, items: [title]}] (the rows Recommend made last)
 *  - profile: {id, name, color, avatar, kids, account}: the profile that is watching (and the account's email, "" for a guest) (the rows above are its)
 *  - sync: {"applied": n}: pulls from the sync backend now (when this build has one and the TV is linked), waiting a few
 *    seconds, and says how many rows changed (the home screen then reads again)
 * A title is the catalog.json's object. Read-only.
 */
public class WatchData extends ContentProvider {
    static final String AUTHORITY = "io.watchmeplus.data";
    private static final String[] COLUMNS = {"json"};

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context c = getContext();
        if (c == null) return null;
        String path = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        String json;
        try {
            switch (path) {
                case "continue": json = continueWatching(c); break;
                case "mylist": json = titles(CinemaPrefs.myList(c)).toString(); break;
                case "recommended": json = CinemaPrefs.recommendedJson(c); break;
                case "profile": json = profile(c); break;
                case "sync": json = Sync.kickJson(c); break;
                default: return null;
            }
        } catch (Exception e) {
            AppLog.w("watch data " + path + ": " + e);
            return null;
        }
        MatrixCursor out = new MatrixCursor(COLUMNS, 1);
        out.addRow(new Object[] {json});
        return out;
    }

    private static String continueWatching(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (Progress.Entry e : Progress.unfinished(c)) {
            a.put(new JSONObject().put("title", Library.json(e.title)).put("rootTitle", e.rootTitle)
                    .put("rootPoster", e.rootPoster).put("position", e.position).put("duration", e.duration)
                    .put("upNext", e.upNext));
        }
        return a.toString();
    }

    private static String profile(Context c) throws Exception {
        ProfileRules.Profile p = Profiles.active(c);
        if (p == null) return "{}";
        return new JSONObject().put("id", p.id).put("name", p.name).put("color", p.color).put("avatar", p.avatar)
                .put("kids", p.kids).put("account", Account.signedInEmail(c)).toString();
    }

    static JSONArray titles(java.util.List<Library.Title> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Library.Title t : list) a.put(Library.json(t));
        return a;
    }

    @Override public String getType(Uri uri) {
        return "application/json";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
