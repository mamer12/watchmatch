package io.watchmatch;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-device sync for Watchme+ (Continue watching, My List, ratings and a few settings) over a Supabase backend,
 * the same one the web app uses (see SyncMap, SyncRules, SyncEngine, SyncClient; the logic is checked on a plain JVM).
 * Hidden and off unless the build has supabase_url and supabase_anon_key.
 *
 * It runs in the one process that owns Watchme+'s data (:cinema inside WatchMatch, the main process of WatchMePlus.apk),
 * on its own thread: nothing here blocks the UI or throws to it. The library stores call nudge() and flush() when
 * something is saved; everything else (queueing, retrying, pulling, applying) happens here, one task at a time.
 */
final class Sync {
    private Sync() {}

    enum Phase { OFF, SYNCING, IDLE, OFFLINE, ERROR }

    /** Sync changed: its status, or (dataChanged) the library under the screens. Called on the main thread. */
    interface Listener { void onSync(boolean dataChanged); }

    /** The answer of an account action, on the main thread: the result, or the error ("wrong", "too_many_attempts", "network", "error"). */
    interface Callback<T> { void done(T result, String error); }

    /** One line of list_devices. */
    static final class Device {
        String name = "";
        long joinedAt;
        boolean me;
    }

    private static final String FILE = "sync-state.json";
    private static final long NUDGE_MS = 250, QUIET_MS = 15_000, KICK_WAIT_MS = 6_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean NUDGE_PENDING = new AtomicBoolean();

    /** Bumped by Reset and Disconnect: work started before it neither writes the library nor sends anything. */
    private static volatile int epoch;
    private static volatile boolean active;
    private static Context app;
    private static ScheduledExecutorService exec;
    private static SyncClient client;
    // Touched on the sync thread only (the volatile ones are read by the screens):
    private static SyncState st = new SyncState();
    private static SyncEngine engine;
    private static int failures, visible;
    private static long lastProgressPush = -SyncRules.PROGRESS_PUSH_MS, lastCycleEnd = -QUIET_MS;
    private static ScheduledFuture<?> pushFuture, retryFuture, pollFuture;
    private static long pushDue;

    private static volatile Phase phase = Phase.OFF;
    private static volatile boolean authLost, linked;
    private static volatile long lastSyncAt;
    private static volatile String playing, deviceName = "", firstMessage;
    private static volatile Listener listener;

    // ---- on and off -------------------------------------------------------------------------

    /** Built with a backend (supabase_url and supabase_anon_key in config.properties). */
    static boolean enabled() {
        return Config.SUPABASE_URL.startsWith("https://") && !Config.SUPABASE_ANON_KEY.isEmpty();
    }

    /** This process is the one that owns Watchme+'s data: :cinema inside WatchMatch, the whole app in WatchMePlus.apk. */
    static boolean ownsData(Context c) {
        String process = Application.getProcessName();
        if (process == null) return false;
        return process.endsWith(":cinema") || (c.getPackageName().equals(CinemaActivity.STANDALONE) && process.equals(c.getPackageName()));
    }

    /** App.onCreate: loads what was saved and, when this TV is linked, syncs. Harmless when it is off or not this process's job. */
    static synchronized void start(Context c) {
        if (active || !enabled() || !ownsData(c)) return;
        app = c.getApplicationContext();
        SyncEngine.log = AppLog::w;
        exec = Executors.newSingleThreadScheduledExecutor();
        client = new SyncClient(Config.SUPABASE_URL, Config.SUPABASE_ANON_KEY, sessionStore(), TrustedTime::now);
        active = true;
        exec.execute(() -> {
            st = SyncState.fromJson(Durable.read(file()));
            if (st.linked() && !client.signedIn()) {
                // The tokens are gone (cleared data): this TV has to be paired again.
                authLost = true;
            }
            publish();
            if (st.linked()) {
                buildEngine();
                cycle();
            }
        });
        TrustedTime.addListener(() -> {
            if (exec != null) exec.execute(() -> {
                failures = 0;
                cycle();
            });
        });
    }

    static int epoch() {
        return epoch;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static void playing(String videoId) {
        playing = videoId;
    }

    static String playingId() {
        return playing;
    }

    private static File file() {
        return new File(app.getFilesDir(), FILE);
    }

    private static SyncClient.Store sessionStore() {
        final SharedPreferences sp = app.getSharedPreferences("sync-session", Context.MODE_PRIVATE);
        return new SyncClient.Store() {
            @Override public SyncClient.Session load() {
                String a = sp.getString("a", ""), r = sp.getString("r", ""), u = sp.getString("u", "");
                return a.isEmpty() || r.isEmpty() || u.isEmpty() ? null
                        : new SyncClient.Session(a, r, u, sp.getLong("e", 0), sp.getString("m", ""));
            }

            @Override public void save(SyncClient.Session s) {
                SharedPreferences.Editor ed = sp.edit();
                if (s == null) ed.clear();
                else ed.putString("a", s.access).putString("r", s.refresh).putString("u", s.userId).putLong("e", s.expiresAt).putString("m", s.email);
                Durable.save(sp, ed);     // a refresh token is single use: it must be on disk before anything else happens
            }
        };
    }

    private static void buildEngine() {
        final Context c = app;
        final int mine = epoch;
        final SyncEngine.Api api = new SyncEngine.Api() {
            private void check() throws SyncClient.HttpException {
                if (mine != epoch) throw new SyncClient.HttpException(401, "signed_out");
            }

            @Override public String userId() {
                return client.userId();
            }

            @Override public void upsert(String table, List<Map<String, Object>> rows) throws SyncClient.HttpException {
                check();
                client.upsert(table, rows);
            }

            @Override public List<Map<String, Object>> pull(String table, String space, String cursor, int limit)
                    throws SyncClient.HttpException {
                check();
                List<Map<String, Object>> rows = client.pull(table, space, cursor, limit);
                check();
                return rows;
            }
        };
        engine = new SyncEngine(st, api, new SyncLocal(c, mine), s -> {
            if (mine != epoch) return;
            try {
                Durable.write(file(), s.toJson());
            } catch (Exception e) {
                AppLog.w("sync state not saved: " + e);
            }
        });
    }

    // ---- the library changed ----------------------------------------------------------------

    /** Something was saved (progress, My List, a rating, a setting): looks at what differs, soon. Any thread, cheap. */
    static void nudge(Context c) {
        if (!active || !NUDGE_PENDING.compareAndSet(false, true)) return;
        exec.schedule(() -> {
            NUDGE_PENDING.set(false);
            if (engine == null) return;
            try {
                engine.reconcile(TrustedTime.now(), st.device);
                schedulePush();
            } catch (RuntimeException e) {
                AppLog.w("sync nudge: " + e);
            }
        }, NUDGE_MS, TimeUnit.MILLISECONDS);
    }

    /** Paused, stopped, finished or left the player: what is queued goes out now. */
    static void flush(Context c) {
        if (!active) return;
        exec.execute(() -> {
            if (engine == null) return;
            cancel(pushFuture);
            pushFuture = null;
            engine.reconcile(TrustedTime.now(), st.device);
            pushNow();
        });
    }

    private static void schedulePush() {
        if (engine == null || st.outbox.isEmpty()) return;
        boolean onlyProgress = true;
        for (SyncRules.Item i : st.outbox) if (!"watch_progress".equals(i.table)) onlyProgress = false;
        long now = SystemClock.elapsedRealtime();
        long due = now + SyncRules.pushDelay(now, lastProgressPush, onlyProgress);
        if (pushFuture != null && !pushFuture.isDone() && pushDue <= due) return;
        cancel(pushFuture);
        pushDue = due;
        pushFuture = exec.schedule(Sync::pushNow, Math.max(0, due - now), TimeUnit.MILLISECONDS);
    }

    private static void pushNow() {
        if (engine == null || st.outbox.isEmpty()) return;
        if (!online()) {
            settle(new SyncClient.HttpException(0, "offline"));
            return;
        }
        try {
            boolean progress = false;
            for (SyncRules.Item i : st.outbox) if ("watch_progress".equals(i.table)) progress = true;
            engine.pushAll();
            if (progress) lastProgressPush = SystemClock.elapsedRealtime();
            succeed();
        } catch (SyncClient.HttpException e) {
            settle(e);
        } catch (RuntimeException e) {
            AppLog.w("sync push: " + e);
            settle(new SyncClient.HttpException(500, String.valueOf(e)));
        }
    }

    // ---- the cycle: pull, then push ---------------------------------------------------------

    /** Looks at the library, pulls what is new and pushes what is queued. Rows applied. On the sync thread. */
    private static int cycle() {
        if (engine == null || !st.linked() || authLost) return 0;
        cancel(retryFuture);
        retryFuture = null;
        try {
            engine.reconcile(TrustedTime.now(), st.device);
            if (!online()) {
                settle(new SyncClient.HttpException(0, "offline"));
                return 0;
            }
            setPhase(Phase.SYNCING);
            engine.pullAll();
            engine.pushAll();
            return succeed();
        } catch (SyncClient.HttpException e) {
            settle(e);
        } catch (RuntimeException e) {
            AppLog.w("sync cycle: " + e);
            settle(new SyncClient.HttpException(500, String.valueOf(e)));
        }
        return 0;
    }

    private static int succeed() {
        failures = 0;
        cancel(retryFuture);
        retryFuture = null;
        String first = engine.takeFirstSyncMessage();
        if (first != null) firstMessage = first;
        int applied = engine.takeApplied();
        lastSyncAt = TrustedTime.now();
        st.lastSyncAt = lastSyncAt;
        lastCycleEnd = SystemClock.elapsedRealtime();
        phase = Phase.IDLE;
        notifyChanged(applied > 0);
        return applied;
    }

    private static void settle(SyncClient.HttpException e) {
        phase = e.status == 0 ? Phase.OFFLINE : Phase.ERROR;
        if (e.status == 401 && "auth_lost".equals(e.getMessage())) {
            authLost = true;      // only a new code helps
            phase = Phase.ERROR;
            notifyChanged(false);
            return;
        }
        AppLog.w("sync " + (e.status == 0 ? "offline" : "error " + e.status + " " + e.getMessage()));
        notifyChanged(false);
        if (retryFuture == null || retryFuture.isDone()) {
            retryFuture = exec.schedule(() -> {
                cycle();
            }, SyncRules.retryDelay(++failures), TimeUnit.MILLISECONDS);
        }
    }

    private static void setPhase(Phase p) {
        phase = p;
        notifyChanged(false);
    }

    private static boolean online() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    // ---- when the screens are up ------------------------------------------------------------

    /** A Watchme+ screen is in front: pulls now and every minute while any is. */
    static void resumed(Context c) {
        if (!active) return;
        exec.execute(() -> {
            if (++visible == 1) pollFuture = exec.scheduleWithFixedDelay(() -> {
                if (visible > 0) cycle();
            }, SyncRules.POLL_MS, SyncRules.POLL_MS, TimeUnit.MILLISECONDS);
            cycle();
        });
    }

    static void paused() {
        if (!active) return;
        exec.execute(() -> {
            visible = Math.max(0, visible - 1);
            if (visible == 0) {
                cancel(pollFuture);
                pollFuture = null;
            }
        });
    }

    /**
     * The home screen (another process) asks for a pull, through a provider: waits a few seconds for it and says how many
     * rows changed, as {"applied":n}. Recent enough pulls are not repeated.
     */
    static String kickJson(Context c) {
        int applied = 0;
        if (active && linked) {
            try {
                Future<Integer> f = exec.submit(new Callable<Integer>() {
                    @Override public Integer call() {
                        if (SystemClock.elapsedRealtime() - lastCycleEnd < QUIET_MS) return 0;
                        return cycle();
                    }
                });
                applied = f.get(KICK_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                AppLog.w("sync kick: " + e);
            }
        }
        return "{\"applied\":" + applied + "}";
    }

    // ---- status -----------------------------------------------------------------------------

    /** Sync is built in and runs in this process: Settings shows its row. */
    static boolean available() {
        return active;
    }

    /** Pulls and pushes now (Settings, Sync now). */
    static void syncNow() {
        if (!active) return;
        exec.execute(() -> {
            failures = 0;
            cycle();
        });
    }

    static boolean isLinked() {
        return active && linked;
    }

    static String deviceName() {
        return deviceName;
    }

    /** After the first sync brought items in: "Synced 12 items from Phone" (else null). */
    static String firstSyncLine() {
        String m = firstMessage;
        if (m == null) return null;
        int bar = m.indexOf('|');
        String n = Ui.digits(bar < 0 ? m : m.substring(0, bar));
        String from = bar < 0 || bar == m.length() - 1 ? "" : m.substring(bar + 1);
        boolean one = "1".equals(bar < 0 ? m : m.substring(0, bar));
        String src = from.isEmpty() ? "" : " " + Ui.isolate(from);
        return Ui.tr("Sincronizado: " + n + (one ? " item" : " itens") + (from.isEmpty() ? "" : " de" + src),
                "Synced " + n + (one ? " item" : " items") + (from.isEmpty() ? "" : " from" + src),
                "Sincronizado: " + n + (one ? " elemento" : " elementos") + (from.isEmpty() ? "" : " de" + src),
                "تمت مزامنة " + n + (one ? " عنصر" : " عناصر") + (from.isEmpty() ? "" : " من" + src));
    }

    /** "Synced just now", "Offline - will sync later", "Error - retrying", "Not linked". */
    static String statusLine() {
        if (!linked) return Ui.tr("Não vinculado", "Not linked", "No vinculado", "غير مرتبط");
        if (authLost) return Ui.tr("Erro — vincule esta TV de novo", "Error — link this TV again", "Error — vincula este TV de nuevo",
                "خطأ — اربط هذا التلفاز من جديد");
        switch (phase) {
            case OFFLINE: return Ui.tr("Sem internet — sincroniza depois", "Offline — will sync later", "Sin conexión — se sincronizará después",
                    "غير متصل — ستتم المزامنة لاحقاً");
            case ERROR: return Ui.tr("Erro — tentando de novo", "Error — retrying", "Error — reintentando", "خطأ — إعادة المحاولة");
            case SYNCING: return Ui.tr("Sincronizando…", "Syncing…", "Sincronizando…", "جارٍ المزامنة…");
            default:
        }
        long minutes = lastSyncAt == 0 ? 0 : Math.max(0, (TrustedTime.now() - lastSyncAt) / 60_000);
        if (lastSyncAt == 0 || minutes < 2) return Ui.tr("Sincronizado agora", "Synced just now", "Sincronizado ahora", "تمت المزامنة الآن");
        String n = Ui.digits(String.valueOf(minutes < 120 ? minutes : minutes / 60));
        return minutes < 120
                ? Ui.tr("Sincronizado há " + n + " min", "Synced " + n + " min ago", "Sincronizado hace " + n + " min", "تمت المزامنة قبل " + n + " د")
                : Ui.tr("Sincronizado há " + n + " h", "Synced " + n + " h ago", "Sincronizado hace " + n + " h", "تمت المزامنة قبل " + n + " س");
    }

    private static void publish() {
        linked = st.linked();
        deviceName = st.device;
        lastSyncAt = st.lastSyncAt;
        phase = !linked ? Phase.OFF : phase == Phase.OFF ? Phase.IDLE : phase;
        notifyChanged(false);
    }

    private static void notifyChanged(final boolean dataChanged) {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onSync(dataChanged);
        });
    }

    // ---- account actions (each on the sync thread; the answer on the main thread) -----------

    private static <T> void run(final Callback<T> cb, final Callable<T> work) {
        if (!active) {
            deliver(cb, null, "error");
            return;
        }
        exec.execute(() -> {
            try {
                deliver(cb, work.call(), null);
            } catch (SyncClient.HttpException e) {
                deliver(cb, null, SyncRules.joinError(e.status, e.getMessage()));
            } catch (Exception e) {
                AppLog.w("sync action: " + e);
                deliver(cb, null, "error");
            }
        });
    }

    private static <T> void deliver(final Callback<T> cb, final T result, final String error) {
        MAIN.post(() -> cb.done(result, error));
    }

    /** "TV" and the model, for the device list ("TV TCL 55C635"). */
    static String defaultDeviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.replaceAll("\\s+", " ").trim();
        String name = model.isEmpty() ? "TV" : "TV " + model;
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    /** Creates this person's private space and starts syncing. */
    static void startSpace(Callback<Boolean> cb) {
        run(cb, () -> {
            String name = defaultDeviceName();
            Object id = client.rpc("create_space", MiniJson.obj("device_name", name), true);
            if (!(id instanceof String) || ((String) id).isEmpty()) throw new SyncClient.HttpException(500, "bad_response");
            link((String) id, name);
            return Boolean.TRUE;
        });
    }

    /** Joins the space the code belongs to; the error is "wrong" for a wrong, expired or used code. */
    static void join(final String code, Callback<Boolean> cb) {
        run(cb, () -> {
            String name = defaultDeviceName();
            Object id = client.rpc("redeem_pairing_code", MiniJson.obj("code", SyncRules.normalizeCode(code), "device_name", name), true);
            if (!(id instanceof String) || ((String) id).isEmpty()) throw new SyncClient.HttpException(200, "wrong");
            link((String) id, name);
            return Boolean.TRUE;
        });
    }

    /** A new 8-character code for another device. */
    static void newCode(Callback<String> cb) {
        run(cb, () -> {
            Object code = client.rpc("create_pairing_code", null, false);
            if (!(code instanceof String)) throw new SyncClient.HttpException(500, "bad_response");
            return (String) code;
        });
    }

    static void devices(Callback<List<Device>> cb) {
        run(cb, () -> {
            List<Device> out = new ArrayList<>();
            for (Object o : MiniJson.list(client.rpc("list_devices", null, false))) {
                Map<String, Object> m = MiniJson.map(o);
                Device d = new Device();
                d.name = MiniJson.str(m.get("device_name"));
                d.joinedAt = SyncRules.parseTime(m.get("joined_at"));
                d.me = MiniJson.bool(m.get("is_me"));
                out.add(d);
            }
            return out;
        });
    }

    /** Leaves the space and forgets the link here; this TV's own data stays. */
    static void leave(Callback<Boolean> cb) {
        run(cb, () -> {
            try {
                client.rpc("leave_space", null, false);
            } catch (SyncClient.HttpException e) {
                AppLog.w("sync leave: " + e.getMessage());      // offline: the server forgets us with its clean-up
            }
            clearLocal();
            return Boolean.TRUE;
        });
    }

    /** Deletes every synced row on the server; other devices keep their own copies and this TV stays linked. */
    static void deleteData(Callback<Boolean> cb) {
        run(cb, () -> {
            client.rpc("delete_space_data", null, false);
            st.outbox = new ArrayList<>();
            st.cursors.clear();
            Durable.write(file(), st.toJson());
            return Boolean.TRUE;
        });
    }

    // ---- hooks for Account (accounts: sign in, sign out, delete) ----------------------------

    /** Runs `r` on the sync thread; false when sync is not running in this process. */
    static boolean submit(Runnable r) {
        if (!active) return false;
        exec.execute(r);
        return true;
    }

    static SyncClient client() {
        return client;
    }

    /** The session store (the same file as the client's). */
    static SyncClient.Store sessions() {
        return sessionStore();
    }

    /** Sync thread only: the space this TV is in ("" when none). */
    static String space() {
        return st.space;
    }

    /** The TV has something of its own to merge: progress, a My List, ratings or more than the one default profile. */
    static boolean hasLocalData() {
        if (app == null) return false;
        if (Profiles.live(app).size() > 1) return true;
        for (SyncEngine.Snapshot s : new SyncLocal(app, epoch).snapshots().values()) {
            if (!s.progress.isEmpty() || !s.myList.isEmpty() || !s.ratings.isEmpty()) return true;
        }
        return false;
    }

    /** Sync thread only: moves this TV into `space` (after the session was saved) and runs the first sync. */
    static void linkTo(String space, String device) {
        link(space, device);
    }

    /** Sync thread only: forgets the link, the outbox, the cursors and the session; the library stays. */
    static void forgetLink() {
        clearLocal();
    }

    /**
     * Reset {catalog} data: forgets the link and the session here, first, and deletes nothing on the server. Work in
     * flight stops writing and sending at once (the epoch); the rest of the clean-up follows on the sync thread.
     */
    static void signOutLocal(Context c) {
        epoch++;
        linked = false;
        phase = Phase.OFF;
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), FILE).delete();
        Account.forgetEmail(c);
        SharedPreferences sp = c.getSharedPreferences("sync-session", Context.MODE_PRIVATE);
        Durable.save(sp, sp.edit().clear());
        if (active) exec.execute(Sync::clearLocal);
        notifyChanged(false);
    }

    private static void link(String space, String device) {
        cancel(pushFuture);
        cancel(retryFuture);
        st = new SyncState();
        st.space = space;
        st.device = device;
        authLost = false;
        failures = 0;
        buildEngine();
        try {
            Durable.write(file(), st.toJson());
        } catch (Exception e) {
            AppLog.w("sync state not saved: " + e);
        }
        publish();
        phase = Phase.SYNCING;
        cycle();
    }

    private static void clearLocal() {
        epoch++;
        cancel(pushFuture);
        cancel(retryFuture);
        pushFuture = retryFuture = null;
        engine = null;
        st = new SyncState();
        failures = 0;
        authLost = false;
        firstMessage = null;
        //noinspection ResultOfMethodCallIgnored
        file().delete();
        sessionStore().save(null);
        phase = Phase.OFF;
        publish();
    }
}
