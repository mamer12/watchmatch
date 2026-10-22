package io.watchmatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static io.watchmatch.SettingsRows.*;

/**
 * The settings' side panel: a live preview of what the focused row does, redrawn when its value changes. The clock and
 * date line as the home screen shows them, a home card at the chosen theme, accent, text size and contrast, the widgets,
 * the comparison with the original home screen, the switch's before and after, {spark}'s filters; the diagrams are in
 * SettingsSketch. Everything is laid out in update() (after a key); draw() only draws.
 */
final class SettingsPreview {
    private static final int MAX_LINES = 10;
    private static final int[] DAY = {0xFF1E57C8, 0xFF4FB9EC}, NIGHT = {0xFF070B1E, 0xFF4B2F8F};
    private static final int[] CARD_COLORS = {0xFF3A4A6B, 0xFF6B3A4A, 0xFF3A6B55};

    private final Context context;
    private final Scene scene;
    private final SettingsSketch sketch;
    private float k = 1, w = 1920, t = 1;
    private final RectF r = new RectF(), panel = new RectF();
    private int kind = P_GROUP, accent = 0xFFFFFFFF;
    private String arg = "";
    private int index = -1;

    private TextPaint header, title, caption, line, lineValue, big, mid, small, numbersBig, thinClock, cardTitle, cardFocus, rowTitle;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sky = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String heading = "", titleText = "";
    private StaticLayout captionLayout;
    private float captionRight;
    private boolean warn, busy;
    // P_GROUP
    private String icon;
    private List<String[]> lines = new ArrayList<>();
    // P_MODE
    private final String[] modeLabel = new String[4], modeA = new String[4], modeB = new String[4];
    private String colA = "", colB = "", busyText = "";
    private int current, target;
    // P_CLOCK, P_WIDGETS, P_SCREENSAVER
    private final String[] tabs = new String[3];
    private final float[] tabX = new float[3];
    private String clock = "", date = "", weather = "", weatherSymbol, clockLine = "", greeting = "", pricesTitle = "", pricesOff = "";
    private float underlineX, underlineW;
    private int underlineAt;
    private boolean weatherOn, quotesOn;
    private final String[] coinName = new String[4], coinPrice = new String[4], coinChange = new String[4];
    private final boolean[] coinUp = new boolean[4], coinOn = new boolean[4];
    private int coinCount, coinFocus = -1;
    // P_CARD
    private String rowName = "", sun = "sunny";
    private final String[] cards = new String[3];
    private boolean contrast;
    // P_PERF
    private final String[] perfLabel = new String[3], perfA = new String[3], perfB = new String[3];
    private final String[] effect = new String[2], effectNote = new String[2];
    private boolean effectsOn;
    private String perfMode = "";
    private int perfFocus = -1;
    // P_ABOUT
    private String version = "", tagline = "";
    // P_SEARCH
    private String query = "", results = "";
    private final String[] chips = new String[5];
    private final float[] chipX = new float[5], chipY = new float[5], chipW = new float[5];
    private int chipCount;
    private boolean voice;

    SettingsPreview(Context c, Scene scene) {
        context = c;
        this.scene = scene;
        sketch = new SettingsSketch(c);
        stroke.setStyle(Paint.Style.STROKE);
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The panel's place (left-to-right coordinates; mirrored in right to left) and the text size. */
    void sizeChanged(float k, float w, float textSize, float left, float top, float right, float bottom) {
        this.k = k;
        this.w = w;
        t = textSize;
        panel.set(left, top, right, bottom);
        header = paint(Ui.semibold, 19 * k, 0x80FFFFFF);
        header.setLetterSpacing(0.12f);
        title = paint(Ui.semibold, 32 * k * t, 0xFFFFFFFF);
        caption = paint(Ui.regular, 25 * k * t, 0xCCFFFFFF);
        line = paint(Ui.regular, 23 * k * t, 0xE6FFFFFF);
        lineValue = paint(Ui.regular, 23 * k * t, 0x99FFFFFF);
        big = paint(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        mid = paint(Ui.medium, 23 * k, 0xE6FFFFFF);
        small = paint(Ui.regular, 20 * k, 0x99FFFFFF);
        numbersBig = new TextPaint(Ui.numbers(Ui.semibold, 50 * k, 0xFFFFFFFF));
        thinClock = new TextPaint(Ui.numbers(Ui.thin, 120 * k, 0xFFFFFFFF));
        rowTitle = paint(Ui.semibold, 25 * k, 0xF2FFFFFF);
        cardTitle = paint(Ui.medium, 21 * k, 0xB3FFFFFF);
        cardFocus = paint(Ui.semibold, 21 * k, 0xFFFFFFFF);
        spinner.setStrokeWidth(3.5f * k);
        sketch.sizeChanged(k, w);
    }

    private static TextPaint paint(android.graphics.Typeface face, float size, int color) {
        return new TextPaint(Ui.text(face, size, color));
    }

    boolean animating() {
        return busy && kind == P_MODE || sketch.animating();
    }

    private float pad() {
        return 44 * k;
    }

    private float areaTop() {
        return panel.top + 140 * k * Math.min(t, 1.15f);
    }

    private float areaBottom() {
        float captionH = captionLayout == null ? 0 : captionLayout.getHeight();
        return panel.bottom - pad() - captionH - 28 * k;
    }

    // MARK: - Update (after a key, or when the state changes)

    void update(Row row, Host h, boolean main) {
        if (row == null) {      // an empty list (no teams yet): an empty panel
            kind = P_GROUP;
            icon = null;
            lines = new ArrayList<>();
            titleText = "";
            captionLayout = null;
            return;
        }
        kind = row.preview;
        accent = Ui.accent();
        arg = row.arg;
        index = row.index;
        warn = row.shownWarn;
        heading = Ui.tr("PRÉVIA", "PREVIEW");
        float inner = panel.width() - 2 * pad();
        titleText = SettingsSketch.fit(row.label.get(), title, inner);
        caption.setColor(warn ? 0xFFFFC266 : 0xCCFFFFFF);
        String about = row.fullAbout;
        captionLayout = about.isEmpty() ? null : Ui.layout(about, caption, inner, 5);
        captionRight = captionLayout == null ? 0 : Ui.lineRight(captionLayout);
        busy = h.busy();
        if (sketch.handles(kind)) {
            sketch.update(row, h, inner);
            return;
        }
        switch (kind) {
            case P_MODE: mode(h, inner); break;
            case P_CLOCK: clock(h, inner); break;
            case P_WIDGETS: widgets(h, inner); break;
            case P_CARD: card(inner); break;
            case P_PERF: perf(h, inner); break;
            case P_ABOUT:
                version = Config.APP_NAME + " " + Ui.digits(versionName(context));
                tagline = Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver");
                break;
            case P_SEARCH: search(inner); break;
            case P_SCREENSAVER: clock(h, inner); break;
            default:
                icon = main ? row.icon : null;
                lines = row.lines == null ? new ArrayList<>() : SettingsSketch.fitLines(row.lines.get(), line, lineValue, inner, MAX_LINES);
                break;
        }
    }

    private void mode(Host h, float inner) {
        current = h.freeActive() ? 1 : 0;
        target = arg.equals(HomeMode.FREE) ? 1 : arg.equals(HomeMode.ORIGINAL) ? 0 : -1;
        colA = Ui.tr("Original", "Original");
        colB = Config.APP_NAME;
        float col = inner * 0.3f;
        String own = Ui.tr("A da TV", "The TV's own");
        modeLabel[0] = SettingsSketch.fit(Ui.tr("Tela inicial", "Home screen"), small, inner * 0.38f);
        modeLabel[1] = SettingsSketch.fit(Ui.tr("Tela de Descanso", "Screensaver"), small, inner * 0.38f);
        modeLabel[2] = SettingsSketch.fit(Ui.tr("Apps de propaganda", "Promo apps"), small, inner * 0.38f);
        modeLabel[3] = SettingsSketch.fit(Ui.tr("Botões do controle", "Remote buttons"), small, inner * 0.38f);
        modeA[0] = SettingsSketch.fit(own, mid, col);
        modeB[0] = SettingsSketch.fit(Config.APP_NAME, mid, col);
        modeA[1] = SettingsSketch.fit(own, mid, col);
        modeB[1] = SettingsSketch.fit(Ui.tr("Céu e relógio", "Sky and clock"), mid, col);
        modeA[2] = SettingsSketch.fit(Ui.tr("Ligados", "On"), mid, col);
        String n = Ui.digits(String.valueOf(h.promoApps()));
        modeB[2] = SettingsSketch.fit(Ui.tr(n + " desligados", n + " off", n + " desactivadas", n + " متوقفة"), mid, col);
        modeA[3] = SettingsSketch.fit(Ui.tr("Como antes", "As before"), mid, col);
        modeB[3] = SettingsSketch.fit(RemoteKeys.supported() ? Ui.tr("Os seus apps", "Your picks") : Ui.tr("Como antes", "As before"), mid, col);
        busyText = busy ? SettingsSketch.fit(SettingsGroups.busyText(h), mid, inner - 50 * k) : "";
    }

    private void clock(Host h, float inner) {
        long now = System.currentTimeMillis();
        clock = Ui.clock(now);
        String d = Ui.date(now);
        date = d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
        HomeData.Weather wx = HomeData.get(context).weather;
        String city = Ui.ar ? Arabic.city(Prefs.city()) : Prefs.city();
        boolean known = wx != null && !Double.isNaN(wx.temp);
        weather = HomeData.degrees(known ? wx.temp : 24) + (city.isEmpty() ? "" : "  " + city);
        weatherSymbol = known && wx.code >= 0 ? Symbols.weather(wx.code, wx.isDay) : "sunny";
        clockLine = SettingsSketch.fit(date + "  ·  " + weather, small, inner - 48 * k);
        tabs[0] = Ui.tr("Início", "Home", "Inicio");
        tabs[1] = Ui.tr("Descobrir", "Discover", "Descubrir");
        tabs[2] = Ui.ar ? "التطبيقات" : "Apps";
        float x = 0;
        for (int i = 0; i < 3; i++) {
            tabX[i] = x;
            x += mid.measureText(tabs[i]) + 30 * k;
        }
        underlineAt = arg.equals("language") ? 1 : arg.equals("temperature") ? 3 : arg.isEmpty() ? 0 : 2;
        underlineX = 0;
        underlineW = underlineAt == 1 ? x - 30 * k : underlineAt == 2 ? thinClock.measureText(clock)
                : underlineAt == 3 ? mid.measureText(weather) + 44 * k : 0;
    }

    private void widgets(Host h, float inner) {
        clock(h, inner);
        greeting = SettingsSketch.fit(Moments.greeting(Moments.now()), big, inner);
        weatherOn = Prefs.weather();
        quotesOn = Prefs.quotes();
        pricesTitle = Ui.tr("COTAÇÕES", "PRICES");
        pricesOff = Ui.tr("Widget de cotações desligado", "Prices widget off");
        HomeData data = HomeData.get(context);
        int coins = "BRL".equalsIgnoreCase(Config.CURRENCY.trim()) ? 4 : 3;
        coinCount = coins;
        coinFocus = arg.equals("coin") ? index : -1;
        for (int i = 0; i < coins; i++) {
            coinName[i] = SettingsSketch.fit(HomeData.coinName(i), small, inner / coins - 24 * k);
            coinPrice[i] = HomeData.compact(data.prices[i]);
            coinChange[i] = HomeData.change(data.changes[i]);
            coinUp[i] = !(data.changes[i] < 0);
            coinOn[i] = Prefs.coin(i);
        }
    }

    private void card(float inner) {
        boolean night = HomeData.get(context).isNight(System.currentTimeMillis());
        float top = areaTop(), bottom = top + 330 * k;
        int[] c = night ? NIGHT : DAY;
        sky.setShader(new LinearGradient(0, top, 0, bottom, c[1], c[0], Shader.TileMode.CLAMP));
        sun = night ? "clear_night" : "sunny";
        contrast = A11y.contrast;
        float scale = A11y.text;
        rowTitle.setTextSize(25 * k * scale);
        cardTitle.setTextSize(21 * k * scale);
        cardFocus.setTextSize(21 * k * scale);
        cardTitle.setColor(contrast ? 0xF2FFFFFF : 0xB3FFFFFF);
        rowName = SettingsSketch.fit(Ui.tr("Continuar assistindo", "Continue watching"), rowTitle, inner - 56 * k);
        float cw = (inner - 56 * k - 40 * k) / 3;
        String[] names = {Ui.tr("Filme da noite", "Tonight's film"), Ui.tr("Série nova", "New series"), Ui.tr("Jogo ao vivo", "Live match")};
        for (int i = 0; i < 3; i++) cards[i] = SettingsSketch.fit(names[i], i == 0 ? cardFocus : cardTitle, cw);
    }

    private void perf(Host h, float inner) {
        JSONObject a = SettingsGroups.metrics(h, HomeMode.ORIGINAL), b = SettingsGroups.metrics(h, HomeMode.FREE);
        String[] keys = {"homeMs", "memAvailableMB", "homePssMB"};
        perfLabel[0] = SettingsSketch.fit(Ui.tr("Botão Início", "Home button"), small, inner * 0.4f);
        perfLabel[1] = SettingsSketch.fit(Ui.tr("Memória livre", "Free memory"), small, inner * 0.4f);
        perfLabel[2] = SettingsSketch.fit(Ui.tr("Memória do Início", "Home screen memory"), small, inner * 0.4f);
        perfFocus = -1;
        for (int i = 0; i < 3; i++) {
            perfA[i] = metric(a, keys[i]);
            perfB[i] = metric(b, keys[i]);
            if (keys[i].equals(arg)) perfFocus = i;
        }
        colA = Ui.tr("Original", "Original");
        colB = Config.APP_NAME;
        effectsOn = !Ui.lite;
        perfMode = Ui.lite ? Ui.tr("Nesta TV agora: leve", "On this TV now: lite") : Ui.tr("Nesta TV agora: completo", "On this TV now: full");
        effect[0] = SettingsSketch.fit(Ui.tr("Céu em movimento", "Moving sky"), mid, inner * 0.5f);
        effect[1] = SettingsSketch.fit(Ui.tr("Vidro líquido", "Liquid glass"), mid, inner * 0.5f);
        effectNote[0] = SettingsSketch.fit(effectsOn ? Ui.tr("Ligado", "On") : Ui.tr("Um degradê parado", "A still gradient"), small, inner * 0.45f);
        effectNote[1] = SettingsSketch.fit(effectsOn ? Ui.tr("Ligado", "On") : Ui.tr("Painéis translúcidos simples", "Plain see-through panels"), small, inner * 0.45f);
    }

    private void search(float inner) {
        voice = arg.equals("voice");
        String[] filters;
        switch (arg) {
            case "kind":
                query = Ui.ar ? "مسلسل كوميدي" : "comedy series";
                filters = new String[] {Ui.tr("Séries", "Series", "Series", "مسلسلات"), Ui.tr("Comédia", "Comedy")};
                break;
            case "era":
                query = Ui.ar ? "افلام رعب قديمة" : "old horror movies";
                filters = new String[] {Ui.tr("Filmes", "Movies"), Ui.tr("Terror", "Horror"), Ui.tr("Antes de 2000", "Before 2000")};
                break;
            case "origin":
                query = Ui.ar ? "مسلسلات تركية" : "turkish series";
                filters = new String[] {Ui.tr("Séries", "Series", "Series", "مسلسلات"), Ui.tr("Turcas", "Turkish")};
                break;
            case "title":
                query = Ui.ar ? "بريكينج باد" : "breaking bad";
                filters = new String[] {Ui.tr("Título", "Title") + ": " + Ui.isolate(query)};
                break;
            case "where":
                query = Ui.ar ? "ذا اوفيس" : "the office";
                filters = new String[] {WatchMe.NAME, Ui.tr("Seus apps", "Your apps"), "TMDB", Ui.tr("Outros apps", "Other apps")};
                break;
            default:
                query = Ui.ar ? "افلام عصابات جديده" : "new gangster movies";
                filters = new String[] {Ui.tr("Filmes", "Movies"), Ui.tr("Crime", "Crime"), Ui.tr("Últimos 3 anos", "Last 3 years")};
                break;
        }
        query = SettingsSketch.fit("“" + query + "”", mid, inner - 120 * k);
        results = Ui.tr("{spark} entendeu", "{spark} understood");
        float x = 0, y = 0, h = 52 * k, gap = 12 * k;
        chipCount = Math.min(chips.length, filters.length);
        for (int i = 0; i < chipCount; i++) {
            chips[i] = SettingsSketch.fit(Ui.digits(filters[i]), mid, inner - 44 * k);
            float cw = mid.measureText(chips[i]) + 44 * k;
            if (x > 0 && x + cw > inner) {
                x = 0;
                y += h + gap;
            }
            chipX[i] = x;
            chipY[i] = y;
            chipW[i] = cw;
            x += cw + gap;
        }
    }

    // MARK: - Draw

    void draw(Canvas c, float seconds) {
        sketch.box(r, panel.left, panel.top, panel.right, panel.bottom);
        scene.glass(c, r, 36 * k, 0x0B0E16, 0.42f, 0xFFFFFF, 0.08f, 0, 1);
        float left = panel.left + pad(), right = panel.right - pad();
        sketch.start(c, heading, left, panel.top + 58 * k, header);
        sketch.start(c, titleText, left, panel.top + 104 * k * Math.min(t, 1.15f), title);
        float top = areaTop(), bottom = areaBottom();
        if (sketch.handles(kind)) {
            sketch.draw(c, left, top, right, bottom, seconds);
        } else {
            switch (kind) {
                case P_MODE: drawMode(c, left, top, right, seconds); break;
                case P_CLOCK: drawClock(c, left, top, right); break;
                case P_WIDGETS: drawWidgets(c, left, top, right, bottom); break;
                case P_CARD: drawCard(c, left, top, right); break;
                case P_PERF: drawPerf(c, left, top, right); break;
                case P_ABOUT: drawAbout(c, left, top, right); break;
                case P_SEARCH: drawSearch(c, left, top, right); break;
                case P_SCREENSAVER: drawScreensaver(c, left, top, right); break;
                default: drawLines(c, left, top, right, bottom); break;
            }
        }
        if (captionLayout != null) {
            c.save();
            c.translate(Ui.layoutX(left, captionRight, w), panel.bottom - pad() - captionLayout.getHeight());
            captionLayout.draw(c);
            c.restore();
        }
    }

    /** What's inside a group: its symbol, then each setting with its value. */
    private void drawLines(Canvas c, float left, float top, float right, float bottom) {
        float y = top;
        if (icon != null) {
            Symbols.draw(context, c, icon, sketch.mx(left + 30 * k), top + 30 * k, 50 * k, 0xE6FFFFFF);
            y += 96 * k;
        }
        float step = 48 * k * t;
        fill.setColor(0x1AFFFFFF);
        for (int i = 0; i < lines.size() && y + step <= bottom + step * 0.4f; i++) {
            String[] l = lines.get(i);
            float base = y + step * 0.62f;
            sketch.start(c, l[0], left, base, line);
            if (!l[1].isEmpty()) sketch.end(c, l[1], right, base, lineValue);
            sketch.box(r, left, y + step - 1, right, y + step);
            c.drawRect(r, fill);
            y += step;
        }
    }

    /** Before and after the switch, side by side; the mode in use filled, the one picked ringed. */
    private void drawMode(Canvas c, float left, float top, float right, float seconds) {
        float width = right - left, ca = left + width * 0.55f, cb = left + width * 0.85f, colW = width * 0.3f;
        float rowH = 56 * k, tableBottom = top + 60 * k + 4 * rowH;
        for (int col = 0; col < 2; col++) {
            float cx = col == 0 ? ca : cb;
            sketch.box(r, cx - colW / 2, top, cx + colW / 2, tableBottom);
            if (col == current) {
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, 18 * k, 18 * k, fill);
            }
            if (col == target) {
                stroke.setStrokeWidth(3 * k);
                stroke.setColor(accent);
                c.drawRoundRect(r, 18 * k, 18 * k, stroke);
            }
            mid.setColor(0xFFFFFFFF);
            sketch.center(c, col == 0 ? colA : colB, cx, top + 40 * k, mid);
        }
        for (int i = 0; i < 4; i++) {
            float base = top + 60 * k + i * rowH + rowH * 0.6f;
            sketch.start(c, modeLabel[i], left, base, small);
            mid.setColor(current == 0 ? 0xFFFFFFFF : 0xB3FFFFFF);
            sketch.center(c, modeA[i], ca, base, mid);
            mid.setColor(current == 1 ? 0xFFFFFFFF : 0xB3FFFFFF);
            sketch.center(c, modeB[i], cb, base, mid);
        }
        mid.setColor(0xE6FFFFFF);
        if (busy) {
            float y = tableBottom + 56 * k;
            spinner.setColor(0xFFFFFFFF);
            Ui.spinner(c, sketch.mx(left + 12 * k), y - 8 * k, 12 * k, seconds * 1.3f, spinner);
            sketch.start(c, busyText, left + 40 * k, y, mid);
        }
    }

    /** The home screen's top: its tabs and the clock with the date and weather; then the big clock it shows alone. */
    private void drawClock(Canvas c, float left, float top, float right) {
        sketch.box(r, left, top, right, top + 150 * k);
        fill.setColor(0xFF10173A);
        c.drawRoundRect(r, 22 * k, 22 * k, fill);
        for (int i = 0; i < 3; i++) {
            mid.setColor(i == 0 ? 0xFFFFFFFF : 0x8CFFFFFF);
            sketch.start(c, tabs[i], left + 24 * k + tabX[i], top + 62 * k, mid);
        }
        numbersBig.setTextSize(44 * k);
        sketch.end(c, clock, right - 24 * k, top + 66 * k, numbersBig);
        sketch.end(c, clockLine, right - 24 * k, top + 112 * k, small);
        float y = top + 300 * k;
        sketch.start(c, clock, Ui.rtl ? left : left - Ui.leftBearing(thinClock, clock), y, thinClock);
        mid.setColor(0xCCFFFFFF);
        sketch.start(c, date, left, y + 48 * k, mid);
        Symbols.draw(context, c, weatherSymbol, sketch.mx(left + 16 * k), y + 94 * k - Ui.halfCap(mid), 28 * k, 0xFFFFFFFF);
        sketch.start(c, weather, left + 44 * k, y + 94 * k, mid);
        if (underlineAt == 0) return;
        float uy = underlineAt == 1 ? top + 78 * k : underlineAt == 2 ? y + 14 * k : y + 108 * k;
        float ux = underlineAt == 1 ? left + 24 * k : left;
        sketch.box(r, ux + underlineX, uy, ux + underlineX + underlineW, uy + 4 * k);
        fill.setColor(accent);
        c.drawRoundRect(r, 2 * k, 2 * k, fill);
    }

    /** The greeting, the clock with the weather (or without), and the drawer with the prices (or without). */
    private void drawWidgets(Canvas c, float left, float top, float right, float bottom) {
        big.setColor(0xF2FFFFFF);
        sketch.start(c, greeting, left, top + 40 * k, big);
        numbersBig.setTextSize(54 * k);
        sketch.start(c, clock, left, top + 120 * k, numbersBig);
        mid.setColor(0xCCFFFFFF);
        sketch.start(c, date, left, top + 162 * k, mid);
        float wy = top + 206 * k;
        if (weatherOn) {
            Symbols.draw(context, c, weatherSymbol, sketch.mx(left + 16 * k), wy - Ui.halfCap(mid), 28 * k, 0xFFFFFFFF);
            mid.setColor(0xE6FFFFFF);
            sketch.start(c, weather, left + 44 * k, wy, mid);
        }
        if (arg.equals("weather") || arg.equals("greeting")) {
            float y0 = arg.equals("weather") ? wy - 34 * k : top - 4 * k, y1 = arg.equals("weather") ? wy + 14 * k : top + 54 * k;
            sketch.box(r, left - 12 * k, y0, right, y1);
            stroke.setStrokeWidth(3 * k);
            stroke.setColor(Ui.alpha(accent, weatherOn || arg.equals("greeting") ? 1 : 0.5f));
            c.drawRoundRect(r, 12 * k, 12 * k, stroke);
        }
        float dt = Math.max(wy + 40 * k, bottom - 150 * k), db = dt + 140 * k;
        sketch.box(r, left, dt, right, db);
        fill.setColor(quotesOn ? 0x1FFFFFFF : 0x0DFFFFFF);
        c.drawRoundRect(r, 22 * k, 22 * k, fill);
        if (arg.equals("prices")) {
            stroke.setStrokeWidth(3 * k);
            stroke.setColor(accent);
            c.drawRoundRect(r, 22 * k, 22 * k, stroke);
        }
        sketch.start(c, pricesTitle, left + 20 * k, dt + 32 * k, header);
        if (!quotesOn) {
            small.setColor(0x80FFFFFF);
            sketch.center(c, pricesOff, (left + right) / 2, dt + 92 * k, small);
            return;
        }
        float cw = (right - left - 40 * k) / coinCount;
        for (int i = 0; i < coinCount; i++) {
            boolean on = coinOn[i];
            float x = left + 20 * k + i * cw;
            small.setColor(on ? 0xB3FFFFFF : 0x4DFFFFFF);
            sketch.start(c, coinName[i], x, dt + 70 * k, small);
            mid.setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            sketch.start(c, coinPrice[i], x, dt + 106 * k, mid);
            small.setColor(!on ? 0x4DFFFFFF : coinUp[i] ? 0xFF30D158 : 0xFFFF453A);
            sketch.start(c, coinChange[i], x, dt + 130 * k, small);
            if (i == coinFocus) {
                sketch.box(r, x - 10 * k, dt + 44 * k, x + cw - 14 * k, db - 4 * k);
                stroke.setStrokeWidth(3 * k);
                stroke.setColor(accent);
                c.drawRoundRect(r, 14 * k, 14 * k, stroke);
            }
        }
        small.setColor(0x99FFFFFF);
    }

    /** A home row at the theme's sky, the accent's focus ring, the text size and contrast now set. */
    private void drawCard(Canvas c, float left, float top, float right) {
        float bottom = top + 330 * k;
        sketch.box(r, left, top, right, bottom);
        c.drawRoundRect(r, 22 * k, 22 * k, sky);
        if (contrast) {
            fill.setColor(0x73000000);
            c.drawRoundRect(r, 22 * k, 22 * k, fill);
        }
        Symbols.draw(context, c, sun, sketch.mx(right - 36 * k), top + 36 * k, 26 * k, 0xE6FFFFFF);
        sketch.start(c, rowName, left + 28 * k, top + 64 * k, rowTitle);
        float gap = 20 * k, cw = (right - left - 56 * k - 2 * gap) / 3, ch = cw * 9 / 16, ct = top + 96 * k;
        for (int i = 0; i < 3; i++) {
            float x = left + 28 * k + i * (cw + gap), s = i == 0 ? 1.06f : 1, cx = x + cw / 2, cy = ct + ch / 2;
            sketch.box(r, cx - cw * s / 2, cy - ch * s / 2, cx + cw * s / 2, cy + ch * s / 2);
            fill.setColor(CARD_COLORS[i]);
            c.drawRoundRect(r, 12 * k, 12 * k, fill);
            Symbols.draw(context, c, "play", sketch.mx(cx), cy, 26 * k, 0x99FFFFFF);
            if (i == 0) {
                r.inset(-3 * k, -3 * k);
                stroke.setStrokeWidth(4 * k);
                stroke.setColor(accent);
                c.drawRoundRect(r, 15 * k, 15 * k, stroke);
            }
            sketch.start(c, cards[i], x, ct + ch * 1.06f + 34 * k * A11y.text, i == 0 ? cardFocus : cardTitle);
        }
    }

    /** The real numbers from the last switches, and what lite turns off. */
    private void drawPerf(Canvas c, float left, float top, float right) {
        float width = right - left, ca = left + width * 0.6f, cb = left + width * 0.88f;
        mid.setColor(0x99FFFFFF);
        sketch.center(c, colA, ca, top + 30 * k, mid);
        mid.setColor(0xFFFFFFFF);
        sketch.center(c, colB, cb, top + 30 * k, mid);
        for (int i = 0; i < 3; i++) {
            float y = top + 60 * k + i * 52 * k;
            if (i == perfFocus) {
                sketch.box(r, left - 12 * k, y, right, y + 48 * k);
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, 12 * k, 12 * k, fill);
            }
            float base = y + 32 * k;
            sketch.start(c, perfLabel[i], left, base, small);
            mid.setColor(0xB3FFFFFF);
            sketch.center(c, perfA[i], ca, base, mid);
            mid.setColor(0xFFFFFFFF);
            sketch.center(c, perfB[i], cb, base, mid);
        }
        float y = top + 260 * k;
        mid.setColor(0xFFFFFFFF);
        sketch.start(c, perfMode, left, y, mid);
        for (int i = 0; i < 2; i++) {
            float base = y + 52 * k + i * 46 * k;
            if (effectsOn) {
                Symbols.draw(context, c, "check", sketch.mx(left + 12 * k), base - Ui.halfCap(mid), 22 * k, 0xFF30D158);
            } else {
                fill.setColor(0x80FFFFFF);
                sketch.box(r, left + 2 * k, base - Ui.halfCap(mid) - 1.5f * k, left + 22 * k, base - Ui.halfCap(mid) + 1.5f * k);
                c.drawRect(r, fill);
            }
            mid.setColor(effectsOn ? 0xE6FFFFFF : 0x80FFFFFF);
            sketch.start(c, effect[i], left + 40 * k, base, mid);
            sketch.end(c, effectNote[i], right, base, small);
        }
    }

    private void drawAbout(Canvas c, float left, float top, float right) {
        float cx = (left + right) / 2;
        Symbols.draw(context, c, "brand", sketch.mx(cx), top + 90 * k, 110 * k, 0xEBFFFFFF);
        big.setColor(0xFFFFFFFF);
        sketch.center(c, Config.APP_NAME, cx, top + 230 * k, big);
        mid.setColor(0x99FFFFFF);
        sketch.center(c, version, cx, top + 272 * k, mid);
        sketch.center(c, tagline, cx, top + 308 * k, mid);
    }

    /** {spark}: a sentence, said or typed, and the filters it becomes. */
    private void drawSearch(Canvas c, float left, float top, float right) {
        sketch.box(r, left, top, right, top + 76 * k);
        fill.setColor(0x26FFFFFF);
        c.drawRoundRect(r, 38 * k, 38 * k, fill);
        Symbols.draw(context, c, voice ? "mic" : "search", sketch.mx(left + 40 * k), top + 38 * k, 26 * k, 0xE6FFFFFF);
        mid.setColor(0xFFFFFFFF);
        sketch.start(c, query, left + 78 * k, top + 38 * k + Ui.halfCap(mid), mid);
        Symbols.draw(context, c, "chevron_down", sketch.mx((left + right) / 2), top + 122 * k, 26 * k, 0x99FFFFFF);
        sketch.start(c, results, left, top + 186 * k, header);
        float y0 = top + 210 * k, h = 52 * k;
        for (int i = 0; i < chipCount; i++) {
            sketch.box(r, left + chipX[i], y0 + chipY[i], left + chipX[i] + chipW[i], y0 + chipY[i] + h);
            fill.setColor(Ui.alpha(accent == 0xFFFFFFFF ? 0xFFFFFFFF : accent, 0.22f));
            c.drawRoundRect(r, h / 2, h / 2, fill);
            mid.setColor(0xFFFFFFFF);
            sketch.start(c, chips[i], left + chipX[i] + 22 * k, y0 + chipY[i] + h / 2 + Ui.halfCap(mid), mid);
        }
    }

    /** The screensaver, small: the night sky, the thin clock, the date and the weather. */
    private void drawScreensaver(Canvas c, float left, float top, float right) {
        sketch.box(r, left, top, right, top + 360 * k);
        fill.setColor(0xFF070B1E);
        c.drawRoundRect(r, 22 * k, 22 * k, fill);
        fill.setColor(0x334B2F8F);
        c.drawCircle(sketch.mx(right - 120 * k), top + 120 * k, 150 * k, fill);
        fill.setColor(0x2616225A);
        c.drawCircle(sketch.mx(left + 140 * k), top + 300 * k, 170 * k, fill);
        float x = left + 36 * k, y = top + 190 * k;
        sketch.start(c, clock, Ui.rtl ? x : x - Ui.leftBearing(thinClock, clock), y, thinClock);
        mid.setColor(0xCCFFFFFF);
        sketch.start(c, date, x, y + 48 * k, mid);
        Symbols.draw(context, c, weatherSymbol, sketch.mx(x + 16 * k), y + 94 * k - Ui.halfCap(mid), 28 * k, 0xFFFFFFFF);
        sketch.start(c, weather, x + 44 * k, y + 94 * k, mid);
    }
}
