package io.watchmatch;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays a the catalog video full screen, the way Netflix does: from where it was left, at the quality picked or, on
 * Auto, at the one the line carries (QualityController decides and SeamlessSwap changes it without a hiccup or a word;
 * a quality picked by hand stays), with subtitles styled as you like, "Skip intro", the scenes the catalog
 * marks for families skipped when that's on, then the next episode. The file comes through Streamer. PlayerOverlay
 * draws the controls and takes the keys.
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback, SeamlessSwap.Host {
    static final String EXTRA_PLAYLIST = "playlist", EXTRA_INDEX = "index", EXTRA_ROOT = "root", EXTRA_FROM_START = "fromStart",
            EXTRA_AT = "at";
    static final int DEFAULT_HEIGHT = 1080;
    /** The place is kept every few seconds (an app update or a power cut can end playing without a goodbye). */
    private static final long STARTED_SAVE_MS = 3_500, SAVE_EVERY_MS = 5_000, SEEK_SETTLE_MS = 700, WATCH_MS = 250;
    private static final long AUTO_MS = 1000;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    final List<Library.Title> playlist = new ArrayList<>();
    private Library.Title root;
    int index;
    private boolean fromStart;
    /** The film "Up next" offers after this one (looked up at half way, off the main thread), its button text, and whether it was asked. */
    private Library.Title moviePick;
    private String upNextLabel;
    private boolean pickAsked, startedFromPreview;
    /** Where the first video starts (ms): the second its preview had reached; -1 for its saved place. */
    private long firstAt = -1;

    /** The view the picture is on now (a swap puts the second one here). */
    private SurfaceView surface;
    private FrameLayout stage;
    private PlayerOverlay overlay;
    /** The player on screen (the only one, but for a swap's few seconds): read it through activePlayer(). */
    private MediaPlayer active;
    private Streamer streamer;
    private boolean surfaceReady, prepared, resumed, upNextSaved, rendered, ended;
    private int generation;           // bumped per video: answers for an older one are dropped

    // Automatic quality
    private QualityController controller;
    private SeamlessSwap swap;
    /** Two decoders at once didn't work on this TV (or it is a low-RAM one): quality then changes only at a pause or a seek. */
    private boolean swapDisabled;
    /** A change the controller wants that can only happen at a pause or a seek (-1: none). */
    private int deferred = -1;
    /** Buffered up to here (ms), from MediaPlayer's buffering updates. */
    private long bufferedMs;
    /** After a quality change at a pause: stay paused once it is ready. */
    private boolean holdPaused;

    // The current video
    List<Library.Source> sources = new ArrayList<>();
    Library.Source source;
    private Library.Subtitles subtitleFiles = new Library.Subtitles();
    private Library.Info info = new Library.Info();
    Srt subtitles;
    String subtitleLanguage;
    /** Subtitles later (+) or earlier (−) than the file says, in ms: for files made for another cut. */
    long subtitleOffset;
    /** Audio languages (MediaPlayer track numbers and names), when the file has more than one. */
    final List<Integer> audioTracks = new ArrayList<>();
    final List<String> audioNames = new ArrayList<>();
    int audioChoice;
    /** Playback speed (Netflix's 0.5× to 1.5×), kept for the next videos of this playlist. */
    float speed = 1f;
    private long startAt, pendingSeek = -1;
    private boolean triedDirect;
    /** The file that failed came through Streamer (set on opening): the CDN-direct step only makes sense then. */
    private boolean streamerWasUsed;
    private AudioFocusRequest focus;
    /** What to do when the player errors (one ladder per video), and the last place/length the player gave us: the only ones used after an error. */
    private final PlayerFailureRules failure = new PlayerFailureRules();
    private long lastPos, lastDur;
    private final LoadPercent loadPercent = new LoadPercent();
    /** What the Streamer had delivered when this loading episode began (the percent counts what comes after). */
    private long deliveredMark;
    private long markedBuffered;

    private final Runnable save = new Runnable() {
        @Override public void run() {
            saveProgress();
            handler.postDelayed(this, SAVE_EVERY_MS);
        }
    };
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            watchPosition();
            handler.postDelayed(this, WATCH_MS);
        }
    };
    private final Runnable commitSeek = this::commitSeek;
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            autoStep();
            handler.postDelayed(this, AUTO_MS);
        }
    };

    static Intent intent(Context c, List<Library.Title> list, int index, Library.Title root, boolean fromStart) {
        JSONArray a = new JSONArray();
        String rootJson = "";
        try {
            for (Library.Title t : list) a.put(Library.json(t));
            if (root != null) rootJson = Library.json(root).toString();
        } catch (Exception e) {
            AppLog.w("playlist: " + e);
        }
        return new Intent(c, PlayerActivity.class).putExtra(EXTRA_PLAYLIST, a.toString()).putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_ROOT, rootJson).putExtra(EXTRA_FROM_START, fromStart);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        subtitleLanguage = CinemaPrefs.subtitles(this);
        readIntent(getIntent());
        FrameLayout rootView = new FrameLayout(this);
        rootView.setBackgroundColor(0xFF000000);
        stage = rootView;
        surface = new SurfaceView(this);
        surface.setZOrderMediaOverlay(true);     // the second view of a swap shares this layer; the one added last is in front
        surface.getHolder().addCallback(this);
        rootView.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        swap = new SeamlessSwap(this, this);
        overlay = new PlayerOverlay(this);
        rootView.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootView);
        load();
    }

    private void readIntent(Intent i) {
        playlist.clear();
        try {
            JSONArray a = new JSONArray(i.getStringExtra(EXTRA_PLAYLIST));
            for (int n = 0; n < a.length(); n++) playlist.add(Library.fromJson(a.getJSONObject(n)));
            String r = i.getStringExtra(EXTRA_ROOT);
            root = r == null || r.isEmpty() ? null : Library.fromJson(new JSONObject(r));
        } catch (Exception e) {
            AppLog.w("player intent: " + e);
        }
        index = Math.max(0, Math.min(i.getIntExtra(EXTRA_INDEX, 0), playlist.size() - 1));
        fromStart = i.getBooleanExtra(EXTRA_FROM_START, false);
        firstAt = i.getLongExtra(EXTRA_AT, -1);
    }

    Library.Title current() {
        return playlist.isEmpty() ? null : playlist.get(index);
    }

    /** The series' or movie's name. */
    String heading() {
        Library.Title t = current();
        if (t == null) return "";
        return root != null ? root.title : t.title;
    }

    /** "S1:E3" for an episode, "" for a movie. */
    String episodeLine() {
        Library.Title t = current();
        return t != null && root != null && t.series && t.season > 0 ? "S" + t.season + ":E" + t.episode : "";
    }

    boolean hasNext() {
        return index < playlist.size() - 1;
    }

    /** A film played by itself (not an episode, not a clip): the one that offers "Up next: <film>" from its credits. */
    private boolean isMovie() {
        Library.Title t = current();
        return t != null && root == null && !t.series && playlist.size() == 1;
    }

    /** The next episode, or the film picked for after this one: what the Next button and the end's countdown go to. */
    boolean upNextAvailable() {
        return hasNext() || moviePick != null;
    }

    /** Autoplay at the end: the setting, and not after a film that began from a preview. */
    boolean autoplayOn() {
        return isMovie() ? NextUpRules.movieCounts(CinemaPrefs.autoplay(this), false, startedFromPreview)
                : CinemaPrefs.autoplay(this);
    }

    /** "Up next: <film>" (built once per pick), or null when this isn't a film with a pick. */
    String upNextLabel() {
        return hasNext() ? null : upNextLabel;
    }

    /** "Up next: <film> in 5". */
    String upNextCount(int seconds) {
        if (hasNext() || moviePick == null) return null;
        String n = Ui.digits(String.valueOf(seconds)), name = Ui.isolate(shortName(moviePick.title));
        return Ui.tr("A seguir: " + name + " em " + n, "Up next: " + name + " in " + n, "A continuación: " + name + " en " + n,
                "التالي: " + name + " خلال " + n);
    }

    private static String shortName(String s) {
        return s.length() <= 30 ? s : s.substring(0, 29).trim() + "…";
    }

    boolean isSeries() {
        return root != null && playlist.size() > 1;
    }

    // MARK: - Loading a video

    private void load() {
        final Library.Title t = current();
        if (t == null) {
            finish();
            return;
        }
        final int gen = ++generation;
        release();
        controller = null;
        deferred = -1;
        bufferedMs = 0;
        holdPaused = false;
        prepared = false;
        triedDirect = false;
        upNextSaved = false;
        moviePick = null;
        upNextLabel = null;
        pickAsked = false;
        startedFromPreview = startedFromPreview || firstAt >= 0;
        ended = false;
        subtitles = null;
        subtitleOffset = 0;
        audioTracks.clear();
        audioNames.clear();
        info = new Library.Info();
        introFrom = FROM_NONE;
        markStart = -1;
        startAt = firstAt >= 0 ? firstAt : fromStart ? 0 : Progress.resumeAt(this, t.id);
        lastPos = startAt;
        lastDur = 0;
        failure.reset();
        Sync.playing(t.id);     // sync never moves the position of the video playing here
        fromStart = false;
        firstAt = -1;
        rendered = false;
        overlay.loading(true);
        final Library library = Library.get(this);
        // The video first (it starts as soon as its address is known); subtitles and skips alongside.
        LOADER.execute(() -> {
            List<Library.Source> list = new ArrayList<>();
            try {
                list = library.sources(t.id);
            } catch (Exception e) {
                AppLog.w("player sources: " + e);
            }
            final List<Library.Source> found = list;
            handler.post(() -> {
                if (gen != generation) return;
                sources = found;
                if (found.isEmpty()) {
                    overlay.message(Ui.tr("Este vídeo não está disponível agora.", "This video isn't available right now.",
                            "Este video no está disponible ahora."));
                    return;
                }
                source = start(this, found);
                makeController();
                open(gen, startAt);
            });
        });
        library.background(() -> {
            final Library.Subtitles files = library.subtitles(t.id);
            final Library.Info details = library.info(t.id);
            handler.post(() -> {
                if (gen != generation) return;
                subtitleFiles = files;
                info = details;
                loadSubtitles();
                otherIntros(t, gen);
            });
        });
    }

    /** The first picture is on screen (until then the overlay shows the title's art). */
    boolean rendered() {
        return rendered;
    }

    /** The picture shown while it loads: the title's (or its series') wide photo, else its poster. */
    String art() {
        Library.Title t = current();
        if (t == null) return "";
        if (t.backdrop != null && !t.backdrop.isEmpty()) return t.backdrop;
        if (root != null && root.backdrop != null && !root.backdrop.isEmpty()) return root.backdrop;
        return root != null && !root.poster.isEmpty() ? root.poster : t.poster;
    }

    /**
     * The file to start with, never above the Settings quality: the quality picked by hand last time, else on Auto 480p
     * on the mirror (else the lowest of 360p or more) and the highest up to 720p elsewhere (QualityRules). With the
     * auto_quality switch off, the old way: the mirror as above, elsewhere the highest up to the Settings quality.
     */
    static Library.Source start(android.content.Context c, List<Library.Source> list) {
        int cap = CinemaPrefs.height(c);
        String id = Endpoints.active().id;
        boolean auto = Prefs.autoQuality();
        if (!auto && !QualityRules.adaptive(id)) return pick(list, cap);
        List<Integer> heights = new ArrayList<>();
        for (Library.Source s : list) if (s.height <= cap) heights.add(s.height);
        if (heights.isEmpty()) for (Library.Source s : list) heights.add(s.height);
        int remembered = CinemaPrefs.remembered(c, id);
        int want = auto && !heights.contains(remembered) ? QualityRules.autoStartHeight(id, heights)
                : QualityRules.startHeight(heights, remembered);
        for (Library.Source s : list) if (s.height == want) return s;
        return pick(list, want);
    }

    /** The highest quality up to the one picked; the lowest when all are higher. */
    static Library.Source pick(List<Library.Source> list, int height) {
        Library.Source best = list.get(0);
        for (Library.Source s : list) if (s.height <= height) best = s;
        return best;
    }

    /** Opens the current source at `at` ms, through Streamer (or straight from the CDN after a failure). */
    private void open(final int gen, final long at) {
        final Library.Source src = source;
        final boolean direct = triedDirect;
        overlay.loading(true);
        Turbo.before(this, null);       // frees memory in the background (once a minute, only when low); playback doesn't wait
        LOADER.execute(() -> {
            String address = src.url;
            Streamer s = null;
            try {
                String[] probe = Streamer.probe(src.url);
                address = probe[0];
                if (!direct) {
                    s = new Streamer(probe[0], Long.parseLong(probe[1]));
                    address = s.address();
                }
            } catch (Exception e) {
                AppLog.w("player probe: " + e);
            }
            final String url = address;
            final Streamer started = s;
            handler.post(() -> {
                if (gen != generation) {
                    if (started != null) started.close();
                    return;
                }
                release();
                streamer = started;
                streamerWasUsed = started != null;
                markDelivered();
                startPlayer(url, at);
            });
        });
    }

    private void startPlayer(String url, long at) {
        startAt = at;
        lastPos = at;
        bufferedMs = 0;
        failure.settled();
        final MediaPlayer player = new MediaPlayer();
        active = player;
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        player.setOnPreparedListener(mp -> {
            prepared = true;
            lastDur = Math.max(0, mp.getDuration());
            readAudioTracks();
            if (startAt > 0) mp.seekTo((int) startAt);
            if (resumed && !holdPaused) play();
            overlay.show();
            learnBitrates();
        });
        bind(player);
        try {
            if (surfaceReady) player.setDisplay(surface.getHolder());
            player.setScreenOnWhilePlaying(true);
            player.setDataSource(url);
            player.prepareAsync();
        } catch (Exception e) {
            onError(player, generation, -1, 0);
        }
    }

    /** What the player on screen reports (also given to a second player when a swap makes it the one on screen). */
    private void bind(final MediaPlayer player) {
        final int gen = generation;
        player.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) overlay.loading(true);
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered = true;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) overlay.loading(false);
            return false;
        });
        player.setOnBufferingUpdateListener((mp, percent) -> bufferedMs = lastDur * (long) percent / 100);
        player.setOnVideoSizeChangedListener((mp, vw, vh) -> fit(surface, vw, vh));
        player.setOnCompletionListener(mp -> finished());
        player.setOnSeekCompleteListener(mp -> {
            if (mp == active) overlay.loading(false);
        });
        player.setOnErrorListener((mp, what, extra) -> {
            onError(mp, gen, what, extra);
            return true;
        });
    }

    /**
     * The player reported an error. The player is in error state: we never ask it anything again (every call answers
     * -38, which comes back here: that was a loop at 60 Hz). A failure is handled once; the player is cut loose and
     * released first, then the ladder (PlayerFailureRules) picks: the same file again after a short wait, the same file
     * straight from the CDN, one quality lower, and last the message with a Retry.
     */
    private void onError(MediaPlayer mp, int gen, int what, int extra) {
        String label = source == null ? "" : source.label;
        if (!failure.accept(what, mp == active, gen, generation)) {
            AppLog.i("player error " + what + "/" + extra + " ignored (gen " + gen + ", now " + generation + ")");
            return;
        }
        AppLog.first("first player error " + what + "/" + extra + " at " + label + " gen " + gen);
        mp.setOnErrorListener(null);
        mp.setOnInfoListener(null);
        mp.setOnCompletionListener(null);
        mp.setOnPreparedListener(null);
        mp.setOnSeekCompleteListener(null);
        mp.setOnBufferingUpdateListener(null);
        mp.setOnVideoSizeChangedListener(null);
        final long at = pendingSeek >= 0 ? pendingSeek : lastPos;
        release();
        startAt = at;
        final Library.Source lower = lowerSource();
        PlayerFailureRules.Step step = failure.next(what, extra, mp != null && streamerWasUsed, lower != null);
        AppLog.i("player error " + what + "/" + extra + " at " + label + " gen " + gen + " attempt " + failure.attempts() + " -> " + step
                + " from " + at / 1000 + " s");
        switch (step) {
            case REOPEN_SAME:
                if (failure.attempts() > 1 && source != null) Streamer.refresh(source.url);     // the signed link may have run out
                overlay.reconnecting(Ui.tr("Reconectando…", "Reconnecting…", "Reconectando…"));
                final int mine = generation;
                handler.postDelayed(() -> {
                    if (mine == generation) open(mine, at);
                }, failure.backoffMs());
                break;
            case DIRECT:
                triedDirect = true;
                overlay.reconnecting(Ui.tr("Reconectando…", "Reconnecting…", "Reconectando…"));
                open(generation, at);
                break;
            case LOWER_QUALITY:
                source = lower;
                triedDirect = false;
                if (controller != null) controller.switched(Math.max(0, sources.indexOf(lower)), SystemClock.uptimeMillis());
                overlay.reconnecting(Ui.tr("Reconectando…", "Reconnecting…", "Reconectando…"));
                open(generation, at);
                break;
            default:
                overlay.messageRetry(Ui.tr("Não foi possível tocar este vídeo.", "This video couldn't be played.",
                        "No se pudo reproducir este video."));
                break;
        }
    }

    /** A loading episode begins (first start, a seek, a re-buffering): the percent starts again from zero. */
    void loadingStarted() {
        loadPercent.reset();
        markDelivered();
    }

    /** A new Streamer or player took over inside one loading episode: count from here, keep the number reached. */
    private void markDelivered() {
        Streamer s = streamer;
        deliveredMark = s == null ? 0 : s.delivered();
        markedBuffered = bufferedMs;
    }

    /** The number under the spinner, for the overlay's tick: what arrived ahead over 6 s of the file. */
    int loadPercent() {
        Streamer s = streamer;
        if (s != null) return loadPercent.update(Math.max(0, s.delivered() - deliveredMark), LoadPercent.targetBytes(s.total, lastDur));
        // straight from the CDN: the player's own buffer, in ms of media
        long ahead = Math.max(0, bufferedMs - Math.max(markedBuffered, position()));
        return loadPercent.update(ahead, LoadPercent.TARGET_SECONDS * 1000L);
    }

    /** The highest file below the one playing, from any provider (null when it is the lowest). */
    private Library.Source lowerSource() {
        if (source == null) return null;
        Library.Source best = null;
        for (Library.Source s : sources) if (s.height < source.height && (best == null || s.height > best.height)) best = s;
        return best;
    }

    /** OK on the final message: the same video again from where it stopped, with the ladder fresh. */
    void retry() {
        if (current() == null) return;
        failure.reset();
        failure.settled();
        triedDirect = false;
        overlay.loading(true);
        open(generation, startAt);
    }

    private void finished() {
        Library.Title t = current();
        if (t != null) Progress.save(this, t, rootTitle(), rootPoster(), duration(), duration());
        markUpNext();
        Progress.flush(this);
        // Only here, at the video's natural end, may the next episode start by itself (the overlay's 5 s countdown).
        // Autoplay off: the Next episode button stays, no countdown. Dismissed, a movie or the last episode: done.
        if (!upNextAvailable() || overlay.nextDismissed()) finish();
        else ended = true;
    }

    void next() {
        AppLog.i("next episode starts (video ended: " + ended + ", at " + lastPos / 1000 + " s)");
        if (hasNext()) play(index + 1);
        else if (moviePick != null) playPick();
    }

    /** The picked film, as a normal play from its page; this one stays finished when its credits were reached. */
    private void playPick() {
        final Library.Title pick = moviePick, t = current();
        if (pick == null || t == null) return;
        if (ended || nextUp()) Progress.save(this, t, rootTitle(), rootPoster(), duration(), duration());
        else saveProgress();
        Progress.flush(this);
        AppLog.i("up next film " + pick.id + " after " + t.id);
        moviePick = null;
        startActivity(intent(this, java.util.Collections.singletonList(pick), 0, null, false));
        finish();
    }

    /** Half way through a film: the catalog's More Like This (its first genre), ranked by MovieNext, off the main thread. */
    private void resolvePick() {
        pickAsked = true;
        final Library.Title t = current();
        final int gen = generation;
        if (t == null) return;
        final Library library = Library.get(this);
        final java.util.Set<String> skip = new java.util.HashSet<>(CinemaPrefs.disliked(this));
        final java.util.Set<String> started = new java.util.HashSet<>();
        for (Progress.Entry e : Progress.everything(this)) {
            if (e.finished() || e.hidden) skip.add(e.title.id);
            else if (e.position > 0) started.add(e.title.id);
        }
        final java.util.Set<String> liked = new java.util.HashSet<>();
        for (String g : CinemaPrefs.likedGenres(this)) liked.add(g.toLowerCase(Locale.ROOT));
        library.background(() -> {
            String genre = t.genres.isEmpty() ? "" : t.genres.split("·")[0].trim();
            String id = Recommend.categoryId(genre);
            List<Library.Title> found = new ArrayList<>(library.genreNow(id == null ? "62" : id, "movies", 0));
            found.addAll(library.genreNow(id == null ? "62" : id, "movies", 1));
            List<MovieNext.Cand> cands = new ArrayList<>();
            for (Library.Title x : found) {
                cands.add(new MovieNext.Cand(x.id, x.genres, x.stars, x.series, x.poster != null && !x.poster.isEmpty(), started.contains(x.id)));
            }
            int best = MovieNext.pick(cands, t.id, t.genres, liked, skip);
            final Library.Title pick = best < 0 ? null : found.get(best);
            AppLog.i("up next film for " + t.id + ": " + (pick == null ? "none" : pick.id) + " of " + found.size());
            handler.post(() -> {
                if (gen != generation || pick == null) return;
                moviePick = pick;
                String name = Ui.isolate(shortName(pick.title));
                upNextLabel = Ui.tr("A seguir: " + name, "Up next: " + name, "A continuación: " + name, "التالي: " + name);
                overlay.invalidate();
            });
        });
    }

    /** Another episode of the playlist, from where it was left. */
    void play(int i) {
        if (i < 0 || i >= playlist.size()) return;
        saveProgress();
        Progress.flush(this);
        index = i;
        load();
    }

    /** Video scaled to fit the screen, its own proportions kept. */
    @Override public void fit(SurfaceView view, int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        int sw = getWindow().getDecorView().getWidth(), sh = getWindow().getDecorView().getHeight();
        if (sw <= 0 || sh <= 0) return;
        float scale = Math.min(sw / (float) vw, sh / (float) vh);
        view.setLayoutParams(new FrameLayout.LayoutParams(Math.round(vw * scale), Math.round(vh * scale), Gravity.CENTER));
    }

    // MARK: - While playing: intro and family skips, Up next

    private void watchPosition() {
        boolean going = playing() && pendingSeek < 0;
        failure.playing(SystemClock.uptimeMillis(), going);
        if (!going) return;
        long p = livePosition(), d = duration();
        if (CinemaPrefs.skipScenes(this)) {
            for (long[] s : info.scenes) {
                if (p >= s[0] && p < s[1] - 300) {
                    cancelSwap();
                    seek(s[1]);
                    overlay.toast(Ui.tr("Cena pulada (modo família)", "Scene skipped (family mode)", "Escena omitida (modo familia)"));
                    return;
                }
            }
        }
        if (NextUpRules.shouldResolve(isMovie(), pickAsked, d, p)) resolvePick();
        if (!upNextSaved && NextUpRules.showing(hasNext(), d, p, info.credits)) markUpNext();
    }

    /** Family mode from the player's buttons: says what it will do for this title, and skips a marked scene at once. */
    void toggleFamily() {
        boolean on = !CinemaPrefs.skipScenes(this);
        CinemaPrefs.setSkipScenes(this, on);
        int n = info.scenes.size();
        if (!on) {
            overlay.toast(Ui.tr("Modo família desligado", "Family mode off", "Modo familia desactivado", "تم إيقاف وضع العائلة"));
        } else if (n == 0) {
            overlay.toast(Ui.tr("Modo família ligado · nenhuma cena marcada neste título",
                    "Family mode on · no marked scenes in this title",
                    "Modo familia activado · ninguna escena marcada en este título",
                    "تم تفعيل وضع العائلة · لا توجد مشاهد محددة في هذا العمل"));
        } else {
            String count = Ui.digits(String.valueOf(n));
            overlay.toast(Ui.tr("Modo família ligado · " + n + (n == 1 ? " cena será pulada" : " cenas serão puladas"),
                    "Family mode on · " + n + (n == 1 ? " scene will be skipped" : " scenes will be skipped"),
                    "Modo familia activado · " + n + (n == 1 ? " escena se omitirá" : " escenas se omitirán"),
                    "تم تفعيل وضع العائلة · سيتم تخطي " + count + (n == 1 ? " مشهد" : " مشاهد")));
            watchPosition();
        }
    }

    /** The next episode waits in Continue watching from the credits on. */
    private void markUpNext() {
        if (upNextSaved || !hasNext()) return;
        upNextSaved = true;
        Progress.upNext(this, playlist.get(index + 1), rootTitle(), rootPoster());
    }

    /** The intro playing now (its end, in ms), or -1: the "Skip intro" button. */
    long introEnd() {
        if (!prepared || pendingSeek >= 0) return -1;
        long p = livePosition();
        for (long[] i : info.intros) if (p >= i[0] && p < i[1] - 1500) return i[1];
        return -1;
    }

    // MARK: - Intros the catalog doesn't have: TheIntroDB, then the person's own mark (the catalog's always wins)

    private static final int FROM_NONE = 0, FROM_CATALOG = 1, FROM_DB = 2, FROM_MARK = 3;
    private int introFrom = FROM_NONE;
    private long markStart = -1;

    /** The key of the series being watched ("" for a movie or a loose episode). */
    private String seriesKey() {
        Library.Title t = current();
        if (root == null || t == null || !t.series || t.season <= 0) return "";
        return !root.id.isEmpty() ? root.id : root.title;
    }

    private void otherIntros(final Library.Title t, final int gen) {
        introFrom = info.intros.isEmpty() ? FROM_NONE : FROM_CATALOG;
        String key = seriesKey();
        if (key.isEmpty()) {
            if (isMovie() && info.credits < 0) movieCredits(t, gen);
            return;
        }
        if (introFrom == FROM_NONE) applyMark(IntroSources.mark(this, key));
        if (introFrom == FROM_CATALOG && info.credits >= 0) return;
        IntroSources.lookup(this, key, root.title, root.other, root.year, t.season, t.episode, found -> handler.post(() -> {
            if (gen != generation || found == null) return;
            if (introFrom != FROM_CATALOG && !found.intros.isEmpty()) {
                info.intros.clear();
                info.intros.addAll(found.intros);
                introFrom = FROM_DB;
            }
            if (info.credits < 0 && found.credits >= 0) info.credits = found.credits;
            overlay.invalidate();
        }));
    }

    private void applyMark(long[] range) {
        if (introFrom == FROM_CATALOG || introFrom == FROM_DB) return;
        info.intros.clear();
        if (range != null) info.intros.add(range);
        introFrom = range == null ? FROM_NONE : FROM_MARK;
    }

    /** "Mark intro" can be offered: an episode of a series. */
    boolean canMarkIntro() {
        return !seriesKey().isEmpty();
    }

    /** Waiting for the second press (the intro's end). */
    boolean markingIntro() {
        return markStart >= 0;
    }

    boolean hasIntroMark() {
        return canMarkIntro() && IntroSources.mark(this, seriesKey()) != null;
    }

    /** First press: the intro starts here. Second: it ends here, and it is kept for every episode of the series. */
    void markIntro() {
        String key = seriesKey();
        if (key.isEmpty() || !prepared) return;
        IntroMark.Press p = IntroMark.press(markStart, livePosition());
        markStart = p.pendingStart;
        switch (p.result) {
            case IntroMark.START_SAVED:
                overlay.toast(Ui.tr("Início da abertura salvo · marque o fim", "Intro start saved · mark the end",
                        "Inicio de la intro guardado · marca el final", "تم حفظ بداية المقدمة · حدد النهاية"));
                break;
            case IntroMark.TOO_SHORT:
                overlay.toast(Ui.tr("Abertura curta demais (mín. 5 s)", "Intro too short (min. 5 s)",
                        "Intro demasiado corta (mín. 5 s)", "المقدمة قصيرة جدًا (5 ثوانٍ على الأقل)"));
                break;
            case IntroMark.TOO_LONG:
                overlay.toast(Ui.tr("Abertura longa demais (máx. 5 min) · início refeito aqui",
                        "Intro too long (max. 5 min) · start moved here",
                        "Intro demasiado larga (máx. 5 min) · inicio movido aquí",
                        "المقدمة طويلة جدًا (5 دقائق كحد أقصى) · نُقلت البداية إلى هنا"));
                break;
            default:
                IntroSources.setMark(this, key, p.range);
                applyMark(p.range);
                overlay.toast(Ui.tr("Abertura salva para esta série", "Intro saved for this series",
                        "Intro guardada para esta serie", "تم حفظ المقدمة لهذا المسلسل"));
        }
    }

    /** A film's credits start from TheIntroDB (the catalog's own wins). */
    private void movieCredits(final Library.Title t, final int gen) {
        IntroSources.lookupMovie(this, !t.id.isEmpty() ? t.id : t.title, t.title, t.other, t.year, found -> handler.post(() -> {
            if (gen != generation || found == null) return;
            if (info.credits < 0 && found.credits >= 0) info.credits = found.credits;
            overlay.invalidate();
        }));
    }

    void clearIntroMark() {
        String key = seriesKey();
        if (key.isEmpty()) return;
        IntroSources.setMark(this, key, null);
        markStart = -1;
        applyMark(null);
        overlay.toast(Ui.tr("Marca da abertura removida", "Intro mark cleared", "Marca de la intro borrada", "تم مسح علامة المقدمة"));
    }

    /** The credits of an episode with one after it (NextUpRules): the "Next episode" button. */
    boolean nextUp() {
        return prepared && (ended || NextUpRules.showing(upNextAvailable(), duration(), position(), info.credits));
    }

    /** Playback reached its natural end and the next episode is still to come (the card counts down on the last frame). */
    boolean ended() {
        return ended;
    }

    void skipTo(long ms) {
        if (!prepared) return;
        pendingSeek = -1;
        cancelSwap();
        if (deferred >= 0) {
            applyDeferred(ms, false);
            return;
        }
        seek(ms);
    }

    // MARK: - Subtitles, audio and quality (from the overlay's panels)

    private void loadSubtitles() {
        subtitles = null;
        final String file = "ar".equals(subtitleLanguage) ? subtitleFiles.arabic
                : "en".equals(subtitleLanguage) ? subtitleFiles.english : "";
        if (file.isEmpty()) return;
        final int gen = generation;
        LOADER.execute(() -> {
            Srt s = null;
            try {
                s = Srt.load(file);
            } catch (Exception e) {
                AppLog.w("subtitles: " + e);
            }
            final Srt loaded = s;
            handler.post(() -> {
                if (gen == generation) subtitles = loaded;
            });
        });
    }

    /** The subtitle on screen now (null for none), timing offset applied. */
    String subtitleNow() {
        Srt s = subtitles;
        return s == null ? null : s.at(position() - subtitleOffset);
    }

    boolean hasSubtitles(String language) {
        if ("ar".equals(language)) return !subtitleFiles.arabic.isEmpty();
        if ("en".equals(language)) return !subtitleFiles.english.isEmpty();
        return true;
    }

    void setSubtitles(String language) {
        subtitleLanguage = language;
        CinemaPrefs.setSubtitles(this, language);
        loadSubtitles();
    }

    /** Quality picked by hand: it stays (Auto off) until Auto is picked again, for the next videos too. */
    void setQuality(Library.Source s) {
        if (s == null) return;
        String id = Endpoints.active().id;
        if (!Prefs.autoQuality()) {
            if (s == source) return;
            if (QualityRules.adaptive(id)) CinemaPrefs.setRemembered(this, id, s.height);     // per provider; the Settings cap stays
            else CinemaPrefs.setHeight(this, s.height);
            changeNow(sources.indexOf(s));
            return;
        }
        CinemaPrefs.setRemembered(this, id, s.height);
        if (controller != null) controller.setFixed(true, SystemClock.uptimeMillis());
        deferred = -1;
        if (s != source) changeNow(sources.indexOf(s));
        else cancelSwap();
    }

    /** Auto picked again: from here the line decides. */
    void setAuto() {
        CinemaPrefs.setRemembered(this, Endpoints.active().id, 0);
        if (controller != null) controller.setFixed(false, SystemClock.uptimeMillis());
    }

    /** Auto is on (the menu's first row is the one ticked). */
    boolean autoQuality() {
        return Prefs.autoQuality() && controller != null && !controller.fixed();
    }

    /** The menu offers Auto only with more than one file and the auto_quality switch on. */
    boolean autoAvailable() {
        return Prefs.autoQuality() && sources.size() > 1;
    }

    // MARK: - Automatic quality: the controller decides, SeamlessSwap changes

    private void makeController() {
        int[] heights = new int[sources.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = sources.get(i).height;
        long now = SystemClock.uptimeMillis();
        controller = new QualityController(heights, CinemaPrefs.height(this), Math.max(0, sources.indexOf(source)), now);
        int remembered = CinemaPrefs.remembered(this, Endpoints.active().id);
        controller.setFixed(remembered > 0 && source.height == remembered, now);
    }

    /** What each file costs per second (size ÷ duration): this one's now, the ones next to it from their size. */
    private void learnBitrates() {
        final QualityController qc = controller;
        final long d = duration();
        final int at = sources.indexOf(source), gen = generation;
        if (qc == null || d <= 0 || at < 0) return;
        if (streamer != null) qc.setBitrate(at, streamer.total * 1000.0 / d);
        LOADER.execute(() -> {
            for (int n = at - 1; n <= at + 1; n++) {
                if (n < 0 || n >= sources.size() || qc.bitrate(n) > 0) continue;
                try {
                    final int index = n;
                    final double rate = Long.parseLong(Streamer.probe(sources.get(n).url)[1]) * 1000.0 / d;
                    handler.post(() -> {
                        if (gen == generation) qc.setBitrate(index, rate);
                    });
                } catch (Exception e) {
                    AppLog.w("bitrate " + n + ": " + e);
                }
            }
        });
    }

    /** Once a second while playing: the speed measured, the buffer ahead, and what the controller makes of them. */
    private void autoStep() {
        QualityController qc = controller;
        if (qc == null || !Prefs.autoQuality() || !playing()) return;
        Streamer s = streamer;
        if (s != null) qc.onThroughput(s.takeSpeed());
        if (qc.fixed() || pendingSeek >= 0 || swap.active()) return;
        double ahead = Math.max(0, (bufferedMs - position()) / 1000.0);
        int to = qc.tick(SystemClock.uptimeMillis(), ahead);
        if (to < 0 || to >= sources.size() || sources.get(to) == source) return;
        AppLog.w("auto quality " + source.label + " -> " + sources.get(to).label + String.format(Locale.US, " (%.0f s ahead, %.1f Mbit/s)", ahead,
                qc.throughput() * 8 / 1e6));
        int support = swapSupport();
        if (support == SWAP_NEVER) deferred = to;
        else if (support == SWAP_OK) AppLog.w("auto swap begin: " + swap.begin(sources.get(to)) + " (canBegin " + swap.canBegin() + ")");
        else AppLog.w("auto swap waits: memory is short");
    }

    private static final int SWAP_OK = 0, SWAP_LATER = 1, SWAP_NEVER = 2;

    /** Can a second player be held now? Never on a low-RAM TV or after a decoder refused two; not while memory is short. */
    private int swapSupport() {
        if (swapDisabled) return SWAP_NEVER;
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return SWAP_OK;
        if (am.isLowRamDevice()) {
            unsupported("low-RAM TV");
            return SWAP_NEVER;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        Runtime rt = Runtime.getRuntime();
        long heapLeft = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        return info.lowMemory || info.availMem < 200L << 20 || heapLeft < 48L << 20 ? SWAP_LATER : SWAP_OK;
    }

    /** A change the person asked for: seamless when it can be, else straight away from where it is. */
    private void changeNow(int index) {
        if (index < 0 || index >= sources.size() || sources.get(index) == source) return;
        cancelSwap();
        if (playing() && pendingSeek < 0 && Prefs.autoQuality() && swapSupport() == SWAP_OK && swap.begin(sources.get(index))) return;
        direct(index, position());
    }

    /** The new file opens at `at`, the way the first one did (a pause shows). */
    private void direct(int index, long at) {
        source = sources.get(index);
        if (controller != null) controller.switched(index, SystemClock.uptimeMillis());
        triedDirect = false;
        open(generation, at);
    }

    /** A pause or a seek: the moment a change that couldn't be seamless may happen. */
    private void applyDeferred(long at, boolean keepPlaying) {
        int to = deferred;
        deferred = -1;
        if (to < 0 || to >= sources.size() || sources.get(to) == source) return;
        holdPaused = !keepPlaying;
        direct(to, at);
    }

    private void cancelSwap() {
        if (swap != null) swap.cancel();
    }

    // MARK: - SeamlessSwap.Host

    @Override public MediaPlayer activePlayer() {
        return active;
    }

    @Override public SurfaceView activeView() {
        return surface;
    }

    @Override public FrameLayout stage() {
        return stage;
    }

    @Override public float baseSpeed() {
        return speed;
    }

    @Override public void matchAudio(MediaPlayer p) {
        if (audioChoice <= 0 || audioChoice >= audioTracks.size()) return;
        try {
            p.selectTrack(audioTracks.get(audioChoice));
        } catch (RuntimeException e) {
            AppLog.w("swap audio track: " + e);
        }
    }

    @Override public void unsupported(String why) {
        if (!swapDisabled) AppLog.w("seamless swap off for this session (" + why + "): quality changes at a pause or a seek");
        swapDisabled = true;
    }

    @Override public void adopt(MediaPlayer p, SurfaceView view, Streamer s, Library.Source src) {
        final MediaPlayer old = active;
        final SurfaceView oldView = surface;
        final Streamer oldStreamer = streamer;
        active = p;
        surface = view;
        streamer = s;
        source = src;
        triedDirect = false;
        p.setOnPreparedListener(null);
        p.setOnSeekCompleteListener(null);
        bind(p);
        p.setScreenOnWhilePlaying(true);
        if (old != null) {
            old.setOnErrorListener(null);
            old.setOnInfoListener(null);
            old.setOnCompletionListener(null);
            try {
                old.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (oldStreamer != null) oldStreamer.close();
        oldView.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> stage.removeView(oldView), 250);
        if (controller != null) controller.switched(Math.max(0, sources.indexOf(src)), SystemClock.uptimeMillis());
        learnBitrates();
        overlay.invalidate();
    }

    private void readAudioTracks() {
        audioTracks.clear();
        audioNames.clear();
        audioChoice = 0;
        try {
            MediaPlayer.TrackInfo[] tracks = activePlayer().getTrackInfo();
            for (int i = 0; tracks != null && i < tracks.length; i++) {
                if (tracks[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                String lang = tracks[i].getLanguage();
                String name = lang == null || lang.isEmpty() || "und".equals(lang) ? Ui.tr("Faixa ", "Track ", "Pista ") + (audioTracks.size() + 1)
                        : new Locale(lang).getDisplayLanguage(Ui.locale());
                audioTracks.add(i);
                audioNames.add(name);
            }
        } catch (RuntimeException e) {
            AppLog.w("audio tracks: " + e);
        }
    }

    void setSpeed(float s) {
        speed = s;
        applySpeed();
    }

    /** Only while playing: setting it on a paused player would start it. */
    private void applySpeed() {
        if (active == null || !prepared || !activePlayer().isPlaying()) return;
        try {
            activePlayer().setPlaybackParams(activePlayer().getPlaybackParams().setSpeed(speed));
        } catch (RuntimeException e) {
            AppLog.w("speed: " + e);
        }
    }

    void setAudio(int choice) {
        if (active == null || !prepared || choice < 0 || choice >= audioTracks.size()) return;
        try {
            activePlayer().selectTrack(audioTracks.get(choice));
            audioChoice = choice;
        } catch (RuntimeException e) {
            AppLog.w("audio track: " + e);
        }
    }

    // MARK: - Playing

    boolean playing() {
        MediaPlayer p = active;
        if (p == null || !prepared) return false;
        try {
            return p.isPlaying();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Where the video is: the player's answer, or the last one we had when it can't answer (never asked after an error). */
    long position() {
        if (pendingSeek >= 0) return pendingSeek;
        return active != null && prepared ? livePosition() : startAt;
    }

    private long livePosition() {
        MediaPlayer p = active;
        if (p == null || !prepared) return lastPos;
        try {
            int v = p.getCurrentPosition();
            if (v >= 0) lastPos = v;
        } catch (RuntimeException e) {
            // not in a state to answer: the last place stands
        }
        return lastPos;
    }

    long duration() {
        MediaPlayer p = active;
        if (p == null || !prepared) return 0;
        try {
            lastDur = Math.max(0, p.getDuration());
        } catch (RuntimeException e) {
            // keep the last length
        }
        return lastDur;
    }

    /** A seek that can't throw at us; the place is kept at once (an error during it reopens there) and the spinner shows until it lands. */
    private void seek(long ms) {
        MediaPlayer p = active;
        if (p == null || !prepared) return;
        lastPos = ms;
        loadingStarted();
        overlay.loading(true);
        try {
            p.seekTo((int) ms);
        } catch (RuntimeException e) {
            AppLog.w("seek: " + e);
            overlay.loading(false);
        }
    }

    void toggle() {
        if (playing()) {
            pause();
            applyDeferred(position(), false);
        } else {
            play();
        }
    }

    private void play() {
        if (active == null || !prepared) return;
        requestFocus();
        holdPaused = false;
        try {
            activePlayer().start();
        } catch (RuntimeException e) {
            AppLog.w("start: " + e);
            return;
        }
        if (speed != 1f) applySpeed();
        handler.removeCallbacks(save);
        handler.postDelayed(save, STARTED_SAVE_MS);
        handler.removeCallbacks(watch);
        handler.post(watch);
        handler.removeCallbacks(autoTick);
        handler.postDelayed(autoTick, AUTO_MS);
        overlay.invalidate();
    }

    private void pause() {
        if (active == null || !prepared) return;
        cancelSwap();
        handler.removeCallbacks(autoTick);
        try {
            activePlayer().pause();
        } catch (RuntimeException e) {
            AppLog.w("pause: " + e);
        }
        saveProgress();
        Progress.flush(this);     // paused, stopped or closed: on disk now (a power cut may follow)
        overlay.invalidate();
    }

    /** ← → : the target moves at once on screen; the player seeks once the keys rest. */
    void skip(long by) {
        if (!prepared) return;
        cancelSwap();
        long base = pendingSeek >= 0 ? pendingSeek : livePosition();
        pendingSeek = Math.max(0, Math.min(duration() - 1000, base + by));
        handler.removeCallbacks(commitSeek);
        handler.postDelayed(commitSeek, SEEK_SETTLE_MS);
        overlay.show();
    }

    private void commitSeek() {
        if (pendingSeek < 0 || active == null || !prepared) return;
        cancelSwap();
        if (deferred >= 0) {
            long at = pendingSeek;
            pendingSeek = -1;
            applyDeferred(at, true);
        } else {
            seek(pendingSeek);
            pendingSeek = -1;
        }
        overlay.invalidate();
    }

    long pendingSeek() {
        return pendingSeek;
    }

    private void saveProgress() {
        Library.Title t = current();
        long d = duration();
        if (t == null || d <= 0) return;
        long at = position();
        Progress.save(this, t, rootTitle(), rootPoster(), at, d);
        AppLog.w("progress " + t.id + " at " + at / 1000 + " s of " + d / 1000);
    }

    private String rootTitle() {
        return root == null ? "" : root.title;
    }

    private String rootPoster() {
        return root == null ? "" : root.poster;
    }

    private void requestFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null || focus != null) return;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
        }).build();
        am.requestAudioFocus(focus);
    }

    private void release() {
        cancelSwap();
        handler.removeCallbacks(autoTick);
        handler.removeCallbacks(save);
        handler.removeCallbacks(watch);
        handler.removeCallbacks(commitSeek);
        pendingSeek = -1;
        MediaPlayer old = active;
        active = null;
        if (old != null) {
            try {
                old.setOnErrorListener(null);
                old.release();
            } catch (RuntimeException e) {
                AppLog.w("release: " + e);
            }
        }
        prepared = false;
        if (streamer != null) {
            streamer.close();
            streamer = null;
        }
    }

    // MARK: - Lifecycle

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (holder != surface.getHolder()) return;     // not the one on screen (a swap's second view, or the old one leaving)
        surfaceReady = true;
        if (active != null) activePlayer().setDisplay(holder);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (holder != surface.getHolder()) return;
        surfaceReady = false;
        if (active != null) activePlayer().setDisplay(null);
    }

    @Override protected void onResume() {
        super.onResume();
        Thumbs.get(this).setListener(overlay::invalidate);
        resumed = true;
        Sync.resumed(this);
        if (prepared) play();
    }

    @Override protected void onPause() {
        Thumbs.get(this).setListener(null);
        resumed = false;
        pause();
        Sync.paused();
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        saveProgress();
        Sync.playing(null);
        Progress.flush(this);
        release();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
        overlay.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (overlay.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
