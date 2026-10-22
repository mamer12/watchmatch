package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/** Opens the catalog's saved settings, first moving over what an older version kept under another name. */
final class LegacyPrefs {
    private LegacyPrefs() {}

    // The name older versions used, kept apart so the saved lists, ratings and progress carry over.
    private static final String OLD = "cine" + "mana";

    /** The home row id older versions saved (a saved row order may still hold it). */
    static String row(String entry) {
        if (entry.equals(OLD)) return HomeRows.LIBRARY;
        if (entry.equals("-" + OLD)) return "-" + HomeRows.LIBRARY;
        return entry;
    }

    @SuppressWarnings("unchecked")
    static SharedPreferences open(Context c, String suffix, int mode) {
        SharedPreferences now = c.getSharedPreferences("catalog-" + suffix, mode);
        SharedPreferences old = c.getSharedPreferences(OLD + "-" + suffix, mode);
        Map<String, ?> all = old.getAll();
        if (all.isEmpty()) return now;
        SharedPreferences.Editor e = now.edit();
        for (Map.Entry<String, ?> kv : all.entrySet()) {
            String k = kv.getKey();
            Object v = kv.getValue();
            if (now.contains(k)) continue;
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
            else if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
        }
        e.commit();
        old.edit().clear().commit();
        return now;
    }
}
