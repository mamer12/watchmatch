package io.watchmatch;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One search for everything: type (or say) a name once and see where it is, a row per app: Watchme+ (the catalog's
 * catalog), the streaming apps that carry it in your country (TMDB) and YouTube (when signed in). Each result opens
 * on its own page where the app allows it (Watchme+, Apple TV, Netflix, YouTube), else the app.
 * The mic next to the box (and the remote's search key) listens in the box itself (Voice: the TV's own recognizer,
 * its words appearing as they're heard); {app} keeps no audio.
 * Also opens for the system's search (ACTION_SEARCH) with its query.
 * A sentence with filters in it ("new gangster movies", "افلام عصابات جديده") goes to Spark (SparkQuery, Spark): a
 * chip for each thing it understood (OK takes one off), {catalog} first, then a row per app on the TV.
 * Every row is Home's (HomeView): its margin, posters (or wide pictures for videos and apps), labels and focus.
 */
public class SearchActivity extends Activity implements Thumbs.Listener {
    static final String EXTRA_QUERY = "io.watchmatch.search.QUERY";
    // Home's rows (1920 space): the side margin, the cards, their focus; ROOM: above and below a row for the focused one.
    private static final int M = 96, POSTER_W = 216, POSTER_H = 324, WIDE_W = 384, WIDE_H = 216, GAP = 24, RADIUS = 14;
    private static final int RING = 4, RING_OUT = 3, ROOM = 28, MIC = 88, MIC_GROW = 8;
    private static final float FOCUS_SCALE = 1.08f;
    private static final int LABEL_INK = 0xB3FFFFFF, SUB_INK = 0x80FFFFFF, PLACEHOLDER = 0xFF1E1F24, FIELD = 0xFF1C1C21;
    private static final long MESSAGE_MS = 4000;
    private static final int VOICE = 1, MIC_PERMISSION = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ImageView> waiting = new ArrayList<>();
    private EditText box;
    private ImageView mic;
    private GradientDrawable micBg;
    private ValueAnimator pulse;
    private Voice voice;
    private boolean quiet;              // the box changed by the app (voice), not typed: no search
    private boolean message;            // the box's hint is a voice message for a short while
    private String beforeVoice = "";
    private TextView status;
    private LinearLayout results;
    private HorizontalScrollView chipLine;
    private LinearLayout chips;
    private Map<String, Drawable> art = new java.util.HashMap<>();
    private Set<String> icons = new java.util.HashSet<>();
    private float k;
    private int searchNo;
    private boolean focusResults;
    private Thumbs thumbs;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        thumbs = Thumbs.get(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0B0B0D);
        // Right to left (the app runs without Android's RTL support): mirrored by hand, everything from the right margin.
        // The page clips its children: the rows (SearchRows) stay under the box and the chips when they scroll.
        page.setPadding(0, px(56 - MIC_GROW), 0, px(40));
        box = new EditText(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(FIELD);
        bg.setCornerRadius(px(40));
        box.setBackground(bg);
        box.setPadding(px(36), 0, px(36), 0);
        box.setTextColor(0xFFFFFFFF);
        box.setHintTextColor(0x80FFFFFF);
        box.setHint(hint());
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(34));
        box.setTypeface(Ui.medium);
        box.setSingleLine(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        box.setOnEditorActionListener((v, action, e) -> {
            focusResults = true;        // Enter: the keyboard goes, the first result gets the focus
            search(box.getText().toString());
            hideKeyboard();
            return true;
        });
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (quiet) return;
                main.removeCallbacks(typed);
                main.postDelayed(typed, 900);       // searching while typing, once the typing pauses
            }
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(px(M), px(MIC_GROW), px(M), px(MIC_GROW));      // room for the mic to grow while listening
        bar.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        box.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        bar.addView(box, new LinearLayout.LayoutParams(px(1100), px(MIC)));
        if (Voice.available(this)) voice = new Voice(this, heard);
        if (canListen()) {
            View m = micButton();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(MIC), px(MIC));
            if (Ui.rtl) {
                lp.rightMargin = px(20);
                bar.addView(m, 0, lp);        // the mic on the box's left
            } else {
                lp.leftMargin = px(20);
                bar.addView(m, lp);
            }
        }
        page.addView(bar);
        status = text(28, 0x99FFFFFF);
        status.setPadding(px(M), px(20), px(M), px(10));
        page.addView(status);
        chipLine = new SearchRows.Edge(this, px(M));
        chipLine.setFillViewport(true);
        chipLine.setVisibility(View.GONE);
        chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        chips.setPadding(px(M), px(8), px(M), px(8));
        chipLine.addView(chips);
        page.addView(chipLine);
        SearchRows scroll = new SearchRows(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        String q = getIntent().getStringExtra(EXTRA_QUERY);
        if (q == null && Intent.ACTION_SEARCH.equals(getIntent().getAction())) q = getIntent().getStringExtra(SearchManager.QUERY);
        if (q != null && !q.trim().isEmpty()) {
            // Sent from the phone (or the system's search): no keyboard, straight to the results.
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            searchFor(q);
        } else {
            box.requestFocus();
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
        if (voice != null) voice.release();
        main.removeCallbacks(restoreBox);
        super.onDestroy();
    }

    private final Runnable typed = () -> search(box.getText().toString());

    /** A whole query at once (the phone, the system, the voice): in the box, searched now, the first result focused. */
    private void searchFor(String q) {
        focusResults = true;
        box.setText(q);
        main.removeCallbacks(typed);
        search(q);
    }

    // MARK: - Voice

    private static String hint() {
        return Ui.tr("Filmes, séries, jogos, vídeos…", "Movies, series, matches, videos…", "Películas, series, partidos, videos…");
    }

    /** In the box (Voice), else the TV's own voice window; either way a mic to show. */
    private boolean canListen() {
        return (voice != null || !getPackageManager().queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty())
                && Symbols.get(this, "mic") != null;
    }

    /** Round, in the box's color; focused, the cards' ring; listening, lit (and breathing, unless Reduce motion). */
    private View micButton() {
        micBg = new GradientDrawable();
        micBg.setShape(GradientDrawable.OVAL);
        micBg.setColor(FIELD);
        mic = new ImageView(this);
        mic.setImageBitmap(Symbols.get(this, "mic"));
        mic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mic.setBackground(micBg);
        mic.setPadding(px(24), px(24), px(24), px(24));
        mic.setFocusable(true);
        mic.setContentDescription(Ui.tr("Buscar por voz", "Search by voice", "Buscar por voz"));
        mic.setOnFocusChangeListener((v, has) -> micLook());
        mic.setOnClickListener(v -> voice());
        micLook();
        return mic;
    }

    private void micLook() {
        if (mic == null) return;
        boolean on = voice != null && voice.listening();
        micBg.setColor(on ? Ui.accent() : FIELD);
        micBg.setStroke(mic.hasFocus() && !on ? px(RING) : 0, Ui.accent());
        mic.setColorFilter(on ? 0xFF0B0B0D : mic.hasFocus() ? 0xFFFFFFFF : 0xB3FFFFFF);
        if (on && pulse == null && !A11y.reduceMotion) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(mic, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f));
            pulse.setDuration(650);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.start();
        } else if (!on && pulse != null) {
            pulse.cancel();
            pulse = null;
            mic.setScaleX(1f);
            mic.setScaleY(1f);
        }
    }

    /** The mic or the remote's search key: listen (asking for the microphone the first time), or stop listening. */
    private void voice() {
        if (voice == null) {
            listen();
            return;
        }
        if (voice.listening()) {
            voice.cancel();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        if (message) {
            main.removeCallbacks(restoreBox);
            restoreBox.run();
        }
        main.removeCallbacks(typed);
        beforeVoice = box.getText().toString();
        quietly("");
        box.setHint(Ui.tr("Ouvindo…", "Listening…", "Escuchando…"));
        voice.start();
        micLook();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] granted) {
        if (request != MIC_PERMISSION) return;
        if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) {
            voice();
        } else {
            // Asked again on the next try; once "don't ask again", the TV's settings are the way.
            status.setText(shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ? Voice.micNeeded()
                    : Ui.tr("Para buscar por voz, permita o microfone para o {app} em Configurações > Apps da TV.",
                    "To search by voice, allow the microphone for {app} in the TV's Settings > Apps.",
                    "Para buscar por voz, permite el micrófono para {app} en Ajustes > Apps del televisor."));
        }
    }

    private final Voice.Listener heard = new Voice.Listener() {
        @Override public void heard(String soFar) {
            quietly(soFar);
        }

        @Override public void said(String sentence) {
            micLook();
            box.setHint(hint());
            searchFor(sentence);
            hideKeyboard();
        }

        @Override public void stopped(String why) {
            micLook();
            if (why == null) {
                restoreBox.run();
                return;
            }
            quietly("");
            box.setHint(why);       // a short while, then the box as it was
            message = true;
            main.postDelayed(restoreBox, MESSAGE_MS);
        }
    };

    /** After listening (or its message): the hint again, and what the box had unless something else was typed. */
    private final Runnable restoreBox = () -> {
        message = false;
        box.setHint(hint());
        if (box.getText().length() == 0) quietly(beforeVoice);
    };

    /** The box's text set by the app: no search for it. */
    private void quietly(String text) {
        quiet = true;
        box.setText(text);
        box.setSelection(text.length());
        quiet = false;
    }

    /** Without an in-app recognizer: the TV's own voice window. */
    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Diga um filme, série ou jogo", "Say a movie, series or match",
                        "Di una película, serie o partido"))
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        String lang = Voice.language();
        if (lang != null) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        try {
            startActivityForResult(i, VOICE);
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("voice search: " + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null) return;
        for (String h : heard) {
            if (h != null && !h.trim().isEmpty()) {
                searchFor(h.trim());
                hideKeyboard();
                return;
            }
        }
    }

    /** The remote's search (or voice) key listens (or stops), where the TV lets it through. */
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) && canListen()) {
            if (e.getRepeatCount() == 0) voice();
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

    @Override public void onThumb() {
        for (int i = waiting.size() - 1; i >= 0; i--) {
            ImageView v = waiting.get(i);
            Object[] tag = (Object[]) v.getTag();
            Bitmap b = thumbs.get((String) tag[0], (String) tag[1], (Integer) tag[2], (Integer) tag[3], px(14));
            if (b != null) {
                v.setImageBitmap(b);
                waiting.remove(i);
            }
        }
    }

    // MARK: - Searching

    private void search(final String query) {
        final String q = query.trim();
        if (q.length() < 2) return;
        run(q, SparkQuery.parse(q), true);
    }

    /**
     * Spark when `sq` has filters; the plain search otherwise. `check`: a sentence without "movies" or "series" in it
     * that is a title in {catalog} ("Top Gun", "New Girl") stays a plain search.
     */
    private void run(final String q, final SparkQuery sq, final boolean check) {
        final int no = ++searchNo;
        status.setText(Ui.tr("Procurando…", "Searching…", "Buscando…"));
        // Its chips at once when it's surely Spark; else once it's known it isn't a title.
        if (!sq.isSpark() || !check || sq.typed) showChips(sq.isSpark() ? sq : null, null);
        final Context c = getApplicationContext();
        io.execute(() -> {
            List<Catalog.Entry> catalog = null;
            if (!sq.isSpark() || check && !sq.typed && PlainSearch.exact(catalog = PlainSearch.watchMe(q), q)) {
                final Map<String, List<Catalog.Entry>> rows = PlainSearch.rows(c, q, catalog != null ? catalog : PlainSearch.watchMe(q));
                main.post(() -> {
                    if (no != searchNo || isFinishing()) return;
                    showChips(null, null);
                    show(q, rows);
                });
                return;
            }
            final Spark.Result r = Spark.run(c, sq);
            main.post(() -> {
                if (no != searchNo || isFinishing()) return;
                showChips(sq, r.title);
                showSpark(r);
            });
        });
    }

    /** A chip taken off: the rest again (a title alone is a plain search; nothing left, nothing shown). */
    private void rerun(SparkQuery next) {
        if (next.isEmpty()) {
            ++searchNo;
            showChips(null, null);
            results.removeAllViews();
            status.setText("");
            box.requestFocus();
            return;
        }
        focusResults = true;
        if (next.isSpark()) run(next.text, next, false);
        else run(next.title, SparkQuery.parse(next.title), false);
    }

    // MARK: - Results

    private void show(String q, Map<String, List<Catalog.Entry>> rows) {
        results.removeAllViews();
        waiting.clear();
        int total = 0;
        View first = null;
        for (Map.Entry<String, List<Catalog.Entry>> row : rows.entrySet()) {
            if (row.getValue().isEmpty()) continue;
            total += row.getValue().size();
            View card = addRow(row.getKey(), row.getValue(), "YouTube".equals(row.getKey()) ? Spark.WIDE : Spark.POSTERS);
            if (first == null) first = card;
        }
        status.setText(total == 0 ? Ui.tr("Nada encontrado para “", "Nothing found for “", "Nada encontrado para “") + Ui.isolate(q) + "”" : "");
        focusFirst(total, first);
    }

    private void showSpark(Spark.Result r) {
        results.removeAllViews();
        waiting.clear();
        art = r.art;
        icons = r.icons;
        int total = 0;
        View first = null;
        for (Spark.Row row : r.rows) {
            if (row.items.isEmpty()) continue;
            // The other apps' tiles don't count as found: they only pass the words on.
            if (row.style != Spark.APPS) total += row.items.size();
            View card = addRow(row.title, row.items, row.style);
            if (first == null) first = card;
        }
        status.setText(total == 0 ? Ui.tr("{spark} não achou nada. Tire um filtro e tente de novo.",
                "{spark} found nothing. Take a filter off and try again.") : "");
        focusFirst(total + (r.rows.isEmpty() ? 0 : 1), first);
    }

    private void focusFirst(int total, View first) {
        if (focusResults && total > 0) {
            focusResults = false;
            if (first != null && results.findFocus() == null) first.requestFocus();
        }
    }

    /** A row's title and its cards, from the margin (the right one in right to left); returns the first card. */
    private View addRow(String name, List<Catalog.Entry> items, int style) {
        TextView title = text(30, 0xF2FFFFFF);
        title.setText(name);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(px(M), px(14), px(M), 0);
        results.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        HorizontalScrollView hs = new SearchRows.Edge(this, px(M));
        final LinearLayout line = new LinearLayout(this);
        // The focused card grows, with its ring and words, into the margins and ROOM: not clipped at the padding.
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(px(M), px(ROOM), px(M), px(ROOM));
        View first = null;
        for (Catalog.Entry e : items) {
            View card = card(e, style, first != null);
            if (Ui.rtl) line.addView(card, 0);
            else line.addView(card);
            if (first == null) first = card;
        }
        if (Ui.rtl) {
            // The first result on the right, the row scrolled to it.
            line.setGravity(Gravity.RIGHT);
            hs.setFillViewport(true);
            hs.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    v.removeOnLayoutChangeListener(this);
                    if (v.findFocus() == null) v.scrollTo(line.getWidth(), 0);
                }
            });
        }
        hs.addView(line);
        results.addView(hs);
        return first;
    }

    // MARK: - Spark's chips

    /** What Spark understood, a chip each (null: none, a plain search). `title`: the title as found. */
    private void showChips(SparkQuery sq, String title) {
        chips.removeAllViews();
        if (sq == null) {
            chipLine.setVisibility(View.GONE);
            return;
        }
        chipLine.setVisibility(View.VISIBLE);
        TextView lead = text(26, 0x99FFFFFF);
        lead.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));     // on the chips' middle
        lead.setText(Ui.tr("{spark} entendeu", "{spark} understood"));
        addChip(lead);
        for (String part : sq.parts()) addChip(chip(sq, part, SparkChips.label(sq, part, title)));
        if (Ui.rtl) chipLine.post(() -> chipLine.scrollTo(chips.getWidth(), 0));
    }

    private void addChip(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(60));
        if (Ui.rtl) {
            lp.leftMargin = px(16);
            chips.addView(v, 0, lp);
        } else {
            lp.rightMargin = px(16);
            chips.addView(v, lp);
        }
    }

    /** A pill like the box; focused, white. OK takes that part off and searches again. */
    private View chip(final SparkQuery sq, final String part, String label) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(px(30));
        final TextView t = text(26, 0xFFFFFFFF);
        t.setText(label + "  ×");
        t.setGravity(Gravity.CENTER);
        t.setBackground(bg);
        t.setPadding(px(26), 0, px(26), 0);
        t.setFocusable(true);
        t.setContentDescription(label + ". " + Ui.tr("OK tira", "OK takes it off"));
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x26FFFFFF);
            t.setTextColor(has ? 0xFF0B0B0D : 0xFFFFFFFF);
        });
        t.setOnClickListener(v -> rerun(sq.without(part)));
        return t;
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
    }

    /**
     * A result, as on Home: a poster (POSTERS) or a wide photo; an app's tile (APPS) shows its TV art. Under it, on every
     * card, its name and a second line (the year, where else it streams, the channel; kept empty so the rows line up).
     * `gap`: not the row's first card.
     */
    private View card(final Catalog.Entry e, int style, boolean gap) {
        boolean poster = style == Spark.POSTERS;
        final int w = px(poster ? POSTER_W : WIDE_W), h = px(poster ? POSTER_H : WIDE_H), radius = px(RADIUS);
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
        if (style == Spark.APPS) {
            Drawable d = art.get(e.pkg);
            if (icons.contains(e.pkg)) {
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                img.setPadding(px(56), px(48), px(56), px(48));
            }
            if (d != null) img.setImageDrawable(d);
        } else {
            img.setTag(new Object[] {Thumbs.key(e.image, w, h, radius), e.image, w, h});
            Bitmap b = thumbs.get((String) ((Object[]) img.getTag())[0], e.image, w, h, radius);
            if (b != null) img.setImageBitmap(b);
            else waiting.add(img);
        }
        frame.addView(img, new FrameLayout.LayoutParams(w, h));
        // Home's ring: 3 px outside the picture.
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
        final TextView label = cardText(e.title, 25, LABEL_INK);
        label.setPadding(0, px(14), 0, 0);
        col.addView(label, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = cardText(e.sub == null || "0".equals(e.sub) || "null".equals(e.sub) ? "" : e.sub, 22, SUB_INK);
        sub.setPadding(0, px(4), 0, 0);
        col.addView(sub, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (gap && Ui.rtl) lp.rightMargin = px(GAP);       // right to left: each card left of the one before
        else if (gap) lp.leftMargin = px(GAP);
        col.setLayoutParams(lp);
        col.setOnFocusChangeListener((v, has) -> {
            float s = has ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(A11y.reduceMotion ? 0 : 160).start();
            focusRing.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            label.setTextColor(has ? 0xFFFFFFFF : LABEL_INK);
            label.setTypeface(has ? Ui.semibold : Ui.medium);
        });
        col.setOnClickListener(v -> open(e));
        col.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                open(e);
                return true;
            }
            return false;
        });
        return col;
    }

    /**
     * A card's line, Home's way: at the card's start edge for the screen's direction (right in right to left), each
     * text in its own direction and cut at its end; so a Latin title in Arabic is right-aligned like the Arabic ones.
     */
    private TextView cardText(String s, float size, int color) {
        TextView t = text(size, color);
        t.setTypeface(Ui.medium);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(s);
        return t;
    }

    /** Its own page where the app allows it (Watchme+ by number, Apple TV and Netflix by link, YouTube), else the app. */
    private void open(final Catalog.Entry e) {
        if (Endpoints.PKG.equals(e.pkg)) {
            start(WatchMe.open(this, e.id, e.query != null ? e.query : e.title));
        } else if (e.video != null) {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + e.video)).setPackage(e.pkg));
        } else if (Links.service(e.pkg) != null && e.tmdb != null) {
            io.execute(() -> {
                final String link = Links.find(e.title, e.tmdb, e.pkg);
                main.post(() -> start(link != null ? new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(e.pkg)
                        : e.search != null ? Spark.searchIntent(e.search, e.query)
                        : HomeApps.launchIntent(getPackageManager(), e.pkg)));
            });
            return;
        } else if (e.search != null) {
            start(Spark.searchIntent(e.search, e.query));       // Spark: the app's own search, with the title
        } else {
            start(HomeApps.launchIntent(getPackageManager(), e.pkg));
        }
        if (!Endpoints.PKG.equals(e.pkg)) Watched.add(this, e);
    }

    private void start(Intent i) {
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent app = HomeApps.launchIntent(getPackageManager(), i.getPackage() == null ? "" : i.getPackage());
            if (app != null) startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(Ui.semibold);
        // Absolute: START follows each text's own direction here (no RTL support), so an Arabic title went right in LTR.
        t.setGravity(Ui.rtl ? Gravity.RIGHT : Gravity.LEFT);
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
