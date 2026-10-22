package io.watchmatch;

/**
 * Which picture file a card asks for and how many download at once (no Android here, WatchmeRulesCheck runs it).
 */
final class ImageRules {
    private ImageRules() {}

    /** Downloads at once: the mirror's picture host is slow, so fewer, in the order they were asked. */
    static int slots(String providerId) {
        return Endpoints.OTHER.equals(providerId) ? 4 : 8;
    }

    /** Downloaded pictures are kept this long (thumbnails are small and every row asks for them again). */
    static final long KEEP_MS = 30L * 86_400_000L;

    /**
     * The card picture: on the mirror the small poster (`..._poster_medium_thumb`, about 30 KB), then the full poster
     * and the tiny thumbnail; elsewhere the full poster first, as before. Empty strings and nulls are skipped.
     */
    static String cardPoster(String providerId, String full, String medium, String tiny) {
        String[] order = Endpoints.OTHER.equals(providerId) ? new String[] {medium, full, tiny} : new String[] {full, medium, tiny};
        for (String s : order) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    /** At most `max` holders at once, first come first served; `max` is read at every acquire (the provider can change). */
    static final class Gate {
        private int active;

        synchronized void acquire(int max) throws InterruptedException {
            while (active >= Math.max(1, max)) wait();
            active++;
        }

        synchronized void release() {
            active--;
            notifyAll();
        }

        synchronized int active() { return active; }
    }
}
