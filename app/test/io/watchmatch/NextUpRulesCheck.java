package io.watchmatch;

/**
 * Checks when the "Next episode" card shows, on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/NextUpRules.java \
 *       app/test/io/watchmatch/NextUpRulesCheck.java && java -cp out/test io.watchmatch.NextUpRulesCheck
 */
public final class NextUpRulesCheck {
    private static int failures;

    public static void main(String[] args) {
        long min = 60_000;
        long ep22 = 22 * min, ep45 = 45 * min, film = 120 * min;
        eq("22 min card", 1_281_800L, NextUpRules.cardAt(ep22, -1));
        eq("45 min card", 2_613_500L, NextUpRules.cardAt(ep45, -1));
        eq("2 h card (credits capped at 150 s)", 7_058_000L, NextUpRules.cardAt(film, -1));
        eq("22 min: not at 20:00", false, NextUpRules.showing(true, ep22, 20 * min, -1));
        eq("22 min: at 21:30", true, NextUpRules.showing(true, ep22, 21 * min + 30_000, -1));
        eq("movie / last episode never", false, NextUpRules.showing(false, ep22, ep22, -1));
        eq("short clip: only at the end", 30_000L, NextUpRules.cardAt(30_000, -1));
        eq("short clip: not before", false, NextUpRules.showing(true, 30_000, 29_000, -1));
        eq("short clip: at the end", true, NextUpRules.showing(true, 30_000, 30_000, -1));
        eq("3 min clip: estimate, 45 s credits", 143_000L, NextUpRules.cardAt(3 * min, -1));
        eq("catalog credits used", 1_200_000L + 8_000, NextUpRules.cardAt(ep22, 1_200_000));
        eq("catalog credits too early: 60% floor", Math.round(0.6 * ep22), NextUpRules.cardAt(ep22, 100_000));
        eq("catalog credits late: 15 s margin", ep22 - 15_000, NextUpRules.cardAt(ep22, ep22 - 5_000));
        // dismissed: stays hidden from autoplay, and no autoplay at the natural end
        eq("dismissed: no countdown", false, NextUpRules.counts(true, true));
        eq("dismissed: no autoplay at the end", false, NextUpRules.playNextAtEnd(true, true, true));
        eq("autoplay off: no countdown", false, NextUpRules.counts(false, false));
        eq("autoplay off: no autoplay at the end", false, NextUpRules.playNextAtEnd(true, false, false));
        eq("autoplay on: countdown", true, NextUpRules.counts(true, false));
        eq("autoplay on at the end", true, NextUpRules.playNextAtEnd(true, true, false));
        eq("last episode: nothing at the end", false, NextUpRules.playNextAtEnd(false, true, false));
        // seeking back before cardAt hides the card again (the overlay resets the dismissal then)
        eq("seek back hides it", false, NextUpRules.showing(true, ep22, 10 * min, -1));
        // never advances while playing: the countdown is only for the natural end, 5 s long
        eq("countdown length", 5_000L, NextUpRules.COUNTDOWN_AT_END_MS);
        eq("22 min: card shown, still playing, no advance", false, NextUpRules.advance(true, false, true, false, 60_000));
        eq("2 h film card, still playing, no advance", false, NextUpRules.advance(true, false, true, false, 600_000));
        eq("45 min, ended, 4.9 s: not yet", false, NextUpRules.advance(true, true, true, false, 4_900));
        eq("ended, 5 s: advances", true, NextUpRules.advance(true, true, true, false, 5_000));
        eq("ended, dismissed/cancelled: no advance", false, NextUpRules.advance(true, true, true, true, 9_000));
        eq("ended, autoplay off: button only", false, NextUpRules.advance(true, true, false, false, 9_000));
        eq("ended, last episode: nothing", false, NextUpRules.advance(false, true, true, false, 9_000));
        eq("seconds left at start", 5, NextUpRules.secondsLeft(0));
        eq("seconds left mid", 3, NextUpRules.secondsLeft(2_000));
        eq("seconds left at the end", 1, NextUpRules.secondsLeft(4_999));
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("NextUpRulesCheck: all passed");
    }

    private static void eq(String what, Object want, Object got) {
        if (!want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }
}
