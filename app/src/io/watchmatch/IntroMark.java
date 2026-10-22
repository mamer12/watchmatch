package io.watchmatch;

/**
 * The person's own intro mark for a series: two presses of "Mark intro" (start, then end), kept as whole seconds from
 * the start of the episode and applied to every episode of the series. Pure Java, checked by IntroMarkCheck.
 */
final class IntroMark {
    private IntroMark() {}

    static final long MIN_MS = 5000, MAX_MS = 300_000;
    static final int START_SAVED = 0, SAVED = 1, TOO_SHORT = 2, TOO_LONG = 3;

    /** What a press did, and the start still waiting (-1 none) for the next one. */
    static final class Press {
        final int result;
        final long pendingStart;
        final long[] range;     // {start, end} in ms when SAVED

        Press(int result, long pendingStart, long[] range) {
            this.result = result;
            this.pendingStart = pendingStart;
            this.range = range;
        }
    }

    /** The press at `position` (ms), with the start pressed before it (-1 when none). */
    static Press press(long pendingStart, long position) {
        long at = Math.max(0, Math.round(position / 1000.0) * 1000);
        if (pendingStart < 0 || at <= pendingStart) return new Press(START_SAVED, at, null);
        long d = at - pendingStart;
        if (d < MIN_MS) return new Press(TOO_SHORT, pendingStart, null);
        if (d > MAX_MS) return new Press(TOO_LONG, at, null);
        return new Press(SAVED, -1, new long[] {pendingStart, at});
    }

    /** "12-85" (seconds) for the range in ms. */
    static String encode(long[] range) {
        return range[0] / 1000 + "-" + range[1] / 1000;
    }

    /** The range (ms) of a saved "12-85", or null when it isn't a valid one. */
    static long[] decode(String s) {
        if (s == null) return null;
        String[] p = s.split("-");
        if (p.length != 2) return null;
        try {
            long a = Long.parseLong(p[0]) * 1000, b = Long.parseLong(p[1]) * 1000;
            return a >= 0 && b - a >= MIN_MS && b - a <= MAX_MS ? new long[] {a, b} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
