package io.watchmatch;

/**
 * Checks the person's own intro mark (two presses, validation, storage format), on a plain JVM. From the root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/IntroMark.java \
 *       app/test/io/watchmatch/IntroMarkCheck.java && java -cp out/test io.watchmatch.IntroMarkCheck
 */
public final class IntroMarkCheck {
    private static int failures;

    public static void main(String[] args) {
        IntroMark.Press p = IntroMark.press(-1, 62_400);
        eq("first press: start", IntroMark.START_SAVED, p.result);
        eq("start rounded to the second", 62_000L, p.pendingStart);
        IntroMark.Press q = IntroMark.press(p.pendingStart, 140_000);
        eq("second press: saved", IntroMark.SAVED, q.result);
        eq("nothing pending after", -1L, q.pendingStart);
        eq("range", "62-140", IntroMark.encode(q.range));
        eq("too short keeps the start", IntroMark.TOO_SHORT, IntroMark.press(62_000, 65_000).result);
        eq("too short pending", 62_000L, IntroMark.press(62_000, 65_000).pendingStart);
        eq("5 s is enough", IntroMark.SAVED, IntroMark.press(62_000, 67_000).result);
        eq("over 5 min", IntroMark.TOO_LONG, IntroMark.press(0, 301_000).result);
        eq("over 5 min restarts at the press", 301_000L, IntroMark.press(0, 301_000).pendingStart);
        eq("5 min exactly", IntroMark.SAVED, IntroMark.press(0, 300_000).result);
        eq("end before start: new start", IntroMark.START_SAVED, IntroMark.press(100_000, 50_000).result);
        eq("end before start: pending", 50_000L, IntroMark.press(100_000, 50_000).pendingStart);
        eq("start at 0", 0L, IntroMark.press(-1, 100).pendingStart);

        eq("decode", 85_000L, IntroMark.decode("12-85")[1]);
        eq("decode start", 12_000L, IntroMark.decode("12-85")[0]);
        eq("decode too short", null, IntroMark.decode("12-14"));
        eq("decode too long", null, IntroMark.decode("0-400"));
        eq("decode junk", null, IntroMark.decode("a-b"));
        eq("decode none", null, IntroMark.decode(null));
        eq("decode one number", null, IntroMark.decode("12"));
        eq("decode negative", null, IntroMark.decode("-5-20"));

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("IntroMarkCheck passed");
    }

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }
}
