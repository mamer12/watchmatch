package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * The catalog's own settings (Settings in its rail, and the player's panel) and My List. Read and written only in the
 * :cinema process.
 */
final class CinemaPrefs {
    private CinemaPrefs() {}

    /** The catalog's own filter: 0 everything, 2 up to PG-13 / TV-14, 3 children's titles only. */
    static final int ALL = 0, TEENS = 2, KIDS = 3;
    static final int SIZE_SMALL = 0, SIZE_MEDIUM = 1, SIZE_LARGE = 2, SIZE_HUGE = 3;
    /** Subtitle looks: Netflix's soft shadow (the default), a heavy outline, or a dark box behind. */
    static final int STYLE_OUTLINE = 0, STYLE_BOX = 1, STYLE_SHADOW = 2;
    static final int[] STYLES = {STYLE_SHADOW, STYLE_OUTLINE, STYLE_BOX};
    private static final int MY_LIST_MAX = 300, LIKED_MAX = 60;
    /** Bump to clear the catalog's data and ask the welcome questions again (CinemaWelcome) on the next launch. */
    private static final int WELCOME_VERSION = 1;

    /** The active profile's settings, My List, ratings and tastes (on the sync thread: the profile being synced). */
    private static SharedPreferences sp(Context c) {
        return ProfilePrefs.open(c, "settings", Context.MODE_PRIVATE);
    }

    /** What is the TV's, not a profile's: quality, previews. Main's file, whoever is watching. */
    private static SharedPreferences gp(Context c) {
        return ProfilePrefs.open(c, ProfileRules.MAIN, "settings", Context.MODE_PRIVATE);
    }

    private static void saveGlobal(Context c, SharedPreferences.Editor edit) {
        Durable.save(gp(c), edit);
    }

    /** Keys that belong to the TV (they share main's file): everything else in it is main's profile. */
    private static boolean isGlobalKey(String k) {
        return k.equals("height") || k.startsWith(QualityRules.rememberKey("")) || k.equals("previews");
    }

    /** Clears one profile's data, leaving the TV's own keys (a profile deleted: its file is removed instead, except main's). */
    static void clearProfile(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : sp(c).getAll().keySet()) if (!isGlobalKey(k)) e.remove(k);
        Durable.save(sp(c), e);
    }

    /** Settings, My List and ratings are on disk moments after a change (Durable), not only when Android gets to it. */
    private static void save(Context c, SharedPreferences.Editor edit) {
        Durable.save(sp(c), edit);
        Sync.nudge(c);     // the one place settings, My List and ratings change: sync looks at what differs
    }

    /** The family filter in force: a Kids profile is never below "family". */
    static int level(Context c) { return ProfileRules.effectiveLevel(ProfilePrefs.kids(c), rawLevel(c)); }
    /** What was saved (what sync sends), whatever the profile forces. */
    static int rawLevel(Context c) { return sp(c).getInt("level", ALL); }
    static void setLevel(Context c, int v) { save(c, sp(c).edit().putInt("level", v)); }

    /** Skips the scenes the catalog marked for families (on by default once a family filter is picked). */
    static boolean skipScenes(Context c) { return ProfileRules.effectiveSkip(ProfilePrefs.kids(c), rawSkipScenes(c)); }
    static boolean rawSkipScenes(Context c) { return sp(c).getBoolean("skipScenes", rawLevel(c) != ALL); }
    static void setSkipScenes(Context c, boolean v) { save(c, sp(c).edit().putBoolean("skipScenes", v)); }

    /** The highest resolution to play (2160 for 4K). */
    static int height(Context c) { return gp(c).getInt("height", PlayerActivity.DEFAULT_HEIGHT); }
    static void setHeight(Context c, int v) { saveGlobal(c, gp(c).edit().putInt("height", v)); }

    /** The quality picked by hand in the player, remembered per provider (0: none). The mirror starts there, else small. */
    static int remembered(Context c, String providerId) { return gp(c).getInt(QualityRules.rememberKey(providerId), 0); }
    static void setRemembered(Context c, String providerId, int v) {
        saveGlobal(c, gp(c).edit().putInt(QualityRules.rememberKey(providerId), v));
    }

    /** "ar", "en" or "off". */
    static String subtitles(Context c) { return sp(c).getString("subtitles", "ar"); }
    static void setSubtitles(Context c, String v) { save(c, sp(c).edit().putString("subtitles", v)); }

    static int subtitleSize(Context c) { return sp(c).getInt("subtitleSize", SIZE_MEDIUM); }
    static void setSubtitleSize(Context c, int v) { save(c, sp(c).edit().putInt("subtitleSize", v)); }

    static int subtitleStyle(Context c) { return sp(c).getInt("subtitleStyle", STYLE_SHADOW); }

    /** The next style in STYLES (← → in the settings). */
    static void cycleSubtitleStyle(Context c, int by) {
        int at = 0;
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i] == subtitleStyle(c)) at = i;
        setSubtitleStyle(c, STYLES[(at + by + STYLES.length) % STYLES.length]);
    }

    static String styleName(int style) {
        if (style == STYLE_BOX) return Ui.tr("Com fundo", "Background", "Con fondo");
        if (style == STYLE_OUTLINE) return Ui.tr("Contorno", "Outline", "Contorno");
        return Ui.tr("Sombra (Netflix)", "Shadow (Netflix)", "Sombra (Netflix)");
    }
    static void setSubtitleStyle(Context c, int v) { save(c, sp(c).edit().putInt("subtitleStyle", v)); }

    /** White or yellow letters. */
    static boolean subtitleYellow(Context c) { return sp(c).getBoolean("subtitleYellow", false); }
    static void setSubtitleYellow(Context c, boolean v) { save(c, sp(c).edit().putBoolean("subtitleYellow", v)); }

    static boolean autoplay(Context c) { return sp(c).getBoolean("autoplay", true); }
    static void setAutoplay(Context c, boolean v) { save(c, sp(c).edit().putBoolean("autoplay", v)); }

    /** Titles in Arabic first (else English, with the Arabic one when there's no English). */
    static boolean arabicTitles(Context c) { return sp(c).getBoolean("arabicTitles", false); }
    static void setArabicTitles(Context c, boolean v) { save(c, sp(c).edit().putBoolean("arabicTitles", v)); }

    /** Video previews while browsing: with sound (default), muted, or none. */
    static final int PREVIEW_MUTED = 0, PREVIEW_SOUND = 1, PREVIEW_OFF = 2;
    static int previews(Context c) { return gp(c).getInt("previews", PREVIEW_SOUND); }
    static void setPreviews(Context c, int v) { saveGlobal(c, gp(c).edit().putInt("previews", v)); }

    static String previewName(int mode) {
        if (mode == PREVIEW_SOUND) return Ui.tr("Com som", "With sound", "Con sonido");
        if (mode == PREVIEW_OFF) return Ui.tr("Desligadas", "Off", "Desactivadas");
        return Ui.tr("Sem som", "Muted", "Sin sonido");
    }

    /** Subtitle size in the 1920×1080 space. */
    static float subtitlePoints(int size) {
        switch (size) {
            case SIZE_SMALL: return 36;
            case SIZE_LARGE: return 58;
            case SIZE_HUGE: return 70;
            default: return 46;
        }
    }

    static String sizeName(int size) {
        switch (size) {
            case SIZE_SMALL: return Ui.tr("Pequena", "Small", "Pequeña");
            case SIZE_LARGE: return Ui.tr("Grande", "Large", "Grande");
            case SIZE_HUGE: return Ui.tr("Enorme", "Extra large", "Muy grande");
            default: return Ui.tr("Média", "Medium", "Mediana");
        }
    }

    static String levelName(int level) {
        if (level == KIDS) return Ui.tr("Crianças", "Kids", "Niños");
        if (level == TEENS) return Ui.tr("Família (até 13)", "Family (up to PG-13)", "Familia (hasta 13)");
        return Ui.tr("Tudo", "Everything", "Todo");
    }

    static String languageName(String code) {
        if ("ar".equals(code)) return Ui.tr("Árabe", "Arabic", "Árabe");
        if ("en".equals(code)) return Ui.tr("Inglês", "English", "Inglés");
        return Ui.tr("Desligadas", "Off", "Desactivados");
    }

    /** "2160p" → "4K". */
    static String qualityName(int height) {
        return height >= 2160 ? "4K" : height + "p";
    }

    // MARK: - Welcome answers (CinemaWelcome): what to recommend before anything was watched

    static boolean welcomed(Context c) { return sp(c).getInt("welcomed", 0) >= WELCOME_VERSION; }
    static void setWelcomed(Context c) { save(c, sp(c).edit().putInt("welcomed", WELCOME_VERSION)); }

    /** A fresh start: history, Continue watching, My List, answers and settings (WatchMatch's own stay). */
    static void resetAll(Context c) {
        Progress.clear(c);
        save(c, sp(c).edit().clear());
    }

    /** Genres liked, by their English names in lowercase ("action", "sci-fi"). */
    static java.util.Set<String> likedGenres(Context c) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String g : sp(c).getString("likedGenres", "").split(",")) if (!g.trim().isEmpty()) out.add(g.trim());
        return out;
    }

    static void setLikedGenres(Context c, java.util.Collection<String> genres) {
        StringBuilder b = new StringBuilder();
        for (String g : genres) b.append(b.length() == 0 ? "" : ",").append(g);
        save(c, sp(c).edit().putString("likedGenres", b.toString()));
    }

    /** "movies", "series" or "both". */
    static String kind(Context c) { return sp(c).getString("kind", "both"); }
    static void setKind(Context c, String v) { save(c, sp(c).edit().putString("kind", v)); }

    /** Titles picked as watched or liked at the welcome: seeds for "Because you liked…", never recommended back. */
    static List<Library.Title> likedTitles(Context c) {
        return titles(c, "likedTitles");
    }

    static void setLikedTitles(Context c, List<Library.Title> list) {
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < list.size() && i < LIKED_MAX; i++) a.put(Library.json(list.get(i)));
        } catch (Exception e) {
            AppLog.w("liked titles not saved: " + e);
            return;
        }
        save(c, sp(c).edit().putString("likedTitles", a.toString()));
    }

    private static List<Library.Title> titles(Context c, String key) {
        List<Library.Title> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Library.fromJson(a.getJSONObject(i)));
        } catch (Exception e) {
            AppLog.w(key + " not read: " + e);
        }
        return out;
    }

    // MARK: - Ratings (Netflix's thumbs): like and love seed recommendations, a dislike is never recommended

    static final int RATE_NONE = 0, RATE_LIKE = 1, RATE_LOVE = 2, RATE_DISLIKE = -1;

    static int rating(Context c, String id) { return sp(c).getInt("rate:" + id, RATE_NONE); }

    static synchronized void setRating(Context c, Library.Title t, int value) {
        save(c, sp(c).edit().putInt("rate:" + t.id, value));
        List<Library.Title> liked = likedTitles(c), out = new ArrayList<>();
        for (Library.Title x : liked) if (!x.id.equals(t.id)) out.add(x);
        if (value > 0) out.add(0, t);
        setLikedTitles(c, out);
    }

    /** Every rated title and its rating (RATE_LIKE, RATE_LOVE or RATE_DISLIKE). */
    static java.util.Map<String, Integer> ratings(Context c) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (e.getKey().startsWith("rate:") && e.getValue() instanceof Integer && (Integer) e.getValue() != RATE_NONE) {
                out.put(e.getKey().substring(5), (Integer) e.getValue());
            }
        }
        return out;
    }

    /** Changes whenever a title is rated (or unrated): recommendations are made again then. */
    static int ratingsStamp(Context c) {
        int stamp = 0;
        for (java.util.Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (e.getKey().startsWith("rate:")) stamp += (e.getKey() + "=" + e.getValue()).hashCode();
        }
        return stamp;
    }

    /** The titles rated "not for me". */
    static java.util.Set<String> disliked(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (e.getKey().startsWith("rate:") && Integer.valueOf(RATE_DISLIKE).equals(e.getValue())) out.add(e.getKey().substring(5));
        }
        return out;
    }

    // MARK: - Changes that came from another device (Sync)

    /** A rating set elsewhere; `title` (when known) also feeds the liked titles, as a rating made here does. */
    static synchronized boolean applyRemoteRating(Context c, String id, int value, Library.Title title) {
        if (rating(c, id) == value) return false;
        if (title != null && title.id.equals(id)) setRating(c, title, value);
        else save(c, value == RATE_NONE ? sp(c).edit().remove("rate:" + id) : sp(c).edit().putInt("rate:" + id, value));
        return true;
    }

    /** A title added to My List elsewhere goes first; one taken out elsewhere goes. True when the list changed. */
    static synchronized boolean applyRemoteMyList(Context c, String id, Library.Title title) {
        boolean has = inMyList(c, id);
        if (title == null) return has && removeFromMyList(c, id);
        return !has && toggleMyList(c, title);
    }

    private static boolean removeFromMyList(Context c, String id) {
        JSONArray out = new JSONArray();
        try {
            for (Library.Title x : myList(c)) if (!x.id.equals(id)) out.put(Library.json(x));
        } catch (Exception e) {
            AppLog.w("my list not saved: " + e);
            return false;
        }
        save(c, sp(c).edit().putString("myList", out.toString()));
        return true;
    }

    // MARK: - The last recommendations, for WatchMatch's home screen (WatchData)

    static void saveRecommended(Context c, List<Library.Row> rows) {
        JSONArray a = new JSONArray();
        try {
            for (Library.Row r : rows) {
                JSONArray items = new JSONArray();
                for (Library.Title t : r.items) items.put(Library.json(t));
                a.put(new org.json.JSONObject().put("key", r.key).put("title", r.title).put("items", items));
            }
        } catch (Exception e) {
            AppLog.w("recommended not saved: " + e);
            return;
        }
        save(c, sp(c).edit().putString("recommended", a.toString()));
    }

    static String recommendedJson(Context c) {
        return sp(c).getString("recommended", "[]");
    }

    // MARK: - My List (newest first)

    static List<Library.Title> myList(Context c) {
        return titles(c, "myList");
    }

    static boolean inMyList(Context c, String id) {
        for (Library.Title t : myList(c)) if (t.id.equals(id)) return true;
        return false;
    }

    /** Adds the title (first) or takes it out; true when it's now in the list. */
    static synchronized boolean toggleMyList(Context c, Library.Title t) {
        List<Library.Title> list = myList(c);
        boolean had = false;
        JSONArray out = new JSONArray();
        try {
            for (Library.Title x : list) if (x.id.equals(t.id)) had = true;
            if (!had) out.put(Library.json(t));
            for (Library.Title x : list) {
                if (x.id.equals(t.id) || out.length() >= MY_LIST_MAX) continue;
                out.put(Library.json(x));
            }
        } catch (Exception e) {
            AppLog.w("my list not saved: " + e);
            return had;
        }
        save(c, sp(c).edit().putString("myList", out.toString()));
        return !had;
    }

}
