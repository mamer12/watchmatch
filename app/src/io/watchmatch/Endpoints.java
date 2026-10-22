package io.watchmatch;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The only place the catalog servers' hostnames are written down: a registry of providers in priority order, the one
 * in use now (picked by Provider), and the package id of the catalog's official TV app (what the helper service watches).
 * Turning Albox on is a one-line edit of its entry below: host, imageHosts, enabled.
 */
final class Endpoints {
    private Endpoints() {}

    /** One way to reach the catalog. A provider with no host or not enabled is never probed, stored or used. */
    static final class Entry {
        final String id, label, host, searchHost, apiPath;
        final List<String> imageHosts, mediaHosts;
        final boolean enabled;

        Entry(String id, String label, String host, String searchHost, String apiPath, List<String> imageHosts,
                List<String> mediaHosts, boolean enabled) {
            this.id = id;
            this.label = label;
            this.host = host;
            this.searchHost = searchHost == null ? host : searchHost;
            this.apiPath = apiPath == null ? DEFAULT_API_PATH : apiPath;
            this.imageHosts = imageHosts;
            this.mediaHosts = mediaHosts;
            this.enabled = enabled;
        }

        boolean usable() { return enabled && host != null; }
        String base() { return "https://" + host + apiPath; }
        String searchBase() { return "https://" + searchHost + apiPath; }
        String probeUrl() { return base() + "mainCategories?lang=en"; }
        String imageHost() { return imageHosts.isEmpty() ? "" : imageHosts.get(0); }
    }

    static final String DEFAULT_API_PATH = "/api/android/";
    static final String EL = "el", ALBOX = "albox", OTHER = "other";

    /** In priority order: the first one that answers wins. */
    static final List<Entry> ALL = Collections.unmodifiableList(Arrays.asList(
        new Entry(EL, "EarthLink (EL)", "cinemana.shabakaty.com", "cinemana.shabakaty.cc", null,
                Arrays.asList("cnth2.shabakaty.com"), Collections.<String>emptyList(), true),
        new Entry(ALBOX, "Albox", null, null, null, Collections.<String>emptyList(), Collections.<String>emptyList(), false),
        new Entry(OTHER, "Other networks", "cee.buzz", null, null,
                Arrays.asList("cnth2.cee.buzz"), Arrays.asList("cdn.cee.buzz"), true)));

    /** The provider used when nothing is cached or answers: the mirror works from most networks. */
    static final String FALLBACK = OTHER;

    /** The catalog's official TV app. Also in res/xml/catalog_helper.xml (packageNames) and the manifest's queries. */
    static final String PKG = "com.shabakaty.cbox.cinemana";

    private static volatile Entry active = byId(FALLBACK);

    static Entry byId(String id) {
        for (Entry e : ALL) if (e.id.equals(id)) return e;
        return null;
    }

    /** The id if it names a provider that can be used, else null. */
    static String usableId(String id) {
        Entry e = id == null ? null : byId(id);
        return e != null && e.usable() ? e.id : null;
    }

    static Entry active() { return active; }

    /** Makes `id` the provider in use (ignored when it isn't usable); true when that changed it. */
    static boolean setActive(String id) {
        String ok = usableId(id);
        if (ok == null || ok.equals(active.id)) return false;
        active = byId(ok);
        return true;
    }

    /** The catalog API of the provider in use (rows, titles, files, subtitles). */
    static String apiBase() { return active.base(); }
    /** Its search endpoints (the same server, another host for the first provider). */
    static String searchBase() { return active.searchBase(); }

    /** An artwork link as the provider in use serves it (links from other providers' image hosts are re-pointed). */
    static String image(String url) { return ProviderRules.rewriteImage(url, active, ALL); }
}
