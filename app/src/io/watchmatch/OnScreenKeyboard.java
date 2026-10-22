package io.watchmatch;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;

/**
 * The on-screen keyboard of Watchme+'s pages (Search types with it, and so do the profile names): English or Arabic
 * letters and digits in six columns, then space, delete, clear and the other alphabet, and (when asked) a wide Done key.
 * Right to left, mirrored: the keyboard sits on the right and its Arabic letters run from the right. It only moves its
 * focus and says what was pressed (Sink); the page owns the text. Drawn in CinemaView's 1920×1080 space.
 * The full keyboard (email and password fields) has no Arabic: Latin letters in either case, digits, a page of symbols,
 * then Aa, #@, delete and clear.
 */
final class OnScreenKeyboard {
    interface Sink {
        void type(String s);
        void delete();
        void clear();
        /** The Done key (only with `withDone`). */
        void done();
    }

    /** What onKey did with a key. */
    static final int HANDLED = 0, EXIT_UP = 1, EXIT_SIDE = 2, IGNORED = 3;

    private static final String LATIN = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final String ARABIC = "ابتثجحخدذرزسشصضطظعغفقكلمنهويةىءأإآ";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
    private static final String SYMBOLS = "@._-+!#$%&*=?/\\()':;,\"~^|<>[]{}`";
    static final int COLUMNS = 6;
    static final float KEY_W = 92, KEY_H = 72, KEY_GAP = 10;
    /** The special row: space, delete, clear, the other alphabet. */
    private static final int K_SPACE = 0, K_DELETE = 1, K_CLEAR = 2, SPECIALS = 4;

    private final CinemaView v;
    private final Sink sink;
    private final boolean withDone, full;
    private boolean arabic, shift, symbols;
    private int keyRow, keyCol;
    private Paint keyFont;

    OnScreenKeyboard(CinemaView v, Sink sink, boolean withDone) {
        this(v, sink, withDone, false);
    }

    /** `full`: the keyboard for email and passwords (see above). */
    OnScreenKeyboard(CinemaView v, Sink sink, boolean withDone, boolean full) {
        this.v = v;
        this.sink = sink;
        this.withDone = withDone;
        this.full = full;
    }

    static float width() {
        return COLUMNS * (KEY_W + KEY_GAP) - KEY_GAP;
    }

    /** Back to the first key (and the Latin letters). */
    void reset() {
        keyRow = keyCol = 0;
        arabic = shift = symbols = false;
    }

    void sizeChanged() {
        keyFont = Ui.text(Ui.semibold, 32 * v.k, 0xFFFFFFFF);
        keyFont.setTextAlign(Paint.Align.CENTER);
    }

    private String alphabet() {
        if (full) return symbols ? SYMBOLS : shift ? UPPER : LATIN;
        return arabic ? ARABIC : LATIN;
    }

    /** Right to left with the Arabic letters: the keys run from the right, in reading order (Latin keys stay as they are). */
    private boolean flipKeys() {
        return Ui.rtl && arabic;
    }

    /** A key's left edge, x in the left-to-right keyboard starting at x0: the keyboard on the right in right to left. */
    private float keyX(float x0, float x, float width) {
        if (!Ui.rtl) return x;
        float panel = width() * v.k, offset = x - x0;
        return flipKeys() ? v.w - x0 - offset - width : v.w - x0 - panel + offset;
    }

    private int letterRows() {
        return (alphabet().length() + COLUMNS - 1) / COLUMNS;
    }

    private int rows() {
        return letterRows() + 1 + (withDone ? 1 : 0);
    }

    private boolean doneRow(int row) {
        return withDone && row == rows() - 1;
    }

    private boolean specialRow(int row) {
        return row == letterRows();
    }

    private int colsIn(int row) {
        if (doneRow(row)) return 1;
        return specialRow(row) ? SPECIALS : Math.min(COLUMNS, alphabet().length() - row * COLUMNS);
    }

    // MARK: - Keys

    /** A d-pad key (already as the remote sent it): HANDLED, or EXIT_UP / EXIT_SIDE when the focus leaves the keyboard. */
    int onKey(int code) {
        int rows = rows();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (keyRow == 0) return EXIT_UP;
                keyRow--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                keyRow = Math.min(rows - 1, keyRow + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                // Right to left, the page's other side is on the left and the Arabic keys run from the right (flipKeys).
                int dx = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, next = keyCol + (flipKeys() ? -dx : dx);
                if (next >= 0 && next < colsIn(keyRow)) keyCol = next;
                else if (dx == (Ui.rtl ? -1 : 1)) return EXIT_SIDE;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                press();
                break;
            default:
                return IGNORED;
        }
        keyCol = Math.min(keyCol, colsIn(keyRow) - 1);
        return HANDLED;
    }

    /**
     * For a page with the keyboard on its trailing side (the form fields on the leading side, as the account forms are):
     * true when `code` moves off the keyboard toward them: ← from the first key, → from the last (the other way round
     * in right to left, where the panel is on the screen's left).
     */
    boolean leaves(int code) {
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        int dx = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, next = keyCol + (flipKeys() ? -dx : dx);
        return !(next >= 0 && next < colsIn(keyRow)) && dx == (Ui.rtl ? 1 : -1);
    }

    private void press() {
        if (doneRow(keyRow)) {
            sink.done();
            return;
        }
        if (!specialRow(keyRow)) {
            sink.type(String.valueOf(alphabet().charAt(keyRow * COLUMNS + keyCol)));
            return;
        }
        if (full) {
            // Aa, #@, delete, clear
            switch (keyCol) {
                case 0: shift = !shift; symbols = false; keyRow = letterRows(); break;
                case 1: symbols = !symbols; keyRow = letterRows(); break;
                case 2: sink.delete(); break;
                default: sink.clear();
            }
            return;
        }
        switch (keyCol) {
            case K_SPACE: sink.type(" "); break;
            case K_DELETE: sink.delete(); break;
            case K_CLEAR: sink.clear(); break;
            default:
                arabic = !arabic;
                keyRow = letterRows();
        }
    }

    // MARK: - Drawing

    /** The keys with the panel's left edge at x0 and its top at `top` (1920 space); `focused` shows the focused key. */
    void draw(Canvas c, float x0, float top, boolean focused) {
        if (keyFont == null) sizeChanged();
        float k = v.k;
        String letters = alphabet();
        int rows = letterRows();
        for (int i = 0; i < letters.length(); i++) {
            int r = i / COLUMNS, cc = i % COLUMNS;
            float x = keyX(x0, x0 + cc * (KEY_W + KEY_GAP) * k, KEY_W * k), y = (top + r * (KEY_H + KEY_GAP)) * k;
            key(c, x, y, KEY_W * k, String.valueOf(letters.charAt(i)), focused && keyRow == r && keyCol == cc);
        }
        // Space (two keys wide), delete, clear, the other alphabet.
        float y = (top + rows * (KEY_H + KEY_GAP)) * k, x = x0;
        String[] names = full
                ? new String[] {shift ? "abc" : "ABC", symbols ? "abc" : "#@!", "⌫", Ui.tr("Limpar", "Clear", "Borrar", "مسح")}
                : new String[] {Ui.tr("Espaço", "Space", "Espacio", "مسافة"), "⌫", Ui.tr("Limpar", "Clear", "Borrar", "مسح"), arabic ? "ABC" : "عربي"};
        float quarter = (width() - 3 * KEY_GAP) / 4;
        float[] widths = full ? new float[] {quarter, quarter, quarter, quarter}
                : new float[] {KEY_W * 2 + KEY_GAP, KEY_W, KEY_W * 2 + KEY_GAP, KEY_W};
        for (int i = 0; i < SPECIALS; i++) {
            key(c, keyX(x0, x, widths[i] * k), y, widths[i] * k, names[i], focused && keyRow == rows && keyCol == i);
            x += (widths[i] + KEY_GAP) * k;
        }
        if (withDone) {
            float dy = (top + (rows + 1) * (KEY_H + KEY_GAP)) * k, w = width() * k;
            key(c, keyX(x0, x0, w), dy, w, Ui.tr("Pronto", "Done", "Listo", "تم"), focused && doneRow(keyRow));
        }
    }

    private void key(Canvas c, float x, float y, float width, String label, boolean focused) {
        float k = v.k;
        v.rect.set(x, y, x + width, y + KEY_H * k);
        v.fill.setColor(focused ? 0xFFFFFFFF : 0x1AFFFFFF);
        c.drawRoundRect(v.rect, 10 * k, 10 * k, v.fill);
        keyFont.setColor(focused ? 0xFF0B0B0D : 0xE6FFFFFF);
        c.drawText(label, v.rect.centerX(), v.rect.centerY() + Ui.halfCap(keyFont), keyFont);
    }
}
