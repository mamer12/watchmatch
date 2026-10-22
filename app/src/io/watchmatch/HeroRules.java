package io.watchmatch;

/** When a title's page may swap its still picture for the title's video (no Android here). */
final class HeroRules {
    private HeroRules() {}

    /** The picture stays this long, once loaded, before the preview starts inside the same hero. */
    static final long DELAY_MS = 1800;

    /**
     * The preview starts when the picture has loaded and rested DELAY_MS, previews are on, motion isn't reduced, the
     * title has something to play and the player isn't opening.
     */
    static boolean shouldStartPreview(boolean reducedMotion, boolean previewsOff, boolean hasSource, boolean imageLoaded,
            long elapsedMs, boolean playing) {
        if (reducedMotion || previewsOff || !hasSource || playing) return false;
        return imageLoaded && elapsedMs >= DELAY_MS;
    }

    /** No video behind the home page's billboard on the mirror (its connection is better spent on what's played). */
    static boolean billboardPreview(String providerId) {
        return !Endpoints.OTHER.equals(providerId);
    }
}
