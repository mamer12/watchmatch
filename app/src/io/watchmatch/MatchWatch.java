package io.watchmatch;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Match Centre over any app, for your teams: a reminder 10 minutes before kick-off, then the live score in a corner
 * (updated every minute) with a flash on each goal, until the final whistle. Woken by an alarm before kick-off; runs as
 * a foreground service while it's on screen, in the home screen's process (it shares Matches). Needs "display over
 * other apps", which WatchMatch gives itself through the TV's ADB when switching.
 */
public class MatchWatch extends Service implements Matches.Listener {
    private static final long REMIND_BEFORE = 10 * 60_000L, WAKE_BEFORE = 12 * 60_000L, TICK = 60_000L;
    private static final long REMINDER_MS = 15_000, GOAL_MS = 10_000, FINAL_MS = 30_000;
    private static final String CHANNEL = "matches";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Matches matches;
    private Thumbs thumbs;
    private WindowManager wm;
    private Bubble bubble;
    private String remindedId = "", lastScore = "";
    private long goalAt, reminderAt, doneAt;

    /** Sets the alarm for your team's next match (or starts watching now if it's close or on). */
    static void schedule(Context c) {
        Matches.Match next = Matches.get(c).myNext(7 * 24 * 3600_000L);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent wake = PendingIntent.getService(c, 0, new Intent(c, MatchWatch.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am == null) return;
        am.cancel(wake);
        // Home Settings > Match Centre > Alerts off: no alarm (the service, if it's on, stops on its next minute).
        if (next == null || !Settings.canDrawOverlays(c) || Prefs.matchAlerts().equals("off")) return;
        long at = next.start - WAKE_BEFORE;
        if (next.state == Matches.LIVE || at <= TrustedTime.now()) {
            start(c);
            return;
        }
        at -= TrustedTime.offset();     // alarms go by the TV's clock, which may still be wrong after a power cut
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, wake);
        } catch (SecurityException e) {     // Android 12+ without exact alarms: a few minutes early is fine
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - 5 * 60_000L, wake);
        }
    }

    private static void start(Context c) {
        Intent i = new Intent(c, MatchWatch.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            AppLog.w("match watch: " + e.getMessage());
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        matches = Matches.get(this);
        thumbs = Thumbs.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Ui.tr("Jogos", "Matches", "Partidos"), NotificationManager.IMPORTANCE_MIN));
        }
        Notification n = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(Ui.tr("Acompanhando seu jogo", "Following your match", "Siguiendo tu partido"))
                .build();
        startForeground(7, n);
        matches.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        matches.removeListener(this);
        hide();
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            matches.refreshIfStale();
            update();
            handler.postDelayed(this, TICK);
        }
    };

    @Override public void onMatches() {
        update();
    }

    /** What to show now: the reminder, the live score (with a goal's flash), the final score for a moment, or nothing. */
    private void update() {
        long now = TrustedTime.now();
        // Match Centre settings: "all" is everything below; "goals" only a goal's flash; "off" nothing. In the quiet
        // hours nothing shows, but the score is still followed (a goal then isn't flashed later).
        String alerts = Prefs.matchAlerts();
        if (alerts.equals("off")) {
            hide();
            stopSelf();
            return;
        }
        boolean quiet = Prefs.quietNow(now), all = alerts.equals("all");
        Matches.Match m = matches.myNext(REMIND_BEFORE + 60_000L);
        if (m == null) {
            // Over (or nothing close): the final score for a moment, then stop until the next one.
            if (bubble != null && bubble.match != null && doneAt == 0) {
                doneAt = now;
                bubble.state = Bubble.FINAL;
                bubble.invalidate();
            }
            if (bubble == null || now - doneAt > FINAL_MS) {
                hide();
                schedule(this);
                stopSelf();
            }
            return;
        }
        doneAt = 0;
        if (m.state == Matches.PRE && !m.id.equals(remindedId)) {
            remindedId = m.id;
            reminderAt = now;
            if (!all || quiet) return;
            show(m, Bubble.REMINDER);
            handler.postDelayed(this::update, REMINDER_MS + 500);
            return;
        }
        if (m.state == Matches.PRE) {
            if (now - reminderAt > REMINDER_MS) hide();
            return;
        }
        String score = m.home.score + "-" + m.away.score;
        boolean goal = !lastScore.isEmpty() && !score.equals(lastScore) && m.state == Matches.LIVE;
        lastScore = score;
        if (goal) {
            goalAt = now;
            handler.postDelayed(this::update, GOAL_MS + 500);
        }
        boolean flash = now - goalAt < GOAL_MS;
        if (quiet || !all && !flash) {
            hide();
            return;
        }
        show(m, flash ? Bubble.GOAL : Bubble.LIVE);
    }

    private void show(Matches.Match m, int state) {
        if (!Settings.canDrawOverlays(this)) return;
        if (bubble == null) {
            bubble = new Bubble(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Ui.rtl ? Gravity.TOP | Gravity.LEFT : Gravity.TOP | Gravity.END;     // right to left: top left
            float k = getResources().getDisplayMetrics().widthPixels / 1920f;
            lp.x = Math.round(48 * k);
            lp.y = Math.round(40 * k);
            try {
                wm.addView(bubble, lp);
            } catch (RuntimeException e) {
                AppLog.w("match overlay: " + e.getMessage());
                bubble = null;
                return;
            }
        }
        bubble.match = m;
        bubble.state = state;
        bubble.requestLayout();
        bubble.invalidate();
    }

    private void hide() {
        if (bubble == null) return;
        try {
            wm.removeView(bubble);
        } catch (RuntimeException ignored) {
        }
        bubble = null;
    }

    /** The corner card: crests, score and minute; "starts in 10 min" before; gold "GOAL!" for a moment after one. */
    private final class Bubble extends View {
        static final int REMINDER = 0, LIVE = 1, GOAL = 2, FINAL = 3;
        Matches.Match match;
        int state;
        private final float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), crest = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint big = Ui.numbers(Ui.semibold, 40 * k, 0xFFFFFFFF), small = Ui.text(Ui.semibold, 22 * k, 0xCCFFFFFF);
        private final RectF r = new RectF();

        Bubble(Context c) {
            super(c);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(state == REMINDER ? 640 * k : 420 * k), Math.round(state == GOAL ? 150 * k : 112 * k));
        }

        @Override protected void onDraw(Canvas c) {
            if (match == null) return;
            r.set(0, 0, getWidth(), getHeight());
            fill.setColor(state == GOAL ? 0xF2E8B53A : 0xEB101318);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            int px = Math.round(56 * k);
            float cy = state == GOAL ? 92 * k : getHeight() / 2f;
            float wide = getWidth();
            if (state == REMINDER) {
                drawCrest(c, match.home.logo, Ui.mx(52 * k, 0, wide), cy, px);
                drawCrest(c, match.away.logo, Ui.mx(116 * k, 0, wide), cy, px);
                small.setColor(0xCCFFFFFF);
                Ui.text(c, Ui.tr("Começa em 10 min · TOD", "Starts in 10 min · TOD", "Empieza en 10 min · TOD"), 160 * k, cy - 10 * k, small, wide);
                big.setTextSize(30 * k);
                Ui.text(c, match.home.shortName + " vs " + match.away.shortName, 160 * k, cy + 28 * k, big, wide);
                big.setTextSize(40 * k);
                return;
            }
            if (state == GOAL) {
                big.setColor(0xFF111111);
                Ui.text(c, Ui.tr("GOL!", "GOAL!", "¡GOL!"), 32 * k, 52 * k, big, wide);
            }
            // Right to left: the home team on the right, its score next to it.
            drawCrest(c, match.home.logo, Ui.mx(52 * k, 0, wide), cy, px);
            drawCrest(c, match.away.logo, Ui.mx(getWidth() - 52 * k, 0, wide), cy, px);
            String score = Ui.rtl ? match.away.score + " - " + match.home.score : match.home.score + " - " + match.away.score;
            big.setColor(state == GOAL ? 0xFF111111 : 0xFFFFFFFF);
            c.drawText(score, getWidth() / 2f - big.measureText(score) / 2, cy + 6 * k, big);
            String note = state == FINAL ? Ui.tr("Fim", "Full time", "Final") : "● " + match.clock;
            small.setColor(state == GOAL ? 0xCC111111 : state == FINAL ? 0xCCFFFFFF : 0xFFFF453A);
            c.drawText(note, getWidth() / 2f - small.measureText(note) / 2, cy + 38 * k, small);
            big.setColor(0xFFFFFFFF);
        }

        private void drawCrest(Canvas c, String url, float cx, float cy, int px) {
            Bitmap b = url.isEmpty() ? null : thumbs.get(Thumbs.key(url, px, px, Thumbs.FIT), url, px, px, Thumbs.FIT);
            if (b == null) {
                postInvalidateDelayed(1000);     // still loading
                return;
            }
            r.set(cx - px / 2f, cy - px / 2f, cx + px / 2f, cy + px / 2f);
            c.drawBitmap(b, null, r, crest);
            r.set(0, 0, getWidth(), getHeight());
        }
    }
}
