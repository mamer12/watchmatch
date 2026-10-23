package io.watchmatch;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, in three tabs (the bar at the top left; ← → on it switches):
 *  - Home: the whole screen is a featured title's photo (a carousel: the catalog, YouTube picked for you, Apple TV,
 *    TOD's matches),
 *    with its name, description and an "Open in …" button; it changes every few seconds (← → while the button is
 *    focused). Without any, a big clock. The apps sit at the bottom; ↓ scrolls down to Continue watching (what's
 *    half-watched and what was opened, Watched; YouTube on its own row), YouTube picked for you (when
 *    signed in), TOD (matches coming up, what's new) and the catalog (picks for you, My List, new, featured);
 *  - Discover: the Top 10 and what's popular on each streaming app (Catalog);
 *  - Apps: the apps used last, every app, the inputs and settings;
 *  - the time, the date and the weather at the top right;
 *  - ← → move along a row (each row remembers its place), OK opens, Back goes up to the tabs, then Home.
 * Everything is drawn on one Canvas in a 1920×1080 space (k = width / 1920). Pictures arrive ready-made at the size
 * they are drawn (Thumbs); nothing is allocated while drawing, and frames are drawn only while something moves.
 */
final class HomeView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        Catalog.Listener, Thumbs.Listener, Matches.Listener, TrustedTime.Listener, Provider.Listener {
    private static final float M = 96;                  // side margin
    private static final float APPS_TOP = 800, ROW_GAP = 92, FOCUSED_ROW_Y = 330;
    private static final float HERO_TEXT_W = 980, BUTTON_TOP = 700, BUTTON_H = 68, RADIUS = 14;
    private static final int S_APPS = 0, S_TILES = 1, S_WIDE = 2, S_VIDEO = 3, S_POSTER = 4, S_COVER = 5, S_MATCH = 6;
    /** Per style: card width, card height, gap, room below for the labels (1920 space). */
    private static final float[][] SIZES = {
        {288, 162, 32, 44}, {288, 162, 32, 48}, {384, 216, 32, 52}, {384, 216, 32, 84}, {346, 324, 24, 8}, {216, 324, 24, 52}, {384, 216, 32, 8},
    };
    private static final float POSTER_NUMBER_W = 130;
    private static final long HERO_EVERY = 9000, FADE_MS = 700, TOAST_MS = 6000;
    /**
     * Watchme+ rows, as in Watchme+ itself: the focused poster grows into a 16:9 tile (TILE_W × TILE_H), the cards
     * after it moving along; its preview plays inside once the focus rests, and its name, facts and story go under it.
     */
    private static final float TILE_W = 688, TILE_H = 387, TILE_NAME = 50, TILE_META = 44, TILE_PAD = 18, TILE_MOTION = 0.34f;
    private static final long TILE_SETTLE_MS = 700, TILE_FADE_MS = 400, TILE_TEXT_MS = 260, WIDE_FADE_MS = 220;
    private static final int PLACEHOLDER = 0xFF1E1F24, SYMBOL_CARD = 0xFF26272D;
    private static final int TITLE_INK = 0xFFFFFFFF, LABEL_INK = 0xCCFFFFFF, META_INK = 0xD9FFFFFF, BODY_INK = 0xBFFFFFFF;
    /** Apps left running after Home (they reopen instantly); the rest are closed to keep memory free. */
    private static final Set<String> KEEP_RUNNING = new HashSet<>(Arrays.asList(
            "com.netflix.ninja", "com.google.android.youtube.tv"));
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Asks Watchme+ to pull from the sync backend (it may wait a few seconds for it). */
    private static final ExecutorService PULLER = Executors.newSingleThreadExecutor();
    private long pulledAt;

    /** One card: an app, an input or shortcut, or a title or video from the catalog. */
    private static final class Cell {
        HomeApps.Item app;
        Catalog.Entry entry;
        String label = "", sub = "", fitLabel = "", fitSub = "";
        String rank;
        String thumbKey;            // Thumbs.key() for the card's size, made once
        float progress = -1;        // how far it was watched (0 to 1), or -1
        String homeKey, awayKey, middle = "", note = "";    // a match card: crests, score or time, clock or day
        Library.Title title;       // a Watchme+ title: its row grows the focused poster into a playing tile
        final Spring grow = new Spring(TILE_MOTION, 1f, 0);
        String wideKey, wideSource;
        long wideAt;
    }

    private static final class Row {
        String key, title = "";
        int style;
        final List<Cell> cells = new ArrayList<>();
        float top;                          // card top in content space (before scrolling)
        int focus;
        boolean tiles;                      // Watchme+ titles: the focused poster grows into a tile (drawTiles)
        float grown;                        // its tiles' biggest growth: the rows under it move down that much
        final Spring scrollX = new Spring(0.34f, 1f, 0);

        float w() { return SIZES[style][0]; }
        float h() { return SIZES[style][1]; }
        float gap() { return SIZES[style][2]; }
        float below() { return SIZES[style][3]; }
    }

    private final Activity activity;
    private final HomeData data;
    private final Recs recs;
    private final Catalog catalog;
    private final Matches matches;
    private final Thumbs thumbs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;

    // Rows and focus (row -1 = the featured title's button, -2 = the round buttons at the top left)
    private static final int TOP = -2, HERO = -1;
    /** Tab bar: its center line and the gap between names; the first row's cards in the other tabs start at TAB_TOP. */
    private static final float TAB_CY = 96, TAB_GAP = 56, TAB_TOP = 330;

    /** One tab: its rows, laid out from its own top. */
    private static final class Tab {
        String key, label;
        final List<Row> rows = new ArrayList<>();
    }

    private List<Tab> tabs = new ArrayList<>();
    private int tab;                    // the tab shown
    private int topIndex;               // the focus on the bar: the Search, Ask Spark, Inputs and TV Settings buttons, then the tabs
    private final HomeApps.Item[] barButtons = {
        HomeApps.action(SEARCH, "search", Ui.tr("Buscar", "Search", "Buscar"), "search"),
        HomeApps.action(SPARK, "spark", Ui.tr("Pergunte ao {spark}", "Ask {spark}", "Pregunta a {spark}"), "sparkle"),
        HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas", "Inputs", "Entradas"), "hdmi"),
        HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("Ajustes da TV", "TV Settings", "Ajustes de la TV"), "tv_settings"),
    };
    private final android.graphics.Path sparkle = new android.graphics.Path();   // the Ask Spark button's stars
    private boolean inputsOpen;         // the inputs' row is open under the apps
    private float[] tabX = new float[0], tabW = new float[0];
    private Paint tabFont;
    private List<Row> rows = new ArrayList<>();
    private int focusRow;
    private int prevRow = -2, prevCol;
    private final Spring scrollY = new Spring(0.42f, 1f, 0);
    private final Spring cellFocus = new Spring(0.26f, 0.82f, 1), lastFocus = new Spring(0.26f, 1f, 0);
    private final Spring heroFocus = new Spring(0.26f, 0.9f, 0);
    private List<HomeApps.Item> shelf = new ArrayList<>(), recent = new ArrayList<>(), inputs = new ArrayList<>(),
            others = new ArrayList<>();
    private List<Catalog.Entry> watched = new ArrayList<>();
    private List<HomeApps.Item> settingsItems = new ArrayList<>();
    private List<Row> cinemaExtras = new ArrayList<>();     // The catalog tab: suggestions for you and My List
    private List<WatchMe.Picks> shownPicks = new ArrayList<>();     // the picks those rows were made from
    /**
     * Home loads that failed or came back empty (Watchme+ unreachable just after boot, no network or a wrong clock
     * yet) are tried again, the last good rows staying meanwhile.
     */
    private final Backoff loads = new Backoff();
    private boolean watchingMissed, extrasMissed;
    private final Runnable retryLoads = this::retryLoads;
    private final Runnable recover = this::recover;

    // Featured title
    private List<Catalog.Entry> heroPicks = new ArrayList<>();
    private int heroIndex;
    private Bitmap heroBmp, heroPrevBmp;
    private long heroFadeAt, heroTextAt;
    private StaticLayout heroTitle, heroOverview;
    private float heroTitleRight, heroOverviewRight;        // right to left: where their lines end (Ui.lineRight)
    private String heroLabel = "", heroMeta = "", heroButton = "", heroTitleText = "";
    private float heroButtonW;
    private final RectF heroButtonRect = new RectF();

    // Watchme+ tiles
    private CinemaPreview preview;
    private long previewAt, tileAt;
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tileRect = new RectF();
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final Spring tileInfoH = new Spring(TILE_MOTION, 1f, TILE_NAME + TILE_META + TILE_PAD);
    private Cell tileCell;              // whose words are under the tile
    private String tileRowKey;          // and its row
    private String tileName = "", tileFacts = "";
    private StaticLayout tileStory;
    private float tileStoryRight;       // right to left: where its lines end (Ui.lineRight), measured once
    private Paint tileNameFont;
    private float rowOffset;            // while drawing: how far grown tiles above push the row down
    private final Runnable tileSettle = this::tileSettled;
    private final Runnable artArrived = () -> {
        invalidate();
        kick();
    };

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames and state
    private boolean running, framePosted, okDown, backDown;
    private long lastNanos;
    private int w, h;
    private float k = 1;
    /** Arabic: the layout mirrored (positions from the right, ← → swapped); pictures and logos as they are. */
    private final boolean rtl = Ui.rtl;
    private String clockText = "", dateText = "", weatherText = "", weatherSymbol, clockLine = "";
    private int moment = -1;            // Moments: morning, afternoon, evening, late night
    private String greeting = "";
    /** The Watchme+ profile watching, when its name is its own ("Me" says nothing): greeted instead of the configured name. */
    private String profileName = "";
    private Paint greetingFont, matchFont, noteFont;
    private long clockMinute = -1;
    private int clockDigits;
    private String lastOpened;

    private Paint titleFont, metaFont, labelFont, bodyFont, buttonFont, rowFont, cardFont, cardFocusFont, subFont;
    /** Home Settings: the Home tab's rows (HomeRows), the focus ring's colour; Accessibility's choices (A11y). */
    private final List<String> homeLayout = HomeRows.layout();
    private int focusRing = 0xFFFFFFFF, labelInk = LABEL_INK, metaInk = META_INK, bodyInk = BODY_INK, tabDim = 0x8CFFFFFF;
    private float awayDim = 0.78f, contrastDim;
    private boolean still;
    private Paint clockFont, bigClockFont, dateFont, rankFont, rankStroke, toastFont;
    private TextPaint titleText, bodyText, toastText;
    private Shader emptySky, topFade, matchSky;
    private String heroKey, heroSource, nextKey, nextSource;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<Bitmap, BitmapShader> tileShaders = new WeakHashMap<>();
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF(), screen = new RectF(), tmpRect = new RectF();

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            catalog.refreshIfStale();
            matches.refreshIfStale();
            checkNotices();
            if (updateClock()) invalidate();
            updateHeroPicks();
            pullRemote(false);
            handler.postDelayed(this, 15_000);
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (focusRow != -1 && heroPicks.size() > 1) showHero(heroIndex + 1);
            handler.postDelayed(this, HERO_EVERY);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        catalog = Catalog.get(a);
        matches = Matches.get(a);
        thumbs = Thumbs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        setContentDescription(Config.APP_NAME);
        border.setStyle(Paint.Style.STROKE);
        clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        // First time: right away (a cold start opens with the apps already there); the rest comes from reload().
        shelf = HomeApps.shelf(a, false);
        buildRows();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        lastNanos = 0;
        closeLastApp();
        data.addListener(this);
        recs.addListener(this);
        catalog.addListener(this);
        matches.addListener(this);
        thumbs.setListener(this);
        TrustedTime.addListener(this);
        Provider.addListener(this);
        Provider.resumed();
        data.refreshIfStale();
        recs.refreshIfStale();
        catalog.refreshIfStale();
        matches.refreshIfStale();
        Companion.start(getContext());
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        handler.postDelayed(rotate, HERO_EVERY);
        checkNotices();
        reload();
        pullRemote(true);
        updateClock();
        updateHeroPicks();
        CinemaLogos.get(getContext()).setListener(artArrived);
        tileFocusChanged();
        invalidate();
        kick();
    }

    void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rotate);
        data.removeListener(this);
        recs.removeListener(this);
        catalog.removeListener(this);
        matches.removeListener(this);
        thumbs.setListener(null);
        TrustedTime.removeListener(this);
        Provider.removeListener(this);
        Provider.paused();
        handler.removeCallbacks(retryLoads);
        handler.removeCallbacks(recover);
        CinemaLogos.get(getContext()).setListener(null);
        handler.removeCallbacks(tileSettle);
        if (preview != null) preview.stop();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        thumbs.trim();      // the app being opened gets the memory
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Home pressed while the home screen is showing: back to the top, first app focused. */
    void reset(boolean animated) {
        selectTab(0, false);
        for (Row r : rows) {
            r.focus = 0;
            r.scrollX.target = 0;
            if (!animated) r.scrollX.snap(0);
        }
        focus(0, !animated);
    }

    /**
     * The app opened from here, closed once its user is back home (Android would otherwise keep it in memory, and on a
     * 2 GB TV the next app then waits for memory to be freed). Netflix and YouTube stay: they're used the most.
     */
    private void closeLastApp() {
        final String pkg = lastOpened;
        lastOpened = null;
        if (pkg == null || KEEP_RUNNING.contains(pkg)) return;
        handler.postDelayed(() -> {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            am.killBackgroundProcesses(pkg);
            AppLog.w("closed " + pkg + " to free memory");
        }, 1500);
    }

    @Override public void onData() {
        updateWeather();
        invalidate();
    }

    @Override public void onRecs() {}      // Recs feeds the screensaver; the carousel comes from Catalog

    private void retryLoads() {
        if (running) reload();
    }

    /** The network came up or the clock was set: the clock redrawn, and whatever failed fetched again. */
    private void recover() {
        if (!running) return;
        clockMinute = -1;
        if (updateClock()) invalidate();
        loads.reset();
        catalog.retryNow();
        recs.retryNow();
        matches.retryNow();
        data.refreshIfStale();
        reload();
    }

    @Override public void onTimeOrNetwork() {
        // Often several at once (a network up, then validated, then the clock set): one recovery for them.
        handler.removeCallbacks(recover);
        handler.postDelayed(recover, 1500);
    }

    @Override public void onCatalog() {
        rebuildSoon();
    }

    /** Another catalog server is in use: its rows (and Watchme+'s) are asked for again from it. */
    @Override public void onProviderChanged() {
        loads.reset();
        catalog.providerChanged();
        if (running) reload();
    }

    @Override public void onMatches() {
        rebuildSoon();
        MatchWatch.schedule(getContext());
    }

    /**
     * Data arriving (the catalog, matches, apps, Watchme+'s picks, often several at once): one rebuild of the rows and
     * the carousel for all of them, a moment later, instead of one each (each costs a frame on the TV).
     */
    private void rebuildSoon() {
        handler.removeCallbacks(rebuild);
        handler.postDelayed(rebuild, 120);
    }

    private final Runnable rebuild = () -> {
        buildRows();
        updateHeroPicks();
    };

    @Override public void onThumb() {
        invalidate();
    }

    // MARK: - Size and paints

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        makePaints();
        fitted.clear();
        clockMinute = -1;
        updateClock();
        updateWeather();
        fitLabels();
        layoutTabs();
        updateHeroText();
    }

    private void makePaints() {
        // Accessibility: the text size scales the paints (t = 1 at Normal); high contrast brightens the dimmed text and
        // darkens the photo behind it. Off, the values are the ones the home screen always had.
        float t = A11y.text;
        boolean strong = A11y.contrast;
        still = A11y.reduceMotion;
        focusRing = Ui.accent();
        labelInk = strong ? 0xFFFFFFFF : LABEL_INK;
        metaInk = strong ? 0xFFFFFFFF : META_INK;
        bodyInk = strong ? 0xF2FFFFFF : BODY_INK;
        tabDim = strong ? 0xE6FFFFFF : 0x8CFFFFFF;
        awayDim = strong ? 0.9f : 0.78f;
        contrastDim = strong ? 0.45f : 0;
        titleFont = Ui.text(Ui.displaySemibold, 80 * k * t, TITLE_INK);
        titleText = new TextPaint(titleFont);
        labelFont = Ui.text(Ui.semibold, 24 * k * t, labelInk);
        labelFont.setLetterSpacing(0.14f);
        metaFont = Ui.text(Ui.medium, 28 * k * t, metaInk);
        bodyFont = Ui.text(Ui.regular, 29 * k * t, bodyInk);
        bodyText = new TextPaint(bodyFont);
        buttonFont = Ui.text(Ui.semibold, 29 * k * t, 0xFFFFFFFF);
        rowFont = Ui.text(Ui.semibold, 30 * k * t, 0xF2FFFFFF);
        cardFont = Ui.text(Ui.medium, 25 * k * t, strong ? 0xF2FFFFFF : 0xB3FFFFFF);
        cardFocusFont = Ui.text(Ui.semibold, 25 * k * t, 0xFFFFFFFF);
        tileNameFont = Ui.text(Ui.semibold, 38 * k * t, 0xFFFFFFFF);
        subFont = Ui.text(Ui.medium, 22 * k, strong ? 0xD9FFFFFF : 0x80FFFFFF);     // also on match cards: their size
        clockFont = Ui.numbers(Ui.semibold, 50 * k * t, 0xFFFFFFFF);
        clockFont.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        bigClockFont = Ui.numbers(Ui.thin, 190 * k, 0xFFFFFFFF);
        dateFont = Ui.text(Ui.medium, 25 * k * t, strong ? 0xFFFFFFFF : 0xCCFFFFFF);
        rankFont = Ui.numbers(Ui.semibold, 270 * k, 0xFF0B0B0D);
        rankFont.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        rankStroke = Ui.numbers(Ui.semibold, 270 * k, 0xB3FFFFFF);
        rankStroke.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        rankStroke.setStyle(Paint.Style.STROKE);
        rankStroke.setStrokeWidth(4 * k);
        toastFont = Ui.text(Ui.medium, 26 * k * t, 0xF2FFFFFF);
        tabFont = Ui.text(Ui.semibold, 30 * k * t, 0xFFFFFFFF);
        greetingFont = Ui.text(Ui.displaySemibold, 42 * k * t, 0xF2FFFFFF);
        matchFont = Ui.numbers(Ui.semibold, 44 * k, 0xFFFFFFFF);
        noteFont = Ui.text(Ui.semibold, 22 * k, 0xB3FFFFFF);
        toastText = new TextPaint(toastFont);
        // No text shadows: the photo comes darkened where text sits (Thumbs), and a shadow costs a blur per draw.
        emptySky = new LinearGradient(0, 0, w, h, 0xFF1A1D29, 0xFF050507, Shader.TileMode.CLAMP);
        matchSky = new android.graphics.RadialGradient(w * (rtl ? 0.26f : 0.74f), h * 0.4f, w * 0.7f,
                new int[] {0xFF1D3B78, 0xFF0D1A3A, 0xFF05070D}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        topFade = new LinearGradient(0, 0, 0, 260 * k, new int[] {0xFF050507, 0xE6050507, 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
    }

    // MARK: - Frames: only while something moves

    private void kick() {
        if (!running || framePosted) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        framePosted = false;
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        boolean moving = scrollY.step(dt) | cellFocus.step(dt) | lastFocus.step(dt) | heroFocus.step(dt) | tileInfoH.step(dt);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            moving |= r.scrollX.step(dt);
            if (!r.tiles) continue;
            // The focused card grows into the tile, the others go back to posters, each from where it is.
            float most = 0;
            for (int j = 0; j < r.cells.size(); j++) {
                Cell cell = r.cells.get(j);
                cell.grow.target = i == focusRow && j == r.focus ? 1 : 0;
                moving |= cell.grow.step(dt);
                most = Math.max(most, cell.grow.value);
            }
            r.grown = Ui.clamp(most);
        }
        long up = SystemClock.uptimeMillis();
        moving |= previewAt != 0 && up - previewAt < TILE_FADE_MS || up - tileAt < TILE_TEXT_MS;
        moving |= heroFadeAt != 0 && up - heroFadeAt < FADE_MS;
        moving |= heroTextAt != 0 && up - heroTextAt < FADE_MS;
        moving |= tickToast(up);
        invalidate();
        if (moving) {
            kick();
        } else {
            lastNanos = 0;
        }
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (titleFont == null) return;
        long up = SystemClock.uptimeMillis();
        float scroll = scrollY.value * k;
        boolean home = tab == 0;
        // 0 on Home at the top, 1 once its rows have moved up and on every other tab (the photo dims behind them).
        float away = home ? Ui.clamp(scrollY.value / 260f) : 1;
        drawBackground(c, up, away);
        if (home && away < 0.999f && !greeting.isEmpty()) {
            greetingFont.setAlpha(Math.round(242 * (1 - away)));
            Ui.text(c, greeting, M * k, 212 * k, greetingFont, w);
        }
        if (home && away < 0.999f) {
            if (heroPicks.isEmpty()) drawBigClock(c, 1 - away);
            else drawHero(c, up, 1 - away);
        }
        c.save();
        c.translate(0, -scroll);
        rowOffset = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            drawRow(c, r, i, scroll);
            if (r.tiles) rowOffset += r.grown * tileExtra(r) * k;
        }
        c.restore();
        float under = home ? away : Ui.clamp(scrollY.value / 60f);
        if (under > 0.001f) {
            // Rows scrolled up fade out under the tabs instead of being cut by them.
            fill.setShader(topFade);
            fill.setAlpha(Math.round(255 * under));
            c.drawRect(0, 0, w, 260 * k, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        if (!home || !heroPicks.isEmpty()) drawClock(c, home ? 1 - away : 1);   // Home without picks: the big clock
        drawTabs(c);
        drawToast(c, up);
    }

    private void drawBackground(Canvas c, long up, float away) {
        Catalog.Entry pick = tab == 0 ? currentPick() : null;
        if (pick != null && pick.match != null) {
            drawMatchBillboard(c, pick.match, away);
            return;
        }
        Bitmap target = heroKey == null ? null : thumbs.get(heroKey, heroSource, HERO_W, HERO_H, Thumbs.SCREEN);
        if (target != null && target != heroBmp) {
            heroPrevBmp = heroBmp;
            heroBmp = target;
            heroFadeAt = up;
            kick();
        }
        float fade = heroFadeAt == 0 || still ? 1 : ease(Ui.clamp((up - heroFadeAt) / (float) FADE_MS));
        if (fade >= 1) heroPrevBmp = null;
        // One full-screen paint when settled (the photo, already darkened); the gradient only under a fade or without one.
        if (heroBmp == null || (fade < 1 && heroPrevBmp == null)) {
            fill.setShader(emptySky);
            c.drawRect(screen, fill);
            fill.setShader(null);
        }
        if (heroPrevBmp != null && fade < 1) drawFull(c, heroPrevBmp, 255);
        if (heroBmp != null) drawFull(c, heroBmp, Math.round(255 * fade));
        // Late night: the photo dims, winding down.
        float dim = Math.max(Math.max(awayDim * away, contrastDim), moment == Moments.NIGHT ? 0.35f : 0);
        if (dim > 0.001f) {
            fill.setColor(Ui.alpha(0xFF050507, dim));
            c.drawRect(screen, fill);
        }
    }

    /** Match day: a dark pitch-blue backdrop with both crests big on the right and the kick-off or score between. */
    private void drawMatchBillboard(Canvas c, Matches.Match m, float away) {
        fill.setShader(matchSky);
        c.drawRect(screen, fill);
        fill.setShader(null);
        float a = 1 - away, size = HERO_CREST * k, cy = 430 * k;
        float lx = 1180 * k, rx = 1640 * k;
        if (rtl) {      // on the left, the home team on the right
            lx = w - lx;
            rx = w - rx;
        }
        image.setAlpha(Math.round(255 * a));
        int px = Math.round(HERO_CREST * k);
        Bitmap home = heroHomeKey == null ? null : thumbs.get(heroHomeKey, m.home.logo, px, px, Thumbs.FIT);
        Bitmap awayCrest = heroAwayKey == null ? null : thumbs.get(heroAwayKey, m.away.logo, px, px, Thumbs.FIT);
        if (home != null) {
            tmpRect.set(lx - size / 2, cy - size / 2, lx + size / 2, cy + size / 2);
            c.drawBitmap(home, null, tmpRect, image);
        }
        if (awayCrest != null) {
            tmpRect.set(rx - size / 2, cy - size / 2, rx + size / 2, cy + size / 2);
            c.drawBitmap(awayCrest, null, tmpRect, image);
        }
        image.setAlpha(255);
        matchFont.setTextSize(84 * k);
        matchFont.setAlpha(Math.round(255 * a));
        c.drawText(heroMiddle, (lx + rx) / 2 - matchFont.measureText(heroMiddle) / 2, cy + Ui.halfCap(matchFont), matchFont);
        matchFont.setAlpha(255);
        if (m.state == Matches.LIVE) {
            noteFont.setColor(Ui.alpha(0xFFFF453A, a));
            noteFont.setTextSize(30 * k);
            String clock = "● " + m.clock;
            c.drawText(clock, (lx + rx) / 2 - noteFont.measureText(clock) / 2, cy + 90 * k, noteFont);
            noteFont.setTextSize(22 * k);
        }
        if (away > 0.001f || contrastDim > 0) {
            fill.setColor(Ui.alpha(0xFF050507, Math.max(awayDim * away, contrastDim)));
            c.drawRect(screen, fill);
        }
    }

    private void drawFull(Canvas c, Bitmap b, int alpha) {
        image.setAlpha(alpha);
        rect.set(0, 0, w, h);
        c.drawBitmap(b, null, rect, image);
        image.setAlpha(255);
    }

    private void drawHero(Canvas c, long up, float alpha) {
        if (heroTitle == null) return;
        float textIn = heroTextAt == 0 || still ? 1 : ease(Ui.clamp((up - heroTextAt) / (float) FADE_MS));
        float a = alpha * textIn, slide = (1 - textIn) * 24 * k;
        float x = M * k + slide;
        // Faded through each paint's color (an offscreen layer per frame is costly on TV GPUs).
        titleText.setColor(Ui.alpha(TITLE_INK, a));
        bodyText.setColor(Ui.alpha(bodyInk, a));
        metaFont.setColor(Ui.alpha(metaInk, a));
        labelFont.setColor(Ui.alpha(labelInk, a));
        // Bottom-up from the button: description, then genre · runtime · app, the title and the app's name above it.
        float bottom = (BUTTON_TOP - 28) * k;
        float overviewTop = bottom - (heroOverview == null ? 0 : heroOverview.getHeight());
        if (heroOverview != null) {
            c.save();
            c.translate(Ui.layoutX(x, heroOverviewRight, w), overviewTop);
            heroOverview.draw(c);
            c.restore();
        }
        float metaBase = overviewTop - 20 * k, titleBottom = overviewTop - 18 * k;
        if (!heroMeta.isEmpty()) {
            Ui.text(c, heroMeta, x, metaBase, metaFont, w);
            titleBottom = metaBase - Ui.cap(metaFont) - 30 * k;
        }
        float titleTop = titleBottom - heroTitle.getHeight();
        c.save();
        c.translate(rtl ? Ui.layoutX(x, heroTitleRight, w) : x - Ui.leftBearing(titleFont, heroTitleText), titleTop);
        heroTitle.draw(c);
        c.restore();
        Ui.text(c, heroLabel, x, titleTop - 14 * k, labelFont, w);
        drawHeroButton(c, alpha);
    }

    private void drawHeroButton(Canvas c, float alpha) {
        float f = Ui.clamp(heroFocus.value), s = 1 + 0.06f * f;
        float bw = heroButtonW, bh = BUTTON_H * k, left = mx(M * k, bw), top = BUTTON_TOP * k;
        heroButtonRect.set(left, top, left + bw, top + bh);
        float cx = heroButtonRect.centerX(), cy = heroButtonRect.centerY();
        rect.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        fill.setColor(Ui.alpha(blend(0x40FFFFFF, 0xFFFFFFFF, f), alpha));
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill);
        int ink = Ui.alpha(blend(0xFFFFFFFF, 0xFF0B0B0D, f), alpha);
        float icon = 26 * k * s, ix = rtl ? rect.right - 34 * k * s - icon / 2 : rect.left + 34 * k * s + icon / 2;
        Symbols.draw(getContext(), c, "play", ix, cy, icon, ink);
        buttonFont.setColor(ink);
        c.save();
        c.scale(s, s, cx, cy);
        Ui.textAt(c, heroButton, rtl ? left + bw - (34 * k + 26 * k + 14 * k) : left + 34 * k + 26 * k + 14 * k,
                cy + Ui.halfCap(buttonFont), buttonFont);
        c.restore();
        // Dots: which of the featured titles this is.
        int n = heroPicks.size();
        if (n < 2) return;
        float dx = rtl ? -22 * k : 22 * k, x = rtl ? heroButtonRect.left - 40 * k : heroButtonRect.right + 40 * k;
        for (int i = 0; i < n; i++) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, alpha * (i == heroIndex ? 1 : 0.35f)));
            c.drawCircle(x + i * dx, cy, (i == heroIndex ? 5.5f : 4.5f) * k, fill);
        }
    }

    /** No featured titles (no TMDB key, or still loading): a big clock with the date and the weather. */
    private void drawBigClock(Canvas c, float alpha) {
        int a = Math.round(255 * alpha);
        bigClockFont.setAlpha(a);
        dateFont.setAlpha(a);
        float x = M * k;
        if (rtl) Ui.text(c, clockText, x, 470 * k, bigClockFont, w);
        else c.drawText(clockText, x - Ui.leftBearing(bigClockFont, clockText), 470 * k, bigClockFont);
        Ui.text(c, dateText, x, 540 * k, dateFont, w);
        if (!weatherText.isEmpty()) {
            float wx = x;
            if (weatherSymbol != null) {
                Symbols.draw(getContext(), c, weatherSymbol, rtl ? w - wx - 16 * k : wx + 16 * k, 590 * k - Ui.halfCap(dateFont),
                        30 * k, Ui.alpha(0xFFFFFFFF, alpha));
                wx += 44 * k;
            }
            Ui.text(c, weatherText, wx, 590 * k, dateFont, w);
        }
        bigClockFont.setAlpha(255);
        dateFont.setAlpha(255);
    }

    /**
     * The bar at the top: the Inputs and TV Settings buttons on the left, the tab names in the middle (the shown one
     * white, the others dimmed). The focused item gets a white pill (or circle), a button its name below.
     */
    private void drawTabs(Canvas c) {
        float cy = TAB_CY * k, ph = 60 * k, padX = 26 * k;
        int buttons = barButtons.length;
        for (int i = 0; i < buttons + tabs.size() && i < tabX.length; i++) {
            float f = barFocus(i);
            if (i < buttons) {
                HomeApps.Item b = barButtons[i];
                float r = ph / 2 * (1 + 0.1f * f), cx = tabX[i] + ph / 2;
                fill.setColor(blend(0x33FFFFFF, 0xFFFFFFFF, f));
                c.drawCircle(cx, cy, r, fill);
                if (b.kind == SPARK) {
                    // the sparkle Watchme+'s top bar has: red until focused (then dark on the white circle)
                    fill.setColor(blend(0xFFE50914, 0xFF0B0B0D, f));
                    Sparkle.draw(c, fill, sparkle, cx - 3 * k, cy + 3 * k, k);
                } else {
                    Symbols.draw(getContext(), c, b.symbol, cx, cy, 28 * k, blend(0xFFFFFFFF, 0xFF0B0B0D, f));
                }
                if (f > 0.01f) {
                    cardFocusFont.setAlpha(Math.round(255 * f));
                    float lw = cardFocusFont.measureText(b.label);
                    float lx = rtl ? Math.min(w - M * k / 2 - lw, cx - lw / 2) : Math.max(M * k / 2, cx - lw / 2);
                    c.drawText(b.label, lx, cy + ph / 2 + 40 * k, cardFocusFont);
                    cardFocusFont.setAlpha(255);
                }
                continue;
            }
            if (f > 0.01f) {
                rect.set(tabX[i] - padX, cy - ph / 2, tabX[i] + tabW[i] + padX, cy + ph / 2);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(rect, ph / 2, ph / 2, fill);
            }
            tabFont.setColor(i - buttons == tab ? blend(0xFFFFFFFF, 0xFF0B0B0D, f) : tabDim);
            c.drawText(tabs.get(i - buttons).label, tabX[i], cy + Ui.halfCap(tabFont), tabFont);
        }
    }

    private float barFocus(int i) {
        if (focusRow == TOP && i == topIndex) return Ui.clamp(cellFocus.value);
        if (prevRow == TOP && i == prevCol) return Ui.clamp(lastFocus.value);
        return 0;
    }

    /**
     * Where each button and tab name sits: buttons from the left margin, tabs centered (measured once, not while
     * drawing). Right to left, the same from the right: the buttons at the right margin, the first tab rightmost.
     */
    private void layoutTabs() {
        if (tabFont == null) return;
        int buttons = barButtons.length, n = buttons + tabs.size();
        tabX = new float[n];
        tabW = new float[n];
        float x = M * k, total = 0;
        for (int i = 0; i < buttons; i++) {
            tabW[i] = 60 * k;
            tabX[i] = mx(x, tabW[i]);
            x += tabW[i] + TAB_GAP / 2 * k;
        }
        for (int i = buttons; i < n; i++) {
            tabW[i] = tabFont.measureText(tabs.get(i - buttons).label);
            total += tabW[i] + (i + 1 < n ? TAB_GAP * k : 0);
        }
        x = (w - total) / 2;
        for (int i = buttons; i < n; i++) {
            tabX[i] = mx(x, tabW[i]);
            x += tabW[i] + TAB_GAP * k;
        }
    }

    /** Top right (right to left: top left): the time, and below it the date and the weather. */
    private void drawClock(Canvas c, float alpha) {
        int a = Math.round(255 * alpha);
        if (a <= 1) return;
        float right = (1920 - M) * k;
        clockFont.setAlpha(a);
        c.drawText(clockText, rtl ? M * k : right, 100 * k, clockFont);
        clockFont.setAlpha(255);
        dateFont.setAlpha(a);
        float lw = dateFont.measureText(clockLine);
        c.drawText(clockLine, rtl ? M * k : right - lw, 142 * k, dateFont);
        if (weatherSymbol != null && !weatherText.isEmpty()) {
            Symbols.draw(getContext(), c, weatherSymbol, rtl ? M * k + lw + 24 * k : right - lw - 24 * k,
                    142 * k - Ui.halfCap(dateFont), 28 * k, Ui.alpha(0xFFFFFFFF, alpha));
        }
        dateFont.setAlpha(255);
    }

    private void drawRow(Canvas c, Row r, int index, float scroll) {
        float top = r.top * k + rowOffset, ch = r.h() * k, bottom = top + ch + r.below() * k + (r.tiles ? tileExtra(r) * k : 0);
        if (bottom < scroll || top - 60 * k > scroll + h) return;     // off screen
        if (!r.title.isEmpty()) Ui.text(c, r.title, M * k, top - 24 * k, rowFont, w);
        if (r.tiles) {
            drawTiles(c, r, index, top);
            return;
        }
        int focused = index == focusRow ? r.focus : -1;
        int fading = index == prevRow ? prevCol : -1;
        for (int i = 0; i < r.cells.size(); i++) {
            if (i == focused || i == fading) continue;
            drawCell(c, r, i, 0);
        }
        if (fading >= 0 && fading < r.cells.size()) drawCell(c, r, fading, lastFocus.value);
        if (focused >= 0 && focused < r.cells.size()) drawCell(c, r, focused, cellFocus.value);
    }

    private void drawCell(Canvas c, Row r, int i, float focus) {
        float cw = r.w() * k, ch = r.h() * k, gap = r.gap() * k;
        float left = mx(M * k + i * (cw + gap) - r.scrollX.value * k, cw), top = r.top * k + rowOffset;
        if (left > w || left + cw < 0) return;
        Cell cell = r.cells.get(i);
        float f = Ui.clamp(focus), s = 1 + 0.08f * focus;
        float boxLeft = r.style == S_POSTER && !rtl ? left + POSTER_NUMBER_W * k : left;
        float boxW = r.style == S_POSTER ? cw - POSTER_NUMBER_W * k : cw;
        if (r.style == S_POSTER && cell.rank != null) {
            // The number behind the poster's edge: on its left, or right to left on its right.
            float rx = rtl ? boxLeft + boxW - 26 * k : boxLeft + 26 * k;
            c.drawText(cell.rank, rx, top + ch - 6 * k, rankFont);
            c.drawText(cell.rank, rx, top + ch - 6 * k, rankStroke);
        }
        float cx = boxLeft + boxW / 2, cy = top + ch / 2;
        rect.set(cx - boxW * s / 2, cy - ch * s / 2, cx + boxW * s / 2, cy + ch * s / 2);
        float radius = RADIUS * k * s;
        if (cell.entry != null && cell.entry.match != null) {
            drawMatchCard(c, cell, radius, s);
        } else if (cell.entry != null) {
            Bitmap b = thumbs.get(cell.thumbKey, cell.entry.image, Math.round(boxW), Math.round(ch), RADIUS * k);
            if (b != null) {
                c.drawBitmap(b, null, rect, image);
            } else {
                fill.setColor(PLACEHOLDER);
                c.drawRoundRect(rect, radius, radius, fill);
            }
        } else if (cell.app != null && cell.app.tile != null) {
            drawTile(c, cell.app.tile, radius);
        } else if (cell.app != null) {
            fill.setColor(SYMBOL_CARD);
            c.drawRoundRect(rect, radius, radius, fill);
            int ink = Ui.alpha(0xFFFFFFFF, cell.app.connected ? 0.92f : 0.4f);
            Symbols.draw(getContext(), c, cell.app.symbol == null ? "apps" : cell.app.symbol, rect.centerX(), rect.centerY(), 50 * k * s, ink);
        }
        if (f > 0.01f) {
            border.setStrokeWidth(4 * k);
            border.setColor(Ui.alpha(focusRing, f));
            rect.inset(-3 * k, -3 * k);
            c.drawRoundRect(rect, radius + 3 * k, radius + 3 * k, border);
        }
        if (cell.progress >= 0) {
            // How far it was watched: a thin bar along the card's bottom, like Netflix.
            float bx = rect.left + 14 * k, bw = rect.width() - 28 * k, by = rect.bottom - 16 * k, bh = 6 * k;
            fill.setColor(0x66FFFFFF);
            c.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, fill);
            fill.setColor(0xFFE50914);
            drawProgress(c, bx, by, bw, bh, cell.progress);
        }
        drawLabels(c, r, cell, boxLeft, top + ch * (1 + 0.04f * focus), boxW, f);
    }

    /** The watched part of a progress bar: from the left, right to left from the right. */
    private void drawProgress(Canvas c, float bx, float by, float bw, float bh, float progress) {
        float done = bw * Math.max(0.04f, progress);
        if (rtl) c.drawRoundRect(bx + bw - done, by, bx + bw, by + bh, bh / 2, bh / 2, fill);
        else c.drawRoundRect(bx, by, bx + done, by + bh, bh / 2, bh / 2, fill);
    }

    private void drawLabels(Canvas c, Row r, Cell cell, float x, float cardBottom, float width, float f) {
        if (r.style == S_POSTER || r.style == S_MATCH) return;
        if (rtl) x += width;        // right-aligned, ending at the card's right edge
        if (r.style == S_APPS) {
            if (f <= 0.01f) return;
            cardFocusFont.setAlpha(Math.round(255 * f));
            Ui.textAt(c, cell.fitLabel, x, cardBottom + 40 * k, cardFocusFont);
            cardFocusFont.setAlpha(255);
            return;
        }
        Paint p = f > 0.5f ? cardFocusFont : cardFont;
        Ui.textAt(c, cell.fitLabel, x, cardBottom + 40 * k, p);
        if (r.style == S_VIDEO && !cell.fitSub.isEmpty()) Ui.textAt(c, cell.fitSub, x, cardBottom + 72 * k, subFont);
    }

    // MARK: - Watchme+ tiles

    /** The preview player (HomeActivity's surface under this view). */
    void setPreview(CinemaPreview p) {
        preview = p;
    }

    /** The preview started showing under the view (or stopped): the tile's picture fades away over it (or back). */
    void onPreview(boolean showing) {
        previewAt = showing ? SystemClock.uptimeMillis() : 0;
        invalidate();
        kick();
    }

    /** How much more room a Watchme+ row takes with its tile grown (1920 space): the tile over the poster, its words. */
    private float tileExtra(Row r) {
        return TILE_H - r.h() + tileInfoH.value - r.below();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * A Watchme+ row: posters with their names, the focused one grown into a tile (the preview plays in the hole
     * left there), the cards after it moved along, the ones leaving on the left fading out.
     */
    private void drawTiles(Canvas c, Row r, int index, float top) {
        float pw = r.w() * k, ph = r.h() * k, step = (r.w() + r.gap()) * k, extraW = (TILE_W + r.gap()) * k - step;
        float scrollX = r.scrollX.value * k, shift = 0;
        long up = SystemClock.uptimeMillis();
        for (int i = 0; i < r.cells.size(); i++) {
            Cell cell = r.cells.get(i);
            float g = Ui.clamp(cell.grow.value), x = M * k + i * step - scrollX + shift;
            shift += extraW * g;
            if (x > w) break;
            float a = Ui.clamp(1 - (M * k - x) / (step * 0.6f));
            if (a <= 0.01f) continue;
            boolean focused = index == focusRow && i == r.focus;
            float tw = lerp(pw, TILE_W * k, g), tx = mx(x, tw);     // right to left: growing leftwards
            tileRect.set(tx, top, tx + tw, top + lerp(ph, TILE_H * k, g));
            float picture = 1;
            if (focused && previewAt != 0) {
                c.drawRoundRect(tileRect, RADIUS * k, RADIUS * k, clear);
                picture = 1 - Ui.clamp((up - previewAt) / (float) TILE_FADE_MS);
            }
            drawTileCell(c, r, cell, g, a, picture, focused, up);
            if (cell == tileCell && g > 0.01f) drawTileInfo(c, x, tileRect.bottom, a * g, up);
        }
    }

    /** One card in tileRect: the poster cropped to its shape, giving way to the wide picture and logo as it grows. */
    private void drawTileCell(Canvas c, Row r, Cell cell, float g, float a, float picture, boolean focused, long up) {
        float radius = RADIUS * k, wide = Ui.clamp(g * 1.6f);
        Bitmap art = g > 0.01f ? wideArt(cell) : null;
        if (art != null) {
            if (cell.wideAt == 0) cell.wideAt = up;
            float fade = Ui.clamp((up - cell.wideAt) / (float) WIDE_FADE_MS);
            if (fade < 1) kick();
            wide *= fade;
        } else {
            wide = 0;
        }
        if (wide < 1) {
            Bitmap poster = thumbs.get(cell.thumbKey, cell.entry.image, Math.round(r.w() * k), Math.round(r.h() * k), RADIUS * k);
            if (poster != null) {
                drawCropped(c, poster, tileRect, a * picture);
            } else if (picture > 0.01f) {
                fill.setColor(Ui.alpha(PLACEHOLDER, a * picture));
                c.drawRoundRect(tileRect, radius, radius, fill);
            }
        }
        if (wide > 0.01f) {
            drawCropped(c, art, tileRect, a * picture * wide);
            Library.Title t = cell.title;
            if (t.cover.isEmpty() && !t.logo.isEmpty()) drawTileLogo(c, t.logo, a * wide * picture);
        }
        if (cell.progress >= 0) {
            float bx = tileRect.left + 14 * k, bw = tileRect.width() - 28 * k, by = tileRect.bottom - 16 * k, bh = 6 * k;
            fill.setColor(Ui.alpha(0x66FFFFFF, a));
            c.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, fill);
            fill.setColor(Ui.alpha(0xFFE50914, a));
            drawProgress(c, bx, by, bw, bh, cell.progress);
        }
        float named = a * (1 - Ui.clamp(g * 2.5f));
        if (named > 0.01f) {
            int was = cardFont.getAlpha();
            cardFont.setAlpha(Math.round(was * named));
            Ui.textAt(c, cell.fitLabel, rtl ? tileRect.right : tileRect.left, tileRect.bottom + 40 * k, cardFont);
            cardFont.setAlpha(was);
        }
        if (!focused) return;
        border.setStrokeWidth(4 * k);
        border.setColor(Ui.alpha(focusRing, a));
        rect.set(tileRect.left - 5 * k, tileRect.top - 5 * k, tileRect.right + 5 * k, tileRect.bottom + 5 * k);
        c.drawRoundRect(rect, radius + 5 * k, radius + 5 * k, border);
    }

    /** The title's wide picture at the tile's size: the catalog's cover, or TMDB's photo (asked for once), or null. */
    private Bitmap wideArt(Cell cell) {
        Library.Title t = cell.title;
        if (t == null) return null;
        if (t.backdrop == null) Library.get(getContext()).backdrop(t, artArrived);
        String source = !t.cover.isEmpty() ? t.cover : t.backdrop != null ? t.backdrop : "";
        if (source.isEmpty()) return null;
        int tw = Math.round(TILE_W * k), th = Math.round(TILE_H * k);
        if (!source.equals(cell.wideSource)) {
            cell.wideSource = source;
            cell.wideKey = Thumbs.key(source, tw, th, RADIUS * k);
            cell.wideAt = 0;
        }
        return thumbs.get(cell.wideKey, source, tw, th, RADIUS * k);
    }

    /** The bitmap filling tileRect without stretching: its middle, cut to the rect's shape. */
    private void drawCropped(Canvas c, Bitmap b, RectF r, float alpha) {
        if (alpha <= 0.01f) return;
        float bw = b.getWidth(), bh = b.getHeight(), shape = r.width() / r.height();
        if (bw / bh > shape) {
            int cw = Math.round(bh * shape), left = Math.round((bw - cw) / 2);
            src.set(left, 0, left + cw, (int) bh);
        } else {
            int ch = Math.round(bw / shape), top = Math.round((bh - ch) / 2);
            src.set(0, top, (int) bw, top + ch);
        }
        image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(b, src, r, image);
        image.setAlpha(255);
    }

    /** The title's logo at the tile's bottom left (right to left: bottom right). */
    private void drawTileLogo(Canvas c, String url, float alpha) {
        if (alpha <= 0.01f) return;
        Bitmap logo = CinemaLogos.get(getContext()).get(url);
        if (logo == null) return;
        float maxW = tileRect.width() * 0.56f, maxH = tileRect.height() * 0.42f, pad = tileRect.width() * 0.045f;
        float s = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        float lw = logo.getWidth() * s, lx = rtl ? tileRect.right - pad - lw : tileRect.left + pad;
        rect.set(lx, tileRect.bottom - pad - logo.getHeight() * s, lx + lw, tileRect.bottom - pad);
        image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(logo, null, rect, image);
        image.setAlpha(255);
    }

    /** Under the tile: the name, year · length, the story. */
    private void drawTileInfo(Canvas c, float x, float bottom, float shown, long up) {
        float a = shown * Ui.clamp((up - tileAt) / (float) TILE_TEXT_MS);
        if (a <= 0.01f) return;
        float y = bottom + TILE_NAME * k;
        tileNameFont.setAlpha(Math.round(255 * a));
        Ui.text(c, tileName, x, y, tileNameFont, w);
        y += TILE_META * k;
        metaFont.setColor(Ui.alpha(metaInk, a));
        Ui.text(c, tileFacts, x, y, metaFont, w);
        if (tileStory == null) return;
        bodyText.setColor(Ui.alpha(bodyInk, a));
        c.save();
        c.translate(Ui.layoutX(x, tileStoryRight, w), y + 20 * k);
        tileStory.draw(c);
        c.restore();
    }

    /** The focused card when it's a Watchme+ title, else null. */
    private Cell focusedTileCell() {
        if (focusRow < 0 || focusRow >= rows.size()) return null;
        Row r = rows.get(focusRow);
        if (!r.tiles || r.cells.isEmpty()) return null;
        Cell cell = r.cells.get(Math.min(r.focus, r.cells.size() - 1));
        return cell.title == null ? null : cell;
    }

    /** The focus moved: the preview stops; a Watchme+ title's words come at once, its preview once the focus rests. */
    private void tileFocusChanged() {
        handler.removeCallbacks(tileSettle);
        final Cell cell = focusedTileCell();
        String rowKey = cell == null ? null : rows.get(focusRow).key;
        if (cell != null && tileCell != null && rowKey.equals(tileRowKey) && cell.title.id.equals(tileCell.title.id)) {
            // The same title in the same row (the rows made again): it keeps playing, or starts once it rests.
            tileCell = cell;
            if (previewAt == 0) handler.postDelayed(tileSettle, TILE_SETTLE_MS);
            return;
        }
        tileRowKey = rowKey;
        if (preview != null) preview.want(null, null);
        if (cell == null || tileNameFont == null) {
            tileCell = null;
            return;
        }
        if (cell != tileCell) {
            tileCell = cell;
            tileAt = SystemClock.uptimeMillis();
            Library.Title t = cell.title;
            tileName = fit(t.title, new TextPaint(tileNameFont), 1100 * k, 'n');
            StringBuilder facts = new StringBuilder(t.year);
            String length = t.series ? Ui.tr("Série", "Series", "Serie")
                    : t.minutes >= 60 ? t.minutes / 60 + "h " + t.minutes % 60 + "m" : t.minutes > 0 ? t.minutes + "m" : "";
            if (!length.isEmpty()) facts.append(facts.length() > 0 ? "   " : "").append(length);
            tileFacts = facts.toString();
            setTileStory(t.overview);
            if (t.overview.isEmpty()) {
                // Rows often come without the story: the title's own page has it.
                Library.get(getContext()).title(t.rootId.isEmpty() ? t.id : t.rootId, full -> {
                    if (tileCell != cell || full == null || full.overview.isEmpty()) return;
                    setTileStory(full.overview);
                    invalidate();
                    kick();
                });
            }
        }
        handler.postDelayed(tileSettle, TILE_SETTLE_MS);
    }

    private void setTileStory(String overview) {
        tileStory = overview.isEmpty() ? null : layout(overview, bodyText, 1100 * k, 3);
        tileStoryRight = tileStory == null || !rtl ? 0 : Ui.lineRight(tileStory);
        tileInfoH.target = TILE_NAME + TILE_META + TILE_PAD + (tileStory == null ? 0 : 20 + tileStory.getHeight() / k);
    }

    /** The focus rested on a Watchme+ title: its preview, in the tile's place once the rows have moved there. */
    private void tileSettled() {
        Cell cell = focusedTileCell();
        if (cell == null || preview == null || !running) return;
        float top = rows.get(focusRow).top * k - scrollY.target * k;
        float left = mx(M * k, TILE_W * k);
        preview.want(cell.title, new RectF(left, top, left + TILE_W * k, top + TILE_H * k));
    }

    /** OK while the tile's preview plays: Watchme+'s player, on from that second. */
    private boolean playPreviewed(Cell cell) {
        Library.Title playing = preview == null || previewAt == 0 ? null : preview.playing();
        if (playing == null) return false;
        long at = preview.position();
        preview.stop();
        click.open();
        try {
            activity.startActivity(WatchMe.open(activity, playing.id, cell.entry.title).putExtra(CinemaActivity.EXTRA_PLAY_AT, at));
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            return false;
        }
    }

    /** Crest size on a match card (1920 space). */
    private static final float CREST = 92;

    /**
     * A match card (in `rect`): the two crests with their names, the kick-off or the score between them, the day or
     * the minute under it (red while it's on), the competition at the top; your team's matches get a gold edge.
     */
    private void drawMatchCard(Canvas c, Cell cell, float radius, float s) {
        Matches.Match m = cell.entry.match;
        fill.setShader(null);
        fill.setColor(m.mine ? 0xFF1A2440 : 0xFF181B24);
        c.drawRoundRect(rect, radius, radius, fill);
        if (m.mine) {
            border.setStrokeWidth(3 * k);
            border.setColor(0xFFE8B53A);
            c.drawRoundRect(rect, radius, radius, border);
        }
        float crest = CREST * k * s, cy = rect.top + rect.height() * 0.46f;
        float lx = rect.left + rect.width() * 0.2f, rx = rect.right - rect.width() * 0.2f;
        if (rtl) {      // the home team on the right
            float t = lx;
            lx = rx;
            rx = t;
        }
        drawCrest(c, cell.homeKey, m.home.logo, lx, cy, crest);
        drawCrest(c, cell.awayKey, m.away.logo, rx, cy, crest);
        subFont.setTextAlign(Paint.Align.CENTER);
        c.drawText(m.home.shortName, lx, cy + crest / 2 + 30 * k * s, subFont);
        c.drawText(m.away.shortName, rx, cy + crest / 2 + 30 * k * s, subFont);
        c.drawText(m.leagueName, rect.centerX(), rect.top + 34 * k * s, subFont);
        subFont.setTextAlign(Paint.Align.LEFT);
        matchFont.setTextSize(44 * k * s);
        c.drawText(cell.middle, rect.centerX() - matchFont.measureText(cell.middle) / 2, cy + Ui.halfCap(matchFont), matchFont);
        noteFont.setColor(m.state == Matches.LIVE ? 0xFFFF453A : 0xB3FFFFFF);
        c.drawText(cell.note, rect.centerX() - noteFont.measureText(cell.note) / 2, cy + 44 * k * s, noteFont);
    }

    private void drawCrest(Canvas c, String key, String url, float cx, float cy, float size) {
        int px = Math.round(CREST * k);
        Bitmap b = key == null ? null : thumbs.get(key, url, px, px, Thumbs.FIT);
        if (b == null) return;
        tmpRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        c.drawBitmap(b, null, tmpRect, image);
    }

    /** An app's 16:9 card with rounded corners (a shader: cheaper than clipping on older TVs). */
    private void drawTile(Canvas c, Bitmap tile, float radius) {
        BitmapShader shader = tileShaders.get(tile);
        if (shader == null) {
            shader = new BitmapShader(tile, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            tileShaders.put(tile, shader);
        }
        matrix.setScale(rect.width() / tile.getWidth(), rect.height() / tile.getHeight());
        matrix.postTranslate(rect.left, rect.top);
        shader.setLocalMatrix(matrix);
        tilePaint.setShader(shader);
        c.drawRoundRect(rect, radius, radius, tilePaint);
        tilePaint.setShader(null);
    }

    // MARK: - Featured title

    private Catalog.Entry currentPick() {
        return heroPicks.isEmpty() ? null : heroPicks.get(Math.min(heroIndex, heroPicks.size() - 1));
    }

    /** The carousel: the catalog's featured titles, Apple TV's popular ones, TOD's upcoming matches, YouTube's trending. */
    private void updateHeroPicks() {
        List<Catalog.Entry> list = new ArrayList<>(catalog.features());
        // Match day: your team's match (today, or on now) leads the carousel.
        Matches.Match mine = matches.myNext(14 * 3600_000L);
        if (mine != null) list.add(0, matchEntry(mine));
        if (samePicks(list, heroPicks)) return;
        Catalog.Entry was = currentPick();
        heroPicks = list;
        int keep = 0;
        for (int i = 0; was != null && i < list.size(); i++) if (pickKey(list.get(i)).equals(pickKey(was))) keep = i;
        heroIndex = keep;
        if (heroPicks.isEmpty() && focusRow == -1) focus(0, false);
        updateHeroText();
        invalidate();
    }

    private static boolean samePicks(List<Catalog.Entry> a, List<Catalog.Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!pickKey(a.get(i)).equals(pickKey(b.get(i)))) return false;
        return true;
    }

    /** What makes a featured title itself (a match: also its score and minute, so the billboard follows the game). */
    private static String pickKey(Catalog.Entry e) {
        return e.match != null ? e.match.id + '|' + e.sub : String.valueOf(e.wide);
    }

    private void showHero(int index) {
        if (heroPicks.isEmpty()) return;
        heroIndex = (index % heroPicks.size() + heroPicks.size()) % heroPicks.size();
        heroTextAt = SystemClock.uptimeMillis();
        updateHeroText();
        if (nextKey != null) thumbs.get(nextKey, nextSource, HERO_W, HERO_H, Thumbs.SCREEN);     // the next one, ready in time
        invalidate();
        kick();
    }

    /** Match day's billboard: the crests' size, and what they and the score between them are. */
    private static final float HERO_CREST = 300;
    private String heroHomeKey, heroAwayKey, heroMiddle = "";

    /** The full-screen photo: 1280 × 720 is sharp enough under the darkening and costs a third of 1920 × 1080. */
    private static final int HERO_W = 1280, HERO_H = 720;

    /** The app's name for a title (Watchme+, "Apple TV"…). */
    private static String appName(Catalog.Entry e) {
        if (Endpoints.PKG.equals(e.pkg)) return WatchMe.NAME;
        if ("com.todtv.tod".equals(e.pkg)) return "TOD";
        if (e.video != null) return "YouTube";
        return e.sub;
    }

    private void updateHeroText() {
        Catalog.Entry p = currentPick();
        heroSource = p == null ? null : p.wide;
        heroKey = heroSource == null ? null : Thumbs.key(heroSource, HERO_W, HERO_H, Thumbs.SCREEN);
        Catalog.Entry next = heroPicks.size() < 2 ? null : heroPicks.get((heroIndex + 1) % heroPicks.size());
        nextSource = next == null ? null : next.wide;
        nextKey = nextSource == null ? null : Thumbs.key(nextSource, HERO_W, HERO_H, Thumbs.SCREEN);
        if (p == null || titleText == null) {
            heroTitle = heroOverview = null;
            return;
        }
        if (p.match != null) {
            // Match day: the billboard is the match itself (crests, score or kick-off), with TOD to watch it.
            Matches.Match m = p.match;
            heroLabel = m.state == Matches.LIVE ? Ui.tr("AO VIVO", "LIVE", "EN VIVO") : Ui.tr("DIA DE JOGO", "MATCH DAY", "DÍA DE PARTIDO");
            heroMeta = p.sub;
            heroTitleText = p.title;
            heroTitle = layout(p.title, titleText, HERO_TEXT_W * k, 2);
            heroTitleRight = rtl ? Ui.lineRight(heroTitle) : 0;
            heroOverview = null;
            heroButton = Ui.tr("Assistir na TOD", "Watch on TOD", "Ver en TOD");
            heroButtonW = (34 + 26 + 14 + 34) * k + buttonFont.measureText(heroButton);
            int crest = Math.round(HERO_CREST * k);
            heroHomeKey = Thumbs.key(m.home.logo, crest, crest, Thumbs.FIT);
            heroAwayKey = Thumbs.key(m.away.logo, crest, crest, Thumbs.FIT);
            heroMiddle = Ui.digits(m.state == Matches.PRE ? new java.text.SimpleDateFormat("HH:mm", Ui.locale()).format(new java.util.Date(m.start))
                    : rtl ? m.away.score + "  -  " + m.home.score       // each score by its crest
                    : m.home.score + "  -  " + m.away.score);
            return;
        }
        String app = appName(p);
        heroLabel = app.toUpperCase(Ui.locale());
        heroMeta = p.sub.equals(app) ? "" : p.sub;     // TOD: the competition and kick-off; YouTube: the channel
        heroTitleText = p.title;
        heroTitle = layout(p.title, titleText, HERO_TEXT_W * k, 2);
        heroOverview = p.overview.isEmpty() ? null : layout(p.overview, bodyText, 900 * k, 3);
        heroTitleRight = rtl ? Ui.lineRight(heroTitle) : 0;
        heroOverviewRight = heroOverview == null || !rtl ? 0 : Ui.lineRight(heroOverview);
        heroButton = Ui.tr("Abrir no ", "Open in ", "Abrir en ") + Ui.isolate(app);
        heroButtonW = (34 + 26 + 14 + 34) * k + buttonFont.measureText(heroButton);
    }

    private static StaticLayout layout(String text, TextPaint paint, float width, int lines) {
        return Ui.layout(text, paint, width, lines);
    }

    // MARK: - Clock and weather

    private boolean updateClock() {
        long now = TrustedTime.now(), minute = now / 60_000;
        if (minute == clockMinute && clockDigits == Ui.digitMode) return false;
        clockMinute = minute;
        clockDigits = Ui.digitMode;
        clockText = Ui.clock(now);
        String d = Ui.date(now);
        dateText = d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
        clockLine = weatherText.isEmpty() ? dateText : dateText + "  ·  " + weatherText;
        int m = Moments.now();
        if (m != moment) {
            moment = m;
            greeting = Moments.greeting(m, profileName);
            if (titleFont != null) rebuildSoon();     // the moment's row and look change with it
        }
        return true;
    }

    private void updateWeather() {
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || wx == null || Double.isNaN(wx.temp)) {
            weatherText = "";
            weatherSymbol = null;
            clockLine = dateText;
            return;
        }
        String city = Ui.ar ? Arabic.city(Prefs.city()) : Prefs.city();
        weatherText = HomeData.degrees(wx.temp) + (city.isEmpty() ? "" : "  " + city);
        weatherSymbol = wx.code < 0 ? null : Symbols.weather(wx.code, wx.isDay);
        clockLine = weatherText.isEmpty() ? dateText : dateText + "  ·  " + weatherText;
    }

    // MARK: - Rows

    /**
     * Another device may have changed Continue watching or My List (cross-device sync, when this build has it): Watchme+
     * pulls (on resume, then at most once a minute while Home is up) and, if anything changed, the rows are read again.
     */
    private void pullRemote(boolean now) {
        if (!Sync.enabled()) return;
        long at = SystemClock.elapsedRealtime();
        if (!now && at - pulledAt < SyncRules.POLL_MS) return;
        pulledAt = at;
        final Context c = getContext().getApplicationContext();
        PULLER.execute(() -> {
            if (WatchMe.pullRemote(c) > 0) handler.post(() -> {
                if (running) reload();
            });
        });
    }

    /** Lists that take a while (app art, recent apps), made in the background. */
    private void reload() {
        final Context c = getContext().getApplicationContext();
        if (shownPicks.isEmpty()) {
            // A cold start: Watchme+'s picks and My List as they were last time, before anything else loads.
            LOADER.execute(() -> {
                final WatchMe.Shown last = WatchMe.lastShown(c);
                if (last != null) handler.post(() -> {
                    if (shownPicks.isEmpty()) showCinemaExtras(last.picks, last.mine);
                });
            });
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> rc = HomeApps.recent(c, 6);
            final int misses = WatchMe.misses();
            final List<Catalog.Entry> wt = continueWatching(c);
            final boolean missed = WatchMe.misses() != misses;
            final List<HomeApps.Item> in = HomeApps.allInputs(c);
            final List<HomeApps.Item> st = new ArrayList<>();
            for (HomeApps.Item it : HomeApps.drawerShortcuts()) if (it.kind != HomeApps.ALL_APPS) st.add(it);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                shelf = s;
                recent = rc;
                inputs = in;
                settingsItems = st;
                others = o;
                watched = wt;
                watchingMissed = missed;
                rebuildSoon();
            });
        });
        loadCinemaExtras();
    }

    /**
     * Watchme+'s picks for you ("Top picks for you", "Because you watched …") and My List, once per Home load (after
     * reload()'s other lists: it ends the load).
     */
    private void loadCinemaExtras() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final WatchMe.Who who = WatchMe.activeProfile(c);
            final String watching = who != null && who.named() ? who.name : "";
            handler.post(() -> {
                if (watching.equals(profileName)) return;
                profileName = watching;
                greeting = Moments.greeting(moment < 0 ? Moments.now() : moment, profileName);
                invalidate();
            });
        });
        LOADER.execute(() -> {
            final int misses = WatchMe.misses();
            final List<Library.Title> mine = WatchMe.myList(c);
            final List<WatchMe.Picks> picks = WatchMe.recommended(c);
            final boolean missed = WatchMe.misses() != misses;
            handler.post(() -> {
                if (picks != null) {
                    extrasLoaded(c, picks, mine, missed);
                    return;
                }
                // Without the Watchme+ app: WatchMatch's own copy makes them here.
                Recommend.rows(c, found -> {
                    List<WatchMe.Picks> made = new ArrayList<>();
                    for (Library.Row r : found) {
                        WatchMe.Picks p = new WatchMe.Picks();
                        p.key = r.key;
                        p.title = r.title;
                        p.items.addAll(r.items);
                        made.add(p);
                    }
                    extrasLoaded(c, made, mine, false);
                });
            });
        });
    }

    /**
     * Picks that came back empty (no network yet, Watchme+ unreachable) leave the rows shown as they are; good ones
     * replace them and are kept for the next cold start. Either way, a failure is tried again (loadDone).
     */
    private void extrasLoaded(final Context c, final List<WatchMe.Picks> picks, final List<Library.Title> mine,
            boolean missed) {
        boolean empty = !anyTitles(picks);
        if (empty && anyTitles(shownPicks)) {
            showCinemaExtras(shownPicks, mine);
        } else {
            showCinemaExtras(picks, mine);
            if (!empty && !missed) LOADER.execute(() -> WatchMe.saveShown(c, picks, mine));
        }
        extrasMissed = missed || empty;
        loadDone();
    }

    private static boolean anyTitles(List<WatchMe.Picks> picks) {
        for (WatchMe.Picks p : picks) if (!p.items.isEmpty()) return true;
        return false;
    }

    /** A Home load ended: all good, or another try after the backoff's wait (while the home screen is showing). */
    private void loadDone() {
        handler.removeCallbacks(retryLoads);
        if (!watchingMissed && !extrasMissed) {
            loads.succeeded();
            return;
        }
        loads.failed();
        AppLog.w("home rows incomplete" + (watchingMissed ? " (continue watching)" : "") + (extrasMissed ? " (picks)" : "")
                + ": again in " + loads.delay() / 1000 + " s");
        if (running) handler.postDelayed(retryLoads, loads.delay());
    }

    private void showCinemaExtras(List<WatchMe.Picks> picks, List<Library.Title> mine) {
        shownPicks = picks;
        List<Row> out = new ArrayList<>();
        for (WatchMe.Picks p : picks) out.add(cinemaRow("cin:" + p.key, p.title, p.items));
        out.add(cinemaRow("cin:mylist", Ui.tr("Minha lista", "My List", "Mi lista"), mine));
        cinemaExtras = out;
        rebuildSoon();
    }

    private static Row cinemaRow(String key, String title, List<Library.Title> items) {
        Row r = new Row();
        r.key = key;
        r.title = title;
        r.style = S_COVER;
        r.tiles = true;
        for (Library.Title t : items) {
            if (t.poster == null || t.poster.isEmpty()) continue;
            Catalog.Entry e = new Catalog.Entry();
            e.title = t.title;
            e.sub = WatchMe.NAME;
            e.image = e.still = t.poster;
            e.pkg = Endpoints.PKG;
            e.id = t.rootId.isEmpty() ? t.id : t.rootId;
            e.query = t.title;
            Cell c = new Cell();
            c.entry = e;
            c.title = t;
            c.label = e.title;
            c.sub = e.sub;
            r.cells.add(c);
        }
        return r;
    }

    /**
     * The tabs and their rows (tabs with nothing to show are left out); the focus stays on the same tab and row (by
     * key) and the same place in it.
     */
    private void buildRows() {
        String tabKey = tab < tabs.size() ? tabs.get(tab).key : "home";
        String focusKey = focusRow >= 0 && focusRow < rows.size() ? rows.get(focusRow).key : null;
        List<Row> old = new ArrayList<>();
        for (Tab t : tabs) old.addAll(t.rows);
        List<Catalog.Row> more = catalog.rows();
        List<Tab> out = new ArrayList<>();
        // Home: the apps, then (scrolling down) Continue watching, TOD, and the catalog: suggestions for you first, then
        // My List, what's new and what's featured.
        List<Row> home = new ArrayList<>();
        home.add(appRow("apps", "", S_APPS, shelf));
        if (inputsOpen) home.add(appRow("inputs", Ui.tr("Entradas", "Inputs", "Entradas"), S_TILES, inputs));
        // Home Settings > Home Rows: the order and the hidden ones (nothing saved: the order above, all shown).
        for (String entry : homeLayout) if (!HomeRows.hidden(entry)) addHomeRows(home, HomeRows.id(entry), more);
        out.add(tab("home", Ui.tr("Início", "Home", "Inicio"), APPS_TOP, home.toArray(new Row[0])));
        List<Row> discover = catalogRows(more, "top");
        discover.addAll(catalogRows(more, "on:"));
        out.add(tab("discover", Ui.tr("Descobrir", "Discover", "Descubrir"), TAB_TOP, discover.toArray(new Row[0])));
        out.add(tab("apps", Ui.ar ? "التطبيقات" : "Apps", TAB_TOP,
                appRow("recent", Ui.tr("Usados recentemente", "Recent apps", "Usadas recientemente"), S_TILES, recent),
                appRow("all", Ui.tr("Todos os apps", "All apps", "Todas las apps"), S_TILES, others),
                appRow("inputs", Ui.tr("Entradas", "Inputs", "Entradas"), S_TILES, inputs),
                appRow("settings", Ui.tr("Ajustes", "Settings", "Ajustes"), S_TILES, withAccount(settingsItems))));
        for (int i = out.size() - 1; i > 0; i--) if (out.get(i).rows.isEmpty()) out.remove(i);
        for (Tab t : out) {
            for (Row r : t.rows) {
                for (Row o : old) {
                    if (!o.key.equals(r.key)) continue;
                    r.focus = Math.min(o.focus, Math.max(0, r.cells.size() - 1));
                    r.scrollX.snap(o.scrollX.value);
                    r.scrollX.target = o.scrollX.target;
                }
            }
        }
        tabs = out;
        tab = 0;
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).key.equals(tabKey)) tab = i;
        rows = tabs.get(tab).rows;
        fitLabels();
        layoutTabs();
        int newFocus = focusRow < 0 ? focusRow : 0;
        if (newFocus == HERO && tab != 0) newFocus = 0;
        for (int i = 0; focusKey != null && i < rows.size(); i++) if (rows.get(i).key.equals(focusKey)) newFocus = i;
        focusRow = newFocus;
        prevRow = -2;
        scrollY.target = scrollTarget();
        tileFocusChanged();
        invalidate();
        kick();
    }

    /** One of the Home tab's rows (HomeRows' ids): a single row, or a family (TOD's, Watchme+'s picks…). */
    private void addHomeRows(List<Row> home, String id, List<Catalog.Row> more) {
        switch (id) {
            case HomeRows.MATCHES: home.add(matchRow()); break;
            case HomeRows.MOMENT: home.add(momentRow(more)); break;
            case HomeRows.CONTINUE: home.add(watchedRow(false)); break;
            case HomeRows.CONTINUE_YT: home.add(watchedRow(true)); break;
            case HomeRows.YOUTUBE: home.addAll(catalogRows(more, "yt:recs")); break;
            case HomeRows.TOD: home.addAll(catalogRows(more, "tod:")); break;
            case HomeRows.PICKS: for (Row r : cinemaExtras) if (!r.key.equals("cin:mylist")) home.add(r); break;
            case HomeRows.MY_LIST: for (Row r : cinemaExtras) if (r.key.equals("cin:mylist")) home.add(r); break;
            case HomeRows.LIBRARY: home.addAll(catalogRows(more, "library:")); break;
            default: break;
        }
    }

    /** Not HomeApps' kinds: the YouTube sign-in and the phone remote's buttons (Apps > Settings). */
    private static final int YOUTUBE_ACCOUNT = 100, PHONE = 101, SEARCH = 102, SPARK = 103;

    /** The settings, plus signing in to (or out of) YouTube when the app is set up for it. */
    private List<HomeApps.Item> withAccount(List<HomeApps.Item> settings) {
        List<HomeApps.Item> out = new ArrayList<>(settings);
        out.add(HomeApps.action(PHONE, "phone", Ui.tr("Celular como controle", "Phone remote", "Teléfono como control"), "sync"));
        if (!YouTubeAccount.configured()) return out;
        boolean in = YouTubeAccount.signedIn(getContext());
        out.add(HomeApps.action(YOUTUBE_ACCOUNT, "youtube-account",
                in ? Ui.tr("Sair do YouTube", "Sign out of YouTube", "Salir de YouTube")
                        : Ui.tr("Entrar no YouTube", "Sign in to YouTube", "Entrar en YouTube"), "play"));
        return out;
    }

    /**
     * YouTube's sign-in for TVs: a code to enter at google.com/device on a phone, checked every few seconds until it's
     * approved (or the code expires, or the window is closed).
     */
    private void youtubeAccount() {
        final Context c = getContext().getApplicationContext();
        if (YouTubeAccount.signedIn(c)) {
            new android.app.AlertDialog.Builder(activity)
                    .setMessage(Ui.tr("Sair do YouTube nesta TV?", "Sign out of YouTube on this TV?", "¿Salir de YouTube en esta TV?"))
                    .setPositiveButton(Ui.tr("Sair", "Sign out", "Salir"), (d, w) -> {
                        YouTubeAccount.signOut(c);
                        catalog.accountChanged();
                        buildRows();
                    })
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel", "Cancelar"), null)
                    .show();
            return;
        }
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(Ui.tr("Entrar no YouTube", "Sign in to YouTube", "Entrar en YouTube"))
                .setMessage(Ui.tr("Um momento…", "One moment…", "Un momento…"))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel", "Cancelar"), null)
                .show();
        LOADER.execute(() -> {
            try {
                final YouTubeAccount.Code code = YouTubeAccount.start();
                handler.post(() -> dialog.setMessage(Ui.tr("No celular, abra ", "On your phone, go to ", "En el teléfono, abre ")
                        + code.url + "\n" + Ui.tr("e digite este código:", "and enter this code:", "e ingresa este código:")
                        + "\n\n" + code.userCode));
                while (dialog.isShowing() && System.currentTimeMillis() < code.until) {
                    Thread.sleep(code.interval * 1000L);
                    if (!dialog.isShowing()) return;
                    if (YouTubeAccount.poll(c, code)) {
                        handler.post(() -> {
                            dialog.dismiss();
                            showToast(Ui.tr("YouTube conectado.", "Signed in to YouTube.", "YouTube conectado."));
                            catalog.accountChanged();
                            buildRows();
                        });
                        return;
                    }
                }
                handler.post(dialog::dismiss);
            } catch (Exception e) {
                AppLog.w("YouTube sign-in: " + e.getMessage());
                handler.post(() -> dialog.setMessage(Ui.tr("Não deu para entrar: ", "Couldn't sign in: ", "No se pudo entrar: ") + e.getMessage()));
            }
        });
    }

    /** A tab with its non-empty rows, stacked from `top`. */
    private static Tab tab(String key, String label, float top, Row... rows) {
        Tab t = new Tab();
        t.key = key;
        t.label = label;
        for (Row r : rows) {
            if (r.cells.isEmpty()) continue;
            r.top = top;
            top += r.h() + r.below() + ROW_GAP;
            t.rows.add(r);
        }
        return t;
    }

    private static List<Row> catalogRows(List<Catalog.Row> from, String prefix) {
        List<Row> out = new ArrayList<>();
        for (Catalog.Row r : from) if (r.key.startsWith(prefix)) out.add(catalogRow(r));
        return out;
    }

    private static Row appRow(String key, String title, int style, List<HomeApps.Item> items) {
        Row r = new Row();
        r.key = key;
        r.title = title;
        r.style = style;
        for (HomeApps.Item it : items) {
            Cell c = new Cell();
            c.app = it;
            c.label = it.label == null ? "" : it.label;
            r.cells.add(c);
        }
        return r;
    }

    /**
     * Continue watching: what's half-watched in Watchme+ (with how far), then what was opened from here
     * (Watched), newest first, each title once.
     */
    private static List<Catalog.Entry> continueWatching(Context c) {
        List<Catalog.Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (WatchMe.Watching p : WatchMe.continueWatching(c)) {
                Library.Title t = p.title;
                Catalog.Entry e = new Catalog.Entry();
                e.title = p.rootTitle.isEmpty() ? t.title : p.rootTitle;
                e.sub = t.series && t.season > 0 ? WatchMe.NAME + " · S" + t.season + " E" + t.episode : WatchMe.NAME;
                String pic = t.backdrop != null && !t.backdrop.isEmpty() ? t.backdrop : !p.rootPoster.isEmpty() ? p.rootPoster : t.poster;
                e.image = e.still = pic;
                e.pkg = Endpoints.PKG;
                e.id = t.rootId.isEmpty() ? t.id : t.rootId;
                e.query = e.title;
                e.progress = p.upNext ? 0 : p.fraction;
                if (!e.title.isEmpty() && pic != null && !pic.isEmpty() && seen.add(e.title)) out.add(e);
            }
        } catch (RuntimeException e) {
            AppLog.w("Watchme+ progress not read: " + e);
        }
        for (Catalog.Entry e : Watched.list(c)) if (seen.add(e.title)) out.add(e);
        return out;
    }

    /** Match Centre's row: the matches coming up and on now, your teams' marked; pressing one opens TOD. */
    private Row matchRow() {
        Row r = new Row();
        r.key = "matches";
        r.title = Ui.tr("Jogos", "Matches", "Partidos");
        r.style = S_MATCH;
        List<Matches.Match> list = matches.upcoming();
        for (int pass = 0; pass < 2; pass++) {      // yours first, then the rest
            for (Matches.Match m : list) {
                if (m.mine != (pass == 0) || r.cells.size() >= 16) continue;
                Cell c = new Cell();
                c.entry = matchEntry(m);
                c.label = m.title();
                c.sub = m.leagueName;
                c.middle = Ui.digits(m.state == Matches.PRE ? new java.text.SimpleDateFormat("HH:mm", Ui.locale()).format(new java.util.Date(m.start))
                        : Ui.rtl ? m.away.score + " - " + m.home.score : m.home.score + " - " + m.away.score);
                c.note = Ui.digits(m.state == Matches.LIVE ? "● " + m.clock : m.state == Matches.DONE ? m.clock
                        : Matches.when(m.start).replaceAll(" \\d+:\\d+$", ""));
                r.cells.add(c);
            }
        }
        return r;
    }

    /** A match as something to open: TOD (it carries them), its line and how it stands. */
    private static Catalog.Entry matchEntry(Matches.Match m) {
        Catalog.Entry e = new Catalog.Entry();
        e.match = m;
        e.title = m.title();
        e.pkg = "com.todtv.tod";
        e.sub = m.leagueName + " · " + (m.state == Matches.LIVE ? m.home.score + " - " + m.away.score + "  " + m.clock
                : m.state == Matches.DONE ? m.home.score + " - " + m.away.score : Matches.when(m.start));
        return e;
    }

    /**
     * The moment's row ("Tonight"): today's matches first, then the one thing you were watching, a pick for you and
     * (in the morning, a YouTube video first) one from YouTube; each title once.
     */
    private Row momentRow(List<Catalog.Row> more) {
        Row r = new Row();
        r.key = "moment";
        r.title = Moments.rowTitle(moment < 0 ? Moments.now() : moment);
        r.style = S_WIDE;
        List<Catalog.Entry> picks = new ArrayList<>();
        long now = TrustedTime.now();
        for (Catalog.Row c : more) {
            if (!c.key.equals("tod:games")) continue;
            for (Catalog.Entry e : c.items) if (e.at > now - 2 * 3600_000L && e.at < now + 14 * 3600_000L && picks.size() < 2) picks.add(e);
        }
        Catalog.Entry youtube = null, cinema = null, going = null;
        for (Catalog.Row c : more) if (c.key.equals("yt:recs") && !c.items.isEmpty()) youtube = c.items.get(0);
        for (Catalog.Entry e : watched) if (going == null && e.video == null) going = e;
        for (Row c : cinemaExtras) {
            if (cinema == null && !c.cells.isEmpty() && !c.key.equals("cin:mylist")) cinema = c.cells.get(0).entry;
        }
        if (moment == Moments.MORNING && youtube != null) picks.add(youtube);
        if (going != null) picks.add(going);
        if (cinema != null) picks.add(cinema);
        if (moment != Moments.MORNING && youtube != null) picks.add(youtube);
        Set<String> seen = new HashSet<>();
        for (Catalog.Entry e : picks) {
            if (e == null || !seen.add(e.title)) continue;
            Cell cell = new Cell();
            cell.entry = e;
            cell.label = e.title;
            cell.sub = e.sub;
            cell.progress = e.progress;
            r.cells.add(cell);
        }
        return r;
    }

    /**
     * Continue watching as a row: movies and series (the catalog, TOD, Netflix, Apple TV and the rest together), or the
     * YouTube videos on their own row.
     */
    private Row watchedRow(boolean youtube) {
        Row r = new Row();
        r.key = youtube ? "continue:youtube" : "continue";
        r.title = youtube ? Ui.tr("Continuar no YouTube", "Continue watching on YouTube", "Seguir viendo en YouTube")
                : Ui.tr("Continuar assistindo", "Continue watching", "Seguir viendo");
        r.style = youtube ? S_VIDEO : S_WIDE;
        for (Catalog.Entry e : watched) {
            if ((e.video != null) != youtube) continue;
            Cell c = new Cell();
            c.entry = e;
            c.label = e.title;
            c.sub = e.sub;
            c.progress = e.progress;
            r.cells.add(c);
        }
        return r;
    }

    private static Row catalogRow(Catalog.Row from) {
        Row r = new Row();
        r.key = from.key;
        r.title = Catalog.title(from.key);
        r.style = from.style == Catalog.POSTER ? S_POSTER : from.style == Catalog.VIDEO ? S_VIDEO
                : from.style == Catalog.COVER ? S_COVER : S_WIDE;
        int n = 0;
        for (Catalog.Entry e : from.items) {
            Cell c = new Cell();
            c.entry = e;
            c.label = e.title;
            c.sub = e.sub;
            if (r.style == S_POSTER) c.rank = Ui.digits(String.valueOf(++n));
            r.cells.add(c);
        }
        return r;
    }

    /** Labels cut to the card's width once, not while drawing. */
    /** Labels already cut to a width ("width|text" → cut), so rebuilding the rows doesn't measure them all again. */
    private final Map<String, String> fitted = new java.util.HashMap<>();

    private String fit(String text, TextPaint paint, float width, char which) {
        if (text.isEmpty()) return text;
        String key = which + (Math.round(width) + "|") + text;
        String cut = fitted.get(key);
        if (cut == null) {
            if (fitted.size() > 2000) fitted.clear();
            cut = TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString();
            fitted.put(key, cut);
        }
        return cut;
    }

    private void fitLabels() {
        if (cardFont == null) return;
        TextPaint label = new TextPaint(cardFocusFont), sub = new TextPaint(subFont);
        List<Row> all = new ArrayList<>();
        for (Tab t : tabs) all.addAll(t.rows);
        for (Row r : all) {
            float width = (r.style == S_POSTER ? r.w() - POSTER_NUMBER_W : r.w()) * k;
            for (Cell c : r.cells) {
                c.fitLabel = fit(c.label, label, width, 'l');
                c.fitSub = fit(c.sub, sub, width, 's');
                if (c.entry != null && c.entry.match != null) {
                    int crest = Math.round(CREST * k);
                    c.homeKey = Thumbs.key(c.entry.match.home.logo, crest, crest, Thumbs.FIT);
                    c.awayKey = Thumbs.key(c.entry.match.away.logo, crest, crest, Thumbs.FIT);
                } else if (c.entry != null) {
                    c.thumbKey = Thumbs.key(c.entry.image, Math.round(width), Math.round(r.h() * k), RADIUS * k);
                }
            }
        }
    }

    // MARK: - Focus and keys

    private float scrollTarget() {
        // On Home the apps stay at the bottom, under the photo; only the rows below them scroll up.
        if (focusRow < (tab == 0 ? 1 : 0) || focusRow >= rows.size()) return 0;
        return Math.max(0, rows.get(focusRow).top - (tab == 0 ? FOCUSED_ROW_Y : TAB_TOP));
    }

    private void focus(int row, boolean snap) {
        if (row != focusRow) {
            prevRow = focusRow;
            if (row == TOP) topIndex = barButtons.length + tab;
            prevCol = focusRow == TOP ? topIndex : focusRow >= 0 && focusRow < rows.size() ? rows.get(focusRow).focus : 0;
            focusRow = row;
            lastFocus.snap(prevRow == HERO ? 0 : Ui.clamp(cellFocus.value));
            lastFocus.target = 0;
            cellFocus.snap(0);
        }
        cellFocus.target = 1;
        heroFocus.target = focusRow == -1 ? 1 : 0;
        if (focusRow >= 0 && focusRow < rows.size() && rows.get(focusRow).tiles) {
            Row r = rows.get(focusRow);
            r.scrollX.target = r.focus * (r.w() + r.gap());       // the tile at the margin, room for it to grow
        }
        scrollY.target = scrollTarget();
        if (snap) {
            scrollY.snap(scrollY.target);
            cellFocus.snap(1);
            lastFocus.snap(0);
            heroFocus.snap(heroFocus.target);
        }
        tileFocusChanged();
        announce();
        invalidate();
        kick();
    }

    private void moveAlong(Row r, int dx) {
        int next = Math.max(0, Math.min(r.cells.size() - 1, r.focus + dx));
        if (next == r.focus) return;
        prevRow = focusRow;
        prevCol = r.focus;
        lastFocus.snap(Ui.clamp(cellFocus.value));
        lastFocus.target = 0;
        cellFocus.snap(0);
        cellFocus.target = 1;
        r.focus = next;
        // Keep the focused card inside the margins.
        float cw = r.w(), step = cw + r.gap(), visible = 1920 - 2 * M;
        float x = next * step, sx = r.scrollX.target;
        if (x < sx) sx = x;
        if (x + cw > sx + visible) sx = x + cw - visible;
        r.scrollX.target = r.tiles ? x : Math.max(0, sx);      // Watchme+: the tile at the margin
        tileFocusChanged();
        announce();
        click.move();
        invalidate();
        kick();
    }

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (Ui.dpad(e.getKeyCode())) {     // right to left: ← moves on, like → in the others
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Back: closes the inputs' row; from a tab's rows up to the tabs, from the tabs to Home, on Home to the apps. */
    private void back() {
        if (inputsOpen) {
            closeInputs();
        } else if (tab != 0 && focusRow != TOP) {
            focus(TOP, false);
        } else if (tab != 0) {
            selectTab(0, true);
            focus(0, false);
        } else if (focusRow != 0) {
            focus(0, false);
        } else {
            return;
        }
        click.move();
    }

    /** Inputs: their row opens right under the apps on Home, focused. */
    private void openInputs() {
        selectTab(0, true);
        inputsOpen = true;
        buildRows();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).key.equals("inputs")) focus(i, false);
    }

    private void closeInputs() {
        inputsOpen = false;
        buildRows();
        focus(0, false);
    }

    /** Shows another tab, from its top (each row keeps its place). */
    private void selectTab(int index, boolean animated) {
        index = Math.max(0, Math.min(tabs.size() - 1, index));
        if (index == tab || tabs.isEmpty()) return;
        tab = index;
        rows = tabs.get(tab).rows;
        prevRow = -2;
        scrollY.snap(0);
        if (tab == 0) heroTextAt = animated ? SystemClock.uptimeMillis() : 0;
        announce();
        invalidate();
        kick();
    }

    private void move(int dx, int dy) {
        if (dy != 0) {
            int next = focusRow + dy;
            if (next == HERO && (heroPicks.isEmpty() || tab != 0)) next += dy;     // only Home has the featured title
            while (next >= 0 && next < rows.size() && rows.get(next).cells.isEmpty()) next += dy;
            if (next < TOP || next >= rows.size()) return;
            focus(next, false);
            click.move();
            return;
        }
        if (focusRow == TOP) {
            int next = Math.max(0, Math.min(barButtons.length + tabs.size() - 1, topIndex + dx));
            if (next == topIndex) return;
            prevRow = TOP;
            prevCol = topIndex;
            lastFocus.snap(Ui.clamp(cellFocus.value));
            lastFocus.target = 0;
            cellFocus.snap(0);
            cellFocus.target = 1;
            topIndex = next;
            if (next >= barButtons.length) selectTab(next - barButtons.length, true);    // like Apple TV: focusing a tab shows it
            else announce();
            click.move();
            invalidate();
            kick();
            return;
        }
        if (rows.isEmpty()) return;
        if (focusRow == -1) {
            showHero(heroIndex + dx);
            handler.removeCallbacks(rotate);
            handler.postDelayed(rotate, HERO_EVERY);
            click.move();
            return;
        }
        moveAlong(rows.get(focusRow), dx);
    }

    private void activate() {
        if (focusRow == TOP) {
            if (topIndex >= barButtons.length) {
                move(0, 1);     // OK on a tab: into it
            } else if (barButtons[topIndex].kind == SEARCH) {
                click.open();
                activity.startActivity(new Intent(activity, SearchActivity.class));
            } else if (barButtons[topIndex].kind == SPARK) {
                click.open();
                activity.startActivity(TonightData.launch(activity, TonightActivity.STYLE_APPS));
            } else if (barButtons[topIndex].kind == HomeApps.INPUTS) {
                click.open();
                openInputs();
            } else {
                float x = topIndex < tabX.length ? tabX[topIndex] : M * k, d = 60 * k;
                open(barButtons[topIndex], new RectF(x, TAB_CY * k - d / 2, x + d, TAB_CY * k + d / 2));
            }
            return;
        }
        if (focusRow == -1) {
            Catalog.Entry p = currentPick();
            if (p != null) openEntry(p, heroButtonRect);
            return;
        }
        if (focusRow < 0 || focusRow >= rows.size()) return;
        Row r = rows.get(focusRow);
        if (r.cells.isEmpty()) return;
        Cell cell = r.cells.get(r.focus);
        if (r.tiles && playPreviewed(cell)) return;
        float cw = r.w() * k, left = mx(M * k + r.focus * (cw + r.gap() * k) - r.scrollX.value * k, cw);
        RectF from = new RectF(left, r.top * k - scrollY.value * k, left + cw, (r.top + r.h()) * k - scrollY.value * k);
        if (cell.app != null && cell.app.kind == YOUTUBE_ACCOUNT) {
            click.open();
            youtubeAccount();
        } else if (cell.app != null && cell.app.kind == PHONE) {
            click.open();
            String at = Companion.address();
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(Ui.tr("Celular como controle", "Phone remote", "Teléfono como control"))
                    .setMessage(at == null ? Ui.tr("A TV está sem rede.", "The TV has no network.", "La TV no tiene red.")
                            : Ui.tr("No celular, na mesma rede Wi-Fi, abra:", "On your phone, on the same Wi-Fi, open:",
                                    "En el teléfono, en la misma Wi-Fi, abre:") + "\n\n" + at + "\n\nPIN: " + Companion.pin(getContext())
                            + "\n\n" + Ui.tr("Depois, mande links ou títulos para a TV.", "Then send links or titles to the TV.",
                                    "Luego envía enlaces o títulos a la TV."))
                    .setPositiveButton("OK", null)
                    .show();
        } else if (cell.app != null) {
            if (open(cell.app, from) && r.key.equals("inputs")) inputsOpen = false;     // closed for next time
        } else if (cell.entry != null) {
            openEntry(cell.entry, from);
        }
    }

    /** A title or video in its app (on its own page when the app allows it), remembered for Continue watching. */
    private void openEntry(final Catalog.Entry e, final RectF from) {
        AppLog.w("open " + e.title + " (" + e.pkg + (e.id != null ? ", id " + e.id : "") + (e.tmdb != null ? ", " + e.tmdb : "") + ")");
        if (Links.service(e.pkg) != null && e.tmdb != null && e.link == null) {
            // Apple TV, Netflix: look up the title's own page first (a moment), then open the app on it.
            click.open();
            final Context c = getContext().getApplicationContext();
            LOADER.execute(() -> {
                final String link = Links.find(e.title, e.tmdb, e.pkg);
                handler.post(() -> {
                    if (link != null) e.link = link;
                    AppLog.w(link != null ? "title page: " + link : "no title page for " + e.title + ": opening the app");
                    if (e.link != null ? openLink(e, from) : open(HomeApps.app(e.pkg, e.sub), from)) Watched.add(c, e);
                });
            });
            return;
        }
        boolean opened;
        if (e.video != null) opened = openVideo(e, from);
        else if (e.link != null) opened = openLink(e, from);
        else if (Endpoints.PKG.equals(e.pkg)) opened = openLibrary(e, from);     // the title, never just the app
        else opened = e.pkg != null && open(HomeApps.app(e.pkg, e.sub), from);
        // The catalog's player keeps its own place (Progress): its titles show up in Continue watching from there.
        if (opened && !Endpoints.PKG.equals(e.pkg) && e.still != null) Watched.add(getContext(), e);
    }

    /** The title's page in Watchme+ (WatchMatch's catalog): by its number, or a search for its name. */
    private boolean openLibrary(Catalog.Entry e, RectF from) {
        click.open();
        Intent intent = WatchMe.open(activity, e.id, e.query != null ? e.query : e.title);
        try {
            activity.startActivity(intent, ActivityOptions.makeScaleUpAnimation(this, Math.round(from.left),
                    Math.round(from.top), Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height()))).toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            return open(HomeApps.app(e.pkg, WatchMe.NAME), from);
        }
    }

    private boolean open(HomeApps.Item it, RectF from) {
        click.open();
        boolean opened = HomeApps.open(activity, it, this, Math.round(from.left), Math.round(from.top),
                Math.round(from.width()), Math.round(from.height()));
        if (opened) lastOpened = it.pkg;
        else showToast(Ui.tr("Não deu para abrir ", "Couldn't open ", "No se pudo abrir ") + Ui.isolate(it.label) + ".");
        return opened;
    }

    /** A title's page in its app (TOD: its website's links open the app), or the app itself if it won't take the link. */
    private boolean openLink(Catalog.Entry e, RectF from) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(e.link)).setPackage(e.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(activity.getPackageManager()) == null) return open(HomeApps.app(e.pkg, e.sub), from);
        click.open();
        Turbo.before(activity, e.pkg);
        try {
            activity.startActivity(intent, ActivityOptions.makeScaleUpAnimation(this, Math.round(from.left),
                    Math.round(from.top), Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height()))).toBundle());
            lastOpened = e.pkg;
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            return open(HomeApps.app(e.pkg, e.sub), from);
        }
    }

    /** A YouTube video, straight in the YouTube app (the app itself if it won't take the link). */
    private boolean openVideo(Catalog.Entry e, RectF from) {
        click.open();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + e.video))
                .setPackage(e.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Turbo.before(activity, e.pkg);
        try {
            activity.startActivity(intent, ActivityOptions.makeScaleUpAnimation(this, Math.round(from.left),
                    Math.round(from.top), Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height()))).toBundle());
            lastOpened = e.pkg;
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            return open(HomeApps.app(e.pkg, "YouTube"), from);
        }
    }

    /** TalkBack reads the focused item. */
    private void announce() {
        String text;
        if (focusRow == TOP) {
            text = topIndex >= barButtons.length ? tabs.get(topIndex - barButtons.length).label : barButtons[topIndex].label;
        } else if (focusRow == -1) {
            Catalog.Entry p = currentPick();
            text = p == null ? "" : p.title + ". " + heroButton;
        } else if (focusRow >= 0 && focusRow < rows.size() && !rows.get(focusRow).cells.isEmpty()) {
            Row r = rows.get(focusRow);
            Cell c = r.cells.get(r.focus);
            text = (r.title.isEmpty() ? "" : r.title + ", ") + c.label + (c.sub.isEmpty() ? "" : ", " + c.sub);
        } else {
            return;
        }
        setContentDescription(text);
        announceForAccessibility(text);
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        kick();
    }

    /** Advances the toast queue; true while one is showing. */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) toast = null;
        if (toast == null && toastText != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next, toastText, 760 * k, 2);
                toastAt = up;
            }
        }
        return toast != null;
    }

    private void drawToast(Canvas c, long up) {
        if (toast == null) return;
        float age = (up - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 32 * k, padY = 20 * k, tw = 0, inset = rtl ? Float.MAX_VALUE : 0;
        for (int i = 0; i < toast.getLineCount(); i++) {
            tw = Math.max(tw, toast.getLineWidth(i));
            if (rtl) inset = Math.min(inset, toast.getLineLeft(i));     // Arabic lines sit at the layout's right
        }
        float top = 48 * k - (1 - a) * 16 * k;
        rect.set((w - tw) / 2f - padX, top, (w + tw) / 2f + padX, top + toast.getHeight() + 2 * padY);
        fill.setColor(Ui.alpha(0xF21C1C1E, a));
        c.drawRoundRect(rect, 30 * k, 30 * k, fill);
        int save = c.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, Math.round(255 * a));
        c.translate(rect.left + padX - inset, rect.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            // The last day's, by a clock that's right (while it's wrong, none is too old).
            long cutoff = TrustedTime.suspect() ? Long.MIN_VALUE : TrustedTime.now() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Helpers

    /** The left edge of a box `size` wide at x from the left: as far from the right edge right to left. */
    private float mx(float x, float size) {
        return rtl ? w - x - size : x;
    }

    private static float ease(float t) {
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
