package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Checks the provider decisions (ProviderRules) and the registry (Endpoints) on a plain JVM. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/{Endpoints,ProviderRules}.java \
 *       app/test/io/watchmatch/ProviderRulesCheck.java && java -cp out/test io.watchmatch.ProviderRulesCheck
 */
public final class ProviderRulesCheck {
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
        registry();
        rounds();
        switching();
        retries();
        images();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static ProviderRules.Round round() {
        List<String> order = new ArrayList<>();
        for (Endpoints.Entry e : ProviderRules.usable(Endpoints.ALL)) order.add(e.id);
        return new ProviderRules.Round(order);
    }

    private static void registry() {
        List<String> ids = new ArrayList<>();
        for (Endpoints.Entry e : Endpoints.ALL) ids.add(e.id);
        eq("registry order", Arrays.asList("el", "albox", "other"), ids);
        List<String> usable = new ArrayList<>();
        for (Endpoints.Entry e : ProviderRules.usable(Endpoints.ALL)) usable.add(e.id);
        eq("albox disabled is never probed", Arrays.asList("el", "other"), usable);
        eq("albox is not usable", null, Endpoints.usableId("albox"));
        eq("el usable", "el", Endpoints.usableId("el"));
        eq("unknown id", null, Endpoints.usableId("nope"));
        eq("default active", "other", Endpoints.active().id);
        eq("other base", "https://cee.buzz/api/android/", Endpoints.byId("other").base());
        eq("other probe", "https://cee.buzz/api/android/mainCategories?lang=en", Endpoints.byId("other").probeUrl());
        eq("el probe", "https://" + Endpoints.byId("el").host + "/api/android/mainCategories?lang=en", Endpoints.byId("el").probeUrl());
        eq("setActive el", true, Endpoints.setActive("el"));
        eq("apiBase follows active", "https://" + Endpoints.byId("el").host + "/api/android/", Endpoints.apiBase());
        eq("setActive disabled ignored", false, Endpoints.setActive("albox"));
        eq("still el", "el", Endpoints.active().id);
        Endpoints.setActive("other");
        eq("apiBase other", "https://cee.buzz/api/android/", Endpoints.apiBase());
    }

    private static void rounds() {
        // EL answers first: used at once.
        ProviderRules.Round r = round();
        r.result("el", true, 300);
        eq("EL ok -> EL", "el", r.decide(300));
        // EL times out, other answers: other, as soon as EL has failed.
        r = round();
        r.result("other", true, 100);
        eq("other waits for EL", ProviderRules.WAIT, r.decide(100));
        r.result("el", false, 3500);
        eq("EL timeout + other ok -> other", "other", r.decide(3500));
        // EL slow but inside the grace: EL.
        r = round();
        r.result("other", true, 100);
        r.result("el", true, 1200);
        eq("EL slow within grace -> EL", "el", r.decide(1200));
        // EL slower than the grace: other wins when the grace ends.
        r = round();
        r.result("other", true, 100);
        eq("grace end", 1600L, r.graceEnds());
        eq("before grace", ProviderRules.WAIT, r.decide(1599));
        eq("after grace -> other", "other", r.decide(1600));
        // Nobody answers.
        r = round();
        r.result("el", false, 3500);
        eq("one failed, one pending", ProviderRules.WAIT, r.decide(3500));
        r.result("other", false, 3500);
        eq("none ok", ProviderRules.NONE, r.decide(3500));
        // Invalid JSON counts as a failure: the probe reports false, same as a timeout.
        r = round();
        r.result("el", false, 50);
        r.result("other", true, 60);
        eq("EL answered with a block page -> other", "other", r.decide(60));
        // A later answer for the same provider is ignored.
        r.result("el", true, 70);
        eq("first answer sticks", "other", r.decide(70));
        eq("no providers", ProviderRules.NONE, new ProviderRules.Round(Collections.<String>emptyList()).decide(0));
        // Higher priority takes over: cached other, EL now answers.
        r = round();
        r.result("el", true, 200);
        eq("higher priority takes over", "el", r.decide(200));
    }

    private static void switching() {
        eq("same provider", true, ProviderRules.mayApply("other", "other", false, 10, 5));
        eq("first switch ever", true, ProviderRules.mayApply("el", "other", false, 100_000, 0));
        eq("rate limited", false, ProviderRules.mayApply("el", "other", false, 100_000 + 59_999, 100_000));
        eq("after a minute", true, ProviderRules.mayApply("el", "other", false, 100_000 + 60_000, 100_000));
        eq("manual ignores the limit", true, ProviderRules.mayApply("el", "other", true, 100_001, 100_000));
        eq("clock set back", true, ProviderRules.mayApply("el", "other", false, 50_000, 100_000));
        eq("none picked", true, ProviderRules.mayApply("", "other", false, 1, 1));
        eq("away 29 min", false, ProviderRules.awayTooLong(1000, 1000 + 29 * 60_000L));
        eq("away 31 min", true, ProviderRules.awayTooLong(1000, 1000 + 31 * 60_000L));
        eq("never paused", false, ProviderRules.awayTooLong(0, 99 * 60_000L));
        eq("one failure", false, ProviderRules.failureTrips(1));
        eq("two failures", true, ProviderRules.failureTrips(2));
        eq("404 is not the network", false, ProviderRules.isNetworkFailure("HTTP 404"));
        eq("503 is", true, ProviderRules.isNetworkFailure("HTTP 503 from x"));
        eq("timeout is", true, ProviderRules.isNetworkFailure("timeout"));
        eq("html page is", true, ProviderRules.isNetworkFailure("not JSON"));
    }

    private static void retries() {
        eq("retry 1", 2000L, ProviderRules.retryDelay(0));
        eq("retry 2", 6000L, ProviderRules.retryDelay(1));
        eq("retry 3", 15000L, ProviderRules.retryDelay(2));
        eq("retries used up", -1L, ProviderRules.retryDelay(3));
    }

    private static String img(String id, String url) {
        return ProviderRules.rewriteImage(url, Endpoints.byId(id), Endpoints.ALL);
    }

    private static void images() {
        String el = "https://cnth2.shabakaty.com/vascin-poster-images/a_poster_medium_thumb.jpg";
        eq("other: EL host moves", "https://cnth2.cee.buzz/vascin-poster-images/a_poster_medium_thumb.jpg", img("other", el));
        eq("other: .cc alias moves", "https://cnth2.cee.buzz/x/a.jpg?w=1",
                img("other", "https://cnth3.shabakaty.cc/x/a.jpg?w=1"));
        eq("other: own host stays", "https://cnth2.cee.buzz/x.jpg", img("other", "https://cnth2.cee.buzz/x.jpg"));
        eq("other: video cdn is not an image host", "https://cdn.cee.buzz/v.mp4", img("other", "https://cdn.cee.buzz/v.mp4"));
        eq("other: file host stays", "https://cndw3.cee.buzz/v.mp4?Expires=1", img("other", "https://cndw3.cee.buzz/v.mp4?Expires=1"));
        eq("other: unrelated host stays", "https://image.tmdb.org/t/p/w500/a.jpg", img("other", "https://image.tmdb.org/t/p/w500/a.jpg"));
        eq("other: port kept", "https://cnth2.cee.buzz:8443/a.jpg", img("other", "https://cnth2.shabakaty.com:8443/a.jpg"));
        eq("el: its own links untouched", el, img("el", el));
        eq("el: its numbered host untouched", "https://cnth3.shabakaty.com/a.jpg", img("el", "https://cnth3.shabakaty.com/a.jpg"));
        eq("el: the mirror's links come back", "https://cnth2.shabakaty.com/a.jpg", img("el", "https://cnth2.cee.buzz/a.jpg"));
        eq("el: unrelated stays", "https://image.tmdb.org/a.jpg", img("el", "https://image.tmdb.org/a.jpg"));
        eq("albox has no image host yet: unchanged", el, img("albox", el));
        eq("not a link", "poster/a.jpg", img("other", "poster/a.jpg"));
        eq("null", null, ProviderRules.rewriteImage(null, Endpoints.byId("other"), Endpoints.ALL));
        // Albox switched on: a registry with a filled entry rewrites to its host, and takes everyone else's.
        List<Endpoints.Entry> on = new ArrayList<>(Endpoints.ALL);
        on.set(1, new Endpoints.Entry("albox", "Albox", "api.albox.example", null, null, Arrays.asList("cnth2.albox.example"),
                Collections.<String>emptyList(), true));
        eq("albox on: usable", 3, ProviderRules.usable(on).size());
        eq("albox on: rewrite", "https://cnth2.albox.example/a.jpg",
                ProviderRules.rewriteImage("https://cnth2.shabakaty.com/a.jpg", on.get(1), on));
        eq("albox on: mirror links too", "https://cnth2.albox.example/a.jpg",
                ProviderRules.rewriteImage("https://cnth2.cee.buzz/a.jpg", on.get(1), on));
        eq("albox on: base", "https://api.albox.example/api/android/", on.get(1).base());
    }
}
