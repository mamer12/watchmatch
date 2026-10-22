package io.watchmatch;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How well a title matches what was typed or said: the exact name, then names starting with it, then a word starting
 * with it, then names containing it. Case, accents and Arabic spelling don't count: "Amélie" is "amelie", and
 * أ إ آ ٱ are ا, ى is ي, ة is ه, without harakat or tatweel; Arabic-Indic digits are 0 to 9.
 */
final class SearchRank {
    private SearchRank() {}

    static final int EXACT = 0, PREFIX = 1, WORD = 2, CONTAINS = 3, NONE = 4;
    private static final Pattern MARKS = Pattern.compile("\\p{Mn}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    static String normalize(String text) {
        if (text == null) return "";
        // NFD splits the hamza and madda off alef, and the accents off Latin letters: MARKS takes them with the harakat.
        String s = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'ـ') continue;                                    // tatweel
            if (c == 'ٱ') c = 'ا';                                // alef wasla
            else if (c == 'ى') c = 'ي';                           // alef maqsura
            else if (c == 'ة') c = 'ه';                           // ta marbuta
            else if (c >= '٠' && c <= '٩') c = (char) ('0' + c - '٠');
            else if (c >= '۰' && c <= '۹') c = (char) ('0' + c - '۰');
            b.append(c);
        }
        return SPACES.matcher(b.toString().toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** `query` already normalized; lower is better. */
    static int score(String title, String query) {
        if (query.isEmpty()) return NONE;
        String t = normalize(title);
        if (t.equals(query)) return EXACT;
        if (t.startsWith(query)) return PREFIX;
        int at = t.indexOf(query);
        if (at < 0) return NONE;
        for (; at > 0; at = t.indexOf(query, at + 1)) {
            if (!Character.isLetterOrDigit(t.charAt(at - 1))) return WORD;
        }
        return CONTAINS;
    }

    /** The better score of a title's two names (the catalog has an English and an Arabic one). */
    static int score(String title, String other, String query) {
        return Math.min(score(title, query), other == null || other.isEmpty() ? NONE : score(other, query));
    }
}
