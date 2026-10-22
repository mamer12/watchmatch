package io.watchmatch;

import java.util.Calendar;

/**
 * The moment of the day the home screen dresses for: morning, afternoon, evening ("Tonight") and late night (dimmer,
 * winding down). Its greeting ("Good evening, Sara") and the name of the row picked for it.
 */
final class Moments {
    private Moments() {}

    static final int MORNING = 0, AFTERNOON = 1, EVENING = 2, NIGHT = 3;

    static int now() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(TrustedTime.now());
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) return MORNING;
        if (hour >= 12 && hour < 17) return AFTERNOON;
        if (hour >= 17 && hour < 23) return EVENING;
        return NIGHT;
    }

    /** "Good evening, Sara" (without a name in config.properties, just "Good evening"). */
    static String greeting(int moment) {
        return greeting(moment, "");
    }

    /** The greeting; `profile` (the Watchme+ profile watching, when it has a name of its own) replaces the configured name. */
    static String greeting(int moment, String profile) {
        String hello;
        switch (moment) {
            case MORNING: hello = Ui.tr("Bom dia", "Good morning", "Buenos días"); break;
            case AFTERNOON: hello = Ui.tr("Boa tarde", "Good afternoon", "Buenas tardes"); break;
            case EVENING: hello = Ui.tr("Boa noite", "Good evening", "Buenas noches"); break;
            default: hello = Ui.tr("Boa noite", "Good night", "Buenas noches"); break;
        }
        String name = profile == null || profile.trim().isEmpty() ? Config.NAME.trim() : profile.trim();
        return name.isEmpty() ? hello : Ui.ar ? hello + "\u060C " + Ui.isolate(name) : hello + ", " + name;
    }

    /** The row picked for the moment. */
    static String rowTitle(int moment) {
        switch (moment) {
            case MORNING: return Ui.tr("Para esta manhã", "This morning", "Para esta mañana");
            case AFTERNOON: return Ui.tr("Para esta tarde", "This afternoon", "Para esta tarde");
            case EVENING: return Ui.tr("Hoje à noite", "Tonight", "Esta noche");
            default: return Ui.tr("Antes de dormir", "Before bed", "Antes de dormir");
        }
    }
}
