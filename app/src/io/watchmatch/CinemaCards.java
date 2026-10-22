package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The cards for CinemaView. In a row, a card is the title's poster with its name under it (in a Top 10 row, the rank
 * in big digits beside it). The focused one grows into a wide tile, as on Netflix's TV app: the poster gives way to
 * the wide picture (TMDB's photo with the logo over it, or the catalog's cover, which has its name in it), the preview
 * plays inside it and CinemaView puts the title's words under it. Elsewhere (More Like This) a card is that wide
 * picture at CARD_W × CARD_H.
 */
final class CinemaCards {
    /** One card of a row. */
    static final class Card {
        Library.Title title;
        Progress.Entry progress;
        String poster = "";
        // Made once, when first drawn: the pictures' keys and source, the names fitted.
        String thumbKey, wideKey, source = "", label, posterLabel;
        /** 1–10 in a Top 10 row (the big number beside the poster), else 0. */
        int rank;
        String rankText, posterKey;
        /** "Continued from Phone": the newest position came from another device (Sync), else null; fromLabel is it fitted. */
        String fromText, fromLabel;
        /** How far it has grown into the focused tile (0..1): each card its own, so fast moves never jump. */
        final Spring grow = new Spring(CinemaView.MOTION, 1f, 0);
        /** When its wide picture was first there (it fades in from then). */
        long wideAt;

        /** The catalog's cover (banner titles), else the wide photo once TMDB has it, else the poster. */
        String image() {
            if (!title.cover.isEmpty()) return title.cover;
            return title.backdrop != null && !title.backdrop.isEmpty() ? title.backdrop : poster;
        }

        /** The picture has the name in it already (a cover), or the logo goes over it. */
        boolean namedArt() {
            return !title.cover.isEmpty() || (title.backdrop != null && !title.backdrop.isEmpty() && !title.logo.isEmpty());
        }
    }

    static final float CARD_W = 338, CARD_H = 190, TILE_W = 688, TILE_H = 387, POSTER_W = 200, POSTER_H = 300, RANK_W = 116;
    private static final int PLACEHOLDER = 0xFF232327;
    private static final long WIDE_FADE_MS = 220;

    private final CinemaView v;
    private final Thumbs thumbs;
    private final CinemaLogos logos;
    private final Library library;
    private final Runnable arrived;
    private Paint nameFont, rankFont, rankEdge, labelFont, fromFont;
    private TextPaint nameText, labelText, fromText;
    private Shader nameShade;
    private final RectF art = new RectF(), logoRect = new RectF();
    private final Rect src = new Rect();

    CinemaCards(CinemaView v, Library library, Thumbs thumbs, CinemaLogos logos, Runnable arrived) {
        this.v = v;
        this.library = library;
        this.thumbs = thumbs;
        this.logos = logos;
        this.arrived = arrived;
    }

    void sizeChanged(float k) {
        nameFont = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
        nameText = new TextPaint(nameFont);
        labelFont = Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF);
        labelText = new TextPaint(labelFont);
        fromFont = Ui.text(Ui.medium, 17 * k, 0xE6FFFFFF);
        fromFont.setShadowLayer(4 * k, 0, 1.5f * k, 0xCC000000);
        fromText = new TextPaint(fromFont);
        // Netflix's Top 10 digits: tall, dark, a light outline; right-aligned against the poster (right to left: on
        // its right, left-aligned).
        Paint.Align rankAlign = Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
        rankFont = Ui.text(Ui.displaySemibold, 250 * k, 0xFF141414);
        rankFont.setTextAlign(rankAlign);
        rankFont.setLetterSpacing(-0.08f);
        rankEdge = Ui.text(Ui.displaySemibold, 250 * k, 0xFF8C8C8C);
        rankEdge.setTextAlign(rankAlign);
        rankEdge.setLetterSpacing(-0.08f);
        rankEdge.setStyle(Paint.Style.STROKE);
        rankEdge.setStrokeWidth(3 * k);
        nameShade = new LinearGradient(0, 0.45f, 0, 1, 0, 0xD9000000, Shader.TileMode.CLAMP);
    }

    /** A card's width in a row (1920 space): the poster, and the rank's digits beside it in a Top 10 row. */
    static float width(Card card) {
        return POSTER_W + (card.rank > 0 ? RANK_W : 0);
    }

    /** Where card `card` at (x, top) is at growth `g`: its poster's place at 0, the tile's (from x) at 1. */
    void place(Card card, float x, float top, float g, RectF out) {
        float k = v.k, left = x + (card.rank > 0 ? RANK_W * k : 0);
        out.set(lerp(left, x, g), top, lerp(left + POSTER_W * k, x + TILE_W * k, g),
                top + lerp(POSTER_H, TILE_H, g) * k);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // MARK: - In a row

    /**
     * A row's card in `r` (place()) at growth `g`: the poster, cropped to the shape it's at, giving way to the wide
     * picture as it grows; both at `picture` (0 once the preview plays in the hole CinemaView left there), the logo
     * and progress over them, the name under the poster and the rank beside it going as it grows, and (focused) the
     * white outline.
     */
    void drawTile(Canvas c, Card card, RectF r, float g, float alpha, float picture, boolean outlined) {
        float k = v.k;
        if (card.title.backdrop == null) library.backdrop(card.title, arrived);    // once per title, cached
        float wide = Ui.clamp(g * 1.6f);
        if (card.rank > 0 && wide < 1) drawRank(c, card, Ui.rtl ? r.right : r.left, r.top + POSTER_H * k, alpha * (1 - wide));
        Bitmap wideArt = g > 0.01f ? wideBitmap(card) : null;
        if (wideArt != null && card.wideAt == 0) card.wideAt = SystemClock.uptimeMillis();
        if (wideArt != null) {
            float fade = Ui.clamp((SystemClock.uptimeMillis() - card.wideAt) / (float) WIDE_FADE_MS);
            if (fade < 1) v.kick();
            wide *= fade;
        } else {
            wide = 0;
        }
        if (wide < 1) {
            Bitmap poster = posterBitmap(card);
            if (poster != null) drawCropped(c, poster, r, alpha * picture);
            else if (picture > 0.01f) {
                v.fill.setColor(Ui.alpha(PLACEHOLDER, alpha * picture));
                c.drawRoundRect(r, CinemaView.RADIUS * k, CinemaView.RADIUS * k, v.fill);
            }
        }
        if (wide > 0.01f) {
            drawCropped(c, wideArt, r, alpha * picture * wide);
            if (!card.title.cover.isEmpty()) {
                // The catalog's cover has its name in it.
            } else if (card.namedArt()) {
                drawLogo(c, card.title.logo, r, alpha * wide * picture);      // gone once the video plays
            } else {
                drawName(c, card, r, alpha * wide * picture, TILE_W);
            }
        }
        if (card.progress != null && !card.progress.upNext) v.drawProgress(c, r, card.progress.fraction(), alpha);
        float named = 1 - Ui.clamp(g * 2.5f);
        if (named > 0.01f) drawPosterLabel(c, card, Ui.rtl ? r.right : r.left, r.bottom, alpha * named);
        if (named > 0.01f && card.fromText != null) drawFrom(c, card, r, alpha * named);
        if (!outlined) return;
        v.stroke.setStrokeWidth(4 * k);
        v.stroke.setColor(Ui.alpha(0xFFFFFFFF, alpha));
        v.rect.set(r.left - 5 * k, r.top - 5 * k, r.right + 5 * k, r.bottom + 5 * k);
        c.drawRoundRect(v.rect, CinemaView.RADIUS * k + 5 * k, CinemaView.RADIUS * k + 5 * k, v.stroke);
    }

    private Bitmap posterBitmap(Card card) {
        if (card.poster.isEmpty()) return null;
        float k = v.k;
        int pw = Math.round(POSTER_W * k), ph = Math.round(POSTER_H * k);
        if (card.posterKey == null) card.posterKey = Thumbs.key(card.poster, pw, ph, CinemaView.RADIUS * k);
        return thumbs.get(card.posterKey, card.poster, pw, ph, CinemaView.RADIUS * k);
    }

    /** The wide picture at the tile's size (only asked for once the card starts growing). */
    private Bitmap wideBitmap(Card card) {
        String source = card.image();
        if (source.isEmpty()) return null;
        if (!source.equals(card.source)) {
            card.source = source;
            card.thumbKey = card.wideKey = null;
            card.label = null;
            card.wideAt = 0;
        }
        float k = v.k;
        int tw = Math.round(TILE_W * k), th = Math.round(TILE_H * k);
        if (card.wideKey == null) card.wideKey = Thumbs.key(source, tw, th, CinemaView.RADIUS * k);
        return thumbs.get(card.wideKey, source, tw, th, CinemaView.RADIUS * k);
    }

    /** The bitmap filling `r` without stretching: the middle of it, cut to r's shape. */
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
        v.image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(b, src, r, v.image);
        v.image.setAlpha(255);
    }

    /** The rank's big digits, against the poster's left edge (right to left: its right edge), standing on its bottom. */
    private void drawRank(Canvas c, Card card, float posterLeft, float bottom, float alpha) {
        if (alpha <= 0.01f) return;
        float k = v.k;
        if (card.rankText == null) card.rankText = String.valueOf(card.rank);
        rankFont.setAlpha(Math.round(255 * alpha));
        rankEdge.setAlpha(Math.round(200 * alpha));
        float at = Ui.rtl ? posterLeft - 22 * k : posterLeft + 22 * k;
        c.drawText(card.rankText, at, bottom + 6 * k, rankFont);
        c.drawText(card.rankText, at, bottom + 6 * k, rankEdge);
    }

    /** The name under the poster, as on the home screen's rows (`left`: its start edge, the right one in right to left). */
    private void drawPosterLabel(Canvas c, Card card, float left, float bottom, float alpha) {
        float k = v.k;
        if (card.posterLabel == null) {
            String name = card.progress != null && !card.progress.rootTitle.isEmpty() ? card.progress.rootTitle : card.title.title;
            card.posterLabel = TextUtils.ellipsize(name, labelText, POSTER_W * k, TextUtils.TruncateAt.END).toString();
        }
        labelFont.setAlpha(Math.round(179 * alpha));
        Ui.textAt(c, card.posterLabel, left, bottom + 34 * k, labelFont);
    }

    /** A small line over the poster's foot, above the progress bar: where the newest position came from (Sync). */
    private void drawFrom(Canvas c, Card card, RectF r, float alpha) {
        float k = v.k, inset = 14 * k;
        if (card.fromLabel == null) {
            card.fromLabel = TextUtils.ellipsize(card.fromText, fromText, r.width() - 2 * inset, TextUtils.TruncateAt.END).toString();
        }
        fromFont.setAlpha(Math.round(230 * alpha));
        Ui.textAt(c, card.fromLabel, Ui.rtl ? r.right - inset : r.left + inset, r.bottom - inset - 5 * k - 12 * k, fromFont);
    }

    // MARK: - Elsewhere (More Like This)

    /** The card at (x, top) as wide box art, CARD_W × CARD_H; `alpha` for cards set back. */
    void draw(Canvas c, Card card, float x, float top, float alpha) {
        float k = v.k;
        art.set(x, top, x + CARD_W * k, top + CARD_H * k);
        boxArt(c, card, art, alpha);
    }

    /** The picture, the logo (or the name), the progress. */
    private void boxArt(Canvas c, Card card, RectF r, float alpha) {
        float k = v.k;
        if (card.title.backdrop == null) library.backdrop(card.title, arrived);    // once per title, cached
        String source = card.image();
        if (!source.equals(card.source)) {
            card.source = source;
            card.thumbKey = card.wideKey = null;
            card.label = null;
            card.wideAt = 0;
        }
        int cw = Math.round(CARD_W * k), ch = Math.round(CARD_H * k);
        if (card.thumbKey == null) card.thumbKey = Thumbs.key(source, cw, ch, CinemaView.RADIUS * k);
        Bitmap b = source.isEmpty() ? null : thumbs.get(card.thumbKey, source, cw, ch, CinemaView.RADIUS * k);
        if (b != null) {
            v.image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, null, r, v.image);
            v.image.setAlpha(255);
        } else {
            v.fill.setColor(Ui.alpha(PLACEHOLDER, alpha));
            c.drawRoundRect(r, CinemaView.RADIUS * k, CinemaView.RADIUS * k, v.fill);
        }
        if (!card.title.cover.isEmpty()) {
            // The catalog's cover has its name in it.
        } else if (card.namedArt()) {
            drawLogo(c, card.title.logo, r, alpha);
        } else {
            drawName(c, card, r, alpha, CARD_W);
        }
        if (card.progress != null && !card.progress.upNext) v.drawProgress(c, r, card.progress.fraction(), alpha);
    }

    /** The logo at the bottom left (right to left: right), fitted into a part of the picture (never cropped). */
    private void drawLogo(Canvas c, String url, RectF r, float alpha) {
        Bitmap logo = logos.get(url);
        if (logo == null) return;
        float maxW = r.width() * 0.56f, maxH = r.height() * 0.42f, pad = r.width() * 0.045f;
        float s = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
        if (Ui.rtl) logoRect.set(r.right - pad - lw, r.bottom - pad - lh, r.right - pad, r.bottom - pad);
        else logoRect.set(r.left + pad, r.bottom - pad - lh, r.left + pad + lw, r.bottom - pad);
        v.image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(logo, null, logoRect, v.image);
        v.image.setAlpha(255);
    }

    /** The name on a darkened bottom, for pictures without one. */
    private void drawName(Canvas c, Card card, RectF r, float alpha, float width) {
        float k = v.k;
        if (card.label == null) {
            String name = card.progress != null && !card.progress.rootTitle.isEmpty() ? card.progress.rootTitle : card.title.title;
            card.label = TextUtils.ellipsize(name, nameText, (width - 36) * k, TextUtils.TruncateAt.END).toString();
        }
        c.save();
        c.translate(r.left, r.top);
        c.scale(r.width(), r.height());
        v.fill.setShader(nameShade);
        v.fill.setAlpha(Math.round(255 * alpha));
        c.drawRect(0, 0, 1, 1, v.fill);
        v.fill.setShader(null);
        v.fill.setAlpha(255);
        c.restore();
        nameFont.setAlpha(Math.round(255 * alpha));
        float bottom = card.progress != null ? 36 * k : 18 * k;
        Ui.textAt(c, card.label, Ui.rtl ? r.right - 18 * k : r.left + 18 * k, r.bottom - bottom, nameFont);
    }
}
