package io.watchmatch;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The catalog's first launch, like Netflix's: what you like (movies or series, genres), titles you've watched or like,
 * then how you watch (family filter, subtitles, preview sound, title language). The answers drive the recommendations
 * from day one (Recommend). Settings > Redo my preferences brings it back.
 */
public class CinemaWelcome extends Activity {
    private Page page;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        page = new Page(this);
        setContentView(page);
        page.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        Thumbs.get(this).setListener(page::invalidate);
    }

    @Override protected void onPause() {
        Thumbs.get(this).setListener(null);
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) page.key(e.getKeyCode());
        return true;
    }

    /** The three steps, drawn in a 1920×1080 space. */
    private static final class Page extends View {
        private static final int STEP_LIKES = 0, STEP_TITLES = 1, STEP_HOW = 2;
        private static final float L = 170, CHIP_W = 380, CHIP_H = 84, CHIP_GAP = 24, POSTER_W = 200, POSTER_H = 300,
                POSTER_GAP = 26, GRID_TOP = 300, BUTTON_Y = 940;
        private static final int CHIP_COLUMNS = 4, POSTER_COLUMNS = 7, MAX_TITLES = 63;
        private static final String[][] GENRES = {
            {"action", "Ação", "Action", "Acción"}, {"comedy", "Comédia", "Comedy", "Comedia"},
            {"drama", "Drama", "Drama", "Drama"}, {"thriller", "Suspense", "Thriller", "Suspense"},
            {"crime", "Crime", "Crime", "Crimen"}, {"sci-fi", "Ficção científica", "Sci-Fi", "Ciencia ficción"},
            {"horror", "Terror", "Horror", "Terror"}, {"romance", "Romance", "Romance", "Romance"},
            {"adventure", "Aventura", "Adventure", "Aventura"}, {"animation", "Animação", "Animation", "Animación"},
            {"fantasy", "Fantasia", "Fantasy", "Fantasía"}, {"mystery", "Mistério", "Mystery", "Misterio"},
            {"documentary", "Documentários", "Documentaries", "Documentales"},
        };
        private static final String[] KINDS = {"both", "movies", "series"};
        private static final int H_LEVEL = 0, H_SUBTITLES = 1, H_PREVIEWS = 2, H_TITLES = 3, H_ROWS = 4;

        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int step = STEP_LIKES, row, col;
        private String kind = "both";
        private final Set<String> genres = new LinkedHashSet<>();
        private final List<Library.Title> titles = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final Set<String> picked = new LinkedHashSet<>();
        private boolean loading;
        private float scroll;

        private float k = 1;
        private int w, h;
        private Paint headFont, subFont, chipFont, buttonFont, valueFont, smallFont;
        private Shader glow;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();

        Page(Activity a) {
            super(a);
            activity = a;
            setFocusable(true);
            stroke.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
            w = width;
            h = height;
            k = w / 1920f;
            headFont = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
            subFont = Ui.text(Ui.regular, 30 * k, 0x99FFFFFF);
            chipFont = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
            chipFont.setTextAlign(Paint.Align.CENTER);
            buttonFont = Ui.text(Ui.semibold, 30 * k, 0xFF0B0B0D);
            buttonFont.setTextAlign(Paint.Align.CENTER);
            valueFont = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
            valueFont.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);     // right to left: on the row's left
            smallFont = Ui.text(Ui.medium, 26 * k, 0x8CFFFFFF);
            glow = new RadialGradient((Ui.rtl ? 0.15f : 0.85f) * w, -h * 0.1f, w * 0.7f, 0x66E50914, 0, Shader.TileMode.CLAMP);
        }

        // MARK: - Keys

        void key(int code) {
            code = Ui.dpad(code);       // right to left: the layout is mirrored, ← moves on
            if (code == KeyEvent.KEYCODE_BACK) {
                if (step == STEP_LIKES) finish();      // skipped: the defaults, asked again from Settings
                else go(step - 1);
            } else if (step == STEP_LIKES) {
                likesKey(code);
            } else if (step == STEP_TITLES) {
                titlesKey(code);
            } else {
                howKey(code);
            }
            invalidate();
        }

        private static boolean ok(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        /** Row 0: movies / series; rows 1..4: genres; last: Next. */
        private void likesKey(int code) {
            int genreRows = (GENRES.length + CHIP_COLUMNS - 1) / CHIP_COLUMNS, last = genreRows + 1;
            int cols = row == 0 ? KINDS.length : row == last ? 1 : Math.min(CHIP_COLUMNS, GENRES.length - (row - 1) * CHIP_COLUMNS);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: row = Math.max(0, row - 1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(last, row + 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: col = Math.max(0, col - 1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: col = Math.min(cols - 1, col + 1); break;
                default:
                    if (!ok(code)) return;
                    if (row == 0) {
                        kind = KINDS[col];
                    } else if (row == last) {
                        go(STEP_TITLES);
                        return;
                    } else {
                        String g = GENRES[(row - 1) * CHIP_COLUMNS + col][0];
                        if (!genres.remove(g)) genres.add(g);
                    }
            }
            int now = row == 0 ? KINDS.length : row == last ? 1 : Math.min(CHIP_COLUMNS, GENRES.length - (row - 1) * CHIP_COLUMNS);
            col = Math.min(col, now - 1);
        }

        /** The posters (rows of POSTER_COLUMNS), then Continue. */
        private void titlesKey(int code) {
            int rows = (titles.size() + POSTER_COLUMNS - 1) / POSTER_COLUMNS, last = rows;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: row = Math.max(0, row - 1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(last, row + 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: col = Math.max(0, col - 1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: col = Math.min(row == last ? 0 : POSTER_COLUMNS - 1, col + 1); break;
                default:
                    if (!ok(code)) return;
                    if (row == last) {
                        go(STEP_HOW);
                        return;
                    }
                    int i = row * POSTER_COLUMNS + col;
                    if (i < titles.size() && !picked.remove(titles.get(i).id)) picked.add(titles.get(i).id);
            }
            if (row == last) col = 0;
            else col = Math.min(col, Math.max(0, Math.min(POSTER_COLUMNS, titles.size() - row * POSTER_COLUMNS) - 1));
            scroll = Math.max(0, row - 1) * (POSTER_H + POSTER_GAP);
        }

        private void howKey(int code) {
            Context c = getContext();
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: row = Math.max(0, row - 1); return;
                case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(H_ROWS, row + 1); return;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (row < H_ROWS) change(c, row, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return;
                default:
                    if (!ok(code)) return;
                    if (row == H_ROWS) finish();
                    else change(c, row, 1);
            }
        }

        private static void change(Context c, int which, int by) {
            switch (which) {
                case H_LEVEL: {
                    int[] levels = {CinemaPrefs.ALL, CinemaPrefs.TEENS, CinemaPrefs.KIDS};
                    CinemaPrefs.setLevel(c, levels[cycle(index(levels, CinemaPrefs.level(c)), by, 3)]);
                    break;
                }
                case H_SUBTITLES: {
                    String[] langs = {"ar", "en", "off"};
                    int at = 0;
                    for (int i = 0; i < 3; i++) if (langs[i].equals(CinemaPrefs.subtitles(c))) at = i;
                    CinemaPrefs.setSubtitles(c, langs[cycle(at, by, 3)]);
                    break;
                }
                case H_PREVIEWS: {
                    int[] modes = {CinemaPrefs.PREVIEW_SOUND, CinemaPrefs.PREVIEW_MUTED, CinemaPrefs.PREVIEW_OFF};
                    CinemaPrefs.setPreviews(c, modes[cycle(index(modes, CinemaPrefs.previews(c)), by, 3)]);
                    break;
                }
                default:
                    CinemaPrefs.setArabicTitles(c, !CinemaPrefs.arabicTitles(c));
                    Library.arabicFirst = CinemaPrefs.arabicTitles(c);
            }
        }

        private static int cycle(int at, int by, int n) {
            return ((at < 0 ? 0 : at) + by + n) % n;
        }

        private static int index(int[] a, int v) {
            for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
            return 0;
        }

        private void go(int to) {
            step = to;
            row = col = 0;
            scroll = 0;
            if (to == STEP_TITLES && titles.isEmpty()) loadTitles();
        }

        /**
         * The best-known titles of all time in the genres picked (Recommend.popular: TMDB's most voted, like IMDb's
         * classics, that the catalog has); without TMDB, the server's featured rows and the newest of those genres.
         */
        private void loadTitles() {
            loading = true;
            final Context c = getContext().getApplicationContext();
            final Library library = Library.get(c);
            final List<String> ids = new ArrayList<>();
            for (String[] g : GENRES) if (genres.contains(g[0])) ids.add(CATEGORY_IDS[indexOfGenre(g[0])]);
            final String k1 = kind;
            final Set<String> picked = new HashSet<>(genres);
            library.home(rows -> library.background(() -> {
                List<Library.Title> out = Recommend.popular(c, library, picked, k1, MAX_TITLES);
                if (!out.isEmpty()) {
                    handler.post(() -> {
                        titles.clear();
                        titles.addAll(out);
                        keys.clear();
                        loading = false;
                        invalidate();
                    });
                    return;
                }
                Set<String> seen = new HashSet<>();
                List<List<Library.Title>> lists = new ArrayList<>();
                for (String id : ids) {
                    if (!k1.equals("series")) lists.add(library.genreNow(id, "movies", 0));
                    if (!k1.equals("movies")) lists.add(library.genreNow(id, "series", 0));
                }
                for (Library.Row r : rows) lists.add(r.items);
                for (int i = 0; out.size() < MAX_TITLES && i < 30; i++) {
                    for (List<Library.Title> l : lists) {
                        if (i >= l.size() || out.size() >= MAX_TITLES) continue;
                        Library.Title t = l.get(i);
                        boolean fits = k1.equals("both") || (k1.equals("series") == t.series);
                        if (fits && !t.poster.isEmpty() && seen.add(t.id)) out.add(t);
                    }
                }
                handler.post(() -> {
                    titles.clear();
                    titles.addAll(out);
                    keys.clear();
                    loading = false;
                    invalidate();
                });
            }));
        }

        /** The catalog's category ids, in GENRES' order. */
        private static final String[] CATEGORY_IDS = {"84", "59", "62", "80", "60", "78", "70", "77", "56", "57", "67", "76", "61"};

        private static int indexOfGenre(String g) {
            for (int i = 0; i < GENRES.length; i++) if (GENRES[i][0].equals(g)) return i;
            return 0;
        }

        private void finish() {
            Context c = getContext();
            CinemaPrefs.setKind(c, kind);
            CinemaPrefs.setLikedGenres(c, genres);
            List<Library.Title> liked = new ArrayList<>();
            for (Library.Title t : titles) if (picked.contains(t.id)) liked.add(t);
            CinemaPrefs.setLikedTitles(c, liked);
            CinemaPrefs.setWelcomed(c);
            activity.setResult(RESULT_OK);
            activity.finish();
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            if (headFont == null) return;
            fill.setShader(null);
            fill.setColor(CinemaArt.BLACK);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(glow);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            drawSteps(c);
            if (step == STEP_LIKES) drawLikes(c);
            else if (step == STEP_TITLES) drawTitles(c);
            else drawHow(c);
        }

        /** "1  2  3" at the top right (top left in right to left): where you are. */
        private void drawSteps(Canvas c) {
            for (int i = 0; i < 3; i++) {
                float x = Ui.mx((1920 - 96 - (2 - i) * 56) * k, 0, w), y = 96 * k;
                fill.setColor(i == step ? CinemaView.RED : i < step ? 0xCCFFFFFF : 0x40FFFFFF);
                c.drawCircle(x, y, (i == step ? 10 : 7) * k, fill);
            }
        }

        private void header(Canvas c, String head, String sub) {
            Ui.text(c, head, L * k, 170 * k, headFont, w);
            Ui.text(c, sub, L * k, 226 * k, subFont, w);
        }

        private void drawLikes(Canvas c) {
            header(c, Ui.tr("O que você gosta de assistir?", "What do you like to watch?", "¿Qué te gusta ver?"),
                    Ui.tr("Escolha alguns gêneros. Dá para mudar depois em Ajustes.", "Pick a few genres. You can change this later in Settings.",
                            "Elige algunos géneros. Puedes cambiarlo luego en Ajustes."));
            String[] kinds = {Ui.tr("Filmes e séries", "Movies & series", "Películas y series"), Ui.tr("Filmes", "Movies", "Películas"),
                    Ui.tr("Séries", "Series", "Series", "مسلسلات")};
            for (int i = 0; i < kinds.length; i++) {
                float x = (L + i * (CHIP_W + CHIP_GAP)) * k, y = 280 * k;
                chip(c, x, y, kinds[i], KINDS[i].equals(kind), row == 0 && col == i);
            }
            for (int i = 0; i < GENRES.length; i++) {
                int r = i / CHIP_COLUMNS + 1, cc = i % CHIP_COLUMNS;
                float x = (L + cc * (CHIP_W + CHIP_GAP)) * k, y = (420 + (r - 1) * (CHIP_H + CHIP_GAP)) * k;
                String[] g = GENRES[i];
                chip(c, x, y, Ui.tr(g[1], g[2], g[3]), genres.contains(g[0]), row == r && col == cc);
            }
            int last = (GENRES.length + CHIP_COLUMNS - 1) / CHIP_COLUMNS + 1;
            button(c, Ui.tr("Próximo", "Next", "Siguiente"), row == last);
        }

        /** A choice: red when picked, white when focused. */
        private void chip(Canvas c, float x, float y, String text, boolean on, boolean focused) {
            x = Ui.mx(x, CHIP_W * k, w);
            rect.set(x, y, x + CHIP_W * k, y + CHIP_H * k);
            fill.setColor(focused ? 0xFFFFFFFF : on ? CinemaView.RED : 0x26FFFFFF);
            c.drawRoundRect(rect, CHIP_H * k / 2, CHIP_H * k / 2, fill);
            chipFont.setColor(focused ? 0xFF0B0B0D : 0xFFFFFFFF);
            c.drawText(on ? "✓  " + text : text, rect.centerX(), rect.centerY() + Ui.halfCap(chipFont), chipFont);
        }

        private void button(Canvas c, String text, boolean focused) {
            float bw = Math.max(320 * k, buttonFont.measureText(text) + 96 * k), x = Ui.mx(L * k, bw, w), y = BUTTON_Y * k;
            rect.set(x, y - 40 * k, x + bw, y + 40 * k);
            fill.setColor(focused ? 0xFFFFFFFF : 0x40FFFFFF);
            c.drawRoundRect(rect, 12 * k, 12 * k, fill);
            buttonFont.setColor(focused ? 0xFF0B0B0D : 0xFFFFFFFF);
            c.drawText(text, rect.centerX(), rect.centerY() + Ui.halfCap(buttonFont), buttonFont);
        }

        private void drawTitles(Canvas c) {
            header(c, Ui.tr("Escolha títulos que você viu ou curtiu", "Pick titles you've watched or liked",
                    "Elige títulos que viste o te gustaron"),
                    Ui.tr("Vamos recomendar outros parecidos.", "We'll recommend more like them.", "Te recomendaremos otros parecidos."));
            if (loading) {
                stroke.setStrokeWidth(5 * k);
                stroke.setColor(0xCCFFFFFF);
                Ui.spinner(c, w / 2f, h / 2f, 34 * k, android.os.SystemClock.uptimeMillis() / 1000f, stroke);
                postInvalidateOnAnimation();
                return;
            }
            int rows = (titles.size() + POSTER_COLUMNS - 1) / POSTER_COLUMNS;
            c.save();
            c.clipRect(0, (GRID_TOP - 30) * k, w, (BUTTON_Y - 70) * k);
            for (int i = 0; i < titles.size(); i++) {
                int r = i / POSTER_COLUMNS, cc = i % POSTER_COLUMNS;
                float x = (L + cc * (POSTER_W + POSTER_GAP)) * k, y = (GRID_TOP + r * (POSTER_H + POSTER_GAP) - scroll) * k;
                if (y > h || y + POSTER_H * k < 0) continue;
                poster(c, i, x, y, row == r && col == cc);
            }
            c.restore();
            String label = picked.isEmpty() ? Ui.tr("Pular", "Skip", "Omitir")
                    : Ui.tr("Continuar", "Continue", "Continuar") + "  (" + picked.size() + ")";
            button(c, label, row == rows);
        }

        private void poster(Canvas c, int i, float x, float y, boolean focused) {
            Library.Title t = titles.get(i);
            while (keys.size() <= i) keys.add(null);
            int pw = Math.round(POSTER_W * k), ph = Math.round(POSTER_H * k);
            x = Ui.mx(x, pw, w);
            if (keys.get(i) == null) keys.set(i, Thumbs.key(t.poster, pw, ph, CinemaView.RADIUS * k));
            float s = focused ? 1.06f : 1;
            rect.set(x + pw * (1 - s) / 2, y + ph * (1 - s) / 2, x + pw * (1 + s) / 2, y + ph * (1 + s) / 2);
            Bitmap b = Thumbs.get(getContext()).get(keys.get(i), t.poster, pw, ph, CinemaView.RADIUS * k);
            boolean on = picked.contains(t.id);
            if (b != null) {
                image.setAlpha(on || focused ? 255 : 190);
                c.drawBitmap(b, null, rect, image);
            } else {
                fill.setColor(0xFF1C1C21);
                c.drawRoundRect(rect, 10 * k, 10 * k, fill);
            }
            if (on) {
                float cx = Ui.rtl ? rect.left + 28 * k : rect.right - 28 * k, cy = rect.top + 28 * k;
                fill.setColor(CinemaView.RED);
                c.drawCircle(cx, cy, 20 * k, fill);
                Symbols.draw(getContext(), c, "check", cx, cy, 28 * k, 0xFFFFFFFF);
            }
            if (focused || on) {
                stroke.setStrokeWidth(4 * k);
                stroke.setColor(focused ? 0xFFFFFFFF : CinemaView.RED);
                rect.inset(-4 * k, -4 * k);
                c.drawRoundRect(rect, 14 * k, 14 * k, stroke);
            }
        }

        private void drawHow(Canvas c) {
            Context ctx = getContext();
            header(c, Ui.tr("Por último: como você assiste?", "Last thing: how do you watch?", "Por último: ¿cómo ves?"),
                    Ui.tr("← → para mudar.", "← → to change.", "← → para cambiar."));
            String[] labels = {Ui.tr("Filtro de família", "Family filter", "Filtro familiar"), Ui.tr("Legendas", "Subtitles", "Subtítulos"),
                    Ui.tr("Prévias ao navegar", "Previews while browsing", "Avances al navegar"), Ui.tr("Títulos em", "Titles in", "Títulos en")};
            String[] values = {CinemaPrefs.levelName(CinemaPrefs.level(ctx)), CinemaPrefs.languageName(CinemaPrefs.subtitles(ctx)),
                    CinemaPrefs.previewName(CinemaPrefs.previews(ctx)),
                    CinemaPrefs.arabicTitles(ctx) ? Ui.tr("Árabe", "Arabic", "Árabe") : Ui.tr("Inglês", "English", "Inglés")};
            float right = (L + 1100) * k, valueX = Ui.rtl ? w - right : right;
            for (int i = 0; i < H_ROWS; i++) {
                float y = (300 + i * 100) * k;
                boolean focused = row == i;
                if (Ui.rtl) rect.set(w - right - 24 * k, y, w - L * k + 24 * k, y + 84 * k);
                else rect.set(L * k - 24 * k, y, right + 24 * k, y + 84 * k);
                fill.setColor(focused ? 0xFFFFFFFF : 0x1AFFFFFF);
                c.drawRoundRect(rect, 14 * k, 14 * k, fill);
                float base = rect.centerY() + Ui.halfCap(valueFont);
                chipFont.setTextAlign(Paint.Align.LEFT);
                chipFont.setColor(focused ? 0xFF0B0B0D : 0xCCFFFFFF);
                Ui.text(c, labels[i], L * k, base, chipFont, w);
                chipFont.setTextAlign(Paint.Align.CENTER);
                valueFont.setColor(focused ? 0xFF0B0B0D : 0xFFFFFFFF);
                c.drawText(focused ? "‹  " + values[i] + "  ›" : values[i], valueX, base, valueFont);
            }
            button(c, Ui.tr("Começar a assistir", "Start watching", "Empezar a ver"), row == H_ROWS);
        }
    }
}
