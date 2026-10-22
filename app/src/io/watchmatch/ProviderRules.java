package io.watchmatch;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The decisions behind Provider, with no Android in them (so ProviderRulesCheck runs them on a plain JVM): which
 * provider a detection round picks, when it may switch, the retry waits, and artwork host rewriting.
 */
final class ProviderRules {
    private ProviderRules() {}

    static final long PROBE_TIMEOUT_MS = 3500, GRACE_MS = 1500, SWITCH_MIN_GAP_MS = 60_000, AWAY_RECHECK_MS = 30 * 60_000L;
    static final long[] RETRY_MS = {2000, 6000, 15000};
    /** Failed catalog requests in a row on the active provider that trigger a re-detection. */
    static final int FAILURES_TO_REDETECT = 2;
    /** A round still waiting for an answer / with no provider answering. */
    static final String WAIT = "?", NONE = "";

    /** The wait before retry number `index` (0 based) after a round where nothing answered; -1 when they are used up. */
    static long retryDelay(int index) {
        return index >= 0 && index < RETRY_MS.length ? RETRY_MS[index] : -1;
    }

    /** The providers worth probing, in priority order. */
    static List<Endpoints.Entry> usable(List<Endpoints.Entry> all) {
        List<Endpoints.Entry> out = new java.util.ArrayList<>();
        for (Endpoints.Entry e : all) if (e.usable()) out.add(e);
        return out;
    }

    /**
     * One detection round: probe answers come in with `result`, `decide` says when to pick. The best answer wins at once
     * when every provider above it has failed, else GRACE_MS after the first success.
     */
    static final class Round {
        private final List<String> order;
        private final Map<String, Boolean> ok = new HashMap<>();
        private long firstOkAt = -1;

        Round(List<String> order) {
            this.order = order;
        }

        synchronized void result(String id, boolean good, long now) {
            if (ok.containsKey(id)) return;
            ok.put(id, good);
            if (good && firstOkAt < 0) firstOkAt = now;
        }

        /** The id to use, WAIT while a better answer may still come, NONE when every probe failed. */
        synchronized String decide(long now) {
            if (order.isEmpty()) return NONE;
            int best = -1;
            boolean above = true;      // every provider before `best` has failed
            for (int i = 0; i < order.size(); i++) {
                Boolean r = ok.get(order.get(i));
                if (r != null && r) {
                    best = i;
                    break;
                }
                if (r == null) above = false;
            }
            if (best >= 0) return above || now - firstOkAt >= GRACE_MS ? order.get(best) : WAIT;
            return ok.size() == order.size() ? NONE : WAIT;
        }

        /** When `decide` should be asked again for the grace to end (-1: no success yet). */
        synchronized long graceEnds() {
            return firstOkAt < 0 ? -1 : firstOkAt + GRACE_MS;
        }

        synchronized boolean ok(String id) {
            Boolean r = ok.get(id);
            return r != null && r;
        }
    }

    /**
     * Whether a round that picked `picked` may make it the provider in use: the same one always; another one when the
     * person pressed Re-check, or the last switch was more than SWITCH_MIN_GAP_MS ago (lastSwitch <= 0: never).
     */
    static boolean mayApply(String picked, String active, boolean manual, long now, long lastSwitch) {
        if (picked == null || picked.isEmpty() || picked.equals(active)) return true;
        return manual || lastSwitch <= 0 || now - lastSwitch >= SWITCH_MIN_GAP_MS || now < lastSwitch;
    }

    /** Coming back to the home screen after this long away runs a new round. */
    static boolean awayTooLong(long pausedAt, long now) {
        return pausedAt > 0 && now - pausedAt > AWAY_RECHECK_MS;
    }

    static boolean failureTrips(int streak) {
        return streak >= FAILURES_TO_REDETECT;
    }

    /** A request error that says "the server can't be reached" (not an HTTP answer such as a 404 for one title). */
    static boolean isNetworkFailure(String message) {
        return message == null || !message.startsWith("HTTP ") || message.startsWith("HTTP 5");
    }

    // MARK: - Artwork hosts

    private static final String[] ALIAS_DOMAINS = {"shabakaty.cc"};

    private static String domainOf(String host) {
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(dot + 1) : host;
    }

    private static boolean isThumbLabel(String host) {
        int dot = host.indexOf('.');
        if (dot <= 4 || !host.startsWith("cnth")) return false;
        for (int i = 4; i < dot; i++) if (!Character.isDigit(host.charAt(i))) return false;
        return true;
    }

    /**
     * `url` pointed at the artwork server of `active`: a link on any other provider's thumbnail host (cnth*, in the
     * families of the known image hosts) is moved to the first image host of `active`. Anything else, and every link
     * when `active` has no image host, stays as it is. The first provider's own links are never touched.
     */
    static String rewriteImage(String url, Endpoints.Entry active, List<Endpoints.Entry> all) {
        if (url == null || active == null) return url;
        String target = active.imageHost();
        if (target.isEmpty()) return url;
        int s = url.indexOf("://");
        if (s < 0 || s > 5) return url;
        int hostEnd = url.length();
        for (int i = s + 3; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == ':' || c == '?' || c == '#') {
                hostEnd = i;
                break;
            }
        }
        String host = url.substring(s + 3, hostEnd).toLowerCase(Locale.US);
        if (host.equals(target) || !isThumbLabel(host)) return url;
        String domain = domainOf(host);
        // The first provider's links stay as its server sends them: only another provider's hosts move to it.
        boolean mine = active.id.equals(Endpoints.EL);
        boolean move = mine ? fromOtherProvider(domain, active, all) : domain.equals(domainOf(target)) || inFamily(domain, all);
        return move ? rewritten(url, s + 3, hostEnd, target) : url;
    }

    private static String rewritten(String url, int from, int to, String host) {
        return url.substring(0, from) + host + url.substring(to);
    }

    private static boolean inFamily(String domain, List<Endpoints.Entry> all) {
        for (String a : ALIAS_DOMAINS) if (domain.equals(a)) return true;
        for (Endpoints.Entry e : all) for (String h : e.imageHosts) if (domain.equals(domainOf(h))) return true;
        return false;
    }

    /** True when `domain` belongs to a provider other than `active` (and is not an alias of the active one's). */
    private static boolean fromOtherProvider(String domain, Endpoints.Entry active, List<Endpoints.Entry> all) {
        for (Endpoints.Entry e : all) {
            if (e.id.equals(active.id)) continue;
            for (String h : e.imageHosts) if (domain.equals(domainOf(h))) return true;
        }
        return false;
    }
}
