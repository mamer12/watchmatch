package io.watchmatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The time of day even when the TV's clock is wrong. TVs without a clock battery (TCL) boot after a power cut at their
 * factory date and get the real time a minute or two later; meanwhile HTTPS fails ("certificate not yet valid") and
 * the clock on screen would be years off. This asks Google's connectivity check (plain HTTP, which works with any
 * clock) for its Date header at startup and whenever a network connects, and keeps the difference when the TV's
 * clock is more than 2 min off. Timed on the time since boot, so it stays right while the TV's clock jumps.
 * Each process asks on its own (App starts it); the answer is shared with the others started later in the same boot.
 */
final class TrustedTime {
    private TrustedTime() {}

    /** The clock was set, the time zone changed, a server's time arrived or a network came up (main thread). */
    interface Listener { void onTimeOrNetwork(); }

    private static final String[] SOURCES = {
        "http://connectivitycheck.gstatic.com/generate_204", "http://clients3.google.com/generate_204",
    };
    private static final long TOLERANCE = 2 * 60_000L;
    /** A stamp this long before the build was made by a clock that was wrong (factory dates are years back). */
    private static final long PLAUSIBLE = 180 * 86_400_000L;
    private static final long[] SYNC_RETRY = {5_000, 15_000, 45_000, 120_000};

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Runnable RETRY = TrustedTime::sync;

    /** A server's time this boot: serverBase at elapsedBase (SystemClock.elapsedRealtime). */
    private static volatile boolean known;
    private static volatile long serverBase, elapsedBase;
    private static volatile boolean syncing, validated;
    private static int syncFailures;
    private static Context app;

    /** Once per process (App.onCreate): the answer saved this boot, a fresh one, and the clock and network events. */
    static synchronized void start(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        NET.execute(TrustedTime::loadShared);
        sync();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { clockChanged(); }
            }, f, null, MAIN);
        } catch (RuntimeException e) {
            AppLog.w("time: no clock events: " + e);
        }
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    sync();
                }

                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    boolean now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (now && !validated) {
                        sync();
                        notifyListeners();
                    }
                    validated = now;
                }

                @Override public void onLost(Network network) {
                    validated = false;
                }
            }, MAIN);
        } catch (RuntimeException e) {
            AppLog.w("time: no network events: " + e);
        }
    }

    /** Now, in epoch ms: the TV's clock, or a server's time when the TV's is more than 2 min off. No allocations. */
    static long now() {
        long sys = System.currentTimeMillis();
        if (!known) return sys;
        long server = serverBase + (SystemClock.elapsedRealtime() - elapsedBase);
        return Math.abs(server - sys) > TOLERANCE ? server : sys;
    }

    /** How far the TV's clock is behind (positive) or ahead: 0 while it's right or no server answered yet. */
    static long offset() {
        return now() - System.currentTimeMillis();
    }

    /**
     * The TV's clock is clearly wrong (before this app was built) and no server's time came yet: nothing may be
     * pruned or expired by age now.
     */
    static boolean suspect() {
        return !known && System.currentTimeMillis() < Config.BUILD_TIME;
    }

    /** A saved stamp that a right clock could have made (not one from a factory date after a power cut). */
    static boolean plausible(long at) {
        return at >= Config.BUILD_TIME - PLAUSIBLE;
    }

    static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    static void removeListener(Listener l) { LISTENERS.remove(l); }

    private static void notifyListeners() {
        MAIN.post(() -> { for (Listener l : LISTENERS) l.onTimeOrNetwork(); });
    }

    private static void clockChanged() {
        if (known) {
            long off = offset();
            AppLog.w("time: the TV's clock changed" + (off == 0 ? ", now right" : ", still " + off / 1000 + " s off"));
        } else {
            sync();
        }
        notifyListeners();
    }

    // MARK: - The server's time

    /** Asks for the time in the background (one request at a time); retries a few times while nothing answers. */
    static void sync() {
        if (app == null || syncing) return;
        syncing = true;
        MAIN.removeCallbacks(RETRY);
        NET.execute(() -> {
            boolean ok = false;
            try {
                for (int i = 0; i < SOURCES.length && !ok; i++) ok = fetch(SOURCES[i]);
            } finally {
                syncing = false;
            }
            final boolean answered = ok;
            MAIN.post(() -> {
                if (answered) {
                    syncFailures = 0;
                } else if (!known && syncFailures < SYNC_RETRY.length) {
                    MAIN.postDelayed(RETRY, SYNC_RETRY[syncFailures++]);
                }
            });
        });
    }

    private static boolean fetch(String url) {
        HttpURLConnection c = null;
        try {
            long sent = SystemClock.elapsedRealtime();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);    // a Wi-Fi sign-in page isn't Google's clock
            int code = c.getResponseCode();
            long date = c.getHeaderFieldDate("Date", 0);
            long got = SystemClock.elapsedRealtime();
            if (code != 204 || date <= 0) return false;
            // Whole seconds, stamped about halfway through the round trip.
            set(date + 500 + (got - sent) / 2, got);
            return true;
        } catch (Exception e) {
            AppLog.w("time from " + url + ": " + e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void set(long server, long elapsed) {
        boolean first = !known;
        long before = now();
        serverBase = server;
        elapsedBase = elapsed;
        known = true;
        long off = offset();
        if (off != 0) AppLog.w("time: the TV's clock is " + off / 1000 + " s off; using the server's");
        saveShared();
        if (first || Math.abs(now() - before) > TOLERANCE) notifyListeners();
    }

    // MARK: - Shared with this app's other processes (valid for this boot only)

    private static SharedPreferences shared() {
        return app.getSharedPreferences("trusted-time", Context.MODE_PRIVATE);
    }

    private static int bootCount() {
        try {
            return Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void loadShared() {
        try {
            SharedPreferences sp = shared();
            int boot = bootCount();
            long elapsed = sp.getLong("elapsed", -1);
            if (known || boot < 0 || sp.getInt("boot", -2) != boot || elapsed < 0 || elapsed > SystemClock.elapsedRealtime()) {
                return;
            }
            serverBase = sp.getLong("server", 0);
            elapsedBase = elapsed;
            known = true;
            if (offset() != 0) notifyListeners();
        } catch (RuntimeException e) {
            AppLog.w("time: saved answer not read: " + e);
        }
    }

    private static void saveShared() {
        int boot = bootCount();
        if (boot < 0) return;
        shared().edit().putInt("boot", boot).putLong("server", serverBase).putLong("elapsed", elapsedBase).apply();
    }
}
