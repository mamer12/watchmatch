package io.watchmatch;

/**
 * What to do when the player reports an error, as plain Java (PlayerFailureRulesCheck runs it). One instance per
 * video: it makes sure a failure is handled once, never answers errors our own calls caused (-38), and walks a ladder
 * that always ends: reopen the same file (transient errors), the same file straight from the CDN, one quality lower,
 * then the final message with a Retry.
 */
final class PlayerFailureRules {
    /** What the player does next. */
    enum Step { IGNORE, REOPEN_SAME, DIRECT, LOWER_QUALITY, GIVE_UP }

    /** INVALID_OPERATION: the answer to a call of ours on a player that was in error state. Never a new failure. */
    static final int INVALID_OPERATION = -38;
    static final int TIMED_OUT = -110, IO = -1004, TIMED_OUT_IO = -1005, UNKNOWN = 1, SERVER_DIED = 100, UNKNOWN_EXTRA = Integer.MIN_VALUE;
    static final long[] BACKOFF_MS = {1000, 3000};
    /** Continuous playback after which the counters start again. */
    static final long RESET_AFTER_MS = 20_000;
    /** Hard cap on openings after failures in one video, whatever the ladder says. */
    static final int MAX_ATTEMPTS = 8;

    private int sameTries, lowerSteps, attempts;
    private boolean triedDirect, handling;
    private long playingSince = -1;

    /** Errors worth reconnecting for: timeouts, network I/O, and the unknown ones. */
    static boolean transientError(int what, int extra) {
        return what == TIMED_OUT || what == IO || what == TIMED_OUT_IO || what == UNKNOWN || what == UNKNOWN_EXTRA
                || extra == TIMED_OUT || extra == IO || extra == TIMED_OUT_IO || extra == UNKNOWN_EXTRA;
    }

    /** A new video (or a retry by the person): everything starts again. */
    void reset() {
        sameTries = lowerSteps = attempts = 0;
        triedDirect = handling = false;
        playingSince = -1;
    }

    /**
     * Whether an error is to be handled: not -38, not from a player that isn't the one on screen, not from an older
     * video, and not while a failure is already being handled. True sets the re-entry guard.
     */
    boolean accept(int what, boolean fromActivePlayer, int playerGeneration, int currentGeneration) {
        if (what == INVALID_OPERATION || !fromActivePlayer || playerGeneration != currentGeneration || handling) return false;
        handling = true;
        return true;
    }

    /** A new player is up (the failure is dealt with): the next error counts again. */
    void settled() {
        handling = false;
    }

    /** The step after an accepted error. `hasStreamer`: the file came through Streamer; `lowerExists`: a lower quality can be tried. */
    Step next(int what, int extra, boolean hasStreamer, boolean lowerExists) {
        if (++attempts > MAX_ATTEMPTS) return Step.GIVE_UP;
        if (transientError(what, extra) && sameTries < BACKOFF_MS.length) {
            sameTries++;
            return Step.REOPEN_SAME;
        }
        if (!triedDirect && hasStreamer) {
            triedDirect = true;
            return Step.DIRECT;
        }
        if (lowerExists && lowerSteps < 3) {
            lowerSteps++;
            sameTries = 0;
            triedDirect = false;
            return Step.LOWER_QUALITY;
        }
        return Step.GIVE_UP;
    }

    /** The wait before the reopen that REOPEN_SAME asked for. */
    long backoffMs() {
        return BACKOFF_MS[Math.max(0, Math.min(sameTries, BACKOFF_MS.length) - 1)];
    }

    /** Called often with the clock and whether the video is playing: 20 s in a row of playing clears the counters. */
    void playing(long nowMs, boolean isPlaying) {
        if (!isPlaying) {
            playingSince = -1;
            return;
        }
        if (playingSince < 0) playingSince = nowMs;
        if (nowMs - playingSince >= RESET_AFTER_MS) {
            sameTries = lowerSteps = attempts = 0;
            triedDirect = false;
        }
    }

    int attempts() {
        return attempts;
    }
}
