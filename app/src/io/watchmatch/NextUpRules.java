package io.watchmatch;

/**
 * When the "Next episode" card shows. The catalog has no credits marker, so the credits are estimated from the length:
 * 3.5% of it, between 45 s and 150 s, at the end. The card comes a few seconds into them, never before 60% of the
 * video and never later than 15 s from the end; a very short video shows it at the end. Pure Java, checked on a
 * plain JVM by NextUpRulesCheck.
 */
final class NextUpRules {
    static final long MIN_CREDITS_MS = 45_000, MAX_CREDITS_MS = 150_000, CARD_DELAY_MS = 8_000, END_MARGIN_MS = 15_000;
    static final float CREDITS_SHARE = 0.035f, EARLIEST_SHARE = 0.6f;

    private NextUpRules() {
    }

    /** Where the credits start (ms): the catalog's own (creditsStart >= 0) or the estimate. */
    static long creditsStart(long duration, long creditsStart) {
        if (creditsStart >= 0 && creditsStart < duration) return creditsStart;
        long len = Math.max(MIN_CREDITS_MS, Math.min(MAX_CREDITS_MS, Math.round(CREDITS_SHARE * duration)));
        return duration - len;
    }

    /** The position (ms) the card appears at; the duration itself when the video is too short for it. */
    static long cardAt(long duration, long creditsStart) {
        long lo = Math.round(EARLIEST_SHARE * duration), hi = duration - END_MARGIN_MS;
        if (hi < lo) return duration;
        return Math.max(lo, Math.min(hi, creditsStart(duration, creditsStart) + CARD_DELAY_MS));
    }

    /** The card shows: an episode with one after it, once past cardAt. Movies and the last episode never. */
    static boolean showing(boolean hasNext, long duration, long position, long creditsStart) {
        return hasNext && duration > 0 && position >= cardAt(duration, creditsStart);
    }

    /** The countdown may run: Autoplay is on and the card wasn't dismissed. */
    static boolean counts(boolean autoplay, boolean dismissed) {
        return autoplay && !dismissed;
    }

    /** At the natural end: play the next one only if there is one, Autoplay is on and the card wasn't dismissed. */
    static boolean playNextAtEnd(boolean hasNext, boolean autoplay, boolean dismissed) {
        return hasNext && counts(autoplay, dismissed);
    }
}
