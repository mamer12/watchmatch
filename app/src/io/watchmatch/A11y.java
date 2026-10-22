package io.watchmatch;

/**
 * Home Settings > Accessibility, as read while making paints and drawing: text size, high contrast, reduce motion.
 * The defaults (1, off, off) draw everything exactly as before. Set once per process (Prefs.init) and again when one
 * changes; the home screen starts over after a change (HomeActivity), so it reads them when it makes its paints.
 */
final class A11y {
    private A11y() {}

    /** Text size factor: 1 (Normal), 1.15 (Large) or 1.3 (Larger). */
    static volatile float text = 1f;
    /** Stronger scrims behind text, brighter secondary text. */
    static volatile boolean contrast;
    /** No Ken Burns, no crossfade or slide on the featured title, springs (focus, scrolling) jump to where they go. */
    static volatile boolean reduceMotion;

    static void apply() {
        String size = Prefs.textSize();
        text = size.equals("larger") ? 1.3f : size.equals("large") ? 1.15f : 1f;
        contrast = Prefs.highContrast();
        reduceMotion = Prefs.reduceMotion();
        Spring.instant = reduceMotion;
    }
}
