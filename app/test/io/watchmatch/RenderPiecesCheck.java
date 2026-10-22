package io.watchmatch;

import java.util.Calendar;

/** Plain-Java checks of Spring, Click's tone synthesis and the sky palette. See SparkQueryCheck for how to run. */
public final class RenderPiecesCheck {
    private static int failed;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        // Spring settles, critically damped does not overshoot, bouncy one does.
        Spring s = new Spring(0.26f, 1f, 0);
        s.target = 1;
        float max = 0;
        int frames = 0;
        while (s.step(1 / 60f) && frames < 600) { max = Math.max(max, s.value); frames++; }
        check(frames < 120 && s.value == 1 && s.velocity == 0, "critical spring settles at target in " + frames + " frames");
        check(max <= 1.0005f, "critical spring does not overshoot");
        Spring b = new Spring(0.26f, 0.5f, 0);
        b.target = 1;
        max = 0;
        while (b.step(1 / 60f)) max = Math.max(max, b.value);
        check(max > 1.05f && b.value == 1, "bouncy spring overshoots then settles");
        Spring.instant = true;
        Spring i = new Spring(0.3f, 1f, 0);
        i.target = 5;
        check(!i.step(0.016f) && i.value == 5, "instant jumps");
        Spring.instant = false;
        Spring z = new Spring(0.3f, 1f, 2);
        check(!z.step(0.016f) && z.settled(), "settled spring reports false");

        // Click's tones.
        byte[] move = Click.Tone.wav(1450, 0.009, 0.030), open = Click.Tone.wav(880, 0.022, 0.090);
        check(move.length == 44 + 2 * 1440 && open.length == 44 + 2 * 4320, "wav lengths");
        check(move[0] == 'R' && move[8] == 'W' && move[36] == 'd', "wav header tags");
        check((move[24] & 255 | (move[25] & 255) << 8 | (move[26] & 255) << 16) == 48000, "sample rate 48000");
        check(move[44] == 0 && move[45] == 0, "starts silent (attack from zero)");
        int peak = 0;
        for (int k = 44; k + 1 < open.length; k += 2) peak = Math.max(peak, Math.abs((short) (open[k] & 255 | open[k + 1] << 8)));
        check(peak > 3000 && peak <= 0.7 * 32767 + 1, "open peak within level " + peak);
        int tail = Math.abs((short) (open[open.length - 2] & 255 | open[open.length - 1] << 8));
        check(tail < 400, "open decays by the end");

        // Sky palette.
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.JUNE, 1, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Shaders.Palette p = new Shaders.Palette();
        Shaders.sky(p, cal.getTimeInMillis(), 0, 0);
        check(p.color(0) == 0xFF1E57C8 && p.color(2) == 0xFF9EE2EE, "noon is DAY");
        cal.set(Calendar.HOUR_OF_DAY, 2);
        Shaders.sky(p, cal.getTimeInMillis(), 0, 0);
        check(p.color(1) == 0xFF16225A, "2am is NIGHT");
        cal.set(Calendar.HOUR_OF_DAY, 17);
        cal.set(Calendar.MINUTE, 40);
        Shaders.sky(p, cal.getTimeInMillis(), 0, 0);
        check(p.color(1) == 0xFFE2546C, "sunset time is SUNSET");
        check(Shaders.timeOfDay(0, 123) == 123, "timeOfDay fallback");
        cal.set(Calendar.HOUR_OF_DAY, 6);
        cal.set(Calendar.MINUTE, 7);
        cal.set(Calendar.SECOND, 9);
        check(Shaders.timeOfDay(cal.getTimeInMillis(), 0) == (6 * 3600 + 7 * 60 + 9) * 1000L, "timeOfDay clock time");
        check(p.set(0xFF0000, 0x00FF00, 0x0000FF).brightest() > 0.71f, "brightest uses luminance");

        System.out.println(failed == 0 ? "all passed" : failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
