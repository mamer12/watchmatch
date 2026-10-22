package io.watchmatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the mirror's picture choice and slots, the title page's preview timing and the reset steps on a plain JVM.
 * From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/{Endpoints,ProviderRules,ImageRules,HeroRules,ResetRules}.java \
 *       app/test/io/watchmatch/WatchmeRulesCheck.java && java -cp out/test io.watchmatch.WatchmeRulesCheck
 */
public final class WatchmeRulesCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) throws Exception {
        images();
        hero();
        reset();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void images() throws Exception {
        eq("mirror: small poster", "m.jpg", ImageRules.cardPoster("other", "big.jpg", "m.jpg", "t.jpg"));
        eq("mirror: no small, the full one", "big.jpg", ImageRules.cardPoster("other", "big.jpg", "", "t.jpg"));
        eq("mirror: only the tiny", "t.jpg", ImageRules.cardPoster("other", "", null, "t.jpg"));
        eq("el: the full poster first", "big.jpg", ImageRules.cardPoster("el", "big.jpg", "m.jpg", "t.jpg"));
        eq("el: falls back to the small", "m.jpg", ImageRules.cardPoster("el", "", "m.jpg", "t.jpg"));
        eq("nothing", "", ImageRules.cardPoster("other", null, null, null));
        eq("slots mirror", 4, ImageRules.slots("other"));
        eq("slots EL", 8, ImageRules.slots("el"));
        eq("kept 30 days", 30L * 86_400_000L, ImageRules.KEEP_MS);

        // The gate never lets more than `max` in, and lets the next one in when one leaves.
        final ImageRules.Gate gate = new ImageRules.Gate();
        final int[] peak = {0};
        final Object lock = new Object();
        final int[] now = {0};
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                try {
                    gate.acquire(4);
                    synchronized (lock) {
                        now[0]++;
                        peak[0] = Math.max(peak[0], now[0]);
                    }
                    Thread.sleep(5);
                    synchronized (lock) { now[0]--; }
                    gate.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        eq("gate peak", true, peak[0] >= 1 && peak[0] <= 4);
        eq("gate empty at the end", 0, gate.active());
    }

    private static void hero() {
        long d = HeroRules.DELAY_MS;
        eq("delay is 1.8 s", 1800L, d);
        eq("starts after the delay", true, HeroRules.shouldStartPreview(false, false, true, true, d, false));
        eq("not before", false, HeroRules.shouldStartPreview(false, false, true, true, d - 1, false));
        eq("not before the picture loaded", false, HeroRules.shouldStartPreview(false, false, true, false, d + 5000, false));
        eq("reduced motion keeps the photo", false, HeroRules.shouldStartPreview(true, false, true, true, d + 5000, false));
        eq("previews off keeps the photo", false, HeroRules.shouldStartPreview(false, true, true, true, d + 5000, false));
        eq("nothing to play", false, HeroRules.shouldStartPreview(false, false, false, true, d + 5000, false));
        eq("player opening", false, HeroRules.shouldStartPreview(false, false, true, true, d + 5000, true));
        eq("billboard preview on EL", true, HeroRules.billboardPreview("el"));
        eq("no billboard preview on the mirror", false, HeroRules.billboardPreview("other"));
    }

    private static void reset() {
        final List<String> ran = new ArrayList<>();
        String[] names = {"a", "b", "c", "d"};
        ResetRules.Step[] steps = {
            () -> ran.add("a"),
            () -> { throw new IOException("disk full"); },
            () -> ran.add("c"),
            () -> { throw new IllegalStateException("odd"); },
        };
        ResetRules.Result r = ResetRules.run(names, steps);
        eq("steps after a failure still run", java.util.Arrays.asList("a", "c"), ran);
        eq("ok list", java.util.Arrays.asList("a", "c"), r.ok);
        eq("failed count", 2, r.failed.size());
        eq("failure names the step", true, r.failed.get(0).startsWith("b: ") && r.failed.get(0).contains("disk full"));
        ResetRules.Result none = ResetRules.run(new String[0], new ResetRules.Step[0]);
        eq("no steps", true, none.ok.isEmpty() && none.failed.isEmpty());
    }
}
