package io.watchmatch;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A title's page, as on Netflix's TV app: over its picture (and preview), its logo or name, the match, year, age rating,
 * seasons or length, the story and the cast, and a column of actions: Play or Resume, Restart, Episodes, More Like
 * This, My List, Rate, Trailer. Episodes opens the seasons and their episodes (picture, length, progress, ✓); More Like
 * This, a grid of titles of the same kind. Drawn by CinemaView, in its 1920×1080 space.
 */
final class CinemaDetails {
    private static final int M_MAIN = 0, M_EPISODES = 1, M_MORE = 2;
    private static final int B_PLAY = 0, B_RESTART = 1, B_EPISODES = 2, B_MORE = 3, B_LIST = 4, B_RATE = 5, B_TRAILER = 6;
    private static final float ACTIONS_Y = 600, ACTION_H = 62, ACTION_W = 520, EP_X = 560, EP_ROW = 214, EP_THUMB_W = 320,
            EP_THUMB_H = 180, MORE_TOP = 230, MORE_COLUMNS = 4;
    private static final int GREEN = 0xFF46D369;
    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    private final CinemaView v;
    private final Library library;
    private final Thumbs thumbs;
    private final CinemaView.Host host;

    private Library.Title title;
    private String poster = "";
    private boolean open, loadingEpisodes, loadingMore;
    private int mode = M_MAIN, action, season, episodeFocus, episodeTop, moreFocus, moreTop;
    private boolean inSeasons;
    private final List<Integer> actions = new ArrayList<>();
    private final List<String> actionLabels = new ArrayList<>();
    private Library.Title target;        // what Play starts
    private boolean targetResumes;
    private int rating;

    // Episodes: all of them, the seasons, and the ones of the picked season (labels made once)
    private List<Library.Title> episodes = Collections.emptyList();
    private final List<Integer> seasons = new ArrayList<>();
    private final List<Library.Title> shown = new ArrayList<>();
    private final List<String> seasonLabels = new ArrayList<>(), epLabels = new ArrayList<>(), epMeta = new ArrayList<>(),
            epKeys = new ArrayList<>();
    private final List<Float> epProgress = new ArrayList<>();
    // More Like This
    private final List<CinemaCards.Card> more = new ArrayList<>();

    private StaticLayout nameLayout, story;
    private float nameRight, storyRight;    // where their lines end (right to left: anchored at the right margin)
    private Library.Info info = new Library.Info();
    private String castLine = "", seasonsText = "";
    private boolean inList;
    private Paint badgeFont, matchFont, actionFont, castFont, epTitleFont, epMetaFont, headFont;
    private TextPaint castText;
    private final RectF logoRect = new RectF();

    CinemaDetails(CinemaView v, Library library, Thumbs thumbs, CinemaView.Host host) {
        this.v = v;
        this.library = library;
        this.thumbs = thumbs;
        this.host = host;
    }

    boolean isOpen() {
        return open;
    }

    /** Episodes or More Like This covers the page (and the preview's subtitles). */
    boolean covered() {
        return open && mode != M_MAIN;
    }

    String titleName() {
        return title == null ? "" : title.title;
    }

    void open(final Library.Title t, String posterUrl) {
        title = t;
        poster = posterUrl == null ? t.poster : posterUrl;
        open = true;
        mode = M_MAIN;
        action = 0;
        episodes = Collections.emptyList();
        seasons.clear();
        shown.clear();
        more.clear();
        seasonsText = "";
        info = new Library.Info();
        castLine = "";
        inList = CinemaPrefs.inMyList(v.getContext(), t.id);
        rating = CinemaPrefs.rating(v.getContext(), t.id);
        library.info(t.id, i -> {
            if (title != t) return;
            info = i;
            castLine = cast(i);
            sizeChanged();
            refresh();
        });
        v.art.show(t, poster);
        library.backdrop(t, () -> {
            if (title == t) v.art.show(t, poster);
            v.invalidate();
        });
        library.warmPlayback(t);
        // The still picture first; the title's video starts inside the same hero after a moment (a preview already
        // playing for this title just carries on, a reduced-motion setting or previews off keep the picture).
        boolean go = HeroRules.shouldStartPreview(A11y.reduceMotion, CinemaPrefs.previews(v.getContext()) == CinemaPrefs.PREVIEW_OFF,
                true, true, HeroRules.DELAY_MS, false);
        host.preview(go ? t : null, null, HeroRules.DELAY_MS);
        if (t.series) {
            loadingEpisodes = true;
            library.episodes(t.id, list -> {
                if (title != t) return;
                loadingEpisodes = false;
                episodes = list;
                refresh();
            });
        }
        sizeChanged();
        refresh();
    }

    private void close() {
        open = false;
        title = null;
        host.preview(null, null);
        v.detailsClosed();
        v.invalidate();
        v.kick();
    }

    /** After the player: the actions and the episodes' bars show the new progress. */
    void refresh() {
        if (!open) return;
        chooseTarget();
        actions.clear();
        actionLabels.clear();
        add(B_PLAY, playLabel());
        if (targetResumes) add(B_RESTART, title.series ? Ui.tr("Episódio do início", "Restart episode", "Episodio desde el inicio")
                : Ui.tr("Do início", "Restart", "Desde el inicio"));
        if (title.series && !episodes.isEmpty()) add(B_EPISODES, Ui.tr("Episódios", "Episodes", "Episodios"));
        add(B_MORE, Ui.tr("Títulos semelhantes", "More Like This", "Más títulos similares"));
        add(B_LIST, inList ? Ui.tr("Na minha lista", "In My List", "En mi lista") : Ui.tr("Minha lista", "My List", "Mi lista"));
        add(B_RATE, rateLabel());
        if (!info.trailer.isEmpty()) add(B_TRAILER, Ui.tr("Trailer", "Trailer", "Tráiler"));
        action = Math.min(action, actions.size() - 1);
        seasons.clear();
        seasonLabels.clear();
        for (Library.Title e : episodes) {
            if (seasons.contains(e.season)) continue;
            seasons.add(e.season);
            seasonLabels.add(Ui.tr("Temporada ", "Season ", "Temporada ") + Math.max(1, e.season));
        }
        if (title.series && !seasons.isEmpty()) {
            seasonsText = seasons.size() == 1 ? episodes.size() + Ui.tr(" episódios", " Episodes", " episodios")
                    : seasons.size() + Ui.tr(" temporadas", " Seasons", " temporadas");
        }
        if (target != null && target.series) season = Math.max(0, seasons.indexOf(target.season));
        pickSeason(false);
        v.invalidate();
        v.kick();
    }

    private void add(int id, String label) {
        actions.add(id);
        actionLabels.add(label);
    }

    private String rateLabel() {
        if (rating == CinemaPrefs.RATE_LOVE) return Ui.tr("Amei", "Love this!", "¡Me encanta!");
        if (rating == CinemaPrefs.RATE_LIKE) return Ui.tr("Gostei", "I like this", "Me gusta");
        if (rating == CinemaPrefs.RATE_DISLIKE) return Ui.tr("Não é para mim", "Not for me", "No es para mí");
        return Ui.tr("Avaliar", "Rate", "Calificar");
    }

    /** Movie: itself. Series: the episode being watched, the one after the last finished, or the first. */
    private void chooseTarget() {
        targetResumes = false;
        if (!title.series) {
            target = title;
            targetResumes = Progress.resumeAt(v.getContext(), title.id) > 0;
            return;
        }
        int[] next = Progress.nextEpisode(v.getContext(), episodes);
        target = next[0] < 0 ? null : episodes.get(next[0]);
        targetResumes = next[1] == 1;
    }

    private String playLabel() {
        if (target == null) return Ui.tr("Carregando…", "Loading…", "Cargando…");
        String which = target.series && target.season > 0 ? "  S" + target.season + ":E" + target.episode : "";
        return (targetResumes ? Ui.tr("Continuar", "Resume", "Continuar") : Ui.tr("Assistir", "Play", "Reproducir")) + which;
    }

    private void pickSeason(boolean resetFocus) {
        shown.clear();
        epLabels.clear();
        epMeta.clear();
        epKeys.clear();
        epProgress.clear();
        int s = seasons.isEmpty() ? 0 : seasons.get(Math.min(season, seasons.size() - 1));
        for (Library.Title e : episodes) {
            if (e.season != s) continue;
            shown.add(e);
            epLabels.add(Math.max(1, e.episode) + ".  " + Ui.tr("Episódio ", "Episode ", "Episodio ") + Math.max(1, e.episode));
            epMeta.add(e.minutes > 0 ? e.minutes + Ui.tr(" min", "m", " min") : "");
            String pic = episodePicture(e);
            epKeys.add(Thumbs.key(pic, Math.round(EP_THUMB_W * v.k), Math.round(EP_THUMB_H * v.k), CinemaView.RADIUS * v.k));
            Progress.Entry p = Progress.get(v.getContext(), e.id);
            epProgress.add(p == null || p.upNext ? 0f : p.fraction());
        }
        if (resetFocus || episodeFocus >= shown.size()) episodeFocus = 0;
        if (!resetFocus && target != null) {
            int i = shown.indexOf(target);
            if (i >= 0) episodeFocus = i;
        }
        episodeTop = Math.max(0, episodeFocus - 1);
    }

    /** An episode's picture: the series' wide photo (episodes have only posters), else the episode's poster. */
    private String episodePicture(Library.Title e) {
        if (title.backdrop != null && !title.backdrop.isEmpty()) return title.backdrop;
        return e.poster.isEmpty() ? title.poster : e.poster;
    }

    void sizeChanged() {
        if (title == null || v.titleText == null) return;
        float k = v.k;
        nameLayout = Ui.layout(title.title, v.titleText, 900 * k, 2);
        badgeFont = Ui.text(Ui.medium, 22 * k, 0xCCFFFFFF);
        matchFont = Ui.text(Ui.semibold, 28 * k, GREEN);
        actionFont = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        castFont = Ui.text(Ui.regular, 24 * k, 0x99FFFFFF);
        castText = new TextPaint(castFont);
        epTitleFont = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        epMetaFont = Ui.text(Ui.medium, 24 * k, 0x99FFFFFF);
        headFont = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
        story = title.overview.isEmpty() ? null : Ui.layout(title.overview, v.bodyText, 860 * k, 3);
        nameRight = Ui.rtl ? Ui.lineRight(nameLayout) : 0;
        storyRight = story == null || !Ui.rtl ? 0 : Ui.lineRight(story);
        if (!castLine.isEmpty()) castLine = TextUtils.ellipsize(castLine, castText, 860 * k, TextUtils.TruncateAt.END).toString();
        if (!seasons.isEmpty()) pickSeason(false);
    }

    boolean step(float dt) {
        return false;
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = Ui.dpad(e.getKeyCode());     // right to left: the page is mirrored, ← and → swap
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok && e.getRepeatCount() > 0) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (mode != M_MAIN) mode = M_MAIN;
            else close();
        } else if (mode == M_EPISODES) {
            episodesKey(code, ok);
        } else if (mode == M_MORE) {
            moreKey(code, ok);
        } else {
            mainKey(code, ok);
        }
        v.invalidate();
        v.kick();
        return true;
    }

    private void mainKey(int code, boolean ok) {
        if (code == KeyEvent.KEYCODE_DPAD_UP) action = Math.max(0, action - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) action = Math.min(actions.size() - 1, action + 1);
        else if (ok || code == KeyEvent.KEYCODE_DPAD_RIGHT) select(actions.get(action), ok);
    }

    private void select(int id, boolean ok) {
        switch (id) {
            case B_EPISODES:
                mode = M_EPISODES;
                inSeasons = false;
                return;
            case B_MORE:
                openMore();
                return;
            default:
                if (!ok) return;     // → opens the panels only
        }
        if (id == B_LIST) toggleList();
        else if (id == B_RATE) rate();
        else if (id == B_TRAILER) trailer();
        else if (target != null) play(target, id == B_RESTART);
    }

    private void episodesKey(int code, boolean ok) {
        if (inSeasons) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                int s = Math.max(0, Math.min(seasons.size() - 1, season + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                if (s != season) {
                    season = s;
                    pickSeason(true);
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT || ok) {
                inSeasons = false;
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                mode = M_MAIN;
            }
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) episodeFocus = Math.max(0, episodeFocus - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) episodeFocus = Math.min(shown.size() - 1, episodeFocus + 1);
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT && seasons.size() > 1) inSeasons = true;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) mode = M_MAIN;
        else if (ok && episodeFocus < shown.size()) play(shown.get(episodeFocus), false);
        if (episodeFocus < episodeTop) episodeTop = episodeFocus;
        if (episodeFocus > episodeTop + 3) episodeTop = episodeFocus - 3;
    }

    private void moreKey(int code, boolean ok) {
        int cols = (int) MORE_COLUMNS, col = moreFocus % cols;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (col == 0) mode = M_MAIN;
            else moreFocus--;
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && col < cols - 1 && moreFocus < more.size() - 1) {
            moreFocus++;
        } else if (code == KeyEvent.KEYCODE_DPAD_UP && moreFocus >= cols) {
            moreFocus -= cols;
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && moreFocus + cols < more.size()) {
            moreFocus += cols;
        } else if (ok && moreFocus < more.size()) {
            CinemaCards.Card c = more.get(moreFocus);
            open(c.title, c.poster);
            return;
        }
        int row = moreFocus / cols;
        if (row < moreTop) moreTop = row;
        if (row > moreTop + 2) moreTop = row - 2;
    }

    /** Titles of the same kind and first genre, newest first (not this one). */
    private void openMore() {
        mode = M_MORE;
        moreFocus = 0;
        moreTop = 0;
        if (!more.isEmpty() || loadingMore) return;
        final Library.Title t = title;
        String genre = t.genres.isEmpty() ? "" : t.genres.split("·")[0].trim();
        final String id = Recommend.categoryId(genre);
        loadingMore = true;
        library.background(() -> {
            List<Library.Title> found = new ArrayList<>(library.genreNow(id == null ? "62" : id, t.series ? "series" : "movies", 0));
            found.addAll(library.genreNow(id == null ? "62" : id, t.series ? "series" : "movies", 1));
            v.post(() -> {
                loadingMore = false;
                if (title != t) return;
                for (Library.Title x : found) {
                    if (x.id.equals(t.id) || more.size() >= 20) continue;
                    CinemaCards.Card c = new CinemaCards.Card();
                    c.title = x;
                    c.poster = x.poster;
                    more.add(c);
                }
                v.invalidate();
            });
        });
    }

    private void toggleList() {
        inList = CinemaPrefs.toggleMyList(v.getContext(), title);
        v.toast(inList ? Ui.tr("Adicionado à Minha lista", "Added to My List", "Agregado a Mi lista")
                : Ui.tr("Removido da Minha lista", "Removed from My List", "Quitado de Mi lista"));
        refresh();
    }

    /** 👍 → 👍👍 → 👎 → none, like Netflix's thumbs. */
    private void rate() {
        rating = rating == CinemaPrefs.RATE_NONE ? CinemaPrefs.RATE_LIKE : rating == CinemaPrefs.RATE_LIKE ? CinemaPrefs.RATE_LOVE
                : rating == CinemaPrefs.RATE_LOVE ? CinemaPrefs.RATE_DISLIKE : CinemaPrefs.RATE_NONE;
        CinemaPrefs.setRating(v.getContext(), title, rating);
        refresh();
    }

    /** The trailer in YouTube (its TV app when it's there). */
    private void trailer() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(info.trailer)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            v.getContext().startActivity(new Intent(i).setPackage(YOUTUBE_TV));
        } catch (ActivityNotFoundException e) {
            try {
                v.getContext().startActivity(i);
            } catch (ActivityNotFoundException e2) {
                v.toast(Ui.tr("Instale o YouTube para ver o trailer", "Install YouTube to watch the trailer", "Instala YouTube para ver el tráiler"));
            }
        }
    }

    /** "Starring: Aaron Paul, Bryan Cranston" */
    private static String cast(Library.Info i) {
        String out = i.cast.isEmpty() ? "" : Ui.tr("Elenco: ", "Starring: ", "Reparto: ") + i.cast;
        if (!i.director.isEmpty()) out += (out.isEmpty() ? "" : "   ") + Ui.tr("Direção: ", "Director: ", "Dirección: ") + i.director;
        return out;
    }

    private void play(Library.Title t, boolean fromStart) {
        if (!title.series) {
            host.play(Collections.singletonList(title), 0, null, fromStart);
            return;
        }
        host.play(episodes, Math.max(0, episodes.indexOf(t)), title, fromStart);
    }

    // MARK: - Drawing

    void draw(Canvas c) {
        if (title == null || nameLayout == null) return;
        if (mode == M_EPISODES) {
            drawEpisodes(c);
            return;
        }
        if (mode == M_MORE) {
            drawMore(c);
            return;
        }
        float k = v.k, x = CinemaView.L * k;
        // Bottom up from the actions: cast, story, the line of facts, the logo or name.
        float y = ACTIONS_Y * k - 40 * k;
        if (!castLine.isEmpty()) {
            Ui.text(c, castLine, x, y, castFont, v.w);
            y -= 44 * k;
        }
        if (story != null) {
            float storyTop = y - 10 * k - story.getHeight();
            c.save();
            c.translate(Ui.layoutX(x, storyRight, v.w), storyTop);
            story.draw(c);
            c.restore();
            y = storyTop - 30 * k;
        }
        drawFacts(c, x, y);
        drawName(c, x, y - Ui.cap(matchFont) - 34 * k);
        drawActions(c, x);
    }

    /** "2024  [TV-14]  3 Seasons  [HD]" (no "% Match": the user found it distracting). */
    private void drawFacts(Canvas c, float x, float base) {
        float k = v.k;
        v.metaFont.setColor(0xE6FFFFFF);
        if (!title.year.isEmpty()) {
            Ui.text(c, title.year, x, base, v.metaFont, v.w);
            x += v.metaFont.measureText(title.year) + 22 * k;
        }
        if (!info.rating.isEmpty()) x = badge(c, info.rating, x, base) + 22 * k;
        String length = title.series ? seasonsText
                : Ui.ar && title.minutes > 0 ? Arabic.duration(title.minutes)
                : title.minutes >= 60 ? title.minutes / 60 + "h " + title.minutes % 60 + "m" : title.minutes > 0 ? title.minutes + "m" : "";
        if (!length.isEmpty()) {
            Ui.text(c, length, x, base, v.metaFont, v.w);
            x += v.metaFont.measureText(length) + 22 * k;
        }
        badge(c, "HD", x, base);
    }

    /** The logo (TMDB) when there is one, else the name; its bottom at `bottom`. */
    private void drawName(Canvas c, float x, float bottom) {
        float k = v.k;
        Bitmap logo = title.logo.isEmpty() ? null : CinemaLogos.get(v.getContext()).get(title.logo);
        if (logo != null) {
            float s = Math.min(640 * k / logo.getWidth(), 200 * k / logo.getHeight());
            float lw = logo.getWidth() * s, left = Ui.mx(x, lw, v.w);
            logoRect.set(left, bottom - logo.getHeight() * s, left + lw, bottom);
            c.drawBitmap(logo, null, logoRect, v.image);
            return;
        }
        c.save();
        c.translate(Ui.rtl ? Ui.layoutX(x, nameRight, v.w) : x - Ui.leftBearing(v.titleFont, title.title),
                bottom - nameLayout.getHeight());
        nameLayout.draw(c);
        c.restore();
    }

    private float badge(Canvas c, String text, float x, float base) {
        float k = v.k, tw = badgeFont.measureText(text), cap = Ui.cap(badgeFont);
        float left = Ui.mx(x, tw + 18 * k, v.w);
        v.rect.set(left, base - cap - 8 * k, left + tw + 18 * k, base + 8 * k);
        v.stroke.setStrokeWidth(1.5f * k);
        v.stroke.setColor(0x99FFFFFF);
        c.drawRoundRect(v.rect, 4 * k, 4 * k, v.stroke);
        c.drawText(text, left + 9 * k, base, badgeFont);
        return Ui.rtl ? x + tw + 18 * k : v.rect.right;     // the next one's x, from the start edge
    }

    /** The column of actions; the focused one white. */
    private void drawActions(Canvas c, float x) {
        float k = v.k, top = ACTIONS_Y * k;
        for (int i = 0; i < actions.size(); i++) {
            float y = top + i * ACTION_H * k, cy = y + ACTION_H * k / 2;
            boolean focused = i == action;
            if (focused) {
                if (Ui.rtl) v.rect.set(v.w - x - ACTION_W * k, y + 4 * k, v.w - x + 16 * k, y + (ACTION_H - 4) * k);
                else v.rect.set(x - 16 * k, y + 4 * k, x + ACTION_W * k, y + (ACTION_H - 4) * k);
                v.fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(v.rect, 8 * k, 8 * k, v.fill);
            }
            int ink = focused ? 0xFF141414 : 0xE6FFFFFF;
            drawIcon(c, actions.get(i), Ui.mx(x + 20 * k, 0, v.w), cy, ink);
            actionFont.setColor(ink);
            Ui.text(c, actionLabels.get(i), x + 62 * k, cy + Ui.halfCap(actionFont), actionFont, v.w);
        }
    }

    private void drawIcon(Canvas c, int id, float cx, float cy, int ink) {
        float k = v.k;
        switch (id) {
            case B_PLAY:
            case B_TRAILER:
                Symbols.draw(v.getContext(), c, "play", cx, cy, 30 * k, ink);
                break;
            case B_RESTART:
                v.stroke.setColor(ink);
                v.stroke.setStrokeWidth(3 * k);
                v.rect.set(cx - 11 * k, cy - 11 * k, cx + 11 * k, cy + 11 * k);
                c.drawArc(v.rect, -60, 300, false, v.stroke);
                break;
            case B_EPISODES:
            case B_MORE:
                v.fill.setColor(ink);
                for (int i = -1; i <= 1; i++) c.drawRect(cx - 12 * k, cy + i * 8 * k - 1.5f * k, cx + 12 * k, cy + i * 8 * k + 1.5f * k, v.fill);
                break;
            case B_LIST:
                if (inList) Symbols.draw(v.getContext(), c, "check", cx, cy, 30 * k, ink);
                else v.drawPlus(c, cx, cy, 11 * k, ink);
                break;
            default:      // rate: a thumb, as text (up, double up, down)
                String thumb = rating == CinemaPrefs.RATE_DISLIKE ? "👎" : rating == CinemaPrefs.RATE_LOVE ? "👍👍" : "👍";
                actionFont.setColor(ink);
                float tw = actionFont.measureText(thumb);
                c.drawText(thumb, cx - Math.min(tw, 30 * k) / 2, cy + Ui.halfCap(actionFont), actionFont);
        }
    }

    // MARK: - Episodes (seasons on the left, the season's episodes on the right; mirrored in right to left)

    private void drawEpisodes(Canvas c) {
        float k = v.k, x = CinemaView.L * k;
        v.fill.setColor(0xEB141414);
        c.drawRect(0, 0, v.w, v.h, v.fill);
        Ui.text(c, title.title, x, 130 * k, headFont, v.w);
        if (loadingEpisodes) {
            v.stroke.setStrokeWidth(5 * k);
            v.stroke.setColor(0xCCFFFFFF);
            Ui.spinner(c, v.w / 2f, v.h / 2f, 30 * k, android.os.SystemClock.uptimeMillis() / 1000f, v.stroke);
            v.kick();
            return;
        }
        for (int i = 0; i < seasons.size(); i++) {
            float y = (230 + i * 70) * k;
            boolean picked = i == season, focused = inSeasons && picked;
            if (focused) {
                if (Ui.rtl) v.rect.set(v.w - x - 360 * k, y - 44 * k, v.w - x + 16 * k, y + 20 * k);
                else v.rect.set(x - 16 * k, y - 44 * k, x + 360 * k, y + 20 * k);
                v.fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(v.rect, 8 * k, 8 * k, v.fill);
            }
            actionFont.setColor(focused ? 0xFF141414 : picked ? 0xFFFFFFFF : 0x80FFFFFF);
            Ui.text(c, seasonLabels.get(i), x, y, actionFont, v.w);
        }
        float ex = EP_X * k;
        for (int i = episodeTop; i < shown.size() && i <= episodeTop + 3; i++) {
            float top = (180 + (i - episodeTop) * EP_ROW) * k;
            drawEpisode(c, i, ex, top, !inSeasons && i == episodeFocus);
        }
    }

    private void drawEpisode(Canvas c, int i, float x, float top, boolean focused) {
        float k = v.k, tw = EP_THUMB_W * k, th = EP_THUMB_H * k, r = CinemaView.RADIUS * k;
        if (focused) {
            if (Ui.rtl) v.rect.set(80 * k, top - 16 * k, v.w - x + 16 * k, top + th + 16 * k);
            else v.rect.set(x - 16 * k, top - 16 * k, v.w - 80 * k, top + th + 16 * k);
            v.fill.setColor(0xFF2F2F2F);
            c.drawRoundRect(v.rect, 10 * k, 10 * k, v.fill);
        }
        Library.Title e = shown.get(i);
        String pic = episodePicture(e);
        Bitmap b = pic.isEmpty() ? null : thumbs.get(epKeys.get(i), pic, Math.round(tw), Math.round(th), r);
        float left = Ui.mx(x, tw, v.w);
        v.rect.set(left, top, left + tw, top + th);
        if (b != null) {
            c.drawBitmap(b, null, v.rect, v.image);
        } else {
            v.fill.setColor(0xFF232327);
            c.drawRoundRect(v.rect, r, r, v.fill);
        }
        float f = epProgress.get(i);
        if (f >= 0.95f) {
            float cx = Ui.rtl ? v.rect.left + 30 * k : v.rect.right - 30 * k, cy = v.rect.top + 30 * k;
            v.fill.setColor(0xE6FFFFFF);
            c.drawCircle(cx, cy, 17 * k, v.fill);
            Symbols.draw(v.getContext(), c, "check", cx, cy, 24 * k, 0xFF141414);
        } else if (f > 0) {
            v.drawProgress(c, v.rect, f, 1);
        }
        if (focused) Symbols.draw(v.getContext(), c, "play", v.rect.centerX(), v.rect.centerY(), 56 * k, 0xE6FFFFFF);
        float tx = x + tw + 32 * k;
        epTitleFont.setColor(focused ? 0xFFFFFFFF : 0xCCFFFFFF);
        Ui.text(c, epLabels.get(i), tx, top + 56 * k, epTitleFont, v.w);
        Ui.text(c, epMeta.get(i), tx, top + 100 * k, epMetaFont, v.w);
    }

    // MARK: - More Like This

    private void drawMore(Canvas c) {
        float k = v.k, x = CinemaView.L * k;
        v.fill.setColor(0xEB141414);
        c.drawRect(0, 0, v.w, v.h, v.fill);
        Ui.text(c, Ui.tr("Títulos semelhantes", "More Like This", "Más títulos similares"), x, 150 * k, headFont, v.w);
        if (loadingMore || more.isEmpty()) {
            v.stroke.setStrokeWidth(5 * k);
            v.stroke.setColor(0xCCFFFFFF);
            if (loadingMore) Ui.spinner(c, v.w / 2f, v.h / 2f, 30 * k, android.os.SystemClock.uptimeMillis() / 1000f, v.stroke);
            if (loadingMore) v.kick();
            return;
        }
        CinemaCards cards = v.cards();
        int cols = (int) MORE_COLUMNS;
        float stepX = (CinemaCards.CARD_W + 40) * k * 1.15f, stepY = (CinemaCards.CARD_H + 60) * k * 1.15f;
        for (int i = moreTop * cols; i < more.size(); i++) {
            int r = i / cols - moreTop, col = i % cols;
            if (r > 2) break;
            float cx = Ui.mx(x + col * stepX, CinemaCards.CARD_W * k, v.w), cy = MORE_TOP * k + r * stepY;
            if (i == moreFocus) {
                v.stroke.setStrokeWidth(4 * k);
                v.stroke.setColor(0xFFFFFFFF);
                v.rect.set(cx - 5 * k, cy - 5 * k, cx + CinemaCards.CARD_W * k + 5 * k, cy + CinemaCards.CARD_H * k + 5 * k);
                c.drawRoundRect(v.rect, 12 * k, 12 * k, v.stroke);
            }
            cards.draw(c, more.get(i), cx, cy, i == moreFocus ? 1 : 0.75f);
        }
    }
}
