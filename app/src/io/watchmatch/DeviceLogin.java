package io.watchmatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * "Sign in this TV with your phone": asks for a code (request_device_login, which makes the guest session when there is none),
 * shows it, and asks every 3 s (6 s after 2 minutes) whether the phone approved it (check_device_login). Every network call
 * runs on the sync thread; the state (DeviceLoginRules.Flow) is only touched on the main thread, once a second by a heartbeat
 * that also replaces an expired code. close() cancels all of it. The screen is AccountScreens.
 */
final class DeviceLogin {
    interface Listener {
        /** The state, the code or the countdown changed: redraw. */
        void changed();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long BEAT_MS = 1_000;

    private final Context app;
    private final Listener listener;
    private final DeviceLoginRules.Flow flow = new DeviceLoginRules.Flow();
    private final Runnable beat = this::onBeat;
    private boolean inFlight;

    DeviceLogin(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
    }

    DeviceLoginRules.Flow flow() {
        return flow;
    }

    /** Starts: a first code is requested. */
    void open() {
        inFlight = false;
        requestCode();
        MAIN.removeCallbacks(beat);
        MAIN.postDelayed(beat, BEAT_MS);
    }

    /** The screen closed: nothing that answers later counts and nothing is polled again. */
    void close() {
        flow.close();
        MAIN.removeCallbacks(beat);
    }

    /** "Try again" after a failure. */
    void retry() {
        if (flow.state != DeviceLoginRules.ERROR) return;
        requestCode();
        MAIN.removeCallbacks(beat);
        MAIN.postDelayed(beat, BEAT_MS);
    }

    private void requestCode() {
        final int gen = flow.request();
        listener.changed();
        boolean queued = Sync.submit(() -> {
            String code = null, error = null;
            try {
                Object r = Sync.client().rpc("request_device_login", MiniJson.obj("device_name", Sync.defaultDeviceName()), true);
                if (r instanceof String) code = (String) r;
                else error = AccountRules.E_ERROR;
            } catch (SyncClient.HttpException e) {
                error = AccountRules.error(e.status, e.getMessage());
                AppLog.w("device login request failed: " + e.status + " " + error);
            } catch (Exception e) {
                error = AccountRules.E_ERROR;
                AppLog.w("device login request failed: " + e.getClass().getSimpleName());
            }
            final String c = code, err = error;
            MAIN.post(() -> {
                if (err != null) flow.requestFailed(gen, err);
                else flow.requested(gen, c, SystemClock.elapsedRealtime());
                listener.changed();
            });
        });
        if (!queued) flow.requestFailed(gen, AccountRules.E_ERROR);
    }

    private void onBeat() {
        if (!flow.active()) return;
        long now = SystemClock.elapsedRealtime();
        if (flow.tick(now)) requestCode();      // the code ran out: a new one (the screen shows it by itself)
        else if (flow.pollDue(now) && !inFlight) poll(now);
        listener.changed();
        MAIN.postDelayed(beat, BEAT_MS);
    }

    private void poll(long now) {
        final int gen = flow.polling(now);
        inFlight = true;
        boolean queued = Sync.submit(() -> {
            String space = null, error = null;
            int step = AccountRules.STEP_LINK;
            try {
                Object r = Sync.client().rpc("check_device_login", null, false);
                if (r instanceof String && !((String) r).isEmpty()) {
                    space = (String) r;
                    step = AccountRules.mergeStep(Sync.space(), space, Sync.hasLocalData());
                }
            } catch (SyncClient.HttpException e) {
                error = AccountRules.error(e.status, e.getMessage());
            } catch (Exception e) {
                error = AccountRules.E_ERROR;
            }
            final String sp = space, err = error;
            final int st = step;
            MAIN.post(() -> {
                inFlight = false;
                if (err != null) flow.pollFailed(gen, err);
                else flow.polled(gen, sp, st);
                afterState();
            });
        });
        if (!queued) inFlight = false;
    }

    /** The answer to "Add this TV's data to your account?". */
    void chooseData(boolean add) {
        flow.chose(add);
        afterState();
    }

    private void afterState() {
        if (flow.state == DeviceLoginRules.LINKING && !flow.fetching) finish();
        listener.changed();
    }

    /** Signs the TV in: the first full pull runs on the sync thread, then the screen moves on. */
    private void finish() {
        flow.fetching();
        MAIN.removeCallbacks(beat);
        final int gen = flow.gen;
        final String space = flow.space;
        final int step = flow.step;
        final boolean add = flow.plan != AccountRules.LINK_FRESH;
        boolean queued = Sync.submit(() -> {
            String error = null;
            try {
                Account.finishDeviceLink(app, space, step, add);
            } catch (RuntimeException e) {
                error = AccountRules.E_ERROR;
                AppLog.w("device login link failed: " + e.getClass().getSimpleName());
            }
            final String err = error;
            MAIN.post(() -> {
                if (flow.gen != gen) return;      // closed meanwhile (the link itself is done and stays)
                if (err != null) flow.linkFailed(err);
                else flow.linked();
                listener.changed();
            });
        });
        if (!queued) flow.linkFailed(AccountRules.E_ERROR);
    }
}
