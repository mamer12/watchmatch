package io.watchmatch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A compact, anonymous summary of the active profile's taste, sent to the AI as data (never ids, emails or the
 * profile's name): the genres watched and rated, the eras, recent, liked and disliked titles, and the time of the week.
 * Port of the web app's features/tonight/tasteSummary.ts (same JSON, same 1.5 KB cap). Pure Java (no Android).
 */
final class TonightTaste {
    private TonightTaste() {}

    static final int MAX_SUMMARY_BYTES = 1500;
    private static final int TOP_GENRES = 6, MAX_RECENT = 10, MAX_LIKED = 10, MAX_DISLIKED = 5, NAME_MAX = 40;
    /** Thursday evening starts the Iraqi weekend (Friday and Saturday are off). */
    private static final int WEEKEND_EVE_DAY = Calendar.THURSDAY, WEEKEND_EVE_HOUR = 16;
    private static final String[] WEEKDAYS = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    /** A title as the summary needs it: its id (to tell titles apart, never sent), name, lower case English genres, year (0 unknown). */
    static final class Title {
        final String id, name;
        final List<String> genres;
        final int year;

        Title(String id, String name, List<String> genres, int year) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.genres = Collections.unmodifiableList(new ArrayList<>(genres));
            this.year = year;
        }
    }

    /** The latest of a title's watching (an episode counts for its series): how far it got (0 to 1) and when (ms). */
    static final class Watched {
        final Title title;
        final double fraction;
        final long at;

        Watched(Title title, double fraction, long at) {
            this.title = title;
            this.fraction = fraction;
            this.at = at;
        }
    }

    static final String LOVE = "love", LIKE = "like", DISLIKE = "dislike";

    static final class Rated {
        final Title title;
        final String rating;

        Rated(Title title, String rating) {
            this.title = title;
            this.rating = rating;
        }
    }

    /** Everything the summary is made from. `myList` is newest first. */
    static final class Input {
        final List<Watched> watched;
        final List<Rated> rated;
        final List<Title> myList;
        final List<String> onboardingGenres;

        Input(List<Watched> watched, List<Rated> rated, List<Title> myList, List<String> onboardingGenres) {
            this.watched = watched;
            this.rated = rated;
            this.myList = myList;
            this.onboardingGenres = onboardingGenres;
        }
    }

    /** The summary: its parts as lists, then asJson(). Mutable while capping, never shared. */
    static final class Summary {
        final List<Object[]> genres = new ArrayList<>();      // {name, weight}
        String lang = "en";
        Map<String, Integer> eras = new LinkedHashMap<>();
        final List<String> recent = new ArrayList<>(), liked = new ArrayList<>(), disliked = new ArrayList<>();
        String weekday = "monday";
        int hour;
        boolean weekendEve;

        String json() {
            List<Object> g = new ArrayList<>();
            for (Object[] x : genres) g.add(MiniJson.obj("g", x[0], "w", x[1]));
            Map<String, Object> out = MiniJson.obj("genres", g, "lang", lang, "eras", eras, "recent", recent, "liked", liked,
                    "disliked", disliked, "ctx", MiniJson.obj("weekday", weekday, "hour", hour, "isWeekendEve", weekendEve));
            return MiniJson.write(out);
        }
    }

    static boolean isWeekendEve(Calendar now) {
        return now.get(Calendar.DAY_OF_WEEK) == WEEKEND_EVE_DAY && now.get(Calendar.HOUR_OF_DAY) >= WEEKEND_EVE_HOUR;
    }

    /** `lang`: the TV's language ("ar", "en", "pt", "es"). */
    static Summary build(Input in, Calendar now, String lang) {
        Summary s = new Summary();
        s.lang = lang;
        s.weekday = WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1];
        s.hour = now.get(Calendar.HOUR_OF_DAY);
        s.weekendEve = isWeekendEve(now);
        for (Map.Entry<String, Double> e : top(affinity(in), TOP_GENRES)) {
            s.genres.add(new Object[] {e.getKey(), Math.round(e.getValue() * 10) / 10.0});
        }
        // Recent: the latest activity per title, newest first.
        Map<String, Long> latest = new LinkedHashMap<>();
        Map<String, Title> seen = new LinkedHashMap<>();
        for (Watched w : in.watched) {
            Long was = latest.get(w.title.id);
            latest.put(w.title.id, was == null ? w.at : Math.max(was, w.at));
            seen.put(w.title.id, w.title);
        }
        List<Map.Entry<String, Long>> byTime = new ArrayList<>(latest.entrySet());
        Collections.sort(byTime, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> recentNames = new ArrayList<>();
        for (Map.Entry<String, Long> e : byTime) recentNames.add(name(seen.get(e.getKey())));
        s.recent.addAll(unique(recentNames, MAX_RECENT));

        List<String> likedNames = new ArrayList<>();
        for (String wanted : new String[] {LOVE, LIKE}) {
            for (Rated r : in.rated) if (r.rating.equals(wanted)) likedNames.add(name(r.title));
        }
        for (Title t : in.myList) likedNames.add(name(t));
        s.liked.addAll(unique(likedNames, MAX_LIKED));
        List<String> dislikedNames = new ArrayList<>();
        for (Rated r : in.rated) if (r.rating.equals(DISLIKE)) dislikedNames.add(name(r.title));
        s.disliked.addAll(unique(dislikedNames, MAX_DISLIKED));

        Map<String, Integer> eras = new LinkedHashMap<>();
        for (Title t : seen.values()) {
            if (t.year <= 0) continue;
            String decade = (t.year / 10 * 10) + "s";
            Integer n = eras.get(decade);
            eras.put(decade, n == null ? 1 : n + 1);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(eras.entrySet());
        Collections.sort(sorted, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < sorted.size() && i < 3; i++) s.eras.put(sorted.get(i).getKey(), sorted.get(i).getValue());
        return cap(s);
    }

    /** The genre weights: the welcome's picks +2, ratings +3 / +2 / -3, and watching +0.5 to +1.5 by how far it got. */
    static Map<String, Double> affinity(Input in) {
        Map<String, Double> a = new LinkedHashMap<>();
        for (String g : in.onboardingGenres) add(a, g, 2);
        for (Rated r : in.rated) {
            double w = LOVE.equals(r.rating) ? 3 : LIKE.equals(r.rating) ? 2 : DISLIKE.equals(r.rating) ? -3 : 0;
            for (String g : r.title.genres) add(a, g, w);
        }
        for (Watched w : in.watched) {
            for (String g : w.title.genres) add(a, g, 0.5 + Math.min(1, Math.max(0, w.fraction)));
        }
        return a;
    }

    private static void add(Map<String, Double> a, String genre, double w) {
        String k = genre == null ? "" : genre.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return;
        Double was = a.get(k);
        a.put(k, (was == null ? 0 : was) + w);
    }

    /** The best `n` genres (positive weights only), heaviest first; ties stay in the order they were first seen. */
    static List<Map.Entry<String, Double>> top(Map<String, Double> a, int n) {
        List<Map.Entry<String, Double>> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : a.entrySet()) if (e.getValue() > 0) list.add(e);
        Collections.sort(list, (x, y) -> Double.compare(y.getValue(), x.getValue()));
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }

    private static String name(Title t) {
        if (t == null) return "";
        String n = t.name.replaceAll("\\s+", " ").trim();
        return n.length() > NAME_MAX ? n.substring(0, NAME_MAX) : n;
    }

    private static List<String> unique(List<String> names, int max) {
        Set<String> set = new LinkedHashSet<>();
        for (String n : names) if (!n.isEmpty()) set.add(n);
        List<String> out = new ArrayList<>(set);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Drops the least useful items until the JSON fits MAX_SUMMARY_BYTES: from the longest list, then the eras. */
    static Summary cap(Summary s) {
        while (s.json().length() > MAX_SUMMARY_BYTES) {
            List<?> longest = s.recent;
            for (List<?> l : new List<?>[] {s.liked, s.disliked, s.genres}) if (l.size() > longest.size()) longest = l;
            if (longest.isEmpty()) {
                s.eras = new LinkedHashMap<>();
                break;
            }
            longest.remove(longest.size() - 1);
        }
        return s;
    }

    /** The name to show for a title that has two (the English one for the AI): the one without Arabic letters when only one has. */
    static String latinName(String a, String b) {
        boolean aAr = hasArabic(a), bAr = hasArabic(b);
        if (aAr && !bAr && b != null && !b.isEmpty()) return b;
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static boolean hasArabic(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x0600 && s.charAt(i) <= 0x06FF) return true;
        return false;
    }
}
