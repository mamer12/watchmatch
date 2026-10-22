package io.watchmatch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spark: a sentence, said or typed in Arabic (Iraqi spelling too) or English, as search filters. "افلام عصابات جديده"
 * and "new gangster movies" are movies · crime · the last three years; "مسلسل بريكينج باد" is a series named that. Words
 * it doesn't know are the title. Pure Java (no Android), so app/test/SparkQueryCheck runs it with plain `java`.
 * Spelling doesn't count (SearchRank.normalize: أ إ آ are ا, ى is ي, ة is ه), nor the article and the "and" in front
 * of a word (ال، وال، بال، لل، و).
 */
final class SparkQuery {
    static final String MOVIE = "movie", TV = "tv";
    /** Era kinds: the last few years, before 2000, a decade, one year, a range, from a year on, up to one. */
    static final String NEW = "new", OLD = "old", DECADE = "decade", YEAR = "year", RANGE = "range", AFTER = "after",
            BEFORE = "before";
    static final String TOP = "top", POPULAR = "popular";
    /** Origins: Arabic, Turkish, Korean, Hindi, Japanese, not Arabic, American, Egyptian, from the Gulf. */
    static final String ARABIC = "ar", TURKISH = "tr", KOREAN = "ko", HINDI = "hi", JAPANESE = "ja", FOREIGN = "foreign",
            AMERICAN = "us", EGYPTIAN = "eg", GULF = "gulf";
    /** How far back "new" goes. */
    static final int NEW_YEARS = 3;
    /** "Old" is before this year. */
    static final int OLD_BEFORE = 2000;

    /** What was said or typed. */
    final String text;
    /** null (both), MOVIE or TV. */
    final String kind;
    /** Genre keys (action, comedy, crime…), in the order said; unmodifiable. */
    final List<String> genres;
    /** null, or one of the era kinds; with `from` and `to` (0 = open). */
    final String era;
    final int from, to;
    final String origin;
    /** Family-safe: nothing above PG-13, no horror. */
    final boolean family;
    /** null, TOP or POPULAR. */
    final String sort;
    /** The words left over, as said ("" when none). */
    final String title;
    /** A type word was said ("movies", "مسلسل"): then it's surely Spark, never a title that happens to have a genre in it. */
    final boolean typed;

    private SparkQuery(String text, String kind, List<String> genres, String era, int from, int to, String origin,
                       boolean family, String sort, String title, boolean typed) {
        this.text = text;
        this.kind = kind;
        this.genres = Collections.unmodifiableList(new ArrayList<>(genres));
        this.era = era;
        this.from = from;
        this.to = to;
        this.origin = origin;
        this.family = family;
        this.sort = sort;
        this.title = title;
        this.typed = typed;
    }

    /** Any filter at all (otherwise it's a plain title search, as before Spark). */
    boolean isSpark() {
        return kind != null || !genres.isEmpty() || era != null || origin != null || family || sort != null;
    }

    boolean hasTitle() {
        return !title.isEmpty();
    }

    boolean isEmpty() {
        return !isSpark() && !hasTitle();
    }

    /** The parts a chip shows, in order: "kind", "genre:crime"…, "origin", "era", "family", "sort", "title". */
    List<String> parts() {
        List<String> out = new ArrayList<>();
        if (kind != null) out.add("kind");
        for (String g : genres) out.add("genre:" + g);
        if (origin != null) out.add("origin");
        if (era != null) out.add("era");
        if (family && !genres.contains("family")) out.add("family");
        if (sort != null) out.add("sort");
        if (hasTitle()) out.add("title");
        return Collections.unmodifiableList(out);
    }

    /** The same query without one of its parts() (a chip taken off). */
    SparkQuery without(String part) {
        List<String> g = new ArrayList<>(genres);
        boolean fam = family;
        if (part.startsWith("genre:")) {
            g.remove(part.substring(6));
            if (part.equals("genre:family")) fam = false;
        }
        boolean noEra = part.equals("era");
        return new SparkQuery(text, part.equals("kind") ? null : kind, g, noEra ? null : era, noEra ? 0 : from,
                noEra ? 0 : to, part.equals("origin") ? null : origin, !part.equals("family") && fam,
                part.equals("sort") ? null : sort, part.equals("title") ? "" : title, typed && !part.equals("kind"));
    }

    @Override public String toString() {
        return "kind=" + kind + " genres=" + genres + " era=" + era + "(" + from + ".." + to + ") origin=" + origin
                + " family=" + family + " sort=" + sort + " title=\"" + title + "\"";
    }

    // MARK: - Parsing

    static SparkQuery parse(String text) {
        return parse(text, Calendar.getInstance().get(Calendar.YEAR));
    }

    /** `thisYear` decides what "new" means (tests pass a fixed one). */
    static SparkQuery parse(String text, int thisYear) {
        String said = text == null ? "" : text.trim();
        String[] raw = SPLIT.split(QUOTES.matcher(said).replaceAll(""));
        List<String> words = new ArrayList<>(), norms = new ArrayList<>();
        for (String w : raw) {
            if (w.isEmpty()) continue;
            words.add(w);
            norms.add(SearchRank.normalize(w));
        }
        int n = words.size();
        boolean[] used = new boolean[n];
        List<List<String>> forms = new ArrayList<>(n);
        for (String w : norms) forms.add(forms(w));

        String kind = null, eraWord = null, origin = null, sort = null;
        int decade = 0;
        boolean family = false, typed = false;
        List<String> genres = new ArrayList<>();
        List<int[]> years = new ArrayList<>();      // {year, -1 before / 0 / 1 after}

        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            int year = year(norms.get(i));
            if (year > 0) {
                used[i] = true;
                int side = 0;
                if (i > 0 && !used[i - 1]) {
                    if (AFTER_WORDS.contains(norms.get(i - 1))) side = 1;
                    else if (BEFORE_WORDS.contains(norms.get(i - 1))) side = -1;
                    if (side != 0) used[i - 1] = true;
                }
                // "2010 to 2015", "من 2010 الى 2015": the word between two years goes with them.
                if (!years.isEmpty() && i > 1 && used[i - 2] && year(norms.get(i - 2)) > 0 && JOIN_WORDS.contains(norms.get(i - 1))) {
                    used[i - 1] = true;
                }
                years.add(new int[] {year, side});
                continue;
            }
            Word hit = null;
            for (Word w : WORDS) {
                if (matches(w, forms, used, i)) {
                    hit = w;
                    break;
                }
            }
            if (hit == null) continue;
            for (int j = 0; j < hit.tokens.length; j++) used[i + j] = true;
            for (String[] e : hit.effects) {
                String key = e[0], value = e.length > 1 ? e[1] : "";
                switch (key) {
                    case "T": kind = value; typed = true; break;
                    case "G": if (!genres.contains(value)) genres.add(value); break;
                    case "F": family = true; if (!genres.contains("family")) genres.add("family"); break;
                    case "E": eraWord = value; break;
                    case "D": decade = Integer.parseInt(value); break;
                    case "S": sort = value; break;
                    case "O":
                        // The most specific origin wins: Egyptian or Gulf over plain Arabic.
                        if (!(ARABIC.equals(value) && (EGYPTIAN.equals(origin) || GULF.equals(origin)))) origin = value;
                        break;
                    default: break;     // "X": a filler phrase ("show me", "ابي اشوف")
                }
            }
            i += hit.tokens.length - 1;
        }

        // The era: years said > a decade > new or old.
        String era = null;
        int from = 0, to = 0;
        if (years.size() >= 2) {
            int a = years.get(0)[0], b = years.get(years.size() - 1)[0];
            era = RANGE;
            from = Math.min(a, b);
            to = Math.max(a, b);
        } else if (years.size() == 1) {
            int y = years.get(0)[0], side = years.get(0)[1];
            era = side > 0 ? AFTER : side < 0 ? BEFORE : YEAR;
            from = side < 0 ? 0 : y;
            to = side > 0 ? 0 : side < 0 ? y - 1 : y;
        } else if (decade > 0) {
            era = DECADE;
            from = decade;
            to = decade + 9;
        } else if (NEW.equals(eraWord)) {
            era = NEW;
            from = thisYear - NEW_YEARS;
        } else if (OLD.equals(eraWord)) {
            era = OLD;
            to = OLD_BEFORE - 1;
        }

        // What's left is the title, without the filler words around it ("show me", "please", "ابي").
        int first = 0, last = n - 1;
        while (first <= last && (used[first] || STOP_WORDS.contains(norms.get(first)))) first++;
        while (last >= first && (used[last] || STOP_WORDS.contains(norms.get(last)))) last--;
        StringBuilder title = new StringBuilder();
        for (int i = first; i <= last; i++) {
            if (used[i]) continue;
            if (title.length() > 0) title.append(' ');
            title.append(words.get(i));
        }
        return new SparkQuery(said, kind, genres, era, from, to, origin, family, sort, title.toString(), typed);
    }

    /** A year from 1900 to 2099, or 0. */
    private static int year(String w) {
        if (w.length() != 4) return 0;
        for (int i = 0; i < 4; i++) if (w.charAt(i) < '0' || w.charAt(i) > '9') return 0;
        int y = Integer.parseInt(w);
        return y >= 1900 && y <= 2099 ? y : 0;
    }

    /** The word, and without the "and" and the article in front (Arabic): والافلام → افلام. */
    private static List<String> forms(String w) {
        List<String> out = new ArrayList<>(4);
        out.add(w);
        if (w.isEmpty() || w.charAt(0) < '؀' || w.charAt(0) > 'ۿ') return out;
        String base = w.length() > 3 && w.charAt(0) == 'و' ? w.substring(1) : w;
        if (!base.equals(w)) out.add(base);
        for (String prefix : ARTICLES) {
            if (base.length() > prefix.length() + 1 && base.startsWith(prefix)) {
                out.add(base.substring(prefix.length()));
                break;
            }
        }
        return out;
    }

    private static boolean matches(Word w, List<List<String>> forms, boolean[] used, int at) {
        if (at + w.tokens.length > forms.size()) return false;
        for (int j = 0; j < w.tokens.length; j++) {
            if (used[at + j] || !forms.get(at + j).contains(w.tokens[j])) return false;
        }
        return true;
    }

    // MARK: - The words it knows

    private static final Pattern QUOTES = Pattern.compile("['’`ʼ]");
    private static final Pattern SPLIT = Pattern.compile("[\\s\\-–—_,.;:!?()\\[\\]{}\"«»“”،؛؟/+&]+");
    private static final String[] ARTICLES = {"بال", "ال", "لل", "كال"};

    /** A phrase (one to three words, normalized) and what it sets: "T=movie", "G=crime;T=tv"… */
    private static final class Word {
        final String[] tokens;
        final String[][] effects;

        Word(String phrase, String effects) {
            tokens = SearchRank.normalize(phrase).split(" ");
            String[] parts = effects.split(";");
            this.effects = new String[parts.length][];
            for (int i = 0; i < parts.length; i++) this.effects[i] = parts[i].split("=", 2);
        }
    }

    private static final List<Word> WORDS = new ArrayList<>();

    private static void add(String effects, String... phrases) {
        for (String p : phrases) WORDS.add(new Word(p, effects));
    }

    static {
        // Fillers that are phrases (one filler word on its own is only dropped around the title, see STOP_WORDS).
        add("X", "show me", "i want", "i wanna", "looking for", "search for", "find me", "give me", "something like",
                "ابي اشوف", "اريد اشوف", "اريد ان اشاهد", "بدي اشوف", "ابغي اشوف", "عايز اشوف", "اكو شي");
        // Type
        add("T=movie", "فلم", "فيلم", "افلام", "فلمات", "فيلمات", "سينما", "movie", "movies", "film", "films", "cinema");
        add("T=tv", "مسلسل", "مسلسلات", "سلسله", "سيريس", "series", "serie", "show", "shows", "tv", "tv show", "tv shows",
                "tv series");
        add("O=ko;T=tv", "k drama", "kdrama", "k dramas", "kdramas", "كي دراما");
        // Genres
        add("G=action", "اكشن", "اكشنات", "حركه", "قتال", "action");
        add("G=comedy", "كوميدي", "كوميديه", "كوميديا", "كوميديات", "مضحك", "مضحكه", "ضحك", "comedy", "comedies",
                "comedic", "funny");
        add("G=drama", "دراما", "درامي", "دراميه", "drama", "dramas", "dramatic");
        add("G=horror", "رعب", "مرعب", "مرعبه", "مخيف", "مخيفه", "horror", "scary");
        add("G=romance", "رومانسي", "رومانسيه", "رومانس", "رومنسي", "رومانسيات", "حب", "غرام", "romance", "romantic", "love");
        add("G=crime", "جريمه", "جرائم", "جرايم", "عصابات", "عصابه", "مافيا", "اجرام", "اجرامي", "اجراميه", "سرقه",
                "سرقات", "crime", "crimes", "gangster", "gangsters", "gang", "gangs", "mafia", "mob", "heist");
        add("G=thriller", "اثاره", "تشويق", "مشوق", "مشوقه", "thriller", "thrillers", "suspense");
        add("G=war", "حرب", "حروب", "حربي", "حربيه", "war", "wars");
        add("G=scifi", "خيال علمي", "خيال علميه", "sci fi", "scifi", "science fiction");
        add("G=fantasy", "فانتازيا", "فنتازيا", "فانتازي", "خيالي", "خياليه", "سحر", "سحري", "fantasy");
        add("G=animation", "رسوم متحركه", "كرتون", "كارتون", "كارتونات", "كرتونات", "انمي", "انيمي", "انيميشن",
                "انميشن", "انيمشن", "animation", "animated", "cartoon", "cartoons", "anime");
        add("G=documentary", "وثائقي", "وثائقيه", "وثائقيات", "وثايقي", "documentary", "documentaries", "docs");
        add("F=1", "family friendly", "عائلي", "عائليه", "عائله", "عوائل", "اطفال", "family", "kids", "children");
        add("G=history", "تاريخي", "تاريخيه", "تاريخ", "history", "historical");
        add("G=mystery", "غموض", "غامض", "غامضه", "mystery", "mysteries", "mysterious");
        add("G=adventure", "مغامره", "مغامرات", "adventure", "adventures");
        // Era
        add("E=new", "brand new", "جديد", "جديده", "جدد", "حديث", "حديثه", "احدث", "اخر", "new", "newest", "latest", "recent");
        add("E=old", "ايام زمان", "زمن جميل", "قديم", "قديمه", "قدامي", "كلاسيكي", "كلاسيكيه", "كلاسيك", "زمان", "old",
                "older", "classic", "classics", "oldies", "vintage");
        add("D=1960", "ستينات", "ستينيات", "60s", "1960s", "sixties");
        add("D=1970", "سبعينات", "سبعينيات", "70s", "1970s", "seventies");
        add("D=1980", "ثمانينات", "ثمانينيات", "80s", "1980s", "eighties");
        add("D=1990", "تسعينات", "تسعينيات", "90s", "1990s", "nineties");
        add("D=2000", "الفينات", "2000s");
        add("D=2010", "2010s");
        // Origin
        add("O=ar", "عربي", "عربيه", "عربيات", "عرب", "arabic", "arab");
        add("O=tr", "تركي", "تركيه", "تركيا", "turkish");
        add("O=ko", "كوري", "كوريه", "كوريا", "korean");
        add("O=hi", "هندي", "هنديه", "هنود", "بوليوود", "indian", "bollywood", "hindi");
        add("O=ja", "ياباني", "يابانيه", "japanese");
        add("O=foreign", "non arabic", "اجنبي", "اجنبيه", "اجانب", "foreign", "international");
        add("O=us", "امريكي", "امريكيه", "امريكاني", "امريكانيه", "هوليوود", "american", "hollywood");
        add("O=eg", "مصري", "مصريه", "مصريات", "egyptian");
        add("O=gulf", "خليجي", "خليجيه", "khaleeji", "gulf");
        // Sort
        add("S=top", "اعلي تقييما", "اعلي تقييم", "top rated", "highest rated", "افضل", "احسن", "اقوي", "اروع", "best",
                "top", "greatest");
        add("S=popular", "اكثر مشاهده", "اكثر شهره", "most popular", "most watched", "مشهور", "مشهوره", "ترند",
                "popular", "trending");
        // Longest phrases first: "خيال علمي" before "خيال".
        Collections.sort(WORDS, (a, b) -> b.tokens.length - a.tokens.length);
    }

    private static Set<String> normalized(String... words) {
        Set<String> out = new HashSet<>();
        for (String w : words) out.add(SearchRank.normalize(w));
        return out;
    }

    /** Dropped only around the title: in the middle they may be part of it ("Game of Thrones"). */
    private static final Set<String> STOP_WORDS = normalized("show", "me", "find", "search", "for", "i", "want", "wanna",
            "to", "watch", "see", "some", "any", "a", "an", "the", "please", "give", "play", "get", "from", "of", "with",
            "in", "on", "and", "or", "like", "good", "nice", "something", "about", "called", "named",
            "ابي", "ابغي", "اريد", "بدي", "عايز", "عاوز", "اباوع", "اشوف", "شوف", "شغل", "شغلي", "شغللي", "اعرض",
            "اعرضلي", "عرض", "ابحث", "دور", "دورلي", "جيب", "جيبلي", "انطيني", "نطيني", "اعطني", "عن", "على", "لي", "الي",
            "من", "في", "و", "بس", "شي", "شيء", "اكو", "عندك", "نوع", "مال", "مالت", "حلو", "حلوه", "يا", "لو", "سمحت",
            "رجاء", "ممكن", "فيه", "بعض", "اي", "مترجم", "مترجمه", "مدبلج", "مدبلجه", "اسمه", "اسمها", "عنوان");
    private static final Set<String> AFTER_WORDS = normalized("after", "since", "from", "بعد", "منذ", "من");
    private static final Set<String> BEFORE_WORDS = normalized("before", "until", "قبل");
    private static final Set<String> JOIN_WORDS = normalized("and", "to", "until", "till", "through", "الى", "حتى", "لغايه", "و");

    /** For tests: the genre keys it knows. */
    static List<String> genreKeys() {
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Word w : WORDS) for (String[] e : w.effects) if (e[0].equals("G")) seen.add(e[1]);
        seen.add("family");
        return new ArrayList<>(seen);
    }
}
