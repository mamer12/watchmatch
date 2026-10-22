package io.watchmatch;

import android.os.SystemClock;

/**
 * The wait before trying again something that failed (a row that came back empty, a server that didn't answer):
 * 5 s, 15 s, 45 s, 2 min, then every 10 min. Counted on the time since boot, which a wrong or corrected clock
 * doesn't move. A network coming up or the clock being set starts it over (reset).
 */
final class Backoff {
    private static final long[] STEPS = {5_000, 15_000, 45_000, 120_000, 600_000};

    private int failures;
    private long failedAt;

    synchronized void failed() {
        failures++;
        failedAt = SystemClock.elapsedRealtime();
    }

    synchronized void succeeded() {
        failures = 0;
    }

    /** Try again right away (the network came up, the clock was set). */
    synchronized void reset() {
        failures = 0;
    }

    synchronized boolean failing() {
        return failures > 0;
    }

    /** Still inside the wait after the last failure. */
    synchronized boolean waiting() {
        return failures > 0 && SystemClock.elapsedRealtime() - failedAt < delay();
    }

    /** The wait after the last failure (0 when nothing failed). */
    synchronized long delay() {
        return delayFor(failures);
    }

    /** The wait after `failures` failures in a row (0 when none); pure, so it can be checked on a plain JVM. */
    static long delayFor(int failures) {
        return failures <= 0 ? 0 : STEPS[Math.min(failures, STEPS.length) - 1];
    }
}
