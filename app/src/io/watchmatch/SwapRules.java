package io.watchmatch;

/**
 * The seamless quality swap's decisions (no Android here, SwapRulesCheck runs it). A second player prepares the new
 * quality, muted, beside the one on screen; this says what to do next, from the two positions and what is buffered:
 *
 *   IDLE → PREPARING (until prepared and seeked) → BUFFERING (until enough is buffered ahead of the seek) →
 *   READY (until the picture reaches the second player's place) → ALIGNING (playing, muted, until within TARGET_DRIFT_MS
 * and its first picture is up)
 *   → SWAPPED. Anything can end in ABORTED; cancel (seek, pause, another episode, back) goes back to IDLE.
 */
final class SwapRules {
    enum State { IDLE, PREPARING, BUFFERING, READY, ALIGNING, SWAPPED, ABORTED }
    enum Action { WAIT, START_SECOND, RESEEK, SLOW_DOWN, SPEED_UP, STEADY, SWAP, ABORT }

    /** How far ahead of the picture the second player waits, and how much it must have buffered beyond that. */
    static final long LEAD_MS = 2500, BUFFER_EXTRA_MS = 4000;
    /** The whole preparation (prepared, seeked and buffered) gives up after this; aligning gets its own limit. */
    static final long TIMEOUT_MS = 12_000, ALIGN_TIMEOUT_MS = 4000;
    /** The second player starts this long before the picture reaches its place (its own start-up delay). */
    static final long START_AHEAD_MS = 120;
    /** The picture may pass the second player's place by this much before it is moved ahead again. */
    static final long STALE_MS = 400;
    static final long TARGET_DRIFT_MS = 150;
    static final float SLOW = 0.98f, FAST = 1.02f;
    /** After an abort, no new attempt for this long, doubling per failure up to BACKOFF_MAX_MS. */
    static final long BACKOFF_MS = 30_000, BACKOFF_MAX_MS = 240_000;

    private State state = State.IDLE;
    private long startedAt, alignStartedAt, target, backoffUntil, backoff;
    private boolean prepared, seeked, rendered;
    private long bufferedEnd;
    private float nudge = 1f;
    private int fails;

    State state() {
        return state;
    }

    boolean active() {
        return state == State.PREPARING || state == State.BUFFERING || state == State.READY || state == State.ALIGNING;
    }

    /** The second player's place (ms) it seeks to and waits at. */
    long target() {
        return target;
    }

    /** The playback speed factor now asked of the second player (1 when steady). */
    float nudge() {
        return nudge;
    }

    /** May a swap begin now (idle or ended, and not backing off)? */
    boolean canBegin(long now) {
        return !active() && now >= backoffUntil;
    }

    void begin(long now, long mainPosition) {
        state = State.PREPARING;
        startedAt = now;
        target = mainPosition + LEAD_MS;
        prepared = seeked = rendered = false;
        bufferedEnd = 0;
        nudge = 1f;
    }

    /** The second player is prepared (the caller seeks it to target()). */
    void onPrepared() {
        if (state == State.PREPARING) prepared = true;
    }

    void onSeeked() {
        if (state == State.PREPARING || state == State.BUFFERING) seeked = true;
    }

    /** The second player's first picture is up. */
    void onRendered() {
        rendered = true;
    }

    /** How far (ms from the file's start) the second player has buffered. */
    void onBuffered(long endMs) {
        bufferedEnd = endMs;
    }

    /** Enough ahead of the seek: LEAD_MS and BUFFER_EXTRA_MS beyond where the picture was when it began. */
    boolean bufferedEnough() {
        return bufferedEnd >= target + BUFFER_EXTRA_MS;
    }

    /**
     * The next step. `main`: the picture's position, `second`: the second player's (ms); `secondPlaying`: it was started.
     * RESEEK: the picture went past the place the second player waits at (the caller seeks it to the new target(), and
     * the preparation goes on). The caller does START_SECOND, the nudges (SLOW_DOWN: the second is ahead, SPEED_UP: behind,
     * STEADY: speed back to normal) and SWAP, and on ABORT drops the second player.
     */
    Action next(long now, long main, long second, boolean secondPlaying) {
        switch (state) {
            case PREPARING:
            case BUFFERING:
                if (now - startedAt >= TIMEOUT_MS) return abort(now);
                if (state == State.PREPARING && prepared && seeked) state = State.BUFFERING;
                if (state == State.BUFFERING && bufferedEnough()) state = State.READY;
                return state == State.READY ? next(now, main, second, secondPlaying) : Action.WAIT;
            case READY:
                if (now - startedAt >= TIMEOUT_MS) return abort(now);
                if (main > target + STALE_MS) {
                    target = main + LEAD_MS;
                    seeked = false;
                    rendered = false;
                    bufferedEnd = 0;
                    state = State.PREPARING;
                    prepared = true;
                    return Action.RESEEK;
                }
                if (main >= target - START_AHEAD_MS) {
                    state = State.ALIGNING;
                    alignStartedAt = now;
                    return Action.START_SECOND;
                }
                return Action.WAIT;
            case ALIGNING:
                if (now - alignStartedAt >= ALIGN_TIMEOUT_MS) return abort(now);
                if (!secondPlaying) return Action.WAIT;
                long drift = drift(main, second);
                if (Math.abs(drift) < TARGET_DRIFT_MS && rendered) {
                    nudge = 1f;
                    state = State.SWAPPED;
                    return Action.SWAP;
                }
                if (Math.abs(drift) < TARGET_DRIFT_MS) return Action.STEADY;     // aligned, waiting for its first picture
                nudge = drift > 0 ? SLOW : FAST;
                return drift > 0 ? Action.SLOW_DOWN : Action.SPEED_UP;
            default:
                return Action.WAIT;
        }
    }

    /** Second minus main (ms): positive when the second player is ahead of the picture. */
    static long drift(long main, long second) {
        return second - main;
    }

    private Action abort(long now) {
        state = State.ABORTED;
        backoff = Math.min(BACKOFF_MAX_MS, backoff == 0 ? BACKOFF_MS : backoff * 2);
        backoffUntil = now + backoff;
        fails++;
        return Action.ABORT;
    }

    /** The second player couldn't be made ready (no address, a decoder error): ends the attempt and starts the backoff. */
    void fail(long now) {
        abort(now);
    }

    int failures() {
        return fails;
    }

    /** The swap went through: the failures are forgotten. */
    void succeeded() {
        backoff = 0;
        backoffUntil = 0;
        fails = 0;
        state = State.IDLE;
    }

    /** A seek, a pause, another episode, back: nothing to back off from. */
    void cancel() {
        state = State.IDLE;
        nudge = 1f;
    }
}
