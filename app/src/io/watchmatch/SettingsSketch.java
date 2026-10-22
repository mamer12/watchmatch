package io.watchmatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import static io.watchmatch.SettingsRows.*;

/**
 * The side panel's diagrams: the Home tab's rows as labelled bars, the shelf's tiles, the remote with what its buttons
 * open, Match Centre's goal bubble (muted in the quiet hours) and the focus with and without motion. Laid out in
 * update() (after a key), drawn without allocating. Coordinates are left to right; right to left mirrors them (mx).
 */
final class SettingsSketch {
    private static final int MAX_BARS = 12, MAX_TILES = HomeApps.MAX_SHELF + 1;

    private final Context context;
    private float k = 1, w = 1920;
    private int kind = -1, accent = 0xFFFFFFFF;
    private final RectF r = new RectF();
    private TextPaint label, small, big, tileFont, strike;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Rows: the Home tab's rows in order, which one is focused, which are hidden.
    private final String[] bars = new String[MAX_BARS];
    private final boolean[] barHidden = new boolean[MAX_BARS];
    private int barCount, barFocus = -1;
    private String appsBar = "", rowsNote = "";
    // Shelf: the tiles, the focused one, a ghost tile for an app not on it yet.
    private final String[] tiles = new String[MAX_TILES];
    private int tileCount, tileFocus = -1;
    private boolean tileGhost;
    private String shelfNote = "";
    // Remote: what each app button opens; the focused one.
    private final String[] keys = new String[4], keyApps = new String[4];
    private int keyFocus = -1;
    private String remoteNote = "";
    // Match: the bubble, muted or off.
    private String home = "", away = "", score = "", minute = "", goal = "", mutedLine = "", matchNote = "";
    private boolean goalsOnly, alertsOff, quiet;
    // Motion: still (reduce motion) or gliding.
    private boolean still;
    private String motionNote = "";

    SettingsSketch(Context c) {
        context = c;
    }

    void sizeChanged(float k, float w) {
        this.k = k;
        this.w = w;
        label = new TextPaint(Ui.text(Ui.medium, 22 * k, 0xE6FFFFFF));
        small = new TextPaint(Ui.text(Ui.regular, 20 * k, 0x99FFFFFF));
        big = new TextPaint(Ui.numbers(Ui.semibold, 38 * k, 0xFFFFFFFF));
        tileFont = new TextPaint(Ui.text(Ui.semibold, 20 * k, 0xF2FFFFFF));
        strike = new TextPaint(label);
        strike.setFlags(strike.getFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        kind = -1;
    }

    boolean handles(int preview) {
        return preview == P_ROWS || preview == P_SHELF || preview == P_REMOTE || preview == P_MATCH || preview == P_MOTION;
    }

    /** The motion demo moves on its own (unless motion is reduced). */
    boolean animating() {
        return kind == P_MOTION && !still;
    }

    // MARK: - Right to left

    float mx(float x) {
        return Ui.rtl ? w - x : x;
    }

    /** `r` set to (l, t, r, b) in left-to-right coordinates, mirrored in right to left. */
    void box(RectF out, float left, float top, float right, float bottom) {
        if (Ui.rtl) out.set(w - right, top, w - left, bottom);
        else out.set(left, top, right, bottom);
    }

    /** Text from x (its start). */
    void start(Canvas c, String s, float x, float y, Paint p) {
        Ui.text(c, s, x, y, p, w);
    }

    /** Text ending at x (its end edge: the right in LTR). */
    void end(Canvas c, String s, float x, float y, Paint p) {
        Paint.Align was = p.getTextAlign();
        p.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        c.drawText(s, mx(x), y, p);
        p.setTextAlign(was);
    }

    void center(Canvas c, String s, float x, float y, Paint p) {
        Paint.Align was = p.getTextAlign();
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, mx(x), y, p);
        p.setTextAlign(was);
    }

    static String fit(String s, TextPaint p, float width) {
        return TextUtils.ellipsize(s == null ? "" : s, p, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }

    // MARK: - Update (after a key)

    void update(Row row, Host h, float width) {
        kind = row.preview;
        accent = Ui.accent();
        switch (kind) {
            case P_ROWS: rows(row, width); break;
            case P_SHELF: shelf(row, h, width); break;
            case P_REMOTE: remote(row, h); break;
            case P_MATCH: match(); break;
            case P_MOTION:
                still = Prefs.reduceMotion();
                motionNote = still ? Ui.tr("Agora: o foco pula", "Now: the focus jumps") : Ui.tr("Agora: o foco desliza", "Now: the focus glides");
                break;
            default: break;
        }
    }

    private void rows(Row row, float width) {
        List<String> layout = row.arg.startsWith("preset:") ? SettingsLists.presetRows(row.arg.substring(7)) : HomeRows.layout();
        barCount = 0;
        barFocus = -1;
        float textW = width - 60 * k;
        for (String e : layout) {
            if (barCount >= MAX_BARS) break;
            String id = HomeRows.id(e);
            if (id.equals(row.arg)) barFocus = barCount;
            barHidden[barCount] = HomeRows.hidden(e);
            bars[barCount++] = fit(HomeRows.label(id), label, textW);
        }
        appsBar = fit(Ui.tr("Apps (sempre primeiro)", "Apps (always first)"), label, textW);
        rowsNote = Ui.tr("Riscadas: ocultas", "Struck out: hidden");
    }

    private void shelf(Row row, Host h, float width) {
        List<String[]> apps = h.shelf();
        tileCount = 0;
        tileFocus = -1;
        tileGhost = false;
        float tileW = (width - 3 * 16 * k) / 4 - 20 * k;
        for (String[] app : apps) {
            if (tileCount >= HomeApps.MAX_SHELF) break;
            if (app[0].equals(row.arg)) tileFocus = tileCount;
            tiles[tileCount++] = fit(app[1], tileFont, tileW);
        }
        if (!row.arg.isEmpty() && tileFocus < 0 && tileCount < HomeApps.MAX_SHELF) {
            tileFocus = tileCount;
            tileGhost = true;
            tiles[tileCount++] = fit("+ " + row.shownLabel, tileFont, tileW);
        }
        shelfNote = Ui.digits(Ui.tr(tileCount + " de " + HomeApps.MAX_SHELF + " lugares", tileCount + " of " + HomeApps.MAX_SHELF + " places",
                tileCount + " de " + HomeApps.MAX_SHELF + " lugares", tileCount + " من " + HomeApps.MAX_SHELF + " أماكن"));
    }

    private void remote(Row row, Host h) {
        String[] names = {"NETFLIX", "YouTube", "prime video", "TCL"};
        keyFocus = row.index;
        for (int i = 0; i < 4; i++) {
            keys[i] = names[i];
            String app = SettingsLists.keyApp(h, i);
            if (i == row.index && row.checked != null) {      // the app picker: the app the focus is on
                int at = h.appPkgs().indexOf(row.arg);
                if (at >= 0) app = h.appLabels().get(at);
            }
            keyApps[i] = fit(app, small, 220 * k);
        }
        remoteNote = RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                "Disney and Globoplay: the TV itself controls those buttons.") : Ui.tr("Só nos controles da TCL", "Only on TCL remotes");
    }

    private void match() {
        List<String> teams = SettingsLists.teamWants();
        home = teams.isEmpty() ? Ui.tr("Seu time", "Your team") : teams.get(0);
        away = Ui.tr("Rival", "Rival");
        home = fit(home, label, 150 * k);
        away = fit(away, label, 150 * k);
        score = Ui.digits(Ui.rtl ? "1 - 2" : "2 - 1");
        minute = "● " + Ui.digits("67'");
        String alerts = Prefs.matchAlerts();
        goalsOnly = alerts.equals("goals");
        alertsOff = alerts.equals("off");
        goal = Ui.tr("GOL!", "GOAL!", "¡GOL!");
        int from = Prefs.quietFrom();
        quiet = from >= 0;
        mutedLine = quiet ? Ui.tr("Silenciado até ", "Muted until ", "Silenciado hasta ", "صامت حتى ") + hour(Prefs.quietUntil()) : "";
        String now = Prefs.quietNow(System.currentTimeMillis()) ? Ui.tr("Silêncio agora", "Quiet now") : "";
        matchNote = alertsOff ? Ui.tr("Alertas desligados: nada aparece", "Alerts off: nothing shows")
                : quiet ? SettingsRows.join(java.util.Arrays.asList(hour(from) + "–" + hour(Prefs.quietUntil()), now))
                : Ui.tr("Pode aparecer a qualquer hora", "Can show at any hour");
    }

    // MARK: - Draw

    void draw(Canvas c, float left, float top, float right, float bottom, float seconds) {
        switch (kind) {
            case P_ROWS: drawRows(c, left, top, right, bottom); break;
            case P_SHELF: drawShelf(c, left, top, right); break;
            case P_REMOTE: drawRemote(c, left, top, right, bottom); break;
            case P_MATCH: drawMatch(c, left, top, right); break;
            case P_MOTION: drawMotion(c, left, top, right, seconds); break;
            default: break;
        }
    }

    /** The Home tab, schematic: the apps first, then each row as a bar; hidden ones dimmed and struck out. */
    private void drawRows(Canvas c, float left, float top, float right, float bottom) {
        float gap = 8 * k, barH = Math.min(46 * k, (bottom - top - 40 * k) / (barCount + 1) - gap);
        float y = top;
        fill.setColor(0x33FFFFFF);
        box(r, left, y, right, y + barH);
        c.drawRoundRect(r, 10 * k, 10 * k, fill);
        label.setColor(0xF2FFFFFF);
        start(c, appsBar, left + 20 * k, y + barH / 2 + Ui.halfCap(label), label);
        y += barH + gap;
        for (int i = 0; i < barCount; i++) {
            boolean hidden = barHidden[i];
            fill.setColor(hidden ? 0x0DFFFFFF : 0x1FFFFFFF);
            box(r, left, y, right, y + barH);
            c.drawRoundRect(r, 10 * k, 10 * k, fill);
            if (i == barFocus) {
                stroke.setStrokeWidth(3 * k);
                stroke.setColor(accent);
                c.drawRoundRect(r, 10 * k, 10 * k, stroke);
            }
            TextPaint p = hidden ? strike : label;
            p.setColor(hidden ? 0x66FFFFFF : 0xE6FFFFFF);
            start(c, bars[i], left + 20 * k, y + barH / 2 + Ui.halfCap(p), p);
            y += barH + gap;
        }
        start(c, rowsNote, left, y + 24 * k, small);
    }

    /** The shelf: its tiles in order (four a row), the focused one ringed; an app not on it shows where it would go. */
    private void drawShelf(Canvas c, float left, float top, float right) {
        float gap = 16 * k, tw = (right - left - 3 * gap) / 4, th = tw * 9 / 16;
        for (int i = 0; i < tileCount; i++) {
            float x = left + (i % 4) * (tw + gap), y = top + (i / 4) * (th + gap + 8 * k);
            box(r, x, y, x + tw, y + th);
            boolean ghost = tileGhost && i == tileFocus;
            fill.setColor(ghost ? 0x0DFFFFFF : 0x2EFFFFFF);
            c.drawRoundRect(r, 14 * k, 14 * k, fill);
            if (i == tileFocus) {
                stroke.setStrokeWidth(3 * k);
                stroke.setColor(accent);
                c.drawRoundRect(r, 14 * k, 14 * k, stroke);
            }
            tileFont.setColor(ghost ? 0x99FFFFFF : 0xF2FFFFFF);
            center(c, tiles[i], x + tw / 2, y + th / 2 + Ui.halfCap(tileFont), tileFont);
        }
        float y = top + 2 * (th + gap + 8 * k) + 20 * k;
        start(c, shelfNote, left, y, small);
    }

    /** The remote's outline: the d-pad, then the four app buttons with the app each opens. */
    private void drawRemote(Canvas c, float left, float top, float right, float bottom) {
        float cx = left + (right - left) * 0.3f, rw = 170 * k, rh = Math.min(bottom - top, 440 * k);
        box(r, cx - rw / 2, top, cx + rw / 2, top + rh);
        stroke.setStrokeWidth(3 * k);
        stroke.setColor(0x66FFFFFF);
        c.drawRoundRect(r, rw / 2, rw / 2, stroke);
        c.drawCircle(mx(cx), top + 110 * k, 50 * k, stroke);
        fill.setColor(0x33FFFFFF);
        c.drawCircle(mx(cx), top + 110 * k, 18 * k, fill);
        float y = top + 210 * k, bh = 40 * k, bw = 130 * k;
        for (int i = 0; i < 4; i++) {
            box(r, cx - bw / 2, y, cx + bw / 2, y + bh);
            boolean on = i == keyFocus;
            fill.setColor(on ? accent : 0x26FFFFFF);
            c.drawRoundRect(r, bh / 2, bh / 2, fill);
            small.setColor(on && accent == 0xFFFFFFFF ? 0xFF1C1C1E : 0xE6FFFFFF);
            center(c, keys[i], cx, y + bh / 2 + Ui.halfCap(small), small);
            // A line to the app's name on the side.
            float lx = cx + rw / 2 + 16 * k;
            stroke.setStrokeWidth(2 * k);
            stroke.setColor(on ? accent : 0x40FFFFFF);
            c.drawLine(mx(cx + bw / 2 + 6 * k), y + bh / 2, mx(lx + 20 * k), y + bh / 2, stroke);
            label.setColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
            start(c, keyApps[i], lx + 30 * k, y + bh / 2 + Ui.halfCap(label), label);
            y += bh + 16 * k;
        }
        small.setColor(0x99FFFFFF);
        start(c, fit(remoteNote, small, right - left), left, top + rh + 36 * k, small);
    }

    /** The bubble a goal brings over what you watch: dimmed when the alerts are off, a muted copy in the quiet hours. */
    private void drawMatch(Canvas c, float left, float top, float right) {
        float bw = Math.min(right - left, 460 * k), bh = goalsOnly ? 150 * k : 120 * k;
        bubble(c, left, top, bw, bh, alertsOff ? 0.35f : 1);
        float y = top + bh + 30 * k;
        if (quiet && !alertsOff) {
            bubble(c, left, y, bw, bh, 0.3f);
            label.setColor(0xFFFFFFFF);
            center(c, mutedLine, left + bw / 2, y + bh / 2 + Ui.halfCap(label), label);
            y += bh + 30 * k;
        }
        small.setColor(0x99FFFFFF);
        start(c, matchNote, left, y + 10 * k, small);
    }

    private void bubble(Canvas c, float left, float top, float bw, float bh, float alpha) {
        box(r, left, top, left + bw, top + bh);
        fill.setColor(Ui.alpha(goalsOnly ? 0xF2E8B53A : 0xEB101318, alpha));
        c.drawRoundRect(r, 28 * k, 28 * k, fill);
        int ink = goalsOnly ? 0xFF111111 : 0xFFFFFFFF;
        float cy = goalsOnly ? top + bh - 52 * k : top + bh / 2;
        if (goalsOnly) {
            big.setColor(Ui.alpha(ink, alpha));
            start(c, goal, left + 28 * k, top + 50 * k, big);
        }
        fill.setColor(Ui.alpha(0xFF2E7D32, alpha));
        c.drawCircle(mx(left + 44 * k), cy, 22 * k, fill);
        fill.setColor(Ui.alpha(0xFF1565C0, alpha));
        c.drawCircle(mx(left + bw - 44 * k), cy, 22 * k, fill);
        big.setColor(Ui.alpha(ink, alpha));
        center(c, score, left + bw / 2, cy + 6 * k, big);
        small.setColor(Ui.alpha(goalsOnly ? 0xCC111111 : 0xFFFF453A, alpha));
        center(c, minute, left + bw / 2, cy + 36 * k, small);
        label.setColor(Ui.alpha(goalsOnly ? 0xCC111111 : 0xB3FFFFFF, alpha));
        start(c, home, left + 76 * k, cy - 30 * k, label);
        end(c, away, left + bw - 76 * k, cy - 30 * k, label);
    }

    /** Three cards and the focus moving along them every second: gliding, or jumping when motion is reduced. */
    private void drawMotion(Canvas c, float left, float top, float right, float seconds) {
        float gap = 24 * k, cw = (right - left - 2 * gap) / 3, ch = cw * 9 / 16;
        float period = 1.2f, t = (seconds % period) / period;
        int step = (int) Math.floor(seconds / period) % 3, prev = (step + 2) % 3;
        float pos = still ? step : prev + (step - prev) * ease(t / 0.4f);
        for (int i = 0; i < 3; i++) {
            float x = left + i * (cw + gap), d = Math.max(0, 1 - Math.abs(pos - i)), s = 1 + 0.06f * d;
            float cx = x + cw / 2, cy = top + ch / 2 + 10 * k;
            box(r, cx - cw * s / 2, cy - ch * s / 2, cx + cw * s / 2, cy + ch * s / 2);
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(r, 14 * k, 14 * k, fill);
        }
        float fx = left + pos * (cw + gap), s = 1.06f, cx = fx + cw / 2, cy = top + ch / 2 + 10 * k;
        box(r, cx - cw * s / 2 - 3 * k, cy - ch * s / 2 - 3 * k, cx + cw * s / 2 + 3 * k, cy + ch * s / 2 + 3 * k);
        stroke.setStrokeWidth(4 * k);
        stroke.setColor(accent);
        c.drawRoundRect(r, 17 * k, 17 * k, stroke);
        label.setColor(0xE6FFFFFF);
        start(c, motionNote, left, top + ch + 80 * k, label);
    }

    private static float ease(float x) {
        float t = Math.max(0, Math.min(1, x));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** Lines that fit (P_GROUP's {name, value} pairs ellipsized for the panel). */
    static List<String[]> fitLines(List<String[]> lines, TextPaint left, TextPaint right, float width, int max) {
        List<String[]> out = new ArrayList<>();
        for (String[] l : lines) {
            if (out.size() >= max) break;
            String v = l.length > 1 && l[1] != null ? l[1] : "";
            String fv = fit(v, right, width * 0.45f);
            float room = width - (fv.isEmpty() ? 0 : right.measureText(fv) + 24 * Math.max(0.5f, left.getTextSize() / 22f));
            out.add(new String[] {fit(l[0], left, room), fv});
        }
        return out;
    }
}
