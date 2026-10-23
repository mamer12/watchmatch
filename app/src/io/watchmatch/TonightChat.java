package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spark Tonight's conversation: the state machine (turn cap, history trimming) and the fixed questions used when the AI
 * can't be reached, which end in the same filters the AI returns. Pure and immutable: every step returns a new State
 * (port of the web app's features/tonight/machine.ts and fallback.ts; keep them in step).
 */
final class TonightChat {
    private TonightChat() {}

    static final int MAX_HISTORY = 20, SHORT_MINUTES = 100, MAX_IDEA = 60;

    enum Mode { AI, FALLBACK }
    enum Status { ASKING, THINKING, DONE }

    /** A tappable answer: what the button shows and what it means. */
    static final class Choice {
        final String label, value;

        Choice(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    // ---- the fixed questions ------------------------------------------------------------------

    /** A question (its text id) and its answers: {answer id, text id, then words that mean it in any language}. */
    private static final class Step {
        final String id, question;
        final String[][] options;

        Step(String id, String question, String[]... options) {
            this.id = id;
            this.question = question;
            this.options = options;
        }
    }

    private static final String SKIP = "skip", IDEA = "idea", IDEA_TEXT = "ideaText", SAID = "said";
    private static final String[] SKIP_OPTION = {SKIP, SKIP, "skip", "pass", "pular", "omitir", "saltar", "تخطي", "تجاوز"};

    private static String[] genre(String id, String... words) {
        String[] o = new String[words.length + 2];
        o[0] = id;
        o[1] = "g:" + id;
        System.arraycopy(words, 0, o, 2, words.length);
        return o;
    }

    /** Asked once each, in this order; every one can be skipped, and the idea also takes free text. */
    private static final Step[] STEPS = {
        new Step("mood", "qMood",
                new String[] {"happy", "happy", "glad", "good", "feliz", "سعيد", "فرحان", "مبسوط"},
                new String[] {"relaxed", "relaxed", "calm", "chill", "relajado", "relaxado", "مرتاح", "هادئ", "رايق"},
                new String[] {"sad", "sad", "down", "cry", "triste", "حزين", "زعلان"},
                new String[] {"tense", "tense", "stressed", "anxious", "tenso", "متوتر", "قلقان"},
                new String[] {"excited", "excited", "pumped", "energetic", "animado", "emocionado", "متحمس"},
                new String[] {"curious", "curious", "curioso", "فضولي", "فضول"},
                new String[] {"romantic", "romantic", "love", "romântico", "romantico", "رومانسي", "حب"},
                new String[] {"nostalgic", "nostalgic", "nostalgia", "nostálgico", "nostalgico", "حنين", "نوستالجيا"},
                SKIP_OPTION),
        new Step("company", "qCompany",
                new String[] {"alone", "alone", "me", "myself", "solo", "sozinho", "وحدي", "لحالي", "انا"},
                new String[] {"family", "family", "kids", "wife", "parents", "familia", "família", "عائله", "عايله", "اهلي", "اطفال"},
                new String[] {"friends", "friends", "buddies", "amigos", "friend", "ربع", "اصدقاء", "اصحاب", "صحاب"},
                SKIP_OPTION),
        new Step("kind", "qKind",
                new String[] {"movie", "movie", "film", "filme", "pelicula", "فيلم", "افلام"},
                new String[] {"tv", "tv", "series", "serie", "show", "série", "مسلسل", "مسلسلات"},
                new String[] {"any", "any", "either", "tanto faz", "cualquiera", "مو مهم", "اي شي", "اي واحد"},
                SKIP_OPTION),
        new Step(IDEA, "qIdea",
                genre("action", "اكشن", "ação", "acción"), genre("comedy", "funny", "comédia", "comedia", "كوميدي"),
                genre("drama", "دراما"), genre("thriller", "suspense", "suspenso", "تشويق", "اثاره"),
                genre("horror", "scary", "terror", "رعب"), genre("sci-fi", "scifi", "sci fi", "science fiction", "خيال علمي"),
                genre("romance", "رومانسيه"), genre("animation", "cartoon", "animação", "animación", "انميشن", "كرتون"),
                genre("documentary", "documentário", "documental", "وثائقي"),
                SKIP_OPTION),
        new Step("length", "qLength",
                new String[] {"short", "short", "quick", "curto", "corto", "قصير", "سريع"},
                new String[] {"long", "long", "time", "tempo", "tiempo", "وقت"},
                new String[] {"any", "anyLength", "whatever", "qualquer", "cualquier", "مو مهم", "اي شي"},
                SKIP_OPTION),
        new Step("origin", "qOrigin",
                new String[] {"ar", "o:ar", "arabic", "árabe", "arabe", "عربي", "عربيه"},
                new String[] {"en", "o:en", "english", "hollywood", "inglês", "ingles", "انكليزي", "انجليزي", "هوليوود"},
                new String[] {"tr", "o:tr", "turkish", "turkey", "turco", "تركي", "تركيه"},
                new String[] {"ko", "o:ko", "korean", "korea", "coreano", "كوري", "كوريه"},
                SKIP_OPTION),
        new Step("era", "qEra",
                new String[] {"new", "e:new", "recent", "latest", "new", "reciente", "جديد", "حديث"},
                new String[] {"mid", "e:mid", "2000s", "2010s", "2000", "2010"},
                new String[] {"old", "e:old", "classic", "classics", "old", "clasico", "clásico", "clássico", "قديم", "كلاسيك"},
                SKIP_OPTION),
    };

    static int steps() {
        return STEPS.length;
    }

    private static List<Choice> choicesFor(Step s, TonightText.Tr tr) {
        List<Choice> out = new ArrayList<>();
        for (String[] o : s.options) out.add(new Choice(TonightText.get(tr, o[1]), o[0]));
        return out;
    }

    private static final Map<String, String[]> MOOD_GENRES = new LinkedHashMap<>(), MOOD_EXCLUDE = new LinkedHashMap<>();
    static {
        mood("happy", new String[] {"comedy", "animation", "adventure"}, "horror", "war");
        mood("relaxed", new String[] {"comedy", "romance", "documentary"}, "horror", "war", "crime");
        mood("sad", new String[] {"drama", "romance"});
        mood("tense", new String[] {"thriller", "crime", "mystery"});
        mood("excited", new String[] {"action", "adventure", "sci-fi"});
        mood("curious", new String[] {"documentary", "mystery", "history"});
        mood("romantic", new String[] {"romance", "drama", "comedy"}, "horror", "war");
        mood("nostalgic", new String[] {"adventure", "comedy", "family"}, "horror");
    }

    private static void mood(String id, String[] genres, String... exclude) {
        MOOD_GENRES.put(id, genres);
        MOOD_EXCLUDE.put(id, exclude);
    }

    static final int RECENT_YEARS = 5;

    /** Maps the answers (question id to answer id) to the filters the AI returns, so one resolver serves both. */
    static TonightProtocol.Filters filtersFromAnswers(Map<String, String> a, int thisYear) {
        String company = a.containsKey("company") ? a.get("company") : "alone";
        String mood = a.get("mood"), idea = a.get(IDEA), text = a.get(IDEA_TEXT);
        List<String> add = new ArrayList<>(), exclude = new ArrayList<>();
        if ("family".equals(company)) {
            add.add("family");
            exclude.addAll(Arrays.asList("horror", "war", "crime"));
        } else if ("friends".equals(company)) {
            add.addAll(Arrays.asList("comedy", "adventure"));
        }
        if (MOOD_EXCLUDE.containsKey(mood)) exclude.addAll(Arrays.asList(MOOD_EXCLUDE.get(mood)));
        Set<String> genres = new LinkedHashSet<>();
        if (idea != null) genres.add(idea);
        if (MOOD_GENRES.containsKey(mood)) genres.addAll(Arrays.asList(MOOD_GENRES.get(mood)));
        genres.addAll(add);
        exclude = new ArrayList<>(new LinkedHashSet<>(exclude));
        if (idea != null) exclude.remove(idea);
        genres.removeAll(exclude);
        String kind = a.get("kind");
        String type = "movie".equals(kind) ? TonightProtocol.MOVIE : "tv".equals(kind) ? TonightProtocol.TV : TonightProtocol.ANY;
        List<String> keywords = new ArrayList<>();
        if (mood != null && !mood.isEmpty()) keywords.add(mood);
        if (text != null && !text.isEmpty()) keywords.add(text.toLowerCase(java.util.Locale.ROOT));
        String era = a.get("era");
        int minYear = "new".equals(era) ? thisYear - RECENT_YEARS : "mid".equals(era) ? 2000 : 0;
        int maxYear = "mid".equals(era) ? 2019 : "old".equals(era) ? 1999 : 0;
        TonightProtocol.Filters f = new TonightProtocol.Filters(type, new ArrayList<>(genres), exclude, keywords,
                "short".equals(a.get("length")) ? SHORT_MINUTES : 0, minYear, maxYear, a.containsKey("origin") ? a.get("origin") : "");
        // What was typed at the idea ("افلام عربيه قديمه", "old Turkish comedy") says its language and era too.
        List<String> said = new ArrayList<>();
        if (a.containsKey(SAID)) said.addAll(Arrays.asList(a.get(SAID).split("\n")));
        if (text != null && !text.isEmpty()) said.add(text);
        return said.isEmpty() ? f : reinforce(f, said, thisYear, true);
    }

    /** The two-letter language of a Spark origin ("مصري" is Arabic), or "" (none, or "not Arabic"). */
    static String langOf(String origin) {
        if (origin == null) return "";
        switch (origin) {
            case SparkQuery.ARABIC: case SparkQuery.EGYPTIAN: case SparkQuery.GULF: return "ar";
            case SparkQuery.AMERICAN: return "en";
            case SparkQuery.TURKISH: case SparkQuery.KOREAN: case SparkQuery.HINDI: case SparkQuery.JAPANESE: return origin;
            default: return "";
        }
    }

    /**
     * What the person said, read again by Spark's own words (Arabic too): a language or an era the AI (or the fixed
     * questions) left out is added from it, never replacing one that is there. `all`: also the type and the genres,
     * when there are none. The last thing said about each wins.
     */
    static TonightProtocol.Filters reinforce(TonightProtocol.Filters f, List<String> said, int thisYear, boolean all) {
        String lang = f.lang, type = f.type;
        int min = f.minYear, max = f.maxYear;
        List<String> genres = new ArrayList<>(f.genres);
        boolean noYears = min == 0 && max == 0;
        String saidLang = "";
        int saidMin = 0, saidMax = 0;
        String saidType = null;
        List<String> saidGenres = new ArrayList<>();
        for (String text : said) {
            SparkQuery q = SparkQuery.parse(text, thisYear);
            if (!langOf(q.origin).isEmpty()) saidLang = langOf(q.origin);
            if (q.era != null) {
                saidMin = q.from;
                saidMax = q.to;
            }
            if (q.kind != null) saidType = SparkQuery.MOVIE.equals(q.kind) ? TonightProtocol.MOVIE : TonightProtocol.TV;
            for (String g : q.genres) {
                String name = "scifi".equals(g) ? "sci-fi" : g;
                if (TonightProtocol.GENRES.contains(name) && !saidGenres.contains(name)) saidGenres.add(name);
            }
        }
        if (lang.isEmpty()) lang = saidLang;
        if (noYears) {
            min = saidMin;
            max = saidMax;
        }
        if (TonightProtocol.ANY.equals(type) && saidType != null) type = saidType;      // "movies" said, whatever the AI sent
        if (all) {
            for (String g : saidGenres) if (!genres.contains(g)) genres.add(g);
        }
        if (lang.equals(f.lang) && min == f.minYear && max == f.maxYear && type.equals(f.type) && genres.equals(f.genres)) return f;
        genres.removeAll(f.excludeGenres);
        return new TonightProtocol.Filters(type, genres, f.excludeGenres, f.keywords, f.maxMinutes, min, max, lang);
    }

    /** The person's own lines of a chat. */
    private static List<String> userLines(List<TonightProtocol.Msg> messages) {
        List<String> out = new ArrayList<>();
        for (TonightProtocol.Msg m : messages) if (m.user) out.add(m.text);
        return out;
    }

    /** Spelling and punctuation aside: lower case words with single spaces. */
    private static String words(String s) {
        return SearchRank.normalize(s).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The option a tapped button or said or typed text means (any language, spelling aside), or null. */
    private static String matchOption(Step s, String input) {
        String t = words(input);
        if (t.isEmpty()) return null;
        for (String[] o : s.options) {
            if (t.equals(o[0].toLowerCase())) return o[0];
            for (String label : TonightText.all(o[1])) {
                if (t.equals(words(label))) return o[0];
            }
        }
        // Said in a sentence ("with my family"): a whole word of an option's name or keywords, the longest match first.
        String best = null;
        int bestLen = 0;
        String padded = " " + t + " ";
        for (String[] o : s.options) {
            for (int i = 1; i < o.length; i++) {
                String k = words(o[i]);
                if (k.length() > bestLen && padded.contains(" " + k + " ")) {
                    best = o[0];
                    bestLen = k.length();
                }
            }
        }
        return best;
    }

    // ---- the state ----------------------------------------------------------------------------

    static final class State {
        final Mode mode;
        final List<TonightProtocol.Msg> messages;
        /** How many times the person answered. */
        final int turns;
        final Status status;
        final List<Choice> choices;
        final TonightProtocol.Filters filters;
        final int step;
        final Map<String, String> answers;

        private State(Mode mode, List<TonightProtocol.Msg> messages, int turns, Status status, List<Choice> choices,
                      TonightProtocol.Filters filters, int step, Map<String, String> answers) {
            this.mode = mode;
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
            this.turns = turns;
            this.status = status;
            this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
            this.filters = filters;
            this.step = step;
            this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        }

        /** Asking; or, with the AI, after the results too (type or tap a refine chip: more of this, shorter, no horror). */
        boolean canAnswer() {
            return status == Status.ASKING || mode == Mode.AI && status == Status.DONE && turns < TonightProtocol.MAX_USER_TURNS;
        }

        boolean done() {
            return status == Status.DONE;
        }

        private State with(Mode m, List<TonightProtocol.Msg> msgs, int t, Status s, List<Choice> c, TonightProtocol.Filters f,
                           int st, Map<String, String> a) {
            return new State(m, msgs, t, s, c, f, st, a);
        }
    }

    private static List<TonightProtocol.Msg> say(List<TonightProtocol.Msg> from, String... lines) {
        List<TonightProtocol.Msg> out = new ArrayList<>(from);
        for (String l : lines) out.add(new TonightProtocol.Msg(false, l));
        return out;
    }

    private static State askStep(State s, int step, String prefix, TonightText.Tr tr) {
        if (step >= STEPS.length) return s;
        Step st = STEPS[step];
        String q = TonightText.get(tr, st.question);
        List<TonightProtocol.Msg> msgs = prefix == null ? say(s.messages, q) : say(s.messages, prefix, q);
        return s.with(s.mode, msgs, s.turns, Status.ASKING, choicesFor(st, tr), s.filters, step, s.answers);
    }

    /** A new chat: the greeting is the first fixed question, so both modes start the same way. */
    static State start(Mode mode, TonightText.Tr tr) {
        State base = new State(mode, Collections.<TonightProtocol.Msg>emptyList(), 0, Status.ASKING,
                Collections.<Choice>emptyList(), null, 0, Collections.<String, String>emptyMap());
        return askStep(base, 0, null, tr);
    }

    /** The questions an answer in words already answered are not asked again. */
    private static int firstOpen(Map<String, String> answers, int from) {
        int next = from;
        while (next < STEPS.length && answers.containsKey(STEPS[next].id)) next++;
        return next;
    }

    /** What a sentence says (Spark's words, Arabic too) as answers: the type, a genre, the language, an era. False when nothing. */
    private static boolean absorb(Map<String, String> answers, String text, int thisYear) {
        SparkQuery q = SparkQuery.parse(text, thisYear);
        boolean any = false;
        if (q.kind != null) {
            answers.put("kind", SparkQuery.MOVIE.equals(q.kind) ? "movie" : "tv");
            any = true;
        }
        for (String g : q.genres) {
            String name = "scifi".equals(g) ? "sci-fi" : g;
            if (TonightProtocol.GENRES.contains(name)) {
                answers.put(IDEA, name);
                any = true;
                break;
            }
        }
        String lang = langOf(q.origin);
        if (!lang.isEmpty()) {
            answers.put("origin", lang);
            any = true;
        }
        if (q.era != null) {
            answers.put("era", SAID);       // the years come from the words (reinforce), not from one of the era buttons
            any = true;
        }
        if (any) answers.put(SAID, answers.containsKey(SAID) ? answers.get(SAID) + "\n" + text : text);
        return any;
    }

    /** Enough to go straight to the titles: a type and at least one of a genre, a language or an era. */
    private static boolean enough(Map<String, String> a) {
        return a.containsKey("kind") && (a.containsKey(IDEA) || a.containsKey("origin") || a.containsKey("era"));
    }

    private static State finish(State s, List<TonightProtocol.Msg> msgs, Map<String, String> answers, TonightText.Tr tr) {
        return s.with(s.mode, say(msgs, TonightText.get(tr, "leadIn")), s.turns, Status.DONE, Collections.<Choice>emptyList(),
                filtersFromAnswers(answers, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)), STEPS.length, answers);
    }

    /**
     * Switches to the fixed questions (the AI is unreachable), keeping what was said: what the person's lines already
     * answer is not asked again, and when it is enough the titles come at once.
     */
    static State toFallback(State s, TonightText.Tr tr) {
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        Map<String, String> answers = new LinkedHashMap<>();
        boolean understood = false;
        for (String line : userLines(s.messages)) understood |= absorb(answers, line, year);
        State f = s.with(Mode.FALLBACK, s.messages, s.turns, Status.ASKING, s.choices, null, 0, answers);
        if (understood && enough(answers)) return finish(f, say(f.messages, TonightText.get(tr, "fallbackNote")), answers, tr);
        return askStep(f, firstOpen(answers, 0), TonightText.get(tr, "fallbackNote"), tr);
    }

    /** Records what the person said. The same state when it's empty or the chat isn't waiting for an answer. */
    static State addUser(State s, String text) {
        String clean = TonightProtocol.cleanText(text, TonightProtocol.MAX_TEXT);
        if (clean.isEmpty() || !s.canAnswer()) return s;
        List<TonightProtocol.Msg> msgs = new ArrayList<>(s.messages);
        msgs.add(new TonightProtocol.Msg(true, clean));
        return s.with(s.mode, msgs, s.turns + 1, Status.THINKING, Collections.<Choice>emptyList(), s.filters, s.step, s.answers);
    }

    /**
     * A fixed-question answer (a button or said or typed). An unknown one asks the same question again, except at the idea,
     * where any other text is the person's own words. Skip moves on without an answer.
     */
    static State answerFallback(State s, String input, TonightText.Tr tr) {
        return answerFallback(s, input, input, tr);
    }

    /** `shown`: the line the chat shows for it (a button's label, not its value: "Arabic", not "ar"). */
    static State answerFallback(State s, String input, String shown, TonightText.Tr tr) {
        if (s.step >= STEPS.length || !s.canAnswer() || s.mode != Mode.FALLBACK) return s;
        Step step = STEPS[s.step];
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String option = matchOption(step, input);
        Map<String, String> said = new LinkedHashMap<>();
        absorb(said, input, year);
        State withUser = addUser(s, shown);
        if (withUser == s) return s;
        Map<String, String> answers = new LinkedHashMap<>(withUser.answers);
        // Free text is always welcome. A sentence that says two or more things (a type, a genre, a language, an era) is
        // taken as that, even when a word of it is also an option; one word that is an option is that option.
        int said2 = (said.containsKey("kind") ? 1 : 0) + (said.containsKey(IDEA) ? 1 : 0) + (said.containsKey("origin") ? 1 : 0)
                + (said.containsKey("era") ? 1 : 0);
        boolean understood = false;
        if (option != null && said2 < 2) {
            if (!SKIP.equals(option)) answers.put(step.id, option);
        } else if (said2 > 0) {
            understood = absorb(answers, input, year);
        } else if (!answers.containsKey(IDEA_TEXT)) {
            answers.put(IDEA_TEXT, TonightProtocol.cleanText(input, MAX_IDEA));      // not understood: kept as words to look for
        }
        if (understood && enough(answers)) return finish(withUser, withUser.messages, answers, tr);
        int next = firstOpen(answers, s.step + 1);
        if (next >= STEPS.length) return finish(withUser, withUser.messages, answers, tr);
        return askStep(withUser.with(s.mode, withUser.messages, withUser.turns, Status.ASKING, withUser.choices, s.filters,
                s.step, answers), next, null, tr);
    }

    /** The AI's reply: the next question, or the filters (which end the chat). The turn cap ends it too. */
    static State applyReply(State s, TonightProtocol.Reply r, TonightText.Tr tr) {
        List<TonightProtocol.Msg> msgs = say(s.messages, r.say);
        if (r.filters != null) {
            List<Choice> refine = new ArrayList<>();
            for (String c : r.choices) refine.add(new Choice(c, c));
            TonightProtocol.Filters f = reinforce(r.filters, userLines(s.messages),
                    java.util.Calendar.getInstance().get(java.util.Calendar.YEAR), false);
            return s.with(s.mode, msgs, s.turns, Status.DONE, refine, f, s.step, s.answers);
        }
        if (s.turns >= TonightProtocol.MAX_USER_TURNS) {
            return s.with(s.mode, say(msgs, TonightText.get(tr, "capped")), s.turns, Status.DONE, Collections.<Choice>emptyList(),
                    new TonightProtocol.Filters(TonightProtocol.ANY, Collections.<String>emptyList(), Collections.<String>emptyList(),
                            Collections.<String>emptyList(), 0, 0, 0, ""), s.step, s.answers);
        }
        List<Choice> choices = new ArrayList<>();
        for (String c : r.choices) choices.add(new Choice(c, c));
        return s.with(s.mode, msgs, s.turns, Status.ASKING, choices, s.filters, s.step, s.answers);
    }

    /** What is sent to the AI: the last MAX_HISTORY lines (the server accepts a leading line of its own). */
    static List<TonightProtocol.Msg> historyForApi(State s) {
        int from = Math.max(0, s.messages.size() - MAX_HISTORY);
        return new ArrayList<>(s.messages.subList(from, s.messages.size()));
    }
}
