package io.watchmatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The screensaver's picture: recommendation photos with a slow zoom, a glass clock and a price row; without photos, the
 * night aurora with a big clock. Everything is positioned at 1920 x 1080 and scaled by k = width / 1920.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    private static final long SHOW = 30_000, FADE = 3_000, FIRST_FADE = 1_600, MOTION_SPAN = 33_000;
    private static final float ZOOM = 0.1f;
    private static final int PHOTO_W = 2112, PHOTO_H = 1188;
    private static final float MARGIN = 80, RIGHT = 1840, TITLE_W = 900, META_BASE = 1008, DATE_BASE = 92,
            CLOCK_TOP = 120, QUOTES_GAP = 52;
    private static final float DRIFT_PERIOD = 600;
    private static final long FRAME_GAP = 45, MINUTE = 60_000, AURORA_GAP = 100, RELEASE_DELAY = 1_000;
    private static final float ALPHA_MIN = 0.004f;

    /** One photo on screen (or waiting): its bitmaps, motion and caption layer. */
    private static final class Slide {
        final Recs.Pick pick;
        final String key;
        final Bitmap photo, frost;
        final BitmapShader frostShader;
        final boolean zoomIn;
        final float panX, panY;
        final Recording caption = Recording.create("caption");
        long start;
        float tx, ty, scale;

        Slide(Recs.Pick pick, Bitmap photo, Bitmap frost, int n) {
            this.pick = pick;
            this.key = Recs.key(pick);
            this.photo = photo;
            this.frost = frost;
            frostShader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            zoomIn = n % 2 == 0;
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** The glass clock's bitmaps for one clock string, made off the main thread. */
    private static final class GlassBits {
        String text;
        Bitmap cover, slope, shadow;
        float x, y, inkTop, inkHeight;
    }

    private final Context context;
    private final HomeData data;
    private final Recs recs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Scene scene = new Scene();
    private final Recording content = Recording.create("dream-content");
    private final Recording chrome = Recording.create("dream-chrome");
    private final Paint layerPaint = new Paint();
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint glassPaint = new Paint();
    private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds = new Rect();
    private final Set<String> failed = new HashSet<>();
    private final boolean reduceMotion = A11y.reduceMotion;
    private final float timeOffset = new Random().nextInt(300);
    private RuntimeShader glass;

    private boolean running, decoding, fallback, contentDirty = true, chromeDirty = true;
    private int generation, slideCount;
    private Slide current, incoming, prepared;
    private GlassBits bits;
    private String glassFor = "", clockText = "";
    private long minuteOfClock = -1, startedMs, firstAt, lastInvalidate, lastAurora;
    private int w, h;
    private float k = 1;

    private Paint fbDate, fbClock, fbNow, fbRange, fbPrice, fbStale;
    private Paint coDate, coPrice, coStale, coClock, coLabel, coMeta;
    private TextPaint coTitle;
    private float clockCap;

    DreamView(Context context) {
        super(context);
        this.context = context;
        Ui.init(context);
        data = HomeData.get(context);
        recs = Recs.get(context);
        setBackgroundColor(Color.BLACK);
        Shaders.dream(scene.palette);
        scene.applyPalette();
        scene.setMotion(0.4f, 0.32f);
        content.setUseCompositingLayer(true, layerPaint);
        if (!Ui.lite) {
            try {
                glass = new RuntimeShader(Shaders.GLASS_TEXT);
                glassPaint.setShader(glass);
            } catch (RuntimeException | LinkageError e) {
                AppLog.w("screensaver glass unavailable: " + e.getMessage());
                glass = null;
            }
        }
    }

    // MARK: - Lifecycle

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedMs = SystemClock.uptimeMillis();
        failed.clear();
        fallback = recs.showcase().isEmpty();
        data.addListener(this);
        recs.addListener(this);
        refresh();
        prepareNext();
        main.postDelayed(tick, MINUTE);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        main.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        final Slide[] old = {current, incoming, prepared};
        current = null;
        incoming = null;
        prepared = null;
        bits = null;
        slideCount = 0;
        glassFor = "";
        main.postDelayed(() -> {
            for (Slide s : old) recycle(s);
        }, RELEASE_DELAY);
    }

    @Override protected void onDetachedFromWindow() {
        worker.shutdownNow();
        super.onDetachedFromWindow();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refresh();
            contentDirty = true;
            chromeDirty = true;
            prepareNext();
            main.postDelayed(this, MINUTE);
        }
    };

    private void refresh() {
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
    }

    @Override public void onData() {
        contentDirty = true;
        chromeDirty = true;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        Choreographer.getInstance().postFrameCallback(this);
        long now = SystemClock.uptimeMillis();
        if (now - lastInvalidate >= FRAME_GAP) {
            lastInvalidate = now;
            invalidate();
        }
    }

    // MARK: - Size and paints

    @Override protected void onSizeChanged(int width, int height, int oldW, int oldH) {
        super.onSizeChanged(width, height, oldW, oldH);
        w = width;
        h = height;
        k = width / 1920f;
        scene.setSize(w, h);
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        makePaints();
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) recordCaption(s);
        bits = null;
        glassFor = "";
        contentDirty = true;
        chromeDirty = true;
        clockText = "";
        minuteOfClock = -1;
    }

    private static int white(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24 | 0xFFFFFF;
    }

    private void makePaints() {
        float t = A11y.text;
        boolean strong = A11y.contrast;
        fbDate = Ui.text(Ui.semibold, 36 * k * t, white(0.85f));
        fbDate.setTextAlign(Paint.Align.CENTER);
        fbClock = Ui.numbers(Ui.thin, 280 * k, white(0.95f));
        fbClock.setLetterSpacing(-0.01f);
        fbClock.setTextAlign(Paint.Align.CENTER);
        fbNow = Ui.numbers(Ui.medium, 30 * k * t, white(0.78f));
        fbRange = Ui.numbers(Ui.regular, 28 * k * t, white(strong ? 0.85f : 0.5f));
        fbPrice = Ui.numbers(Ui.medium, 24 * k * t, white(strong ? 0.9f : 0.6f));
        fbStale = Ui.text(Ui.regular, 20 * k * t, white(strong ? 0.7f : 0.35f));
        fbStale.setTextAlign(Paint.Align.CENTER);
        clockCap = 0;

        coDate = Ui.numbers(Ui.semibold, 30 * k * t, white(0.92f));
        coPrice = Ui.numbers(Ui.regular, 23 * k * t, white(strong ? 0.95f : 0.78f));
        coStale = Ui.text(Ui.medium, 21 * k * t, white(strong ? 0.85f : 0.6f));
        coClock = Ui.numbers(Ui.displaySemibold, 200 * k, white(0.95f));
        coLabel = Ui.text(Ui.medium, 25 * k * t, white(strong ? 0.95f : 0.72f));
        coLabel.setLetterSpacing(0.1f);
        coMeta = Ui.text(Ui.medium, 29 * k * t, white(strong ? 0.95f : 0.78f));
        coMeta.setLetterSpacing(0.014f);
        coTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        coTitle.setTypeface(Ui.displayMedium);
        coTitle.setTextSize(76 * k * t);
        coTitle.setColor(Color.WHITE);
        coTitle.setLetterSpacing(0.005f);
        int shadow = Color.argb(strong ? 204 : 102, 0, 0, 0);
        for (Paint p : new Paint[] {coDate, coPrice, coStale, coClock, coLabel, coMeta, coTitle}) {
            p.setShadowLayer(18 * k, 0, 2 * k, shadow);
        }
    }

    // MARK: - Photo pipeline

    private void prepareNext() {
        if (!running || decoding || prepared != null) return;
        List<Recs.Pick> list = recs.showcase();
        int n = list.size();
        if (n == 0) return;
        Slide last = incoming != null ? incoming : current;
        int at = -1;
        if (last != null) for (int i = 0; i < n; i++) if (Recs.key(list.get(i)).equals(last.key)) at = i;
        Recs.Pick pick = null;
        for (int i = 1; i <= n && pick == null; i++) {
            Recs.Pick p = list.get((at + i) % n);
            if (!failed.contains(Recs.key(p))) pick = p;
        }
        if (pick == null) {
            if (current == null) fallback = true;
            invalidate();
            return;
        }
        if (last != null && last.key.equals(Recs.key(pick)) && !last.photo.isRecycled()) {
            prepared = new Slide(pick, last.photo, last.frost, slideCount++);
            recordCaption(prepared);
            invalidate();
            return;
        }
        File file = recs.readyImage(pick);
        if (file == null) {
            failed.add(Recs.key(pick));
            prepareNext();
            return;
        }
        decode(pick, file);
    }

    private void decode(Recs.Pick pick, File file) {
        decoding = true;
        final int gen = generation;
        final boolean mirrored = Ui.rtl, strong = A11y.contrast;
        try {
            worker.execute(() -> {
                Bitmap[] pair = null;
                try {
                    float[] light = new float[2];
                    pair = Backdrop.photo(file, PHOTO_W, PHOTO_H, light);
                    bake(pair[0], light, mirrored, strong);
                    Bitmap onGpu = Backdrop.gpu(pair[0]);
                    Bitmap frost = pair[1];
                    main.post(() -> decoded(pick, onGpu, frost, gen));
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("screensaver photo failed: " + e);
                    if (pair != null) for (Bitmap b : pair) if (b != null && !b.isRecycled()) b.recycle();
                    main.post(() -> decodeFailed(pick, gen));
                }
            });
        } catch (RejectedExecutionException e) {
            decoding = false;
        }
    }

    private void decoded(Recs.Pick pick, Bitmap photo, Bitmap frost, int gen) {
        if (gen != generation) {
            photo.recycle();
            frost.recycle();
            return;
        }
        decoding = false;
        prepared = new Slide(pick, photo, frost, slideCount++);
        recordCaption(prepared);
        invalidate();
    }

    private void decodeFailed(Recs.Pick pick, int gen) {
        if (gen != generation) return;
        decoding = false;
        failed.add(Recs.key(pick));
        prepareNext();
    }

    private static float strength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    private static int black(float a) {
        return Color.argb(Math.round(Math.max(0, Math.min(1, a)) * 255), 0, 0, 0);
    }

    /** Darkens the parts of the photo under the caption and the clock, once, so the text stays readable. */
    private static void bake(Bitmap b, float[] light, boolean mirrored, boolean strong) {
        float pw = b.getWidth(), ph = b.getHeight();
        float cap = strength(light[0], 0.5f), corner = strength(light[1], 0.42f);
        if (strong) {
            cap = Math.max(cap, 0.78f);
            corner = Math.max(corner, 0.78f);
        }
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, ph, 0, 0.42f * ph,
                new int[] {black(.55f * cap), black(.33f * cap), black(.11f * cap), 0},
                new float[] {0, .35f, .7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0.42f * ph, pw, ph, p);

        float bx = mirrored ? pw : 0;
        float r = 0.7f * pw;
        p.setShader(new RadialGradient(bx, ph, r, new int[] {black(cap), black(.55f * cap), black(.15f * cap), 0},
                new float[] {0, .4f, .72f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.62f, 0, ph);
        c.drawRect(mirrored ? pw - r : 0, ph - r, mirrored ? pw : r, ph, p);
        c.restore();

        p.setShader(new LinearGradient(0, 0, 0, 0.3f * ph, new int[] {black(.3f), black(.075f), 0},
                new float[] {0, .45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, pw, 0.3f * ph, p);

        float cx = (mirrored ? 0.14f : 0.86f) * pw, cy = 0.15f * ph, cr = 0.36f * pw;
        p.setShader(new RadialGradient(cx, cy, cr,
                new int[] {black(corner), black(.6f * corner), black(.15f * corner), 0},
                new float[] {0, .42f, .75f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.72f, 0, cy);
        c.drawRect(mirrored ? 0 : 0.5f * pw, cy - cr, mirrored ? 0.5f * pw : pw, cy + cr, p);
        c.restore();
    }

    private void recycle(Slide s) {
        if (s == null) return;
        for (Bitmap b : new Bitmap[] {s.photo, s.frost}) {
            if (b != null && !b.isRecycled() && !inUse(b)) b.recycle();
        }
    }

    private boolean inUse(Bitmap b) {
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s != null && (s.photo == b || s.frost == b)) return true;
        }
        return false;
    }

    // MARK: - Timeline

    private static float smooth(float t) {
        t = Ui.clamp(t);
        return t * t * (3 - 2 * t);
    }

    private void advance(long now) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.start = now;
            firstAt = now;
            bindGlass();
            prepareNext();
        } else if (incoming == null && prepared != null && now >= current.start + SHOW) {
            incoming = prepared;
            prepared = null;
            incoming.start = now;
            bindGlass();
        } else if (incoming != null && now >= incoming.start + FADE) {
            final Slide old = current;
            current = incoming;
            incoming = null;
            main.postDelayed(() -> recycle(old), RELEASE_DELAY);
            bindGlass();
            prepareNext();
        }
    }

    private void bindGlass() {
        if (glass == null || current == null) return;
        glass.setInputShader("imageA", current.frostShader);
        glass.setInputShader("imageB", incoming != null ? incoming.frostShader : current.frostShader);
    }

    private void place(Slide s, long now) {
        float u = Ui.clamp((now - s.start) / (float) MOTION_SPAN);
        float f = reduceMotion ? 0 : s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        s.tx = rx * (s.panX * f - 1);
        s.ty = ry * (s.panY * f - 1);
        s.scale = (float) w / s.photo.getWidth() * z;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || coClock == null) return;
        long now = SystemClock.uptimeMillis();
        syncClock();
        advance(now);
        float first = current == null ? 0 : smooth((now - firstAt) / (float) FIRST_FADE);
        if (current == null || first < 1) {
            if (fallback) drawFallback(c, now, current == null ? 1 : 1 - first);
            else c.drawColor(Color.BLACK);
        }
        if (current == null) return;
        if (fallback && first >= 1) fallback = false;

        place(current, now);
        drawPhoto(c, current, first);
        float e = 0;
        if (incoming != null) {
            e = smooth((now - incoming.start) / (float) FADE);
            place(incoming, now);
            drawPhoto(c, incoming, e);
        }
        if (chromeDirty) recordChrome();
        chrome.setAlpha(first);
        chrome.draw(c);
        drawGlass(c, first, e);
        drawCaptions(c, now);
    }

    private void drawPhoto(Canvas c, Slide s, float alpha) {
        if (alpha <= ALPHA_MIN) return;
        photoPaint.setAlpha(Math.round(Math.min(1, alpha) * 255));
        c.save();
        c.translate(s.tx, s.ty);
        c.scale(s.scale, s.scale);
        c.drawBitmap(s.photo, 0, 0, photoPaint);
        c.restore();
    }

    private void drawGlass(Canvas c, float alpha, float mix) {
        GlassBits g = bits;
        if (glass == null || g == null || alpha <= ALPHA_MIN || g.cover == null) return;
        Slide a = current, b = incoming != null ? incoming : current;
        float f = Backdrop.PHOTO_FROST;
        glass.setFloatUniform("placeA", a.tx, a.ty, a.scale * f);
        glass.setFloatUniform("placeB", b.tx, b.ty, b.scale * f);
        glass.setFloatUniform("mixB", incoming != null ? mix : 0);
        glass.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    private void drawCaptions(Canvas c, long now) {
        float rise = reduceMotion ? 0 : 14 * k;
        if (incoming == null) {
            float a = slideCountIsFirst(current) ? smooth((now - firstAt - 700) / 900f) : 1;
            drawCaption(c, current, a, rise);
            return;
        }
        float t = now - incoming.start;
        drawCaption(c, current, 1 - Ui.clamp(t / 500f), 0);
        drawCaption(c, incoming, smooth((t - 1300) / 900f), rise);
    }

    private boolean slideCountIsFirst(Slide s) {
        return s.start == firstAt;
    }

    private void drawCaption(Canvas c, Slide s, float alpha, float rise) {
        if (alpha <= ALPHA_MIN) return;
        s.caption.setAlpha(alpha);
        s.caption.setTranslationY(rise * (1 - alpha));
        s.caption.draw(c);
    }

    private void syncClock() {
        long t = TrustedTime.now();
        long minute = t / MINUTE;
        if (minute == minuteOfClock && !clockText.isEmpty()) return;
        minuteOfClock = minute;
        clockText = Ui.clock(t);
        contentDirty = true;
        chromeDirty = true;
        if (glass != null && running && !glassFor.equals(clockText)) buildGlass(clockText);
    }

    // MARK: - Caption layer

    private void recordCaption(Slide s) {
        if (w == 0 || coTitle == null) return;
        Canvas c = s.caption.beginRecording(w, h);
        try {
            s.caption.setPosition(0, 0, w, h);
            s.caption.setUseCompositingLayer(true, layerPaint);
            String title = s.pick.title == null ? "" : s.pick.title;
            StaticLayout layout = Ui.layout(title, coTitle, TITLE_W * k, 2);
            int lastLine = layout.getLineCount() - 1;
            float metaBase = META_BASE * k;
            float top = metaBase - 58 * k - layout.getLineBaseline(lastLine);
            float x = Ui.layoutX(MARGIN * k, Ui.lineRight(layout), w);
            if (!Ui.rtl) x -= Ui.leftBearing(coTitle, title);
            c.save();
            c.translate(x, top);
            layout.draw(c);
            c.restore();
            float capTop = top + layout.getLineBaseline(0) - Ui.cap(coTitle);
            Ui.text(c, Recs.periodLabel(), MARGIN * k, capTop - 30 * k, coLabel, w);
            String meta = s.pick.meta;
            if (meta != null && !meta.isEmpty()) Ui.text(c, meta, MARGIN * k, metaBase, coMeta, w);
        } finally {
            s.caption.endRecording();
        }
    }

    // MARK: - Chrome layer (date, clock, prices)

    private void recordChrome() {
        chromeDirty = false;
        Canvas c = chrome.beginRecording(w, h);
        try {
            long now = TrustedTime.now();
            drawDateLine(c, now);
            drawClock(c);
            drawQuotes(c);
        } finally {
            chrome.endRecording();
        }
    }

    private boolean weatherShown() {
        return Prefs.weather() && !Double.isNaN(data.weather.temp);
    }

    private void drawDateLine(Canvas c, long now) {
        String date = Ui.date(now);
        HomeData.Weather wx = data.weather;
        boolean show = weatherShown();
        String temp = show ? HomeData.degrees(wx.temp) : "";
        String icon = show ? Symbols.weather(wx.code, wx.isDay) : null;
        float size = 30 * k;
        float dateW = coDate.measureText(date);
        float tempW = show ? coDate.measureText(temp) : 0;
        float iconW = show ? Symbols.width(context, icon, size) : 0;
        float total = dateW + (show ? 26 * k + iconW + 10 * k + tempW : 0);
        float x = RIGHT * k - total;
        float base = DATE_BASE * k;
        Paint.Align was = coDate.getTextAlign();
        coDate.setTextAlign(Paint.Align.LEFT);
        c.drawText(date, Ui.mx(x, dateW, w), base, coDate);
        if (show) {
            float ix = x + dateW + 26 * k;
            float cy = base - Ui.cap(coDate) / 2;
            Symbols.draw(context, c, icon, Ui.mx(ix, iconW, w) + iconW / 2, cy, size, white(0.92f));
            float tx = ix + iconW + 10 * k;
            c.drawText(temp, Ui.mx(tx, tempW, w), base, coDate);
        }
        coDate.setTextAlign(was);
    }

    private void drawClock(Canvas c) {
        GlassBits g = bits;
        if (glass != null) {
            if (g != null && g.shadow != null) {
                plain.setAlpha(140);
                c.drawBitmap(g.shadow, g.x, g.y + 6 * k, plain);
            }
            return;
        }
        coClock.getTextBounds(clockText, 0, clockText.length(), bounds);
        float x = Ui.rtl ? MARGIN * k - bounds.left : RIGHT * k - bounds.right;
        c.drawText(clockText, x, CLOCK_TOP * k - bounds.top, coClock);
    }

    private void drawQuotes(Canvas c) {
        int n = HomeData.coinCount();
        if (n == 0) return;
        int[] coins = HomeData.active();
        float cap = Ui.cap(coPrice);
        float base = CLOCK_TOP * k + Ui.cap(coClock) + QUOTES_GAP * k;
        float gap = 26 * k, inner = 7 * k;
        String[] texts = new String[coins.length];
        float[] widths = new float[coins.length];
        float glyph = 0, rowW = 0;
        for (int i = 0; i < coins.length; i++) {
            texts[i] = HomeData.compact(data.prices[coins[i]]);
            glyph = Glyphs.width(context, HomeData.COINS[coins[i]], cap);
            widths[i] = Glyphs.width(context, HomeData.COINS[coins[i]], cap) + inner + coPrice.measureText(texts[i]);
            rowW += widths[i] + (i > 0 ? gap : 0);
        }
        float x = RIGHT * k - rowW;
        Paint.Align was = coPrice.getTextAlign();
        coPrice.setTextAlign(Paint.Align.LEFT);
        float cx = x;
        for (int i = 0; i < coins.length; i++) {
            float gw = Glyphs.width(context, HomeData.COINS[coins[i]], cap);
            float left = Ui.mx(cx, widths[i], w);
            Glyphs.coin(context, c, HomeData.COINS[coins[i]], left + gw / 2, base - cap / 2, cap, white(0.65f));
            c.drawText(texts[i], left + gw + inner, base, coPrice);
            cx += widths[i] + gap;
        }
        coPrice.setTextAlign(was);
        String stale = HomeData.staleness(data.pricesAt, 5 * MINUTE);
        if (!stale.isEmpty()) {
            float sw = coStale.measureText(stale);
            was = coStale.getTextAlign();
            coStale.setTextAlign(Paint.Align.LEFT);
            c.drawText(stale, Ui.mx(x - 24 * k - sw, sw, w), base, coStale);
            coStale.setTextAlign(was);
        }
    }

    // MARK: - Glass clock

    private void buildGlass(String text) {
        glassFor = text;
        final int gen = generation;
        final float scale = k;
        final int width = w;
        final boolean mirrored = Ui.rtl;
        try {
            worker.execute(() -> {
                GlassBits g;
                try {
                    g = makeGlass(text, scale, width, mirrored);
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("glass clock failed: " + e);
                    return;
                }
                main.post(() -> {
                    if (gen != generation || !text.equals(clockText)) return;
                    bits = g;
                    glass.setInputShader("cover", nearest(g.cover));
                    glass.setInputShader("slope", nearest(g.slope));
                    glass.setFloatUniform("origin", g.x, g.y);
                    glass.setFloatUniform("ink", g.inkTop, g.inkHeight);
                    glass.setFloatUniform("refraction", 14 * k);
                    glass.setFloatUniform("specular", 1);
                    chromeDirty = true;
                    invalidate();
                });
            });
        } catch (RejectedExecutionException e) {
            glassFor = "";
        }
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        try {
            s.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
        } catch (RuntimeException | LinkageError ignored) {
        }
        return s;
    }

    private static GlassBits makeGlass(String text, float k, int width, boolean mirrored) {
        Paint p = Ui.numbers(Ui.displaySemibold, 200 * k, Color.WHITE);
        Rect ink = new Rect();
        p.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int bw = ink.width() + 2 * pad, bh = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, p);
        int[] px = new int[bw * bh];
        cover.getPixels(px, 0, bw, 0, 0, bw, bh);
        int[] a = new int[px.length];
        for (int i = 0; i < px.length; i++) a[i] = (px[i] >>> 24) * 16;
        int[] height = blur(a, bw, bh, Math.max(2, Math.round(6 * k)));
        int[] soft = blur(a, bw, bh, Math.max(3, Math.round(12 * k)));

        float[] gx = new float[px.length], gy = new float[px.length];
        float max = 1;
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int i = y * bw + x;
                float dx = ((x + 1 < bw ? height[i + 1] : 0) - (x > 0 ? height[i - 1] : 0)) / 2f;
                float dy = ((y + 1 < bh ? height[i + bw] : 0) - (y > 0 ? height[i - bw] : 0)) / 2f;
                gx[i] = dx;
                gy[i] = dy;
                max = Math.max(max, (float) Math.hypot(dx, dy));
            }
        }
        int[] slope = new int[px.length], shade = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int r = Math.round(128 + 127 * gx[i] / max), g = Math.round(128 + 127 * gy[i] / max);
            slope[i] = 0xFF000000 | clamp255(r) << 16 | clamp255(g) << 8 | Math.min(255, height[i] / 16);
            shade[i] = Math.min(255, soft[i] / 16) << 24;
        }
        GlassBits out = new GlassBits();
        out.text = text;
        out.cover = cover;
        out.slope = Bitmap.createBitmap(slope, bw, bh, Bitmap.Config.ARGB_8888);
        out.shadow = Bitmap.createBitmap(shade, bw, bh, Bitmap.Config.ARGB_8888);
        float inkLeft = mirrored ? MARGIN * k : RIGHT * k - ink.width();
        out.x = inkLeft - pad;
        out.y = CLOCK_TOP * k - pad;
        out.inkTop = pad;
        out.inkHeight = ink.height();
        return out;
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Three box-blur passes in each direction; pixels outside the image count as 0. */
    private static int[] blur(int[] src, int bw, int bh, int r) {
        int[] a = src.clone(), b = new int[a.length];
        for (int pass = 0; pass < 3; pass++) {
            box(a, b, bw, bh, r, true);
            box(b, a, bw, bh, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int bw, int bh, int r, boolean horizontal) {
        int lines = horizontal ? bh : bw, len = horizontal ? bw : bh;
        int step = horizontal ? 1 : bw, lineStep = horizontal ? bw : 1;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep, sum = 0;
            for (int i = 0; i <= Math.min(r, len - 1); i++) sum += in[base + i * step];
            for (int i = 0; i < len; i++) {
                out[base + i * step] = sum / div;
                if (i + r + 1 < len) sum += in[base + (i + r + 1) * step];
                if (i - r >= 0) sum -= in[base + (i - r) * step];
            }
        }
    }

    // MARK: - Fallback (aurora and a big clock)

    private void drawFallback(Canvas c, long now, float alpha) {
        if (now - lastAurora >= AURORA_GAP) {
            lastAurora = now;
            scene.advance(timeOffset + (now - startedMs) / 1000f);
        }
        int save = alpha < 1 ? c.saveLayerAlpha(0, 0, w, h, Math.round(alpha * 255)) : c.save();
        scene.drawBackground(c);
        if (contentDirty) recordContent();
        float phase = reduceMotion ? 0 : (float) (2 * Math.PI * (now - startedMs) / 1000.0 / DRIFT_PERIOD);
        content.setTranslationX(reduceMotion ? 0 : 40 * k * (float) Math.sin(phase));
        content.setTranslationY(reduceMotion ? 0 : 26 * k * (float) Math.sin(2 * phase + 0.7));
        content.draw(c);
        c.restoreToCount(save);
    }

    private void recordContent() {
        contentDirty = false;
        Canvas c = content.beginRecording(w, h);
        try {
            float cx = w / 2f;
            float cap = Ui.cap(fbClock);
            float clockBase = h / 2f + cap / 2 - 10 * k;
            c.drawText(Ui.date(TrustedTime.now()), cx, clockBase - cap - 34 * k, fbDate);
            c.drawText(clockText, cx, clockBase, fbClock);
            drawFallbackWeather(c, cx, clockBase + 76 * k);
            drawFallbackPrices(c, cx);
        } finally {
            content.endRecording();
        }
    }

    private void drawFallbackWeather(Canvas c, float cx, float base) {
        if (!weatherShown()) return;
        HomeData.Weather wx = data.weather;
        String icon = Symbols.weather(wx.code, wx.isDay);
        String now = HomeData.degrees(wx.temp);
        String max = HomeData.degrees(wx.max), min = HomeData.degrees(wx.min);
        String range = Ui.ar ? "العظمى " + max + "  الصغرى " + min
                : Ui.en ? "H:" + max + "  L:" + min : "Máx. " + max + "  Mín. " + min;
        float size = 32 * k;
        float iconW = Symbols.width(context, icon, size), nowW = fbNow.measureText(now), rangeW = fbRange.measureText(range);
        float total = iconW + 12 * k + nowW + 18 * k + rangeW;
        float x = cx - total / 2;
        fbNow.setTextAlign(Paint.Align.LEFT);
        fbRange.setTextAlign(Paint.Align.LEFT);
        Symbols.draw(context, c, icon, Ui.mx(x, iconW, w) + iconW / 2, base - Ui.cap(fbNow) / 2, size, white(0.78f));
        x += iconW + 12 * k;
        c.drawText(now, Ui.mx(x, nowW, w), base, fbNow);
        x += nowW + 18 * k;
        c.drawText(range, Ui.mx(x, rangeW, w), base, fbRange);
    }

    private void drawFallbackPrices(Canvas c, float cx) {
        int n = HomeData.coinCount();
        if (n == 0) return;
        int[] coins = HomeData.active();
        float cap = Ui.cap(fbPrice), base = h - 92 * k, gap = 56 * k, inner = 10 * k;
        String[] texts = new String[coins.length];
        float[] widths = new float[coins.length];
        float total = 0;
        for (int i = 0; i < coins.length; i++) {
            texts[i] = HomeData.price(data.prices[coins[i]]);
            widths[i] = Glyphs.width(context, HomeData.COINS[coins[i]], cap) + inner + fbPrice.measureText(texts[i]);
            total += widths[i] + (i > 0 ? gap : 0);
        }
        fbPrice.setTextAlign(Paint.Align.LEFT);
        float x = cx - total / 2;
        for (int i = 0; i < coins.length; i++) {
            String coin = HomeData.COINS[coins[i]];
            float gw = Glyphs.width(context, coin, cap);
            float left = Ui.mx(x, widths[i], w);
            Glyphs.coin(context, c, coin, left + gw / 2, base - cap / 2, cap, white(0.5f));
            c.drawText(texts[i], left + gw + inner, base, fbPrice);
            x += widths[i] + gap;
        }
        String stale = HomeData.staleness(data.pricesAt, 5 * MINUTE);
        if (!stale.isEmpty()) {
            c.drawText(Ui.tr("cotações de " + stale, "prices from " + stale, "precios de " + stale,
                    "الأسعار " + stale), cx, base + 38 * k, fbStale);
        }
    }
}
