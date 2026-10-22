package io.watchmatch;

import java.util.List;

/**
 * Which quality a video starts at (no Android here, StreamRulesCheck runs it). The mirror starts at 480p; the first
 * provider at the highest up to 720p. Both then follow QualityController, until a fixed quality is picked by hand.
 */
final class QualityRules {
    private QualityRules() {}

    static final int START_HEIGHT = 480, MIN_START_HEIGHT = 360;
    /** The first provider's automatic start: the highest up to this (then QualityController moves it). */
    static final int EL_START_MAX = 720;

    /** Only the mirror starts small. */
    static boolean adaptive(String providerId) {
        return Endpoints.OTHER.equals(providerId);
    }

    /**
     * The height to start at among `heights` (any order, none above the person's cap): the remembered manual pick if the
     * file exists, else 480p, else the lowest of at least 360p, else the highest there is. 0 when there are none.
     */
    static int startHeight(List<Integer> heights, int remembered) {
        if (heights.isEmpty()) return 0;
        int lowestAbove = Integer.MAX_VALUE, highest = 0;
        for (int h : heights) {
            if (remembered > 0 && h == remembered) return h;
            highest = Math.max(highest, h);
            if (h >= MIN_START_HEIGHT) lowestAbove = Math.min(lowestAbove, h);
        }
        if (heights.contains(START_HEIGHT)) return START_HEIGHT;
        return lowestAbove != Integer.MAX_VALUE ? lowestAbove : highest;
    }

    /**
     * Where automatic quality starts among `heights` (none above the person's cap): the mirror at 480p (see startHeight
     * with nothing remembered), the first provider at the highest up to 720p (else the lowest there is). 0 for none.
     */
    static int autoStartHeight(String providerId, List<Integer> heights) {
        if (heights.isEmpty()) return 0;
        if (adaptive(providerId)) return startHeight(heights, 0);
        int best = 0, lowest = Integer.MAX_VALUE;
        for (int h : heights) {
            if (h <= EL_START_MAX) best = Math.max(best, h);
            lowest = Math.min(lowest, h);
        }
        return best > 0 ? best : lowest;
    }

    /** The preference key a manual pick is remembered under, per provider. */
    static String rememberKey(String providerId) {
        return "quality:" + providerId;
    }
}
