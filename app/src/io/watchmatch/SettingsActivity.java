package io.watchmatch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.watchmatch.SettingsRows.*;

/**
 * WatchMatch's settings (the app's icon, "All Apps", or the gear on the home screen). The main page has the home screen
 * switch at the top and the groups under it (SettingsGroups); inside a group, each row shows its value and a line on what
 * it does. The side panel (right; left in right to left) previews the focused row's effect (SettingsPreview). The switch
 * runs in the main process (HomeMode): this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.watchmatch/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback, Host {
        // At 1920 × 1080 (k scales it); right to left mirrors every x: the list on the right, the preview on the left.
        private static final float M = 96, LIST_R = 1040, TOP = 168, BOTTOM = 972, PANEL_L = 1120, PANEL_R = 1824, HINT_Y = 1026;
        private static final float CARD_H = 176, GROUP_H = 100, ROW_H = 96, GAP = 8, AFTER_CARD = 22, PAD = 30;
        private static final int AMBER = 0xFFFFC266, AMBER_INK = 0xFF9A5B00, INK = 0xFF1C1C1E;
        /** Long lists (the teams) get their text made around the focus only. */
        private static final int WINDOW = 14;
        private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SettingsPreview preview;
        private final List<Page> stack = new ArrayList<>();
        private final Spring focusPop = new Spring(0.28f, 0.85f, 1), lastPop = new Spring(0.28f, 1f, 0), scrollY = new Spring(0.34f, 1f, 0);
        private int lastFocus = -1;
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private String stateText = "", recsText = "";
        private boolean hasKey, running, okDown, backDown, wasBusy, shownBusy;
        private long pendingAt, startedAt, lastNanos, lastSky, shownMinute;
        private List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        private List<String[]> shelf = new ArrayList<>();
        private int promo = -1;
        private final Spoken spoken;
        private int w, h;
        private float k = 1, t = 1, hs = 1;
        private boolean madeContrast;
        private TextPaint brandName, brandSub, crumb, pageTitle, kicker, cardTitle, cardAbout, groupTitle, groupSummary,
                rowLabel, rowValue, rowAbout, hint;
        private Paint spinner, white, shadow, flatShadow, ring;
        private int aboutInk = 0x8CFFFFFF, accent = 0xFFFFFFFF;
        private String crumbText = "", titleText = "", hintText = "", settingsWord = "", kickerText = "";
        private float nameWidth;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                final Context c = getContext();
                LOADER.execute(() -> {
                    final JSONObject s = HomeMode.state(c);
                    final boolean key = Recs.hasKey(c);
                    final JSONObject r = Recs.status(c);
                    final String sText = s.toString(), rText = r.toString();
                    handler.post(() -> stateRead(s, sText, key, r, rText));
                });
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            preview = new SettingsPreview(a, scene);
            setFocusable(true);
            setContentDescription(Config.APP_NAME);
            stack.add(SettingsGroups.main(this));
            page().rebuild();
            loadInBackground();
            // TalkBack: one item per row on screen, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    Page p = page();
                    for (int i = 0; i < p.rows.size(); i++) if (onScreen(p, i)) out.add(i + 1);
                }
                @Override public String text(int id) {
                    Page p = page();
                    if (id < 1 || id > p.rows.size()) return "";
                    Row r = p.rows.get(id - 1);
                    if (r.stamp < 0) shown(p, r, id - 1);
                    String value = r.value == null ? "" : r.value.get();
                    return r.label.get() + (value.isEmpty() ? "" : ", " + value) + (r.shownChecked ? Ui.tr(", escolhido", ", selected") : "")
                            + (r.fullAbout.isEmpty() ? "" : ". " + r.fullAbout);
                }
                @Override public boolean bounds(int id, Rect out) {
                    Page p = page();
                    if (id < 1 || id > p.rows.size() || !onScreen(p, id - 1)) return false;
                    rowBox(p, id - 1, focusBox);
                    focusBox.round(out);
                    return true;
                }
                @Override public int focused() { return page().focus + 1; }
                @Override public void focus(int id) { moveFocus(id - 1); }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        private Page page() {
            return stack.get(stack.size() - 1);
        }

        // MARK: - Host (what the pages ask for)

        @Override public Context context() { return getContext(); }
        @Override public void later(Runnable r) { handler.post(r); }
        @Override public boolean showing(Page p) { return page() == p; }
        @Override public JSONObject state() { return state; }
        @Override public JSONObject recs() { return recs; }
        @Override public boolean hasKey() { return hasKey; }
        @Override public List<String> appPkgs() { return appPkgs; }
        @Override public List<String> appLabels() { return appLabels; }
        @Override public List<String[]> shelf() { return shelf; }
        @Override public void setShelf(List<String[]> apps) { shelf = apps; }

        @Override public boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        @Override public boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        @Override public int promoApps() {
            JSONArray off = state.optJSONArray("disabled");
            if (freeActive() && off != null) return off.length();
            return Math.max(0, promo);
        }

        @Override public void requestMode(boolean free) {
            HomeMode.request(getContext(), free ? HomeMode.FREE : HomeMode.ORIGINAL);
            pendingAt = SystemClock.uptimeMillis();
        }

        @Override public void open(Page p) {
            if (p.key.equals("remote")) {
                HomeMode.ensureRemoteService(getContext(), 0);   // the buttons work from inside other apps through this service
                if (appPkgs.size() <= 1) loadApps();
            }
            page().scroll = scrollY.target;
            p.rebuild();
            stack.add(p);
            lastFocus = -1;
            focusPop.snap(0);
            focusPop.target = 1;
            refresh();
            scrollY.snap(p.scroll);
            spoken.focusMoved();
        }

        @Override public void back() {
            if (stack.size() < 2) return;
            stack.remove(stack.size() - 1);
            Page p = page();
            p.rebuild();       // values may have changed down there
            lastFocus = -1;
            focusPop.snap(0);
            focusPop.target = 1;
            refresh();
            scrollY.snap(p.scroll);
            spoken.focusMoved();
        }

        @Override public void changed() {
            refresh();
            spoken.focusMoved();
        }

        @Override public void screensaver() {
            pause();
            activity.showPreview();
        }

        @Override public void launch(Intent i) {
            try {
                activity.startActivity(i);
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("settings: couldn't open " + i + ": " + e.getMessage());
            }
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        @Override public void loadApps() {
            List<String>[] lists = SettingsLists.installedApps(getContext());
            appPkgs = lists[0];
            appLabels = lists[1];
        }

        /** What the summaries need from the package manager: the apps, the shelf, the promo apps this TV has. */
        private void loadInBackground() {
            final Context c = getContext().getApplicationContext();
            LOADER.execute(() -> {
                final List<String>[] lists = SettingsLists.installedApps(c);
                List<String[]> all = HomeApps.shelfCandidates(c);
                final List<String[]> named = new ArrayList<>();
                for (String pkg : HomeApps.shelfPackages(c)) {
                    for (String[] app : all) if (app[0].equals(pkg)) { named.add(new String[] {pkg, SettingsLists.appName(pkg, app[1])}); break; }
                }
                int n = 0;
                PackageManager pm = c.getPackageManager();
                for (String pkg : HomeMode.DEBLOAT) {
                    try {
                        ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                        if (info.enabled) n++;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // not on this TV
                    }
                }
                final int promoFound = n;
                handler.post(() -> {
                    if (appPkgs.size() <= 1) {
                        appPkgs = lists[0];
                        appLabels = lists[1];
                    }
                    if (!page().key.equals("shelf")) shelf = named;
                    promo = promoFound;
                    refresh();
                });
            });
        }

        /** The state file, read in the background: redraw only when something changed (or the minute did). */
        private void stateRead(JSONObject s, String sText, boolean key, JSONObject r, String rText) {
            if (!running) return;
            boolean nowBusy = !s.optString("busy").isEmpty();
            if (nowBusy) pendingAt = 0;
            // A switch just ended: TalkBack reads how it went (the newest notice).
            JSONArray notices = s.optJSONArray("notices");
            if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                JSONObject newest = notices.optJSONObject(0);
                if (newest != null) announceForAccessibility(newest.optString("text"));
            }
            wasBusy = nowBusy;
            long minute = System.currentTimeMillis() / 60_000;
            boolean changed = !sText.equals(stateText) || !rText.equals(recsText) || key != hasKey || busy() != shownBusy || minute != shownMinute;
            state = s;
            stateText = sText;
            recs = r;
            recsText = rText;
            hasKey = key;
            if (changed) refresh();
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            page().rebuild();      // back from another screen ({catalog}, the TV's settings): values may have changed
            refresh();
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy() || preview.animating() || !focusPop.settled() || !lastPop.settled() || !scrollY.settled();
            if (moving || scene.moving() && SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            makePaints();
            refresh();
        }

        /** The paints, at the size Accessibility > Text size asks for (t = 1 at Normal). */
        private void makePaints() {
            t = A11y.text;
            hs = 1 + (t - 1) * 0.75f;
            madeContrast = A11y.contrast;
            aboutInk = madeContrast ? 0xD9FFFFFF : 0x8CFFFFFF;
            brandName = paint(Ui.displaySemibold, 38 * k * t, 0xFFFFFFFF);
            brandSub = paint(Ui.regular, 30 * k * t, 0x8CFFFFFF);
            crumb = paint(Ui.semibold, 20 * k * t, 0x80FFFFFF);
            crumb.setLetterSpacing(0.08f);
            pageTitle = paint(Ui.displaySemibold, 46 * k * t, 0xFFFFFFFF);
            kicker = paint(Ui.semibold, 19 * k * t, 0x99FFFFFF);
            kicker.setLetterSpacing(0.1f);
            cardTitle = paint(Ui.displaySemibold, 34 * k * t, 0xFFFFFFFF);
            cardAbout = paint(Ui.regular, 24 * k * t, aboutInk);
            groupTitle = paint(Ui.medium, 30 * k * t, 0xF2FFFFFF);
            groupSummary = paint(Ui.regular, 23 * k * t, aboutInk);
            rowLabel = paint(Ui.medium, 30 * k * t, 0xF2FFFFFF);
            rowValue = paint(Ui.regular, 28 * k * t, 0xB3FFFFFF);
            rowAbout = paint(Ui.regular, 22 * k * t, aboutInk);
            hint = paint(Ui.regular, 23 * k * t, madeContrast ? 0xCCFFFFFF : 0x8CFFFFFF);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            flatShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * k);
            preview.sizeChanged(k, w, t, PANEL_L * k, TOP * k, PANEL_R * k, BOTTOM * k);
            for (Page p : stack) for (Row r : p.rows) r.stamp = -1;
        }

        private static TextPaint paint(android.graphics.Typeface face, float size, int color) {
            return new TextPaint(Ui.text(face, size, color));
        }

        // MARK: - Text, made after a key (never in onDraw)

        private void refresh() {
            if (w == 0) return;
            if (A11y.text != t || A11y.contrast != madeContrast) makePaints();
            Page p = page();
            int n = p.rows.size(), from = n <= 4 * WINDOW ? 0 : Math.max(0, p.focus - WINDOW);
            int to = n <= 4 * WINDOW ? n : Math.min(n, p.focus + WINDOW + 1);
            for (int i = from; i < to; i++) shown(p, p.rows.get(i), i);
            shownBusy = busy();
            shownMinute = System.currentTimeMillis() / 60_000;
            float listW = (LIST_R - M) * k;
            hintText = SettingsSketch.fit(p.hint.get(), hint, listW);
            accent = Ui.accent();
            settingsWord = Ui.tr("Ajustes", "Settings");
            kickerText = Ui.tr("TELA INICIAL", "HOME SCREEN");
            nameWidth = brandName.measureText(Config.APP_NAME);
            titleText = SettingsSketch.fit(p.title.get(), pageTitle, listW);
            crumbText = stack.size() < 2 ? "" : stack.size() == 2 ? Ui.tr("AJUSTES", "SETTINGS")
                    : stack.get(stack.size() - 2).title.get().toUpperCase(Ui.locale());
            ensureVisible(p);
            Row f = p.focused();
            if (f != null && f.stamp < 0) shown(p, f, p.focus);
            preview.update(f, this, p.main);
            invalidate();
        }

        /** A row's text as it will be drawn: read, then cut to fit. */
        private void shown(Page p, Row r, int i) {
            r.stamp = 0;
            String label = r.label.get(), value = r.value == null ? "" : r.value.get(), about = r.about == null ? "" : r.about.get();
            r.fullAbout = about;
            r.shownChecked = r.checked != null && r.checked.getAsBoolean();
            r.shownDim = r.dim != null && r.dim.getAsBoolean();
            r.shownLocked = r.isLocked();
            r.shownWarn = r.warn != null && r.warn.getAsBoolean();
            float inner = (LIST_R - M - 2 * PAD) * k, mark = (r.opens || r.shownChecked ? 44 : 0) * k;
            if (p.main && i == 0) {
                float room = inner - 112 * k;
                r.shownValue = SettingsSketch.fit(value, rowValue, room * 0.4f);
                r.shownLabel = SettingsSketch.fit(label, cardTitle, room - rowValue.measureText(r.shownValue) - 70 * k);
                r.shownAbout = SettingsSketch.fit(about, cardAbout, room);
            } else if (p.main) {
                r.shownLabel = SettingsSketch.fit(label, groupTitle, inner - 100 * k - mark);
                r.shownValue = SettingsSketch.fit(value, groupSummary, inner - 100 * k - mark);
                r.shownAbout = "";
            } else {
                r.shownValue = SettingsSketch.fit(value, rowValue, inner * 0.45f);
                float valueW = r.shownValue.isEmpty() ? 0 : rowValue.measureText(r.shownValue) + 24 * k;
                r.shownLabel = SettingsSketch.fit(label, rowLabel, inner - valueW - mark);
                r.shownAbout = SettingsSketch.fit(about, rowAbout, inner - mark);
            }
        }

        // MARK: - Layout: rows in the page's own coordinates (px), scrolled by scrollY

        private float rowTop(Page p, int i) {
            if (!p.main) return i * (ROW_H * hs + GAP) * k;
            if (i == 0) return 0;
            return (CARD_H * hs + AFTER_CARD + (i - 1) * (GROUP_H * hs + GAP)) * k;
        }

        private float rowHeight(Page p, int i) {
            return (p.main ? i == 0 ? CARD_H : GROUP_H : ROW_H) * hs * k;
        }

        private float viewHeight() {
            return (BOTTOM - TOP) * k;
        }

        private void ensureVisible(Page p) {
            if (p.rows.isEmpty()) return;
            int last = p.rows.size() - 1;
            float top = rowTop(p, p.focus), bottom = top + rowHeight(p, p.focus), margin = 16 * k;
            float content = rowTop(p, last) + rowHeight(p, last), target = p.scroll;
            if (top - margin < target) target = top - margin;
            if (bottom + margin > target + viewHeight()) target = bottom + margin - viewHeight();
            target = Math.max(0, Math.min(target, Math.max(0, content - viewHeight())));
            p.scroll = target;
            scrollY.target = target;
        }

        /** The row's box on screen (mirrored in right to left). */
        private void rowBox(Page p, int i, RectF out) {
            float top = TOP * k + rowTop(p, i) - scrollY.value, bottom = top + rowHeight(p, i);
            if (Ui.rtl) out.set(w - LIST_R * k, top, w - M * k, bottom);
            else out.set(M * k, top, LIST_R * k, bottom);
        }

        private boolean onScreen(Page p, int i) {
            float top = TOP * k + rowTop(p, i) - scrollY.value;
            return top + rowHeight(p, i) > (TOP - 20) * k && top < (BOTTOM + 20) * k;
        }

        private float mx(float x) {
            return Ui.rtl ? w - x : x;
        }

        /** Where a row's text starts (its right edge in right to left) and where its value ends. */
        private float startX(float d) {
            return Ui.rtl ? rowRect.right - d : rowRect.left + d;
        }

        private float endX(float d) {
            return Ui.rtl ? rowRect.left + d : rowRect.right - d;
        }

        private static float inward(float x, float d) {
            return Ui.rtl ? x + d : x - d;
        }

        /** Text that ends at x (x already mirrored). */
        private static void textEnd(Canvas c, String s, float x, float y, Paint p) {
            Paint.Align was = p.getTextAlign();
            p.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
            c.drawText(s, x, y, p);
            p.setTextAlign(was);
        }

        // MARK: - Draw

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            focusPop.step(dt);
            lastPop.step(dt);
            scrollY.step(dt);
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            scene.drawBackground(c);
            if (A11y.contrast) c.drawColor(0x99000000);     // Accessibility > High contrast: a darker sky behind the text

            Page p = page();
            drawHeader(c, p);
            c.save();
            if (Ui.rtl) c.clipRect(w - (LIST_R + 24) * k, (TOP - 20) * k, w - (M - 24) * k, (BOTTOM + 20) * k);
            else c.clipRect((M - 24) * k, (TOP - 20) * k, (LIST_R + 24) * k, (BOTTOM + 20) * k);
            for (int i = 0; i < p.rows.size(); i++) {
                if (!onScreen(p, i)) continue;
                Row r = p.rows.get(i);
                if (r.stamp < 0) continue;      // not laid out yet (far from the focus in a long list)
                rowBox(p, i, rowRect);
                float f = i == p.focus ? Ui.clamp(focusPop.value) : i == lastFocus ? Ui.clamp(lastPop.value) : 0;
                if (p.main && i == 0) drawModeCard(c, r, f, seconds);
                else if (p.main) drawGroup(c, r, f);
                else drawRow(c, r, f);
            }
            c.restore();
            preview.draw(c, seconds);
            Ui.text(c, hintText, M * k, HINT_Y * k, hint, w);
        }

        private void drawHeader(Canvas c, Page p) {
            if (p.main) {
                Symbols.draw(getContext(), c, "brand", mx((M + 22) * k), 100 * k, 40 * k, 0xEBFFFFFF);
                float x = (M + 60) * k;
                Ui.text(c, Config.APP_NAME, x, 114 * k, brandName, w);
                Ui.text(c, settingsWord, x + nameWidth + 18 * k, 114 * k, brandSub, w);
                return;
            }
            Ui.text(c, crumbText, M * k, 70 * k, crumb, w);
            Ui.text(c, titleText, M * k, 128 * k, pageTitle, w);
        }

        /** Focused: a white card (lifted, with a shadow) and, with an accent colour, its ring. Returns nothing: rowRect grows. */
        private void drawFocus(Canvas c, float f, float radius) {
            if (f <= 0.01f) return;
            float s = 1 + 0.02f * f, cx = rowRect.centerX(), cy = rowRect.centerY();
            tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
            float r = radius * s;
            tmp.offset(0, 10 * k * f);
            if (Ui.lite) {       // a blur per frame is too much for the 2 GB TVs: a soft flat shadow instead
                flatShadow.setColor(Ui.alpha(0xFF000000, 0.22f * f));
                c.drawRoundRect(tmp, r, r, flatShadow);
            } else {
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
            }
            tmp.offset(0, -10 * k * f);
            white.setAlpha(Math.round(255 * f));
            c.drawRoundRect(tmp, r, r, white);
            if (accent != 0xFFFFFFFF) {
                ring.setColor(Ui.alpha(accent, f));
                c.drawRoundRect(tmp, r, r, ring);
            }
            rowRect.set(tmp);
        }

        /** The home screen switch: what's in use, what switching does (or how it's going), at the top of the main page. */
        private void drawModeCard(Canvas c, Row r, float f, float seconds) {
            float radius = 28 * k;
            scene.glass(c, rowRect, radius, 0x0B0E16, 0.35f, 0xFFFFFF, 0.08f, 0, 1);
            drawFocus(c, f, radius);
            int ink = blend(0xFFFFFFFF, INK, f);
            Symbols.draw(getContext(), c, "home", startX(58 * k), rowRect.top + rowRect.height() * 0.42f, 44 * k,
                    freeActive() ? blend(accent, INK, f) : ink);
            float x = startX(112 * k), top = rowRect.top, scale = hs;
            kicker.setColor(blend(0x99FFFFFF, 0x991C1C1E, f));
            Ui.textAt(c, kickerText, x, top + 46 * k * scale, kicker);
            cardTitle.setColor(ink);
            Ui.textAt(c, r.shownLabel, x, top + 96 * k * scale, cardTitle);
            cardAbout.setColor(r.shownWarn ? blend(AMBER, AMBER_INK, f) : blend(aboutInk, 0x991C1C1E, f));
            Ui.textAt(c, r.shownAbout, x, top + 140 * k * scale, cardAbout);
            float end = endX(PAD * k), base = top + 96 * k * scale;
            rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
            if (busy()) {
                textEnd(c, r.shownValue, end, base, rowValue);
                float vw = rowValue.measureText(r.shownValue) + 26 * k;
                spinner.setColor(ink);
                Ui.spinner(c, inward(end, vw), base - Ui.halfCap(rowValue), 12 * k, seconds * 1.3f, spinner);
            } else {
                float cw = chevron(c, end, base - Ui.halfCap(rowValue), blend(0x66FFFFFF, 0x991C1C1E, f));
                textEnd(c, r.shownValue, inward(end, cw + 12 * k), base, rowValue);
            }
        }

        /** A group on the main page: its symbol, its name and the summary of its values. */
        private void drawGroup(Canvas c, Row r, float f) {
            drawFocus(c, f, 20 * k);
            int ink = blend(0xF2FFFFFF, INK, f);
            Symbols.draw(getContext(), c, r.icon, startX(52 * k), rowRect.centerY(), 34 * k, ink);
            float x = startX(100 * k);
            groupTitle.setColor(ink);
            Ui.textAt(c, r.shownLabel, x, rowRect.top + 44 * k * hs, groupTitle);
            groupSummary.setColor(blend(aboutInk, 0x991C1C1E, f));
            Ui.textAt(c, r.shownValue, x, rowRect.top + 78 * k * hs, groupSummary);
            chevron(c, endX(PAD * k), rowRect.centerY(), blend(0x66FFFFFF, 0x991C1C1E, f));
        }

        /** A setting: its name and value, and under them what it does. */
        private void drawRow(Canvas c, Row r, float f) {
            drawFocus(c, f, 18 * k);
            boolean faint = r.shownLocked || r.shownDim;
            rowLabel.setColor(blend(faint ? 0x73FFFFFF : 0xF2FFFFFF, faint ? 0x801C1C1E : INK, f));
            float base = rowRect.top + 41 * k * hs, end = endX(PAD * k);
            Ui.textAt(c, r.shownLabel, startX(PAD * k), base, rowLabel);
            rowAbout.setColor(r.shownWarn ? blend(AMBER, AMBER_INK, f) : blend(r.shownLocked ? 0x59FFFFFF : aboutInk, 0x991C1C1E, f));
            Ui.textAt(c, r.shownAbout, startX(PAD * k), rowRect.top + 75 * k * hs, rowAbout);
            int mark = blend(0xFFFFFFFF, INK, f);
            if (r.shownChecked) {
                float cw = Symbols.width(getContext(), "check", 26 * k);
                Symbols.draw(getContext(), c, "check", inward(end, cw / 2), base - Ui.halfCap(rowLabel), 26 * k, mark);
                end = inward(end, cw + 16 * k);
            } else if (r.opens) {
                end = inward(end, chevron(c, end, base - Ui.halfCap(rowLabel), blend(0x66FFFFFF, 0x991C1C1E, f)) + 14 * k);
            }
            if (!r.shownValue.isEmpty()) {
                rowValue.setColor(blend(r.shownLocked ? 0x59FFFFFF : 0xB3FFFFFF, r.shownLocked ? 0x591C1C1E : 0xB31C1C1E, f));
                textEnd(c, r.shownValue, end, base, rowValue);
            }
        }

        /** The "opens a screen" chevron at `end`, pointing left in right to left. Returns its width. */
        private float chevron(Canvas c, float end, float cy, int color) {
            float cw = Symbols.width(getContext(), "chevron_right", 22 * k), cx = inward(end, cw / 2);
            if (!Ui.rtl) {
                Symbols.draw(getContext(), c, "chevron_right", cx, cy, 22 * k, color);
                return cw;
            }
            c.save();
            c.scale(-1, 1, cx, cy);
            Symbols.draw(getContext(), c, "chevron_right", cx, cy, 22 * k, color);
            c.restore();
            return cw;
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        // MARK: - Keys: ↑ ↓ move, ← → change (swapped in right to left), OK changes or opens, Back goes up a level

        private void moveFocus(int to) {
            Page p = page();
            if (to < 0 || to >= p.rows.size() || to == p.focus) return;
            lastFocus = p.focus;
            lastPop.snap(focusPop.value);
            lastPop.target = 0;
            focusPop.snap(0);
            focusPop.target = 1;
            p.focus = to;
            refresh();
        }

        private void activate() {
            Row r = page().focused();
            if (r != null) r.press();
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) {
                        moveFocus(page().focus + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
                        spoken.focusMoved();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    // Right to left: ← is "next" (the values run leftwards), → "back".
                    int dir = Ui.dpad(e.getKeyCode()) == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    Row r = page().focused();
                    if (down && r != null) r.side(dir);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (stack.size() < 2) return false;   // leaves the settings
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
    }
}
