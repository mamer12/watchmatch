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
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/{MiniJson,SearchRank,Arabic,SparkQuery,TonightProtocol,TonightText,TonightChat,TonightTaste,TonightResolver,TonightApi}.java \
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
        language();
        text();
        expect("a catalog genre with no line keeps its own name", "Biography".equals(TonightResolver.genreLabel(EN, "biography")));
        expect("a known genre uses its line", "Comedy".equals(TonightResolver.genreLabel(EN, "comedy")));
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
        expect("filters keep the choices as refine chips", done != null && done.choices.equals(Collections.singletonList("x")));

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

    private static TonightProtocol.Filters moodFilters(String mood) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mood", mood);
        return TonightChat.filtersFromAnswers(m, 2026);
    }

    private static void chat() {
        TonightChat.State s = TonightChat.start(TonightChat.Mode.FALLBACK, EN);
        expect("starts with the mood question: 8 moods and Skip", s.canAnswer() && s.messages.size() == 1
                && s.messages.get(0).text.equals("How are you feeling?") && s.choices.size() == 9
                && s.choices.get(8).value.equals("skip") && s.choices.get(0).value.equals("happy"));
        expect("the AI chat starts the same way", TonightChat.start(TonightChat.Mode.AI, EN).messages.get(0).text.equals(s.messages.get(0).text));
        TonightChat.State bad = answer(s, "banana");
        expect("free text is never an error: kept as words, on to the next question", bad.canAnswer() && bad.step == 1
                && "banana".equals(bad.answers.get("ideaText")) && bad.messages.get(bad.messages.size() - 1).text.equals("Who is watching?"));
        // Typed at the very first question: the type, language and era in it answer the rest, so the titles come at once.
        TonightChat.State free = answer(s, "arabic movies before 2000");
        expect("\"arabic movies before 2000\" at the mood step goes straight to the titles", free.done() && free.filters.type.equals("movie")
                && free.filters.lang.equals("ar") && free.filters.maxYear == 1999 && free.filters.minYear == 0);
        free = answer(s, "افلام عربيه قبل 2000");
        expect("the same in Arabic", free.done() && free.filters.type.equals("movie") && free.filters.lang.equals("ar") && free.filters.maxYear == 1999);
        free = answer(s, "turkish comedy");
        expect("a genre and a language are not enough without a type: the questions go on, skipping what is answered",
                !free.done() && free.step == 1 && free.answers.get("origin").equals("tr") && free.answers.get("idea").equals("comedy"));
        free = answer(answer(answer(answer(free, "skip"), "movie"), "skip"), "skip");     // who, kind, length, era (idea and origin skipped)
        expect("then the answered ones are skipped", free.done() && free.filters.lang.equals("tr") && free.filters.genres.contains("comedy"));
        TonightChat.State upset = answer(s, "sad movies");
        expect("a sentence with one option word is that option", "sad".equals(upset.answers.get("mood")) && !upset.done());
        s = answer(s, "tense");
        expect("who is watching next", s.step == 1 && s.messages.get(s.messages.size() - 1).text.equals("Who is watching?") && s.choices.size() == 4);
        s = answer(s, "family");
        s = answer(s, "A series");
        expect("the idea question: genres, Skip, typing allowed", s.step == 3 && s.choices.size() == 10 && s.canAnswer());
        s = answer(s, "something about dogs");
        expect("free text at the idea is kept as a keyword", s.step == 4 && "something about dogs".equals(s.answers.get("ideaText")) && s.answers.get("idea") == null);
        s = answer(s, "short");
        expect("then the origin", s.step == 5 && s.messages.get(s.messages.size() - 1).text.equals("Which language or origin?"));
        s = answer(s, "Skip");
        expect("skipped: no answer saved, on to the era", s.step == 6 && !s.answers.containsKey("origin"));
        s = answer(s, "recent");
        expect("done after the seventh", s.done() && s.filters != null && !s.canAnswer());
        TonightProtocol.Filters f = s.filters;
        expect("tense + family + series + heist + short + recent", f.type.equals("tv")
                && f.genres.equals(Arrays.asList("thriller", "mystery", "family")) && f.excludeGenres.equals(Arrays.asList("horror", "war", "crime"))
                && f.maxMinutes == 100 && f.keywords.equals(Arrays.asList("tense", "something about dogs")) && f.minYear > 2000 && f.maxYear == 0 && f.lang.isEmpty());
        expect("a finished chat takes no more answers", answer(s, "alone") == s);
        TonightChat.State allSkipped = TonightChat.start(TonightChat.Mode.FALLBACK, EN);
        for (int i = 0; i < TonightChat.steps(); i++) allSkipped = answer(allSkipped, "skip");
        expect("seven skips: open filters", allSkipped.done() && allSkipped.filters.genres.isEmpty() && allSkipped.filters.type.equals("any")
                && allSkipped.answers.isEmpty() && !allSkipped.filters.hasLimits());
        expect("no question comes twice", TonightChat.steps() == 7);
        for (TonightChat.Choice c : TonightChat.start(TonightChat.Mode.FALLBACK, EN).choices) {
            expect("every mood maps to genres: " + c.value, c.value.equals("skip") || !moodFilters(c.value).genres.isEmpty());
        }

        Map<String, String> a = new LinkedHashMap<>();
        a.put("company", "friends");
        a.put("kind", "movie");
        a.put("mood", "happy");
        a.put("idea", "horror");
        a.put("origin", "tr");
        a.put("era", "old");
        TonightProtocol.Filters g = TonightChat.filtersFromAnswers(a, 2026);
        expect("friends + film + happy + horror + Turkish + classics", g.type.equals("movie")
                && g.genres.equals(Arrays.asList("horror", "comedy", "animation", "adventure")) && g.excludeGenres.equals(Collections.singletonList("war"))
                && g.lang.equals("tr") && g.maxYear == 1999 && g.minYear == 0);
        TonightProtocol.Filters h = TonightChat.filtersFromAnswers(Collections.<String, String>emptyMap(), 2026);
        expect("no answers: any, no genres", h.type.equals("any") && h.genres.isEmpty() && h.keywords.isEmpty());
        Map<String, String> mid = new LinkedHashMap<>();
        mid.put("era", "mid");
        TonightProtocol.Filters m = TonightChat.filtersFromAnswers(mid, 2026);
        expect("2000 to 2019", m.minYear == 2000 && m.maxYear == 2019);

        // Said, in any language, in a sentence.
        TonightChat.State v = TonightChat.start(TonightChat.Mode.FALLBACK, EN);
        v = answer(v, "حزين");
        expect("Arabic mood said", v.step == 1 && "sad".equals(v.answers.get("mood")));
        v = answer(v, "العائلة");
        expect("Arabic label said", v.step == 2 && "family".equals(v.answers.get("company")));
        v = answer(v, "اريد فيلم");
        expect("Arabic sentence", v.step == 3 && "movie".equals(v.answers.get("kind")));
        v = answer(v, "something funny please");
        expect("English sentence picks the genre", v.step == 4 && "comedy".equals(v.answers.get("idea")));
        expect("a label with brackets", "short".equals(answer(v, "Short (under 100 min)").answers.get("length")));
        expect("'Just me' by label", "alone".equals(answer(answer(TonightChat.start(TonightChat.Mode.FALLBACK, EN), "skip"), "just me").answers.get("company")));
        expect("by voice with a full stop", "friends".equals(answer(answer(TonightChat.start(TonightChat.Mode.FALLBACK, EN), "skip"), "With my friends.").answers.get("company")));

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
        TonightChat.State refine = TonightChat.applyReply(next, reply("{\"say\":\"Here\",\"choices\":[\"Shorter\",\"No horror\"],\"filters\":{\"type\":\"any\",\"genres\":[\"drama\"],\"excludeGenres\":[],\"keywords\":[]}}"), EN);
        expect("after results the AI's refine chips stay and typing is allowed", refine.done() && refine.choices.size() == 2 && refine.canAnswer());
        TonightChat.State refined = TonightChat.addUser(refine, "shorter please");
        expect("a refinement goes back to the AI", refined.status == TonightChat.Status.THINKING && refined.turns == refine.turns + 1);
        expect("fixed-question results take no refinement", !answer(allSkipped, "x").canAnswer());
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

    private static int idx(List<TonightResolver.Item> l, String id) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(id)) return i;
        return -1;
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
        expect("filter: seen, banned genre, too long, too old, poorly rated, wrong kind and none of the wanted genres are out", ids.equals(Arrays.asList("a")));
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
        expect("resolve: only what the catalog has, with a reason each", picks.size() == 1 && picks.get(0).item.id.equals("a")
                && picks.get(0).reason.equals("Comedy · ★ 7.0"));
        expect("reason: liked genre", TonightResolver.reason(item("b", 2020, 7, 95, false, "drama"), strict, liked, EN, false).equals("You like Drama"));
        expect("reason: time", TonightResolver.reason(item("t", 2020, 6, 95, false, "western"), strict, Collections.<String, Double>emptyMap(), EN, false).equals("Fits your time: 95 min"));
        expect("reason: rated", TonightResolver.reason(item("t", 2020, 8.0, 95, false, "western"), filters("movie", Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0), Collections.<String, Double>emptyMap(), EN, false).equals("Highly rated: 8.0"));
        expect("reason: genre and high stars, short", TonightResolver.reason(item("t", 2020, 7.4, 95, false, "comedy", "drama", "romance"), filters("any", Arrays.asList("comedy", "drama"),
                Collections.<String>emptyList(), 0), liked, EN, false).equals("Comedy · Drama · ★ 7.4"));
        expect("reason: popular", TonightResolver.reason(item("t", 2020, 6, 95, false, "western"), filters("any", Collections.<String>emptyList(), Collections.<String>emptyList(), 0),
                Collections.<String, Double>emptyMap(), EN, false).equals("Popular"));
        expect("reason: two genres, Arabic joiner", TonightResolver.reason(item("t", 2020, 6, 95, false, "comedy", "drama"), filters("any", Arrays.asList("comedy", "drama"),
                Collections.<String>emptyList(), 0), liked, EN, true).equals("Comedy · Drama"));

        // Quality: wanted genres count, the rating counts, a cartoon nobody asked for waits, a repeat shows once.
        TonightProtocol.Filters mood = filters("movie", Arrays.asList("comedy", "romance"), Collections.<String>emptyList(), 0);
        Map<String, Double> none = Collections.<String, Double>emptyMap();
        List<TonightResolver.Item> good2 = TonightResolver.rank(Arrays.asList(
                item("one", 2022, 7.0, 95, false, "comedy"),
                item("both", 2022, 7.0, 95, false, "comedy", "romance"),
                item("anime", 2022, 8.5, 95, false, "comedy", "animation"),
                item("low", 2022, 5.6, 95, false, "comedy", "romance")), mood, none, 2026);
        expect("all the wanted genres rank above one", good2.get(0).id.equals("both"));
        expect("an unasked cartoon ranks below a plain comedy of lower stars", good2.indexOf(good2.get(0)) == 0
                && idx(good2, "anime") > idx(good2, "one"));
        expect("a better rating wins with the same genres", idx(good2, "both") < idx(good2, "low"));
        List<TonightResolver.Item> dup = Arrays.asList(
                new TonightResolver.Item("x1", "The Film", "", "", 2020, 7, 95, false, Arrays.asList("comedy")),
                new TonightResolver.Item("x2", "the  film!", "", "", 2020, 7, 95, false, Arrays.asList("comedy")),
                new TonightResolver.Item("x3", "The Film", "", "", 1999, 7, 95, false, Arrays.asList("comedy")));
        expect("the same title and year shows once", TonightResolver.filter(dup, mood, Collections.<String>emptySet()).size() == 2);
        expect("an unrated title is not preferred to a well rated one", TonightResolver.score(item("u", 2022, 0, 95, false, "comedy"), mood, none, 2026)
                < TonightResolver.score(item("r", 2022, 7.5, 95, false, "comedy"), mood, none, 2026));

        // Nothing matches: the limits go first, then the exclusions, then the genres.
        TonightProtocol.Filters tight = new TonightProtocol.Filters("movie", Arrays.asList("western"), Arrays.asList("drama"),
                Collections.<String>emptyList(), 30, 0, 0, "");
        Catalog c2 = new Catalog(item("a", 2020, 7, 95, false, "comedy"), item("b", 2020, 7, 95, false, "drama"));
        List<TonightResolver.Pick> relaxed = TonightResolver.resolve(tight, new TonightResolver.Deps(c2, liked, exclude, 2026, EN, false));
        expect("relaxing finds titles in the end (limits, then exclusions, then genres)", relaxed.size() == 2 && relaxed.get(0).item.id.equals("b"));
        expect("relax order", TonightResolver.relax(tight).maxMinutes == 0 && TonightResolver.relax(tight).excludeGenres.equals(Collections.singletonList("drama"))
                && TonightResolver.relax(TonightResolver.relax(tight)).excludeGenres.isEmpty() && TonightResolver.relax(TonightResolver.relax(TonightResolver.relax(tight))).genres.isEmpty()
                && TonightResolver.relax(TonightResolver.relax(TonightResolver.relax(TonightResolver.relax(tight)))) == null);
        expect("an empty catalog gives nothing, not a crash", TonightResolver.resolve(tight, new TonightResolver.Deps(new Catalog(), liked, exclude, 2026, EN, false)).isEmpty());
        TonightResolver.Item[] lots = new TonightResolver.Item[30];
        for (int i = 0; i < lots.length; i++) lots[i] = item("m" + i, 2020, 7, 90, false, "comedy");
        expect("at most RESULT_LIMIT picks", TonightResolver.resolve(filters("movie", Arrays.asList("comedy"), Collections.<String>emptyList(), 0),
                new TonightResolver.Deps(new Catalog(lots), liked, exclude, 2026, EN, false)).size() == TonightResolver.RESULT_LIMIT);
    }


    // ---- the language and the era (the API cannot filter by language: it is read from the titles) -------------

    /** The real API's shape: type, category and years are the server's, 12 titles a page, English first, one in six Arabic. */
    private static final class Mirror implements TonightResolver.Source {
        final List<TonightResolver.Item> all = new ArrayList<>();
        int requests;

        Mirror(int n) {
            for (int i = 0; i < n; i++) {
                String lang = i % 6 == 0 ? "9" : i % 17 == 0 ? "22" : "7";
                all.add(new TonightResolver.Item("t" + i, "Title " + i, "", "", 1940 + i % 85, 6.5, 100, false,
                        Arrays.asList(i % 2 == 0 ? "comedy" : "drama"), lang));
            }
        }

        @Override public List<List<TonightResolver.Item>> fetch(List<TonightResolver.Query> queries) {
            List<List<TonightResolver.Item>> out = new ArrayList<>();
            for (TonightResolver.Query q : queries) {
                requests++;
                List<TonightResolver.Item> match = new ArrayList<>();
                for (TonightResolver.Item i : all) {
                    boolean cat = q.category.isEmpty() || q.category.equals("59") && i.genres.contains("comedy")
                            || q.category.equals("62") && i.genres.contains("drama");
                    boolean years = q.from == 0 && q.to == 0 || i.year >= q.from && i.year <= q.to;
                    if (cat && years && q.type.equals(i.series ? "series" : "movies")) match.add(i);
                }
                int from = Math.min(match.size(), q.page * 12);
                out.add(new ArrayList<>(match.subList(from, Math.min(match.size(), from + 12))));
            }
            return out;
        }
    }

    private static void language() {
        Map<String, Double> none = Collections.<String, Double>emptyMap();
        Set<String> seen = Collections.<String>emptySet();
        TonightProtocol.Filters arOld = new TonightProtocol.Filters("movie", Arrays.asList("comedy"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0, 0, 1999, "ar");
        Mirror mirror = new Mirror(1200);
        TonightResolver.Found found = TonightResolver.search(arOld, new TonightResolver.Deps(mirror, none, seen, 2026, EN, false));
        boolean only = !found.picks.isEmpty();
        for (TonightResolver.Pick p : found.picks) only &= p.item.lang.equals("9") && p.item.year > 0 && p.item.year <= 1999;
        expect("Arabic + before 2000: only Arabic titles of 1999 or earlier", only);
        expect("Arabic + before 2000: reads past the first pages to find enough", found.picks.size() == TonightResolver.RESULT_LIMIT);
        expect("Arabic + before 2000: nothing was loosened", !found.relaxed && found.used.lang.equals("ar") && found.used.maxYear == 1999);
        TonightResolver.Query first = TonightResolver.queries(arOld, 2026).get(0);
        expect("the era goes to the server, a language reads six pages a round", first.to == 1999 && first.from == 1900
                && TonightResolver.queries(arOld, 2026).size() == TonightResolver.LANG_PAGES
                && TonightResolver.queries(arOld, 2026, 1).get(0).page == TonightResolver.LANG_PAGES);

        // Few matches: honest, never filled up with English or new titles.
        TonightProtocol.Filters rare = new TonightProtocol.Filters("movie", Arrays.asList("comedy"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0, 0, 1942, "ar");
        TonightResolver.Found few = TonightResolver.search(rare, new TonightResolver.Deps(new Mirror(1200), none, seen, 2026, EN, false));
        boolean fewOnly = few.picks.size() < TonightResolver.RESULT_LIMIT;
        for (TonightResolver.Pick p : few.picks) fewOnly &= p.item.lang.equals("9") && p.item.year <= 1942;
        expect("few Arabic titles up to 1942: only those, however few", fewOnly);
        TonightProtocol.Filters none9 = new TonightProtocol.Filters("movie", Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0, 0, 1900, "ar");
        expect("no title fits: nothing, the language and era are not relaxed",
                TonightResolver.search(none9, new TonightResolver.Deps(new Mirror(300), none, seen, 2026, EN, false)).picks.isEmpty());
        expect("a language the catalog has no number for gives nothing", TonightResolver.knowsLanguage("tr")
                && !TonightResolver.knowsLanguage("xx") && TonightResolver.filter(new Mirror(50).all, new TonightProtocol.Filters("movie", Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0, 0, 0, "xx"), seen).isEmpty());
        expect("a title with no year is not known to fit an era", TonightResolver.filter(Collections.singletonList(item("n", 0, 7, 90, false, "comedy")),
                arOld.without("origin"), seen).isEmpty());
        expect("relax never drops the language or the era", TonightResolver.relax(arOld) == null
                || TonightResolver.relax(arOld).lang.equals("ar") && TonightResolver.relax(arOld).maxYear == 1999);
        TonightProtocol.Filters r = arOld;
        for (int i = 0; i < 5 && r != null; i++) {
            expect("relax step keeps ar and 1999", r.lang.equals("ar") && r.maxYear == 1999);
            r = TonightResolver.relax(r);
        }

        // What was said: the words and the AI's codes.
        int y = 2026;
        TonightProtocol.Filters blank = new TonightProtocol.Filters("any", Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0, 0, 0, "");
        TonightProtocol.Filters a = TonightChat.reinforce(blank, Collections.singletonList("افلام عربيه قديمه"), y, true);
        expect("\"افلام عربيه قديمه\": Arabic films before 2000", a.lang.equals("ar") && a.maxYear == 1999 && a.minYear == 0 && a.type.equals("movie"));
        a = TonightChat.reinforce(blank, Collections.singletonList("عربي"), y, false);
        expect("\"عربي\" is Arabic", a.lang.equals("ar") && a.maxYear == 0);
        a = TonightChat.reinforce(blank, Arrays.asList("comedy", "arabic movies before 2000"), y, false);
        expect("\"arabic movies before 2000\"", a.lang.equals("ar") && a.maxYear == 1999);
        expect("\"arabic movies before 2000\" also says the type (the Movies chip), in the AI's chat too", a.type.equals("movie"));
        TonightResolver.Item dubbed = new TonightResolver.Item("d", "Remi", "", "", 1996, 7, 0, true, Arrays.asList("arabic dubbed", "adventure"), "9");
        TonightResolver.Item real = new TonightResolver.Item("r", "Film", "", "", 1980, 7, 100, false, Arrays.asList("comedy"), "9");
        expect("Arabic: a title dubbed into Arabic is not an Arabic title; a series is not a film",
                TonightResolver.filter(Arrays.asList(dubbed, real), arOld.without("kind"), seen).size() == 1
                && TonightResolver.filter(Arrays.asList(dubbed, real), arOld, seen).size() == 1
                && TonightResolver.filter(Arrays.asList(dubbed, real), arOld, seen).get(0).id.equals("r"));
        a = TonightChat.reinforce(blank, Collections.singletonList("2000 to 2019"), y, false);
        expect("\"2000 to 2019\"", a.minYear == 2000 && a.maxYear == 2019);
        a = TonightChat.reinforce(blank, Collections.singletonList("من 2000 الى 2019"), y, false);
        expect("\"من 2000 الى 2019\"", a.minYear == 2000 && a.maxYear == 2019);
        a = TonightChat.reinforce(blank, Collections.singletonList("last 5 years"), y, false);
        expect("\"last 5 years\"", a.minYear == 2021 && a.maxYear == 0);
        a = TonightChat.reinforce(blank, Collections.singletonList("classics"), y, false);
        expect("\"classics\"", a.maxYear == 1999);
        a = TonightChat.reinforce(blank, Collections.singletonList("تركي"), y, false);
        expect("Turkish", a.lang.equals("tr"));
        TonightProtocol.Filters ai = new TonightProtocol.Filters("movie", Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0, 2010, 2015, "ko");
        expect("what the AI set is never replaced", TonightChat.reinforce(ai, Collections.singletonList("افلام عربيه قديمه"), y, true) == ai);
        TonightChat.State st = TonightChat.start(TonightChat.Mode.AI, EN);
        st = TonightChat.addUser(st, "افلام عربيه قديمه");
        st = TonightChat.applyReply(st, reply("{\"say\":\"ok\",\"choices\":[],\"filters\":{\"type\":\"movie\",\"genres\":[\"comedy\"]}}"), EN);
        expect("the AI's filters without a language get it from what was said", st.filters.lang.equals("ar") && st.filters.maxYear == 1999);

        // The fixed questions: a button shows its label; a typed idea carries its language and era.
        TonightChat.State fb = TonightChat.start(TonightChat.Mode.FALLBACK, EN);
        for (int i = 0; i < 3; i++) fb = TonightChat.answerFallback(fb, "skip", EN);       // mood, company, kind
        fb = TonightChat.answerFallback(fb, "اكشن عربي قديم؟", EN);
        expect("a typed idea answers the language and the era too", fb.step == 4 && fb.answers.get("origin").equals("ar"));
        fb = TonightChat.answerFallback(fb, "skip", EN);       // length: the origin and era questions are skipped
        expect("fixed questions end with the language", fb.done() && fb.filters.lang.equals("ar") && fb.filters.maxYear == 1999);
        TonightChat.State btn = TonightChat.start(TonightChat.Mode.FALLBACK, EN);
        for (int i = 0; i < 5; i++) btn = TonightChat.answerFallback(btn, "skip", EN);
        btn = TonightChat.answerFallback(btn, "ar", "Arabic", EN);
        expect("a button's line is its label", btn.messages.get(btn.messages.size() - 2).text.equals("Arabic"));
        // The AI goes away mid-chat: what was typed is not asked again.
        TonightChat.State mid = TonightChat.addUser(TonightChat.start(TonightChat.Mode.AI, EN), "arabic movies before 2000");
        TonightChat.State down = TonightChat.toFallback(mid, EN);
        expect("the AI lost mid-chat: enough was said, the titles come", down.done() && down.filters.lang.equals("ar") && down.filters.maxYear == 1999);
        TonightChat.State mid2 = TonightChat.toFallback(TonightChat.addUser(TonightChat.start(TonightChat.Mode.AI, EN), "something fun"), EN);
        expect("the AI lost mid-chat: nothing understood, the questions start", !mid2.done() && mid2.step == 0 && mid2.canAnswer());
        Map<String, String> typed = new LinkedHashMap<>();
        typed.put("ideaText", "افلام عربيه قديمه");
        TonightProtocol.Filters tf = TonightChat.filtersFromAnswers(typed, y);
        expect("a typed idea reads its language, era and kind", tf.lang.equals("ar") && tf.maxYear == 1999 && tf.type.equals("movie"));

        // Chips come off one at a time.
        expect("chips: kind, genre, language, era", arOld.parts().equals(Arrays.asList("kind", "genre:comedy", "origin", "era")));
        TonightProtocol.Filters minus = arOld.without("origin").without("era").without("genre:comedy").without("kind");
        expect("chips: taking each off", minus.lang.isEmpty() && minus.maxYear == 0 && minus.genres.isEmpty() && minus.type.equals("any") && minus.parts().isEmpty());
        SparkQuery sq = arOld.query();
        expect("Spark's query from the filters", sq.origin.equals("ar") && sq.era.equals(SparkQuery.BEFORE) && sq.to == 1999
                && sq.kind.equals("movie") && sq.genres.equals(Collections.singletonList("comedy")));
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
