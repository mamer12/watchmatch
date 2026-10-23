package io.watchmatch;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which film "Up next" offers after a film: the best of the catalog's More Like This list (same genre, newest first).
 * Skips the film itself, series, and what is already finished, hidden or rated "not for me"; prefers what the person
 * hasn't started, shares genres with the film, is well rated and has a poster. Pure Java, checked by MovieNextCheck.
 */
final class MovieNext {
    static final double MIN_SCORE = 3;

    private MovieNext() {
    }

    /** What the ranking reads of a catalog title. */
    static final class Cand {
        final String id, genres, stars;
        final boolean series, hasPoster, started;

        Cand(String id, String genres, String stars, boolean series, boolean hasPoster, boolean started) {
            this.id = id;
            this.genres = genres == null ? "" : genres;
            this.stars = stars == null ? "" : stars;
            this.series = series;
            this.hasPoster = hasPoster;
            this.started = started;
        }
    }

    /** The index in `list` of the best title, or -1 when none is good enough. `skip`: ids finished, hidden or disliked. */
    static int pick(List<Cand> list, String currentId, String currentGenres, Set<String> likedGenres, Set<String> skip) {
        String[] want = split(currentGenres);
        int best = -1;
        double bestScore = MIN_SCORE;
        for (int i = 0; i < list.size(); i++) {
            Cand c = list.get(i);
            if (c == null || c.series || c.id.isEmpty() || c.id.equals(currentId) || skip.contains(c.id)) continue;
            double s = score(c, want, likedGenres);
            if (s > bestScore) {      // a tie keeps the earlier (newer) one
                bestScore = s;
                best = i;
            }
        }
        return best;
    }

    static double score(Cand c, String[] want, Set<String> likedGenres) {
        double s = 0;
        String[] have = split(c.genres);
        for (int i = 0; i < have.length; i++) {
            for (int j = 0; j < want.length; j++) if (have[i].equals(want[j])) s += j == 0 ? 4 : 3;
            if (likedGenres.contains(have[i])) s += 1;
        }
        double stars = stars(c.stars);
        s += stars >= 7.5 ? 3 : stars >= 6.5 ? 2 : stars >= 5.5 ? 1 : stars <= 0 ? -1 : -2;
        s += c.hasPoster ? 2 : -3;
        if (c.started) s -= 4;
        return s;
    }

    /** "Action · Drama" → {"action", "drama"}. */
    private static String[] split(String genres) {
        if (genres == null || genres.trim().isEmpty()) return new String[0];
        String[] p = genres.split("·");
        for (int i = 0; i < p.length; i++) p[i] = p[i].trim().toLowerCase(Locale.ROOT);
        return p;
    }

    private static double stars(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
