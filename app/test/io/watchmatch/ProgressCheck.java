package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Continue watching's choice of what to show (Progress.select) on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -cp $ANDROID_HOME/platforms/android-34/android.jar -d out/test app/src/io/watchmatch/*.java \
 *       app/test/io/watchmatch/ProgressCheck.java && java -cp out/test io.watchmatch.ProgressCheck
 */
public final class ProgressCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    private static Progress.Entry entry(String id, String root, long at, long position, long duration, boolean upNext) {
        Progress.Entry e = new Progress.Entry();
        e.title = new Library.Title();
        e.title.id = id;
        e.title.rootId = root;
        e.at = at;
        e.position = position;
        e.duration = duration;
        e.upNext = upNext;
        return e;
    }

    private static List<String> ids(List<Progress.Entry> l) {
        List<String> out = new ArrayList<>();
        for (Progress.Entry e : l) out.add(e.title.id);
        return out;
    }

    public static void main(String[] args) {
        // The Middle: the up-next E24 was saved at the credits, then E23 kept saving past 95%: E23 is the newest.
        List<Progress.Entry> middle = Arrays.asList(entry("e23", "mid", 300, 99000, 100000, false), entry("e24", "mid", 200, 0, 0, true));
        eq("a finished newest episode shows the series' waiting up-next", Arrays.asList("e24"), ids(Progress.select(middle, 20)));
        List<Progress.Entry> lastEpisode = Arrays.asList(entry("e24", "mid", 300, 99000, 100000, false));
        eq("a finished last episode with nothing after it leaves", Arrays.asList(), ids(Progress.select(lastEpisode, 20)));
        List<Progress.Entry> watching = Arrays.asList(entry("e5", "s", 300, 40000, 100000, false), entry("e6", "s", 200, 0, 0, true));
        eq("the newest unfinished episode wins over an older up-next", Arrays.asList("e5"), ids(Progress.select(watching, 20)));
        List<Progress.Entry> old = Arrays.asList(entry("e6", "s", 300, 99000, 100000, false), entry("e5", "s", 200, 40000, 100000, false));
        eq("an older unfinished episode does not come back after a newer finished one", Arrays.asList(), ids(Progress.select(old, 20)));
        Progress.Entry hidden = entry("e24", "mid", 200, 0, 0, true);
        hidden.hidden = true;
        eq("a hidden up-next stays hidden", Arrays.asList(), ids(Progress.select(Arrays.asList(entry("e23", "mid", 300, 99000, 100000, false), hidden), 20)));
        List<Progress.Entry> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(entry("m" + i, "", 1000 - i, 40000, 100000, false));
        eq("at most 20 titles", 20, Progress.select(many, 20).size());
        eq("the newest 20 stay", "m0", ids(Progress.select(many, 20)).get(0));
        eq("and the 20th is m19", "m19", ids(Progress.select(many, 20)).get(19));
        eq("a film below 3 s is not started", Arrays.asList(), ids(Progress.select(Arrays.asList(entry("f", "", 5, 1000, 100000, false)), 20)));
        tulsaKing();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static List<Library.Title> series(int[] perSeason, int[] listed) {
        List<Library.Title> l = new ArrayList<>();
        int n = 0;
        for (int s = 0; s < perSeason.length; s++) {
            for (int e = 1; e <= perSeason[s]; e++) {
                Library.Title t = new Library.Title();
                t.id = "ep" + (++n);
                t.rootId = "tk";
                t.series = true;
                t.season = s + 1;
                t.episode = e;
                l.add(t);
            }
        }
        return l;
    }

    private static Progress.Entry ep(String id, int season, int episode, long at, long pos, long dur, boolean upNext, String root) {
        Progress.Entry e = entry(id, root, at, pos, dur, upNext);
        e.title.season = season;
        e.title.episode = episode;
        return e;
    }

    private static List<Integer> t(List<Library.Title> eps, List<Progress.Entry> en) {
        int[] r = Progress.targetFor(eps, en);
        return Arrays.asList(r[0], r[1]);
    }

    private static void tulsaKing() {
        // Seasons of 10, 10, 10: S3E10 is the last listed (the list lacks S4E1: stale).
        List<Library.Title> stale = series(new int[] {10, 10, 10}, null);
        List<Library.Title> full = series(new int[] {10, 10, 10, 1}, null);
        full.get(30).id = "3150356";
        List<Progress.Entry> none = new ArrayList<>();
        eq("never watched: S1E1", Arrays.asList(0, 0), t(full, none));
        eq("S4E1 unfinished resumes it", Arrays.asList(30, 1), t(full, Arrays.asList(ep("3150356", 4, 1, 9, 50000, 100000, false, "tk"))));
        eq("S4E1 finished stays on it", Arrays.asList(30, 0), t(full, Arrays.asList(ep("3150356", 4, 1, 9, 99000, 100000, false, "tk"))));
        eq("S4E1 only up-next", Arrays.asList(30, 0), t(full, Arrays.asList(ep("3150356", 4, 1, 9, 0, 0, true, "tk"))));
        eq("S4E1 under 3 s plays it", Arrays.asList(30, 0), t(full, Arrays.asList(ep("3150356", 4, 1, 9, 1000, 100000, false, "tk"))));
        eq("stale list, S4E1 unfinished: last listed, not S1E1", Arrays.asList(29, 0), t(stale, Arrays.asList(ep("3150356", 4, 1, 9, 50000, 100000, false, "tk"))));
        eq("stale list, S4E1 up-next: last listed", Arrays.asList(29, 0), t(stale, Arrays.asList(ep("3150356", 4, 1, 9, 0, 0, true, "tk"))));
        eq("stale list is noticed", true, Progress.listIsStale(stale, Arrays.asList(ep("3150356", 4, 1, 9, 0, 0, true, "tk"))));
        eq("full list is not stale", false, Progress.listIsStale(full, Arrays.asList(ep("3150356", 4, 1, 9, 0, 0, true, "tk"))));
        eq("S3E10 finished then up-next S4E1", Arrays.asList(30, 0), t(full, Arrays.asList(
                ep("ep30", 3, 10, 9, 99000, 100000, false, "tk"), ep("3150356", 4, 1, 8, 0, 0, true, "tk"))));
        eq("only an up-next S4E1 and an older finished S3E10 entry", Arrays.asList(30, 0), t(full, Arrays.asList(
                ep("ep30", 3, 10, 5, 99000, 100000, false, "tk"), ep("3150356", 4, 1, 9, 0, 0, true, "tk"))));
        eq("interleaved: newest unfinished S3E3 among older S1E1", Arrays.asList(22, 1), t(full, Arrays.asList(
                ep("ep1", 1, 1, 1, 99000, 100000, false, "tk"), ep("ep23", 3, 3, 7, 40000, 100000, false, "tk"),
                ep("ep11", 2, 1, 3, 99000, 100000, false, "tk"))));
        eq("different id, same season and episode", Arrays.asList(22, 1), t(full, Arrays.asList(
                ep("other-id", 3, 3, 7, 40000, 100000, false, "tk"))));
        eq("finished middle episode goes to the next", Arrays.asList(23, 0), t(full, Arrays.asList(ep("ep23", 3, 3, 7, 99000, 100000, false, "tk"))));
        eq("finished last episode stays", Arrays.asList(30, 0), t(full, Arrays.asList(ep("3150356", 4, 1, 7, 99000, 100000, false, "tk"))));
        eq("empty list", -1, Progress.targetFor(new ArrayList<Library.Title>(), none)[0]);
    }
}
