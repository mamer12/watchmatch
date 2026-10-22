package io.watchmatch;

/**
 * Decides, silently, which quality a video plays at (no Android here, QualityControllerCheck runs it). The player feeds
 * it the measured download speed and, once a second, how much is buffered ahead; it answers with the one step to take
 * (or none). The files are in `heights` from the lowest to the highest; their bitrates (bytes per second, file size ÷
 * duration) arrive as the player learns them, 0 while unknown.
 *
 * Down: at once when under DOWN_AHEAD_S ahead and falling, or when the speed is under bitrate × DOWN_FACTOR. Up: only
 * after UP_STABLE_MS with at least UP_AHEAD_S ahead, one step, when the speed carries the next file with UP_FACTOR to
 * spare. At least COOLDOWN_MS between two changes. A quality picked by hand turns all of it off (setFixed).
 */
final class QualityController {
    static final double UP_FACTOR = 1.4, DOWN_FACTOR = 1.1, DOWN_AHEAD_S = 8, UP_AHEAD_S = 20, EWMA = 0.3;
    static final long UP_STABLE_MS = 25_000, COOLDOWN_MS = 15_000;
    /** Ahead must have dropped by at least this much since the last tick to count as falling. */
    static final double FALLING_S = 0.25;

    private final int[] heights;
    private final double[] bitrate;
    private final int top;
    private int current;
    private boolean fixed;
    private double throughput = -1, lastAhead = -1;
    private long lastSwitch, healthySince = -1;

    /** `cap`: the highest height allowed (the Settings quality); at least the lowest file stays allowed. */
    QualityController(int[] heights, int cap, int startIndex, long nowMs) {
        this.heights = heights.clone();
        this.bitrate = new double[heights.length];
        int t = 0;
        for (int i = 0; i < heights.length; i++) if (heights[i] <= cap) t = i;
        this.top = t;
        this.current = Math.max(0, Math.min(startIndex, heights.length - 1));
        this.lastSwitch = nowMs;
    }

    int current() {
        return current;
    }

    boolean fixed() {
        return fixed;
    }

    double throughput() {
        return throughput;
    }

    /** A quality picked by hand (true) turns automatic changes off until Auto is chosen again (false). */
    void setFixed(boolean v, long nowMs) {
        fixed = v;
        healthySince = -1;
        lastAhead = -1;
        if (!v) lastSwitch = nowMs;
    }

    void setBitrate(int index, double bytesPerSecond) {
        if (index >= 0 && index < bitrate.length && bytesPerSecond > 0) bitrate[index] = bytesPerSecond;
    }

    double bitrate(int index) {
        return index >= 0 && index < bitrate.length ? bitrate[index] : 0;
    }

    /** A download speed sample (bytes per second): smoothed with an EWMA. */
    void onThroughput(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return;
        throughput = throughput < 0 ? bytesPerSecond : throughput + EWMA * (bytesPerSecond - throughput);
    }

    /** The player moved to `index` (by this controller's answer or from outside): the cooldown starts. */
    void switched(int index, long nowMs) {
        current = Math.max(0, Math.min(index, heights.length - 1));
        lastSwitch = nowMs;
        healthySince = -1;
        lastAhead = -1;
    }

    /** The file index to move to (one step from the current), or -1 to stay. `aheadS`: seconds buffered ahead of the picture. */
    int tick(long nowMs, double aheadS) {
        boolean falling = lastAhead >= 0 && aheadS < lastAhead - FALLING_S;
        lastAhead = aheadS;
        if (aheadS >= UP_AHEAD_S) {
            if (healthySince < 0) healthySince = nowMs;
        } else {
            healthySince = -1;
        }
        if (fixed || heights.length < 2 || nowMs - lastSwitch < COOLDOWN_MS) return -1;
        double now = bitrate[current];
        boolean starving = aheadS < DOWN_AHEAD_S && falling;
        boolean tooSlow = throughput > 0 && now > 0 && throughput < now * DOWN_FACTOR;
        if ((starving || tooSlow) && current > 0) return current - 1;
        if (current < top && healthySince >= 0 && nowMs - healthySince >= UP_STABLE_MS) {
            double next = bitrate[current + 1];
            if (next > 0 && throughput > 0 && next * UP_FACTOR <= throughput) return current + 1;
        }
        return -1;
    }
}
