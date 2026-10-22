package io.watchmatch;

/**
 * The number under the loading spinner ("37%"): what has arrived ahead of the place, over what is wanted before the
 * picture can run (6 s of the file). Pure Java (LoadPercentCheck runs it). Within one loading episode it never goes
 * down, rises at most MAX_STEP points a tick, and stays at 99 or less: only the spinner leaving says it is done.
 */
final class LoadPercent {
    static final long FALLBACK_TARGET_BYTES = 3L << 20;
    static final int TARGET_SECONDS = 6, MAX_STEP = 8, CAP = 99;
    /** Minimum time between updates (the overlay's tick). */
    static final long TICK_MS = 250;

    private int value;

    /** Bytes to have ahead: 6 s at the file's average rate (size ÷ duration); 3 MB when either is unknown. */
    static long targetBytes(long totalBytes, long durationMs) {
        if (totalBytes <= 0 || durationMs <= 0) return FALLBACK_TARGET_BYTES;
        return Math.max(1, totalBytes * TARGET_SECONDS * 1000L / durationMs);
    }

    /** A new load, a seek into unbuffered data or a re-buffering starts again from zero. */
    void reset() {
        value = 0;
    }

    int value() {
        return value;
    }

    /** One tick: `ahead` and `target` in the same unit (bytes, or ms). Returns the number to show. */
    int update(long ahead, long target) {
        long raw = target <= 0 ? 0 : Math.max(0, Math.min(CAP, ahead * 100 / target));
        int next = (int) Math.min(raw, (long) value + MAX_STEP);
        if (next > value) value = next;
        return value;
    }
}
