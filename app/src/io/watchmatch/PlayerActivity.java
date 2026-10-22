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
    private static final long STARTED_SAVE_MS = 3_500, SAVE_EVERY_MS = 5_000, SEEK_SETTLE_MS = 700, NEXT_UP_MS = 40_000, WATCH_MS = 250;
    private static final long AUTO_MS = 1000;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    final List<Library.Title> playlist = new ArrayList<>();
    private Library.Title root;
    int index;
    private boolean fromStart;
    /** Where the first video starts (ms): the second its preview had reached; -1 for its saved place. */
    private long firstAt = -1;

    /** The view the picture is on now (a swap puts the second one here). */
    private SurfaceView surface;
    private FrameLayout stage;
    private PlayerOverlay overlay;
    /** The player on screen (the only one, but for a swap's few seconds): read it through activePlayer(). */
    private MediaPlayer active;
    private Streamer streamer;
    private boolean surfaceReady, prepared, resumed, upNextSaved, rendered;
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
    private AudioFocusRequest focus;

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
        subtitles = null;
        subtitleOffset = 0;
        audioTracks.clear();
        audioNames.clear();
        info = new Library.Info();
        startAt = firstAt >= 0 ? firstAt : fromStart ? 0 : Progress.resumeAt(this, t.id);
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
                startPlayer(url, at);
            });
        });
    }

    private void startPlayer(String url, long at) {
        startAt = at;
        bufferedMs = 0;
        final MediaPlayer player = new MediaPlayer();
        active = player;
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        player.setOnPreparedListener(mp -> {
            prepared = true;
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
            failed(-1, 0);
        }
    }

    /** What the player on screen reports (also given to a second player when a swap makes it the one on screen). */
    private void bind(MediaPlayer player) {
        player.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) overlay.loading(true);
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered = true;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) overlay.loading(false);
            return false;
        });
        player.setOnBufferingUpdateListener((mp, percent) -> bufferedMs = Math.max(0, mp.getDuration()) * (long) percent / 100);
        player.setOnVideoSizeChangedListener((mp, vw, vh) -> fit(surface, vw, vh));
        player.setOnCompletionListener(mp -> finished());
        player.setOnErrorListener((mp, what, extra) -> {
            failed(what, extra);
            return true;
        });
    }

    /**
     * The player gave up. First the same file straight from the CDN; then, only for 4K, 1080p, saying so (the TV
     * couldn't decode it or the line is too slow: the picked quality stays saved for the next video).
     */
    private void failed(int what, int extra) {
        AppLog.w("player error " + what + "/" + extra + " at " + (source == null ? "" : source.label));
        long at = position();
        if (!triedDirect && streamer != null) {
            triedDirect = true;
            open(generation, at);
            return;
        }
        if (source != null && source.height > DEFAULT_HEIGHT) {
            source = pick(sources, DEFAULT_HEIGHT);
            triedDirect = false;
            overlay.toast(Ui.tr("4K não carregou nesta TV: tocando em ", "4K couldn't play here: playing ",
                    "4K no se pudo reproducir: reproduciendo en ") + source.label);
            open(generation, at);
            return;
        }
        overlay.message(Ui.tr("Não foi possível tocar este vídeo.", "This video couldn't be played.",
                "No se pudo reproducir este video."));
    }

    private void finished() {
        Library.Title t = current();
        if (t != null) Progress.save(this, t, rootTitle(), rootPoster(), duration(), duration());
        markUpNext();
        Progress.flush(this);
        if (hasNext() && CinemaPrefs.autoplay(this)) next();
        else finish();
    }

    void next() {
        if (hasNext()) play(index + 1);
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
        if (!playing() || pendingSeek >= 0) return;
        long p = activePlayer().getCurrentPosition(), d = duration();
        if (CinemaPrefs.skipScenes(this)) {
            for (long[] s : info.scenes) {
                if (p >= s[0] && p < s[1] - 300) {
                    cancelSwap();
                    activePlayer().seekTo((int) s[1]);
                    overlay.toast(Ui.tr("Cena pulada (modo família)", "Scene skipped (family mode)", "Escena omitida (modo familia)"));
                    return;
                }
            }
        }
        if (!upNextSaved && d > 0 && d - p < NEXT_UP_MS) markUpNext();
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
        long p = activePlayer().getCurrentPosition();
        for (long[] i : info.intros) if (p >= i[0] && p < i[1] - 1500) return i[1];
        return -1;
    }

    /** The last NEXT_UP_MS of an episode with one after it: the "Next episode" button. */
    boolean nextUp() {
        long d = duration();
        return hasNext() && prepared && d > 0 && d - position() < NEXT_UP_MS;
    }

    void skipTo(long ms) {
        if (!prepared) return;
        pendingSeek = -1;
        cancelSwap();
        if (deferred >= 0) {
            applyDeferred(ms, false);
            return;
        }
        activePlayer().seekTo((int) ms);
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
        else if (support == SWAP_OK) swap.begin(sources.get(to));
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
        bind(p);
        p.setOnPreparedListener(null);
        p.setOnSeekCompleteListener(null);
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
        return active != null && prepared && activePlayer().isPlaying();
    }

    long position() {
        if (pendingSeek >= 0) return pendingSeek;
        return active != null && prepared ? activePlayer().getCurrentPosition() : startAt;
    }

    long duration() {
        return active != null && prepared ? Math.max(0, activePlayer().getDuration()) : 0;
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
        activePlayer().start();
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
        activePlayer().pause();
        saveProgress();
        Progress.flush(this);     // paused, stopped or closed: on disk now (a power cut may follow)
        overlay.invalidate();
    }

    /** ← → : the target moves at once on screen; the player seeks once the keys rest. */
    void skip(long by) {
        if (!prepared) return;
        cancelSwap();
        long base = pendingSeek >= 0 ? pendingSeek : activePlayer().getCurrentPosition();
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
            activePlayer().seekTo((int) pendingSeek);
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
        if (active != null) {
            activePlayer().setOnErrorListener(null);
            activePlayer().release();
            active = null;
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
