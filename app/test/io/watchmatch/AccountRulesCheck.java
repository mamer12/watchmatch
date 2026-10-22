package io.watchmatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks Watchme+ accounts on a plain JVM, with no Android and no network: what the start shows (welcome, straight in, the
 * password again), which call makes or joins an account (upgrade in place, sign-up, sign-in), the wire format of every
 * account call against a fake transport, what happens to the data already on the TV when it signs in to another account
 * (the merge through the real sync engine, and the clean start), how failures become words, what a sign-out wipes (and that
 * it wipes even offline), the two-step delete and its delay, and that the password is never stored, sent anywhere but its
 * own request, or logged. From the repository's root (android.jar only so that Backoff compiles; nothing of Android runs):
 *
 *   AJ=$(ls "$ANDROID_HOME"/platforms/android-*\/android.jar | sort -V | tail -n 1)
 *   mkdir -p out/test && javac -cp "$AJ" -d out/test app/src/io/watchmatch/{MiniJson,Backoff,PinRules,ProfileRules,SyncRules,SyncMap,SyncState,SyncClient,SyncEngine,ResetRules,AccountRules}.java \
 *       app/test/io/watchmatch/{ProfileTestKit,AccountRulesCheck}.java && java -cp out/test io.watchmatch.AccountRulesCheck
 */
public final class AccountRulesCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) throws Exception {
        modes();
        startPlans();
        calls();
        forms();
        errors();
        wire();
        merge();
        signOut();
        deleteGate();
        secrets();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- who, and what the start shows --------------------------------------------------------

    private static void modes() {
        eq("no session is nobody", AccountRules.MODE_NONE, AccountRules.mode(false, ""));
        eq("a session with no email is a guest", AccountRules.MODE_GUEST, AccountRules.mode(true, ""));
        eq("a session with no email (null) is a guest", AccountRules.MODE_GUEST, AccountRules.mode(true, null));
        eq("an email makes it an account", AccountRules.MODE_ACCOUNT, AccountRules.mode(true, "a@b.co"));
    }

    private static void startPlans() {
        int go = AccountRules.PLAN_GO, welcome = AccountRules.PLAN_WELCOME, again = AccountRules.PLAN_SIGN_IN_AGAIN;
        eq("a first start shows the welcome", welcome, AccountRules.startPlan(true, false, false, "", false));
        eq("a build without a backend shows nothing", go, AccountRules.startPlan(false, false, false, "", false));
        eq("continue as guest is remembered", go, AccountRules.startPlan(true, false, false, "", true));
        eq("a guest paired before accounts goes straight in", go, AccountRules.startPlan(true, true, true, "", false));
        eq("a guest with a session goes straight in", go, AccountRules.startPlan(true, true, false, "", false));
        eq("an account with its session goes straight in", go, AccountRules.startPlan(true, true, true, "a@b.co", false));
        eq("an account whose tokens are gone asks for the password again", again, AccountRules.startPlan(true, false, false, "a@b.co", false));
        eq("after a sign-out (no session, nothing chosen) the welcome is back", welcome, AccountRules.startPlan(true, false, false, "", false));
        eq("accounts off wins over a lost account", go, AccountRules.startPlan(false, false, false, "a@b.co", false));
    }

    private static void calls() {
        eq("guest session: create upgrades in place", AccountRules.CREATE_UPGRADE, AccountRules.createKind(true, ""));
        eq("no session: create signs up", AccountRules.CREATE_SIGNUP, AccountRules.createKind(false, ""));
        eq("an account cannot create another", AccountRules.CREATE_NONE, AccountRules.createKind(true, "a@b.co"));

        eq("same space: nothing to decide", AccountRules.STEP_SAME_SPACE, AccountRules.mergeStep("s1", "s1", true));
        eq("another space with data on the TV: ask", AccountRules.STEP_ASK, AccountRules.mergeStep("s1", "s2", true));
        eq("never linked, with local data: ask", AccountRules.STEP_ASK, AccountRules.mergeStep("", "s2", true));
        eq("another space, nothing local: just link", AccountRules.STEP_LINK, AccountRules.mergeStep("s1", "s2", false));
        eq("same space keeps the state", AccountRules.LINK_KEEP_STATE, AccountRules.linkPlan(AccountRules.STEP_SAME_SPACE, false));
        eq("add: merge", AccountRules.LINK_MERGE, AccountRules.linkPlan(AccountRules.STEP_ASK, true));
        eq("don't add: clean start", AccountRules.LINK_FRESH, AccountRules.linkPlan(AccountRules.STEP_ASK, false));
        eq("nothing to ask: link, no wipe", AccountRules.LINK_MERGE, AccountRules.linkPlan(AccountRules.STEP_LINK, false));
        eq("the old guest space is left when moving", true, AccountRules.leavesOldSpace("s1", "s2"));
        eq("not when it is the same", false, AccountRules.leavesOldSpace("s1", "s1"));
        eq("not when there was none", false, AccountRules.leavesOldSpace("", "s2"));
    }

    private static void forms() {
        eq("email trimmed and lower-cased", "ana@mail.com", AccountRules.cleanEmail("  Ana@Mail.COM "));
        for (String ok : new String[] {"a@b.co", "ana.k+tv@mail.example.com", "x_y-z@d-e.org"}) eq("valid " + ok, true, AccountRules.validEmail(ok));
        for (String bad : new String[] {"", "ana", "ana@", "@mail.com", "a@b", "a@b.c", "a b@c.com", "a@@b.com", "a@b..", "a@.com"}) {
            eq("invalid '" + bad + "'", false, AccountRules.validEmail(bad));
        }
        eq("7 characters is short", "short", AccountRules.passwordIssue("1234567"));
        eq("8 characters pass", null, AccountRules.passwordIssue("12345678"));
        eq("73 characters are too long", "long", AccountRules.passwordIssue(new String(new char[73]).replace('\0', 'x')));
        eq("empty is short", "short", AccountRules.passwordIssue(""));
        eq("create: weak password named", "password", AccountRules.formIssue("a@b.co", "short", true));
        eq("create: bad email named first", "email", AccountRules.formIssue("nope", "short", true));
        eq("sign-in: any non-empty password goes (it may predate the rule)", null, AccountRules.formIssue("a@b.co", "abc", false));
        eq("sign-in: empty password named", "password", AccountRules.formIssue("a@b.co", "", false));
    }

    private static void errors() {
        eq("no network", "network", AccountRules.error(0, "timeout"));
        eq("wrong password", "invalid", AccountRules.error(400, "invalid_credentials: Invalid login credentials"));
        eq("wrong password, older wording", "invalid", AccountRules.error(400, "Invalid login credentials"));
        eq("email taken (sign-up)", "taken", AccountRules.error(422, "user_already_exists: User already registered"));
        eq("email taken (upgrade)", "taken", AccountRules.error(422, "email_exists: A user with this email address has already been registered"));
        eq("weak password", "weak", AccountRules.error(422, "weak_password: Password should be at least 8 characters."));
        eq("rate limit", "rate", AccountRules.error(429, "over_request_rate_limit: Request rate limit reached"));
        eq("rate limit by wording", "rate", AccountRules.error(400, "too_many_attempts"));
        eq("confirmation needed", "confirm", AccountRules.error(200, "confirm_required"));
        eq("email not confirmed", "confirm", AccountRules.error(400, "email_not_confirmed: Email not confirmed"));
        eq("delete_my_account not applied", "unavailable", AccountRules.error(404,
                "Could not find the function public.delete_my_account without parameters in the schema cache"));
        eq("session lost", "session", AccountRules.error(401, "auth_lost"));
        eq("anything else", "error", AccountRules.error(500, "boom"));
        eq("a null message is an error, not a crash", "error", AccountRules.error(500, null));
    }

    // ---- the wire ---------------------------------------------------------------------------

    private static final class Call {
        final String method, url, body;
        final Map<String, String> headers;

        Call(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = new LinkedHashMap<>(headers);
            this.body = body;
        }
    }

    private static final class Wire implements SyncClient.Transport {
        final List<Call> calls = new ArrayList<>();
        final List<SyncClient.Response> replies = new ArrayList<>();

        @Override public SyncClient.Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
            calls.add(new Call(method, url, headers, body));
            if (replies.isEmpty()) throw new IOException("no network");
            return replies.remove(0);
        }

        Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    private static final class Mem implements SyncClient.Store {
        SyncClient.Session s;

        @Override public SyncClient.Session load() {
            return s;
        }

        @Override public void save(SyncClient.Session x) {
            s = x;
        }
    }

    private static final String PASSWORD = "correct horse 9!";

    private static SyncClient.Response reply(int status, String body) {
        return new SyncClient.Response(status, body);
    }

    private static String session(String user, String email, String access) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"r-" + access + "\",\"expires_in\":3600,\"user\":{\"id\":\"" + user
                + "\",\"email\":\"" + email + "\"}}";
    }

    private static void wire() {
        long[] now = {1_000_000};
        Wire w = new Wire();
        Mem store = new Mem();
        SyncClient c = new SyncClient("https://x.example/", "anon-key", store, () -> now[0], w);

        // Sign in: the password goes in the body of this one request.
        w.replies.add(reply(200, session("u1", "ana@mail.com", "a1")));
        SyncClient.Session s = null;
        try {
            s = c.signInPassword("ana@mail.com", PASSWORD);
        } catch (SyncClient.HttpException e) {
            eq("sign-in works", "no error", e.getMessage());
        }
        Call call = w.last();
        eq("sign-in method", "POST", call.method);
        eq("sign-in url", "https://x.example/auth/v1/token?grant_type=password", call.url);
        eq("sign-in body", "ana@mail.com", MiniJson.map(MiniJson.parse(call.body)).get("email"));
        eq("sign-in body carries the password", PASSWORD, MiniJson.map(MiniJson.parse(call.body)).get("password"));
        eq("sign-in needs no bearer", false, call.headers.containsKey("Authorization"));
        eq("the session keeps the email", "ana@mail.com", s == null ? null : s.email);
        eq("and it was saved", "u1", store.s == null ? null : store.s.userId);
        eq("the client says whose it is", "ana@mail.com", c.email());

        // A refresh keeps the email (the refresh answer has the user too).
        now[0] += 3_600_000;
        w.replies.add(reply(200, session("u1", "ana@mail.com", "a2")));
        w.replies.add(reply(200, "\"space-1\""));
        try {
            c.rpc("create_space", MiniJson.obj("device_name", "TV"), false);
        } catch (SyncClient.HttpException e) {
            eq("rpc after a refresh", "no error", e.getMessage());
        }
        eq("the refresh keeps the email", "ana@mail.com", c.email());

        // Wrong password.
        Wire w2 = new Wire();
        SyncClient c2 = new SyncClient("https://x.example", "anon-key", new Mem(), () -> now[0], w2);
        w2.replies.add(reply(400, "{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}"));
        String err = "";
        try {
            c2.signInPassword("ana@mail.com", "nope");
        } catch (SyncClient.HttpException e) {
            err = AccountRules.error(e.status, e.getMessage());
            eq("the failure message never has the password", false, e.getMessage().contains("nope"));
        }
        eq("wrong password becomes 'invalid'", "invalid", err);
        eq("and nothing was saved", false, c2.signedIn());

        // Offline.
        try {
            c2.signInPassword("ana@mail.com", "x");
        } catch (SyncClient.HttpException e) {
            eq("offline is status 0", 0, e.status);
            eq("offline becomes 'network'", "network", AccountRules.error(e.status, e.getMessage()));
        }

        // Sign-up with no session; the project wants a confirmation.
        Mem m3 = new Mem();
        Wire w3 = new Wire();
        SyncClient c3 = new SyncClient("https://x.example", "anon-key", m3, () -> now[0], w3);
        w3.replies.add(reply(200, "{\"id\":\"u9\",\"email\":\"new@mail.com\",\"confirmation_sent_at\":\"2026-10-23T10:00:00Z\"}"));
        try {
            c3.signUpPassword("new@mail.com", PASSWORD);
            eq("sign-up without a session is 'confirm'", "thrown", "returned");
        } catch (SyncClient.HttpException e) {
            eq("sign-up that needs confirming", "confirm", AccountRules.error(e.status, e.getMessage()));
        }
        eq("nothing saved then", null, m3.s);
        w3.replies.add(reply(200, session("u9", "new@mail.com", "n1")));
        try {
            c3.signUpPassword("new@mail.com", PASSWORD);
        } catch (SyncClient.HttpException e) {
            eq("sign-up works", "no error", e.getMessage());
        }
        eq("sign-up url", "https://x.example/auth/v1/signup", w3.last().url);
        eq("sign-up saved the account", "new@mail.com", m3.s == null ? null : m3.s.email);

        // Upgrade in place: the guest's user id stays, PUT /auth/v1/user with its bearer.
        Mem m4 = new Mem();
        m4.s = new SyncClient.Session("g-access", "g-refresh", "guest-1", now[0] + 3_600_000);
        Wire w4 = new Wire();
        SyncClient c4 = new SyncClient("https://x.example", "anon-key", m4, () -> now[0], w4);
        eq("a guest has no email", "", c4.email());
        eq("a guest's rows are told by its user id", "guest-1", c4.userId());
        w4.replies.add(reply(200, "{\"id\":\"guest-1\",\"email\":\"ana@mail.com\",\"is_anonymous\":false}"));
        try {
            c4.upgrade("ana@mail.com", PASSWORD);
        } catch (SyncClient.HttpException e) {
            eq("upgrade works", "no error", e.getMessage());
        }
        Call up = w4.last();
        eq("upgrade method", "PUT", up.method);
        eq("upgrade url", "https://x.example/auth/v1/user", up.url);
        eq("upgrade is the guest's own session", "Bearer g-access", up.headers.get("Authorization"));
        eq("the user id is unchanged", "guest-1", m4.s.userId);
        eq("the session is now an account", "ana@mail.com", m4.s.email);
        eq("an account's rows can't be told by user id (every device shares it)", null, c4.userId());
        eq("but the session still knows the user", "guest-1", m4.s.userId);
        eq("and the tokens are the same", "g-access", m4.s.access);

        // Upgrade that needs the email confirmed first (the answer has no email yet).
        Mem m5 = new Mem();
        m5.s = new SyncClient.Session("g-access", "g-refresh", "guest-2", now[0] + 3_600_000);
        Wire w5 = new Wire();
        SyncClient c5 = new SyncClient("https://x.example", "anon-key", m5, () -> now[0], w5);
        w5.replies.add(reply(200, "{\"id\":\"guest-2\",\"email\":\"\",\"new_email\":\"ana@mail.com\"}"));
        try {
            c5.upgrade("ana@mail.com", PASSWORD);
            eq("upgrade that needs confirming", "thrown", "returned");
        } catch (SyncClient.HttpException e) {
            eq("upgrade that needs confirming", "confirm", AccountRules.error(e.status, e.getMessage()));
        }
        eq("it stays a guest then", "", m5.s.email);

        // Email taken on upgrade.
        w5.replies.add(reply(422, "{\"code\":422,\"error_code\":\"email_exists\",\"msg\":\"A user with this email address has already been registered\"}"));
        try {
            c5.upgrade("ana@mail.com", PASSWORD);
        } catch (SyncClient.HttpException e) {
            eq("email taken on upgrade", "taken", AccountRules.error(e.status, e.getMessage()));
        }

        // Change password, logout (this device only).
        w4.replies.add(reply(200, "{\"id\":\"guest-1\"}"));
        try {
            c4.changePassword("another long one 7");
        } catch (SyncClient.HttpException e) {
            eq("change password works", "no error", e.getMessage());
        }
        eq("change password is PUT /auth/v1/user", "PUT https://x.example/auth/v1/user", w4.last().method + " " + w4.last().url);
        eq("with only the password", "[password]", new ArrayList<>(MiniJson.map(MiniJson.parse(w4.last().body)).keySet()).toString());
        w4.replies.add(reply(204, ""));
        try {
            c4.logout();
        } catch (SyncClient.HttpException e) {
            eq("logout works", "no error", e.getMessage());
        }
        eq("logout ends this device only", "POST https://x.example/auth/v1/logout?scope=local", w4.last().method + " " + w4.last().url);

        // The password appears in no URL and no header, anywhere.
        boolean leaked = false;
        for (Wire x : new Wire[] {w, w2, w3, w4, w5}) {
            for (Call k : x.calls) {
                leaked |= k.url.contains(PASSWORD) || k.url.contains("password=") || k.headers.toString().contains(PASSWORD);
            }
        }
        eq("the password is never in a URL or a header", false, leaked);
        eq("and never in a session", false, String.valueOf(m4.s.access + m4.s.refresh + m4.s.userId + m4.s.email).contains(PASSWORD));
    }

    // ---- the TV's own data meets an account's -----------------------------------------------------

    private static long titleIdsAt(ProfileTestKit.Device d, String id) {
        SyncMap.P p = d.lib.of("main").progress.get(id);
        return p == null ? -1 : p.position;
    }

    private static void merge() throws Exception {
        long t = 1_900_000_000_000L;
        ProfileTestKit.Server server = new ProfileTestKit.Server();

        // The account has data from the phone.
        ProfileTestKit.Device phone = new ProfileTestKit.Device(server, "uidAccount", "Phone", true);
        phone.lib.of("main").list.add(ProfileTestKit.title("1", "Phone pick"));
        phone.lib.of("main").progress.put("50", ProfileTestKit.progress("50", "Show", 9000, t + 5_000));
        phone.lib.addProfile("zoe00001", "Zoe", true);
        phone.lib.of("zoe00001").list.add(ProfileTestKit.title("3", "Cartoon"));
        phone.sync(t + 6_000);

        // A guest TV with its own data signs in to that account and chooses "add": the data joins.
        ProfileTestKit.Device tv = new ProfileTestKit.Device(server, "uidAccount", "TV", true);
        tv.lib.of("main").list.add(ProfileTestKit.title("2", "TV pick"));
        tv.lib.of("main").progress.put("50", ProfileTestKit.progress("50", "Show", 1000, t + 1_000));       // older than the phone's
        tv.lib.of("main").progress.put("51", ProfileTestKit.progress("51", "Only here", 4000, t + 2_000));
        tv.sync(t + 10_000);
        eq("add: My List is the union (account's)", true, tv.lib.of("main").listIds().contains("1"));
        eq("add: My List is the union (TV's)", true, tv.lib.of("main").listIds().contains("2"));
        eq("add: the newest progress wins (the phone's, further along, newer)", 9000L, titleIdsAt(tv, "50"));
        eq("add: progress only the TV had is kept", 4000L, titleIdsAt(tv, "51"));
        eq("add: the account's profiles arrive", true, ProfileRules.byId(tv.lib.profiles, "zoe00001") != null);
        phone.sync(t + 11_000);
        eq("add: and the account now has the TV's list", true, phone.lib.of("main").listIds().contains("2"));
        eq("add: and the TV's progress", 4000L, titleIdsAt(phone, "51"));
        eq("add: nothing of the account was removed", true, phone.lib.of("main").listIds().contains("1")
                && ProfileRules.byId(phone.lib.profiles, "zoe00001") != null);

        // The newer local progress wins when it is newer.
        ProfileTestKit.Device tv2 = new ProfileTestKit.Device(server, "uidAccount", "TV2", true);
        tv2.lib.of("main").progress.put("50", ProfileTestKit.progress("50", "Show", 12_000, t + 50_000));
        tv2.sync(t + 60_000);
        eq("add: a newer position on the TV wins", 12_000L, titleIdsAt(tv2, "50"));

        // "Don't add": the TV starts clean, then only the account's data comes in.
        ProfileTestKit.Device clean = new ProfileTestKit.Device(server, "uidAccount", "TV3", true);
        clean.sync(t + 70_000);
        eq("don't add: the account's list is there", true, clean.lib.of("main").listIds().contains("1"));
        eq("don't add: the account's profile is there", true, ProfileRules.byId(clean.lib.profiles, "zoe00001") != null);

        // A deleted profile stays deleted when the TV merges (tombstones).
        phone.lib.delete("zoe00001", false);
        phone.sync(t + 80_000);
        ProfileTestKit.Device tv4 = new ProfileTestKit.Device(server, "uidAccount", "TV4", true);
        tv4.lib.addProfile("zoe00001", "Zoe", true);
        tv4.sync(t + 90_000);
        ProfileRules.Profile zoe = ProfileRules.byId(tv4.lib.profiles, "zoe00001");
        eq("a profile deleted in the account is deleted on the merging TV", true, zoe != null && zoe.deleted);
    }

    // ---- sign out -----------------------------------------------------------------------------

    private static void signOut() throws IOException {
        List<String> ran = new ArrayList<>();
        String[] names = AccountRules.SIGN_OUT_WIPES;
        ResetRules.Step[] steps = new ResetRules.Step[names.length];
        for (int i = 0; i < steps.length; i++) {
            final String n = names[i];
            steps[i] = () -> ran.add(n);
        }
        ResetRules.Result r = AccountRules.signOut(() -> ran.add("logout"), names, steps);
        eq("sign-out: logout first", "logout", ran.get(0));
        eq("sign-out: then every wipe, in order", Arrays.asList(names), ran.subList(1, ran.size()));
        eq("sign-out: all ok", names.length, r.ok.size());

        // Offline: the logout fails, the wipes still all run. One wipe failing does not stop the rest either.
        ran.clear();
        steps[2] = () -> {
            throw new IllegalStateException("disk");
        };
        r = AccountRules.signOut(() -> {
            throw new IOException("no network");
        }, names, steps);
        eq("offline sign-out still wipes", names.length - 1, ran.size());
        eq("a failing wipe is listed", 1, r.failed.size());

        // What it wipes covers what is personal, in the names WatchmeReset really uses.
        String reset = new String(Files.readAllBytes(Paths.get("app/src/io/watchmatch/WatchmeReset.java")), StandardCharsets.UTF_8);
        for (String n : Arrays.asList("sync link", "profiles", "lists, ratings, progress and settings")) {
            eq("the wipe set has '" + n + "'", true, Arrays.asList(names).contains(n) && reset.contains("\"" + n + "\""));
        }
        eq("the wipe set clears the account marker", true, Arrays.asList(names).contains("account marker"));
        String account = new String(Files.readAllBytes(Paths.get("app/src/io/watchmatch/Account.java")), StandardCharsets.UTF_8);
        eq("the real sign-out uses that sequence", true, account.contains("AccountRules.signOut(logout, names, steps)")
                && account.contains("WatchmeReset.all(c)") && account.contains("\"account marker\""));
        eq("the real sign-out ends only this device's session", true, account.contains("cl.logout()"));
    }

    // ---- delete -------------------------------------------------------------------------------

    private static void deleteGate() {
        AccountRules.DeleteGate g = new AccountRules.DeleteGate();
        long t = 10_000;
        eq("not armed at first", false, g.armed());
        eq("first press only arms", false, g.press(t));
        eq("armed", true, g.armed());
        eq("5 seconds to wait", 5, g.secondsLeft(t));
        eq("2 seconds in: 3 left", 3, g.secondsLeft(t + 2_000));
        eq("a press too early does nothing", false, g.press(t + 2_000));
        eq("and does not restart the wait", 0, g.secondsLeft(t + 5_000));
        eq("a press just before 5 seconds does nothing", false, g.press(t + 4_999));
        eq("a press at 5 seconds deletes", true, g.press(t + 5_000));
        eq("and disarms", false, g.armed());
        eq("pressing again starts over", false, g.press(t + 5_001));

        AccountRules.DeleteGate h = new AccountRules.DeleteGate();
        h.press(t);
        h.disarm();
        eq("leaving the screen disarms: the next press only arms", false, h.press(t + 9_000));
        eq("still needs its 5 seconds", false, h.press(t + 10_000));
        eq("then deletes", true, h.press(t + 14_001));

        AccountRules.DeleteGate stale = new AccountRules.DeleteGate();
        stale.press(t);
        eq("after 30 seconds the old press is forgotten (this one arms again)", false, stale.press(t + 30_000));
        eq("and needs its own wait", false, stale.press(t + 31_000));
        eq("not armed means no countdown", -1, new AccountRules.DeleteGate().secondsLeft(t));
        eq("the delay is 5 seconds", 5_000L, AccountRules.DELETE_DELAY_MS);
    }

    // ---- secrets ------------------------------------------------------------------------------

    private static void secrets() throws IOException {
        String body = "{\"email\":\"a@b.co\",\"password\":\"" + PASSWORD + "\"}";
        eq("redact hides a JSON password", false, AccountRules.redact(body).contains(PASSWORD));
        eq("redact keeps the rest", true, AccountRules.redact(body).contains("a@b.co"));
        eq("redact hides tokens", false, AccountRules.redact("Authorization: Bearer abc.def.ghi").contains("abc.def"));
        eq("redact hides a form password", false, AccountRules.redact("x?password=" + PASSWORD + "&y=1").contains(PASSWORD));
        eq("redact hides token fields", false, AccountRules.redact("{\"refresh_token\":\"zzz\"}").contains("zzz"));
        eq("redact of null", "", AccountRules.redact(null));

        // The code: nothing logs a password, nothing writes one down.
        for (String file : new String[] {"Account", "AccountScreens", "AccountText", "AccountRules", "SyncClient", "Sync"}) {
            String src = new String(Files.readAllBytes(Paths.get("app/src/io/watchmatch/" + file + ".java")), StandardCharsets.UTF_8);
            boolean logged = false, stored = false;
            for (String line : src.split("\n")) {
                String l = line.toLowerCase();
                if ((l.contains("applog.") || l.contains("log.w(") || l.contains("log.d(") || l.contains("system.out") || l.contains("println"))
                        && (l.contains("password") || l.contains("pwbuf") || l.contains("pw)"))) logged = true;
                if ((l.contains("putstring") || l.contains("durable.write") || l.contains("durable.save") || l.contains("fileoutputstream"))
                        && (l.contains("password") || l.contains("pwbuf"))) stored = true;
            }
            eq(file + " never logs a password", false, logged);
            eq(file + " never stores a password", false, stored);
        }
        String screens = new String(Files.readAllBytes(Paths.get("app/src/io/watchmatch/AccountScreens.java")), StandardCharsets.UTF_8);
        eq("the form clears the password when it closes or goes back", true, screens.split("wipe\\(pwBuf\\)", -1).length - 1 >= 4);
        String client = new String(Files.readAllBytes(Paths.get("app/src/io/watchmatch/SyncClient.java")), StandardCharsets.UTF_8);
        eq("a Session has no password field", false, client.substring(client.indexOf("static final class Session"), client.indexOf("interface Store"))
                .toLowerCase().contains("password"));
    }
}
