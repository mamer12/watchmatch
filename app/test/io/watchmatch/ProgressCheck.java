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
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
