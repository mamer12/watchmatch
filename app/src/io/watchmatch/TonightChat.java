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

    static final int MAX_HISTORY = 20, SHORT_MINUTES = 100;

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

    private static final Step[] STEPS = {
        new Step("company", "qCompany",
                new String[] {"alone", "alone", "me", "myself", "alone", "solo", "sozinho", "وحدي", "لحالي", "انا"},
                new String[] {"family", "family", "kids", "wife", "parents", "familia", "família", "عائله", "عايله", "اهلي", "اطفال"},
                new String[] {"friends", "friends", "buddies", "amigos", "friend", "ربع", "اصدقاء", "اصحاب", "صحاب"}),
        new Step("kind", "qKind",
                new String[] {"movie", "movie", "film", "filme", "pelicula", "فيلم", "افلام"},
                new String[] {"tv", "tv", "series", "serie", "show", "série", "مسلسل", "مسلسلات"},
                new String[] {"any", "any", "either", "tanto faz", "cualquiera", "مو مهم", "اي شي", "اي واحد"}),
        new Step("mood", "qMood",
                new String[] {"light", "light", "fun", "funny", "comedy", "leve", "ligero", "خفيف", "مرح", "كوميدي"},
                new String[] {"intense", "intense", "action", "intenso", "قوي", "مشحون", "اكشن"},
                new String[] {"thoughtful", "thoughtful", "deep", "drama", "reflexivo", "عميق", "دراما"},
                new String[] {"thrilling", "thrilling", "thriller", "suspense", "suspenso", "تشويق", "اثاره", "رعب"}),
        new Step("length", "qLength",
                new String[] {"short", "short", "quick", "curto", "corto", "قصير", "سريع"},
                new String[] {"long", "long", "time", "tempo", "tiempo", "وقت"},
                new String[] {"any", "anyLength", "whatever", "qualquer", "cualquier", "مو مهم", "اي شي"}),
    };

    static int steps() {
        return STEPS.length;
    }

    private static List<Choice> choicesFor(Step s, TonightText.Tr tr) {
        List<Choice> out = new ArrayList<>();
        for (String[] o : s.options) out.add(new Choice(TonightText.get(tr, o[1]), o[0]));
        return out;
    }

    private static final Map<String, String[]> MOOD_GENRES = new LinkedHashMap<>();
    static {
        MOOD_GENRES.put("light", new String[] {"comedy", "animation", "romance"});
        MOOD_GENRES.put("intense", new String[] {"action", "crime", "thriller"});
        MOOD_GENRES.put("thoughtful", new String[] {"drama", "history", "documentary"});
        MOOD_GENRES.put("thrilling", new String[] {"thriller", "mystery", "action"});
    }

    /** Maps the answers (question id to answer id) to the filters the AI returns, so one resolver serves both. */
    static TonightProtocol.Filters filtersFromAnswers(Map<String, String> a) {
        String company = a.containsKey("company") ? a.get("company") : "alone";
        List<String> add = new ArrayList<>(), exclude = new ArrayList<>();
        if ("family".equals(company)) {
            add.add("family");
            exclude.addAll(Arrays.asList("horror", "war", "crime"));
        } else if ("friends".equals(company)) {
            add.addAll(Arrays.asList("comedy", "adventure"));
        }
        Set<String> genres = new LinkedHashSet<>();
        String[] mood = MOOD_GENRES.get(a.get("mood"));
        if (mood != null) genres.addAll(Arrays.asList(mood));
        genres.addAll(add);
        genres.removeAll(exclude);
        String kind = a.get("kind");
        String type = "movie".equals(kind) ? TonightProtocol.MOVIE : "tv".equals(kind) ? TonightProtocol.TV : TonightProtocol.ANY;
        List<String> keywords = a.get("mood") != null && !a.get("mood").isEmpty() ? Collections.singletonList(a.get("mood"))
                : Collections.<String>emptyList();
        return new TonightProtocol.Filters(type, new ArrayList<>(genres), exclude, keywords,
                "short".equals(a.get("length")) ? SHORT_MINUTES : 0, 0, 0, "");
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

        boolean canAnswer() {
            return status == Status.ASKING;
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

    /** Switches to the fixed questions (the AI is unreachable), from the first one, keeping what was said. */
    static State toFallback(State s, TonightText.Tr tr) {
        State f = s.with(Mode.FALLBACK, s.messages, s.turns, Status.ASKING, s.choices, null, 0, Collections.<String, String>emptyMap());
        return askStep(f, 0, TonightText.get(tr, "fallbackNote"), tr);
    }

    /** Records what the person said. The same state when it's empty or the chat isn't waiting for an answer. */
    static State addUser(State s, String text) {
        String clean = TonightProtocol.cleanText(text, TonightProtocol.MAX_TEXT);
        if (clean.isEmpty() || !s.canAnswer()) return s;
        List<TonightProtocol.Msg> msgs = new ArrayList<>(s.messages);
        msgs.add(new TonightProtocol.Msg(true, clean));
        return s.with(s.mode, msgs, s.turns + 1, Status.THINKING, Collections.<Choice>emptyList(), s.filters, s.step, s.answers);
    }

    /** A fixed-question answer (a button or said or typed). An unknown one asks the same question again. */
    static State answerFallback(State s, String input, TonightText.Tr tr) {
        if (s.step >= STEPS.length || !s.canAnswer()) return s;
        Step step = STEPS[s.step];
        String option = matchOption(step, input);
        State withUser = addUser(s, input);
        if (withUser == s) return s;
        if (option == null) {
            return withUser.with(s.mode, say(withUser.messages, TonightText.get(tr, "pickOne")), s.turns, Status.ASKING,
                    choicesFor(step, tr), s.filters, s.step, s.answers);
        }
        Map<String, String> answers = new LinkedHashMap<>(withUser.answers);
        answers.put(step.id, option);
        int next = s.step + 1;
        if (next >= STEPS.length) {
            return withUser.with(s.mode, say(withUser.messages, TonightText.get(tr, "leadIn")), withUser.turns, Status.DONE,
                    Collections.<Choice>emptyList(), filtersFromAnswers(answers), next, answers);
        }
        return askStep(withUser.with(s.mode, withUser.messages, withUser.turns, Status.ASKING, withUser.choices, s.filters,
                s.step, answers), next, null, tr);
    }

    /** The AI's reply: the next question, or the filters (which end the chat). The turn cap ends it too. */
    static State applyReply(State s, TonightProtocol.Reply r, TonightText.Tr tr) {
        List<TonightProtocol.Msg> msgs = say(s.messages, r.say);
        if (r.filters != null) {
            return s.with(s.mode, msgs, s.turns, Status.DONE, Collections.<Choice>emptyList(), r.filters, s.step, s.answers);
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
