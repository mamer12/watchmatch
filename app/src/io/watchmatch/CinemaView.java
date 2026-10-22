package io.watchmatch;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Watchme+ (the catalog), laid out like Netflix:
 *  - a bar along the top (Search, Home, Movies, Series, New & Popular, My List, Settings), reached with ↑ from the top
 *    of a page;
 *  - Home, Movies and Series open on a billboard: one of the catalog's banner titles over the whole width, with Play,
 *    My List and More info (CinemaBillboard); resting there, its preview plays behind it (CinemaPreview) and, left
 *    alone a while longer, full screen;
 *  - below, rows of box art (CinemaCards): Continue watching, Top 10, Top picks, My List, Because you watched…, the
 *    server's own collections, the newest, one row per genre. Going down, the billboard gives way to the focused title,
 *    as on Netflix's TV app: its picture over the top of the screen with its logo, match, year, rating, length and story,
 *    and after a moment its preview behind them; the focused row stays put (the ones above slide away), the focused card
 *    is outlined;
 *  - OK opens the title's page (CinemaDetails); held, it adds the title to My List (on Continue watching: takes the
 *    card out of the row). Back goes to the billboard, then to the top bar, then out.
 * Drawn on one Canvas in a 1920×1080 space (k = width / 1920), like the home screen; frames only while something moves.
 */
final class CinemaView extends View implements Choreographer.FrameCallback, Thumbs.Listener, CinemaSettings.Listener,
        CinemaSearch.Listener, CinemaBillboard.Listener {
    /** What the activity does for the view: the player, the preview under the page, and leaving. */
    interface Host {
        void play(List<Library.Title> list, int index, Library.Title root, boolean fromStart);
        /** The focus rested on `t`: its preview after a moment, full screen behind the page (where: null); null stops it. */
        void preview(Library.Title t, RectF where);
        /** The same, starting after `delayMs` (the title page waits for its picture to be seen first). */
        void preview(Library.Title t, RectF where, long delayMs);
        /** The welcome questions again (Settings). */
        void redoWelcome();
        /** Reset {catalog} data (Settings): clears it all, then the welcome questions. */
        void resetWatchme();
        /** The profile screens ended (or Watchme+ opened straight into a profile): the active profile may need its welcome questions. */
        void profileReady();
        /** The account screens at the start are over (signed in, created or guest): on to the profiles. */
        void accountReady();
        /** The preview's subtitle on screen now, or null. */
        String previewSubtitle();
        void exit();
    }

    static final int RED = 0xFFE50914, BACKGROUND = 0xFF141414;
    static final float L = 110, RADIUS = 8;
    /**
     * The focused row's posters top at ROW_Y (each row: its name, the posters, their names under them); its focused
     * poster grows into a wide tile (CinemaCards.TILE_W × TILE_H) with the title's words under it (infoH, as tall as
     * they are), so the rows below it move down by tileExtra().
     */
    private static final float NAV_Y = 92, GAP = 16, ROW_STEP = CinemaCards.POSTER_H + 120, ROW_Y = 170,
            LABEL_H = 34, INFO_NAME = 52, INFO_META = 48, INFO_PAD = 18;
    private static final long PREVIEW_FADE_MS = 400;
    /** One pace for everything that moves in the rows (the rows, a row's cards, the tiles growing), so they arrive together. */
    static final float MOTION = 0.34f;
    private static final int P_SEARCH = 0, P_HOME = 1, P_MOVIES = 2, P_SERIES = 3, P_NEW = 4, P_LIST = 5, P_SETTINGS = 6,
            PAGES = 7, GRID_COLUMNS = 5;
    /** How far the first row rises into the billboard's bottom (under its buttons, like Netflix's peek). */
    private static final float BILLBOARD_OVERLAP = 64;
    /** The avatar at the end of the top bar (after the gear): it opens the profile menu. */
    private static final int NAV_PROFILE = P_SETTINGS + 1;
    /** Spark's sparkle in the top bar, between the lens and Home (not a page: OK opens its own screen). */
    private static final int NAV_SPARK = -1;
    private final android.graphics.Path sparkle = new android.graphics.Path();
    /** focusRow on the billboard. */
    private static final int BILLBOARD = -1;
    /** The focused card grows after resting this long; the picture and preview follow once it's grown. */
    private static final long TEXT_MS = 140, TEXT_FADE_MS = 260, SETTLE_MS = 700, RETRY_MS = 8000, TOAST_MS = 2800, ROTATE_MS = 9000;
    /** OK held this many repeats: My List (on Continue watching: out of the row). */
    private static final int LONG_PRESS_REPEATS = 6;
    private static final int PLACEHOLDER = 0xFF232327;

    private static final class Line {
        String key = "", title = "";
        Library.Row source;
        final List<CinemaCards.Card> cells = new ArrayList<>();
        int focus;
        final Spring scrollX = new Spring(MOTION, 1f, 0);
        /** Its cards' biggest growth (a tile shrinking or growing in it): the rows under it make that much room. */
        float grown;
    }

    private final Host host;
    private final Library library;
    private final Thumbs thumbs;
    private final CinemaLogos logos;
    final CinemaArt art;
    private final CinemaCards cards;
    private final CinemaBillboard billboard;
    private final CinemaDetails details;
    private final CinemaSettings settings;
    private final CinemaSearch search;
    private final ProfileScreens profiles;
    private final AccountScreens account;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressWarnings("unchecked")
    private final List<Line>[] pages = new List[PAGES];
    private final int[] pageRow = new int[PAGES];
    private final boolean[] pageLoading = new boolean[PAGES];
    /** Home: the server's rows, and the ones made from the history (Recommend), shown after Continue watching. */
    private List<Line> serverLines;
    private List<Library.Row> recommended = new ArrayList<>(), top10 = new ArrayList<>();
    private List<Library.Title> banner = new ArrayList<>();
    private long historyStamp = -1;
    private boolean okDown, longPressed;
    private String toast = "";
    private long toastAt;
    private int page = P_HOME, focusRow = BILLBOARD, navFocus = P_HOME;
    private boolean inNav, homeFailed;
    /** The page's scroll (1920 space), how far the focused card has grown, the bar's visibility. */
    /** Where the rows are, in rows: the focused one's index (billboardPos() on the billboard). */
    private final Spring rowPos = new Spring(MOTION, 1f, 0), navShown = new Spring(0.28f, 1f, 1);
    /** The words under the tile: their height (1920 space), following the focused title's. */
    private final Spring infoH = new Spring(MOTION, 1f, INFO_NAME + INFO_META + INFO_PAD);
    /** The focused title's name under its tile (fitted to the width). */
    private String heroTileName = "";
    private Paint tileNameFont;
    private final Paint clear = new Paint();
    private final RectF tile = new RectF();

    // The focused title's hero (over the top of the screen, while in the rows)
    private CinemaCards.Card heroCard;
    private android.text.StaticLayout heroName, heroStory;
    private float heroStoryRight;       // where heroStory's lines end (right to left: anchored there)
    private String heroKind = "", heroFacts = "", heroRating = "", heroText = "";
    private long heroAt;

    // The preview: since when it shows, and its subtitles
    private long previewAt, lastKeyAt;
    private final SubtitleText previewText;

    // Frames and paints
    private boolean running, framePosted;
    private long lastNanos;
    int w, h;
    float k = 1;
    Paint titleFont, metaFont, bodyFont, labelFont, otherFont, rowFont, navFont, statusFont, buttonFont;
    android.text.TextPaint titleText, bodyText;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rect = new RectF();
    private final RectF bar = new RectF();
    private Shader navShade, rowsShade, rowsLeft, rowsBottom;
    private Paint markFont;

    /** Watching a full-screen preview this long without a key: the page fades away. */
    private static final long IMMERSE_MS = 12_000, IMMERSE_FADE_MS = 700, SUBTITLE_TICK_MS = 200;
    // A preview played untouched long enough opens to the whole screen, as on Netflix.
    private final Runnable immerse = () -> {
        placePreview(true);
        kickNow();
    };
    private final Runnable artArrived = this::kickNow;
    private final Runnable heroSoon = this::updateHero;
    private final Runnable settle = this::settle;
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (running && focusRow != BILLBOARD && billboardShown() > 0.9f && !details.isOpen()) billboard.next(1);
            handler.postDelayed(this, ROTATE_MS);
        }
    };
    private final Runnable subtitleTick = new Runnable() {
        @Override public void run() {
            if (previewAt == 0) return;
            if (previewText.set(host.previewSubtitle())) invalidate();
            handler.postDelayed(this, SUBTITLE_TICK_MS);
        }
    };
    private final Runnable retryHome = () -> {
        if (serverLines == null || serverLines.isEmpty()) loadHome();
    };

    CinemaView(Activity a, Host host) {
        super(a);
        this.host = host;
        library = Library.get(a);
        thumbs = Thumbs.get(a);
        logos = CinemaLogos.get(a);
        art = new CinemaArt(thumbs);
        cards = new CinemaCards(this, library, thumbs, logos, artArrived);
        billboard = new CinemaBillboard(this, logos, this);
        details = new CinemaDetails(this, library, thumbs, host);
        settings = new CinemaSettings(this, this);
        search = new CinemaSearch(this, library, thumbs, this);
        profiles = new ProfileScreens(this, settings, new ProfileScreens.Host() {
            @Override public void profileChanged() {
                reloadForProfile();
            }

            @Override public void exitWatchme() {
                CinemaView.this.host.exit();
            }

            @Override public void profilesClosed() {
                CinemaView.this.host.profileReady();
                kickNow();
            }
        });
        account = new AccountScreens(this, settings, new AccountScreens.Host() {
            @Override public void accountDone(boolean atStart) {
                reloadForProfile();      // what the account brought in is read again
                if (atStart) CinemaView.this.host.accountReady();
                else profiles.openPicker(false);
            }

            @Override public void exitWatchme() {
                CinemaView.this.host.exit();
            }

            @Override public void accountClosed() {
                kickNow();
            }
        });
        previewText = new SubtitleText(a);
        clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        clear.setAntiAlias(true);
        setFocusable(true);
        setContentDescription(WatchMe.NAME);
        stroke.setStyle(Paint.Style.STROKE);
        loadHome();
    }

    CinemaCards cards() {
        return cards;
    }

    void kickNow() {
        invalidate();
        kick();
    }

    // MARK: - Lifecycle (called by CinemaActivity)

    void resume() {
        if (running) return;
        running = true;
        lastNanos = 0;
        thumbs.setListener(this);
        logos.setListener(artArrived);
        rebuildHome();
        if (page == P_LIST) openPage(P_LIST);
        details.refresh();
        billboard.myListChanged();
        refreshRecommendations();
        handler.postDelayed(rotate, ROTATE_MS);
        settleSoon();
        kickNow();
    }

    /** Sync pulled changes from another device (the lists are read again) or its status changed (just redrawn). */
    void syncChanged(boolean dataChanged) {
        if (!running) return;
        if (dataChanged) {
            rebuildHome();
            if (page == P_LIST) openPage(P_LIST);
            details.refresh();
            billboard.myListChanged();
            refreshRecommendations();
        }
        kickNow();
    }

    void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
        handler.removeCallbacks(settle);
        handler.removeCallbacks(heroSoon);
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(subtitleTick);
        host.preview(null, null);
        thumbs.setListener(null);
        logos.setListener(null);
        thumbs.trim();      // the player gets the memory
    }

    @Override public void onThumb() {
        kickNow();
    }

    // MARK: - Pages and rows

    private void loadHome() {
        pageLoading[P_HOME] = true;
        library.banner(list -> {
            banner = list;
            boolean had = hasBillboard();
            if (page == P_HOME || page == P_MOVIES || page == P_SERIES) billboard.setItems(billboardItems());
            // It came after the rows: the page opens on it (unless keys were pressed meanwhile).
            if (!had && hasBillboard() && lastKeyAt == 0 && !details.isOpen()) moveTo(BILLBOARD, 0);
            kickNow();
        });
        Recommend.top10(getContext(), rows -> {
            top10 = rows;
            rebuildHome();
        });
        library.home(rows -> {
            pageLoading[P_HOME] = false;
            List<Line> lines = new ArrayList<>();
            for (Library.Row r : rows) lines.add(line(r));
            homeFailed = rows.isEmpty();
            if (homeFailed) handler.postDelayed(retryHome, RETRY_MS);
            serverLines = lines;
            rebuildHome();
            if (page == P_HOME) pageChanged();
        });
    }

    /** The billboard's titles for this page: the banner's, of the page's kind. */
    private List<Library.Title> billboardItems() {
        List<Library.Title> out = new ArrayList<>();
        for (Library.Title t : banner) {
            if (page == P_MOVIES && t.series) continue;
            if (page == P_SERIES && !t.series) continue;
            out.add(t);
        }
        return out;
    }

    private boolean hasBillboard() {
        return (page == P_HOME || page == P_MOVIES || page == P_SERIES) && !billboard.isEmpty();
    }

    /** Recommendations again when something new was watched or rated (a few searches: in the background). */
    private void refreshRecommendations() {
        List<Progress.Entry> history = Progress.history(getContext());
        long stamp = history.size() * 31L + (history.isEmpty() ? 0 : history.get(0).at) + CinemaPrefs.myList(getContext()).size()
                + 1_000_003L * CinemaPrefs.ratingsStamp(getContext());
        if (stamp == historyStamp) return;
        historyStamp = stamp;
        Recommend.rows(getContext(), rows -> {
            recommended = rows;
            CinemaPrefs.saveRecommended(getContext(), rows);
            rebuildHome();
        });
    }

    /**
     * Home's rows: Continue watching, Top picks, the first "Because you…", the Top 10s, the other "Because you…", My
     * List, then the server's own. Rebuilt after the player and when recommendations arrive; the focus stays put.
     */
    private void rebuildHome() {
        if (serverLines == null) return;
        List<Line> old = pages[P_HOME];
        String focusKey = page == P_HOME && old != null && focusRow >= 0 && focusRow < old.size() ? old.get(focusRow).key : null;
        List<Line> lines = new ArrayList<>();
        Line cont = continueLine();
        if (!cont.cells.isEmpty()) lines.add(cont);
        List<Line> because = new ArrayList<>();
        for (Library.Row r : recommended) {
            if (r.key.startsWith("because")) because.add(line(r));
            else lines.add(line(r));
        }
        // As on the website: Continue watching, Top picks, the first "Because you…", the Top 10s, the other
        // "Because you…" rows, My List, then the rest.
        if (!because.isEmpty()) lines.add(because.remove(0));
        for (Library.Row r : top10) lines.add(ranked(line(r)));
        lines.addAll(because);
        Line mine = gridLine("mylist", Ui.tr("Minha lista", "My List", "Mi lista"), CinemaPrefs.myList(getContext()));
        if (!mine.cells.isEmpty()) lines.add(mine);
        lines.addAll(serverLines);
        for (Line l : lines) {
            for (int i = 0; old != null && i < old.size(); i++) {
                Line o = old.get(i);
                if (!o.key.equals(l.key)) continue;
                l.focus = Math.min(o.focus, Math.max(0, l.cells.size() - 1));
                l.scrollX.snap(l.focus * cardStep(l));
            }
        }
        pages[P_HOME] = lines;
        if (page != P_HOME || focusKey == null) return;
        int at = 0;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).key.equals(focusKey)) at = i;
        focusRow = at;
        pageRow[P_HOME] = at;
        rowPos.snap(posFor(at));
        kickNow();
    }

    private void openPage(int p) {
        page = p;
        if (p == P_SEARCH) {
            pages[p] = new ArrayList<>();
            search.reset();
            search.setSuggestions(suggestions());
        } else if (p == P_SETTINGS) {
            pages[p] = new ArrayList<>();
            settings.open();
        } else if (p == P_LIST) {
            pages[p] = grid(CinemaPrefs.myList(getContext()), "mylist");
        } else if (p == P_NEW) {
            pages[p] = newAndPopular();
        } else if (pages[p] == null) {
            List<Line> lines = new ArrayList<>();
            if (p == P_MOVIES || p == P_SERIES) {
                for (Library.Row r : Library.rows(p == P_MOVIES ? Library.MOVIES : Library.SERIES)) lines.add(line(r));
            }
            pages[p] = lines;
        }
        if (p != P_SETTINGS) settings.close();
        if (p == P_HOME || p == P_MOVIES || p == P_SERIES) billboard.setItems(billboardItems());
        pageChanged();
    }

    @Override public void redoWelcome() {
        host.redoWelcome();
    }

    /** A setting changed what the catalog shows (family filter, title language, history cleared): load again. */
    @Override public void catalogChanged() {
        pages[P_MOVIES] = pages[P_SERIES] = pages[P_SEARCH] = null;
        serverLines = null;
        recommended = new ArrayList<>();
        historyStamp = -1;
        pages[P_HOME] = null;
        loadHome();
        refreshRecommendations();
        invalidate();
    }

    private void pageChanged() {
        List<Line> lines = lines();
        focusRow = hasBillboard() ? BILLBOARD : Math.min(Math.max(0, pageRow[page]), Math.max(0, lines.size() - 1));
        billboard.resetButton();
        rowPos.snap(posFor(focusRow));
        loadAround();
        settleSoon();
        kickNow();
    }

    private List<Line> lines() {
        List<Line> l = pages[page];
        return l == null ? Collections.<Line>emptyList() : l;
    }

    private Line line(Library.Row r) {
        Line l = new Line();
        l.key = r.key;
        l.title = r.title;
        l.source = r;
        fill(l);
        return l;
    }

    /** A Top 10 row: each card knows its rank. */
    private static Line ranked(Line l) {
        for (int i = 0; i < l.cells.size(); i++) l.cells.get(i).rank = i + 1;
        return l;
    }

    /** Cards for the titles the row has so far (more arrive as it's scrolled). */
    private static void fill(Line l) {
        for (int i = l.cells.size(); i < l.source.items.size(); i++) l.cells.add(card(l.source.items.get(i), null));
    }

    private static CinemaCards.Card card(Library.Title t, Progress.Entry progress) {
        CinemaCards.Card c = new CinemaCards.Card();
        c.title = t;
        c.progress = progress;
        c.poster = progress != null && !progress.rootPoster.isEmpty() ? progress.rootPoster : t.poster;
        if (progress != null && !progress.from.isEmpty() && !progress.upNext) {
            c.fromText = Ui.tr("Continuado de ", "Continued from ", "Continuado desde ", "تابع من ") + Ui.isolate(progress.from);
        }
        return c;
    }

    /** New & Popular: the newest movies and series, the Top 10s, and what everyone's watching (the catalog's banner). */
    private List<Line> newAndPopular() {
        List<Line> lines = new ArrayList<>();
        lines.add(line(Library.rows(Library.MOVIES).get(0)));
        lines.add(line(Library.rows(Library.SERIES).get(0)));
        for (Library.Row r : top10) lines.add(ranked(line(r)));
        if (!banner.isEmpty()) {
            lines.add(gridLine("everyone", Ui.tr("Todo mundo está assistindo", "Everyone's watching", "Lo que todos ven"), banner));
        }
        return lines;
    }

    /** A row of titles already in hand (My List). */
    private static Line gridLine(String key, String title, List<Library.Title> titles) {
        Line l = new Line();
        l.key = key;
        l.title = title;
        for (Library.Title t : titles) l.cells.add(card(t, null));
        return l;
    }

    /** Titles as rows of GRID_COLUMNS (My List). */
    private static List<Line> grid(List<Library.Title> titles, String key) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < titles.size(); i += GRID_COLUMNS) {
            lines.add(gridLine(key + "-" + i, i == 0 ? Ui.tr("Minha lista", "My List", "Mi lista") : "",
                    titles.subList(i, Math.min(titles.size(), i + GRID_COLUMNS))));
        }
        return lines;
    }

    private Line continueLine() {
        Line l = new Line();
        l.key = "continue";
        l.title = Ui.tr("Continuar assistindo", "Continue watching", "Seguir viendo");
        for (Progress.Entry e : Progress.unfinished(getContext())) l.cells.add(card(e.title, e));
        return l;
    }

    /** The rows near the focus load (and the focused one keeps loading pages near its end). */
    private void loadAround() {
        List<Line> lines = lines();
        int from = Math.max(0, focusRow - 1);
        for (int i = from; i < Math.min(lines.size(), Math.max(0, focusRow) + 3); i++) {
            final Line l = lines.get(i);
            Library.Row r = l.source;
            if (r == null || r.loading || !r.more) continue;
            boolean nearEnd = i == focusRow && l.focus >= l.cells.size() - 8;
            if (r.loaded && !nearEnd) continue;
            final List<Line> owner = lines;
            library.more(r, added -> rowLoaded(owner, l));
        }
    }

    private void rowLoaded(List<Line> owner, Line l) {
        fill(l);
        int index = owner.indexOf(l);
        if (l.cells.isEmpty() && !l.source.more && index >= 0) {
            owner.remove(index);        // nothing in this genre
            if (owner == lines() && focusRow > index) focusRow--;
            if (owner == lines() && focusRow >= 0) focusRow = Math.max(0, Math.min(focusRow, owner.size() - 1));
            rowPos.target = posFor(focusRow);
            if (owner == lines()) loadAround();
        }
        if (owner == lines()) kickNow();
    }

    /** Search before typing: the top picks, else the first of the server's rows. */
    private List<Library.Title> suggestions() {
        for (Library.Row r : recommended) if (r.key.equals("picks")) return r.items;
        return serverLines == null || serverLines.isEmpty() ? new ArrayList<Library.Title>() : serverLines.get(0).source.items;
    }

    @Override public void openResult(Library.Title t) {
        details.open(t, t.poster);
        kickNow();
    }

    /** Opened from outside (CinemaActivity.EXTRA_ID): straight to the title's page. */
    void openTitle(Library.Title t) {
        if (t == null) return;
        inNav = false;
        details.open(t, t.poster);
    }

    /** Opened from outside (CinemaActivity.EXTRA_SETTINGS): the Settings page. */
    void openSettings() {
        inNav = false;
        navFocus = P_SETTINGS;
        if (page != P_SETTINGS) openPage(P_SETTINGS);
    }

    /** Opened from outside (CinemaActivity.EXTRA_RESET): the reset confirmation, on the Settings page (an adult's PIN first, when any has one). */
    void askReset() {
        profiles.requireAdult(() -> {
            openSettings();
            settings.askReset();
            invalidate();
        });
    }

    /** Opened from outside (CinemaActivity.EXTRA_PROFILES): "Who's watching?". */
    void openProfiles() {
        inNav = false;
        host.preview(null, null);
        profiles.openPicker(false);
    }

    /** Watchme+ opened: the picker or a PIN when the profiles need one. True when a screen is up (nothing loads for the profile until it ends). */
    boolean startProfiles() {
        host.preview(null, null);
        return profiles.start();
    }

    /** Watchme+ opened: the welcome (sign in, create an account, guest) when this TV has not chosen yet. True when a screen is up. */
    boolean startAccount() {
        host.preview(null, null);
        return account.start();
    }

    @Override public void signInWithPhone() {
        host.preview(null, null);
        account.openPhoneFromSettings();
    }

    @Override public void manageAccount() {
        host.preview(null, null);
        account.openHome();
    }

    /** Profile screens from Settings (Profiles row): management, after an adult's PIN when any has one. */
    void openManageProfiles() {
        host.preview(null, null);
        profiles.openManage(false);
    }

    /** The active profile is another one (or became Kids, or was reset): its lists, rows and settings are read again. */
    void reloadForProfile() {
        Library.arabicFirst = CinemaPrefs.arabicTitles(getContext());
        settings.close();
        details.refresh();
        inNav = false;
        catalogChanged();
        pageRow[P_HOME] = 0;
        openPage(P_HOME);
        billboard.myListChanged();
        kickNow();
    }

    @Override public void manageProfiles() {
        openManageProfiles();
    }

    /** The confirmed reset: Watchme+'s data goes (WatchmeReset), then the welcome questions start again. */
    @Override public void resetAll() {
        host.resetWatchme();
    }

    /** Opened from outside (CinemaActivity.EXTRA_QUERY): the search page, the words already searched. */
    void openSearch(String query) {
        inNav = false;
        if (page != P_SEARCH) openPage(P_SEARCH);
        search.setQuery(query);
    }

    // MARK: - Layout

    /** The rows' position on the billboard: the first row's cards start under its buttons. */
    private static float billboardPos() {
        return -(CinemaBillboard.HEIGHT - BILLBOARD_OVERLAP - ROW_Y) / ROW_STEP;
    }

    private float posFor(int row) {
        return row == BILLBOARD ? billboardPos() : row;
    }

    /** A row's top on screen (1920 space) for the rows' position `pos`. */
    /** How much more room a row takes with its tile grown: the tile's height over the poster's, and its words. */
    private float tileExtra() {
        return CinemaCards.TILE_H - CinemaCards.POSTER_H + infoH.value - LABEL_H;
    }

    /** From one card of a row to the next (1920 space): posters, wider in a Top 10 row (the rank beside each). */
    private static float cardStep(Line l) {
        return (l.cells.isEmpty() ? CinemaCards.POSTER_W : CinemaCards.width(l.cells.get(0))) + GAP;
    }

    /** The focused tile's place on screen once the rows rest (pixels). */
    private RectF tileRect() {
        RectF r = new RectF(L * k, ROW_Y * k, (L + CinemaCards.TILE_W) * k, (ROW_Y + CinemaCards.TILE_H) * k);
        if (Ui.rtl) r.set(w - r.right, r.top, w - r.left, r.bottom);       // right to left: at the right margin
        return r;
    }

    /** The playing preview into the focused tile, or (`full`) onto the whole screen. */
    private void placePreview(boolean full) {
        if (details.isOpen() || focusRow == BILLBOARD || previewAt == 0) return;
        CinemaCards.Card c = focusedCard();
        if (c != null) host.preview(c.title, full ? null : tileRect());
    }

    /** How much the billboard shows: 1 on it, 0 in the rows (its words fade, the focused title's come in). */
    private float billboardShown() {
        return hasBillboard() ? Ui.clamp(rowPos.value / billboardPos()) : 0;
    }

    // MARK: - Focus

    private CinemaCards.Card focusedCard() {
        List<Line> lines = lines();
        if (focusRow < 0 || focusRow >= lines.size()) return null;
        Line l = lines.get(focusRow);
        return l.cells.isEmpty() ? null : l.cells.get(Math.min(l.focus, l.cells.size() - 1));
    }

    private void moveTo(int row, int col) {
        List<Line> lines = lines();
        if (row == BILLBOARD && hasBillboard()) {
            focusRow = BILLBOARD;
        } else {
            if (lines.isEmpty()) return;
            focusRow = Math.max(0, Math.min(row, lines.size() - 1));
            Line l = lines.get(focusRow);
            l.focus = Math.max(0, Math.min(col, Math.max(0, l.cells.size() - 1)));
            l.scrollX.target = l.focus * cardStep(l);
            pageRow[page] = focusRow;
        }
        rowPos.target = posFor(focusRow);
        loadAround();
        settleSoon();
        kickNow();
    }

    /** The focus moved: the preview stops; the title's words follow at once, its picture and preview once it rests. */
    private void settleSoon() {
        handler.removeCallbacks(settle);
        handler.removeCallbacks(heroSoon);
        host.preview(null, null);
        handler.postDelayed(heroSoon, TEXT_MS);
        handler.postDelayed(settle, SETTLE_MS);
    }

    /** The focus rested: the billboard's or the focused title's picture (TMDB's wide photo), then its preview behind. */
    private void settle() {
        if (details.isOpen() || inNav || !running || profiles.isOpen()) return;
        Library.Title t;
        String poster;
        if (focusRow == BILLBOARD) {
            t = billboard.current();
            poster = t == null ? "" : t.poster;
        } else {
            CinemaCards.Card c = focusedCard();
            t = c == null ? null : c.title;
            poster = c == null ? "" : c.poster;
        }
        if (t == null) return;
        final Library.Title shown = t;
        final String p = poster;
        art.show(t, poster);
        library.backdrop(t, () -> {
            if (!details.isOpen()) art.show(shown, p);
            kickNow();
        });
        // The billboard's plays on the whole screen (not on the mirror: its line is kept for what is played); a card's inside its tile.
        if (focusRow == BILLBOARD && !HeroRules.billboardPreview(Endpoints.active().id)) host.preview(null, null);
        else host.preview(t, focusRow == BILLBOARD ? null : tileRect());
    }

    /** The focused title's words for the hero: logo or name, match, year, rating, length, story. */
    private void updateHero() {
        CinemaCards.Card c = focusRow == BILLBOARD ? null : focusedCard();
        if (c == heroCard) return;
        heroCard = c;
        heroAt = SystemClock.uptimeMillis();
        heroRating = "";
        if (c == null || titleText == null) {
            heroName = heroStory = null;
            kickNow();
            return;
        }
        final Library.Title t = c.title;
        // Its video files now, while the focus settles, so the preview doesn't wait for them (cached 10 minutes).
        library.warmPlayback(t);
        if (!t.series || !t.rootId.isEmpty()) library.background(() -> {
            try {
                library.sources(t.id);
            } catch (Exception ignored) {
                // the preview asks again, and says why
            }
        });
        heroText = c.progress != null && !c.progress.rootTitle.isEmpty() ? c.progress.rootTitle : t.title;
        heroName = Ui.layout(heroText, titleText, 1000 * k, 2);
        heroTileName = android.text.TextUtils.ellipsize(heroText, new android.text.TextPaint(tileNameFont), 1100 * k,
                android.text.TextUtils.TruncateAt.END).toString();
        setStory(t.overview);
        if (t.overview.isEmpty()) {
            // Rows often come without the story: the title's own page has it (a series': the series').
            final CinemaCards.Card asked = c;
            library.title(t.rootId.isEmpty() ? t.id : t.rootId, full -> {
                if (heroCard == asked && full != null && !full.overview.isEmpty()) {
                    setStory(full.overview);
                    kickNow();
                }
            });
        }
        heroKind = t.series ? Ui.tr("SÉRIE", "SERIES", "SERIE") : Ui.tr("FILME", "FILM", "PELÍCULA");
        StringBuilder facts = new StringBuilder();
        if (!t.year.isEmpty()) facts.append(t.year);
        String length = c.progress != null && t.series && t.season > 0 ? "S" + t.season + ":E" + t.episode
                : t.series ? Ui.tr("Série", "Series", "Serie")
                : Ui.ar && t.minutes > 0 ? Arabic.duration(t.minutes)
                : t.minutes >= 60 ? t.minutes / 60 + "h " + t.minutes % 60 + "m" : t.minutes > 0 ? t.minutes + "m" : "";
        if (!length.isEmpty()) facts.append(facts.length() > 0 ? "   " : "").append(length);
        heroFacts = facts.toString();
        final CinemaCards.Card target = c;
        library.info(t.id, i -> {
            if (heroCard != target) return;
            heroRating = i.rating;
            kickNow();
        });
        kickNow();
    }

    /** The story under the tile (three lines at most), and the room it takes. */
    private void setStory(String overview) {
        heroStory = overview.isEmpty() ? null : Ui.layout(overview, bodyText, 1100 * k, 3);
        heroStoryRight = heroStory == null || !Ui.rtl ? 0 : Ui.lineRight(heroStory);
        infoH.target = INFO_NAME + INFO_META + INFO_PAD + (heroStory == null ? 0 : 20 + heroStory.getHeight() / k);
    }

    // MARK: - Billboard actions

    @Override public void billboardPlay(Library.Title t) {
        if (!t.series) {
            host.play(Collections.singletonList(t), 0, null, false);
            return;
        }
        library.episodes(t.id, list -> {
            int[] next = Progress.nextEpisode(getContext(), list);
            if (next[0] < 0) details.open(t, t.poster);
            else host.play(list, next[0], t, false);
        });
    }

    @Override public void billboardInfo(Library.Title t) {
        details.open(t, t.poster);
        kickNow();
    }

    @Override public void billboardList(Library.Title t) {
        boolean added = CinemaPrefs.toggleMyList(getContext(), t);
        toast(added ? Ui.tr("Adicionado à Minha lista", "Added to My List", "Agregado a Mi lista")
                : Ui.tr("Removido da Minha lista", "Removed from My List", "Quitado de Mi lista"));
        rebuildHome();
    }

    @Override public void billboardChanged(Library.Title t) {
        art.show(t, t.poster);
        library.backdrop(t, artArrived);       // its logo, when TMDB has one
        if (focusRow == BILLBOARD) settleSoon();
        kickNow();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (account.isOpen()) {
            lastKeyAt = SystemClock.uptimeMillis();
            return account.onKey(e);
        }
        if (profiles.isOpen()) {
            lastKeyAt = SystemClock.uptimeMillis();
            return profiles.onKey(e);
        }
        // Full-screen preview: a key only brings the page back.
        boolean immersed = chrome(SystemClock.uptimeMillis()) < 1;
        lastKeyAt = SystemClock.uptimeMillis();
        scheduleImmerse();
        if (immersed) {
            // OK plays it on from here; any other key brings the page back (and the preview into its tile).
            if (isOk(e.getKeyCode()) && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) playPreviewed();
            else placePreview(false);
            kickNow();
            return e.getKeyCode() != KeyEvent.KEYCODE_HOME;
        }
        if (details.isOpen()) return details.onKey(e);
        int code = e.getKeyCode();
        if (isOk(code) && !inNav && focusRow != BILLBOARD && page != P_SETTINGS && page != P_SEARCH) return okKey(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isOurs(code);
        if (inNav) return navKey(code);
        if (page == P_SEARCH) {
            if (!search.onKey(e) && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_BACK)) openNav();
            invalidate();
            return true;
        }
        if (page == P_SETTINGS) {
            if (settings.onKey(code)) {
                invalidate();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_BACK) openNav();
            return true;
        }
        if (focusRow == BILLBOARD) return billboardKey(code);
        List<Line> lines = lines();
        Line l = focusRow < lines.size() ? lines.get(focusRow) : null;
        switch (Ui.dpad(code)) {      // right to left: ← goes on along the row
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (l != null && l.focus > 0) moveTo(focusRow, l.focus - 1);       // at the first card: stays
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (l != null && l.focus < l.cells.size() - 1) moveTo(focusRow, l.focus + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusRow > 0) moveTo(focusRow - 1, lines.get(focusRow - 1).focus);
                else if (hasBillboard()) moveTo(BILLBOARD, 0);
                else openNav();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusRow < lines.size() - 1) moveTo(focusRow + 1, lines.get(focusRow + 1).focus);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (hasBillboard()) moveTo(BILLBOARD, 0);
                else if (focusRow > 0) moveTo(0, 0);
                else openNav();
                return true;
            default:
                return false;
        }
    }

    private boolean billboardKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BACK:
                openNav();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                List<Line> lines = lines();
                if (!lines.isEmpty()) moveTo(0, lines.get(0).focus);
                return true;
            default:
                boolean ours = billboard.onKey(code);
                kickNow();
                return ours;
        }
    }

    /**
     * OK opens on release; held, it adds the title to My List (or takes it out), and on a Continue watching card it
     * takes the card out of the row instead (like Netflix's "Remove from row").
     */
    private boolean okKey(KeyEvent e) {
        CinemaCards.Card c = focusedCard();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                okDown = true;
                longPressed = false;
            } else if (okDown && !longPressed && e.getRepeatCount() >= LONG_PRESS_REPEATS && c != null) {
                longPressed = true;
                if (c.progress != null) {
                    Progress.hide(getContext(), c.progress);
                    toast(Ui.tr("Removido de Continuar assistindo", "Removed from Continue watching", "Quitado de Seguir viendo"));
                } else {
                    boolean added = CinemaPrefs.toggleMyList(getContext(), c.title);
                    toast(added ? Ui.tr("Adicionado à Minha lista", "Added to My List", "Agregado a Mi lista")
                            : Ui.tr("Removido da Minha lista", "Removed from My List", "Quitado de Mi lista"));
                }
                rebuildHome();
            }
            return true;
        }
        if (okDown && !longPressed) open(c);
        okDown = false;
        return true;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isOurs(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    /** Back from a title's page: the billboard's or the focused card's picture and preview again. */
    void detailsClosed() {
        refreshRecommendations();       // a rating there changes them
        if (focusRow == BILLBOARD && billboard.current() != null) art.show(billboard.current(), billboard.current().poster);
        settleSoon();
    }

    /** The preview started showing under the view (or stopped): the picture fades out (or back). */
    void onPreview(boolean showing) {
        previewAt = showing ? SystemClock.uptimeMillis() : 0;
        handler.removeCallbacks(subtitleTick);
        previewText.set(null);
        if (showing) {
            // The settings may have changed meanwhile; in a tile, a little smaller.
            if (details.isOpen() || focusRow == BILLBOARD) previewText.configure(k, 1100 * k);
            else previewText.configure(k * 0.75f, (CinemaCards.TILE_W - 60) * k);
            handler.post(subtitleTick);
        }
        scheduleImmerse();
        kickNow();
    }

    private void scheduleImmerse() {
        handler.removeCallbacks(immerse);
        if (previewAt != 0) {
            handler.postDelayed(immerse, Math.max(previewAt, lastKeyAt) + IMMERSE_MS - SystemClock.uptimeMillis());
        }
    }

    /** 1 while the page shows; down to 0 once a full-screen preview has played IMMERSE_MS untouched. */
    private float chrome(long up) {
        if (previewAt == 0) return 1;
        long since = up - Math.max(previewAt, lastKeyAt) - IMMERSE_MS;
        return since <= 0 ? 1 : 1 - Ui.clamp(since / (float) IMMERSE_FADE_MS);
    }

    void toast(String text) {
        toast = text;
        toastAt = SystemClock.uptimeMillis();
        kickNow();
    }

    /** The top bar: ← → between the pages, ↓ or OK goes into the one picked. */
    private boolean navKey(int code) {
        switch (Ui.dpad(code)) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                navFocus = navFocus == NAV_SPARK ? P_SEARCH : navFocus == P_HOME ? NAV_SPARK : Math.max(0, navFocus - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                navFocus = navFocus == P_SEARCH ? NAV_SPARK : navFocus == NAV_SPARK ? P_HOME : Math.min(NAV_PROFILE, navFocus + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (navFocus == NAV_PROFILE) {
                    profiles.openMenu();
                    break;
                }
                if (navFocus == NAV_SPARK) {
                    getContext().startActivity(TonightData.launch(getContext()));
                    break;
                }
                inNav = false;
                if (navFocus != page || navFocus == P_LIST) openPage(navFocus);
                else settleSoon();
                break;
            case KeyEvent.KEYCODE_BACK:
                host.exit();
                return true;
            default:
                return false;
        }
        kickNow();
        return true;
    }

    private void openNav() {
        inNav = true;
        navFocus = page;
        host.preview(null, null);
        kickNow();
    }

    /** OK while a preview plays: the player, on from the second the preview is at (CinemaActivity.play). */
    private void playPreviewed() {
        if (details.isOpen()) return;
        if (focusRow == BILLBOARD) {
            if (billboard.current() != null) billboardPlay(billboard.current());
            return;
        }
        CinemaCards.Card c = focusedCard();
        if (c == null) return;
        if (c.progress != null) open(c);
        else billboardPlay(c.title);
    }

    private void open(CinemaCards.Card c) {
        if (c == null) return;
        if (c.progress == null) {
            // Playing in its tile: straight to the player; else its page, as on Netflix.
            if (previewAt != 0) playPreviewed();
            else details.open(c.title, c.poster);
            return;
        }
        final Library.Title t = c.title;
        if (!t.series || t.rootId.isEmpty()) {
            host.play(Collections.singletonList(t), 0, null, false);
            return;
        }
        // An episode: the whole series as the playlist, from this one.
        final Progress.Entry p = c.progress;
        library.episodes(t.rootId, list -> {
            int at = 0;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(t.id)) at = i;
            Library.Title root = new Library.Title();
            root.id = t.rootId;
            root.title = p.rootTitle;
            root.poster = p.rootPoster;
            root.series = true;
            if (list.isEmpty()) host.play(Collections.singletonList(t), 0, root, false);
            else host.play(list, at, root, false);
        });
    }

    // MARK: - Size and paints

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        art.size(w, h);
        titleFont = Ui.text(Ui.displaySemibold, 84 * k, 0xFFFFFFFF);
        titleText = new android.text.TextPaint(titleFont);
        metaFont = Ui.text(Ui.medium, 28 * k, 0xE6FFFFFF);
        bodyFont = Ui.text(Ui.regular, 29 * k, 0xC2FFFFFF);
        bodyText = new android.text.TextPaint(bodyFont);
        labelFont = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        labelFont.setLetterSpacing(0.18f);
        otherFont = Ui.text(Ui.medium, 30 * k, 0xA6FFFFFF);
        rowFont = Ui.text(Ui.semibold, 32 * k, 0xFFE5E5E5);
        navFont = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        markFont = Ui.text(Ui.displaySemibold, 30 * k, 0xFFFFFFFF);
        markFont.setTextAlign(Paint.Align.CENTER);
        markFont.setFakeBoldText(true);
        statusFont = Ui.text(Ui.medium, 30 * k, 0xB3FFFFFF);
        statusFont.setTextAlign(Paint.Align.CENTER);
        buttonFont = Ui.text(Ui.semibold, 29 * k, 0xFFFFFFFF);
        navShade = new LinearGradient(0, 0, 0, 230 * k, 0xD9000000, 0, Shader.TileMode.CLAMP);
        float shadeAt = CinemaBillboard.HEIGHT - BILLBOARD_OVERLAP;
        rowsShade = new LinearGradient(0, (shadeAt - 200) * k, 0, (shadeAt + 20) * k, 0, BACKGROUND, Shader.TileMode.CLAMP);
        tileNameFont = Ui.text(Ui.semibold, 38 * k, 0xFFFFFFFF);
        // Dark under the tile and its words: on the left, or on the right in right to left.
        rowsLeft = new LinearGradient(Ui.rtl ? w : 0, 0, Ui.rtl ? 0 : w, 0,
                new int[] {BACKGROUND, BACKGROUND, 0xA6141414, 0x73141414}, new float[] {0, 0.4f, 0.7f, 1}, Shader.TileMode.CLAMP);
        rowsBottom = new LinearGradient(0, h * 0.4f, 0, h * 0.85f, 0, BACKGROUND, Shader.TileMode.CLAMP);
        cards.sizeChanged(k);
        billboard.sizeChanged(k);
        for (List<Line> lines : pages) {
            if (lines == null) continue;
            for (Line l : lines) for (CinemaCards.Card c : l.cells) c.thumbKey = c.label = null;
        }
        heroCard = null;
        details.sizeChanged();
        settings.sizeChanged();
        search.sizeChanged();
        profiles.sizeChanged();
        account.sizeChanged();
    }

    // MARK: - Frames

    void kick() {
        if (!running || framePosted) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        framePosted = false;
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        // The bar shows at the top of a page and in it; it fades away while the rows below are browsed.
        boolean top = hasBillboard() ? rowPos.target < billboardPos() / 2 : focusRow <= 0;
        navShown.target = !details.isOpen() && (inNav || page == P_SEARCH || page == P_SETTINGS || top) ? 1 : 0;
        boolean moving = rowPos.step(dt) | navShown.step(dt) | infoH.step(dt) | details.step(dt);
        List<Line> lines = lines();
        for (int r = 0; r < lines.size(); r++) {
            Line l = lines.get(r);
            moving |= l.scrollX.step(dt);
            // The focused card grows into the tile, every other one goes back to a card, each from where it is.
            float most = 0;
            for (int i = 0; i < l.cells.size(); i++) {
                CinemaCards.Card card = l.cells.get(i);
                card.grow.target = r == focusRow && i == l.focus ? 1 : 0;
                moving |= card.grow.step(dt);
                most = Math.max(most, card.grow.value);
            }
            l.grown = Ui.clamp(most);
        }
        long up = SystemClock.uptimeMillis();
        moving |= pageLoading[page] || (lines().isEmpty() && page <= P_SERIES && page != P_SEARCH && !homeFailed);
        moving |= up - toastAt < TOAST_MS + 300 || up - heroAt < TEXT_FADE_MS || previewAt != 0 && up - previewAt < PREVIEW_FADE_MS;
        float ch = chrome(up);
        moving |= ch > 0 && ch < 1;
        invalidate();
        if (moving) kick();
        else lastNanos = 0;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (titleFont == null) return;
        drawContent(c);
        if (profiles.isOpen()) profiles.draw(c);
        if (account.isOpen()) account.draw(c);
    }

    private void drawContent(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float ch = chrome(up);
        if (details.isOpen() || page == P_SEARCH || page == P_SETTINGS) {
            if (art.draw(c, previewAt, ch)) kick();
        }
        if (ch <= 0) {
            drawImmersedTitle(c);
            drawPreviewSubtitle(c, ch);
            return;
        }
        // Fading out (a short moment): the page through one layer.
        int layer = ch < 1 ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * ch)) : -1;
        drawPage(c);
        if (layer >= 0) c.restoreToCount(layer);
        drawPreviewSubtitle(c, ch);
    }

    /** The preview's subtitle: in the focused card, by the billboard's picture, or at the bottom once full screen. */
    private void drawPreviewSubtitle(Canvas c, float chrome) {
        if (previewAt == 0 || details.covered()) return;
        if (chrome < 0.5f) previewText.draw(c, w / 2f, 990 * k);
        else if (details.isOpen()) previewText.draw(c, Ui.rtl ? w * 0.33f : w * 0.67f, 560 * k);
        else if (focusRow == BILLBOARD) previewText.draw(c, Ui.rtl ? w * 0.33f : w * 0.67f, 600 * k);
        else previewText.draw(c, Ui.rtl ? w - (L + CinemaCards.TILE_W / 2f) * k : (L + CinemaCards.TILE_W / 2f) * k,
                (ROW_Y + CinemaCards.TILE_H - 22) * k);
    }

    /** Full-screen preview: only the title, small, bottom left (like Netflix). */
    private void drawImmersedTitle(Canvas c) {
        Library.Title t = details.isOpen() ? null : focusRow == BILLBOARD ? billboard.current() : heroCard == null ? null : heroCard.title;
        String name = details.isOpen() ? details.titleName() : t == null ? "" : t.title;
        rowFont.setAlpha(220);
        Ui.text(c, name, 96 * k, h - 80 * k, rowFont, w);
        rowFont.setAlpha(255);
    }

    private void drawPage(Canvas c) {
        if (details.isOpen()) {
            details.draw(c);
            drawToast(c);
            return;
        }
        if (page == P_SETTINGS || page == P_SEARCH) {
            fill.setColor(0xF00B0B0D);
            c.drawRect(0, 0, w, h, fill);
            if (page == P_SETTINGS) settings.draw(c);
            else search.draw(c);
            drawNav(c);
            drawToast(c);
            return;
        }
        drawTop(c);
        drawLines(c);
        drawStatus(c);
        drawNav(c);
        drawToast(c);
    }

    /**
     * The top of the screen: on the billboard, its picture (its preview on the whole screen behind) and words; in the
     * rows, the focused title's picture on the right, dimmed and fading into the dark on the left (under the tile and
     * its words) and at the bottom (under the next rows); the billboard fading out as they come up.
     */
    private void drawTop(Canvas c) {
        float b = billboardShown();
        // In the rows the preview plays in the tile, so the picture stays.
        if (art.draw(c, b > 0.5f ? previewAt : 0, 1)) kick();
        if (b > 0.01f) {
            int layer = b < 1 ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * b)) : -1;
            billboard.draw(c, 0, focusRow == BILLBOARD && !inNav);
            if (layer >= 0) c.restoreToCount(layer);
            fill.setShader(rowsShade);
            c.drawRect(0, (CinemaBillboard.HEIGHT - BILLBOARD_OVERLAP - 200) * k, w, h, fill);
            fill.setShader(null);
        }
        if (b < 0.99f) {
            int a = Math.round(255 * (1 - b));
            fill.setAlpha(a);
            fill.setShader(rowsLeft);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(rowsBottom);
            c.drawRect(0, h * 0.4f, w, h, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
    }

    private void drawLines(Canvas c) {
        List<Line> lines = lines();
        float pos = rowPos.value, y = ROW_Y - pos * ROW_STEP;
        for (int i = 0; i < lines.size(); i++) {
            float top = y * k;
            if (top > h) break;
            // Each row sits under the one above as it is now (its tile grown or not), so they never overlap.
            y += ROW_STEP + lines.get(i).grown * tileExtra();
            // The rows above the focused one slide up and fade away, as on Netflix's TV app.
            float alpha = Ui.clamp(1 + (i - pos) * 1.8f);
            if (alpha > 0.01f) drawLine(c, lines.get(i), i, top, alpha);
        }
    }

    /**
     * A row. Its focused card grows into a tile, wider and taller, the cards after it moving along to make room, and
     * the cards that leave on the left fade out like the rows above. The preview plays inside the tile (the view
     * leaves a hole there for the video under it) and the title's words go under it.
     */
    private void drawLine(Canvas c, Line l, int index, float top, float alpha) {
        if (!l.title.isEmpty()) {
            rowFont.setAlpha(Math.round(255 * alpha));
            Ui.text(c, l.title, L * k, top - 22 * k, rowFont, w);
        }
        float pw = CinemaCards.POSTER_W * k, ph = CinemaCards.POSTER_H * k, step = cardStep(l) * k;
        if (l.cells.isEmpty()) {
            // Still loading: grey posters where they will be.
            fill.setColor(Ui.alpha(PLACEHOLDER, alpha));
            for (float x = L * k; x < w; x += step) {
                float left = Ui.mx(x, pw, w);
                rect.set(left, top, left + pw, top + ph);
                c.drawRoundRect(rect, RADIUS * k, RADIUS * k, fill);
            }
            return;
        }
        float extraW = (CinemaCards.TILE_W + GAP) * k - step;
        float scroll = l.scrollX.value * k, shift = 0;
        for (int i = 0; i < l.cells.size(); i++) {
            CinemaCards.Card card = l.cells.get(i);
            float g = Ui.clamp(card.grow.value), x = L * k + i * step - scroll + shift;
            shift += extraW * g;
            if (x > w) break;
            float a = alpha * Ui.clamp(1 - (L * k - x) / (step * 0.6f));
            if (a <= 0.01f) continue;
            boolean focused = index == focusRow && i == l.focus;
            cards.place(card, x, top, g, tile);
            if (Ui.rtl) tile.set(w - tile.right, tile.top, w - tile.left, tile.bottom);     // from the right edge
            float picture = 1;
            if (focused && previewAt != 0 && !details.isOpen()) {
                // The video under the view shows through: the picture fades away over it.
                c.drawRoundRect(tile, RADIUS * k, RADIUS * k, clear);
                picture = 1 - Ui.clamp((SystemClock.uptimeMillis() - previewAt) / (float) PREVIEW_FADE_MS);
            }
            cards.drawTile(c, card, tile, g, a, picture, focused && !inNav);
            // The same title (its card may have been made again since, as rows are when coming back to them).
            if (heroCard != null && g > 0.01f && card.title.id.equals(heroCard.title.id)) {
                drawTileInfo(c, Ui.rtl ? w - tile.right : tile.left, tile.bottom, a * g);
            }
        }
    }

    /** Under the tile, as on Netflix: match · year · length · rating, then the story (x: from the start edge). */
    private void drawTileInfo(Canvas c, float x, float bottom, float shown) {
        float a = shown * Ui.clamp((SystemClock.uptimeMillis() - heroAt) / (float) TEXT_FADE_MS);
        if (a <= 0.01f) return;
        float y = bottom + INFO_NAME * k;
        tileNameFont.setAlpha(Math.round(255 * a));
        Ui.text(c, heroTileName, x, y, tileNameFont, w);
        y += INFO_META * k;
        float fx = x;
        metaFont.setAlpha(Math.round(200 * a));
        if (!heroFacts.isEmpty()) {
            Ui.text(c, heroFacts, fx, y, metaFont, w);
            fx += metaFont.measureText(heroFacts) + 22 * k;
        }
        if (!heroRating.isEmpty()) {
            float tw = metaFont.measureText(heroRating), cap = Ui.cap(metaFont);
            float left = Ui.mx(fx, tw + 18 * k, w);
            rect.set(left, y - cap - 8 * k, left + tw + 18 * k, y + 8 * k);
            stroke.setStrokeWidth(1.5f * k);
            stroke.setColor(Ui.alpha(0x99FFFFFF, a));
            c.drawRoundRect(rect, 4 * k, 4 * k, stroke);
            Ui.text(c, heroRating, fx + 9 * k, y, metaFont, w);
        }
        metaFont.setAlpha(230);
        if (heroStory != null) {
            bodyText.setAlpha(Math.round(194 * a));
            c.save();
            c.translate(Ui.layoutX(x, heroStoryRight, w), y + 20 * k);
            heroStory.draw(c);
            c.restore();
            bodyText.setAlpha(194);
        }
    }

    private void drawToast(Canvas c) {
        long age = SystemClock.uptimeMillis() - toastAt;
        if (toast.isEmpty() || age > TOAST_MS + 300) return;
        float a = age < TOAST_MS ? 1 : 1 - (age - TOAST_MS) / 300f;
        float tw = statusFont.measureText(toast) + 80 * k;
        rect.set((w - tw) / 2, 60 * k, (w + tw) / 2, 136 * k);
        fill.setColor(Ui.alpha(0xF01A1A1F, a));
        c.drawRoundRect(rect, 38 * k, 38 * k, fill);
        statusFont.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawText(toast, w / 2f, rect.centerY() + Ui.halfCap(statusFont), statusFont);
        statusFont.setColor(0xB3FFFFFF);
    }

    /** The red bar and the kind (FILM, SERIES), over the title. */
    void drawLabel(Canvas c, String label, float x, float base) {
        fill.setColor(RED);
        float cap = Ui.cap(labelFont);
        rect.set(x, base - cap, x + 6 * k, base);
        c.drawRect(rect, fill);
        c.drawText(label, x + 18 * k, base, labelFont);
    }

    /** A red bar along the bottom of the card: how much was watched. */
    void drawProgress(Canvas c, RectF card, float fraction, float alpha) {
        float inset = 14 * k, bh = 5 * k, y = card.bottom - inset - bh;
        bar.set(card.left + inset, y, card.right - inset, y + bh);
        fill.setColor(Ui.alpha(0x66FFFFFF, alpha));
        c.drawRoundRect(bar, bh / 2, bh / 2, fill);
        if (Ui.rtl) bar.left = bar.right - Math.max(bh, bar.width() * fraction);     // right to left: fills from the right
        else bar.right = bar.left + Math.max(bh, bar.width() * fraction);
        fill.setColor(Ui.alpha(RED, alpha));
        c.drawRoundRect(bar, bh / 2, bh / 2, fill);
    }

    private void drawStatus(Canvas c) {
        if (!lines().isEmpty()) return;
        float cx = w / 2f, cy = h * 0.8f;
        if (pageLoading[page] || (page != P_LIST && !homeFailed)) {
            stroke.setStrokeWidth(5 * k);
            stroke.setColor(0xCCFFFFFF);
            Ui.spinner(c, cx, cy, 30 * k, SystemClock.uptimeMillis() / 1000f, stroke);
            return;
        }
        if (page == P_LIST) {
            c.drawText(Ui.tr("Segure OK num título para adicioná-lo aqui", "Hold OK on a title to add it here",
                    "Mantén OK en un título para agregarlo aquí"), cx, h * 0.5f, statusFont);
            return;
        }
        c.drawText(Ui.tr("O catálogo não está respondendo. Tentando de novo…", "The catalog isn't answering. Trying again…",
                "El catálogo no responde. Intentando de nuevo…"), cx, cy, statusFont);
    }

    /** The top bar: the mark, a lens for Search, the pages by name, a gear for Settings at the right. */
    private void drawNav(Canvas c) {
        float a = Ui.clamp(navShown.value);
        if (a <= 0.01f) return;
        fill.setShader(navShade);
        fill.setAlpha(Math.round(255 * a));
        c.drawRect(0, 0, w, 230 * k, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        float y = NAV_Y * k, x = L * k;
        drawMark(c, Ui.mx(x + 26 * k, 0, w), y, 26 * k);
        x += 100 * k;
        String[] names = {"", Ui.tr("Início", "Home", "Inicio"), Ui.tr("Filmes", "Movies", "Películas"),
                Ui.tr("Séries", "Series", "Series", "مسلسلات"), Ui.tr("Novidades", "New & Popular", "Novedades"),
                Ui.tr("Minha lista", "My List", "Mi lista"), ""};
        for (int i = 0; i <= P_SETTINGS; i++) {
            boolean icon = i == P_SEARCH || i == P_SETTINGS;
            float itemW = icon ? 64 * k : navFont.measureText(names[i]) + 44 * k;
            float left = i == P_SETTINGS ? (1920 - 96 - 84) * k - itemW : x;
            boolean focused = inNav && i == navFocus, current = i == page;
            float at = Ui.mx(left, itemW, w);      // right to left: the bar runs from the right
            rect.set(at, y - 30 * k, at + itemW, y + 30 * k);
            if (focused) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, a));
                c.drawRoundRect(rect, 30 * k, 30 * k, fill);
            } else if (current && !details.isOpen()) {
                fill.setColor(Ui.alpha(RED, a));
                c.drawRect(rect.centerX() - 18 * k, y + 26 * k, rect.centerX() + 18 * k, y + 30 * k, fill);
            }
            int ink = Ui.alpha(focused ? 0xFF0B0B0D : current ? 0xFFFFFFFF : 0xA6FFFFFF, a);
            if (i == P_SEARCH) {
                drawSearchIcon(c, rect.centerX(), y, ink);
            } else if (i == P_SETTINGS) {
                Symbols.draw(getContext(), c, "settings", rect.centerX(), y, 34 * k, ink);
            } else {
                navFont.setColor(ink);
                c.drawText(names[i], at + 22 * k, y + Ui.halfCap(navFont), navFont);
            }
            if (i != P_SETTINGS) x += itemW + 12 * k;
            if (i == P_SEARCH) {
                drawSparkItem(c, x, y, a);
                x += 64 * k + 12 * k;
            }
        }
        navFont.setColor(0xFFFFFFFF);
        drawProfileTab(c, a);
    }

    /** The active profile's avatar at the end of the bar; focused, a white ring. */
    private void drawProfileTab(Canvas c, float a) {
        ProfileRules.Profile p = Profiles.active(getContext());
        if (p == null) return;
        float cx = Ui.mx((1920 - 96 - 26) * k, 0, w), cy = NAV_Y * k;
        int layer = a < 1 ? c.saveLayerAlpha(cx - 40 * k, cy - 40 * k, cx + 40 * k, cy + 40 * k, Math.round(255 * a)) : -1;
        Avatars.draw(c, p.avatar, p.color, cx, cy, 24 * k, inNav && navFocus == NAV_PROFILE);
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** Watchme+'s mark (as on its icon): a red rounded square with a white W and a small plus. */
    private void drawMark(Canvas c, float cx, float cy, float r) {
        rect.set(cx - r, cy - r, cx + r, cy + r);
        fill.setColor(RED);
        c.drawRoundRect(rect, r * 0.4f, r * 0.4f, fill);
        markFont.setTextSize(r * 1.16f);
        c.drawText("W", cx - r * 0.12f, cy + Ui.halfCap(markFont), markFont);
        fill.setColor(0xFFFFFFFF);
        float px = cx + r * 0.52f, py = cy - r * 0.48f, p = r * 0.2f, t = r * 0.076f;
        c.drawRect(px - p, py - t, px + p, py + t, fill);
        c.drawRect(px - t, py - p, px + t, py + p, fill);
    }

    /** "+", as on Netflix's My List. */
    void drawPlus(Canvas c, float cx, float cy, float r, int ink) {
        stroke.setColor(ink);
        stroke.setStrokeWidth(4 * k);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx - r, cy, cx + r, cy, stroke);
        c.drawLine(cx, cy - r, cx, cy + r, stroke);
    }

    /** Spark's entry: a sparkle (AI) beside the lens, same size and focus pill, red until focused. */
    private void drawSparkItem(Canvas c, float x, float y, float a) {
        boolean focused = inNav && navFocus == NAV_SPARK;
        float at = Ui.mx(x, 64 * k, w);
        rect.set(at, y - 30 * k, at + 64 * k, y + 30 * k);
        if (focused) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawRoundRect(rect, 30 * k, 30 * k, fill);
        }
        fill.setColor(Ui.alpha(focused ? 0xFF0B0B0D : RED, a));
        float cx = rect.centerX() - 3 * k, cy = y + 3 * k;
        drawStar(c, cx, cy, 14 * k);
        drawStar(c, cx + 11 * k, cy - 13 * k, 6.5f * k);
    }

    /** A four-pointed star with hollow sides (reuses one Path). */
    private void drawStar(Canvas c, float cx, float cy, float r) {
        float in = r * 0.18f;
        sparkle.reset();
        sparkle.moveTo(cx, cy - r);
        sparkle.quadTo(cx + in, cy - in, cx + r, cy);
        sparkle.quadTo(cx + in, cy + in, cx, cy + r);
        sparkle.quadTo(cx - in, cy + in, cx - r, cy);
        sparkle.quadTo(cx - in, cy - in, cx, cy - r);
        sparkle.close();
        c.drawPath(sparkle, fill);
    }

    private void drawSearchIcon(Canvas c, float cx, float cy, int ink) {
        stroke.setColor(ink);
        stroke.setStrokeWidth(4 * k);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float r = 11 * k;
        c.drawCircle(cx - 4 * k, cy - 4 * k, r, stroke);
        c.drawLine(cx + 4 * k, cy + 4 * k, cx + 14 * k, cy + 14 * k, stroke);
    }
}
