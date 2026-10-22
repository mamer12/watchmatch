package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The billboard at the top of Home, Movies and Series, like Netflix's: one of the catalog's banner titles (its website's
 * carousel) over the whole width, its logo (or name), what it is, the story, and Play · My List · More info.
 * ← → move between the buttons, and past the first or the last, to the previous or next title. CinemaView draws the
 * picture (CinemaArt) and the preview behind it; this draws what's over them. 1920×1080 space, scrolled with the page.
 */
final class CinemaBillboard {
    interface Listener {
        void billboardPlay(Library.Title t);
        void billboardInfo(Library.Title t);
        void billboardList(Library.Title t);
        /** Another title came up (its picture and preview follow). */
        void billboardChanged(Library.Title t);
    }

    /** The billboard's height in the page; the first row overlaps its bottom. */
    static final float HEIGHT = 860;
    private static final int B_PLAY = 0, B_LIST = 1, B_INFO = 2;
    private static final long FADE_MS = 400;
    private static final int MAX = 10;

    private final CinemaView v;
    private final Listener listener;
    private final CinemaLogos logos;
    private final List<Library.Title> items = new ArrayList<>();
    private int index, button;
    private long changedAt;
    private StaticLayout story, name;
    private float storyRight, nameRight;    // where their lines end (right to left: anchored at the right margin)
    private String meta = "", label = "";
    private boolean inList;
    private Paint labelFont, metaFont, buttonFont;
    private TextPaint storyText, nameText;
    private final RectF logoRect = new RectF();

    CinemaBillboard(CinemaView v, CinemaLogos logos, Listener listener) {
        this.v = v;
        this.logos = logos;
        this.listener = listener;
    }

    void sizeChanged(float k) {
        labelFont = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        labelFont.setLetterSpacing(0.2f);
        metaFont = Ui.text(Ui.medium, 28 * k, 0xE6FFFFFF);
        buttonFont = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        storyText = new TextPaint(Ui.text(Ui.regular, 30 * k, 0xD9FFFFFF));
        nameText = new TextPaint(Ui.text(Ui.displaySemibold, 88 * k, 0xFFFFFFFF));
        texts();
    }

    /** The titles to show (the kind of the page: all, movies or series). */
    void setItems(List<Library.Title> list) {
        items.clear();
        for (Library.Title t : list) if (items.size() < MAX) items.add(t);
        index = 0;
        texts();
        if (current() != null) listener.billboardChanged(current());
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    Library.Title current() {
        return items.isEmpty() ? null : items.get(Math.min(index, items.size() - 1));
    }

    /** The next title (when left alone a while, or past the last button). */
    void next(int by) {
        if (items.size() < 2) return;
        index = (index + by + items.size()) % items.size();
        changedAt = SystemClock.uptimeMillis();
        texts();
        listener.billboardChanged(current());
    }

    void myListChanged() {
        Library.Title t = current();
        inList = t != null && CinemaPrefs.inMyList(v.getContext(), t.id);
    }

    // MARK: - Keys (false: not ours, ↑ ↓ for the page)

    boolean onKey(int code) {
        Library.Title t = current();
        if (t == null) return false;
        switch (Ui.dpad(code)) {      // right to left: the buttons run leftwards, ← goes on
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (button > B_PLAY) button--;
                else next(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (button < B_INFO) button++;
                else next(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (button == B_PLAY) listener.billboardPlay(t);
                else if (button == B_LIST) {
                    listener.billboardList(t);
                    myListChanged();
                } else listener.billboardInfo(t);
                return true;
            default:
                return false;
        }
    }

    void resetButton() {
        button = B_PLAY;
    }

    // MARK: - Drawing

    private void texts() {
        Library.Title t = current();
        if (t == null || storyText == null) {
            story = name = null;
            return;
        }
        float k = v.k;
        label = t.series ? Ui.tr("SÉRIE", "SERIES", "SERIE") : Ui.tr("FILME", "FILM", "PELÍCULA");
        meta = t.meta();
        story = t.overview.isEmpty() ? null : Ui.layout(t.overview, storyText, 860 * k, 3);
        name = Ui.layout(t.title, nameText, 1000 * k, 2);
        storyRight = story == null || !Ui.rtl ? 0 : Ui.lineRight(story);
        nameRight = Ui.rtl ? Ui.lineRight(name) : 0;
        myListChanged();
    }

    /** What's over the picture: `top` is the billboard's top on screen (it scrolls up with the page). */
    void draw(Canvas c, float top, boolean focused) {
        Library.Title t = current();
        if (t == null || name == null) return;
        float k = v.k, x = CinemaView.L * k;
        float a = changedAt == 0 ? 1 : Ui.clamp((SystemClock.uptimeMillis() - changedAt) / (float) FADE_MS);
        if (a < 1) v.kick();
        float buttonsY = top + 700 * k;
        // Bottom up: buttons, the story, what it is, the logo or the name, the kind.
        float storyTop = buttonsY - 70 * k - (story == null ? 0 : story.getHeight());
        storyText.setAlpha(Math.round(217 * a));
        if (story != null) {
            c.save();
            c.translate(Ui.layoutX(x, storyRight, v.w), storyTop);
            story.draw(c);
            c.restore();
        }
        float metaBase = storyTop - 24 * k;
        metaFont.setAlpha(Math.round(230 * a));
        Ui.text(c, meta, x, metaBase, metaFont, v.w);
        float nameBottom = metaBase - Ui.cap(metaFont) - 30 * k;
        float nameTop = drawName(c, t, x, nameBottom, a);
        labelFont.setAlpha(Math.round(255 * a));
        v.fill.setColor(Ui.alpha(CinemaView.RED, a));
        float cap = Ui.cap(labelFont);
        float barX = Ui.mx(x, 6 * k, v.w);
        c.drawRect(barX, nameTop - 22 * k - cap, barX + 6 * k, nameTop - 22 * k, v.fill);
        Ui.text(c, label, x + 18 * k, nameTop - 22 * k, labelFont, v.w);
        drawButtons(c, x, buttonsY, focused);
        drawDots(c, buttonsY);
    }

    /** The logo when TMDB has one, else the name; returns its top. */
    private float drawName(Canvas c, Library.Title t, float x, float bottom, float a) {
        float k = v.k;
        Bitmap logo = t.logo.isEmpty() ? null : logos.get(t.logo);
        if (logo != null) {
            float s = Math.min(720 * k / logo.getWidth(), 220 * k / logo.getHeight());
            float lw = logo.getWidth() * s, left = Ui.mx(x, lw, v.w);
            logoRect.set(left, bottom - logo.getHeight() * s, left + lw, bottom);
            v.image.setAlpha(Math.round(255 * a));
            c.drawBitmap(logo, null, logoRect, v.image);
            v.image.setAlpha(255);
            return logoRect.top;
        }
        float nameTop = bottom - name.getHeight();
        nameText.setAlpha(Math.round(255 * a));
        c.save();
        c.translate(Ui.rtl ? Ui.layoutX(x, nameRight, v.w) : x - Ui.leftBearing(nameText, t.title), nameTop);
        name.draw(c);
        c.restore();
        return nameTop;
    }

    private void drawButtons(Canvas c, float x, float y, boolean focused) {
        float k = v.k, bh = 72 * k;
        String[] names = {Ui.tr("Assistir", "Play", "Reproducir"), inList ? Ui.tr("Na lista", "In My List", "En mi lista")
                : Ui.tr("Minha lista", "My List", "Mi lista"), Ui.tr("Mais informações", "More info", "Más información")};
        for (int i = 0; i < names.length; i++) {
            boolean on = focused && i == button;
            float icon = 44 * k, bw = buttonFont.measureText(names[i]) + 68 * k + icon;
            float left = Ui.mx(x, bw, v.w);     // right to left: Play at the right margin, the others leftwards
            v.rect.set(left, y - bh / 2, left + bw, y + bh / 2);
            // Netflix: Play is white, the others grey; the focused one white with a ring.
            boolean white = i == B_PLAY ? !focused || on : on;
            v.fill.setColor(white ? 0xFFFFFFFF : 0x806D6D6E);
            c.drawRoundRect(v.rect, 10 * k, 10 * k, v.fill);
            if (on) {
                v.stroke.setStrokeWidth(3 * k);
                v.stroke.setColor(0xFFFFFFFF);
                v.rect.inset(-6 * k, -6 * k);
                c.drawRoundRect(v.rect, 14 * k, 14 * k, v.stroke);
                v.rect.inset(6 * k, 6 * k);
            }
            int ink = white ? 0xFF141414 : 0xFFFFFFFF;
            float ix = Ui.rtl ? left + bw - 34 * k - 14 * k : x + 34 * k + 14 * k;
            if (i == B_PLAY) Symbols.draw(v.getContext(), c, "play", ix, y, 34 * k, ink);
            else if (i == B_LIST && inList) Symbols.draw(v.getContext(), c, "check", ix, y, 34 * k, ink);
            else if (i == B_LIST) v.drawPlus(c, ix, y, 13 * k, ink);
            else drawInfoIcon(c, ix, y, ink);
            buttonFont.setColor(ink);
            Ui.textAt(c, names[i], Ui.rtl ? left + bw - 34 * k - icon : x + 34 * k + icon, y + Ui.halfCap(buttonFont),
                    buttonFont);
            x += bw + 22 * k;
        }
    }

    /** ⓘ */
    private void drawInfoIcon(Canvas c, float cx, float cy, int ink) {
        float k = v.k, r = 14 * k;
        v.stroke.setStrokeWidth(3 * k);
        v.stroke.setColor(ink);
        c.drawCircle(cx, cy, r, v.stroke);
        v.fill.setColor(ink);
        c.drawRect(cx - 1.5f * k, cy - 3 * k, cx + 1.5f * k, cy + 7 * k, v.fill);
        c.drawCircle(cx, cy - 7 * k, 2 * k, v.fill);
    }

    /** Which of the titles this is, at the right (right to left: at the left, the first one rightmost). */
    private void drawDots(Canvas c, float y) {
        int n = items.size();
        if (n < 2) return;
        float k = v.k, dx = 22 * k, x = v.w - 96 * k - (n - 1) * dx;
        for (int i = 0; i < n; i++) {
            v.fill.setColor(i == index ? 0xFFFFFFFF : 0x59FFFFFF);
            float cx = x + i * dx;
            c.drawCircle(Ui.rtl ? v.w - cx : cx, y, (i == index ? 6 : 5) * k, v.fill);
        }
    }
}
