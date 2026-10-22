package io.watchmatch;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Runs the real account code (SyncClient's email and password calls, the engine) against the real backend on a plain JVM.
 * It SKIPs, with the reason, what the project cannot do for it: email sign-up that needs confirming (nothing is created,
 * no email is sent: the project's public /auth/v1/settings says so first) and delete_my_account not applied (0002_accounts.sql,
 * which the owner applies). Always, with no account made: wrong password, weak password and a guest that cannot upgrade
 * with a weak one. With confirmation off and delete_my_account applied (or `--allow-leftovers`, which leaves one test user
 * behind when it is not): a guest becomes an account in place (same user, same space, same data), a second device signs in
 * and gets the library, both then sync both ways although they share one user id, a taken email, a changed password, a
 * sign-out that ends only that device, and the account's deletion. Test users are always cleaned up: delete_my_account when
 * it exists, else leave_space (an auth user can't be removed with the public key). Prints PASS / SKIP / FAIL lines and
 * exits 1 on any FAIL. The URL and the anon key are public values; no token or password is ever printed. From the
 * repository's root (android.jar only so that Backoff compiles; nothing of Android runs):
 *
 *   AJ=$(ls "$ANDROID_HOME"/platforms/android-*\/android.jar | sort -V | tail -n 1)
 *   mkdir -p out/test && javac -cp "$AJ" -d out/test app/src/io/watchmatch/{MiniJson,Backoff,PinRules,ProfileRules,SyncRules,SyncMap,SyncState,SyncClient,SyncEngine,AccountRules}.java \
 *       app/test/io/watchmatch/{ProfileTestKit,AccountLiveCheck}.java
 *   java -cp out/test io.watchmatch.AccountLiveCheck "$(sed -n 's/^supabase_url=//p' config.properties)" \
 *       "$(sed -n 's/^supabase_anon_key=//p' config.properties)" [--allow-leftovers]
 */
public final class AccountLiveCheck {
    private static int failures, skipped;

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (!ok && detail != null && !detail.isEmpty() ? "  (" + detail + ")" : ""));
    }

    private static void skip(String what, String why) {
        skipped++;
        System.out.println("SKIP  " + what + ": " + why);
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

    private static final class Dev {
        final Mem store = new Mem();
        final SyncClient client;

        Dev(String url, String key) {
            client = new SyncClient(url, key, store, System::currentTimeMillis);
        }
    }

    private static final class Node {
        final ProfileTestKit.Lib lib = new ProfileTestKit.Lib();
        final SyncState state = new SyncState();
        final SyncEngine engine;

        Node(final Dev d, String space, String name) {
            state.space = space;
            state.device = name;
            engine = new SyncEngine(state, new SyncEngine.Api() {
                @Override public String userId() {
                    return d.client.userId();
                }

                @Override public void upsert(String table, List<Map<String, Object>> rows) throws SyncClient.HttpException {
                    d.client.upsert(table, rows);
                }

                @Override public List<Map<String, Object>> pull(String table, String sp, String cursor, int limit) throws SyncClient.HttpException {
                    return d.client.pull(table, sp, cursor, limit);
                }
            }, lib, s -> { });
        }

        void sync() throws SyncClient.HttpException {
            engine.reconcile(System.currentTimeMillis(), state.device);
            engine.pullAll();
            engine.pushAll();
        }
    }

    /** What the account call failed with, as the app words it ("" when it did not fail). */
    private static String codeOf(Throwable e) {
        if (!(e instanceof SyncClient.HttpException)) return "exception " + e.getClass().getSimpleName();
        SyncClient.HttpException h = (SyncClient.HttpException) e;
        return AccountRules.error(h.status, h.getMessage());
    }

    private static String get(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("apikey", key);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(25_000);
        try (java.io.InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0].trim() : System.getenv("SUPABASE_URL");
        String key = args.length > 1 ? args[1].trim() : System.getenv("SUPABASE_ANON_KEY");
        boolean leftovers = false;
        for (String a : args) leftovers |= "--allow-leftovers".equals(a);
        if (url == null || key == null || url.isEmpty() || key.isEmpty() || url.startsWith("--") || key.startsWith("--")) {
            System.out.println("FAIL  pass the project URL and the public anon key (arguments or SUPABASE_URL, SUPABASE_ANON_KEY)");
            System.exit(1);
        }
        try {
            run(url.replaceAll("/+$", ""), key, leftovers);
        } catch (Exception e) {
            check("the live account check ran", false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println(failures == 0 ? "DONE  no failures" + (skipped > 0 ? ", " + skipped + " skipped" : "") : "DONE  " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(String url, String key, boolean leftovers) throws Exception {
        String rnd = Long.toHexString(System.nanoTime() & 0xFFFFFFFFFFL);
        String email = "wm-check-" + rnd + "@example.com", password = "Check-" + rnd + "-pw9", changed = "Changed-" + rnd + "-pw7";

        // What the project allows, from its public settings (nothing is created, no email is sent).
        Map<String, Object> settings = MiniJson.map(MiniJson.parse(get(url + "/auth/v1/settings", key)));
        boolean autoconfirm = MiniJson.bool(settings.get("mailer_autoconfirm")), signupOff = MiniJson.bool(settings.get("disable_signup"));
        check("the auth settings are readable", settings.containsKey("external"), "");

        // ---- always: failures that create nothing ------------------------------------------------
        Dev none = new Dev(url, key);
        String wrong = "";
        try {
            none.client.signInPassword("wm-nobody-" + rnd + "@example.com", "not-a-real-password");
        } catch (Exception e) {
            wrong = codeOf(e);
        }
        check("a wrong email or password is 'invalid'", "invalid".equals(wrong), wrong);
        check("and nothing was saved", !none.client.signedIn(), "");

        Dev guest = new Dev(url, key);
        guest.client.signUp();
        String guestId = guest.store.s.userId;
        String weak = "";
        try {
            guest.client.upgrade("wm-weak-" + rnd + "@example.com", "abc");
        } catch (Exception e) {
            weak = codeOf(e);
        }
        check("a guest cannot upgrade with a weak password ('weak', or 'confirm' when the project confirms)", "weak".equals(weak) || ("confirm".equals(weak) && !autoconfirm), weak);
        check("it is still the same guest", guest.client.email().isEmpty() && guestId.equals(guest.store.s.userId), "");

        // Is delete_my_account applied? (A throwaway guest: if it exists, the guest is gone.)
        boolean canDelete;
        try {
            guest.client.rpc("delete_my_account", null, false);
            canDelete = true;
        } catch (SyncClient.HttpException e) {
            canDelete = false;
            check("delete_my_account missing reads as 'unavailable'", "unavailable".equals(AccountRules.error(e.status, e.getMessage())),
                    e.status + " " + e.getMessage());
            try {
                guest.client.rpc("leave_space", null, false);
            } catch (SyncClient.HttpException ignored) {
                // never in a space
            }
        }
        if (canDelete) check("delete_my_account deletes the caller", true, "");

        if (signupOff) {
            skip("accounts journey", "email sign-up is switched off in the project");
            return;
        }
        if (!autoconfirm) {
            skip("accounts journey", "the project asks to confirm the email first (mailer_autoconfirm is off): signing up would send mail to a made-up address");
            return;
        }
        if (!canDelete && !leftovers) {
            skip("accounts journey", "delete_my_account is not applied (supabase/migrations/0002_accounts.sql): the test user could not be removed. Run again with --allow-leftovers to leave one test user, or apply the migration");
            return;
        }
        journey(url, key, email, password, changed, canDelete);
    }

    private static void journey(String url, String key, String email, String password, String changed, boolean canDelete) throws Exception {
        Dev a = new Dev(url, key), b = new Dev(url, key);
        boolean cleaned = false;
        try {
            // Device A is a guest with data in its own space.
            a.client.signUp();
            String guestId = a.store.s.userId;
            String space = (String) a.client.rpc("create_space", MiniJson.obj("device_name", "Live A"), false);
            Node na = new Node(a, space, "Live A");
            na.lib.of("main").list.add(ProfileTestKit.title("acc-1", "Account title"));
            na.lib.of("main").progress.put("acc-50", ProfileTestKit.progress("acc-50", "Account show", 7000, System.currentTimeMillis()));
            na.lib.addProfile("acczoe01", "Zoe", true);
            na.sync();

            // Create account on a guest: in place.
            a.client.upgrade(email, password);
            check("create account on a guest keeps the user", guestId.equals(a.store.s.userId), "");
            check("and the session is an account now", email.equals(a.client.email()), a.client.email());
            Object again = a.client.rpc("create_space", MiniJson.obj("device_name", "Live A"), false);
            check("and the space is the same (create_space is idempotent)", space.equals(again), "");
            check("an account's rows can't be told by user id", a.client.userId() == null, "");

            // Device B signs in with the same email and password.
            b.client.signInPassword(email, password);
            check("sign-in on a second device is the same user", guestId.equals(b.store.s.userId), "");
            check("and the session has the email", email.equals(b.client.email()), "");
            Object spaceB = b.client.rpc("create_space", MiniJson.obj("device_name", "Live B"), false);
            check("create_space after sign-in returns the existing space", space.equals(spaceB), "");
            Node nb = new Node(b, space, "Live B");
            nb.sync();
            check("the second device gets the My List", nb.lib.of("main").listIds().contains("acc-1"), "");
            check("and the progress", nb.lib.of("main").progress.containsKey("acc-50"), "");
            check("and the profiles", ProfileRules.byId(nb.lib.profiles, "acczoe01") != null, "");

            // Both ways, although both devices are the same user.
            nb.lib.of("main").list.add(ProfileTestKit.title("acc-2", "Added on B"));
            nb.lib.of("main").progress.put("acc-51", ProfileTestKit.progress("acc-51", "B's show", 3000, System.currentTimeMillis()));
            nb.sync();
            na.sync();
            check("B's My List reaches A (one user id on both)", na.lib.of("main").listIds().contains("acc-2"), "");
            check("B's progress reaches A", na.lib.of("main").progress.containsKey("acc-51"), "");
            na.lib.of("main").progress.put("acc-50", ProfileTestKit.progress("acc-50", "Account show", 9500, System.currentTimeMillis() + 1000));
            na.sync();
            nb.sync();
            check("A's newer progress reaches B", nb.lib.of("main").progress.get("acc-50") != null
                    && nb.lib.of("main").progress.get("acc-50").position == 9500, "");

            // Failures with an account that exists.
            String code = "";
            try {
                new Dev(url, key).client.signInPassword(email, "wrong-" + password);
            } catch (Exception e) {
                code = codeOf(e);
            }
            check("a wrong password for a real email is 'invalid'", "invalid".equals(code), code);
            code = "";
            try {
                new Dev(url, key).client.signUpPassword(email, password);
            } catch (Exception e) {
                code = codeOf(e);
            }
            check("signing up with a taken email is 'taken'", "taken".equals(code), code);
            Dev third = new Dev(url, key);
            third.client.signUp();
            code = "";
            try {
                third.client.upgrade(email, password);
            } catch (Exception e) {
                code = codeOf(e);
            }
            check("upgrading a guest to a taken email is 'taken'", "taken".equals(code), code);
            try {
                third.client.rpc("leave_space", null, false);
            } catch (SyncClient.HttpException ignored) {
                // never in a space
            }

            // Change password; the old one stops working, the new one signs in; the other device is still signed in.
            a.client.changePassword(changed);
            Dev c = new Dev(url, key);
            c.client.signInPassword(email, changed);
            check("the new password signs in", email.equals(c.client.email()), "");
            code = "";
            try {
                new Dev(url, key).client.signInPassword(email, password);
            } catch (Exception e) {
                code = codeOf(e);
            }
            check("the old password no longer does", "invalid".equals(code), code);
            check("B still works after A changed the password", b.client.rpc("create_space", MiniJson.obj("device_name", "Live B"), false) != null, "");

            // Sign out ends one device's session only.
            c.client.logout();
            check("A is still signed in after C signed out", a.client.rpc("create_space", MiniJson.obj("device_name", "Live A"), false) != null, "");

            // Delete (or, without delete_my_account, leave).
            if (canDelete) {
                a.client.rpc("delete_my_account", null, false);
                cleaned = true;
                code = "";
                try {
                    new Dev(url, key).client.signInPassword(email, changed);
                } catch (Exception e) {
                    code = codeOf(e);
                }
                check("a deleted account can't sign in", "invalid".equals(code), code);
            }
        } finally {
            if (!cleaned) {
                try {
                    a.client.rpc("leave_space", null, false);
                    System.out.println("NOTE  left the space; the test user " + email + " remains (apply 0002_accounts.sql, then delete it from the Auth dashboard)");
                } catch (Exception e) {
                    System.out.println("NOTE  cleanup incomplete: " + codeOf(e) + "; test user " + email + " may remain");
                }
            }
        }
    }
}
