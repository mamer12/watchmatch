package io.watchmatch;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spark Tonight on a plain JVM, no network: the answer's checks (as the web app's validate.ts), the taste summary (shape,
 * 1.5 KB cap, no ids, Thursday evening), the conversation (fixed questions, turn cap, history), the client against a
 * fake transport, the resolver against a fake catalog, and every line having its Arabic. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/{MiniJson,SearchRank,Arabic,TonightProtocol,TonightText,TonightChat,TonightTaste,TonightResolver,TonightApi}.java \
 *       app/test/io/watchmatch/TonightCheck.java && java -cp out/test io.watchmatch.TonightCheck
 *
 * Exits 1 when a check fails.
 */
public final class TonightCheck {
    private static int passed, failed;
    /** English in a check; the lines' own text. */
    private static final TonightText.Tr EN = (pt, en, es) -> en;

    public static void main(String[] args) {
        protocol();
        taste();
        chat();
        api();
        resolver();
        text();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void expect(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    private static TonightProtocol.Reply reply(String json) {
        return TonightProtocol.sanitizeReply(MiniJson.parse(json));
    }

    // ---- the answer's checks --------------------------------------------------------------------

    private static void protocol() {
        TonightProtocol.Reply q = reply("{\"say\":\"  Who is\\n watching? \",\"choices\":[\"Me\",\"Me\",\"Family\",\"\"],\"filters\":null}");
        expect("question kept, whitespace collapsed", q != null && q.say.equals("Who is watching?") && q.filters == null);
        expect("choices unique and non-empty", q != null && q.choices.equals(Arrays.asList("Me", "Family")));
        TonightProtocol.Reply many = reply("{\"say\":\"Pick?\",\"choices\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"],\"filters\":null}");
        expect("at most 5 choices", many != null && many.choices.size() == TonightProtocol.MAX_CHOICES);
        TonightProtocol.Reply longChoice = reply("{\"say\":\"Pick?\",\"choices\":[\"" + rep('x', 80) + "\"],\"filters\":null}");
        expect("a choice is cut to 40", longChoice != null && longChoice.choices.get(0).length() == TonightProtocol.MAX_CHOICE);
        TonightProtocol.Reply longSay = reply("{\"say\":\"?" + rep('y', 900) + "\",\"choices\":[],\"filters\":null}");
        expect("say is cut to 500", longSay != null && longSay.say.length() == TonightProtocol.MAX_SAY);

        TonightProtocol.Reply done = reply("{\"say\":\"Here you go\",\"choices\":[\"x\"],\"filters\":{\"type\":\"movie\","
                + "\"genres\":[\"Comedy\",\"nonsense\",\"comedy\",\"sci-fi\"],\"excludeGenres\":[\"comedy\",\"horror\"],"
                + "\"maxMinutes\":95,\"minYear\":2010,\"maxYear\":2020,\"lang\":\" AR \",\"keywords\":[\"Light\",\"light\",\"fun\"]}}");
        TonightProtocol.Filters f = done == null ? null : done.filters;
        expect("filters rebuilt: type, known genres only, once", f != null && f.type.equals("movie") && f.genres.equals(Arrays.asList("comedy", "sci-fi")));
        expect("excluded genres never repeat a wanted one", f != null && f.excludeGenres.equals(Collections.singletonList("horror")));
        expect("limits kept", f != null && f.maxMinutes == 95 && f.minYear == 2010 && f.maxYear == 2020 && f.lang.equals("ar"));
        expect("keywords lower case, unique", f != null && f.keywords.equals(Arrays.asList("light", "fun")));
        expect("filters end the chat: no choices", done != null && done.choices.isEmpty());

        TonightProtocol.Filters bad = reply("{\"say\":\"ok\",\"choices\":[],\"filters\":{\"type\":\"book\",\"genres\":\"x\",\"maxMinutes\":5,"
                + "\"minYear\":2020,\"maxYear\":2010,\"lang\":\"english\"}}").filters;
        expect("bad filters become safe ones", bad != null && bad.type.equals("any") && bad.genres.isEmpty() && bad.maxMinutes == 0
                && bad.minYear == 0 && bad.maxYear == 0 && bad.lang.isEmpty());
        expect("a reply with nothing to answer or ask is refused", reply("{\"say\":\"Hello there\",\"choices\":[],\"filters\":null}") == null);
        expect("an Arabic question mark counts", reply("{\"say\":\"شنو مزاجك؟\",\"choices\":[],\"filters\":null}") != null);
        expect("no say is refused", reply("{\"say\":\"  \",\"choices\":[\"a\"],\"filters\":null}") == null);
        expect("not an object is refused", TonightProtocol.sanitizeReply(MiniJson.parse("[1]")) == null);

        expect("failFor: reason first", TonightProtocol.failFor(500, "rate_limited") == TonightProtocol.Fail.RATE_LIMITED);
        expect("failFor: 401", TonightProtocol.failFor(401, "") == TonightProtocol.Fail.UNAUTHORIZED);
        expect("failFor: 429", TonightProtocol.failFor(429, "") == TonightProtocol.Fail.RATE_LIMITED);
        expect("failFor: 404 is not deployed", TonightProtocol.failFor(404, "") == TonightProtocol.Fail.NOT_CONFIGURED);
        expect("failFor: the rest", TonightProtocol.failFor(502, "bad_model_output") == TonightProtocol.Fail.UNAVAILABLE);

        String body = TonightProtocol.requestBody(Arrays.asList(new TonightProtocol.Msg(false, "Q?"), new TonightProtocol.Msg(true, "A \"x\"")), "{\"a\":1}");
        Map<String, Object> parsed = MiniJson.map(MiniJson.parse(body));
        expect("request body: messages and summary", MiniJson.list(parsed.get("messages")).size() == 2
                && MiniJson.str(MiniJson.map(MiniJson.list(parsed.get("messages")).get(1)).get("role")).equals("user")
                && MiniJson.str(MiniJson.map(MiniJson.list(parsed.get("messages")).get(1)).get("text")).equals("A \"x\"")
                && MiniJson.lng(MiniJson.map(parsed.get("summary")).get("a")) == 1);
    }

    // ---- the taste summary ----------------------------------------------------------------------

    private static TonightTaste.Title title(String id, String name, int year, String... genres) {
        return new TonightTaste.Title(id, name, Arrays.asList(genres), year);
    }

    private static Calendar at(int y, int month, int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, month, day, hour, 0, 0);
        return c;
    }

    private static void taste() {
        TonightTaste.Title alpha = title("a", "Alpha", 2021, "comedy", "romance"), bravo = title("b", "Bravo", 2015, "action");
        TonightTaste.Title charlie = title("c", "Charlie", 1999, "horror"), delta = title("d", "Delta Show", 2022, "comedy", "drama");
        TonightTaste.Input in = new TonightTaste.Input(
                Arrays.asList(new TonightTaste.Watched(alpha, 1, 100), new TonightTaste.Watched(delta, 0.5, 300),
                        new TonightTaste.Watched(charlie, 0.1, 200)),
                Arrays.asList(new TonightTaste.Rated(alpha, "love"), new TonightTaste.Rated(bravo, "like"),
                        new TonightTaste.Rated(charlie, "dislike")),
                Arrays.asList(delta, bravo), Arrays.asList("drama"));
        Calendar tuesday = at(2026, Calendar.OCTOBER, 20, 12);
        TonightTaste.Summary s = TonightTaste.build(in, tuesday, "en");
        expect("top genre is comedy", !s.genres.isEmpty() && s.genres.get(0)[0].equals("comedy"));
        List<String> names = new ArrayList<>();
        for (Object[] g : s.genres) names.add((String) g[0]);
        expect("a disliked genre is not a taste", !names.contains("horror"));
        expect("recent: newest activity first", s.recent.equals(Arrays.asList("Delta Show", "Charlie", "Alpha")));
        expect("liked: loved, liked, then My List", s.liked.equals(Arrays.asList("Alpha", "Bravo", "Delta Show")));
        expect("disliked", s.disliked.equals(Collections.singletonList("Charlie")));
        expect("eras of what was watched", s.eras.get("2020s") == 2 && s.eras.get("1990s") == 1 && !s.eras.containsKey("2010s"));
        String json = s.json();
        Map<String, Object> m = MiniJson.map(MiniJson.parse(json));
        expect("shape: genres, lang, eras, recent, liked, disliked, ctx", new ArrayList<>(m.keySet()).equals(
                Arrays.asList("genres", "lang", "eras", "recent", "liked", "disliked", "ctx")));
        expect("ctx: weekday and hour", MiniJson.str(MiniJson.map(m.get("ctx")).get("weekday")).equals("tuesday")
                && MiniJson.lng(MiniJson.map(m.get("ctx")).get("hour")) == 12 && !MiniJson.bool(MiniJson.map(m.get("ctx")).get("isWeekendEve")));
        expect("no ids or emails in it", !json.contains("@") && !json.contains("\"id\"") && !json.contains("userId"));

        Calendar thursdayNight = at(2026, Calendar.OCTOBER, 22, 21);
        TonightTaste.Summary t = TonightTaste.build(in, thursdayNight, "ar");
        expect("Thursday evening starts the weekend", t.weekendEve && t.weekday.equals("thursday") && t.hour == 21 && t.lang.equals("ar"));
        expect("Thursday morning does not", !TonightTaste.isWeekendEve(at(2026, Calendar.OCTOBER, 22, 9)));
        expect("Thursday 16:00 does", TonightTaste.isWeekendEve(at(2026, Calendar.OCTOBER, 22, 16)));
        expect("Friday evening is not the eve", !TonightTaste.isWeekendEve(at(2026, Calendar.OCTOBER, 23, 21)));

        // A big library stays under the cap.
        List<TonightTaste.Watched> watched = new ArrayList<>();
        List<TonightTaste.Rated> rated = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            TonightTaste.Title x = title("x" + i, "A very long title number " + i + " that goes on and on", 1990 + i % 30, "drama", "comedy");
            watched.add(new TonightTaste.Watched(x, 1, i));
            rated.add(new TonightTaste.Rated(x, i % 3 == 0 ? "dislike" : "like"));
        }
        TonightTaste.Summary big = TonightTaste.build(new TonightTaste.Input(watched, rated, Collections.<TonightTaste.Title>emptyList(),
                Collections.<String>emptyList()), tuesday, "en");
        expect("big library fits 1.5 KB", big.json().length() <= TonightTaste.MAX_SUMMARY_BYTES);
        expect("lists are capped", big.recent.size() <= 10 && big.liked.size() <= 10 && big.disliked.size() <= 5);
        expect("names are cut to 40", big.recent.get(0).length() <= 40);
        TonightTaste.Summary empty = TonightTaste.build(new TonightTaste.Input(Collections.<TonightTaste.Watched>emptyList(),
                Collections.<TonightTaste.Rated>emptyList(), Collections.<TonightTaste.Title>emptyList(), Collections.<String>emptyList()), tuesday, "en");
        expect("nothing yet: empty lists, still valid JSON", empty.recent.isEmpty() && empty.genres.isEmpty() && empty.json().startsWith("{"));
        TonightTaste.Summary tiny = new TonightTaste.Summary();
        for (int i = 0; i < 10; i++) {
            tiny.recent.add(rep('x', 40) + i);
            tiny.liked.add(rep('y', 40) + i);
            tiny.disliked.add(rep('z', 40) + i);
        }
        for (int i = 0; i < 40; i++) tiny.genres.add(new Object[] {"genre" + i, 1.5});
        expect("cap shrinks any summary", TonightTaste.cap(tiny).json().length() <= TonightTaste.MAX_SUMMARY_BYTES);
        expect("the English name is the one for the AI", TonightTaste.latinName("الكيف", "The Kif").equals("The Kif")
                && TonightTaste.latinName("Inception", "بداية").equals("Inception"));
    }

    // ---- the conversation -----------------------------------------------------------------------

    private static TonightChat.State answer(TonightChat.State s, String text) {
        return TonightChat.answerFallback(s, text, EN);
    }

    private static void chat() {
        TonightChat.State s = TonightChat.start(TonightChat.Mode.FALLBACK, EN);
        expect("starts with the first question and its buttons", s.canAnswer() && s.messages.size() == 1
                && s.messages.get(0).text.equals("Who is watching tonight?") && s.choices.size() == 3);
        expect("the AI chat starts the same way", TonightChat.start(TonightChat.Mode.AI, EN).messages.get(0).text.equals(s.messages.get(0).text));
        TonightChat.State bad = answer(s, "banana");
        expect("an unknown answer asks again", bad.canAnswer() && bad.step == 0 && bad.turns == 0
                && bad.messages.get(bad.messages.size() - 1).text.equals("Please pick one of the options.") && bad.choices.size() == 3);
        s = answer(s, "family");
        expect("next question", s.canAnswer() && s.step == 1 && s.choices.get(0).value.equals("movie"));
        s = answer(s, "A series");
        s = answer(s, "Thrilling");
        expect("the third question has its four moods", s.step == 3 && s.choices.size() == 3 && s.messages.get(s.messages.size() - 1).text.equals("How much time do you have?"));
        s = answer(s, "short");
        expect("done after the fourth", s.done() && s.filters != null && !s.canAnswer());
        TonightProtocol.Filters f = s.filters;
        expect("family + series + thrilling + short: filters", f.type.equals("tv") && f.genres.equals(Arrays.asList("thriller", "mystery", "action", "family"))
                && f.excludeGenres.equals(Arrays.asList("horror", "war", "crime")) && f.maxMinutes == 100 && f.keywords.equals(Collections.singletonList("thrilling")));
        expect("a finished chat takes no more answers", answer(s, "alone") == s);

        Map<String, String> a = new LinkedHashMap<>();
        a.put("company", "friends");
        a.put("kind", "movie");
        a.put("mood", "light");
        a.put("length", "long");
        TonightProtocol.Filters g = TonightChat.filtersFromAnswers(a);
        expect("friends + film + light: filters", g.type.equals("movie") && g.genres.equals(Arrays.asList("comedy", "animation", "romance", "adventure"))
                && g.excludeGenres.isEmpty() && g.maxMinutes == 0);
        TonightProtocol.Filters h = TonightChat.filtersFromAnswers(Collections.<String, String>emptyMap());
        expect("no answers: any, no genres", h.type.equals("any") && h.genres.isEmpty() && h.keywords.isEmpty());

        // Said, in any language, in a sentence.
        TonightChat.State v = TonightChat.start(TonightChat.Mode.FALLBACK, EN);
        v = answer(v, "العائلة");
        expect("Arabic label said", v.step == 1 && "family".equals(v.answers.get("company")));
        v = answer(v, "اريد فيلم");
        expect("Arabic sentence", v.step == 2 && "movie".equals(v.answers.get("kind")));
        v = answer(v, "something with suspense please");
        expect("English sentence", v.step == 3 && "thrilling".equals(v.answers.get("mood")));
        expect("a label with brackets", answer(v, "Short (under 100 min)").done());
        expect("'Just me' by label", "alone".equals(answer(TonightChat.start(TonightChat.Mode.FALLBACK, EN), "just me").answers.get("company")));
        expect("by voice with a full stop", "friends".equals(answer(TonightChat.start(TonightChat.Mode.FALLBACK, EN), "With my friends.").answers.get("company")));

        TonightChat.State blank = TonightChat.start(TonightChat.Mode.AI, EN);
        expect("empty input changes nothing", TonightChat.addUser(blank, "   ") == blank);
        TonightChat.State asked = TonightChat.addUser(blank, "  Just\nme  ");
        expect("a typed answer: thinking, one more turn", asked.status == TonightChat.Status.THINKING && asked.turns == 1
                && asked.messages.get(1).user && asked.messages.get(1).text.equals("Just me") && asked.choices.isEmpty());
        expect("no answer while thinking", TonightChat.addUser(asked, "again") == asked);
        TonightChat.State next = TonightChat.applyReply(asked, reply("{\"say\":\"What mood?\",\"choices\":[\"Light\",\"Intense\"],\"filters\":null}"), EN);
        expect("AI question: asking, its choices", next.canAnswer() && next.choices.size() == 2 && next.choices.get(1).value.equals("Intense"));
        TonightChat.State fin = TonightChat.applyReply(next, reply("{\"say\":\"Here\",\"choices\":[],\"filters\":{\"type\":\"any\",\"genres\":[\"drama\"],\"excludeGenres\":[],\"keywords\":[]}}"), EN);
        expect("AI filters end the chat", fin.done() && fin.filters.genres.equals(Collections.singletonList("drama")));
        TonightChat.State capped = asked;
        for (int i = 0; i < TonightProtocol.MAX_USER_TURNS; i++) {
            capped = TonightChat.applyReply(capped, reply("{\"say\":\"More?\",\"choices\":[\"yes\"],\"filters\":null}"), EN);
            if (capped.done()) break;
            capped = TonightChat.addUser(capped, "yes");
        }
        expect("the turn cap ends it with open filters", capped.done() && capped.filters != null && capped.filters.type.equals("any")
                && capped.turns <= TonightProtocol.MAX_USER_TURNS && capped.messages.get(capped.messages.size() - 1).text.startsWith("That is enough"));
        TonightChat.State fb = TonightChat.toFallback(asked, EN);
        expect("AI failed: the fixed questions from the start, what was said kept", fb.mode == TonightChat.Mode.FALLBACK && fb.step == 0
                && fb.canAnswer() && fb.messages.size() == asked.messages.size() + 2 && fb.messages.get(fb.messages.size() - 2).text.startsWith("The smart chat"));

        TonightChat.State long30 = blank;
        for (int i = 0; i < 10; i++) {
            long30 = TonightChat.addUser(long30, "answer " + i);
            long30 = TonightChat.applyReply(long30, reply("{\"say\":\"Q" + i + "?\",\"choices\":[\"a\"],\"filters\":null}"), EN);
        }
        List<TonightProtocol.Msg> history = TonightChat.historyForApi(long30);
        expect("history: the last 20", history.size() == TonightChat.MAX_HISTORY && history.get(history.size() - 1).text.equals("Q9?"));
        expect("history of a short chat is all of it", TonightChat.historyForApi(blank).size() == 1);
        expect("a state is immutable", tryAdd(blank.messages) && tryAdd(blank.choices));
    }

    private static boolean tryAdd(List<?> list) {
        try {
            list.add(null);
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    // ---- the AI, over a fake transport ----------------------------------------------------------

    private static final class Fake implements TonightApi.Transport {
        TonightApi.Response answer;
        IOException error;
        String url, body;
        Map<String, String> headers;
        int calls;

        @Override public TonightApi.Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
            calls++;
            this.url = url;
            this.headers = headers;
            this.body = body;
            if (error != null) throw error;
            return answer;
        }
    }

    private static void api() {
        Fake fake = new Fake();
        List<TonightProtocol.Msg> msgs = Collections.singletonList(new TonightProtocol.Msg(true, "hello"));
        TonightApi ok = new TonightApi("https://x.supabase.co/", "anon", () -> "tok", fake);
        fake.answer = new TonightApi.Response(200, "{\"ok\":true,\"say\":\"What mood?\",\"choices\":[\"Light\"],\"filters\":null}");
        TonightProtocol.Answer r = ok.ask(msgs, "{}");
        expect("a good answer", r.ok() && r.reply.say.equals("What mood?") && r.reply.choices.equals(Collections.singletonList("Light")));
        expect("posts to the function with the session's token", fake.url.equals("https://x.supabase.co/functions/v1/spark-tonight")
                && "Bearer tok".equals(fake.headers.get("Authorization")) && "anon".equals(fake.headers.get("apikey")));
        expect("the body carries the chat and the summary", fake.body.contains("\"messages\"") && fake.body.contains("\"summary\":{}"));
        expect("ready with a backend and a session", ok.ready());
        expect("not ready without a session", !new TonightApi("https://x.supabase.co", "anon", () -> null, fake).ready());
        expect("not ready without a backend", !new TonightApi("", "", () -> "tok", fake).ready());
        expect("http is refused", !new TonightApi("http://x.supabase.co", "anon", () -> "tok", fake).configured());

        int before = fake.calls;
        expect("no backend: not configured, no call", new TonightApi("", "", () -> "tok", fake).ask(msgs, "{}").fail == TonightProtocol.Fail.NOT_CONFIGURED && fake.calls == before);
        expect("no session: no session, no call", new TonightApi("https://x.supabase.co", "anon", () -> null, fake).ask(msgs, "{}").fail == TonightProtocol.Fail.NO_SESSION && fake.calls == before);
        expect("a token that throws is no session", new TonightApi("https://x.supabase.co", "anon", () -> {
            throw new IllegalStateException("x");
        }, fake).ask(msgs, "{}").fail == TonightProtocol.Fail.NO_SESSION);

        fake.answer = new TonightApi.Response(429, "{\"ok\":false,\"reason\":\"rate_limited\"}");
        expect("429: rate limited", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.RATE_LIMITED);
        fake.answer = new TonightApi.Response(401, "{\"ok\":false,\"reason\":\"unauthorized\"}");
        expect("401: unauthorized", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.UNAUTHORIZED);
        fake.answer = new TonightApi.Response(503, "{\"ok\":false,\"reason\":\"not_configured\"}");
        expect("503 without the key on the server", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.NOT_CONFIGURED);
        fake.answer = new TonightApi.Response(502, "<html>bad gateway</html>");
        expect("not JSON: unavailable", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.UNAVAILABLE);
        fake.answer = new TonightApi.Response(200, "{\"ok\":true,\"say\":\"\",\"choices\":[],\"filters\":null}");
        expect("a useless answer is a bad response", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.BAD_RESPONSE);
        fake.answer = new TonightApi.Response(200, "{\"ok\":false,\"reason\":\"model_error\"}");
        expect("ok:false is a failure even at 200", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.MODEL_ERROR);
        fake.error = new SocketTimeoutException("slow");
        expect("a timeout", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.TIMEOUT);
        fake.error = new IOException("offline");
        expect("offline", ok.ask(msgs, "{}").fail == TonightProtocol.Fail.UNAVAILABLE);
    }

    // ---- the titles -----------------------------------------------------------------------------

    private static TonightResolver.Item item(String id, int year, double stars, int minutes, boolean series, String... genres) {
        return new TonightResolver.Item(id, "Title " + id, "", "", year, stars, minutes, series, Arrays.asList(genres));
    }

    /** A catalog that answers every query with the same list, and counts what was asked. */
    private static final class Catalog implements TonightResolver.Source {
        final List<TonightResolver.Item> all;
        final List<TonightResolver.Query> asked = new ArrayList<>();

        Catalog(TonightResolver.Item... all) {
            this.all = Arrays.asList(all);
        }

        @Override public List<List<TonightResolver.Item>> fetch(List<TonightResolver.Query> queries) {
            List<List<TonightResolver.Item>> out = new ArrayList<>();
            for (TonightResolver.Query q : queries) {
                asked.addAll(Collections.singletonList(q));
                List<TonightResolver.Item> list = new ArrayList<>();
                for (TonightResolver.Item i : all) if (q.type.equals(i.series ? "series" : "movies")) list.add(i);
                out.add(q.page == 0 ? list : Collections.<TonightResolver.Item>emptyList());
            }
            return out;
        }
    }

    private static TonightProtocol.Filters filters(String type, List<String> genres, List<String> exclude, int maxMinutes) {
        return new TonightProtocol.Filters(type, genres, exclude, Collections.<String>emptyList(), maxMinutes, 0, 0, "");
    }

    private static void resolver() {
        TonightProtocol.Filters f = filters("any", Arrays.asList("comedy", "sci-fi", "western"), Collections.<String>emptyList(), 0);
        List<TonightResolver.Query> q = TonightResolver.queries(f, 2026);
        expect("queries: both kinds, two categories (the third has none), two pages", q.size() == 2 * 2 * TonightResolver.PAGES
                && q.get(0).type.equals("movies") && q.get(0).category.equals("59") && q.get(2).category.equals("78"));
        List<TonightResolver.Query> open = TonightResolver.queries(filters("tv", Collections.<String>emptyList(), Collections.<String>emptyList(), 0), 2026);
        expect("no genre: any category, only series, three pages", open.size() == TonightResolver.WIDE_PAGES && open.get(0).type.equals("series") && open.get(0).category.isEmpty());
        List<TonightResolver.Query> years = TonightResolver.queries(new TonightProtocol.Filters("movie", Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0, 2010, 0, ""), 2026);
        expect("years: from the year to next year", years.get(0).from == 2010 && years.get(0).to == 2027);
        expect("catalog names: Sci-Fi is sci-fi", TonightResolver.genreName("Sci-Fi").equals("sci-fi") && TonightResolver.genreName(" Science Fiction ").equals("sci-fi"));

        Set<String> exclude = new HashSet<>(Collections.singletonList("seen"));
        List<TonightResolver.Item> items = Arrays.asList(
                item("seen", 2020, 8, 100, false, "comedy"),
                item("horror", 2020, 8, 100, false, "comedy", "horror"),
                item("long", 2020, 8, 150, false, "comedy"),
                item("old", 2005, 8, 90, false, "comedy"),
                item("low", 2020, 3.1, 90, false, "comedy"),
                item("a", 2020, 7.0, 95, false, "comedy"),
                item("show", 2020, 7.0, 45, true, "comedy"),
                item("b", 2020, 7.0, 95, false, "drama"));
        TonightProtocol.Filters strict = new TonightProtocol.Filters("movie", Arrays.asList("comedy"), Arrays.asList("horror"),
                Collections.<String>emptyList(), 120, 2010, 0, "");
        List<TonightResolver.Item> good = TonightResolver.filter(items, strict, exclude);
        List<String> ids = new ArrayList<>();
        for (TonightResolver.Item i : good) ids.add(i.id);
        expect("filter: seen, banned genre, too long, too old, poorly rated and wrong kind are out", ids.equals(Arrays.asList("a", "b")));
        expect("a series' length is not an episode's limit", TonightResolver.filter(Collections.singletonList(item("s", 2020, 7, 300, true, "drama")),
                filters("tv", Collections.<String>emptyList(), Collections.<String>emptyList(), 100), exclude).size() == 1);
        Map<String, Double> liked = new LinkedHashMap<>();
        liked.put("drama", 5.0);
        List<TonightResolver.Item> ranked = TonightResolver.rank(Arrays.asList(item("b", 2020, 7, 95, false, "drama"), item("a", 2020, 7, 95, false, "comedy")),
                filters("movie", Arrays.asList("comedy"), Collections.<String>emptyList(), 0), liked, 2026);
        expect("a strongly liked genre (+5) beats a wanted one (+4)", ranked.get(0).id.equals("b"));
        liked.put("drama", 1.0);
        ranked = TonightResolver.rank(Arrays.asList(item("b", 2020, 7, 95, false, "drama"), item("a", 2020, 7, 95, false, "comedy")),
                filters("movie", Arrays.asList("comedy"), Collections.<String>emptyList(), 0), liked, 2026);
        expect("a wanted genre beats a lightly liked one", ranked.get(0).id.equals("a"));

        TonightResolver.Deps deps = new TonightResolver.Deps(new Catalog(items.toArray(new TonightResolver.Item[0])), liked, exclude, 2026, EN, false);
        List<TonightResolver.Pick> picks = TonightResolver.resolve(strict, deps);
        expect("resolve: only what the catalog has, with a reason each", picks.size() == 2 && picks.get(0).item.id.equals("a")
                && picks.get(0).reason.equals("Matches your mood: Comedy"));
        expect("reason: liked genre", TonightResolver.reason(item("b", 2020, 7, 95, false, "drama"), strict, liked, EN, false).equals("You like Drama"));
        expect("reason: time", TonightResolver.reason(item("t", 2020, 6, 95, false, "western"), strict, Collections.<String, Double>emptyMap(), EN, false).equals("Fits your time: 95 min"));
        expect("reason: rated", TonightResolver.reason(item("t", 2020, 8.0, 95, false, "western"), filters("movie", Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0), Collections.<String, Double>emptyMap(), EN, false).equals("Highly rated: 8.0"));
        expect("reason: popular", TonightResolver.reason(item("t", 2020, 6, 95, false, "western"), filters("any", Collections.<String>emptyList(), Collections.<String>emptyList(), 0),
                Collections.<String, Double>emptyMap(), EN, false).equals("Popular right now"));
        expect("reason: two genres, Arabic joiner", TonightResolver.reason(item("t", 2020, 6, 95, false, "comedy", "drama"), filters("any", Arrays.asList("comedy", "drama"),
                Collections.<String>emptyList(), 0), liked, EN, true).equals("Matches your mood: Comedy و" + "Drama"));

        // Nothing matches: the limits go first, then the exclusions, then the genres.
        TonightProtocol.Filters tight = new TonightProtocol.Filters("movie", Arrays.asList("western"), Arrays.asList("drama"),
                Collections.<String>emptyList(), 30, 0, 0, "");
        Catalog c2 = new Catalog(item("a", 2020, 7, 95, false, "comedy"), item("b", 2020, 7, 95, false, "drama"));
        List<TonightResolver.Pick> relaxed = TonightResolver.resolve(tight, new TonightResolver.Deps(c2, liked, exclude, 2026, EN, false));
        expect("relaxing finds titles in the end (the limit goes first, the excluded genre stays out)", relaxed.size() == 1 && relaxed.get(0).item.id.equals("a"));
        expect("relax order", TonightResolver.relax(tight).maxMinutes == 0 && TonightResolver.relax(tight).excludeGenres.equals(Collections.singletonList("drama"))
                && TonightResolver.relax(TonightResolver.relax(tight)).excludeGenres.isEmpty() && TonightResolver.relax(TonightResolver.relax(TonightResolver.relax(tight))).genres.isEmpty()
                && TonightResolver.relax(TonightResolver.relax(TonightResolver.relax(TonightResolver.relax(tight)))) == null);
        expect("an empty catalog gives nothing, not a crash", TonightResolver.resolve(tight, new TonightResolver.Deps(new Catalog(), liked, exclude, 2026, EN, false)).isEmpty());
        TonightResolver.Item[] lots = new TonightResolver.Item[30];
        for (int i = 0; i < lots.length; i++) lots[i] = item("m" + i, 2020, 7, 90, false, "comedy");
        expect("at most 12 picks", TonightResolver.resolve(filters("movie", Arrays.asList("comedy"), Collections.<String>emptyList(), 0),
                new TonightResolver.Deps(new Catalog(lots), liked, exclude, 2026, EN, false)).size() == TonightResolver.RESULT_LIMIT);
    }

    // ---- the words ------------------------------------------------------------------------------

    private static void text() {
        Set<String> sameInArabic = new HashSet<>(Arrays.asList("Drama", "Romance", "Crime"));
        for (String id : TonightText.ids()) {
            String en = TonightText.english(id);
            String ar = Arabic.text(en);
            expect("Arabic for \"" + en + "\"", !ar.equals(en) || sameInArabic.contains(en));
            String[] all = TonightText.all(id);
            for (String line : all) {
                expect("no product name or Cinemana typed in \"" + id + "\"", !line.contains("Cinemana") && !line.contains("WatchMatch")
                        && !line.contains("Watchme+") && !line.contains("Spark Tonight") && !line.contains("Spark "));
            }
        }
        expect("the name is a placeholder", TonightText.english("title").contains("{spark}") && TonightText.english("noMic").contains("{app}"));
    }

    private static String rep(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }
}
