package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks which film "Up next" picks (MovieNext), on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/MovieNext.java \
 *       app/test/io/watchmatch/MovieNextCheck.java && java -cp out/test io.watchmatch.MovieNextCheck
 */
public final class MovieNextCheck {
    private static int failures;

    private static MovieNext.Cand c(String id, String genres, String stars, boolean poster, boolean started) {
        return new MovieNext.Cand(id, genres, stars, false, poster, started);
    }

    private static int pick(List<MovieNext.Cand> l, Set<String> liked, String... skip) {
        return MovieNext.pick(l, "now", "Action · Thriller", liked, new HashSet<>(Arrays.asList(skip)));
    }

    public static void main(String[] args) {
        Set<String> none = Collections.emptySet();
        List<MovieNext.Cand> l = new ArrayList<>();
        l.add(c("now", "Action · Thriller", "8.0", true, false));
        l.add(c("a", "Action · Thriller", "7.0", true, false));
        l.add(c("b", "Action · Thriller", "8.0", true, false));
        eq("never the film itself", 2, pick(l, none));
        eq("skips watched / hidden / disliked", 1, pick(l, none, "b"));
        eq("everything skipped: nothing", -1, pick(l, none, "a", "b"));
        eq("empty list: nothing", -1, pick(new ArrayList<>(), none));

        l = new ArrayList<>();
        l.add(c("x", "Comedy", "8.5", true, false));
        l.add(c("y", "Action", "7.0", true, false));
        eq("same genre beats a better rating", 1, pick(l, none));

        l = new ArrayList<>();
        l.add(c("started", "Action · Thriller", "8.0", true, true));
        l.add(c("fresh", "Action · Thriller", "8.0", true, false));
        eq("prefers unseen over started", 1, pick(l, none));

        l = new ArrayList<>();
        l.add(c("low", "Action · Thriller", "4.0", true, false));
        l.add(c("good", "Action · Thriller", "7.8", true, false));
        eq("prefers the better rated", 1, pick(l, none));

        l = new ArrayList<>();
        l.add(c("nopic", "Action · Thriller", "8.0", false, false));
        l.add(c("pic", "Action · Thriller", "8.0", true, false));
        eq("prefers one with a poster", 1, pick(l, none));

        l = new ArrayList<>();
        l.add(new MovieNext.Cand("show", "Action · Thriller", "9.0", true, true, false));
        eq("a series is never a film pick", -1, pick(l, none));

        l = new ArrayList<>();
        l.add(c("p", "Drama", "6.0", true, false));
        l.add(c("q", "Drama", "6.0", true, false));
        eq("a tie keeps the earlier", 0, MovieNext.pick(l, "now", "Drama", none, none));

        l = new ArrayList<>();
        l.add(c("m", "Documentary", "4.0", false, false));
        eq("nothing alike, poor, no poster: nothing", -1, pick(l, none));

        l = new ArrayList<>();
        l.add(c("r", "Romance", "6.0", true, false));
        l.add(c("s", "Horror", "6.0", true, false));
        Set<String> liked = new HashSet<>(Arrays.asList("horror"));
        eq("liked genres tip the balance", 1, MovieNext.pick(l, "now", "Drama", liked, none));
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("MovieNextCheck: all passed");
    }

    private static void eq(String what, Object want, Object got) {
        if (!want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }
}
