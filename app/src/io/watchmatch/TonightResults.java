package io.watchmatch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Ask Spark's results, drawn two ways. STYLE_CATALOG (Watchme+): the titles themselves, a grid of posters that scrolls
 * down. STYLE_APPS (WatchMatch): a row of posters for each app that has them ("Watchme+", "On Shahid"…), as search shows
 * them. Both under a line of filter chips (OK takes one off). Cards are built once per result: nothing is allocated
 * while the person scrolls. Right to left: the first card on the right.
 */
final class TonightResults {
    /** What OK on a card or a chip does. */
    interface Host {
        void open(Catalog.Entry entry);

        void dropPart(String part);
    }

    private static final int M = 96, GAP = 24, RADIUS = 14, RING = 4, RING_OUT = 3, ROOM = 30, INSET = 10;
    private static final int GRID_W = 250, GRID_H = 375, GRID_COLS = 6, ROW_W = 216, ROW_H = 324;
    private static final float FOCUS_SCALE = 1.06f;
    private static final int LABEL_INK = 0xB3FFFFFF, SUB_INK = 0x80FFFFFF, PLACEHOLDER = 0xFF1E1F24;

    private final Activity a;
    private final float k;
    private final Thumbs thumbs;
    private final Host host;
    private final boolean apps;
    private final List<ImageView> waiting = new ArrayList<>();
    private final HorizontalScrollView chipLine;
    private final LinearLayout chips, list;
    private final ScrollView scroll;

    TonightResults(Activity a, float k, Thumbs thumbs, Host host, boolean apps) {
        this.a = a;
        this.k = k;
        this.thumbs = thumbs;
        this.host = host;
        this.apps = apps;
        chipLine = new SearchRows.Edge(a, px(M));
        chipLine.setFillViewport(true);
        chips = new LinearLayout(a);
        chips.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        chips.setPadding(px(M), px(6), px(M), px(10));
        chipLine.addView(chips);
        chipLine.setVisibility(View.GONE);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll = apps ? new SearchRows(a) : new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.addView(list);
        scroll.setVisibility(View.GONE);
    }

    View chipLine() {
        return chipLine;
    }

    View scroll() {
        return scroll;
    }

    boolean visible() {
        return scroll.getVisibility() == View.VISIBLE;
    }

    void hide() {
        list.removeAllViews();
        chips.removeAllViews();
        waiting.clear();
        scroll.setVisibility(View.GONE);
        chipLine.setVisibility(View.GONE);
    }

    // MARK: - Text

    /** "12 titles found", or what was asked when it is few or none: "Only 4 titles found: Arabic · Before 2000". */
    static String headline(int n, TonightProtocol.Filters used, boolean relaxed) {
        String asked = TextUtils.join(" · ", labels(used));
        boolean few = n < TonightResolver.RESULT_LIMIT && (!used.lang.isEmpty() || used.minYear > 0 || used.maxYear > 0);
        String count = Ui.digits(String.valueOf(n));
        String line;
        if (n == 0) {
            line = Ui.tr("Nenhum título encontrado", "No titles found", "Ningún título encontrado", "ما لقيت أي عنوان");
        } else if (few) {
            line = Ui.tr("Só " + count + " títulos encontrados", "Only " + count + (n == 1 ? " title" : " titles") + " found",
                    "Solo " + count + " títulos encontrados", "لقيت " + count + " عناوين بس");
        } else {
            line = count + Ui.tr(" títulos encontrados", n == 1 ? " title found" : " titles found", " títulos encontrados",
                    " عنوان");
        }
        if ((n == 0 || few) && !asked.isEmpty()) line += ": " + asked;
        if (relaxed) {
            line += "  ·  " + Ui.tr("Gêneros ou tempo afrouxados", "Genres or time loosened", "Géneros o tiempo aflojados",
                    "خففنا الأنواع أو الوقت");
        }
        return line;
    }

    /** One word each for the filters ("Arabic", "Before 2000", "Comedy"), in order. */
    static List<String> labels(TonightProtocol.Filters f) {
        SparkQuery q = f.query();
        List<String> out = new ArrayList<>();
        for (String part : f.parts()) out.add(label(f, q, part));
        return out;
    }

    private static String label(TonightProtocol.Filters f, SparkQuery q, String part) {
        if (part.equals("minutes")) {
            return Ui.tr("Até " + f.maxMinutes + " min", "Up to " + f.maxMinutes + " min", "Hasta " + f.maxMinutes + " min",
                    "حتى " + f.maxMinutes + " دقيقة");
        }
        return SparkChips.label(q, part.replace("sci-fi", "scifi"), null);
    }

    // MARK: - The chips

    /** The filters that gave the results, a chip each (none: no line). */
    void showChips(TonightProtocol.Filters used) {
        chips.removeAllViews();
        List<String> parts = used.parts();
        chipLine.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
        if (parts.isEmpty()) return;
        SparkQuery q = used.query();
        TextView lead = text(26, 0x99FFFFFF, Ui.semibold);
        lead.setGravity(Gravity.CENTER_VERTICAL | (Ui.rtl ? Gravity.RIGHT : Gravity.LEFT));
        lead.setText(Ui.tr("{spark} entendeu", "{spark} understood"));
        addChip(lead);
        for (String part : parts) addChip(chip(part, label(used, q, part)));
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

    private View chip(final String part, String label) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(px(30));
        final TextView t = text(26, 0xFFFFFFFF, Ui.semibold);
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
        t.setOnClickListener(v -> host.dropPart(part));
        return t;
    }

    // MARK: - The results

    /** Watchme+ (the titles themselves): a grid, six across, scrolling down. Returns the first card. */
    View showGrid(List<Catalog.Entry> items) {
        list.removeAllViews();
        waiting.clear();
        scroll.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
        View first = null;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % GRID_COLS == 0) {
                row = new LinearLayout(a);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(px(M), px(ROOM / 2), px(M), px(ROOM / 2));
                row.setGravity(Ui.rtl ? Gravity.RIGHT : Gravity.LEFT);
                list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            View card = card(items.get(i), GRID_W, GRID_H, i % GRID_COLS > 0);
            if (Ui.rtl) row.addView(card, 0);
            else row.addView(card);
            if (first == null) first = card;
        }
        return first;
    }

    /** WatchMatch (the apps): a title and a row of posters for each app that has some. Returns the first card. */
    View showRows(List<Spark.Row> rows) {
        list.removeAllViews();
        waiting.clear();
        scroll.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
        View first = null;
        for (Spark.Row r : rows) {
            if (r.items.isEmpty() || r.style == Spark.APPS) continue;
            View card = addRow(r.title, r.items);
            if (first == null) first = card;
        }
        return first;
    }

    private View addRow(String name, List<Catalog.Entry> items) {
        TextView title = text(30, 0xF2FFFFFF, Ui.semibold);
        title.setText(name);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(px(M), px(14), px(M), 0);
        list.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        HorizontalScrollView hs = new SearchRows.Edge(a, px(M));
        final LinearLayout line = new LinearLayout(a);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(px(M), px(ROOM), px(M), px(ROOM));
        View first = null;
        for (Catalog.Entry e : items) {
            View card = card(e, ROW_W, ROW_H, first != null);
            if (Ui.rtl) line.addView(card, 0);
            else line.addView(card);
            if (first == null) first = card;
        }
        if (Ui.rtl) {
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
        list.addView(hs);
        return first;
    }

    /** A poster as in search: its title, and under it the year. OK opens it. */
    private View card(final Catalog.Entry e, int pw, int ph, boolean gap) {
        final int w = px(pw), h = px(ph), radius = px(RADIUS);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true);
        col.setClipChildren(false);
        FrameLayout frame = new FrameLayout(a);
        frame.setClipChildren(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PLACEHOLDER);
        bg.setCornerRadius(radius);
        frame.setBackground(bg);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        if (e.image != null) {
            String key = Thumbs.key(e.image, w, h, radius);
            img.setTag(new Object[] {key, e.image, w, h});
            Bitmap b = thumbs.get(key, e.image, w, h, radius);
            if (b != null) img.setImageBitmap(b);
            else waiting.add(img);
        }
        frame.addView(img, new FrameLayout.LayoutParams(w, h));
        int out = px(RING_OUT);
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + out);
        ring.setStroke(px(RING), Ui.accent());
        final View focusRing = new View(a);
        focusRing.setBackground(ring);
        focusRing.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(w + 2 * out, h + 2 * out);
        rp.leftMargin = rp.topMargin = -out;
        frame.addView(focusRing, rp);
        col.addView(frame, new LinearLayout.LayoutParams(w, h));
        // The words keep a little room at both sides: grown on focus they are never cut at the card's edge.
        final TextView label = cardText(e.title, 25, LABEL_INK);
        label.setPadding(px(INSET), px(14), px(INSET), 0);
        col.addView(label, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        String sub = e.sub == null || "0".equals(e.sub) || "null".equals(e.sub) ? "" : Ui.digits(e.sub);
        TextView subLine = cardText(sub, 22, SUB_INK);
        subLine.setPadding(px(INSET), px(4), px(INSET), 0);
        col.addView(subLine, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (gap && Ui.rtl) lp.rightMargin = px(GAP);
        else if (gap) lp.leftMargin = px(GAP);
        col.setLayoutParams(lp);
        col.setOnFocusChangeListener((v, has) -> {
            float s = has ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(A11y.reduceMotion ? 0 : 160).start();
            focusRing.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            label.setTextColor(has ? 0xFFFFFFFF : LABEL_INK);
            label.setTypeface(has ? Ui.semibold : Ui.medium);
        });
        col.setContentDescription(e.title + (sub.isEmpty() ? "" : ", " + sub));
        col.setOnClickListener(v -> host.open(e));
        col.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                host.open(e);
                return true;
            }
            return false;
        });
        return col;
    }

    private TextView cardText(String s, float size, int color) {
        TextView t = text(size, color, Ui.medium);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setText(s);
        return t;
    }

    /** Posters that arrived late. */
    void onThumb() {
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

    private TextView text(float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(a);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(face);
        t.setGravity(Ui.rtl ? Gravity.RIGHT : Gravity.LEFT);
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
