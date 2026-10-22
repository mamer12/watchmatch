package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.function.Supplier;

/**
 * Which profile's saved data a read or write goes to: the active profile's, or (on the sync thread, for the duration of
 * `with`) another one's. Main keeps the file names used before profiles, so what Watchme+ saved then is simply main's.
 */
final class ProfilePrefs {
    private ProfilePrefs() {}

    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    /** The profile the current thread works on: the one set by with(), else the active one. */
    static String id(Context c) {
        String s = SCOPE.get();
        return s != null ? s : Profiles.activeId(c);
    }

    /** Runs `work` with every ProfilePrefs/Progress/CinemaPrefs access on this thread going to profile `pid`. */
    static <T> T with(String pid, Supplier<T> work) {
        String was = SCOPE.get();
        SCOPE.set(pid);
        try {
            return work.get();
        } finally {
            if (was == null) SCOPE.remove();
            else SCOPE.set(was);
        }
    }

    /** "progress" or "settings" of the current profile. */
    static SharedPreferences open(Context c, String suffix, int mode) {
        return open(c, id(c), suffix, mode);
    }

    static SharedPreferences open(Context c, String pid, String suffix, int mode) {
        if (ProfileRules.MAIN.equals(pid)) return LegacyPrefs.open(c, suffix, mode);
        return c.getSharedPreferences("catalog-" + ProfileRules.storeName(suffix, pid), mode);
    }

    /** The current profile is a Kids one. */
    static boolean kids(Context c) {
        ProfileRules.Profile p = Profiles.get(c, id(c));
        return p != null && p.kids;
    }
}
