package io.watchmatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The pure rules behind Recs (no Android), so they can be checked on a plain JVM. */
final class RecsRules {
    private RecsRules() {}

    static final int MORNING = 0, AFTERNOON = 1, NIGHT = 2;
    static final int PICKS_PER_HOUR = 3;

    /** {TMDB provider-name fragment, app key, package}, in the order that decides a tie. */
    static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"osn", "osn", "com.osn.go"},
        {"tod", "tod", "com.todtv.tod"},
    };

    static final String[][] NAMES = {
        {"netflix", "Netflix"}, {"prime", "Prime Video"}, {"disney", "Disney+"}, {"hulu", "Hulu"},
        {"paramount", "Paramount+"}, {"peacock", "Peacock"}, {"globoplay", "Globoplay"}, {"max", "Max"},
        {"appletv", "Apple TV"}, {"osn", "OSN+"}, {"tod", "TOD"},
    };

    static int period(int hour) {
        return hour >= 5 && hour < 12 ? MORNING : hour >= 12 && hour < 18 ? AFTERNOON : NIGHT;
    }

    static String serviceName(String appKey) {
        if (appKey != null) for (String[] n : NAMES) if (n[0].equals(appKey)) return n[1];
        return "";
    }

    /** {fragment, app key, package} of the service a TMDB provider name stands for, or null. */
    static String[] service(String providerName) {
        if (providerName == null) return null;
        String name = providerName.toLowerCase(Locale.ROOT);
        for (String[] row : SERVICES) {
            String f = row[0];
            boolean hit = f.length() <= 3 ? Pattern.compile("\\b" + Pattern.quote(f) + "\\b").matcher(name).find()
                    : name.contains(f);
            if (hit) return row;
        }
        return null;
    }

    /** "Title: Subtitle" and "Title - Subtitle" are the same franchise as "Title". */
    static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut < 0 ? t : t.substring(0, cut)).trim();
    }

    /**
     * Up to three of the n candidates for the given hour: the window slides one slot per hour, a title is taken once,
     * a franchise once, and the first pass also takes one title per service.
     */
    static List<Integer> choose(String[] titles, String[] apps, long hour) {
        int n = titles.length;
        List<Integer> chosen = new ArrayList<>();
        if (n == 0) return chosen;
        Set<String> franchises = new HashSet<>(), services = new HashSet<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n && chosen.size() < PICKS_PER_HOUR; i++) {
                int at = (int) ((hour * PICKS_PER_HOUR + i) % n);
                if (chosen.contains(at)) continue;
                String f = franchise(titles[at]);
                if (franchises.contains(f)) continue;
                if (pass == 0 && apps[at] != null && services.contains(apps[at])) continue;
                chosen.add(at);
                franchises.add(f);
                if (apps[at] != null) services.add(apps[at]);
            }
        }
        return chosen;
    }

    /** Several lists taken one item at a time, in turn. */
    static <T> List<T> interleave(List<List<T>> lists) {
        List<T> out = new ArrayList<>();
        for (int i = 0; ; i++) {
            boolean any = false;
            for (List<T> l : lists) {
                if (i < l.size()) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) return out;
        }
    }

    static String translateGenre(String name, String language) {
        String[][] rows;
        switch (language) {
            case "pt": rows = new String[][] {{"Action & Adventure", "Ação e Aventura"},
                    {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"}, {"War & Politics", "Guerra e Política"},
                    {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"}, {"Talk", "Entrevistas"}}; break;
            case "es": rows = new String[][] {{"Action & Adventure", "Acción y Aventura"},
                    {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"}, {"War & Politics", "Guerra y Política"},
                    {"Kids", "Infantil"}, {"News", "Noticias"}, {"Soap", "Telenovela"}, {"Talk", "Entrevistas"}}; break;
            case "ar": rows = new String[][] {{"Action & Adventure", "أكشن ومغامرة"},
                    {"Sci-Fi & Fantasy", "خيال علمي وفانتازيا"}, {"War & Politics", "حرب وسياسة"},
                    {"Kids", "أطفال"}, {"News", "أخبار"}, {"Soap", "مسلسل درامي"}, {"Talk", "حوارات"}}; break;
            default: return name;
        }
        for (String[] r : rows) if (r[0].equalsIgnoreCase(name)) return r[1];
        return name;
    }
}
