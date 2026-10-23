package io.watchmatch;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ask {spark}: a chat that keeps asking short questions (the mood, who is watching, what is on your mind, how long)
 * and then picks real titles from {catalog}. The AI asks (TonightApi) when this TV has a Watchme+ session; otherwise,
 * or when it can't be reached, seven skippable fixed questions do (TonightChat). Answers: a button, typing or the voice (Voice,
 * as in search). The titles come as cards that open the title's page, as search results do. Its own screen: the normal
 * search is untouched. Chat logic is TonightChat, TonightResolver and TonightTaste (pure); this is the views.
 */
public class TonightActivity extends Activity implements Thumbs.Listener, TonightResults.Host {
    /** Who opened it, and so how the results look: STYLE_APPS (WatchMatch: a row for each app), else STYLE_CATALOG (the titles). */
    static final String EXTRA_STYLE = "io.watchmatch.tonight.STYLE";
    static final String STYLE_APPS = "apps", STYLE_CATALOG = "catalog";

    // Search's own measures (1920 space): the side margin and the buttons.
    private static final int M = 96, RING = 4;
    private static final int PILL_H = 76, MIC = 88, BUBBLE_W = 1100, FIELD_W = 1100;
    private static final int FIELD = 0xFF1C1C21;
    private static final int MIC_PERMISSION = 2;

    private static final TonightText.Tr TR = Ui::tr;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TonightApi api;
    private TonightData.Taste taste;
    private String summary = "{}";
    private TonightChat.State state;
    private boolean aiReady;
    private int generation, shown;
    private boolean apps;
    private TonightProtocol.Filters active;
    private TonightResults results;
    private float k;
    private Thumbs thumbs;
    private Voice voice;
    private View mic;
    private GradientDrawable micBg;
    private TextView headline, status;
    private LinearLayout chat, choices;
    private ScrollView chatScroll;
    private HorizontalScrollView choiceLine;
    private EditText box;
    private LinearLayout inputBar;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        thumbs = Thumbs.get(this);
        apps = STYLE_APPS.equals(getIntent().getStringExtra(EXTRA_STYLE));
        api = TonightData.api();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0B0B0D);
        page.setPadding(0, px(48), 0, px(36));
        headline = text(46, 0xFFFFFFFF, Ui.semibold);
        headline.setPadding(px(M), 0, px(M), 0);
        headline.setText(TonightText.get(TR, "title"));
        page.addView(headline);
        status = text(28, 0x99FFFFFF, Ui.medium);
        status.setPadding(px(M), px(8), px(M), px(12));
        status.setText(TonightText.get(TR, "lead"));
        page.addView(status);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(px(M), px(8), px(M), px(8));
        chatScroll = new ScrollView(this);
        chatScroll.setVerticalScrollBarEnabled(false);
        chatScroll.setFocusable(false);
        chatScroll.addView(chat);
        page.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        results = new TonightResults(this, k, thumbs, this, apps);
        page.addView(results.chipLine());
        page.addView(results.scroll(), new LinearLayout.LayoutParams(-1, 0, 1));      // the screen's height under the heading
        choiceLine = new SearchRows.Edge(this, px(M));
        choiceLine.setFillViewport(true);
        choices = new LinearLayout(this);
        choices.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        choices.setPadding(px(M), px(10), px(M), px(10));
        choiceLine.addView(choices);
        page.addView(choiceLine);
        page.addView(inputBar());
        setContentView(page);
        begin();
    }

    // MARK: - The conversation

    /** Finds out (off the main thread) whether the AI can be asked, and reads the taste once; then the first question. */
    private void begin() {
        final int run = ++generation;
        status.setText(TonightText.get(TR, "lead"));
        final Context c = getApplicationContext();
        io.execute(() -> {
            if (taste == null) taste = TonightData.taste(c);
            final boolean ai = api.ready();
            if (ai) summary = TonightTaste.build(taste.input, TonightData.now(), TonightData.language()).json();
            main.post(() -> {
                if (run != generation || isFinishing()) return;
                aiReady = ai;
                state = TonightChat.start(ai ? TonightChat.Mode.AI : TonightChat.Mode.FALLBACK, TR);
                shown = 0;
                chat.removeAllViews();
                hideResults();
                render();
            });
        });
    }

    /** What the person answered (a button, typing or the voice). */
    private void submit(String text) {
        submit(text, text);
    }

    /** `shown`: what the chat says the person said (a button's label, where `text` is its value). */
    private void submit(String text, String shown) {
        if (state == null || !state.canAnswer()) return;
        final int run = ++generation;       // a typed refine while titles are loading drops that search
        if (state.mode == TonightChat.Mode.FALLBACK) {
            state = TonightChat.answerFallback(state, text, shown, TR);
            render();
            if (state.done() && state.filters != null) find(state.filters);
            return;
        }
        final TonightChat.State asked = TonightChat.addUser(state, text);
        if (asked == state) return;
        state = asked;
        render();
        final List<TonightProtocol.Msg> history = TonightChat.historyForApi(asked);
        final String sum = summary;
        io.execute(() -> {
            final TonightProtocol.Answer answer = api.ask(history, sum);
            main.post(() -> {
                if (run != generation || isFinishing()) return;
                if (!answer.ok()) {
                    AppLog.w("tonight AI: " + answer.fail);
                    state = TonightChat.toFallback(asked, TR);
                } else {
                    state = TonightChat.applyReply(asked, answer.reply, TR);
                }
                render();
                if (state.done() && state.filters != null) find(state.filters);
            });
        });
    }

    /** The filters become results: the titles (Watchme+) or a row for each app (WatchMatch). */
    private void find(final TonightProtocol.Filters filters) {
        final int run = generation;
        active = filters;
        status.setText(TonightText.get(TR, "finding"));
        final Context c = getApplicationContext();
        io.execute(() -> {
            TonightResolver.Found found;
            try {
                found = TonightResolver.search(filters, new TonightResolver.Deps(TonightData.source(c), TonightTaste.affinity(taste.input),
                        taste.exclude, TonightData.now().get(java.util.Calendar.YEAR), TR, Ui.ar));
            } catch (RuntimeException e) {
                AppLog.w("tonight resolve: " + e);
                found = new TonightResolver.Found(new ArrayList<TonightResolver.Pick>(), filters, false);
            }
            final TonightResolver.Found got = found;
            final List<Catalog.Entry> titles = TonightData.entries(got.picks);
            List<Spark.Row> byApp = null;
            if (apps) {
                try {
                    // Watchme+'s row is what was found above; the other apps' rows come from TMDB with the same filters.
                    // The apps' rows from TMDB (an empty catalog row: none of Spark's own); Watchme+'s row is added first here.
                    List<Spark.Row> rest = Spark.run(c, got.used.query(), new ArrayList<Catalog.Entry>()).rows;
                    byApp = new ArrayList<>();
                    if (!titles.isEmpty()) byApp.add(new Spark.Row(WatchMe.NAME, Spark.POSTERS, titles));
                    byApp.addAll(rest);
                } catch (RuntimeException e) {
                    AppLog.w("tonight apps: " + e);
                }
            }
            final List<Spark.Row> rows = byApp;
            AppLog.w("tonight " + (apps ? "apps" : "catalog") + ": " + got.picks.size() + " picks, " + titles.size() + " cards, "
                    + (rows == null ? 0 : rows.size()) + " rows, filters " + got.used);
            main.post(() -> {
                if (run != generation || isFinishing()) return;
                showResults(got, titles, rows);
            });
        });
    }

    private void showResults(TonightResolver.Found found, List<Catalog.Entry> titles, List<Spark.Row> rows) {
        int total = titles.size();
        if (apps) {
            total = 0;
            if (rows == null) rows = java.util.Collections.singletonList(new Spark.Row(WatchMe.NAME, Spark.POSTERS, titles));
            for (Spark.Row r : rows) if (r.style != Spark.APPS) total += r.items.size();
        }
        String line = TonightResults.headline(total, found.used, found.relaxed);
        status.setText(line);
        if (total == 0) {
            hideResults();
            addBubble(false, line);
            showChoices(state.choices, true);
            return;
        }
        results.showChips(found.used);
        chatScroll.setVisibility(View.GONE);
        inputBar.setVisibility(state != null && state.canAnswer() ? View.VISIBLE : View.GONE);   // the AI takes a refinement
        View first = apps ? results.showRows(rows) : results.showGrid(titles);
        if (first != null) first.requestFocus();
    }

    /** Back to the chat. */
    private void hideResults() {
        results.hide();
        chatScroll.setVisibility(View.VISIBLE);
    }

    /** A chip taken off: the same search without it. */
    @Override public void dropPart(String part) {
        if (active != null) find(active.without(part));
    }

    @Override public void open(Catalog.Entry entry) {
        EntryOpen.open(this, entry);
    }

    /** New lines of the chat, then what can be answered. */
    private void render() {
        if (state.status == TonightChat.Status.ASKING && results.visible()) hideResults();   // the AI asks again
        for (; shown < state.messages.size(); shown++) {
            TonightProtocol.Msg m = state.messages.get(shown);
            addBubble(m.user, m.text);
        }
        switch (state.status) {
            case THINKING: status.setText(TonightText.get(TR, "thinking")); break;
            case DONE: status.setText(TonightText.get(TR, "finding")); break;
            default: status.setText(TonightText.get(TR, "lead")); break;
        }
        inputBar.setVisibility(state.canAnswer() ? View.VISIBLE : View.GONE);
        showChoices(state.choices, state.canAnswer() || state.done());
        chatScroll.post(() -> chatScroll.scrollTo(0, chat.getHeight()));
    }

    private void startOver() {
        if (voice != null) voice.cancel();
        box.setText("");
        begin();
    }

    // MARK: - Views

    private void addBubble(boolean user, String s) {
        TextView t = text(32, user ? 0xFF0B0B0D : 0xFFFFFFFF, Ui.medium);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(user ? 0xFFFFFFFF : 0x26FFFFFF);
        bg.setCornerRadius(px(30));
        t.setBackground(bg);
        t.setPadding(px(32), px(20), px(32), px(20));
        t.setText(s);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setGravity(hasArabic(s) ? Gravity.RIGHT : Gravity.LEFT);
        // The person's lines on one side, the chat's on the other (mirrored in Arabic).
        boolean right = user != Ui.rtl;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = right ? Gravity.RIGHT : Gravity.LEFT;
        lp.bottomMargin = px(14);
        t.setMaxWidth(px(BUBBLE_W));
        chat.addView(t, lp);
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x0600 && s.charAt(i) <= 0x06FF) return true;
        return false;
    }

    /** The answers as buttons, and Start over. The first one takes the focus. */
    private void showChoices(List<TonightChat.Choice> list, boolean visible) {
        choices.removeAllViews();
        choiceLine.setVisibility(visible ? View.VISIBLE : View.GONE);
        View first = null;
        for (final TonightChat.Choice c : list) {
            View v = pill(c.label, () -> submit(c.value, c.label));
            addPill(v);
            if (first == null) first = v;
        }
        View again = pill(TonightText.get(TR, "startOver"), this::startOver);
        addPill(again);
        if (!results.visible()) {
            if (first != null) first.requestFocus();
            else if (state != null && state.canAnswer()) box.requestFocus();
            else again.requestFocus();
        }
        if (Ui.rtl) choiceLine.post(() -> choiceLine.scrollTo(choices.getWidth(), 0));
    }

    private void addPill(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(PILL_H));
        if (Ui.rtl) {
            lp.leftMargin = px(16);
            choices.addView(v, 0, lp);
        } else {
            lp.rightMargin = px(16);
            choices.addView(v, lp);
        }
    }

    /** A pill like Spark's chips; focused, white. */
    private View pill(String label, final Runnable action) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(px(PILL_H / 2f));
        final TextView t = text(30, 0xFFFFFFFF, Ui.medium);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(bg);
        t.setPadding(px(34), 0, px(34), 0);
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x26FFFFFF);
            t.setTextColor(has ? 0xFF0B0B0D : 0xFFFFFFFF);
        });
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private View inputBar() {
        inputBar = new LinearLayout(this);
        inputBar.setClipChildren(false);
        inputBar.setClipToPadding(false);
        inputBar.setPadding(px(M), px(8), px(M), px(8));
        inputBar.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        box = new EditText(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(FIELD);
        bg.setCornerRadius(px(40));
        box.setBackground(bg);
        box.setPadding(px(36), 0, px(36), 0);
        box.setTextColor(0xFFFFFFFF);
        box.setHintTextColor(0x80FFFFFF);
        box.setHint(TonightText.get(TR, "hint"));
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
        box.setTypeface(Ui.medium);
        box.setSingleLine(true);
        box.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        box.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        box.setOnEditorActionListener((v, action, e) -> {
            String s = box.getText().toString();
            box.setText("");
            hideKeyboard();
            if (!s.trim().isEmpty()) submit(s);
            return true;
        });
        inputBar.addView(box, new LinearLayout.LayoutParams(px(FIELD_W), px(MIC)));
        if (Voice.available(this) && Symbols.get(this, "mic") != null) {
            voice = new Voice(this, heard);
            View m = micButton();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(MIC), px(MIC));
            if (Ui.rtl) {
                lp.rightMargin = px(20);
                inputBar.addView(m, 0, lp);
            } else {
                lp.leftMargin = px(20);
                inputBar.addView(m, lp);
            }
        }
        return inputBar;
    }

    private View micButton() {
        micBg = new GradientDrawable();
        micBg.setShape(GradientDrawable.OVAL);
        ImageView m = new ImageView(this);
        m.setImageBitmap(Symbols.get(this, "mic"));
        m.setScaleType(ImageView.ScaleType.FIT_CENTER);
        m.setBackground(micBg);
        m.setPadding(px(24), px(24), px(24), px(24));
        m.setFocusable(true);
        m.setContentDescription(TonightText.get(TR, "voice"));
        m.setOnFocusChangeListener((v, has) -> micLook());
        m.setOnClickListener(v -> listen());
        mic = m;
        micLook();
        return m;
    }

    private void micLook() {
        if (mic == null) return;
        boolean on = voice != null && voice.listening();
        micBg.setColor(on ? Ui.accent() : FIELD);
        micBg.setStroke(mic.hasFocus() && !on ? px(RING) : 0, Ui.accent());
        ((ImageView) mic).setColorFilter(on ? 0xFF0B0B0D : mic.hasFocus() ? 0xFFFFFFFF : 0xB3FFFFFF);
    }

    // MARK: - Voice (the same recognizer as search)

    private void listen() {
        if (voice == null) return;
        if (voice.listening()) {
            voice.cancel();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        box.setText("");
        box.setHint(TonightText.get(TR, "listening"));
        voice.start();
        micLook();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] granted) {
        if (request != MIC_PERMISSION) return;
        if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) listen();
        else status.setText(shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ? Voice.micNeeded()
                : TonightText.get(TR, "noMic"));
    }

    private final Voice.Listener heard = new Voice.Listener() {
        @Override public void heard(String soFar) {
            box.setText(soFar);
        }

        @Override public void said(String sentence) {
            micLook();
            box.setHint(TonightText.get(TR, "hint"));
            box.setText("");
            submit(sentence);
        }

        @Override public void stopped(String why) {
            micLook();
            box.setText("");
            box.setHint(why != null ? why : TonightText.get(TR, "hint"));
        }
    };

    /** The remote's voice key listens (or stops), where the TV lets it through. */
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) && voice != null) {
            if (e.getRepeatCount() == 0) listen();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /** While listening, Back and OK stop it. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (voice != null && voice.listening() && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER)) {
            if (e.getAction() == KeyEvent.ACTION_UP) voice.cancel();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
    }

    @Override public void onThumb() {
        results.onThumb();
    }

    @Override protected void onResume() {
        super.onResume();
        thumbs.setListener(this);
    }

    @Override protected void onPause() {
        thumbs.setListener(null);
        if (voice != null) voice.cancel();
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        if (voice != null) voice.release();
        io.shutdown();
        super.onDestroy();
    }

    private TextView text(float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(face);
        // Absolute: START follows each text's own direction here (no RTL support), so Arabic would go the wrong way in LTR.
        t.setGravity(Ui.rtl ? Gravity.RIGHT : Gravity.LEFT);
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
