package io.watchmatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;

/**
 * The catalog's settings, a page of the top bar: family filter and scene skipping, quality, subtitles (language, size,
 * style, color), autoplay, previews while browsing, title language, and clearing the history. ← → change a value, OK too.
 */
final class CinemaSettings {
    /** Settings that change what the pages show: they load again. */
    interface Listener {
        void catalogChanged();
        void redoWelcome();
        /** Reset {catalog} data was confirmed. */
        void resetAll();
        /** The Profiles row: manage them (an adult's PIN first, when any adult has one). */
        void manageProfiles();
        /** The Account row: sign in, create, sign out... */
        void manageAccount();
    }

    static final float TOP = 250, ROW_H = 58, WIDTH = 1100;
    private static final int S_LEVEL = 0, S_SCENES = 1, S_QUALITY = 2, S_SUBTITLES = 3, S_SIZE = 4, S_STYLE = 5,
            S_COLOR = 6, S_AUTOPLAY = 7, S_PREVIEWS = 8, S_TITLES = 9, S_REDO = 10, S_CLEAR = 11, S_RESET = 12, S_SYNC = 13,
            S_PROFILES = 14, S_ACCOUNT = 15;
    /** Rows that fit under the header; with the Profiles and Sync rows there are more, and the list scrolls. */
    private static final int VISIBLE = 13;
    /** In the order the setting cycles: with sound first (the default). */
    private static final int[] PREVIEWS = {CinemaPrefs.PREVIEW_SOUND, CinemaPrefs.PREVIEW_MUTED, CinemaPrefs.PREVIEW_OFF};
    private static final int[] LEVELS = {CinemaPrefs.ALL, CinemaPrefs.TEENS, CinemaPrefs.KIDS};
    private static final int[] HEIGHTS = {2160, 1080, 720, 480};
    private static final String[] LANGUAGES = {"ar", "en", "off"};

    private final CinemaView v;
    private final Listener listener;
    private boolean open, confirmClear;
    /** The "Reset {catalog} data?" screen is up; resetFocus: 0 Cancel (the default), 1 Reset. */
    private boolean resetAsk;
    private int resetFocus;
    private int row, first;
    private Paint headerFont, labelFont, valueFont, helpFont;
    private final SyncScreens sync;

    CinemaSettings(CinemaView v, Listener listener) {
        this.v = v;
        this.listener = listener;
        sync = new SyncScreens(v, this);
    }

    private int[] rows;
    private int rowsKey = -1;

    /**
     * The rows, in order: a Kids profile has no Profiles, Reset or Sync rows (those are an adult's), and Sync's row is
     * there only in a build with a sync backend.
     */
    private int[] ids() {
        boolean kids = Profiles.kidsActive(v.getContext());
        int key = (kids ? 1 : 0) | (Sync.available() ? 2 : 0);
        if (rows == null || key != rowsKey) {
            java.util.List<Integer> list = new java.util.ArrayList<>();
            for (int i = S_LEVEL; i <= S_CLEAR; i++) list.add(i);
            if (ProfileRules.allowed(kids, ProfileRules.F_PROFILES)) list.add(S_PROFILES);
            if (Account.available() && ProfileRules.allowed(kids, ProfileRules.F_ACCOUNT)) list.add(S_ACCOUNT);
            if (ProfileRules.allowed(kids, ProfileRules.F_RESET)) list.add(S_RESET);
            if (Sync.available() && ProfileRules.allowed(kids, ProfileRules.F_SYNC)) list.add(S_SYNC);
            rows = new int[list.size()];
            for (int i = 0; i < rows.length; i++) rows[i] = list.get(i);
            rowsKey = key;
        }
        return rows;
    }

    private int count() {
        return ids().length;
    }

    /** The id of the focused row. */
    private int id() {
        int[] ids = ids();
        return ids[Math.min(row, ids.length - 1)];
    }

    private boolean kids() {
        return Profiles.kidsActive(v.getContext());
    }

    Paint headerFont() { return headerFont; }
    Paint labelFont() { return labelFont; }
    Paint valueFont() { return valueFont; }
    Paint helpFont() { return helpFont; }

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
        row = 0;
        confirmClear = false;
        resetAsk = false;
        first = 0;
        rows = null;
        sync.close();
    }

    /** Settings was opened from WatchMatch's Settings straight to the confirmation. */
    void askReset() {
        resetAsk = true;
        resetFocus = 0;
    }

    void close() {
        open = false;
    }

    void sizeChanged() {
        float k = v.k;
        headerFont = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
        labelFont = Ui.text(Ui.medium, 30 * k, 0xCCFFFFFF);
        valueFont = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        valueFont.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);     // right to left: on the list's left
        helpFont = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
    }

    // MARK: - Keys (false: ↑ from the first row, for the top bar)

    boolean onKey(int code) {
        Context c = v.getContext();
        if (resetAsk) return resetKey(code);
        if (sync.isOpen()) return sync.onKey(code);
        switch (Ui.dpad(code)) {      // right to left: ← is the next value
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) return false;
                row--;
                first = Math.min(first, row);
                confirmClear = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = Math.min(count() - 1, row + 1);
                first = Math.max(first, row - VISIBLE + 1);
                confirmClear = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!isButton(id())) change(c, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                change(c, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                int id = id();
                if (id == S_RESET) {
                    resetAsk = true;
                    resetFocus = 0;
                } else if (id == S_SYNC) sync.open();
                else if (id == S_PROFILES) listener.manageProfiles();
                else if (id == S_ACCOUNT) listener.manageAccount();
                else if (id == S_CLEAR) clear(c);
                else if (id == S_REDO) listener.redoWelcome();
                else change(c, 1);
                return true;
            default:
                return false;
        }
    }

    /** The confirmation: ← → between Cancel and Reset, OK picks, Back cancels. */
    private boolean resetKey(int code) {
        switch (Ui.dpad(code)) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                resetFocus = 1 - resetFocus;
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                resetAsk = false;
                if (resetFocus == 1) listener.resetAll();
                return true;
            case KeyEvent.KEYCODE_BACK:
                resetAsk = false;
                return true;
            default:
                return true;     // the page behind stays still
        }
    }

    private static boolean isButton(int id) {
        return id == S_CLEAR || id == S_REDO || id == S_RESET || id == S_SYNC || id == S_PROFILES || id == S_ACCOUNT;
    }

    private void change(Context c, int by) {
        int id = id();
        if (kids() && (id == S_LEVEL || id == S_SCENES)) {
            v.toast(Ui.tr("Definido pelo perfil infantil", "Set by the Kids profile", "Definido por el perfil infantil",
                    "محدد من ملف الأطفال"));
            return;
        }
        switch (id) {
            case S_LEVEL:
                CinemaPrefs.setLevel(c, LEVELS[next(indexOf(LEVELS, CinemaPrefs.level(c)), by, LEVELS.length)]);
                listener.catalogChanged();
                break;
            case S_SCENES:
                CinemaPrefs.setSkipScenes(c, !CinemaPrefs.skipScenes(c));
                break;
            case S_QUALITY:
                CinemaPrefs.setHeight(c, HEIGHTS[next(indexOf(HEIGHTS, CinemaPrefs.height(c)), by, HEIGHTS.length)]);
                break;
            case S_SUBTITLES: {
                int at = 2;
                for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(CinemaPrefs.subtitles(c))) at = i;
                CinemaPrefs.setSubtitles(c, LANGUAGES[next(at, by, LANGUAGES.length)]);
                break;
            }
            case S_SIZE:
                CinemaPrefs.setSubtitleSize(c, next(CinemaPrefs.subtitleSize(c), by, 4));
                break;
            case S_STYLE:
                CinemaPrefs.cycleSubtitleStyle(c, by);
                break;
            case S_COLOR:
                CinemaPrefs.setSubtitleYellow(c, !CinemaPrefs.subtitleYellow(c));
                break;
            case S_AUTOPLAY:
                CinemaPrefs.setAutoplay(c, !CinemaPrefs.autoplay(c));
                break;
            case S_PREVIEWS:
                CinemaPrefs.setPreviews(c, PREVIEWS[next(indexOf(PREVIEWS, CinemaPrefs.previews(c)), by, PREVIEWS.length)]);
                break;
            case S_TITLES:
                CinemaPrefs.setArabicTitles(c, !CinemaPrefs.arabicTitles(c));
                Library.arabicFirst = CinemaPrefs.arabicTitles(c);
                listener.catalogChanged();
                break;
            default:
        }
    }

    private void clear(Context c) {
        if (!confirmClear) {
            confirmClear = true;     // a second OK clears
            return;
        }
        Progress.clear(c);
        confirmClear = false;
        listener.catalogChanged();
    }

    private static int next(int at, int by, int n) {
        return ((at < 0 ? 0 : at) + by + n) % n;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    // MARK: - Drawing

    void draw(Canvas canvas) {
        if (headerFont == null) sizeChanged();
        if (resetAsk) {
            drawReset(canvas);
            return;
        }
        if (sync.isOpen()) {
            sync.draw(canvas);
            return;
        }
        Context c = v.getContext();
        float k = v.k, x = CinemaView.L * k;
        Ui.text(canvas, Ui.tr("Ajustes", "Settings", "Ajustes"), x, 214 * k, headerFont, v.w);
        int[] ids = ids();
        for (int i = first; i < Math.min(ids.length, first + VISIBLE); i++) {
            int id = ids[i];
            boolean focused = i == row;
            drawRow(canvas, (TOP + (i - first) * ROW_H) * k, focused, label(id), value(c, id), focused && !isButton(id),
                    id == S_CLEAR || id == S_RESET);
        }
        Ui.text(canvas, help(id()), x, (TOP + VISIBLE * ROW_H + 40) * k, helpFont, v.w);
    }

    /**
     * One row of a list at y: the name at the margin, the value at the other end (right to left: mirrored); the focused
     * one white. `arrows` puts ‹ › around the value, `red` colors it as a warning.
     */
    void drawRow(Canvas canvas, float y, boolean focused, String label, String value, boolean arrows, boolean red) {
        float k = v.k, x = CinemaView.L * k, right = (CinemaView.L + WIDTH) * k;
        // Right to left, mirrored: the names end at the right margin, the values start at the list's left edge.
        float valueX = Ui.rtl ? v.w - right : right;
        if (focused) {
            if (Ui.rtl) v.rect.set(v.w - right - 24 * k, y, v.w - x + 24 * k, y + (ROW_H - 8) * k);
            else v.rect.set(x - 24 * k, y, right + 24 * k, y + (ROW_H - 8) * k);
            v.fill.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(v.rect, 14 * k, 14 * k, v.fill);
        }
        float base = y + (ROW_H - 8) * k / 2 + Ui.halfCap(labelFont);
        labelFont.setColor(focused ? 0xFF0B0B0D : 0xCCFFFFFF);
        valueFont.setColor(focused ? 0xFF0B0B0D : red ? CinemaView.RED : 0xFFFFFFFF);
        Ui.text(canvas, label, x, base, labelFont, v.w);
        canvas.drawText(arrows ? "‹  " + value + "  ›" : value, valueX, base, valueFont);
    }

    private static String label(int i) {
        switch (i) {
            case S_LEVEL: return Ui.tr("Filtro de família", "Family filter", "Filtro familiar");
            case S_SCENES: return Ui.tr("Pular cenas sensíveis", "Skip sensitive scenes", "Omitir escenas sensibles");
            case S_QUALITY: return Ui.tr("Qualidade", "Quality", "Calidad");
            case S_SUBTITLES: return Ui.tr("Legendas", "Subtitles", "Subtítulos");
            case S_SIZE: return Ui.tr("Tamanho das legendas", "Subtitle size", "Tamaño de subtítulos");
            case S_STYLE: return Ui.tr("Estilo das legendas", "Subtitle style", "Estilo de subtítulos");
            case S_COLOR: return Ui.tr("Cor das legendas", "Subtitle color", "Color de subtítulos");
            case S_AUTOPLAY: return Ui.tr("Próximo episódio automático", "Autoplay next episode", "Siguiente episodio automático");
            case S_PREVIEWS: return Ui.tr("Prévias ao navegar", "Previews while browsing", "Avances al navegar");
            case S_TITLES: return Ui.tr("Títulos em", "Titles in", "Títulos en");
            case S_REDO: return Ui.tr("Refazer minhas preferências", "Redo my preferences", "Rehacer mis preferencias");
            case S_RESET: return Ui.tr("Redefinir os dados do {catalog}", "Reset {catalog} data", "Restablecer los datos de {catalog}",
                    "إعادة ضبط بيانات {catalog}");
            case S_SYNC: return Ui.tr("Sincronização", "Sync", "Sincronización", "المزامنة");
            case S_PROFILES: return Ui.tr("Perfis", "Profiles", "Perfiles", "الملفات");
            case S_ACCOUNT: return AccountText.account();
            default: return Ui.tr("Apagar histórico", "Clear watch history", "Borrar historial");
        }
    }

    private String value(Context c, int i) {
        String on = Ui.tr("Ligado", "On", "Activado"), off = Ui.tr("Desligado", "Off", "Desactivado");
        switch (i) {
            case S_LEVEL: return CinemaPrefs.levelName(CinemaPrefs.level(c));
            case S_SCENES: return CinemaPrefs.skipScenes(c) ? on : off;
            case S_QUALITY: return CinemaPrefs.qualityName(CinemaPrefs.height(c));
            case S_SUBTITLES: return CinemaPrefs.languageName(CinemaPrefs.subtitles(c));
            case S_SIZE: return CinemaPrefs.sizeName(CinemaPrefs.subtitleSize(c));
            case S_STYLE: return CinemaPrefs.styleName(CinemaPrefs.subtitleStyle(c));
            case S_COLOR: return CinemaPrefs.subtitleYellow(c) ? Ui.tr("Amarelo", "Yellow", "Amarillo") : Ui.tr("Branco", "White", "Blanco");
            case S_AUTOPLAY: return CinemaPrefs.autoplay(c) ? on : off;
            case S_PREVIEWS: return CinemaPrefs.previewName(CinemaPrefs.previews(c));
            case S_TITLES: return CinemaPrefs.arabicTitles(c) ? Ui.tr("Árabe", "Arabic", "Árabe") : Ui.tr("Inglês", "English", "Inglés");
            case S_REDO: case S_RESET: return Ui.rtl ? "‹" : "›";
            case S_PROFILES: {
                ProfileRules.Profile p = Profiles.active(c);
                return (p == null ? "" : Ui.isolate(p.name) + "   ") + (Ui.rtl ? "‹" : "›");
            }
            case S_SYNC: return Sync.statusLine();
            case S_ACCOUNT: {
                String email = Account.signedInEmail(c);
                return email.isEmpty() ? AccountText.status("") : Ui.isolate(email);
            }
            default: return confirmClear ? Ui.tr("OK de novo para apagar", "Press OK again to clear", "OK otra vez para borrar") : "";
        }
    }

    private String help(int i) {
        if (kids() && (i == S_LEVEL || i == S_SCENES)) {
            return Ui.tr("Este é um perfil infantil: o filtro de família e as cenas sensíveis ficam sempre ligados.",
                    "This is a Kids profile: the family filter and skipping sensitive scenes are always on.",
                    "Este es un perfil infantil: el filtro familiar y omitir escenas sensibles están siempre activados.",
                    "هذا ملف أطفال: فلتر العائلة وتخطي المشاهد الحساسة مفعّلان دائماً.");
        }
        switch (i) {
            case S_ACCOUNT: return Account.signedInEmail(v.getContext()).isEmpty()
                    ? Ui.tr("Crie uma conta para usar os mesmos perfis e progresso em todos os aparelhos.",
                            "Create an account to use the same profiles and progress on every device.",
                            "Crea una cuenta para usar los mismos perfiles y progreso en todos tus dispositivos.",
                            "أنشئ حساباً لاستخدام الملفات والتقدم نفسيهما على كل جهاز.")
                    : Ui.tr("Sua conta: sair, mudar a senha ou excluir. Perfis e progresso acompanham você em todos os aparelhos.",
                            "Your account: sign out, change the password or delete it. Profiles and progress follow you on every device.",
                            "Tu cuenta: cerrar sesión, cambiar la contraseña o eliminarla. Perfiles y progreso te siguen en todos tus dispositivos.",
                            "حسابك: تسجيل الخروج أو تغيير كلمة المرور أو الحذف. الملفات والتقدم يتبعانك على كل جهاز.");
            case S_PROFILES: return Ui.tr("Quem está assistindo: nome, avatar, PIN e perfil infantil.", "Who is watching: names, avatars, PINs and Kids profiles.",
                    "Quién está viendo: nombres, avatares, PIN y perfiles infantiles.", "من يشاهد: الأسماء والصور الرمزية والرموز وملفات الأطفال.");
            case S_LEVEL: return Ui.tr("Família mostra títulos até 13 anos; Crianças, só os infantis. Vale para tudo no app.",
                    "Family shows titles up to PG-13 / TV-14; Kids, children's titles only. Applies everywhere in the app.",
                    "Familia muestra títulos hasta 13 años; Niños, solo infantiles. Vale para toda la app.");
            case S_SCENES: return Ui.tr("Pula sozinho as cenas que o {catalog} marcou para famílias.",
                    "Skips the scenes {catalog} marked for families, by itself.", "Omite solo las escenas que {catalog} marcó para familias.");
            case S_QUALITY: return Ui.tr("A mais alta a usar. 4K precisa de uma conexão de uns 30 Mbit/s.",
                    "The highest to use. 4K needs a connection of about 30 Mbit/s.", "La más alta a usar. 4K necesita unos 30 Mbit/s.");
            case S_PREVIEWS: return Ui.tr("Um trecho do título começa atrás das informações depois de um instante, como na Netflix.",
                    "A clip of the title starts behind its details after a moment, like Netflix.",
                    "Un fragmento del título empieza detrás de su información tras un momento, como en Netflix.");
            case S_REDO: return Ui.tr("Gêneros e títulos de que você gosta, para as recomendações.",
                    "The genres and titles you like, for the recommendations.", "Los géneros y títulos que te gustan, para las recomendaciones.");
            case S_RESET: return Ui.tr("Apaga Minha Lista, notas, progresso, gostos e imagens guardadas, e recomeça as perguntas.",
                    "Clears My List, ratings, progress, your tastes and saved pictures, then starts the questions again.",
                    "Borra Mi lista, valoraciones, progreso, gustos e imágenes guardadas, y vuelve a las preguntas.",
                    "يمسح قائمتي والتقييمات والتقدم والأذواق والصور المحفوظة ثم يبدأ الأسئلة من جديد.");
            case S_SYNC: return Ui.tr("Mantém Continuar assistindo, Minha Lista, notas e alguns ajustes iguais na TV, no celular e no computador.",
                    "Keeps Continue watching, My List, ratings and a few settings the same on your TV, phone and computer.",
                    "Mantiene Seguir viendo, Mi lista, valoraciones y algunos ajustes iguales en el TV, el móvil y el ordenador.",
                    "تبقي متابعة المشاهدة وقائمتي والتقييمات وبعض الإعدادات متطابقة على التلفاز والهاتف والحاسوب.");
            case S_CLEAR: return Ui.tr("Apaga Continuar assistindo, o progresso e as recomendações.",
                    "Clears Continue watching, progress and recommendations.", "Borra Seguir viendo, el progreso y las recomendaciones.");
            default: return "";
        }
    }

    /** "Reset {catalog} data?": what goes, and two buttons, Cancel first and focused. */
    private void drawReset(Canvas canvas) {
        float k = v.k, x = CinemaView.L * k;
        Ui.text(canvas, Ui.tr("Redefinir os dados do {catalog}?", "Reset {catalog} data?", "¿Restablecer los datos de {catalog}?",
                "إعادة ضبط بيانات {catalog}؟"), x, 300 * k, headerFont, v.w);
        String[] lines = {
            Ui.tr("Minha Lista", "My List", "Mi lista", "قائمتي"),
            Ui.tr("Notas e progresso de cada título", "Ratings and watch progress", "Valoraciones y progreso", "التقييمات والتقدم"),
            Ui.tr("Seus gostos e as perguntas de boas-vindas", "Your tastes and the welcome answers", "Tus gustos y las preguntas de bienvenida",
                    "أذواقك وإجابات الترحيب"),
            Ui.tr("Respostas do catálogo, sugestões e imagens guardadas", "Saved catalog answers, picks and pictures",
                    "Respuestas, sugerencias e imágenes guardadas", "إجابات الكتالوج والاقتراحات والصور المحفوظة"),
        };
        for (int i = 0; i < lines.length; i++) Ui.text(canvas, "•  " + lines[i], x, (390 + i * 56) * k, labelFont, v.w);
        Ui.text(canvas, Ui.tr("Os ajustes do próprio {app} não mudam.", "{app}'s own settings stay as they are.",
                "Los ajustes de {app} no cambian.", "إعدادات {app} نفسه لا تتغير."), x, 650 * k, helpFont, v.w);
        String[] names = {Ui.tr("Cancelar", "Cancel", "Cancelar", "إلغاء"), Ui.tr("Redefinir", "Reset", "Restablecer", "إعادة ضبط")};
        float bx = x, by = 720 * k, bh = 74 * k;
        for (int i = 0; i < 2; i++) {
            float bw = valueFont.measureText(names[i]) + 96 * k;
            boolean focused = i == resetFocus;
            float left = Ui.rtl ? v.w - bx - bw : bx;
            v.rect.set(left, by, left + bw, by + bh);
            v.fill.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
            canvas.drawRoundRect(v.rect, 14 * k, 14 * k, v.fill);
            Paint f = labelFont;
            f.setColor(focused ? 0xFF0B0B0D : i == 1 ? CinemaView.RED : 0xFFFFFFFF);
            Paint.Align was = f.getTextAlign();
            f.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(names[i], left + bw / 2, by + bh / 2 + Ui.halfCap(f), f);
            f.setTextAlign(was);
            bx += bw + 28 * k;
        }
    }
}
