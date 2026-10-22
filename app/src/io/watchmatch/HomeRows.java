package io.watchmatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Home tab's rows under the apps (Home Settings > Home Rows): their order, which are hidden, and the presets that
 * set both in one step. Nothing saved: the built-in order (DEFAULT), all shown, as the home screen always had them.
 * The apps shelf isn't here: it stays first, under the featured title (HomeView's focus and scrolling start from it).
 */
final class HomeRows {
    private HomeRows() {}

    static final String MATCHES = "matches", MOMENT = "moment", CONTINUE = "continue", CONTINUE_YT = "continue:youtube",
            YOUTUBE = "youtube", TOD = "tod", PICKS = "picks", MY_LIST = "mylist", LIBRARY = "library";
    /** The built-in order (HomeView.buildRows before rows could be moved). */
    static final String[] DEFAULT = {MATCHES, MOMENT, CONTINUE, CONTINUE_YT, YOUTUBE, TOD, PICKS, MY_LIST, LIBRARY};
    static final String[] PRESETS = {"default", "cinema", "sports", "kids", "minimal"};

    /** Every row once, in order; hidden ones as "-id". Rows the saved list doesn't know (newer ones) go at the end. */
    static List<String> layout() {
        return complete(Prefs.homeRows());
    }

    private static List<String> complete(List<String> saved) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (saved != null) {
            for (String old : saved) {
                String entry = LegacyPrefs.row(old);
                if (known(id(entry)) && seen.add(id(entry))) out.add(entry);
            }
        }
        for (String id : DEFAULT) if (seen.add(id)) out.add(id);
        return out;
    }

    static String id(String entry) {
        return entry.startsWith("-") ? entry.substring(1) : entry;
    }

    static boolean hidden(String entry) {
        return entry.startsWith("-");
    }

    private static boolean known(String id) {
        return Arrays.asList(DEFAULT).contains(id);
    }

    /** A preset's rows (null for "default": the built-in order). Shown ones in order, then the rest hidden. */
    static List<String> preset(String name) {
        switch (name) {
            case "cinema":
                return shown(CONTINUE, MOMENT, PICKS, MY_LIST, LIBRARY, TOD, YOUTUBE, CONTINUE_YT, MATCHES);
            case "sports":
                return shown(MATCHES, MOMENT, TOD, CONTINUE, YOUTUBE, CONTINUE_YT, PICKS, MY_LIST, LIBRARY);
            case "kids":
                return shown(CONTINUE, PICKS, MY_LIST, LIBRARY, YOUTUBE);
            case "minimal":
                return shown(CONTINUE, MOMENT);
            default:
                return null;
        }
    }

    private static List<String> shown(String... ids) {
        List<String> out = new ArrayList<>(Arrays.asList(ids));
        for (String id : DEFAULT) if (!out.contains(id)) out.add("-" + id);
        return out;
    }

    static String presetName(String name) {
        switch (name) {
            case "cinema": return Ui.tr("Cinema", "Cinema", "Cine", "سينما");
            case "sports": return Ui.tr("Esportes", "Sports", "Deportes", "رياضة");
            case "kids": return Ui.tr("Crianças", "Kids", "Niños", "أطفال");
            case "minimal": return Ui.tr("Mínimo", "Minimal", "Mínimo", "بسيط");
            case "custom": return Ui.tr("Personalizado", "Custom", "Personalizado", "مخصص");
            default: return Ui.tr("Padrão (o de hoje)", "Default (today's)", "Predeterminado (el de hoy)", "الافتراضي (الحالي)");
        }
    }

    static String label(String id) {
        switch (id) {
            case MATCHES: return Ui.tr("Jogos", "Matches", "Partidos", "المباريات");
            case MOMENT: return Ui.tr("Para agora (manhã, tarde, noite)", "For now (morning, afternoon, tonight)",
                    "Para ahora (mañana, tarde, noche)", "لهذا الوقت (صباحًا، ظهرًا، مساءً)");
            case CONTINUE: return Ui.tr("Continuar assistindo", "Continue watching", "Seguir viendo", "متابعة المشاهدة");
            case CONTINUE_YT: return Ui.tr("Continuar no YouTube", "Continue watching on YouTube", "Seguir viendo en YouTube",
                    "متابعة المشاهدة على YouTube");
            case YOUTUBE: return Ui.tr("YouTube para você", "YouTube for you", "YouTube para ti", "YouTube لك");
            case TOD: return "TOD";
            case PICKS: return Ui.tr("Sugestões do ", "Picks on ", "Sugerencias en ", "مقترحات ") + Ui.isolate(WatchMe.NAME);
            case MY_LIST: return Ui.tr("Minha lista", "My List", "Mi lista", "قائمتي");
            case LIBRARY: return Ui.tr("Novos e destaques do ", "New and featured on ", "Nuevos y destacados en ",
                    "الجديد والمختارات على ") + Ui.isolate(WatchMe.NAME);
            default: return id;
        }
    }
}
