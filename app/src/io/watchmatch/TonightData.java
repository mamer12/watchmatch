package io.watchmatch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Spark Tonight on the TV: where its pieces get their data. The active profile's watching, ratings and My List (made
 * into the taste summary), the sync session's token for the AI, and {catalog}'s AdvancedSearch for the titles. All
 * blocking: off the main thread.
 */
final class TonightData {
    private TonightData() {}

    private static final long FRESH_MS = 10 * 60_000L, WAIT_S = 25;
    private static final String GENRE_SEPARATOR = " · ";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    /**
     * Opens Ask Spark. `style` says who asked and so how the results look: TonightActivity.STYLE_APPS (WatchMatch's home:
     * the results by app) or STYLE_CATALOG (Watchme+: the titles themselves). Either way Watchme+'s own app when it is
     * installed: its sync session (the AI), profile and taste live there; this copy only when it is not.
     */
    static Intent launch(Context c, String style) {
        boolean apps = TonightActivity.STYLE_APPS.equals(style);
        Intent standalone = new Intent().setComponent(new ComponentName(CinemaActivity.STANDALONE, TonightActivity.class.getName()));
        boolean installed = standalone.resolveActivity(c.getPackageManager()) != null;
        return (installed ? standalone : new Intent(c, TonightActivity.class)).putExtra(TonightActivity.EXTRA_STYLE, apps
                ? TonightActivity.STYLE_APPS : TonightActivity.STYLE_CATALOG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The AI, with this process's sync session (none: the fixed questions). */
    static TonightApi api() {
        return new TonightApi(Config.SUPABASE_URL, Config.SUPABASE_ANON_KEY, () -> {
            SyncClient client = Sync.client();
            return client == null || !client.signedIn() ? null : client.sessionToken();
        });
    }

    /** The code of the TV's language, as the summary says it. */
    static String language() {
        return Ui.ar ? "ar" : Ui.es ? "es" : Ui.en ? "en" : "pt";
    }

    /** Everything the profile has done with titles, in the summary's terms. */
    static final class Taste {
        final TonightTaste.Input input;
        /** Ids never to offer: finished, in My List, rated. */
        final Set<String> exclude;

        Taste(TonightTaste.Input input, Set<String> exclude) {
            this.input = input;
            this.exclude = exclude;
        }
    }

    static Taste taste(Context c) {
        Map<String, TonightTaste.Title> titles = new HashMap<>();
        Map<String, TonightTaste.Watched> byTitle = new LinkedHashMap<>();
        Set<String> exclude = new HashSet<>();
        for (Progress.Entry e : Progress.history(c)) {
            boolean episode = !e.title.rootId.isEmpty();
            String id = episode ? e.title.rootId : e.title.id;
            TonightTaste.Title t = known(titles, id, episode ? e.rootTitle : e.title.title, e.title.other, e.title);
            double fraction = e.finished() ? 1 : e.fraction();
            TonightTaste.Watched was = byTitle.get(id);
            // The latest activity decides how far it got; an episode of a series counts for the series.
            if (was == null || e.at > was.at) byTitle.put(id, new TonightTaste.Watched(t, fraction, e.at));
            if (!episode && e.finished()) exclude.add(id);
        }
        List<TonightTaste.Title> mine = new ArrayList<>();
        for (Library.Title t : CinemaPrefs.myList(c)) {
            mine.add(known(titles, t.id, t.title, t.other, t));
            exclude.add(t.id);
        }
        for (Library.Title t : CinemaPrefs.likedTitles(c)) known(titles, t.id, t.title, t.other, t);
        List<TonightTaste.Rated> rated = new ArrayList<>();
        for (Map.Entry<String, Integer> r : CinemaPrefs.ratings(c).entrySet()) {
            int v = r.getValue();
            String rating = v == CinemaPrefs.RATE_LOVE ? TonightTaste.LOVE : v == CinemaPrefs.RATE_LIKE ? TonightTaste.LIKE
                    : TonightTaste.DISLIKE;
            TonightTaste.Title t = titles.get(r.getKey());
            rated.add(new TonightTaste.Rated(t != null ? t : new TonightTaste.Title(r.getKey(), "", new ArrayList<String>(), 0), rating));
            exclude.add(r.getKey());
        }
        List<String> onboarding = new ArrayList<>(CinemaPrefs.likedGenres(c));
        return new Taste(new TonightTaste.Input(new ArrayList<>(byTitle.values()), rated, mine, onboarding), exclude);
    }

    private static TonightTaste.Title known(Map<String, TonightTaste.Title> titles, String id, String name, String other,
                                            Library.Title from) {
        TonightTaste.Title t = titles.get(id);
        if (t != null && !t.genres.isEmpty()) return t;
        List<String> genres = new ArrayList<>();
        for (String g : from.genres.split(GENRE_SEPARATOR)) {
            String n = TonightResolver.genreName(g);
            if (!n.isEmpty()) genres.add(n);
        }
        if (t != null && genres.isEmpty()) return t;
        int year = 0;
        try {
            year = Integer.parseInt(from.year.trim());
        } catch (NumberFormatException e) {
            // unknown
        }
        t = new TonightTaste.Title(id, TonightTaste.latinName(name, other), genres, year);
        titles.put(id, t);
        return t;
    }

    /** {catalog}'s titles for the queries, a few at a time. */
    static TonightResolver.Source source(Context c) {
        final Library library = Library.get(c);
        return queries -> {
            List<Callable<List<TonightResolver.Item>>> calls = new ArrayList<>();
            for (final TonightResolver.Query q : queries) calls.add(() -> items(library.advancedNow(params(q), FRESH_MS)));
            List<List<TonightResolver.Item>> out = new ArrayList<>();
            try {
                for (Future<List<TonightResolver.Item>> f : POOL.invokeAll(calls, WAIT_S, TimeUnit.SECONDS)) {
                    try {
                        out.add(f.get());
                    } catch (Exception e) {
                        AppLog.w("tonight: " + e.getMessage());
                        out.add(new ArrayList<TonightResolver.Item>());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return out;
        };
    }

    private static String params(TonightResolver.Query q) {
        StringBuilder b = new StringBuilder("type=").append(q.type);
        if (!q.category.isEmpty()) b.append("&category_id=").append(q.category);
        if (q.from > 0 || q.to > 0) b.append("&year=").append(q.from).append(',').append(q.to);
        return b.append("&page=").append(q.page).toString();
    }

    /** What the cards open: the catalog's own title, by id. */
    static final Map<String, Library.Title> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<TonightResolver.Item> items(List<Library.Title> list) {
        List<TonightResolver.Item> out = new ArrayList<>();
        for (Library.Title t : list) {
            List<String> genres = new ArrayList<>();
            for (String g : t.categories) genres.add(TonightResolver.genreName(g));
            int year = 0;
            double stars = 0;
            try {
                year = Integer.parseInt(t.year.trim());
            } catch (NumberFormatException e) {
                // unknown
            }
            try {
                stars = Double.parseDouble(t.stars.trim());
            } catch (NumberFormatException e) {
                // unknown
            }
            if (t.poster.isEmpty()) continue;
            LAST.put(t.id, t);
            out.add(new TonightResolver.Item(t.id, t.title, t.other, t.title + " " + t.other + " " + t.overview, year, stars,
                    t.minutes, t.series, genres, t.language));
        }
        return out;
    }

    /** The picks as search-style cards for Watchme+ (a poster, the title, "1998 · ★ 6.4"); what OK opens is its page by number. */
    static List<Catalog.Entry> entries(List<TonightResolver.Pick> picks) {
        List<Catalog.Entry> out = new ArrayList<>();
        for (TonightResolver.Pick p : picks) {
            TonightResolver.Item item = p.item;
            Library.Title t = LAST.get(item.id);
            if (t == null || t.poster.isEmpty()) continue;
            Catalog.Entry e = new Catalog.Entry();
            e.title = item.title;
            StringBuilder sub = new StringBuilder(item.year > 0 ? String.valueOf(item.year) : "");
            if (item.stars > 0) sub.append(sub.length() > 0 ? " · " : "").append("★ ").append(String.format(Locale.US, "%.1f", item.stars));
            e.sub = sub.toString();
            e.image = e.still = t.poster;
            e.pkg = Endpoints.PKG;
            e.id = item.id;
            e.query = item.other.isEmpty() ? item.title : item.other;
            out.add(e);
        }
        return out;
    }

    /** The calendar of now, by the trusted clock (the TV's own may be wrong after a power cut). */
    static Calendar now() {
        Calendar c = Calendar.getInstance(Locale.US);
        c.setTimeInMillis(TrustedTime.now());
        return c;
    }
}
