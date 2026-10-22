package io.watchmatch;

import io.watchmatch.SwapRules.Action;
import io.watchmatch.SwapRules.State;

/**
 * Checks SwapRules (state machine, timeout, cancel, alignment drift) on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/SwapRules.java \
 *       app/test/io/watchmatch/SwapRulesCheck.java && java -cp out/test io.watchmatch.SwapRulesCheck
 */
public final class SwapRulesCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) {
        happy();
        timeout();
        cancel();
        reseek();
        drift();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static SwapRules ready(long now, long main) {
        SwapRules r = new SwapRules();
        r.begin(now, main);
        r.onPrepared();
        r.onSeeked();
        r.next(now, main, 0, false);
        r.onBuffered(r.target() + SwapRules.BUFFER_EXTRA_MS);
        return r;
    }

    private static void happy() {
        SwapRules r = new SwapRules();
        eq("idle can begin", true, r.canBegin(0));
        r.begin(1000, 60_000);
        eq("target is main + lead", 62_500L, r.target());
        eq("preparing", State.PREPARING, r.state());
        eq("waits", Action.WAIT, r.next(1100, 60_100, 0, false));
        r.onPrepared();
        eq("prepared alone is not enough", Action.WAIT, r.next(1200, 60_200, 0, false));
        r.onSeeked();
        eq("buffering", Action.WAIT, r.next(1300, 60_300, 0, false));
        eq("buffering state", State.BUFFERING, r.state());
        r.onBuffered(r.target() + SwapRules.BUFFER_EXTRA_MS - 1);
        eq("not enough buffer", Action.WAIT, r.next(1400, 60_400, 0, false));
        r.onBuffered(r.target() + SwapRules.BUFFER_EXTRA_MS);
        eq("ready, picture still far", Action.WAIT, r.next(1500, 60_500, 0, false));
        eq("ready state", State.READY, r.state());
        eq("start just before the place", Action.START_SECOND, r.next(3000, r.target() - 100, 0, false));
        eq("aligning", State.ALIGNING, r.state());
        r.onRendered();
        eq("aligned in 150 ms: swap", Action.SWAP, r.next(3100, 62_600, 62_500, true));
        r.succeeded();
        eq("idle after", State.IDLE, r.state());
    }

    private static void timeout() {
        SwapRules r = new SwapRules();
        r.begin(0, 1000);
        eq("11.9 s: still waiting", Action.WAIT, r.next(11_900, 2000, 0, false));
        eq("12 s: abort", Action.ABORT, r.next(12_000, 2000, 0, false));
        eq("aborted", State.ABORTED, r.state());
        eq("backing off", false, r.canBegin(12_000 + 29_999));
        eq("allowed after 30 s", true, r.canBegin(12_000 + 30_000));
        r.begin(50_000, 1000);
        eq("second failure", Action.ABORT, r.next(62_000, 2000, 0, false));
        eq("backoff doubles", false, r.canBegin(62_000 + 59_999));
        eq("2 failures", 2, r.failures());
        SwapRules late = ready(0, 10_000);
        late.next(0, 10_000, 0, false);
        eq("aligning never finishing aborts", Action.START_SECOND, late.next(2400, 12_450, 0, false));
        eq("align timeout", Action.ABORT, late.next(2400 + SwapRules.ALIGN_TIMEOUT_MS, 16_800, 16_900, true));
    }

    private static void cancel() {
        SwapRules r = ready(0, 5000);
        r.cancel();
        eq("a cancel is idle", State.IDLE, r.state());
        eq("no backoff after a cancel", true, r.canBegin(1));
        eq("not active", false, r.active());
        SwapRules s = new SwapRules();
        s.begin(0, 100);
        eq("active while preparing", true, s.active());
        eq("no second begin while active", false, s.canBegin(5));
    }

    private static void reseek() {
        SwapRules r = ready(0, 10_000);
        long old = r.target();
        eq("picture passed the place by over 400 ms", Action.RESEEK, r.next(5000, old + SwapRules.STALE_MS + 1, 0, false));
        eq("new target", old + SwapRules.STALE_MS + 1 + SwapRules.LEAD_MS, r.target());
        eq("back to preparing", State.PREPARING, r.state());
        eq("buffer forgotten", false, r.bufferedEnough());
    }

    private static void drift() {
        eq("drift sign", 200L, SwapRules.drift(1000, 1200));
        SwapRules r = ready(0, 10_000);
        r.next(100, r.target() - 100, 0, false);
        r.onRendered();
        eq("second ahead by 200: slow it", Action.SLOW_DOWN, r.next(200, 20_000, 20_200, true));
        eq("speed 0.98", 0.98f, r.nudge());
        eq("second behind by 300: speed it up", Action.SPEED_UP, r.next(300, 20_300, 20_000, true));
        eq("speed 1.02", 1.02f, r.nudge());
        eq("149 ms is within the target", Action.SWAP, r.next(400, 20_000, 20_149, true));
        SwapRules s = ready(0, 10_000);
        s.next(100, s.target() - 100, 0, false);
        eq("aligned but no picture yet: steady", Action.STEADY, s.next(200, 20_000, 20_050, true));
    }
}
