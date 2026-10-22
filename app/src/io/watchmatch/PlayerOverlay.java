package io.watchmatch;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What's drawn over the video, Netflix-style. A key shows the controls for a few seconds: the title, the time bar
 * (← → skip, held: faster; OK pauses) and, a step down, buttons for Audio & Subtitles, Quality, Episodes and the next
 * episode, each opening a panel on the right. Paused for a while, "You're watching" with the story. Also the subtitles
 * (size, style and color from CinemaPrefs), "Skip intro" / "Next episode" ready for OK, and the spinner.
 * 1920×1080 space. Right to left, the words, buttons and panels mirror; the time bar and ← → seeking stay as they are
 * (media timelines aren't mirrored).
 */
final class PlayerOverlay extends View {
    private static final long SHOW_MS = 4500, FADE_MS = 220, TOAST_MS = 3500, TICK_MS = 200, PAUSE_INFO_MS = 5000;
    /** The loader shows only past LOADER_WAIT_MS (short waits don't flash it); the title's art fades out over ART_FADE_MS. */
    private static final long LOADER_WAIT_MS = 250, ART_FADE_MS = 450;
    private long loadingSince, renderedAt, lastDrawAt;
    private float loaderLevel;
    /** Something was on screen at the last tick: one more paint clears it once it's gone (no faint leftovers). */
    private boolean shownLast;
    private static final float M = 96, BAR_Y = 936, BUTTONS_Y = 1000, PANEL_W = 700, ROW_H = 84;
    private static final int PANEL_ROWS = 8;
    private static final String[] LANGUAGES = {"ar", "en", "off"};
    private static final int F_BAR = 0, F_BUTTONS = 1;
    private static final int B_AUDIO = 0, B_QUALITY = 1, B_EPISODES = 2, B_NEXT = 3, B_SPEED = 4, B_FAMILY = 5;
    /** The Auto row of the quality panel (the other rows are file numbers). */
    private static final int AUTO_ROW = -1;
    private static final int P_NONE = 0, P_AUDIO = 1, P_QUALITY = 2, P_EPISODES = 3, P_SPEED = 4;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f};
    /** The "Next episode" button fills over this long at the credits, then the next one plays (Back: watch credits). */
    private static final long NEXT_COUNTDOWN_MS = NextUpRules.COUNTDOWN_AT_END_MS;
    private static final int R_AUDIO = 0, R_SUBTITLES = 1, R_SIZE = 2, R_STYLE = 3, R_COLOR = 4, R_TIMING = 5;

    private final PlayerActivity player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long visibleUntil, toastAt, pausedAt, nextUpSince;
    private boolean creditsChosen;
    private boolean loading;
    private int focusArea = F_BAR, buttonFocus, panel = P_NONE, panelRow, panelTop;
    private String message, toast = "", shownButton, status;
    /** The final message offers OK to try again. */
    private boolean retryable;
    /** The number under the spinner, formatted only when it changes (nothing is built while drawing). */
    private String percentText;
    private int percentShown = -1;
    private final Runnable percentTick = new Runnable() {
        @Override public void run() {
            if (!loading) return;
            int p = player.loadPercent();
            if (p != percentShown) {
                percentShown = p;
                percentText = Ui.digits(p + "%");
                invalidate();
            }
            handler.postDelayed(this, LoadPercent.TICK_MS);
        }
    };
    private StaticLayout pauseStory;
    private float pauseStoryRight;
    private final SubtitleText subtitle;
    private final List<Integer> buttons = new ArrayList<>(), panelRows = new ArrayList<>();
    private final List<String> buttonNames = new ArrayList<>();

    private float k = 1;
    private int w, h;
    private Paint titleFont, metaFont, timeFont, buttonFont, rowFont, valueFont, headerFont, messageFont, bigFont, percentFont;
    private TextPaint storyText;
    private Shader bottomShade, pauseShade;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private String artKey, artSource;
    private static final int ART_W = 1280, ART_H = 720;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long up = SystemClock.uptimeMillis();
            boolean changed = updateSubtitle();
            boolean paused = !player.playing() && !loading && message == null;
            if (paused && pausedAt == 0) pausedAt = up;
            if (!paused) pausedAt = 0;
            boolean button = player.introEnd() >= 0 || player.nextUp() || shownButton != null;
            countdown(up);
            boolean shown = changed || button || controlsAlpha(up) > 0 || loading || loaderLevel > 0 || pausedAt != 0
                    || up - toastAt < TOAST_MS + FADE_MS || !player.rendered() || renderedAt == 0 || up - renderedAt < ART_FADE_MS;
            if (shown || shownLast) invalidate();
            shownLast = shown;
            if (controlsAlpha(up) <= 0 && panel == P_NONE) focusArea = F_BAR;
            handler.postDelayed(this, TICK_MS);
        }
    };

    PlayerOverlay(PlayerActivity a) {
        super(a);
        player = a;
        subtitle = new SubtitleText(a);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        handler.post(tick);
    }

    void destroy() {
        handler.removeCallbacks(percentTick);
        handler.removeCallbacks(tick);
    }

    void show() {
        visibleUntil = SystemClock.uptimeMillis() + SHOW_MS;
        pausedAt = player.playing() ? 0 : SystemClock.uptimeMillis();
        invalidate();
    }

    void loading(boolean on) {
        boolean began = on && !loading;
        loading = on;
        if (on) message = null;
        if (began) {
            player.loadingStarted();
            percentShown = -1;
            percentText = Ui.digits("0%");
            handler.removeCallbacks(percentTick);
            handler.postDelayed(percentTick, LoadPercent.TICK_MS);
        }
        if (!on) handler.removeCallbacks(percentTick);
        if (!on) {
            status = null;
            storyChanged();
        }
        invalidate();
    }

    void message(String text) {
        message = text;
        retryable = false;
        loading = false;
        status = null;
        invalidate();
    }

    /** The final failure message, with OK to try again and Back to leave. */
    void messageRetry(String text) {
        message(text);
        retryable = true;
    }

    /** The spinner with a line under it ("Reconnecting…") while the player reopens the video. */
    void reconnecting(String text) {
        loading(true);
        status = text;
        invalidate();
    }

    void toast(String text) {
        toast = text;
        toastAt = SystemClock.uptimeMillis();
        invalidate();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (!handles(code)) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (message != null) {
            if (code == KeyEvent.KEYCODE_BACK) player.finish();
            else if (isOk(code)) {
                if (retryable) player.retry();
                else player.finish();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && panel == P_NONE && player.nextUp() && !creditsChosen && player.introEnd() < 0) {
            creditsChosen = true;       // the card is dismissed (or the end's countdown cancelled): no autoplay for this episode
            invalidate();
            return true;
        }
        if (panel != P_NONE) panelKey(Ui.dpad(code));       // right to left: the panels and buttons are mirrored
        else if (mediaKey(code)) show();
        else if (focusArea == F_BUTTONS) buttonsKey(Ui.dpad(code));
        else barKey(code, e.getRepeatCount());
        invalidate();
        return true;
    }

    private boolean mediaKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!player.playing()) player.toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player.playing()) player.toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                player.toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                player.next();
                return true;
            case KeyEvent.KEYCODE_MENU:
                openPanel(P_AUDIO);
                return true;
            default:
                return false;
        }
    }

    /** The time bar: ← → skip, OK pauses (or takes the Skip intro / Next episode button), ↓ the buttons. */
    private void barKey(int code, int repeat) {
        long step = repeat < 4 ? 10_000 : repeat < 12 ? 30_000 : 60_000;
        boolean visible = controlsAlpha(SystemClock.uptimeMillis()) > 0.5f;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                player.skip(-step);
                return;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                player.skip(step);
                return;
            case KeyEvent.KEYCODE_DPAD_UP:
                openPanel(P_AUDIO);
                return;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (visible) {
                    buildButtons();
                    focusArea = F_BUTTONS;
                    buttonFocus = 0;
                }
                show();
                return;
            case KeyEvent.KEYCODE_BACK:
                player.finish();
                return;
            default:    // OK: the Skip intro / Next episode button first, while it shows
                if (repeat > 0) return;
                long intro = player.introEnd();
                if (intro >= 0) {
                    player.skipTo(intro);
                } else if (player.nextUp()) {
                    player.next();
                } else {
                    player.toggle();
                    show();
                }
        }
    }

    private void buttonsKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                buttonFocus = Math.max(0, buttonFocus - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                buttonFocus = Math.min(buttons.size() - 1, buttonFocus + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BACK:
                focusArea = F_BAR;
                break;
            default:
                if (!isOk(code) || buttonFocus >= buttons.size()) break;
                int b = buttons.get(buttonFocus);
                if (b == B_AUDIO) openPanel(P_AUDIO);
                else if (b == B_QUALITY) openPanel(P_QUALITY);
                else if (b == B_EPISODES) openPanel(P_EPISODES);
                else if (b == B_SPEED) openPanel(P_SPEED);
                else if (b == B_FAMILY) {
                    player.toggleFamily();
                    buildButtons();
                }
                else player.next();
        }
        show();
    }

    private void buildButtons() {
        buttons.clear();
        buttonNames.clear();
        addButton(B_AUDIO, Ui.tr("Áudio e legendas", "Audio & Subtitles", "Audio y subtítulos"));
        addButton(B_QUALITY, Ui.tr("Qualidade", "Quality", "Calidad") + (player.source == null ? "" : "  " + badge(player.source.label)));
        addButton(B_SPEED, Ui.tr("Velocidade", "Speed", "Velocidad") + "  " + speedName(player.speed));
        boolean family = CinemaPrefs.skipScenes(getContext());
        addButton(B_FAMILY, family ? Ui.tr("Modo família: ligado", "Family mode: On", "Modo familia: activado", "وضع العائلة: مفعّل")
                : Ui.tr("Modo família: desligado", "Family mode: Off", "Modo familia: desactivado", "وضع العائلة: متوقف"));
        if (player.isSeries()) addButton(B_EPISODES, Ui.tr("Episódios", "Episodes", "Episodios"));
        if (player.hasNext()) addButton(B_NEXT, Ui.tr("Próximo episódio", "Next episode", "Siguiente episodio"));
    }

    private void addButton(int id, String name) {
        buttons.add(id);
        buttonNames.add(name);
    }

    // MARK: - Panels

    private void openPanel(int p) {
        panel = p;
        panelRow = 0;
        panelTop = 0;
        panelRows.clear();
        if (p == P_AUDIO) {
            if (player.audioTracks.size() > 1) panelRows.add(R_AUDIO);
            panelRows.add(R_SUBTITLES);
            panelRows.add(R_SIZE);
            panelRows.add(R_STYLE);
            panelRows.add(R_COLOR);
            panelRows.add(R_TIMING);
        } else if (p == P_QUALITY) {
            if (player.autoAvailable()) panelRows.add(AUTO_ROW);
            for (int i = player.sources.size() - 1; i >= 0; i--) panelRows.add(i);     // best first
            panelRow = Math.max(0, panelRows.indexOf(player.autoQuality() ? AUTO_ROW : player.sources.indexOf(player.source)));
        } else if (p == P_SPEED) {
            for (int i = 0; i < SPEEDS.length; i++) {
                panelRows.add(i);
                if (SPEEDS[i] == player.speed) panelRow = i;
            }
        } else {
            int season = player.current() == null ? 0 : player.current().season;
            for (int i = 0; i < player.playlist.size(); i++) if (player.playlist.get(i).season == season) panelRows.add(i);
            panelRow = Math.max(0, panelRows.indexOf(player.index));
        }
        keepRowVisible();
        show();
    }

    /** "Auto (480p)": the quality it is at now. */
    private String autoLabel() {
        String now = player.source == null ? "" : badge(player.source.label);
        return Ui.tr("Automática", "Auto", "Automática", "تلقائي") + (now.isEmpty() ? "" : " (" + now + ")");
    }

    private void panelKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                panelRow = Math.max(0, panelRow - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                panelRow = Math.min(panelRows.size() - 1, panelRow + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (panel == P_AUDIO) change(panelRows.get(panelRow), code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) panel = P_NONE;
                break;
            case KeyEvent.KEYCODE_BACK:
                panel = P_NONE;
                break;
            default:
                if (!isOk(code) || panelRows.isEmpty()) break;
                int row = panelRows.get(panelRow);
                if (panel == P_AUDIO) {
                    change(row, 1);
                } else if (panel == P_QUALITY) {
                    panel = P_NONE;
                    if (row == AUTO_ROW) player.setAuto();
                    else player.setQuality(player.sources.get(row));
                } else if (panel == P_SPEED) {
                    panel = P_NONE;
                    player.setSpeed(SPEEDS[row]);
                } else {
                    panel = P_NONE;
                    if (row != player.index) player.play(row);
                }
        }
        keepRowVisible();
        show();
    }

    private void keepRowVisible() {
        if (panelRow < panelTop) panelTop = panelRow;
        if (panelRow >= panelTop + PANEL_ROWS) panelTop = panelRow - PANEL_ROWS + 1;
    }

    private void change(int row, int by) {
        switch (row) {
            case R_AUDIO: {
                int n = player.audioTracks.size();
                if (n > 0) player.setAudio((player.audioChoice + by + n) % n);
                break;
            }
            case R_SUBTITLES: {
                int at = indexOf(player.subtitleLanguage);
                for (int i = 0; i < LANGUAGES.length; i++) {
                    at = (at + by + LANGUAGES.length) % LANGUAGES.length;
                    if (player.hasSubtitles(LANGUAGES[at])) break;
                }
                player.setSubtitles(LANGUAGES[at]);
                break;
            }
            case R_SIZE:
                CinemaPrefs.setSubtitleSize(getContext(), Math.max(0, Math.min(3, CinemaPrefs.subtitleSize(getContext()) + by)));
                break;
            case R_STYLE:
                CinemaPrefs.cycleSubtitleStyle(getContext(), by);
                break;
            case R_COLOR:
                CinemaPrefs.setSubtitleYellow(getContext(), !CinemaPrefs.subtitleYellow(getContext()));
                break;
            case R_TIMING:
                player.subtitleOffset = Math.max(-10_000, Math.min(10_000, player.subtitleOffset + by * 250L));
                break;
            default:
        }
        subtitleStyle();
    }

    private String panelValue(int row) {
        switch (row) {
            case R_AUDIO: return player.audioNames.isEmpty() ? "" : player.audioNames.get(player.audioChoice);
            case R_SUBTITLES: return CinemaPrefs.languageName(player.subtitleLanguage);
            case R_SIZE: return CinemaPrefs.sizeName(CinemaPrefs.subtitleSize(getContext()));
            case R_STYLE: return CinemaPrefs.styleName(CinemaPrefs.subtitleStyle(getContext()));
            case R_COLOR: return CinemaPrefs.subtitleYellow(getContext()) ? Ui.tr("Amarelo", "Yellow", "Amarillo")
                    : Ui.tr("Branco", "White", "Blanco");
            case R_TIMING: return String.format(Locale.US, "%+.2f s", player.subtitleOffset / 1000f);
            default: return "";
        }
    }

    private static String panelLabel(int row) {
        switch (row) {
            case R_AUDIO: return Ui.tr("Áudio", "Audio", "Audio");
            case R_SUBTITLES: return Ui.tr("Legendas", "Subtitles", "Subtítulos");
            case R_SIZE: return Ui.tr("Tamanho", "Size", "Tamaño");
            case R_STYLE: return Ui.tr("Estilo", "Style", "Estilo");
            case R_COLOR: return Ui.tr("Cor", "Color", "Color");
            default: return Ui.tr("Sincronia", "Timing", "Sincronía");
        }
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean handles(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_SPACE:
                return true;
            default:
                return false;
        }
    }

    private static int indexOf(String language) {
        for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(language)) return i;
        return 2;
    }

    // MARK: - Paints

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        titleFont = Ui.text(Ui.displaySemibold, 44 * k, 0xFFFFFFFF);
        bigFont = Ui.text(Ui.displaySemibold, 76 * k, 0xFFFFFFFF);
        percentFont = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
        percentFont.setTextAlign(Paint.Align.CENTER);
        percentFont.setFontFeatureSettings("tnum");
        metaFont = Ui.text(Ui.medium, 28 * k, 0xCCFFFFFF);
        timeFont = Ui.numbers(Ui.medium, 28 * k, 0xE6FFFFFF);
        buttonFont = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        rowFont = Ui.text(Ui.medium, 29 * k, 0xB3FFFFFF);
        valueFont = Ui.text(Ui.semibold, 29 * k, 0xFFFFFFFF);
        valueFont.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);     // right to left: values on the left
        headerFont = Ui.text(Ui.semibold, 24 * k, 0x99FFFFFF);
        headerFont.setLetterSpacing(0.14f);
        messageFont = Ui.text(Ui.medium, 32 * k, 0xFFFFFFFF);
        messageFont.setTextAlign(Paint.Align.CENTER);
        storyText = new TextPaint(Ui.text(Ui.regular, 30 * k, 0xCCFFFFFF));
        bottomShade = new LinearGradient(0, h - 420 * k, 0, h, 0, 0xF2000000, Shader.TileMode.CLAMP);
        pauseShade = new LinearGradient(Ui.rtl ? w : 0, 0, w * (Ui.rtl ? 0.25f : 0.75f), 0, new int[] {0xF2000000, 0xB3000000, 0},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        subtitleStyle();
        storyChanged();
    }

    /** The story shown while paused (a new episode has its own). */
    private void storyChanged() {
        Library.Title t = player.current();
        pauseStory = storyText == null || t == null || t.overview.isEmpty() ? null : Ui.layout(t.overview, storyText, 900 * k, 4);
        pauseStoryRight = pauseStory == null || !Ui.rtl ? 0 : Ui.lineRight(pauseStory);
    }

    /** Subtitles drawn as the settings say (size, style, color). */
    private void subtitleStyle() {
        if (titleFont == null) return;
        subtitle.configure(k, 1500 * k);
        invalidate();
    }

    private float controlsAlpha(long up) {
        if (panel != P_NONE || message != null || (!player.playing() && !loading)) return 1;
        return Ui.clamp((visibleUntil - up) / (float) FADE_MS);
    }

    /** True when the subtitle on screen changed. */
    private boolean updateSubtitle() {
        return titleFont != null && subtitle.set(player.subtitleNow());
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (titleFont == null) return;
        long up = SystemClock.uptimeMillis();
        float dt = lastDrawAt == 0 ? 0.016f : Math.min(0.1f, (up - lastDrawAt) / 1000f);
        lastDrawAt = up;
        // Until the first picture, the title's art; it then fades away over the video.
        if (!player.rendered()) renderedAt = 0;
        else if (renderedAt == 0) renderedAt = up;
        float art = renderedAt == 0 ? 1 : 1 - Ui.clamp((up - renderedAt) / (float) ART_FADE_MS);
        boolean animating = art > 0;
        // Before the first picture the screen is plain black with the spinner, like Netflix: no art, no controls.
        boolean firstLoad = !player.rendered() && message == null && panel == P_NONE;
        if (firstLoad) c.drawColor(0xFF000000);
        else if (art > 0 && message == null) drawLoadingArt(c, art);
        float a = firstLoad ? 0 : controlsAlpha(up);
        animating |= a > 0 && a < 1;
        boolean pauseInfo = pausedAt != 0 && up - pausedAt > PAUSE_INFO_MS && panel == P_NONE && message == null && !loading;
        if (pauseInfo) drawPauseInfo(c);
        else if (a > 0) drawControls(c, a);
        if (!pauseInfo && !firstLoad) drawSubtitle(c, a);
        if (panel != P_NONE) drawPanel(c);
        if (!loading) loadingSince = 0;
        else if (loadingSince == 0) loadingSince = up;
        float want = loading && up - loadingSince >= LOADER_WAIT_MS ? 1 : 0;
        loaderLevel += (want - loaderLevel) * Math.min(1, dt * 9);
        if (Math.abs(want - loaderLevel) < 0.01f) loaderLevel = want;
        if (loaderLevel > 0) drawLoader(c, up, loaderLevel);
        animating |= loading || loaderLevel > 0;
        if (message != null) {
            c.drawText(message, w / 2f, h / 2f, messageFont);
            if (retryable) {
                messageFont.setAlpha(170);
                c.drawText(Ui.tr("OK: tentar de novo · Voltar: sair", "OK: try again · Back: exit", "OK: reintentar · Atrás: salir"),
                        w / 2f, h / 2f + 64 * k, messageFont);
                messageFont.setAlpha(255);
            }
        } else if (status != null && loading) {
            c.drawText(status, w / 2f, h / 2f + 220 * k, messageFont);
        }
        if (!pauseInfo && panel == P_NONE && !firstLoad) drawButton(c, a);
        drawToast(c, up);
        if (animating) postInvalidateOnAnimation();     // every frame while something moves, not every tick
    }

    /** The loader: a red arc growing and shrinking as it turns on a faint ring, the percent inside it. */
    private void drawLoader(Canvas c, long up, float a) {
        float cx = w / 2f, cy = h / 2f, r = 62 * k, t = (up % 100_000) / 1000f;
        fill.setShader(null);
        stroke.setStrokeWidth(6 * k);
        stroke.setColor(Ui.alpha(0x2EFFFFFF, a));
        c.drawCircle(cx, cy, r, stroke);
        float sweep = 30 + 230 * (0.5f - 0.5f * (float) Math.cos(t * 2 * Math.PI / 1.5f));
        float start = (t * 320) % 360 - sweep / 2;
        stroke.setColor(Ui.alpha(CinemaView.RED, a));
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, start, sweep, false, stroke);
        if (percentText != null) {
            percentFont.setAlpha(Math.round(255 * a));
            c.drawText(percentText, cx, cy + Ui.cap(percentFont) / 2f, percentFont);
        }
    }

    private void drawControls(Canvas c, float a) {
        int alpha = Math.round(255 * a);
        fill.setShader(bottomShade);
        fill.setAlpha(alpha);
        c.drawRect(0, h - 420 * k, w, h, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        float left = M * k, right = (1920 - M) * k, y = BAR_Y * k;
        // Title (and episode) over the bar, like Netflix.
        String heading = player.heading();
        titleFont.setAlpha(alpha);
        Ui.text(c, heading, left, y - 64 * k, titleFont, w);
        String ep = player.episodeLine();
        if (!ep.isEmpty()) {
            metaFont.setAlpha(Math.round(204 * a));
            Ui.text(c, ep, left + titleFont.measureText(heading) + 24 * k, y - 64 * k, metaFont, w);
        }
        // The bar, with the time left at its end.
        long d = player.duration(), p = player.position();
        String remaining = time(Math.max(0, d - p));
        timeFont.setAlpha(alpha);
        timeFont.setTextAlign(Paint.Align.RIGHT);
        c.drawText(remaining, right, y + Ui.halfCap(timeFont), timeFont);
        float barRight = right - timeFont.measureText(remaining) - 32 * k, bh = 6 * k;
        rect.set(left, y - bh / 2, barRight, y + bh / 2);
        fill.setColor(Ui.alpha(0x59FFFFFF, a));
        c.drawRoundRect(rect, bh, bh, fill);
        float f = d <= 0 ? 0 : Ui.clamp(p / (float) d);
        rect.right = left + (barRight - left) * f;
        fill.setColor(Ui.alpha(CinemaView.RED, a));
        c.drawRoundRect(rect, bh, bh, fill);
        boolean onBar = focusArea == F_BAR && panel == P_NONE;
        c.drawCircle(rect.right, y, (player.pendingSeek() >= 0 ? 16 : onBar ? 12 : 8) * k, fill);
        if (player.pendingSeek() >= 0) {
            timeFont.setTextAlign(Paint.Align.CENTER);
            c.drawText(time(p), Math.max(left + 60 * k, Math.min(barRight - 60 * k, rect.right)), y - 30 * k, timeFont);
        }
        if (!player.playing() && !loading && message == null && panel == P_NONE) {
            fill.setColor(Ui.alpha(0x99000000, a));
            c.drawCircle(w / 2f, h / 2f, 70 * k, fill);
            Symbols.draw(getContext(), c, "play", w / 2f + 4 * k, h / 2f, 64 * k, Ui.alpha(0xFFFFFFFF, a));
        }
        drawButtons(c, a);
    }

    /** Audio & Subtitles · Quality · Episodes · Next episode. */
    private void drawButtons(Canvas c, float a) {
        if (focusArea != F_BUTTONS) buildButtons();
        float x = M * k, cy = BUTTONS_Y * k, bh = 56 * k;
        for (int i = 0; i < buttons.size(); i++) {
            String name = buttonNames.get(i);
            float bw = buttonFont.measureText(name) + 56 * k;
            boolean focused = focusArea == F_BUTTONS && i == buttonFocus;
            float bx = Ui.mx(x, bw, w);
            rect.set(bx, cy - bh / 2, bx + bw, cy + bh / 2);
            fill.setColor(Ui.alpha(focused ? 0xFFFFFFFF : 0x33FFFFFF, a));
            c.drawRoundRect(rect, bh / 2, bh / 2, fill);
            buttonFont.setColor(Ui.alpha(focused ? 0xFF0B0B0D : 0xFFFFFFFF, a));
            c.drawText(name, bx + 28 * k, cy + Ui.halfCap(buttonFont), buttonFont);
            x += bw + 20 * k;
        }
    }

    /**
     * Before the first picture: the title's own art (darkened) and its name, not a black screen; the video then
     * appears over it.
     */
    private void drawLoadingArt(Canvas c, float alpha) {
        String source = player.art();
        if (!source.equals(artSource)) {
            artSource = source;
            artKey = source.isEmpty() ? null : Thumbs.key(source, ART_W, ART_H, Thumbs.SCREEN);
        }
        android.graphics.Bitmap b = artKey == null ? null : Thumbs.get(getContext()).get(artKey, source, ART_W, ART_H, Thumbs.SCREEN);
        if (b != null) {
            rect.set(0, 0, w, h);
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, null, rect, image);
            image.setAlpha(255);
        }
        bigFont.setAlpha(Math.round(255 * alpha));
        Ui.text(c, player.heading(), M * k * 1.6f, h - 210 * k, bigFont, w);
        bigFont.setAlpha(255);
        String ep = player.episodeLine();
        if (!ep.isEmpty()) {
            int was = metaFont.getAlpha();
            metaFont.setAlpha(Math.round(was * alpha));
            Ui.text(c, ep, M * k * 1.6f, h - 150 * k, metaFont, w);
            metaFont.setAlpha(was);
        }
    }

    /** Paused a while: what's playing, calmly, over the darkened picture. */
    /**
     * Paused a while, as on Netflix: the picture dims, darker on the left, and there, low on the screen, "You're
     * watching", the name, the episode and the story.
     */
    private void drawPauseInfo(Canvas c) {
        fill.setShader(null);
        fill.setColor(0x80000000);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(pauseShade);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        float x = M * k * 1.4f, bottom = h - 150 * k;
        float storyTop = bottom - (pauseStory == null ? 0 : pauseStory.getHeight());
        if (pauseStory != null) {
            c.save();
            c.translate(Ui.layoutX(x, pauseStoryRight, w), storyTop);
            pauseStory.draw(c);
            c.restore();
        }
        float y = storyTop - 30 * k;
        String ep = player.episodeLine();
        if (!ep.isEmpty()) {
            Ui.text(c, ep, x, y, metaFont, w);
            y -= Ui.cap(metaFont) + 28 * k;
        }
        Ui.text(c, player.heading(), x, y, bigFont, w);
        headerFont.setAlpha(200);
        Ui.text(c, Ui.tr("VOCÊ ESTÁ ASSISTINDO", "YOU'RE WATCHING", "ESTÁS VIENDO"), x, y - Ui.cap(bigFont) - 30 * k, headerFont, w);
    }

    private void drawSubtitle(Canvas c, float controls) {
        subtitle.draw(c, w / 2f, (controls > 0.5f ? BAR_Y - 130 : 990) * k);
    }

    private void drawPanel(Canvas c) {
        // Right to left, the panel is on the left: its box mirrored, its words through Ui.text (mirrored the same way).
        float left = w - (PANEL_W + 64) * k, top = 120 * k, rowH = ROW_H * k, boxLeft = Ui.mx(left, PANEL_W * k, w);
        int rows = Math.min(panelRows.size() - panelTop, PANEL_ROWS);
        rect.set(boxLeft, top, boxLeft + PANEL_W * k, top + 96 * k + rows * rowH + 24 * k);
        fill.setShader(null);
        fill.setColor(0xF2141418);
        c.drawRoundRect(rect, 24 * k, 24 * k, fill);
        String header = panel == P_AUDIO ? Ui.tr("ÁUDIO E LEGENDAS", "AUDIO & SUBTITLES", "AUDIO Y SUBTÍTULOS")
                : panel == P_QUALITY ? Ui.tr("QUALIDADE", "QUALITY", "CALIDAD")
                : panel == P_SPEED ? Ui.tr("VELOCIDADE", "PLAYBACK SPEED", "VELOCIDAD") : Ui.tr("EPISÓDIOS", "EPISODES", "EPISODIOS");
        headerFont.setAlpha(153);
        Ui.text(c, header, left + 40 * k, top + 66 * k, headerFont, w);
        for (int i = 0; i < rows; i++) {
            int at = panelTop + i, row = panelRows.get(at);
            float y = top + 96 * k + i * rowH;
            boolean focused = at == panelRow;
            if (focused) {
                rect.set(boxLeft + 16 * k, y + 4 * k, boxLeft + (PANEL_W - 16) * k, y + rowH - 4 * k);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(rect, 14 * k, 14 * k, fill);
            }
            float base = y + rowH / 2 + Ui.halfCap(rowFont);
            rowFont.setColor(focused ? 0xFF0B0B0D : 0xCCFFFFFF);
            valueFont.setColor(focused ? 0xFF0B0B0D : 0xFFFFFFFF);
            float lx = left + 44 * k, rx = Ui.rtl ? boxLeft + 44 * k : left + (PANEL_W - 44) * k;
            if (panel == P_AUDIO) {
                Ui.text(c, panelLabel(row), lx, base, rowFont, w);
                c.drawText(focused ? "‹  " + panelValue(row) + "  ›" : panelValue(row), rx, base, valueFont);
            } else if (panel == P_QUALITY) {
                if (row == AUTO_ROW) {
                    Ui.text(c, autoLabel(), lx, base, rowFont, w);
                    if (player.autoQuality()) c.drawText("✓", rx, base, valueFont);
                } else {
                    Library.Source s = player.sources.get(row);
                    Ui.text(c, badge(s.label), lx, base, rowFont, w);
                    if (s == player.source && !player.autoQuality()) c.drawText("✓", rx, base, valueFont);
                }
            } else if (panel == P_SPEED) {
                Ui.text(c, speedName(SPEEDS[row]) + (SPEEDS[row] == 1f ? Ui.tr("  (normal)", "  (Normal)", "  (normal)") : ""), lx, base, rowFont, w);
                if (SPEEDS[row] == player.speed) c.drawText("✓", rx, base, valueFont);
            } else {
                Library.Title t = player.playlist.get(row);
                Ui.text(c, Ui.tr("Episódio ", "Episode ", "Episodio ") + Math.max(1, t.episode), lx, base, rowFont, w);
                Progress.Entry e = row == player.index ? null : Progress.get(getContext(), t.id);
                String mark = row == player.index ? "▶" : e != null && e.finished() ? "✓" : t.minutes > 0 ? t.minutes + " min" : "";
                c.drawText(mark, rx, base, valueFont);
            }
        }
    }

    /** "Skip intro" / "Next episode", bottom right, ready for OK (like Netflix). */
    private void drawButton(Canvas c, float controls) {
        shownButton = message != null ? null
                : player.introEnd() >= 0 ? Ui.tr("Pular abertura", "Skip intro", "Saltar intro")
                : player.nextUp() && (!creditsChosen || player.ended()) ? nextLabel(SystemClock.uptimeMillis()) : null;
        if (shownButton == null) return;
        valueFont.setColor(0xFF0B0B0D);
        float tw = valueFont.measureText(shownButton), bw = tw + 120 * k, bh = 76 * k;
        float right = (1920 - M) * k, bottom = (controls > 0.5f ? BAR_Y - 120 : 1000) * k;
        if (Ui.rtl) rect.set(w - right, bottom - bh, w - right + bw, bottom);      // right to left: bottom left
        else rect.set(right - bw, bottom - bh, right, bottom);
        long up = SystemClock.uptimeMillis();
        if (counting(up) && player.nextUp()) {
            // At the video's end only. Grey, filling with white as the countdown runs.
            fill.setColor(0xB3FFFFFF);
            c.drawRoundRect(rect, 12 * k, 12 * k, fill);
            float f = Ui.clamp((up - nextUpSince) / (float) NEXT_COUNTDOWN_MS);
            c.save();
            if (Ui.rtl) c.clipRect(rect.right - rect.width() * f, rect.top, rect.right, rect.bottom);
            else c.clipRect(rect.left, rect.top, rect.left + rect.width() * f, rect.bottom);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(rect, 12 * k, 12 * k, fill);
            c.restore();
        } else {
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(rect, 12 * k, 12 * k, fill);
        }
        Symbols.draw(getContext(), c, "play", Ui.rtl ? rect.right - 44 * k : rect.left + 44 * k, rect.centerY(), 30 * k,
                0xFF0B0B0D);
        c.drawText(shownButton, Ui.rtl ? rect.left + 36 * k : rect.right - 36 * k, rect.centerY() + Ui.halfCap(valueFont),
                valueFont);
    }

    private void drawToast(Canvas c, long up) {
        long age = up - toastAt;
        if (toast.isEmpty() || age > TOAST_MS + FADE_MS) return;
        float a = age < TOAST_MS ? 1 : 1 - (age - TOAST_MS) / (float) FADE_MS;
        float tw = messageFont.measureText(toast) + 80 * k;
        rect.set((w - tw) / 2, 80 * k, (w + tw) / 2, 156 * k);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xF01A1A1F, a));
        c.drawRoundRect(rect, 38 * k, 38 * k, fill);
        messageFont.setAlpha(Math.round(255 * a));
        c.drawText(toast, w / 2f, rect.centerY() + Ui.halfCap(messageFont), messageFont);
        messageFont.setAlpha(255);
    }

    private static String speedName(float s) {
        return s == 1f ? "1×" : s == 0.5f ? "0.5×" : s == 0.75f ? "0.75×" : s == 1.25f ? "1.25×" : "1.5×";
    }

    /** The end's countdown runs: the video ended, autoplay is on, and Back hasn't cancelled it. */
    private boolean counting(long up) {
        return nextUpSince != 0 && player.ended() && !creditsChosen && CinemaPrefs.autoplay(getContext()) && up - nextUpSince < NEXT_COUNTDOWN_MS;
    }

    private int countLeft = -1;
    private String countLabel;

    /** "Next episode", or "Next episode in 5" during the end's countdown (built again only when the second changes). */
    private String nextLabel(long up) {
        if (!counting(up)) return Ui.tr("Próximo episódio", "Next episode", "Siguiente episodio");
        int left = NextUpRules.secondsLeft(up - nextUpSince);
        if (left != countLeft || countLabel == null) {
            countLeft = left;
            String n = Ui.digits(String.valueOf(left));
            countLabel = Ui.tr("Próximo episódio em " + n, "Next episode in " + n, "Siguiente episodio en " + n,
                    "الحلقة التالية خلال " + n);
        }
        return countLabel;
    }

    /** Back chose the credits: no autoplay for this episode. */
    boolean nextDismissed() {
        return creditsChosen;
    }

    /** At the video's natural end only: the Next episode button fills over 5 s, then the next one plays. Never while it plays. */
    private void countdown(long up) {
        if (!player.nextUp()) {
            nextUpSince = 0;
            creditsChosen = false;      // seeking back before the card hides it and forgets the dismissal
            return;
        }
        if (!player.ended()) {
            nextUpSince = 0;
            return;
        }
        if (nextUpSince == 0) nextUpSince = up;
        if (NextUpRules.advance(player.hasNext(), true, CinemaPrefs.autoplay(getContext()), creditsChosen, up - nextUpSince)) {
            nextUpSince = 0;
            player.next();
        }
    }

    /** "2160p" → "4K". */
    private static String badge(String label) {
        return label.startsWith("2160") ? "4K" : label;
    }

    private static String time(long ms) {
        long s = ms / 1000, hours = s / 3600, minutes = s / 60 % 60, seconds = s % 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
