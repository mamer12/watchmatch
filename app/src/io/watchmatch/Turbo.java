package io.watchmatch;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Turbo: right before an app or a video opens, asks Android to drop the cached background processes, so the thing the
 * person opens has memory to run in. It uses the TV's own ADB on loopback (the same channel as HomeMode), because an app
 * can't list other apps' processes. Only `am kill` on cached app processes (TurboRules): never the app in use, ours,
 * HomeMode.PROTECTED, a Wi-Fi owner or the TV's own pieces; nothing is force-stopped, disabled, cleared or uninstalled.
 * Always off the UI thread and in parallel with the launch: it never delays it.
 */
final class Turbo {
    private Turbo() {}

    interface Done { void result(long freedMb, long availableMb, String error); }

    private static final Executor WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "turbo");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(HomeMode.PKG, CinemaActivity.STANDALONE));
    private static final long OWNERS_TTL_MS = 10 * 60000;
    private static final int SETTLE_MS = 700;
    private static final Object LOCK = new Object();
    private static long lastRun, ownersAt;           // elapsedRealtime; 0 = never
    private static boolean busy;
    private static Set<String> owners = new HashSet<>();

    static boolean enabled() {
        return !"off".equals(Prefs.turbo());
    }

    /** Call just before opening an app or a video (any thread, never blocks). `opening` is the package about to open, or null. */
    static void before(Context c, String opening) {
        if (!enabled()) return;
        final Context app = c.getApplicationContext();
        final long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (busy || (lastRun > 0 && now - lastRun < TurboRules.COOLDOWN_MS)) return;
            busy = true;
        }
        WORK.execute(() -> auto(app, opening));
    }

    /** The Settings button: runs now whatever the setting and the cooldown say, and tells the result off the UI thread. */
    static void now(Context c, Done done) {
        final Context app = c.getApplicationContext();
        synchronized (LOCK) {
            if (busy) {
                done.result(0, TurboRules.mb(TurboRules.parseMeminfo(meminfo())[1]), null);
                return;
            }
            busy = true;
        }
        WORK.execute(() -> {
            try {
                long[] before = TurboRules.parseMeminfo(meminfo());
                String error = null;
                try {
                    drop(app, null);
                } catch (IOException e) {
                    error = e.getMessage();
                }
                long[] after = TurboRules.parseMeminfo(meminfo());
                done.result(Math.max(0, TurboRules.mb(after[1] - before[1])), TurboRules.mb(after[1]), error);
            } finally {
                finish();
            }
        });
    }

    private static void auto(Context app, String opening) {
        try {
            long[] before = TurboRules.parseMeminfo(meminfo());
            long now = SystemClock.elapsedRealtime();
            if (!TurboRules.shouldRun(enabled(), now, lastRunValue(), before[0], before[1])) return;
            try {
                int n = drop(app, opening);
                long[] after = TurboRules.parseMeminfo(meminfo());
                AppLog.w("turbo: freed " + Math.max(0, TurboRules.mb(after[1] - before[1])) + " MB (" + TurboRules.mb(after[1])
                        + " MB available now, " + n + " apps dropped)");
            } catch (IOException e) {
                AppLog.w("turbo: " + e.getMessage());
            }
        } finally {
            finish();
        }
    }

    private static long lastRunValue() {
        synchronized (LOCK) { return lastRun; }
    }

    private static void finish() {
        synchronized (LOCK) {
            lastRun = SystemClock.elapsedRealtime();     // a failed run waits out the cooldown too
            busy = false;
        }
    }

    /** Drops the allowed cached processes; how many were asked to go. */
    private static int drop(Context app, String opening) throws IOException {
        try (Adb adb = Adb.connect(app, 2000, null)) {
            List<String> cached = TurboRules.cachedPackages(adb.shell("dumpsys activity lru"));
            List<String> pkgs = TurboRules.candidates(cached, HomeMode.PROTECTED, wifiOwners(adb), NEVER, opening);
            String command = TurboRules.command(pkgs);
            if (command.isEmpty()) return 0;
            adb.shell(command);
            SystemClock.sleep(SETTLE_MS);                // let the kernel take the pages back before measuring
            return pkgs.size();
        }
    }

    /** Wi-Fi owners change rarely and `dumpsys wifi` is slow: asked at most every ten minutes. */
    private static Set<String> wifiOwners(Adb adb) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (ownersAt == 0 || now - ownersAt > OWNERS_TTL_MS) {
            owners = HomeMode.wifiOwners(adb);
            ownersAt = now;
        }
        return owners;
    }

    private static String meminfo() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            for (int i = 0; i < 6 && (line = r.readLine()) != null; i++) sb.append(line).append('\n');
        } catch (IOException e) {
            return "";
        }
        return sb.toString();
    }
}
