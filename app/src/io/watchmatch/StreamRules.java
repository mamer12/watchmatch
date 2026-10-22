package io.watchmatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure part of the mirror's parallel range downloads (Streamer): parsing and splitting byte ranges, mapping the
 * redirecting media host to a file host, and when a signed link runs out. No Android here (StreamRulesCheck runs it).
 */
final class StreamRules {
    private StreamRules() {}

    /** Most bytes answered per response chunk, pieces per chunk, connections open at once. */
    static final long MAX_RESPONSE_BYTES = 8L << 20;
    static final int PIECES = 4, MAX_CONNECTIONS = 4, RETRIES = 1, FILE_HOSTS = 6, HOST_TRIES = 2;
    /** A signed link is reused until it is this close to running out. */
    static final long EXPIRY_MARGIN_MS = 10 * 60_000L;

    private static final Pattern RANGE = Pattern.compile("^\\s*bytes\\s*=\\s*(\\d+)\\s*-\\s*(\\d*)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_RANGE = Pattern.compile("^\\s*bytes\\s+(\\d+)\\s*-\\s*(\\d+)\\s*/\\s*(\\d+|\\*)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_HOST = Pattern.compile("^cndw\\d*$");

    /** A span of bytes, both ends included. */
    static final class Span {
        final long start, end;

        Span(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() { return end - start + 1; }

        @Override public boolean equals(Object o) { return o instanceof Span && ((Span) o).start == start && ((Span) o).end == end; }
        @Override public int hashCode() { return (int) (start * 31 + end); }
        @Override public String toString() { return start + "-" + end; }
    }

    /** `bytes=a-b` or `bytes=a-` (end -1). Suffix, multi-range and malformed values give null: not ours to split. */
    static long[] parseRange(String header) {
        if (header == null) return null;
        Matcher m = RANGE.matcher(header);
        if (!m.matches()) return null;
        try {
            long start = Long.parseLong(m.group(1));
            long end = m.group(2).isEmpty() ? -1 : Long.parseLong(m.group(2));
            if (end >= 0 && end < start) return null;
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The total in a `Content-Range: bytes a-b/total` header, -1 when missing or `*`. */
    static long totalFromContentRange(String header) {
        if (header == null) return -1;
        Matcher m = CONTENT_RANGE.matcher(header);
        if (!m.matches() || m.group(3).equals("*")) return -1;
        try {
            return Long.parseLong(m.group(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The span to answer: at most `max` bytes from `start`, not past `end` (-1: open) nor the last byte of `total`. Null when `start` is past the end. */
    static Span clampSpan(long start, long end, long total, long max) {
        if (total >= 0 && start >= total) return null;
        long last = start + max - 1;
        if (end >= 0) last = Math.min(last, end);
        if (total >= 0) last = Math.min(last, total - 1);
        return new Span(start, last);
    }

    /** `parts` consecutive pieces covering the span exactly; sizes differ by at most one byte and none is empty. */
    static List<Span> split(Span span, int parts) {
        long length = span.length();
        int n = (int) Math.max(1, Math.min(parts, length));
        long base = length / n, extra = length % n, at = span.start;
        List<Span> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long size = base + (i < extra ? 1 : 0);
            out.add(new Span(at, at + size - 1));
            at += size;
        }
        return out;
    }

    // MARK: - Hosts

    private static String hostOf(String link) {
        int s = link.indexOf("://");
        if (s < 0) return null;
        int from = s + 3, to = link.length();
        for (int i = from; i < link.length(); i++) {
            char c = link.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                to = i;
                break;
            }
        }
        return link.substring(from, to).toLowerCase(Locale.US);
    }

    /** True for the redirecting media host (cdn.) or a signed file host (cndwN.) of `base` (the provider's host). */
    static boolean isMediaHost(String link, String base, List<String> mediaHosts) {
        String h = link == null ? null : hostOf(link);
        if (h == null || base == null) return false;
        if (mediaHosts.contains(h)) return true;
        String suffix = "." + base;
        return h.endsWith(suffix) && FILE_HOST.matcher(h.substring(0, h.length() - suffix.length())).matches();
    }

    /**
     * The same signed link on a file host: `cdn.` (which only redirects) becomes `cndwN.`, N picked from the file's path
     * so a file sticks to one host; `attempt` moves to the next one. Links already on a file host come back as they are;
     * anything that isn't the provider's https media host gives null.
     */
    static String fileHostUrl(String link, int attempt, String base, List<String> mediaHosts) {
        if (link == null || !link.startsWith("https://") || base == null) return null;
        String host = hostOf(link);
        if (host == null) return null;
        String suffix = "." + base;
        if (host.endsWith(suffix) && FILE_HOST.matcher(host.substring(0, host.length() - suffix.length())).matches()) return link;
        if (!mediaHosts.contains(host)) return null;
        int hostStart = link.indexOf("://") + 3, hostEnd = hostStart + host.length();
        int pathEnd = link.length();
        for (int i = hostEnd; i < link.length(); i++) {
            char c = link.charAt(i);
            if (c == '?' || c == '#') {
                pathEnd = i;
                break;
            }
        }
        String path = link.substring(hostEnd, pathEnd);
        long h = 0;
        for (int i = 0; i < path.length(); i++) h = (h * 31 + path.charAt(i)) & 0xFFFFFFFFL;
        int n = (int) ((h + attempt) % FILE_HOSTS) + 1;
        return link.substring(0, hostStart) + "cndw" + n + suffix + link.substring(hostEnd);
    }

    /** Parallel fetching runs only for the mirror's media hosts, with the switch on. */
    static boolean parallelWanted(String providerId, boolean enabled, String link, String base, List<String> mediaHosts) {
        return enabled && Endpoints.OTHER.equals(providerId) && isMediaHost(link, base, mediaHosts);
    }

    // MARK: - Signed links

    private static final Pattern EXPIRES = Pattern.compile("[?&]Expires=(\\d{9,11})(?:&|#|$)");
    private static final Pattern AMZ_DATE = Pattern.compile("[?&]X-Amz-Date=(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})Z");
    private static final Pattern AMZ_TTL = Pattern.compile("[?&]X-Amz-Expires=(\\d+)");

    /** Expiry in ms since the epoch from `Expires=<unix seconds>` or `X-Amz-Date` + `X-Amz-Expires`; -1 when unsigned. */
    static long signedExpiry(String link) {
        if (link == null) return -1;
        Matcher e = EXPIRES.matcher(link);
        if (e.find()) return Long.parseLong(e.group(1)) * 1000;
        Matcher d = AMZ_DATE.matcher(link), t = AMZ_TTL.matcher(link);
        if (d.find() && t.find()) {
            java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)) - 1, Integer.parseInt(d.group(3)),
                    Integer.parseInt(d.group(4)), Integer.parseInt(d.group(5)), Integer.parseInt(d.group(6)));
            return c.getTimeInMillis() + Long.parseLong(t.group(1)) * 1000;
        }
        return -1;
    }

    /** True when the link is signed and runs out within `marginMs` (unsigned links never do, as far as we can tell). */
    static boolean isExpiring(String link, long nowMs, long marginMs) {
        long exp = signedExpiry(link);
        return exp >= 0 && exp - nowMs < marginMs;
    }
}
