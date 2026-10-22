package io.watchmatch;

import android.content.Context;

import java.io.File;

/**
 * "Reset {catalog} data": every profile (back to a single "Me"), My List, ratings, watch progress, the welcome answers and every {catalog} setting, then the
 * catalog's saved answers, picks and pictures. WatchMatch's own settings (Prefs) are never touched. Runs in :cinema,
 * which owns that data; each step may fail without stopping the rest (ResetRules).
 */
final class WatchmeReset {
    private WatchmeReset() {}

    static final String[] NAMES = {"sync link", "profiles", "lists, ratings, progress and settings", "catalog answers", "pictures", "saved picks"};

    /** The reset: the sync link first (nothing of the reset is sent, and nothing on the server is deleted), then everything else. */
    static ResetRules.Result run(final Context c) {
        ResetRules.Result r = ResetRules.run(NAMES, all(c));
        Library.arabicFirst = false;
        for (String f : r.failed) AppLog.w("reset: " + f);
        return r;
    }

    /** Every step, the sync link first (NAMES, in order). */
    static ResetRules.Step[] all(final Context c) {
        return join(() -> Sync.signOutLocal(c), steps(c));
    }

    /** The steps without the sync link (a sign-in that clears the TV first, on the sync thread, links it itself). */
    static void runLocal(final Context c) {
        ResetRules.Step[] steps = steps(c);
        String[] names = java.util.Arrays.copyOfRange(NAMES, 1, NAMES.length);
        ResetRules.Result r = ResetRules.run(names, steps);
        Library.arabicFirst = false;
        for (String f : r.failed) AppLog.w("reset: " + f);
    }

    private static ResetRules.Step[] join(ResetRules.Step first, ResetRules.Step[] rest) {
        ResetRules.Step[] all = new ResetRules.Step[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private static ResetRules.Step[] steps(final Context c) {
        return new ResetRules.Step[] {
            () -> Profiles.resetAll(c),     // every profile but a fresh "Me" goes with its data
            () -> CinemaPrefs.resetAll(c),
            () -> {
                Library.get(c).forgetArt();
                deleteIn(new File(c.getCacheDir(), "library"), null);
            },
            () -> {
                deleteIn(new File(c.getCacheDir(), "logos"), null);
                deleteIn(new File(c.getCacheDir(), "thumbs"), null);
            },
            () -> {
                deleteIn(c.getFilesDir(), "watchme-");
                deleteIn(c.getFilesDir(), "home-picks.json");
            },
        };
    }

    /** Deletes the files in `dir` whose names start with `prefix` (all of them when null). */
    private static void deleteIn(File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (f.isFile() && (prefix == null || f.getName().startsWith(prefix))) f.delete();
        }
    }
}
