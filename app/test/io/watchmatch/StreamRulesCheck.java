package io.watchmatch;

import java.util.Arrays;
import java.util.List;

/**
 * Checks the mirror's range splitting, file-host mapping, signed-link expiry and start quality on a plain JVM. From the
 * repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/{Endpoints,ProviderRules,StreamRules,QualityRules}.java \
 *       app/test/io/watchmatch/StreamRulesCheck.java && java -cp out/test io.watchmatch.StreamRulesCheck
 */
public final class StreamRulesCheck {
    private static int passed, failed;
    private static final List<String> MEDIA = Arrays.asList("cdn.cee.buzz");

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) {
        ranges();
        hosts();
        signed();
        quality();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void ranges() {
        eq("open range", Arrays.asList(0L, -1L), toList(StreamRules.parseRange("bytes=0-")));
        eq("closed range", Arrays.asList(5L, 9L), toList(StreamRules.parseRange(" bytes = 5 - 9 ")));
        eq("suffix is not ours", null, StreamRules.parseRange("bytes=-500"));
        eq("multi is not ours", null, StreamRules.parseRange("bytes=0-1,5-6"));
        eq("backwards", null, StreamRules.parseRange("bytes=9-5"));
        eq("none", null, StreamRules.parseRange(null));
        eq("content-range total", 1000L, StreamRules.totalFromContentRange("bytes 0-0/1000"));
        eq("content-range star", -1L, StreamRules.totalFromContentRange("bytes 0-0/*"));
        eq("content-range junk", -1L, StreamRules.totalFromContentRange("nope"));

        long mb = 1L << 20, total = 100 * mb;
        eq("open range cut to 8 MB", new StreamRules.Span(0, 8 * mb - 1), StreamRules.clampSpan(0, -1, total, StreamRules.MAX_RESPONSE_BYTES));
        eq("closed range kept", new StreamRules.Span(10, 19), StreamRules.clampSpan(10, 19, total, StreamRules.MAX_RESPONSE_BYTES));
        eq("clamped to the last byte", new StreamRules.Span(total - 5, total - 1),
                StreamRules.clampSpan(total - 5, -1, total, StreamRules.MAX_RESPONSE_BYTES));
        eq("past the end", null, StreamRules.clampSpan(total, -1, total, StreamRules.MAX_RESPONSE_BYTES));
        eq("unknown total", new StreamRules.Span(7, 7 + 8 * mb - 1), StreamRules.clampSpan(7, -1, -1, StreamRules.MAX_RESPONSE_BYTES));

        List<StreamRules.Span> parts = StreamRules.split(new StreamRules.Span(0, 8 * mb - 1), 4);
        eq("4 pieces", 4, parts.size());
        eq("equal pieces", 2 * mb, parts.get(0).length());
        // Pieces cover the span exactly, in order, no gaps, sizes within one byte.
        for (long len : new long[] {1, 2, 3, 4, 5, 7, 1001, 8 * mb}) {
            StreamRules.Span s = new StreamRules.Span(1000, 1000 + len - 1);
            List<StreamRules.Span> p = StreamRules.split(s, 4);
            long at = s.start, min = Long.MAX_VALUE, max = 0;
            boolean contiguous = true;
            for (StreamRules.Span x : p) {
                contiguous &= x.start == at && x.length() > 0;
                at = x.end + 1;
                min = Math.min(min, x.length());
                max = Math.max(max, x.length());
            }
            eq("covers " + len, true, contiguous && at == s.end + 1 && max - min <= 1);
            eq("count " + len, (int) Math.min(4, len), p.size());
        }
    }

    private static List<Long> toList(long[] a) {
        if (a == null) return null;
        return Arrays.asList(a[0], a[1]);
    }

    private static void hosts() {
        String link = "https://cdn.cee.buzz/files/movie.mp4?Expires=1900000000&Signature=abc";
        String one = StreamRules.fileHostUrl(link, 0, "cee.buzz", MEDIA);
        eq("rewritten to a file host", true, one != null && one.matches("https://cndw[1-6]\\.cee\\.buzz/files/movie\\.mp4\\?Expires=1900000000&Signature=abc"));
        eq("stable per file", one, StreamRules.fileHostUrl(link, 0, "cee.buzz", MEDIA));
        String two = StreamRules.fileHostUrl(link, 1, "cee.buzz", MEDIA);
        eq("next attempt, next host", true, two != null && !two.equals(one));
        eq("six hosts then around", one, StreamRules.fileHostUrl(link, 6, "cee.buzz", MEDIA));
        eq("already a file host", "https://cndw2.cee.buzz/a.mp4", StreamRules.fileHostUrl("https://cndw2.cee.buzz/a.mp4", 0, "cee.buzz", MEDIA));
        eq("other host", null, StreamRules.fileHostUrl("https://example.com/a.mp4", 0, "cee.buzz", MEDIA));
        eq("not https", null, StreamRules.fileHostUrl("http://cdn.cee.buzz/a.mp4", 0, "cee.buzz", MEDIA));
        eq("different files can differ", true, !StreamRules.fileHostUrl("https://cdn.cee.buzz/a.mp4", 0, "cee.buzz", MEDIA)
                .equals(StreamRules.fileHostUrl("https://cdn.cee.buzz/b.mp4", 0, "cee.buzz", MEDIA))
                || !StreamRules.fileHostUrl("https://cdn.cee.buzz/a.mp4", 0, "cee.buzz", MEDIA)
                .equals(StreamRules.fileHostUrl("https://cdn.cee.buzz/c.mp4", 0, "cee.buzz", MEDIA)));
        eq("media host cdn", true, StreamRules.isMediaHost("https://cdn.cee.buzz/a", "cee.buzz", MEDIA));
        eq("media host cndw", true, StreamRules.isMediaHost("https://cndw4.cee.buzz/a", "cee.buzz", MEDIA));
        eq("api host is not media", false, StreamRules.isMediaHost("https://cee.buzz/api/", "cee.buzz", MEDIA));
        eq("image host is not media", false, StreamRules.isMediaHost("https://cnth2.cee.buzz/a.jpg", "cee.buzz", MEDIA));
        eq("parallel on the mirror", true, StreamRules.parallelWanted("other", true, "https://cndw1.cee.buzz/a", "cee.buzz", MEDIA));
        eq("parallel off by the switch", false, StreamRules.parallelWanted("other", false, "https://cndw1.cee.buzz/a", "cee.buzz", MEDIA));
        eq("parallel never on EL", false, StreamRules.parallelWanted("el", true, "https://cndw1.cee.buzz/a", "cee.buzz", MEDIA));
        eq("parallel not for other hosts", false, StreamRules.parallelWanted("other", true, "https://files.example.com/a", "cee.buzz", MEDIA));
    }

    private static void signed() {
        eq("unsigned", -1L, StreamRules.signedExpiry("https://cdn.cee.buzz/a.mp4"));
        eq("Expires", 1900000000_000L, StreamRules.signedExpiry("https://x/a.mp4?Expires=1900000000&Signature=z"));
        long exp = StreamRules.signedExpiry("https://x/a?X-Amz-Date=20231114T221320Z&X-Amz-Expires=3600");
        eq("amz date + ttl", 1_700_000_000_000L + 3_600_000L, exp);
        long now = 1_900_000_000_000L - 300_000L;
        eq("expiring soon", true, StreamRules.isExpiring("https://x/a?Expires=1900000000", now, StreamRules.EXPIRY_MARGIN_MS));
        eq("good for a while", false, StreamRules.isExpiring("https://x/a?Expires=1900000000", now - 3_600_000L, StreamRules.EXPIRY_MARGIN_MS));
        eq("unsigned never expires", false, StreamRules.isExpiring("https://x/a", now, StreamRules.EXPIRY_MARGIN_MS));
    }

    private static void quality() {
        eq("480 by default", 480, QualityRules.startHeight(Arrays.asList(240, 360, 480, 720, 1080), 0));
        eq("remembered pick", 720, QualityRules.startHeight(Arrays.asList(240, 360, 480, 720, 1080), 720));
        eq("remembered pick not there", 480, QualityRules.startHeight(Arrays.asList(240, 360, 480, 720), 1080));
        eq("no 480: lowest of 360+", 720, QualityRules.startHeight(Arrays.asList(240, 720, 1080), 0));
        eq("no 480, 360 there", 360, QualityRules.startHeight(Arrays.asList(240, 360, 720), 0));
        eq("all tiny: the highest", 240, QualityRules.startHeight(Arrays.asList(144, 240), 0));
        eq("none", 0, QualityRules.startHeight(Arrays.<Integer>asList(), 0));
        eq("only the mirror is adaptive", true, QualityRules.adaptive("other") && !QualityRules.adaptive("el"));
        eq("EL starts at the highest up to 720", 720, QualityRules.autoStartHeight("el", Arrays.asList(240, 480, 720, 1080)));
        eq("EL, nothing up to 720: the lowest", 1080, QualityRules.autoStartHeight("el", Arrays.asList(1080, 2160)));
        eq("mirror starts at 480", 480, QualityRules.autoStartHeight("other", Arrays.asList(240, 480, 720, 1080)));
        eq("no heights", 0, QualityRules.autoStartHeight("el", Arrays.<Integer>asList()));
        eq("remember key per provider", "quality:other", QualityRules.rememberKey("other"));
    }
}
