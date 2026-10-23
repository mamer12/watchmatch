package io.watchmatch;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks TurboRules (low-memory rule, cooldown, setting off, which packages may be dropped, the shell line) on a plain JVM.
 * From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/TurboRules.java \
 *       app/test/io/watchmatch/TurboCheck.java && java -cp out/test io.watchmatch.TurboCheck
 */
public final class TurboCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    private static final String MEM = "MemTotal:  2788396 kB\nMemFree:   122136 kB\nMemAvailable:  1342004 kB\nBuffers: 41796 kB\n";
    private static final String LRU = "ACTIVITY MANAGER LRU PROCESSES\n  Activities:\n"
            + "  #41: fg     TOP  LCM 13065:io.watchmatch:home/u0a126 act:activities\n"
            + "  #39: vis    SVC  --- 2882:com.netflix.ninja/u0a58 act:activities|recents\n"
            + "  #38: cch    CAC  --- 13248:io.watchmeplus/u0a127 act:activities|recents\n"
            + "  #37: cch+10 CAC  --- 7046:com.apple.atve.androidtv.appletv/u0a86 act:activities|recents\n"
            + "  #36: cch+11 CACC --- 7047:com.apple.atve.androidtv.appletv:sync/u0a86 act:client\n"
            + "  #33: cch+30 CAC  --- 4378:com.google.android.youtube.tv/u0a56 act:activities|recents\n"
            + "  #32: cch+40 CACC --- 7487:com.android.settings.intelligence/u0a48 act:client\n"
            + "  #30: cch+45 CEM  --- 8480:com.android.keychain/1000\n"
            + "  #29: cch+50 CEM  --- 8481:com.wifi.owner/u0a200\n"
            + "  #28: cch+55 CEM  --- 8482:com.google.android.inputmethod.latin/u0a210\n"
            + "  #27: cch+56 CEM  --- 8483:com.game.big/u0a211\n"
            + "  #26: cch+57 CEM  --- 8484:com.tcl.guard/u0a212\n";

    public static void main(String[] args) {
        long[] mem = TurboRules.parseMeminfo(MEM);
        eq("free parsed", 122136L, mem[0]);
        eq("available parsed", 1342004L, mem[1]);
        eq("no MemAvailable falls back to free", 5000L, TurboRules.parseMeminfo("MemFree: 5000 kB")[1]);
        eq("empty text unknown", -1L, TurboRules.parseMeminfo("")[0]);

        eq("below 600 MB available is low", true, TurboRules.isLow(500000, 500000));
        eq("plenty is not low", false, TurboRules.isLow(900000, 2000000));
        eq("tight free with middling available is low", true, TurboRules.isLow(110000, 1300000));
        eq("tight free with lots available is not low", false, TurboRules.isLow(110000, 2000000));
        eq("unknown available is not low", false, TurboRules.isLow(100, -1));

        eq("runs when on, low, never run", true, TurboRules.shouldRun(true, 1000, 0, 100000, 400000));
        eq("off never runs", false, TurboRules.shouldRun(false, 1000, 0, 100000, 400000));
        eq("cooldown blocks at 59 s", false, TurboRules.shouldRun(true, 100000 + 59000, 100000, 100000, 400000));
        eq("cooldown over at 60 s", true, TurboRules.shouldRun(true, 100000 + 60000, 100000, 100000, 400000));
        eq("not low does not run", false, TurboRules.shouldRun(true, 1000, 0, 900000, 2000000));

        List<String> cached = TurboRules.cachedPackages(LRU);
        eq("cached only, app uids only, once each", Arrays.asList("io.watchmeplus", "com.apple.atve.androidtv.appletv",
                "com.google.android.youtube.tv", "com.android.settings.intelligence", "com.wifi.owner",
                "com.google.android.inputmethod.latin", "com.game.big", "com.tcl.guard"), cached);

        List<String> out = TurboRules.candidates(cached, set("com.tcl.guard"), set("com.wifi.owner"),
                set("io.watchmeplus", "io.watchmatch"), "com.google.android.youtube.tv");
        eq("candidates filtered", Arrays.asList("com.apple.atve.androidtv.appletv", "com.game.big"), out);
        eq("opening app kept", false, out.contains("com.google.android.youtube.tv"));
        eq("wifi owner kept", false, out.contains("com.wifi.owner"));
        eq("protected kept", false, out.contains("com.tcl.guard"));
        eq("input method kept", false, out.contains("com.google.android.inputmethod.latin"));
        eq("our own kept", false, out.contains("io.watchmeplus"));
        eq("bad name dropped", Collections.emptyList(), TurboRules.candidates(Arrays.asList("x; reboot", "a b.c", "pm"),
                set(), set(), set(), null));
        eq("tv pieces are system-like", true, TurboRules.systemLike("org.droidtv.hdmi.x"));
        eq("games are not", false, TurboRules.systemLike("com.game.big"));

        String[] many = new String[40];
        for (int i = 0; i < many.length; i++) many[i] = "com.app.n" + i;
        eq("capped", TurboRules.MAX_KILLS, TurboRules.candidates(Arrays.asList(many), set(), set(), set(), null).size());

        eq("command", "am kill --user 0 com.a.b; am kill --user 0 com.c.d", TurboRules.command(Arrays.asList("com.a.b", "com.c.d")));
        eq("command skips unsafe", "am kill --user 0 com.a.b", TurboRules.command(Arrays.asList("x;reboot", "com.a.b")));
        eq("empty command", "", TurboRules.command(Collections.<String>emptyList()));
        eq("mb", 1310L, TurboRules.mb(1342004));

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
