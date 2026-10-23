package io.watchmatch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** What OK does on a search or Ask Spark result: its own page where the app allows it, else the app (as search always did). */
final class EntryOpen {
    private EntryOpen() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Watchme+ by number, Apple TV and Netflix by link, YouTube by video, else the app's own search or the app. */
    static void open(final Activity a, final Catalog.Entry e) {
        if (Endpoints.PKG.equals(e.pkg)) {
            start(a, WatchMe.open(a, e.id, e.query != null ? e.query : e.title));
        } else if (e.video != null) {
            start(a, new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + e.video)).setPackage(e.pkg));
        } else if (Links.service(e.pkg) != null && e.tmdb != null) {
            IO.execute(() -> {
                final String link = Links.find(e.title, e.tmdb, e.pkg);
                MAIN.post(() -> start(a, link != null ? new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(e.pkg)
                        : e.search != null ? Spark.searchIntent(e.search, e.query)
                        : HomeApps.launchIntent(a.getPackageManager(), e.pkg)));
            });
            return;
        } else if (e.search != null) {
            start(a, Spark.searchIntent(e.search, e.query));       // Spark: the app's own search, with the title
        } else {
            start(a, HomeApps.launchIntent(a.getPackageManager(), e.pkg));
        }
        if (!Endpoints.PKG.equals(e.pkg)) Watched.add(a, e);
    }

    private static void start(Activity a, Intent i) {
        if (i == null || a.isFinishing()) return;
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent app = HomeApps.launchIntent(a.getPackageManager(), i.getPackage() == null ? "" : i.getPackage());
            if (app != null) a.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
