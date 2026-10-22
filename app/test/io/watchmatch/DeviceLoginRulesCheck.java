package io.watchmatch;

/**
 * Checks "Sign in this TV with your phone" on a plain JVM: how the code is shown and the QR link built, the poll schedule
 * (3 s, then 6 s after 2 minutes), and the flow's state machine: request, pending, approved, add this TV's data (or not),
 * linking, ready; an expired code is replaced; answers that arrive after a refresh or after closing do nothing; failures
 * and the rate limit read as words. From the repository's root (android.jar only so that Backoff compiles):
 *
 *   AJ=$(ls "$ANDROID_HOME"/platforms/android-*\/android.jar | sort -V | tail -n 1)
 *   mkdir -p out/test && javac -cp "$AJ" -d out/test app/src/io/watchmatch/{Backoff,ResetRules,SyncRules,AccountRules,DeviceLoginRules}.java \
 *       app/test/io/watchmatch/DeviceLoginRulesCheck.java && java -cp out/test io.watchmatch.DeviceLoginRulesCheck
 */
public final class DeviceLoginRulesCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    private static final String CODE = "K7M2QX4P", CODE2 = "ABCDEFGH";

    public static void main(String[] args) {
        display();
        schedule();
        happyPath();
        withLocalData();
        sameSpace();
        expiry();
        staleAnswers();
        failures();
        cancel();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static DeviceLoginRules.Flow pending(long now) {
        DeviceLoginRules.Flow f = new DeviceLoginRules.Flow();
        int g = f.request();
        f.requested(g, CODE, now);
        return f;
    }

    private static void display() {
        eq("the code is shown in two groups", "K7M2 QX4P", DeviceLoginRules.group(CODE));
        eq("a wrong-length code is shown as it is", "K7M", DeviceLoginRules.group("K7M"));
        eq("no code is nothing", "", DeviceLoginRules.group(null));
        eq("the QR link", "https://watch-me-plus.vercel.app/?tv=K7M2QX4P", DeviceLoginRules.qrUrl("https://watch-me-plus.vercel.app", CODE));
        eq("trailing slashes in the site are dropped", "https://x.io/?tv=K7M2QX4P", DeviceLoginRules.qrUrl("https://x.io//", CODE));
        eq("the countdown at the start", "10:00", DeviceLoginRules.clock(DeviceLoginRules.TTL_MS));
        eq("the countdown rounds up", "9:59", DeviceLoginRules.clock(DeviceLoginRules.TTL_MS - 1_001));
        eq("seconds are padded", "0:07", DeviceLoginRules.clock(6_200));
        eq("the countdown never goes below zero", "0:00", DeviceLoginRules.clock(-5_000));
    }

    private static void schedule() {
        eq("polls every 3 seconds at first", 3_000L, DeviceLoginRules.pollDelay(0));
        eq("still 3 seconds just before 2 minutes", 3_000L, DeviceLoginRules.pollDelay(119_999));
        eq("then every 6 seconds", 6_000L, DeviceLoginRules.pollDelay(120_000));
        DeviceLoginRules.Flow f = pending(1_000);
        eq("not due right after the code showed", false, f.pollDue(1_000));
        eq("not due after 2.9 s", false, f.pollDue(3_900));
        eq("due after 3 s", true, f.pollDue(4_000));
        f.polling(4_000);
        eq("not due right after a poll", false, f.pollDue(4_001));
        eq("due again 3 s later", true, f.pollDue(7_000));
        long late = 1_000 + 130_000;
        f.polling(late);
        eq("after 2 minutes 3 s is too soon", false, f.pollDue(late + 3_000));
        eq("after 2 minutes 6 s is due", true, f.pollDue(late + 6_000));
        eq("nothing is polled before a code exists", false, new DeviceLoginRules.Flow().pollDue(99_999));
    }

    private static void happyPath() {
        DeviceLoginRules.Flow f = new DeviceLoginRules.Flow();
        eq("starts idle", DeviceLoginRules.IDLE, f.state);
        int g = f.request();
        eq("a request is under way", DeviceLoginRules.REQUESTING, f.state);
        f.requested(g, CODE, 5_000);
        eq("the code is pending", DeviceLoginRules.PENDING, f.state);
        eq("the code is kept", CODE, f.code);
        eq("10 minutes left", DeviceLoginRules.TTL_MS, f.remaining(5_000));
        eq("9 minutes left a minute later", DeviceLoginRules.TTL_MS - 60_000, f.remaining(65_000));
        int p = f.polling(8_000);
        f.polled(p, null, AccountRules.STEP_LINK);
        eq("waiting stays pending", DeviceLoginRules.PENDING, f.state);
        f.polled(p, "space-1", AccountRules.STEP_LINK);
        eq("approved with nothing on the TV goes straight to linking", DeviceLoginRules.LINKING, f.state);
        eq("the TV's data is merged (nothing to lose)", AccountRules.LINK_MERGE, f.plan);
        eq("the space is kept", "space-1", f.space);
        f.fetching();
        eq("the profiles are being fetched", true, f.fetching);
        f.linked();
        eq("ready", DeviceLoginRules.READY, f.state);
        eq("a finished flow stops polling", false, f.active());
        eq("and nothing is due", false, f.pollDue(99_999));
    }

    private static void withLocalData() {
        for (boolean add : new boolean[] {true, false}) {
            DeviceLoginRules.Flow f = pending(0);
            f.polled(f.gen, "space-2", AccountRules.mergeStep("old-guest", "space-2", true));
            eq("data on the TV asks first", DeviceLoginRules.ASK, f.state);
            eq("the question does not poll", false, f.pollDue(99_999));
            f.chose(add);
            eq("then links", DeviceLoginRules.LINKING, f.state);
            eq(add ? "add keeps the TV's data for the merge" : "don't add clears the TV first", add ? AccountRules.LINK_MERGE : AccountRules.LINK_FRESH, f.plan);
            f.linked();
            eq("ready after the question", DeviceLoginRules.READY, f.state);
        }
        DeviceLoginRules.Flow f = pending(0);
        f.chose(true);
        eq("an answer before the question does nothing", DeviceLoginRules.PENDING, f.state);
    }

    private static void sameSpace() {
        DeviceLoginRules.Flow f = pending(0);
        f.polled(f.gen, "space-3", AccountRules.mergeStep("space-3", "space-3", true));
        eq("already in that space: no question, keep going", DeviceLoginRules.LINKING, f.state);
        eq("and the state is kept", AccountRules.LINK_KEEP_STATE, f.plan);
    }

    private static void expiry() {
        DeviceLoginRules.Flow f = pending(0);
        eq("not expired just before 10 minutes", false, f.tick(DeviceLoginRules.TTL_MS - 1));
        eq("expired at 10 minutes", true, f.tick(DeviceLoginRules.TTL_MS));
        eq("a new code is being requested", DeviceLoginRules.REQUESTING, f.state);
        eq("the old code is gone", "", f.code);
        f.requested(f.gen, CODE2, DeviceLoginRules.TTL_MS + 100);
        eq("the new code is pending", CODE2, f.code);
        eq("with its own 10 minutes", DeviceLoginRules.TTL_MS, f.remaining(DeviceLoginRules.TTL_MS + 100));
        eq("and its own poll schedule", false, f.pollDue(DeviceLoginRules.TTL_MS + 200));
        eq("it is not expired again at once", false, f.tick(DeviceLoginRules.TTL_MS + 5_000));
    }

    private static void staleAnswers() {
        DeviceLoginRules.Flow f = pending(0);
        int oldPoll = f.polling(3_000);
        f.tick(DeviceLoginRules.TTL_MS);
        f.requested(f.gen, CODE2, DeviceLoginRules.TTL_MS);
        f.polled(oldPoll, "space-x", AccountRules.STEP_LINK);
        eq("an answer for the old code does not sign in", DeviceLoginRules.PENDING, f.state);
        f.pollFailed(oldPoll, AccountRules.E_SESSION);
        eq("nor does its failure", DeviceLoginRules.PENDING, f.state);
        int g = f.request();
        f.requested(g - 1, CODE, 0);
        eq("a late code from before a retry is ignored", DeviceLoginRules.REQUESTING, f.state);
    }

    private static void failures() {
        DeviceLoginRules.Flow f = new DeviceLoginRules.Flow();
        int g = f.request();
        f.requestFailed(g, AccountRules.E_NETWORK);
        eq("no network is an error to retry", DeviceLoginRules.ERROR, f.state);
        eq("with its code", AccountRules.E_NETWORK, f.error);
        eq("an error does not poll", false, f.pollDue(99_999));
        g = f.request();
        eq("retry asks again", DeviceLoginRules.REQUESTING, f.state);
        eq("and clears the error", "", f.error);
        f.requestFailed(g, AccountRules.error(400, "too_many_requests"));
        eq("the rate limit is its own message", AccountRules.E_RATE, f.error);
        g = f.request();
        f.requested(g, "bad", 0);
        eq("a code that is not 8 letters of the alphabet is an error", DeviceLoginRules.ERROR, f.state);
        g = f.request();
        f.requested(g, "K7M2QX0P", 0);
        eq("a code with a 0 is refused", DeviceLoginRules.ERROR, f.state);

        f = pending(0);
        f.pollFailed(f.gen, AccountRules.E_NETWORK);
        eq("a poll with no network keeps waiting", DeviceLoginRules.PENDING, f.state);
        eq("and says so", true, f.offline);
        f.polled(f.gen, null, AccountRules.STEP_LINK);
        eq("the next answer clears it", false, f.offline);
        f.pollFailed(f.gen, AccountRules.E_SESSION);
        eq("a lost session needs a new code", DeviceLoginRules.ERROR, f.state);

        f = pending(0);
        f.polled(f.gen, "s", AccountRules.STEP_LINK);
        f.linkFailed(AccountRules.E_ERROR);
        eq("a failed link is an error to retry", DeviceLoginRules.ERROR, f.state);
    }

    private static void cancel() {
        DeviceLoginRules.Flow f = pending(0);
        int p = f.polling(3_000);
        f.close();
        eq("closed", DeviceLoginRules.CLOSED, f.state);
        eq("a closed flow is not active", false, f.active());
        eq("a closed flow polls nothing", false, f.pollDue(99_999));
        eq("a closed flow never expires into a request", false, f.tick(DeviceLoginRules.TTL_MS * 2));
        f.polled(p, "space-1", AccountRules.STEP_LINK);
        eq("an approval arriving after closing is ignored", DeviceLoginRules.CLOSED, f.state);
        f.requested(f.gen, CODE, 0);
        f.request();
        eq("nothing restarts a closed flow", DeviceLoginRules.CLOSED, f.state);
        f.chose(true);
        f.linked();
        eq("nor a late link", DeviceLoginRules.CLOSED, f.state);
    }
}
