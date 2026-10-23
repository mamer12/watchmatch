package io.watchmatch;

import android.app.Activity;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Netflix's moving picture behind the focused title: once the focus rests on it a moment, the title itself plays under
 * CinemaView (its trailers are YouTube links, which can't play here), with sound unless the settings say otherwise:
 * from the beginning when it's new, from where it was left when it isn't (a series: the episode it's at), and on until
 * the focus moves. Play then continues from that second (position()); a minute or more of it counts as watched.
 */
final class CinemaPreview implements SurfaceHolder.Callback {
    interface Listener { void onPreview(boolean showing); }

    // CinemaView asks only once the focus rests (its own pause skips fast scrolling), so this is short: Netflix's
    // still picture turns to video within a second or two.
    private static final long DELAY_MS = 250, COUNTS_MS = 60_000;
    private static final int HEIGHT = 480;
    private static final float OVERSCAN = 1.04f;
    private static final float VOLUME = 0.8f;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final SurfaceView surface;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    /** The player's fast reader (parallel ranges, both ends of the file first), as PlayerActivity's. */
    private Streamer streamer;
    private boolean surfaceReady, showing;
    private int generation;
    private Library.Title pending;
    /** A title whose page was opened from outside (Spark, search): its preview plays muted and without subtitles, over a page nobody chose to rest on. */
    private String quietId;
    /** Where it plays: the focused card's picture, or null for the whole screen; the video's own size. */
    private RectF target;
    private int videoW, videoH;
    /** What's playing: the video (an episode for a series), its series' name and poster, where it started. */
    private Library.Title playing;
    private String rootTitle = "", rootPoster = "";
    private long startedAt, askedAt;
    /** The video's subtitles in the language picked (null: none or still loading). */
    private volatile Srt subtitles;

    private final Runnable start = this::start;

    CinemaPreview(Activity a, SurfaceView surface, Listener listener) {
        activity = a;
        this.surface = surface;
        this.listener = listener;
        surface.getHolder().addCallback(this);
    }

    /**
     * The focus rested on `t` (null: it moved, or the page went away): the clip starts after a moment. The same title
     * again (its page opened from its card, a row refreshed) keeps playing as it is.
     */
    void want(Library.Title t, RectF where) {
        want(t, where, DELAY_MS);
    }

    void want(Library.Title t, RectF where, long delayMs) {
        if (t == null || !t.id.equals(quietId)) quietId = null;
        if (t != null && pending != null && t.id.equals(pending.id) && (showing || generation > 0 && player != null)) {
            target = where == null ? null : new RectF(where);      // from the card to its page: it just moves
            cover(videoW, videoH);
            return;
        }
        stop();
        pending = t;
        askedAt = SystemClock.uptimeMillis();
        target = where == null ? null : new RectF(where);
        if (t != null && CinemaPrefs.previews(activity) != CinemaPrefs.PREVIEW_OFF) handler.postDelayed(start, delayMs);
    }

    /** The page of title `id` was just opened from outside: let its preview play without sound or subtitles. */
    void quiet(String id) {
        quietId = id;
        if (player != null && quiet()) player.setVolume(0, 0);
        subtitles = null;
    }

    private boolean quiet() {
        return pending != null && pending.id.equals(quietId);
    }

    void stop() {
        handler.removeCallbacks(start);
        generation++;
        subtitles = null;
        if (player != null) {
            countAsWatched();
            player.setOnErrorListener(null);
            player.release();
            player = null;
        }
        if (streamer != null) {
            streamer.close();
            streamer = null;
        }
        if (showing) {
            showing = false;
            listener.onPreview(false);
        }
    }

    /** The video playing now and its position (ms), for Play to continue from; null when nothing plays. */
    Library.Title playing() {
        return showing ? playing : null;
    }

    long position() {
        return showing && player != null ? player.getCurrentPosition() : 0;
    }

    private void start() {
        final Library.Title t = pending;
        if (t == null || !surfaceReady) return;
        final int gen = ++generation;
        final boolean silent = quiet();
        final Library library = Library.get(activity);
        LOADER.execute(() -> {
            try {
                // A series: the episode it's at (Progress.nextEpisode); an episode or a movie: itself.
                Library.Title video = t;
                String name = "", poster = "";
                if (t.series && t.rootId.isEmpty()) {
                    List<Library.Title> eps = library.episodesNow(t.id);
                    int[] next = Progress.nextEpisode(activity, eps);
                    if (next[0] < 0) return;
                    video = eps.get(next[0]);
                    name = t.title;
                    poster = t.poster;
                } else if (!t.rootId.isEmpty()) {
                    Progress.Entry e = Progress.get(activity, t.id);
                    if (e != null) {
                        name = e.rootTitle;
                        poster = e.rootPoster;
                    }
                }
                List<Library.Source> sources = library.sources(video.id);
                if (sources.isEmpty()) return;
                if (!silent) loadSubtitles(library, video.id, gen);
                String[] probe = Streamer.probe(PlayerActivity.pick(sources, HEIGHT).url);
                String address = probe[0];
                Streamer reader = null;
                try {
                    reader = new Streamer(probe[0], Long.parseLong(probe[1]));
                    address = reader.address();
                } catch (Exception e) {
                    AppLog.w("preview streamer: " + e);      // straight from the CDN then
                }
                final String url = address;
                final Streamer started = reader;
                final long from = Progress.resumeAt(activity, video.id);
                final Library.Title v = video;
                final String n = name, p = poster;
                handler.post(() -> {
                    if (gen != generation) {
                        if (started != null) started.close();
                        return;
                    }
                    streamer = started;
                    playing = v;
                    rootTitle = n;
                    rootPoster = p;
                    startedAt = from;
                    play(gen, url, from);
                });
            } catch (Exception e) {
                AppLog.w("preview: " + e);
            }
        });
    }

    /** Subtitles too, like the player's (in the background: the picture doesn't wait for them). */
    private void loadSubtitles(final Library library, final String id, final int gen) {
        subtitles = null;
        final String language = CinemaPrefs.subtitles(activity);
        if ("off".equals(language)) return;
        library.background(() -> {
            Library.Subtitles files = library.subtitles(id);
            String file = "ar".equals(language) ? files.arabic : files.english;
            if (file.isEmpty()) return;
            try {
                Srt s = Srt.load(file);
                if (gen == generation) subtitles = s;
            } catch (Exception e) {
                AppLog.w("preview subtitles: " + e);
            }
        });
    }

    /** The subtitle on screen now, or null. */
    String subtitleNow() {
        Srt s = subtitles;
        if (!showing || s == null || player == null) return null;
        try {
            return s.at(player.getCurrentPosition());
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** A minute or more of preview is watching: it goes to Continue watching (and Resume) like the player's. */
    private void countAsWatched() {
        if (!showing || playing == null || player == null) return;
        try {
            long at = player.getCurrentPosition(), d = player.getDuration();
            if (d > 0 && at - startedAt >= COUNTS_MS) Progress.save(activity, playing, rootTitle, rootPoster, at, d);
        } catch (IllegalStateException ignored) {
        }
    }

    private void play(final int gen, String url, final long from) {
        if (gen != generation || !surfaceReady) return;
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        float volume = CinemaPrefs.previews(activity) == CinemaPrefs.PREVIEW_SOUND && !quiet() ? VOLUME : 0;
        mp.setVolume(volume, volume);
        mp.setOnPreparedListener(p -> {
            if (gen != generation) return;
            if (from > 0) p.seekTo((int) from);
            p.start();
        });
        mp.setOnInfoListener((p, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && gen == generation) {
                AppLog.w("preview " + playing.id + " showing after " + (SystemClock.uptimeMillis() - askedAt) + " ms");
                showing = true;
                listener.onPreview(true);
            }
            return false;
        });
        mp.setOnVideoSizeChangedListener((p, vw, vh) -> {
            videoW = vw;
            videoH = vh;
            cover(vw, vh);
        });
        mp.setOnCompletionListener(p -> stop());
        mp.setOnErrorListener((p, what, extra) -> {
            stop();
            return true;
        });
        try {
            mp.setDisplay(surface.getHolder());
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (Exception e) {
            AppLog.w("preview: " + e);
            stop();
        }
    }

    /**
     * Fills its place (the screen, or the card's picture), cropping what's over; the page covers what lies outside
     * a card (it leaves only the picture open).
     */
    private void cover(int vw, int vh) {
        int sw = activity.getWindow().getDecorView().getWidth(), sh = activity.getWindow().getDecorView().getHeight();
        if (vw <= 0 || vh <= 0 || sw <= 0 || sh <= 0) return;
        RectF r = target == null ? new RectF(0, 0, sw, sh) : target;
        // A little over the place: some decoders leave a line of green at the frame's edges; it falls outside.
        float scale = Math.max(r.width() / vw, r.height() / vh) * OVERSCAN;
        int w = Math.round(vw * scale), h = Math.round(vh * scale);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round(r.centerX() - w / 2f);
        lp.topMargin = Math.round(r.centerY() - h / 2f);
        surface.setLayoutParams(lp);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stop();
    }
}
