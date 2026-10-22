package io.watchmatch;

/**
 * Checks the player's failure ladder on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/PlayerFailureRules.java \
 *       app/test/io/watchmatch/PlayerFailureRulesCheck.java && java -cp out/test io.watchmatch.PlayerFailureRulesCheck
 */
public final class PlayerFailureRulesCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) {
        ignored();
        ladder();
        nonTransient();
        neverLoops();
        resets();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void ignored() {
        PlayerFailureRules r = new PlayerFailureRules();
        eq("-38 ignored", false, r.accept(-38, true, 1, 1));
        eq("not the active player", false, r.accept(-110, false, 1, 1));
        eq("older generation", false, r.accept(-110, true, 1, 2));
        eq("first real error taken", true, r.accept(-110, true, 2, 2));
        eq("re-entry refused", false, r.accept(-110, true, 2, 2));
        eq("-38 still ignored while handling", false, r.accept(-38, true, 2, 2));
        r.settled();
        eq("taken again after a new player", true, r.accept(1, true, 2, 2));
    }

    private static void ladder() {
        PlayerFailureRules r = new PlayerFailureRules();
        eq("transient 1", PlayerFailureRules.Step.REOPEN_SAME, r.next(-110, 0, true, true));
        eq("backoff 1", 1000L, r.backoffMs());
        eq("transient 2", PlayerFailureRules.Step.REOPEN_SAME, r.next(-110, 0, true, true));
        eq("backoff 2", 3000L, r.backoffMs());
        eq("then direct", PlayerFailureRules.Step.DIRECT, r.next(-110, 0, true, true));
        eq("then lower", PlayerFailureRules.Step.LOWER_QUALITY, r.next(-110, 0, false, true));
        eq("a fresh ladder for the lower file", PlayerFailureRules.Step.REOPEN_SAME, r.next(-1004, 0, true, true));
        PlayerFailureRules s = new PlayerFailureRules();
        for (int i = 0; i < 2; i++) s.next(-110, 0, false, false);
        eq("nothing lower: give up", PlayerFailureRules.Step.GIVE_UP, s.next(-110, 0, false, false));
    }

    private static void nonTransient() {
        PlayerFailureRules r = new PlayerFailureRules();
        eq("decoder error goes direct first", PlayerFailureRules.Step.DIRECT, r.next(-1007, 0, true, true));
        eq("then lower", PlayerFailureRules.Step.LOWER_QUALITY, r.next(-1007, 0, true, true));
        eq("transient kinds", true, PlayerFailureRules.transientError(-1005, 0));
        eq("unknown is transient", true, PlayerFailureRules.transientError(1, -2147483648));
        eq("malformed is not", false, PlayerFailureRules.transientError(-1007, 0));
    }

    private static void neverLoops() {
        PlayerFailureRules r = new PlayerFailureRules();
        int steps = 0;
        PlayerFailureRules.Step s;
        do {
            s = r.next(-110, 0, true, true);
            steps++;
        } while (s != PlayerFailureRules.Step.GIVE_UP && steps < 1000);
        eq("ends", true, steps <= PlayerFailureRules.MAX_ATTEMPTS + 1);
    }

    private static void resets() {
        PlayerFailureRules r = new PlayerFailureRules();
        r.next(-110, 0, true, true);
        r.next(-110, 0, true, true);
        r.playing(1000, true);
        r.playing(15_000, true);
        eq("15 s is not enough", 2, r.attempts());
        r.playing(16_000, false);
        r.playing(17_000, true);
        r.playing(30_000, true);
        eq("a pause restarts the count", 2, r.attempts());
        r.playing(37_001, true);
        eq("20 s in a row clears it", 0, r.attempts());
        eq("same ladder from the top", PlayerFailureRules.Step.REOPEN_SAME, r.next(-110, 0, true, true));
        r.reset();
        eq("reset", 0, r.attempts());
    }
}
