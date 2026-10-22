package io.watchmatch;

/**
 * Checks the loading percent on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/LoadPercent.java \
 *       app/test/io/watchmatch/LoadPercentCheck.java && java -cp out/test io.watchmatch.LoadPercentCheck
 */
public final class LoadPercentCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) {
        LoadPercent p = new LoadPercent();
        eq("target from rate", 6_000_000L, LoadPercent.targetBytes(100_000_000L, 100_000));
        eq("target fallback (no size)", 3L << 20, LoadPercent.targetBytes(0, 100_000));
        eq("target fallback (no duration)", 3L << 20, LoadPercent.targetBytes(100_000_000L, 0));
        eq("starts at 0", 0, p.update(0, 1000));
        eq("eased: at most +8", 8, p.update(900, 1000));
        eq("again +8", 16, p.update(900, 1000));
        eq("never down", 16, p.update(100, 1000));
        for (int i = 0; i < 20; i++) p.update(5000, 1000);
        eq("capped at 99", 99, p.value());
        p.reset();
        eq("reset to 0", 0, p.value());
        eq("unknown target stays 0", 0, p.update(500, 0));
        eq("negative ahead stays 0", 0, p.update(-5, 1000));
        eq("small step passes", 3, p.update(30, 1000));
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
