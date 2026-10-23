package io.watchmatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turbo's decisions, with no Android in them (checked on a JVM by TurboCheck): when memory counts as low, the cooldown,
 * which cached processes may be asked to go, and the one shell line that does it. It only ever drops cached background
 * processes with `am kill`: nothing is force-stopped, disabled, cleared or uninstalled.
 */
final class TurboRules {
    private TurboRules() {}

    static final long LOW_AVAILABLE_KB = 600L * 1024;      // MemAvailable under this: low
    static final long TIGHT_FREE_KB = 160L * 1024;         // MemFree under this ...
    static final long TIGHT_AVAILABLE_KB = 1500L * 1024;   // ... with MemAvailable under this: low too (the TV is swapping caches)
    static final long COOLDOWN_MS = 60000;
    static final int MAX_KILLS = 25;

    private static final Pattern MEM_FREE = Pattern.compile("MemFree:\\s+(\\d+) kB");
    private static final Pattern MEM_AVAILABLE = Pattern.compile("MemAvailable:\\s+(\\d+) kB");
    // `dumpsys activity lru`: "#38: cch+10 CAC  --- 13248:io.watchmeplus/u0a127 act:..." (only app uids, only cached)
    private static final Pattern LRU = Pattern.compile("#\\s*\\d+:\\s+cch\\S*\\s+\\S+\\s+\\S+\\s+\\d+:([\\w.]+)(?::[\\w.]+)?/u0a\\d+");
    private static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-zA-Z0-9_]+)+");
    /** Name parts that mean the TV itself: never touched even if cached. */
    private static final String[] SYSTEM_HINTS = {"inputmethod", "launcher", "systemui", "tvinput", "tuner", "livetv", "hdmi",
        ".cec", "tv.settings", "setupwraith", "setupwizard", "dolby", "bluetooth", "provider"};

    /** {MemFree, MemAvailable} in kB from /proc/meminfo text; -1 for a value that isn't there (MemAvailable then = MemFree). */
    static long[] parseMeminfo(String text) {
        long free = first(MEM_FREE, text), avail = first(MEM_AVAILABLE, text);
        return new long[] {free, avail < 0 ? free : avail};
    }

    private static long first(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static boolean isLow(long freeKb, long availableKb) {
        if (availableKb < 0) return false;                 // unknown: leave it alone
        if (availableKb < LOW_AVAILABLE_KB) return true;
        return freeKb >= 0 && freeKb < TIGHT_FREE_KB && availableKb < TIGHT_AVAILABLE_KB;
    }

    /** Whether an automatic run goes ahead: the setting is on, the cooldown is over and memory is low. */
    static boolean shouldRun(boolean enabled, long now, long lastRun, long freeKb, long availableKb) {
        if (!enabled) return false;
        if (lastRun > 0 && now - lastRun < COOLDOWN_MS) return false;
        return isLow(freeKb, availableKb);
    }

    /** The cached app packages in an LRU dump, once each, in the dump's order. */
    static List<String> cachedPackages(String lru) {
        List<String> out = new ArrayList<>();
        Matcher m = LRU.matcher(lru == null ? "" : lru);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /** Which of the cached packages may be dropped: never ours, the one being opened, the protected, Wi-Fi owners or the TV's own. */
    static List<String> candidates(List<String> cached, Collection<String> protectedPkgs, Collection<String> wifiOwners,
                                   Collection<String> never, String opening) {
        List<String> out = new ArrayList<>();
        for (String p : cached) {
            if (out.size() >= MAX_KILLS) break;
            if (!PACKAGE.matcher(p).matches() || p.equals(opening)) continue;
            if (protectedPkgs.contains(p) || wifiOwners.contains(p) || never.contains(p) || systemLike(p)) continue;
            out.add(p);
        }
        return out;
    }

    static boolean systemLike(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String h : SYSTEM_HINTS) if (p.contains(h)) return true;
        return p.equals("android") || p.startsWith("android.") || p.startsWith("com.android.");
    }

    /** One shell line: `am kill` for each package (it only kills what is safe to kill, i.e. cached). Empty if none. */
    static String command(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (!PACKAGE.matcher(p).matches()) continue;      // it goes into a shell command
            if (sb.length() > 0) sb.append("; ");
            sb.append("am kill --user 0 ").append(p);
        }
        return sb.toString();
    }

    static long mb(long kb) {
        return kb < 0 ? -1 : kb / 1024;
    }
}
