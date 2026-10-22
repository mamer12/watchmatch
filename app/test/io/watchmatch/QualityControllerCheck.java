package io.watchmatch;

/**
 * Checks QualityController (decisions, hysteresis, cooldown, cap, manual override, start) on a plain JVM. From the root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/QualityController.java \
 *       app/test/io/watchmatch/QualityControllerCheck.java && java -cp out/test io.watchmatch.QualityControllerCheck
 */
public final class QualityControllerCheck {
    private static int passed, failed;
    private static final int[] H = {240, 480, 720, 1080, 2160};
    private static final double MB = 1_000_000;     // bytes per second: 1 MB/s = 8 Mbit/s

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    /** Bitrates 0.1, 0.25, 0.5, 0.9, 2.5 MB/s. */
    private static QualityController make(int cap, int start) {
        QualityController q = new QualityController(H, cap, start, 0);
        double[] b = {0.1, 0.25, 0.5, 0.9, 2.5};
        for (int i = 0; i < b.length; i++) q.setBitrate(i, b[i] * MB);
        return q;
    }

    public static void main(String[] args) {
        cooldown();
        up();
        down();
        cap();
        manual();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void cooldown() {
        QualityController q = make(1080, 2);
        q.onThroughput(0.1 * MB);                      // far too slow for 720p
        eq("no change inside the cooldown", -1, q.tick(10_000, 30));
        eq("down once the cooldown is over (speed under 1.3 x bitrate)", 1, q.tick(15_000, 30));
        q.switched(1, 15_000);
        eq("not again at once", -1, q.tick(20_000, 30));
    }

    private static void up() {
        QualityController q = make(1080, 1);
        q.onThroughput(5 * MB);
        eq("not before 45 s of a healthy buffer", -1, q.tick(15_000, 30));
        eq("still not at 44 s", -1, q.tick(59_000, 30));
        eq("one step up after 45 s with 25 s ahead", 2, q.tick(60_000, 30));
        q.switched(2, 60_000);
        eq("only one step at a time, and the cooldown", -1, q.tick(61_000, 30));
        QualityController thin = make(1080, 1);
        thin.onThroughput(5 * MB);
        thin.tick(15_000, 30);
        eq("a dip under 25 s ahead restarts the wait", -1, thin.tick(30_000, 19));
        eq("the wait counts from the dip", -1, thin.tick(70_000, 30));
        eq("then up", 2, thin.tick(120_000, 30));
        QualityController slow = make(1080, 1);
        slow.onThroughput(0.6 * MB);                  // 720p is 0.5: 0.6 < 0.5 x 1.8
        slow.tick(15_000, 30);
        eq("no up when speed < next bitrate x 1.8", -1, slow.tick(90_000, 30));
        QualityController unknown = new QualityController(H, 1080, 1, 0);
        unknown.onThroughput(9 * MB);
        unknown.tick(15_000, 30);
        eq("no up while the next bitrate is unknown", -1, unknown.tick(90_000, 30));
    }

    private static void down() {
        QualityController q = make(1080, 3);
        q.onThroughput(5 * MB);
        q.tick(16_000, 20);
        eq("under 14 s ahead and falling: down one step", 2, q.tick(17_000, 7));
        QualityController flat = make(1080, 3);
        flat.onThroughput(5 * MB);
        flat.tick(16_000, 7);
        eq("under 14 s but not falling: stay", -1, flat.tick(17_000, 7));
        QualityController low = make(1080, 0);
        low.onThroughput(0.01 * MB);
        eq("nothing under the lowest", -1, low.tick(20_000, 1));
        QualityController smooth = make(1080, 2);
        smooth.onThroughput(2 * MB);
        smooth.onThroughput(0.2 * MB);                // EWMA: 2 + 0.3 x (0.2 - 2) = 1.46
        eq("EWMA smooths a dip", true, Math.abs(smooth.throughput() - 1.46 * MB) < 1);
        eq("a smoothed dip above 1.3 x bitrate stays", -1, smooth.tick(20_000, 30));
    }

    private static void cap() {
        QualityController q = make(720, 2);
        q.onThroughput(9 * MB);
        q.tick(15_000, 40);
        eq("never above the cap", -1, q.tick(60_000, 40));
        QualityController below = new QualityController(new int[] {1080, 2160}, 720, 0, 0);
        below.setBitrate(0, 0.9 * MB);
        below.setBitrate(1, 2.5 * MB);
        below.onThroughput(9 * MB);
        below.tick(15_000, 40);
        eq("a cap under the lowest file keeps the lowest", -1, below.tick(60_000, 40));
    }

    private static void manual() {
        QualityController q = make(1080, 2);
        q.onThroughput(0.01 * MB);
        q.setFixed(true, 0);
        eq("a fixed pick turns auto off", -1, q.tick(60_000, 1));
        eq("fixed", true, q.fixed());
        q.setFixed(false, 100_000);
        eq("Auto again: cooldown from then", -1, q.tick(105_000, 30));
        eq("and it decides again", 1, q.tick(116_000, 30));
    }
}
