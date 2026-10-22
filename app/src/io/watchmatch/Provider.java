package io.watchmatch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picks the catalog server that works on this network, without anyone choosing (the rules are ProviderRules'). Starts
 * from the one used last at once; every cold start, a network coming back, two failed catalog requests in a row, and a
 * return to the home screen after a long time away run a round that probes the enabled providers in parallel and may
 * switch. The person only sees which one is used (Settings) and a Re-check button. Each process (:home, :cinema) runs
 * its own, sharing the last answer through one small preferences file.
 */
final class Provider {
    private Provider() {}

    interface Listener { void onProviderChanged(); }

    /** `adb shell am start -n io.watchmatch/.HomeActivity --es provider_override el` (el, albox, other; auto clears it). */
    static final String EXTRA_OVERRIDE = "provider_override", EXTRA_PARALLEL = "parallel_fetch", EXTRA_AUTO_QUALITY = "auto_quality";
    private static final String FILE = "provider", K_ACTIVE = "active", K_CHECKED = "checked", K_OVERRIDE = "provider_override";

    private static final Object LOCK = new Object();
    private static final ExecutorService ROUNDS = Executors.newSingleThreadExecutor();
    private static final ExecutorService PROBES = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static Context app;
    private static SharedPreferences store;
    private static boolean started, running, firstNetworkEvent = true;
    private static volatile int retryIndex;
    private static int streak;
    private static long lastSwitch, lastRoundEnd, pausedAt;
    private static final Runnable RETRY = () -> detect(false, "retry");

    /** Once per process (further calls do nothing): the cached provider is in use now, a round runs behind it. */
    static void start(Context c) {
        synchronized (LOCK) {
            if (started) return;
            started = true;
            app = c.getApplicationContext();
        }
        String cached = Endpoints.usableId(sp().getString(K_ACTIVE, null));
        Endpoints.setActive(cached == null ? Endpoints.FALLBACK : cached);
        String forced = override();
        if (forced != null) Endpoints.setActive(forced);
        registerNetwork();
        detect(false, "start");
    }

    static Endpoints.Entry current() { return Endpoints.active(); }

    /** Fixed for troubleshooting: nothing is probed or switched. */
    static boolean fixed() { return override() != null; }

    /** When a round last found an answer (epoch ms, TrustedTime), 0 when never. */
    static long checkedAt() {
        return app == null ? 0 : sp().getLong(K_CHECKED, 0);
    }

    static boolean checking() {
        synchronized (LOCK) { return running; }
    }

    static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    static void removeListener(Listener l) { LISTENERS.remove(l); }

    /** Re-check in Settings: a round now that may switch whenever it likes. */
    static void recheck(Context c) {
        start(c);
        sp().edit().remove(K_OVERRIDE).apply();
        detect(true, "manual");
    }

    /**
     * The troubleshooting extras of an intent: EXTRA_OVERRIDE (el, albox, other; "auto" gives detection back) and
     * EXTRA_PARALLEL ("off" / "on": the mirror's parallel downloads).
     */
    static void applyIntent(Context c, Intent intent) {
        if (intent == null) return;
        String off = intent.getStringExtra(EXTRA_PARALLEL);
        if (off != null) {
            Prefs.setParallelFetch(!off.equalsIgnoreCase("off"));
            AppLog.w("parallel downloads: " + off);
        }
        String auto = intent.getStringExtra(EXTRA_AUTO_QUALITY);
        if (auto != null) {
            Prefs.setAutoQuality(!auto.equalsIgnoreCase("off"));
            AppLog.w("auto quality: " + auto);
        }
        String v = intent.getStringExtra(EXTRA_OVERRIDE);
        if (v == null) return;
        start(c);
        SharedPreferences.Editor e = sp().edit();
        if (Endpoints.usableId(v) != null) e.putString(K_OVERRIDE, v);
        else e.remove(K_OVERRIDE);
        e.commit();
        AppLog.w("provider override: " + v);
        String forced = override();
        if (forced != null) switchTo(forced);
        else detect(true, "override cleared");
    }

    // MARK: - Triggers

    /** The home screen went away / came back: after more than 30 minutes, a round runs. */
    static void paused() {
        pausedAt = TrustedTime.now();
    }

    static void resumed() {
        if (app == null) return;
        long was = pausedAt;
        pausedAt = 0;
        if (ProviderRules.awayTooLong(was, TrustedTime.now())) detect(false, "back after a while");
    }

    /** What a real catalog request on the provider in use came to: two failures in a row re-detect at once. */
    static void catalogResult(boolean ok, String error) {
        if (app == null) return;
        synchronized (LOCK) {
            if (ok) {
                streak = 0;
                return;
            }
            if (!ProviderRules.isNetworkFailure(error) || !ProviderRules.failureTrips(++streak)) return;
            streak = 0;
        }
        if (!fixed()) detect(false, "catalog requests failed");
    }

    private static void registerNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (firstNetworkEvent) {      // the one fired on registering: the start round is running already
                        firstNetworkEvent = false;
                        return;
                    }
                    retryIndex = 0;
                    if (SystemClock.elapsedRealtime() - lastRoundEnd > 5000) detect(false, "network available");
                }
            }, MAIN);
        } catch (RuntimeException e) {
            AppLog.w("provider: no network events: " + e);
        }
    }

    // MARK: - A round

    private static void detect(final boolean manual, final String why) {
        synchronized (LOCK) {
            if (running || app == null) return;
            running = true;
        }
        MAIN.removeCallbacks(RETRY);
        ROUNDS.execute(() -> {
            try {
                round(manual, why);
            } catch (RuntimeException | InterruptedException e) {
                AppLog.w("provider round: " + e);
            } finally {
                synchronized (LOCK) { running = false; }
                lastRoundEnd = SystemClock.elapsedRealtime();
                MAIN.post(Provider::notifyListeners);
            }
        });
    }

    private static void round(boolean manual, String why) throws InterruptedException {
        if (override() != null) return;
        List<Endpoints.Entry> list = ProviderRules.usable(Endpoints.ALL);
        List<String> order = new ArrayList<>();
        for (Endpoints.Entry e : list) order.add(e.id);
        final ProviderRules.Round r = new ProviderRules.Round(order);
        final long began = SystemClock.elapsedRealtime();
        for (final Endpoints.Entry e : list) {
            PROBES.execute(() -> {
                boolean good = probe(e);
                synchronized (LOCK) {
                    r.result(e.id, good, SystemClock.elapsedRealtime());
                    LOCK.notifyAll();
                }
            });
        }
        String pick;
        synchronized (LOCK) {
            while (true) {
                long now = SystemClock.elapsedRealtime();
                if (now - began >= ProviderRules.PROBE_TIMEOUT_MS) for (String id : order) r.result(id, false, now);
                pick = r.decide(now);
                if (!pick.equals(ProviderRules.WAIT)) break;
                long until = r.graceEnds() > 0 ? r.graceEnds() : began + ProviderRules.PROBE_TIMEOUT_MS;
                LOCK.wait(Math.max(20, Math.min(until, began + ProviderRules.PROBE_TIMEOUT_MS) - now));
            }
        }
        String active = Endpoints.active().id;
        if (pick.isEmpty()) {
            AppLog.w("provider (" + why + "): nobody answered, keeping " + active);
            long delay = ProviderRules.retryDelay(retryIndex++);
            if (delay >= 0) MAIN.postDelayed(RETRY, delay);
            return;
        }
        retryIndex = 0;
        sp().edit().putLong(K_CHECKED, TrustedTime.now()).apply();
        long now = TrustedTime.now();
        if (pick.equals(active)) {
            sp().edit().putString(K_ACTIVE, pick).apply();
        } else if (ProviderRules.mayApply(pick, active, manual, now, lastSwitch)) {
            lastSwitch = now;
            switchTo(pick);
        } else {
            AppLog.w("provider (" + why + "): " + pick + " answers, switching held back (less than a minute since the last one)");
        }
    }

    /** One probe: the mainCategories request must come back as a JSON array (a block page answers 200 with HTML). */
    private static boolean probe(Endpoints.Entry e) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(e.probeUrl()).openConnection();
            c.setConnectTimeout((int) ProviderRules.PROBE_TIMEOUT_MS);
            c.setReadTimeout((int) ProviderRules.PROBE_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return false;
            new JSONArray(Library.read(c.getInputStream()));
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void switchTo(String id) {
        String before = Endpoints.active().id;
        if (!Endpoints.setActive(id)) return;
        sp().edit().putString(K_ACTIVE, id).commit();
        streak = 0;
        AppLog.w("provider: " + before + " -> " + id);
        clearCaches();
        MAIN.post(Provider::notifyListeners);
    }

    /** What was fetched from the old server and kept on disk: the catalog answers and the home screen's saved picks. */
    private static void clearCaches() {
        File[] files = new File(app.getCacheDir(), "library").listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(app.getFilesDir(), "home-picks.json").delete();
    }

    private static void notifyListeners() {
        for (Listener l : LISTENERS) l.onProviderChanged();
    }

    // MARK: - State

    private static String override() {
        return app == null ? null : Endpoints.usableId(sp().getString(K_OVERRIDE, null));
    }

    @SuppressWarnings("deprecation")
    private static synchronized SharedPreferences sp() {
        if (store == null) store = app.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS);
        return store;
    }

    /** "5 min ago", for Settings: when a round last found a provider answering. */
    static String verified() {
        long at = checkedAt();
        if (at <= 0) return Ui.tr("Ainda não", "Not yet", "Aún no", "ليس بعد");
        long minutes = Math.max(0, (TrustedTime.now() - at) / 60_000);
        if (minutes < 1) return Ui.tr("Agora há pouco", "Just now", "Hace un momento", "الآن");
        if (minutes < 60) {
            String n = Ui.digits(String.valueOf(minutes));
            return Ui.tr("Há " + n + " min", n + " min ago", "Hace " + n + " min", "قبل " + n + " د");
        }
        String h = Ui.digits(String.valueOf(minutes / 60));
        return Ui.tr("Há " + h + " h", h + " h ago", "Hace " + h + " h", "قبل " + h + " س");
    }

    /** The provider's name for Settings. */
    static String label(Endpoints.Entry e) {
        if (Endpoints.ALBOX.equals(e.id)) return "Albox";
        if (Endpoints.OTHER.equals(e.id)) return Ui.tr("Outras redes", "Other networks", "Otras redes", "شبكات أخرى");
        return e.label;
    }
}
