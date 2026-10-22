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
 * Spark Tonight: a chat that keeps asking short questions (who is watching, the mood, what you feel like, how long)
 * and then picks real titles from {catalog}. The AI asks (TonightApi) when this TV has a Watchme+ session; otherwise,
 * or when it can't be reached, four fixed questions do (TonightChat). Answers: a button, typing or the voice (Voice,
 * as in search). The titles come as cards that open the title's page, as search results do. Its own screen: the normal
 * search is untouched. Chat logic is TonightChat, TonightResolver and TonightTaste (pure); this is the views.
 */
public class TonightActivity extends Activity implements Thumbs.Listener {
    // Search's own measures (1920 space): the side margin, the cards, their focus.
    private static final int M = 96, POSTER_W = 216, POSTER_H = 324, GAP = 24, RADIUS = 14, RING = 4, RING_OUT = 3, ROOM = 28;
    private static final int PILL_H = 76, MIC = 88, BUBBLE_W = 1100, FIELD_W = 1100;
    private static final float FOCUS_SCALE = 1.08f;
    private static final int FIELD = 0xFF1C1C21, INK = 0xB3FFFFFF, SUB_INK = 0x80FFFFFF, PLACEHOLDER = 0xFF1E1F24;
    private static final int MIC_PERMISSION = 2;

    private static final TonightText.Tr TR = Ui::tr;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ImageView> waiting = new ArrayList<>();
    private TonightApi api;
    private TonightData.Taste taste;
    private String summary = "{}";
    private TonightChat.State state;
    private boolean aiReady;
    private int generation, shown;
    private float k;
    private Thumbs thumbs;
    private Voice voice;
    private View mic;
    private GradientDrawable micBg;
    private TextView headline, status;
    private LinearLayout chat, choices, picksRow;
    private ScrollView chatScroll;
    private HorizontalScrollView choiceLine, picksLine;
    private TextView picksLabel;
    private EditText box;
    private LinearLayout inputBar;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        thumbs = Thumbs.get(this);
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
        picksLabel = text(30, 0xFFFFFFFF, Ui.semibold);
        picksLabel.setPadding(px(M), px(8), px(M), px(12));
        picksLabel.setVisibility(View.GONE);
        page.addView(picksLabel);
        picksLine = new SearchRows.Edge(this, px(M));
        picksLine.setVisibility(View.GONE);
        picksRow = new LinearLayout(this);
        picksRow.setClipChildren(false);
        picksRow.setGravity(Gravity.TOP | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        picksRow.setPadding(px(M), px(ROOM), px(M), px(ROOM));
        picksLine.setFillViewport(true);
        picksLine.addView(picksRow);
        page.addView(picksLine);
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
                showPicks(null);
                render();
            });
        });
    }

    /** What the person answered (a button, typing or the voice). */
    private void submit(String text) {
        if (state == null || !state.canAnswer()) return;
        final int run = generation;
        if (state.mode == TonightChat.Mode.FALLBACK) {
            state = TonightChat.answerFallback(state, text, TR);
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

    /** The filters become cards. */
    private void find(final TonightProtocol.Filters filters) {
        final int run = generation;
        status.setText(TonightText.get(TR, "finding"));
        final Context c = getApplicationContext();
        io.execute(() -> {
            List<TonightResolver.Pick> picks;
            try {
                picks = TonightResolver.resolve(filters, new TonightResolver.Deps(TonightData.source(c), TonightTaste.affinity(taste.input),
                        taste.exclude, TonightData.now().get(java.util.Calendar.YEAR), TR, Ui.ar));
            } catch (RuntimeException e) {
                AppLog.w("tonight resolve: " + e);
                picks = new ArrayList<>();
            }
            final List<TonightResolver.Pick> found = picks;
            main.post(() -> {
                if (run != generation || isFinishing()) return;
                if (found.isEmpty()) {
                    addBubble(false, TonightText.get(TR, "nothing"));
                    status.setText(TonightText.get(TR, "nothing"));
                    showChoices(new ArrayList<TonightChat.Choice>(), true);
                } else {
                    showPicks(found);
                }
            });
        });
    }

    /** New lines of the chat, then what can be answered. */
    private void render() {
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
            View v = pill(c.label, () -> submit(c.value));
            addPill(v);
            if (first == null) first = v;
        }
        View again = pill(TonightText.get(TR, "startOver"), this::startOver);
        addPill(again);
        if (picksLine.getVisibility() != View.VISIBLE) {
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

    // MARK: - The picks

    /** The cards (null: back to the chat). */
    private void showPicks(List<TonightResolver.Pick> picks) {
        picksRow.removeAllViews();
        waiting.clear();
        boolean on = picks != null && !picks.isEmpty();
        picksLine.setVisibility(on ? View.VISIBLE : View.GONE);
        picksLabel.setVisibility(on ? View.VISIBLE : View.GONE);
        chatScroll.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) return;
        picksLabel.setText(TonightText.get(TR, "picks"));
        picksLabel.setGravity(Ui.rtl ? Gravity.RIGHT : Gravity.LEFT);
        status.setText(TonightText.get(TR, "leadIn"));
        inputBar.setVisibility(View.GONE);
        View first = null;
        for (int i = 0; i < picks.size(); i++) {
            View card = card(picks.get(i), i > 0);
            if (Ui.rtl) picksRow.addView(card, 0);
            else picksRow.addView(card);
            if (first == null) first = card;
        }
        if (first != null) first.requestFocus();
        if (Ui.rtl) picksLine.post(() -> picksLine.scrollTo(picksRow.getWidth(), 0));
    }

    /** A poster as in search results: its title and, under it, why it is here. OK opens the title's page. */
    private View card(final TonightResolver.Pick pick, boolean gap) {
        final TonightResolver.Item item = pick.item;
        final Library.Title title = TonightData.LAST.get(item.id);
        final int w = px(POSTER_W), h = px(POSTER_H), radius = px(RADIUS);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true);
        col.setClipChildren(false);
        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(false);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(PLACEHOLDER);
        ph.setCornerRadius(radius);
        frame.setBackground(ph);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        String poster = title == null ? null : title.poster;
        if (poster != null) {
            String key = Thumbs.key(poster, w, h, radius);
            img.setTag(new Object[] {key, poster, w, h});
            Bitmap b = thumbs.get(key, poster, w, h, radius);
            if (b != null) img.setImageBitmap(b);
            else waiting.add(img);
        }
        frame.addView(img, new FrameLayout.LayoutParams(w, h));
        int out = px(RING_OUT);
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + out);
        ring.setStroke(px(RING), Ui.accent());
        final View focusRing = new View(this);
        focusRing.setBackground(ring);
        focusRing.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(w + 2 * out, h + 2 * out);
        rp.leftMargin = rp.topMargin = -out;
        frame.addView(focusRing, rp);
        col.addView(frame, new LinearLayout.LayoutParams(w, h));
        final TextView label = cardText(item.title, 25, INK, 1);
        label.setPadding(0, px(14), 0, 0);
        col.addView(label, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView why = cardText(Ui.digits(pick.reason), 22, SUB_INK, 3);
        why.setPadding(0, px(4), 0, 0);
        col.addView(why, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (gap && Ui.rtl) lp.rightMargin = px(GAP);
        else if (gap) lp.leftMargin = px(GAP);
        col.setLayoutParams(lp);
        col.setOnFocusChangeListener((v, has) -> {
            float s = has ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(A11y.reduceMotion ? 0 : 160).start();
            focusRing.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            label.setTextColor(has ? 0xFFFFFFFF : INK);
            label.setTypeface(has ? Ui.semibold : Ui.medium);
        });
        col.setContentDescription(item.title + ". " + pick.reason + ". " + TonightText.get(TR, "open"));
        col.setOnClickListener(v -> open(item));
        col.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                open(item);
                return true;
            }
            return false;
        });
        return col;
    }

    private TextView cardText(String s, float size, int color, int lines) {
        TextView t = text(size, color, Ui.medium);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setGravity(hasArabic(s) ? Gravity.RIGHT : Ui.rtl ? Gravity.RIGHT : Gravity.LEFT);
        t.setText(s);
        return t;
    }

    /** The title's page in Watchme+ (by its number). */
    private void open(TonightResolver.Item item) {
        try {
            startActivity(WatchMe.open(this, item.id, item.title));
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("tonight open: " + e.getMessage());
        }
    }

    @Override public void onThumb() {
        for (int i = waiting.size() - 1; i >= 0; i--) {
            ImageView v = waiting.get(i);
            Object[] tag = (Object[]) v.getTag();
            Bitmap b = thumbs.get((String) tag[0], (String) tag[1], (Integer) tag[2], (Integer) tag[3], px(RADIUS));
            if (b != null) {
                v.setImageBitmap(b);
                waiting.remove(i);
            }
        }
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
