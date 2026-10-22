package io.watchmatch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * How WatchMatch's home screen asks its own copy of Watchme+ (the :cinema process, where the data lives) to pull from
 * the sync backend, when the Watchme+ app is not installed: content://io.watchmatch.sync/sync answers {"applied": n}.
 * Not exported: only this app asks. (With the Watchme+ app, WatchData's /sync does the same.)
 */
public class SyncProvider extends ContentProvider {
    static final String AUTHORITY = "io.watchmatch.sync";

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (getContext() == null) return null;
        MatrixCursor out = new MatrixCursor(new String[] {"json"}, 1);
        out.addRow(new Object[] {Sync.kickJson(getContext())});
        return out;
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
