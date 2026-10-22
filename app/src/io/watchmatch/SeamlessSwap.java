package io.watchmatch;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Changes the quality without the person noticing: a second MediaPlayer (muted) prepares the new file on a second
 * SurfaceView stacked behind the one on screen, waits a little ahead of the picture, starts just before the picture
 * reaches it and, once both are within SwapRules.TARGET_DRIFT_MS, takes over in one step (its surface in front, the
 * sound moved, the old player released). SwapRules makes every decision; this only does what it says. A swap that
 * can't be made ready is dropped without a word. The player must run on the main thread.
 */
final class SeamlessSwap {
    /** What the player screen gives and takes. */
    interface Host {
        MediaPlayer activePlayer();
        SurfaceView activeView();
        FrameLayout stage();
        float baseSpeed();
        void fit(SurfaceView view, int videoWidth, int videoHeight);
        void matchAudio(MediaPlayer p);
        /** The new player is now the one on screen: the old player, surface and Streamer are the host's to drop. */
        void adopt(MediaPlayer p, SurfaceView view, Streamer streamer, Library.Source source);
        /** The second decoder couldn't run beside the first: no more swaps this session. */
        void unsupported(String why);
    }

    private static final long TICK_MS = 40;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Host host;
    private final android.content.Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SwapRules rules = new SwapRules();
    private MediaPlayer second;
    private SurfaceView view;
    private Streamer streamer;
    private Library.Source target;
    private String address;
    private boolean surfaceReady, secondPlaying;
    private int token;
    private long durationMs;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            step();
            if (rules.active()) handler.postDelayed(this, TICK_MS);
        }
    };

    SeamlessSwap(android.content.Context context, Host host) {
        this.context = context;
        this.host = host;
    }

    boolean active() {
        return rules.active();
    }

    boolean canBegin() {
        return rules.canBegin(SystemClock.uptimeMillis());
    }

    Library.Source target() {
        return target;
    }

    /** Starts preparing `to`; false when a swap is already going or one failed lately. */
    boolean begin(final Library.Source to) {
        long now = SystemClock.uptimeMillis();
        MediaPlayer main = host.activePlayer();
        if (main == null || !rules.canBegin(now)) return false;
        long at;
        try {
            at = main.getCurrentPosition();
            durationMs = main.getDuration();
        } catch (IllegalStateException e) {
            return false;
        }
        dispose();
        final int mine = ++token;
        target = to;
        rules.begin(now, at);
        IO.execute(() -> {
            String url = to.url;
            Streamer s = null;
            try {
                String[] probe = Streamer.probe(to.url);
                s = new Streamer(probe[0], Long.parseLong(probe[1]));
                url = s.address();
            } catch (Exception e) {
                AppLog.w("swap probe: " + e);
                url = null;
            }
            final String found = url;
            final Streamer opened = s;
            handler.post(() -> {
                if (mine != token || !rules.active() || found == null) {
                    if (opened != null) opened.close();
                    if (mine == token && rules.active()) giveUp();
                    return;
                }
                streamer = opened;
                address = found;
                makeView();
                handler.postDelayed(tick, TICK_MS);
            });
        });
        return true;
    }

    /** A seek, a pause, another episode, back, a manual pick: whatever was being prepared is dropped. */
    void cancel() {
        if (rules.active()) AppLog.w("swap cancelled");
        rules.cancel();
        dispose();
    }

    private void giveUp() {
        rules.fail(SystemClock.uptimeMillis());
        AppLog.w("swap given up");
        dispose();
    }

    private void makeView() {
        view = new SurfaceView(context);
        view.setZOrderMediaOverlay(true);     // same layer as the picture on screen, below it: this one is added first
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (second == null && rules.active()) makePlayer(holder);
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
            }
        });
        android.view.ViewGroup.LayoutParams lp = host.activeView().getLayoutParams();
        FrameLayout.LayoutParams own = lp instanceof FrameLayout.LayoutParams ? new FrameLayout.LayoutParams((FrameLayout.LayoutParams) lp)
                : new FrameLayout.LayoutParams(-1, -1);
        host.stage().addView(view, 0, own);
    }

    private void makePlayer(SurfaceHolder holder) {
        final MediaPlayer p = new MediaPlayer();
        second = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            p.setVolume(0f, 0f);
            p.setDisplay(holder);
            p.setOnPreparedListener(mp -> {
                if (mp != second) return;
                host.matchAudio(mp);
                rules.onPrepared();
                try {
                    mp.seekTo((int) rules.target(), MediaPlayer.SEEK_CLOSEST);
                } catch (RuntimeException e) {
                    AppLog.w("swap seek: " + e);
                }
            });
            p.setOnSeekCompleteListener(mp -> {
                if (mp == second) rules.onSeeked();
            });
            p.setOnBufferingUpdateListener((mp, percent) -> {
                if (mp == second) rules.onBuffered(durationMs * percent / 100);
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (mp == second && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rules.onRendered();
                return false;
            });
            p.setOnVideoSizeChangedListener((mp, vw, vh) -> {
                if (mp == second && view != null) host.fit(view, vw, vh);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                AppLog.w("swap player error " + what + "/" + extra + ": two decoders don't fit here");
                host.unsupported("second player error " + what + "/" + extra);
                giveUp();
                return true;
            });
            p.setDataSource(address);
            p.prepareAsync();
        } catch (Exception e) {
            AppLog.w("swap player: " + e);
            host.unsupported("second player: " + e);
            giveUp();
        }
    }

    private void step() {
        MediaPlayer main = host.activePlayer(), p = second;
        if (main == null) {
            giveUp();
            return;
        }
        long now = SystemClock.uptimeMillis(), at, there = 0;
        try {
            at = main.getCurrentPosition();
            if (p != null && rules.state() == SwapRules.State.ALIGNING) there = p.getCurrentPosition();
        } catch (IllegalStateException e) {
            giveUp();
            return;
        }
        SwapRules.Action a = rules.next(now, at, there, secondPlaying);
        try {
            switch (a) {
                case START_SECOND:
                    p.start();
                    secondPlaying = true;
                    speed(p, 1f);
                    break;
                case RESEEK:
                    p.seekTo((int) rules.target(), MediaPlayer.SEEK_CLOSEST);
                    break;
                case SLOW_DOWN:
                case SPEED_UP:
                case STEADY:
                    speed(p, rules.nudge());
                    break;
                case SWAP:
                    swap(p);
                    break;
                case ABORT:
                    AppLog.w("swap timed out in " + rules.state());
                    dispose();
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            AppLog.w("swap step " + a + ": " + e);
            giveUp();
        }
    }

    private void speed(MediaPlayer p, float factor) {
        try {
            p.setPlaybackParams(p.getPlaybackParams().setSpeed(host.baseSpeed() * factor));
        } catch (RuntimeException e) {
            AppLog.w("swap speed: " + e);
        }
    }

    /** One step: the sound across and the old picture hidden, which shows the new one behind it (the new view is never
     * moved: bringToFront would put its surface above the overlay and punch the subtitles and controls out). */
    private void swap(MediaPlayer p) {
        speed(p, 1f);
        p.setVolume(1f, 1f);
        MediaPlayer old = host.activePlayer();
        if (old != null) old.setVolume(0f, 0f);
        Streamer s = streamer;
        SurfaceView v = view;
        Library.Source src = target;
        // Handed over: this object lets go without releasing what is now the screen's.
        second = null;
        view = null;
        streamer = null;
        secondPlaying = false;
        handler.removeCallbacks(tick);
        host.adopt(p, v, s, src);
        rules.succeeded();
        AppLog.w("swap done: " + src.label);
    }

    private void dispose() {
        token++;
        handler.removeCallbacks(tick);
        secondPlaying = false;
        if (second != null) {
            second.setOnErrorListener(null);
            try {
                second.release();
            } catch (RuntimeException ignored) {
            }
            second = null;
        }
        if (view != null) {
            host.stage().removeView(view);
            view = null;
        }
        if (streamer != null) {
            streamer.close();
            streamer = null;
        }
    }
}
