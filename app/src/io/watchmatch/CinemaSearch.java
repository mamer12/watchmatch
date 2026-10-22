package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Netflix's search, a page of the top bar: an on-screen keyboard on the left (English or Arabic letters and digits, space,
 * delete, clear) and, on the right, the results as you type (wide cards, three across); before typing, suggestions.
 * Right to left, mirrored: the keyboard on the right (its Arabic letters from the right), the results on the left.
 * A remote with letters, or a keyboard, types straight in. Drawn by CinemaView in its 1920×1080 space.
 */
final class CinemaSearch {
    /** Opens a result's page. */
    interface Listener { void openResult(Library.Title t); }

    private static final int RESULT_COLUMNS = 3;
    private static final float KEYS_TOP = 300, RESULTS_X = 840, CARD_W = 320,
            CARD_H = 180, CARD_GAP = 24, RESULTS_TOP = 250, QUERY_TOP = 170;
    private static final long SEARCH_DELAY_MS = 450;

    private final CinemaView v;
    private final Library library;
    private final Thumbs thumbs;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder query = new StringBuilder();
    private boolean inResults, loading;
    private int resultIndex, resultTop;
    private final OnScreenKeyboard keyboard;
    private List<Library.Title> results = new ArrayList<>(), suggestions = new ArrayList<>();
    private final List<String> resultKeys = new ArrayList<>(), resultLabels = new ArrayList<>();
    private Paint queryFont, headFont, cardFont, hintFont;
    private TextPaint queryText, cardText;
    private Shader cardShade;
    private final Runnable search = this::search;
    private final Runnable artArrived;

    CinemaSearch(CinemaView v, Library library, Thumbs thumbs, Listener listener) {
        this.v = v;
        this.library = library;
        this.thumbs = thumbs;
        this.listener = listener;
        keyboard = new OnScreenKeyboard(v, new OnScreenKeyboard.Sink() {
            @Override public void type(String s) {
                CinemaSearch.this.type(s);
            }

            @Override public void delete() {
                CinemaSearch.this.delete();
            }

            @Override public void clear() {
                query.setLength(0);
                changed();
            }

            @Override public void done() { }
        }, false);
        artArrived = () -> {
            resultKeys.clear();
            v.invalidate();
        };
    }

    void sizeChanged() {
        float k = v.k;
        keyboard.sizeChanged();
        queryFont = Ui.text(Ui.semibold, 40 * k, 0xFFFFFFFF);
        headFont = Ui.text(Ui.semibold, 30 * k, 0xCCFFFFFF);
        cardFont = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        hintFont = Ui.text(Ui.regular, 26 * k, 0x8CFFFFFF);
        queryText = new TextPaint(queryFont);
        cardText = new TextPaint(cardFont);
        cardShade = new LinearGradient(0, CARD_H * k * 0.4f, 0, CARD_H * k, 0, 0xD9000000, Shader.TileMode.CLAMP);
        resultKeys.clear();
        resultLabels.clear();
    }

    /** Before anything is typed: these (recommendations, or the featured titles). */
    void setSuggestions(List<Library.Title> list) {
        suggestions = list;
        if (query.length() == 0) showList(list);
    }

    /** Opened with words (from the home screen): searched right away, the focus on the results. */
    void setQuery(String text) {
        query.setLength(0);
        query.append(text == null ? "" : text.trim());
        search();
        inResults = query.length() > 0;
    }

    void reset() {
        inResults = false;
        keyboard.reset();
    }

    // MARK: - Keys (false: ↑ from the keyboard's first row, for the top bar)

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        int unicode = e.getUnicodeChar();
        if (unicode > 0 && !Character.isISOControl(unicode) && code != KeyEvent.KEYCODE_DPAD_CENTER) {
            type(String.valueOf((char) unicode));      // a real keyboard, or a remote with letters
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            delete();
            return true;
        }
        return inResults ? resultsKey(code) : keyboardKey(code);
    }

    private boolean keyboardKey(int code) {
        switch (keyboard.onKey(code)) {
            case OnScreenKeyboard.EXIT_UP: return false;
            case OnScreenKeyboard.EXIT_SIDE:
                if (!shown().isEmpty()) inResults = true;      // toward the results (the other side of the page)
                return true;
            case OnScreenKeyboard.HANDLED: return true;
            default: return false;
        }
    }

    private boolean resultsKey(int code) {
        List<Library.Title> list = shown();
        int col = resultIndex % RESULT_COLUMNS;
        switch (Ui.dpad(code)) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col == 0) inResults = false;
                else resultIndex--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < RESULT_COLUMNS - 1 && resultIndex < list.size() - 1) resultIndex++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (resultIndex >= RESULT_COLUMNS) resultIndex -= RESULT_COLUMNS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (resultIndex + RESULT_COLUMNS < list.size()) resultIndex += RESULT_COLUMNS;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (resultIndex < list.size()) listener.openResult(list.get(resultIndex));
                break;
            case KeyEvent.KEYCODE_BACK:
                inResults = false;
                break;
            default:
                return false;
        }
        int row = resultIndex / RESULT_COLUMNS;
        if (row < resultTop) resultTop = row;
        if (row > resultTop + 2) resultTop = row - 2;
        return true;
    }

    private void type(String s) {
        if (query.length() >= 40) return;
        query.append(s);
        changed();
    }

    private void delete() {
        if (query.length() > 0) query.setLength(query.length() - 1);
        changed();
    }

    private void changed() {
        handler.removeCallbacks(search);
        if (query.toString().trim().length() < 2) {
            loading = false;
            showList(suggestions);
            return;
        }
        loading = true;
        handler.postDelayed(search, SEARCH_DELAY_MS);
    }

    private void search() {
        final String q = query.toString().trim();
        if (q.length() < 2) {
            showList(suggestions);
            return;
        }
        loading = true;
        library.search(q, found -> {
            if (!q.equals(query.toString().trim())) return;     // typed on meanwhile
            loading = false;
            showList(found);
        });
    }

    private void showList(List<Library.Title> list) {
        results = list;
        resultIndex = 0;
        resultTop = 0;
        resultKeys.clear();
        resultLabels.clear();
        if (list.isEmpty()) inResults = false;
        v.invalidate();
        v.kick();
    }

    private List<Library.Title> shown() {
        return results;
    }

    // MARK: - Drawing

    void draw(Canvas c) {
        if (queryFont == null) sizeChanged();
        float k = v.k, x = CinemaView.L * k;
        // The words typed, with a caret.
        float boxW = OnScreenKeyboard.width() * k, bx = Ui.mx(x, boxW, v.w);
        v.rect.set(bx, QUERY_TOP * k, bx + boxW, (QUERY_TOP + 84) * k);
        v.fill.setColor(0x26FFFFFF);
        c.drawRoundRect(v.rect, 14 * k, 14 * k, v.fill);
        String shownQuery = query.length() == 0 ? "" : query.toString();
        float start = Ui.rtl ? v.rect.right - 28 * k : x + 28 * k;     // right to left: the words end at the right
        if (shownQuery.isEmpty()) {
            Ui.textAt(c, Ui.tr("Buscar filmes e séries", "Search movies & series", "Buscar películas y series"), start,
                    v.rect.centerY() + Ui.halfCap(hintFont), hintFont);
        } else {
            String fit = TextUtils.ellipsize(shownQuery, queryText, v.rect.width() - 70 * k, TextUtils.TruncateAt.START).toString();
            Ui.textAt(c, fit, start, v.rect.centerY() + Ui.halfCap(queryFont), queryFont);
            v.fill.setColor(CinemaView.RED);
            float cx = Ui.rtl ? start - queryFont.measureText(fit) - 10 * k : x + 28 * k + queryFont.measureText(fit) + 6 * k;
            c.drawRect(cx, v.rect.top + 22 * k, cx + 4 * k, v.rect.bottom - 22 * k, v.fill);
        }
        keyboard.draw(c, x, KEYS_TOP, !inResults);
        drawResults(c);
    }

    private void drawResults(Canvas c) {
        float k = v.k, x0 = RESULTS_X * k;
        boolean suggesting = query.toString().trim().length() < 2;
        String head = suggesting ? Ui.tr("Sugestões para você", "Suggested for you", "Sugerencias para ti")
                : loading ? Ui.tr("Buscando…", "Searching…", "Buscando…")
                : results.isEmpty() ? Ui.tr("Nada encontrado. Tente outra palavra.", "Nothing found. Try another word.",
                        "Nada encontrado. Prueba otra palabra.")
                : Ui.tr("Resultados", "Results", "Resultados");
        Ui.text(c, head, x0, (RESULTS_TOP - 30) * k, headFont, v.w);
        List<Library.Title> list = shown();
        for (int i = resultTop * RESULT_COLUMNS; i < list.size(); i++) {
            int r = i / RESULT_COLUMNS - resultTop, cc = i % RESULT_COLUMNS;
            if (r > 3) break;
            float x = Ui.mx(x0 + cc * (CARD_W + CARD_GAP) * k, CARD_W * k, v.w), y = (RESULTS_TOP + r * (CARD_H + 40)) * k;
            card(c, i, list.get(i), x, y, inResults && i == resultIndex);
        }
    }

    private void card(Canvas c, int i, Library.Title t, float x, float y, boolean focused) {
        float k = v.k, cw = CARD_W * k, ch = CARD_H * k;
        if (t.backdrop == null) library.backdrop(t, artArrived);
        while (resultKeys.size() <= i) resultKeys.add(null);
        while (resultLabels.size() <= i) resultLabels.add(null);
        String source = t.backdrop != null && !t.backdrop.isEmpty() ? t.backdrop : t.poster;
        if (resultKeys.get(i) == null) resultKeys.set(i, Thumbs.key(source, Math.round(cw), Math.round(ch), CinemaView.RADIUS * k));
        if (resultLabels.get(i) == null) {
            resultLabels.set(i, TextUtils.ellipsize(t.title, cardText, cw - 32 * k, TextUtils.TruncateAt.END).toString());
        }
        float s = focused ? 1.06f : 1;
        v.rect.set(x + cw * (1 - s) / 2, y + ch * (1 - s) / 2, x + cw * (1 + s) / 2, y + ch * (1 + s) / 2);
        Bitmap b = source.isEmpty() ? null : thumbs.get(resultKeys.get(i), source, Math.round(cw), Math.round(ch), CinemaView.RADIUS * k);
        if (b != null) {
            c.drawBitmap(b, null, v.rect, v.image);
        } else {
            v.fill.setColor(0xFF1C1C21);
            c.drawRoundRect(v.rect, 10 * k, 10 * k, v.fill);
        }
        c.save();
        c.translate(v.rect.left, v.rect.top);
        c.scale(s, s);
        v.fill.setShader(cardShade);
        c.drawRoundRect(0, 0, cw, ch, 10 * k, 10 * k, v.fill);
        v.fill.setShader(null);
        Ui.textAt(c, resultLabels.get(i), Ui.rtl ? cw - 16 * k : 16 * k, ch - 18 * k, cardFont);
        c.restore();
        if (focused) {
            v.stroke.setStrokeWidth(4 * k);
            v.stroke.setColor(0xFFFFFFFF);
            v.rect.inset(-4 * k, -4 * k);
            c.drawRoundRect(v.rect, 14 * k, 14 * k, v.stroke);
        }
    }
}
