package io.watchmatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Watchme+ accounts (email and password) on top of Sync: sign in, create (a guest becomes the account in place), sign out,
 * change password, delete. The rules are AccountRules; the calls are SyncClient's. Every action runs on the sync thread and
 * answers on the main thread. The password goes into the one request that needs it and nowhere else (no field, no file, no log).
 * `account.json` (email, guest chosen) is readable by every process: the home screen's "Signed in as".
 */
final class Account {
    private Account() {}

    /** An action's answer: ok, a failure (AccountRules.E_*), or "ask" (sign-in found data on this TV: add it or not?). */
    static final class Result {
        final String error;
        final boolean ask;

        private Result(String error, boolean ask) {
            this.error = error;
            this.ask = ask;
        }

        boolean ok() {
            return error == null && !ask;
        }
    }

    interface Done {
        void done(Result r);

        /** The password was accepted and the profiles are being fetched ("Getting your profiles…"). */
        default void progress() { }
    }

    private static final Result OK = new Result(null, false), ASK = new Result(null, true);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String FILE = "account.json", MARKER = "account marker";

    // ---- what is kept: the email and whether guest was chosen -----------------------------------

    private static String email = "";
    private static boolean guest;
    private static long stamp = -1, length = -1;

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    private static synchronized void load(Context c) {
        File f = file(c);
        long s = f.lastModified(), l = f.length();
        if (s == stamp && l == length) return;
        stamp = s;
        length = l;
        email = "";
        guest = false;
        String text = Durable.read(f);
        if (text == null) return;
        try {
            java.util.Map<String, Object> m = MiniJson.map(MiniJson.parse(text));
            email = AccountRules.cleanEmail(MiniJson.str(m.get("email")));
            guest = MiniJson.bool(m.get("guest"));
        } catch (RuntimeException e) {
            email = "";
        }
    }

    private static synchronized void save(Context c, String mail, boolean chosenGuest) {
        try {
            Durable.write(file(c), MiniJson.write(MiniJson.obj("email", mail, "guest", chosenGuest)));
        } catch (Exception e) {
            AppLog.w("account marker not saved: " + e.getClass().getSimpleName());
        }
        stamp = -1;
    }

    /** The account's email as last saved, "" for a guest. Any process, cheap. */
    static String email(Context c) {
        load(c);
        return email;
    }

    static boolean guestChosen(Context c) {
        load(c);
        return guest;
    }

    /** "Continue as guest" was chosen: the welcome screen does not come back. */
    static void markGuest(Context c) {
        save(c, "", true);
    }

    /** Reset {catalog} data ends the session: the email goes, the choice of guest stays. */
    static void forgetEmail(Context c) {
        load(c);
        if (!email.isEmpty()) save(c, "", guest);
    }

    /** Who is signed in: an account's email, "" for a guest or nobody. */
    static String signedInEmail(Context c) {
        SyncClient cl = Sync.client();
        if (cl != null && cl.signedIn()) return cl.email();
        return email(c);
    }

    static boolean hasSession() {
        SyncClient cl = Sync.client();
        return cl != null && cl.signedIn();
    }

    static int mode(Context c) {
        return AccountRules.mode(hasSession(), signedInEmail(c));
    }

    /** Accounts exist in this build, in this process. */
    static boolean available() {
        return Sync.available();
    }

    /** What Watchme+ shows first (AccountRules.startPlan). */
    static int startPlan(Context c) {
        return AccountRules.startPlan(Sync.available(), hasSession(), Sync.isLinked(), email(c), guestChosen(c));
    }

    // ---- running an action ------------------------------------------------------------------

    private static void run(String what, Done cb, Callable<Result> work) {
        boolean queued = Sync.submit(() -> {
            Result r;
            try {
                r = work.call();
            } catch (SyncClient.HttpException e) {
                String code = AccountRules.error(e.status, e.getMessage());
                AppLog.w("account " + what + " failed: " + e.status + " " + code);
                r = new Result(code, false);
            } catch (Exception e) {
                AppLog.w("account " + what + " failed: " + e.getClass().getSimpleName());
                r = new Result(AccountRules.E_ERROR, false);
            }
            final Result answer = r;
            MAIN.post(() -> cb.done(answer));
        });
        if (!queued) cb.done(new Result(AccountRules.E_ERROR, false));
    }

    private static String spaceId(Object id) throws SyncClient.HttpException {
        if (!(id instanceof String) || ((String) id).isEmpty()) throw new SyncClient.HttpException(500, "bad_response");
        return (String) id;
    }

    private static SyncClient.Store memory(final SyncClient.Session[] box) {
        return new SyncClient.Store() {
            @Override public SyncClient.Session load() {
                return box[0];
            }

            @Override public void save(SyncClient.Session s) {
                box[0] = s;
            }
        };
    }

    // ---- sign in ----------------------------------------------------------------------------

    /** A password accepted and the account's space known, waiting for "add this TV's data?" (tokens only, in memory). */
    private static final class Pending {
        final SyncClient.Session session;
        final String space, device;
        final int step;

        Pending(SyncClient.Session session, String space, String device, int step) {
            this.session = session;
            this.space = space;
            this.device = device;
            this.step = step;
        }
    }

    private static volatile Pending pending;

    /**
     * Signs in. The session is kept apart until it is settled where this TV goes: the answer is OK, a failure, or ASK
     * when this TV has data the account does not (then chooseData() finishes it).
     */
    static void signIn(final Context c, final String mail, final String password, Done cb) {
        final Context app = c.getApplicationContext();
        run("sign-in", cb, () -> {
            SyncClient.Session[] box = new SyncClient.Session[1];
            SyncClient probe = new SyncClient(Config.SUPABASE_URL, Config.SUPABASE_ANON_KEY, memory(box), TrustedTime::now);
            probe.signInPassword(AccountRules.cleanEmail(mail), password);
            String device = Sync.defaultDeviceName();
            String space = spaceId(probe.rpc("create_space", MiniJson.obj("device_name", device), false));
            int step = AccountRules.mergeStep(Sync.space(), space, Sync.hasLocalData());
            pending = new Pending(box[0], space, device, step);
            if (step == AccountRules.STEP_ASK) return ASK;
            MAIN.post(cb::progress);
            finishSignIn(app, true);
            return OK;
        });
    }

    /** The answer to "Add this TV's data to your account?": yes merges, no clears the TV first. */
    static void chooseData(final Context c, final boolean add, Done cb) {
        final Context app = c.getApplicationContext();
        run("sign-in", cb, () -> {
            MAIN.post(cb::progress);
            finishSignIn(app, add);
            return OK;
        });
    }

    /** Back on that question: nothing was changed on this TV. */
    static void cancelSignIn() {
        pending = null;
    }

    /** Sync thread. */
    private static void finishSignIn(Context c, boolean addData) throws SyncClient.HttpException {
        Pending p = pending;
        if (p == null) throw new SyncClient.HttpException(401, "no_session");
        int plan = AccountRules.linkPlan(p.step, addData);
        String old = Sync.space();
        if (plan == AccountRules.LINK_KEEP_STATE) {
            Sync.sessions().save(p.session);
            save(c, p.session.email, guestChosen(c));
            Sync.syncNow();
        } else {
            SyncClient guestClient = Sync.client();
            if (AccountRules.leavesOldSpace(old, p.space) && guestClient != null && guestClient.signedIn()) {
                try {
                    guestClient.rpc("leave_space", null, false);
                } catch (SyncClient.HttpException e) {
                    AppLog.w("account: old space not left: " + e.status);      // the server's clean-up forgets it
                }
            }
            Sync.forgetLink();
            if (plan == AccountRules.LINK_FRESH) WatchmeReset.runLocal(c);
            Sync.sessions().save(p.session);
            save(c, p.session.email, guestChosen(c));
            Sync.linkTo(p.space, p.device);     // the first sync: this account's profiles and data come in
        }
        pending = null;
        markWelcomed(c);
    }

    /** An existing account's profiles were set up elsewhere: their first launch here must not ask the questions again. */
    private static void markWelcomed(Context c) {
        for (ProfileRules.Profile p : Profiles.live(c)) {
            ProfilePrefs.with(p.id, () -> {
                if (!CinemaPrefs.welcomed(c)) CinemaPrefs.setWelcomed(c);
                return null;
            });
        }
    }

    // ---- create -----------------------------------------------------------------------------

    /** Create account: a guest becomes the account in place (same user, same space, same data); with no session it signs up. */
    static void create(final Context c, final String mail, final String password, Done cb) {
        final Context app = c.getApplicationContext();
        run("create", cb, () -> {
            SyncClient cl = Sync.client();
            String clean = AccountRules.cleanEmail(mail);
            int kind = AccountRules.createKind(cl.signedIn(), cl.email());
            if (kind == AccountRules.CREATE_NONE) throw new SyncClient.HttpException(409, "already_account");
            if (kind == AccountRules.CREATE_UPGRADE) cl.upgrade(clean, password);
            else cl.signUpPassword(clean, password);
            save(app, clean, guestChosen(app));
            if (Sync.space().isEmpty()) {
                String device = Sync.defaultDeviceName();
                Sync.linkTo(spaceId(cl.rpc("create_space", MiniJson.obj("device_name", device), false)), device);
            } else {
                Sync.syncNow();
            }
            return OK;
        });
    }

    // ---- change password, sign out, delete ------------------------------------------------------

    static void changePassword(String password, Done cb) {
        run("change-password", cb, () -> {
            Sync.client().changePassword(password);
            return OK;
        });
    }

    /** Ends the session here, then wipes this TV (the data stays in the account). The caller leaves Watchme+ after. */
    static void signOut(final Context c, Done cb) {
        final Context app = c.getApplicationContext();
        run("sign-out", cb, () -> {
            wipe(app, () -> {
                SyncClient cl = Sync.client();
                if (cl != null && cl.signedIn()) cl.logout();
            });
            return OK;
        });
    }

    /** Deletes the account on the server (delete_my_account), then wipes this TV. */
    static void deleteAccount(final Context c, Done cb) {
        final Context app = c.getApplicationContext();
        run("delete", cb, () -> {
            Sync.client().rpc("delete_my_account", null, false);
            wipe(app, () -> { });
            return OK;
        });
    }

    private static void wipe(final Context c, ResetRules.Step logout) {
        ResetRules.Step[] reset = WatchmeReset.all(c);
        ResetRules.Step[] steps = java.util.Arrays.copyOf(reset, reset.length + 1);
        steps[reset.length] = () -> {
            save(c, "", false);
            Library.arabicFirst = false;
        };
        String[] names = java.util.Arrays.copyOf(WatchmeReset.NAMES, WatchmeReset.NAMES.length + 1);
        names[WatchmeReset.NAMES.length] = MARKER;
        ResetRules.Result r = AccountRules.signOut(logout, names, steps);
        for (String f : r.failed) AppLog.w("sign-out: " + f);
    }
}
