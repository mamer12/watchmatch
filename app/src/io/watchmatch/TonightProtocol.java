package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Spark Tonight sends to and takes from the `spark-tonight` Edge Function, with the same limits and the same
 * checks as the web app's supabase/functions/spark-tonight/validate.ts (keep them in step). The model's answer is never
 * trusted: sizes are capped and the filters are rebuilt from known names. Pure Java (no Android).
 */
final class TonightProtocol {
    private TonightProtocol() {}

    static final int MAX_USER_TURNS = 12, MAX_MESSAGES = 24, MAX_TEXT = 400, MAX_SUMMARY_JSON = 2000, MAX_SAY = 500,
            MAX_CHOICES = 5, MAX_CHOICE = 40, MAX_KEYWORDS = 5, MAX_GENRES = 6;

    /** The genre names the server and the resolver understand (English, lower case). */
    static final List<String> GENRES = Collections.unmodifiableList(Arrays.asList(
            "action", "adventure", "animation", "comedy", "crime", "documentary", "drama", "family", "fantasy", "history",
            "horror", "music", "mystery", "romance", "sci-fi", "thriller", "war", "western"));

    static final String MOVIE = "movie", TV = "tv", ANY = "any";

    /** One line of the chat. */
    static final class Msg {
        final boolean user;
        final String text;

        Msg(boolean user, String text) {
            this.user = user;
            this.text = text;
        }
    }

    /** What the person wants tonight. Immutable; maxMinutes, minYear and maxYear are 0 when not set, lang is "" when not set. */
    static final class Filters {
        final String type;
        final List<String> genres, excludeGenres, keywords;
        final int maxMinutes, minYear, maxYear;
        final String lang;

        Filters(String type, List<String> genres, List<String> excludeGenres, List<String> keywords, int maxMinutes,
                int minYear, int maxYear, String lang) {
            this.type = type;
            this.genres = Collections.unmodifiableList(new ArrayList<>(genres));
            this.excludeGenres = Collections.unmodifiableList(new ArrayList<>(excludeGenres));
            this.keywords = Collections.unmodifiableList(new ArrayList<>(keywords));
            this.maxMinutes = maxMinutes;
            this.minYear = minYear;
            this.maxYear = maxYear;
            this.lang = lang == null ? "" : lang;
        }

        /** The same filters without the limits (time, years, language). */
        Filters withoutLimits() {
            return new Filters(type, genres, excludeGenres, keywords, 0, 0, 0, "");
        }

        Filters withoutExclusions() {
            return new Filters(type, genres, Collections.<String>emptyList(), keywords, maxMinutes, minYear, maxYear, lang);
        }

        Filters withoutGenres() {
            return new Filters(type, Collections.<String>emptyList(), excludeGenres, Collections.<String>emptyList(),
                    maxMinutes, minYear, maxYear, lang);
        }

        boolean hasLimits() {
            return maxMinutes > 0 || minYear > 0 || maxYear > 0 || !lang.isEmpty();
        }

        @Override public String toString() {
            return "type=" + type + " genres=" + genres + " not=" + excludeGenres + " kw=" + keywords + " max=" + maxMinutes
                    + " years=" + minYear + ".." + maxYear + " lang=" + lang;
        }
    }

    /** The AI's turn: a line to say and, while it still asks, short answers to tap; or the filters, which end the chat. */
    static final class Reply {
        final String say;
        final List<String> choices;
        final Filters filters;

        Reply(String say, List<String> choices, Filters filters) {
            this.say = say;
            this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
            this.filters = filters;
        }
    }

    /** Why the AI could not answer; any of these switches the chat to the fixed questions. */
    enum Fail { NO_SESSION, NOT_CONFIGURED, RATE_LIMITED, UNAVAILABLE, TIMEOUT, MODEL_ERROR, BAD_RESPONSE, TOO_MANY_TURNS, UNAUTHORIZED }

    /** A reply or a reason. */
    static final class Answer {
        final Reply reply;
        final Fail fail;

        private Answer(Reply reply, Fail fail) {
            this.reply = reply;
            this.fail = fail;
        }

        static Answer ok(Reply r) {
            return new Answer(r, null);
        }

        static Answer failed(Fail f) {
            return new Answer(null, f);
        }

        boolean ok() {
            return reply != null;
        }
    }

    /** Trims, collapses whitespace and drops control characters. */
    static String cleanText(Object v, int max) {
        if (!(v instanceof String)) return "";
        String s = ((String) v).replaceAll("[\\u0000-\\u001f\\u007f]+", " ").replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static int intIn(Object v, int min, int max) {
        if (!(v instanceof Number)) return 0;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return 0;
        long r = Math.round(d);
        return r >= min && r <= max ? (int) r : 0;
    }

    private static List<String> genreList(Object v) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : MiniJson.list(v)) {
            String g = cleanText(o, 20).toLowerCase(java.util.Locale.ROOT);
            if (GENRES.contains(g)) out.add(g);
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > MAX_GENRES ? new ArrayList<>(list.subList(0, MAX_GENRES)) : list;
    }

    /** The model's `filters` as safe Filters, or null when it is not an object. */
    static Filters sanitizeFilters(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> m = MiniJson.map(raw);
        Object t = m.get("type");
        String type = MOVIE.equals(t) ? MOVIE : TV.equals(t) ? TV : ANY;
        List<String> genres = genreList(m.get("genres"));
        List<String> exclude = new ArrayList<>();
        for (String g : genreList(m.get("excludeGenres"))) if (!genres.contains(g)) exclude.add(g);
        Set<String> kw = new LinkedHashSet<>();
        for (Object o : MiniJson.list(m.get("keywords"))) {
            String k = cleanText(o, 30).toLowerCase(java.util.Locale.ROOT);
            if (!k.isEmpty()) kw.add(k);
        }
        List<String> keywords = new ArrayList<>(kw);
        if (keywords.size() > MAX_KEYWORDS) keywords = new ArrayList<>(keywords.subList(0, MAX_KEYWORDS));
        int maxMinutes = intIn(m.get("maxMinutes"), 20, 400);
        int minYear = intIn(m.get("minYear"), 1900, 2100), maxYear = intIn(m.get("maxYear"), 1900, 2100);
        boolean ordered = minYear == 0 || maxYear == 0 || minYear <= maxYear;
        String lang = "";
        if (m.get("lang") instanceof String) {
            String l = ((String) m.get("lang")).trim().toLowerCase(java.util.Locale.ROOT);
            if (l.matches("[a-z]{2}")) lang = l;
        }
        return new Filters(type, genres, exclude, keywords, maxMinutes, ordered ? minYear : 0, ordered ? maxYear : 0, lang);
    }

    /** The model's JSON (already parsed) as a Reply, or null when it is useless. */
    static Reply sanitizeReply(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> m = MiniJson.map(raw);
        String say = cleanText(m.get("say"), MAX_SAY);
        if (say.isEmpty()) return null;
        Set<String> choices = new LinkedHashSet<>();
        for (Object o : MiniJson.list(m.get("choices"))) {
            String c = cleanText(o, MAX_CHOICE);
            if (!c.isEmpty()) choices.add(c);
        }
        List<String> list = new ArrayList<>(choices);
        if (list.size() > MAX_CHOICES) list = new ArrayList<>(list.subList(0, MAX_CHOICES));
        Filters filters = sanitizeFilters(m.get("filters"));
        // Either a next question (with something to tap or type) or the filters; a reply with neither is useless.
        if (filters == null && list.isEmpty() && !say.contains("?") && !say.contains("؟")) return null;
        return new Reply(say, list, filters);        // with filters, the choices are refine chips shown under the results
    }

    /** The request body: {"messages":[{"role","text"}...],"summary":{...}}. `summary` is already JSON. */
    static String requestBody(List<Msg> messages, String summaryJson) {
        List<Object> list = new ArrayList<>();
        for (Msg m : messages) list.add(MiniJson.obj("role", m.user ? "user" : "assistant", "text", m.text));
        return "{\"messages\":" + MiniJson.write(list) + ",\"summary\":" + summaryJson + "}";
    }

    /** Maps a server `reason` (or the HTTP status) to a Fail. */
    static Fail failFor(int status, String reason) {
        if ("not_configured".equals(reason)) return Fail.NOT_CONFIGURED;
        if ("rate_limited".equals(reason)) return Fail.RATE_LIMITED;
        if ("unauthorized".equals(reason)) return Fail.UNAUTHORIZED;
        if ("too_many_turns".equals(reason)) return Fail.TOO_MANY_TURNS;
        if ("model_error".equals(reason)) return Fail.MODEL_ERROR;
        if (status == 401 || status == 403) return Fail.UNAUTHORIZED;
        if (status == 429) return Fail.RATE_LIMITED;
        if (status == 404) return Fail.NOT_CONFIGURED;
        return Fail.UNAVAILABLE;
    }
}
