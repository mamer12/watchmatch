package io.watchmatch;

import java.util.Locale;

/**
 * Watchme+ accounts without Android (AccountRulesCheck): what the start does, which call makes or joins an account,
 * what happens to the data already on the TV, which words a failure becomes, what a sign-out must wipe, and the
 * two-step delete. A guest is the anonymous session; an account is the same session with an email and a password.
 * The password is only ever held while a form is open and in the one request that carries it (redact() is for logs).
 */
final class AccountRules {
    private AccountRules() {}

    static final int MIN_PASSWORD = 8, MAX_PASSWORD = 72, MAX_EMAIL = 254;

    // ---- who is using this TV ---------------------------------------------------------------

    static final int MODE_NONE = 0, MODE_GUEST = 1, MODE_ACCOUNT = 2, MODE_LINKED = 3;

    /** No session: nothing yet. A session with no email: a guest. With an email: an account. */
    static int mode(boolean hasSession, String email) {
        if (!hasSession) return MODE_NONE;
        return email == null || email.isEmpty() ? MODE_GUEST : MODE_ACCOUNT;
    }

    /** As above, and a guest session that a phone linked to an account is MODE_LINKED (no email is known on the TV). */
    static int mode(boolean hasSession, String email, boolean linkedByPhone) {
        int m = mode(hasSession, email);
        return m == MODE_GUEST && linkedByPhone ? MODE_LINKED : m;
    }

    /** What Settings, Account offers: a linked TV manages its account elsewhere (no password or delete here). */
    static boolean emailActions(int mode) {
        return mode == MODE_ACCOUNT;
    }

    // ---- the start --------------------------------------------------------------------------

    static final int PLAN_GO = 0, PLAN_WELCOME = 1, PLAN_SIGN_IN_AGAIN = 2;

    /**
     * What Watchme+ shows first. Nothing new when accounts are off, for an account whose session is alive, for a guest
     * already syncing (it was paired before accounts) and for a person who chose to continue as a guest. An account whose
     * tokens are gone asks for the password again; everyone else sees the welcome screen.
     */
    static int startPlan(boolean enabled, boolean hasSession, boolean linked, String email, boolean guestChosen) {
        if (!enabled) return PLAN_GO;
        if (email != null && !email.isEmpty()) return hasSession ? PLAN_GO : PLAN_SIGN_IN_AGAIN;
        if (hasSession || linked || guestChosen) return PLAN_GO;
        return PLAN_WELCOME;
    }

    // ---- which call ---------------------------------------------------------------------------

    static final int CREATE_NONE = 0, CREATE_UPGRADE = 1, CREATE_SIGNUP = 2;

    /** Create account: a guest's session becomes the account in place (PUT /auth/v1/user); with no session it is a sign-up. */
    static int createKind(boolean hasSession, String email) {
        switch (mode(hasSession, email)) {
            case MODE_ACCOUNT: return CREATE_NONE;
            case MODE_GUEST: return CREATE_UPGRADE;
            default: return CREATE_SIGNUP;
        }
    }

    // ---- signing in on a TV that has data -----------------------------------------------------

    static final int STEP_SAME_SPACE = 0, STEP_ASK = 1, STEP_LINK = 2;

    /**
     * After the password was accepted and the account's space is known: the same space (nothing changes), data on the TV
     * that the account does not have (ask "Add this TV's data to your account?") or nothing to ask.
     */
    static int mergeStep(String currentSpace, String accountSpace, boolean hasLocalData) {
        if (currentSpace != null && !currentSpace.isEmpty() && currentSpace.equals(accountSpace)) return STEP_SAME_SPACE;
        return hasLocalData ? STEP_ASK : STEP_LINK;
    }

    static final int LINK_KEEP_STATE = 0, LINK_MERGE = 1, LINK_FRESH = 2;

    /**
     * What to do with the TV's own data. Same space: just keep going. Link and add: the TV's data stays and the first
     * sync merges it with the account's (My List is the union, the newest progress wins, profile lists join with their
     * deletions: SyncEngine). Link without adding: the TV is cleared first and only the account's data comes in.
     */
    static int linkPlan(int step, boolean addData) {
        if (step == STEP_SAME_SPACE) return LINK_KEEP_STATE;
        if (step == STEP_ASK && !addData) return LINK_FRESH;
        return LINK_MERGE;
    }

    /** The old guest space is left once the TV moves to another space. */
    static boolean leavesOldSpace(String currentSpace, String accountSpace) {
        return currentSpace != null && !currentSpace.isEmpty() && !currentSpace.equals(accountSpace);
    }

    // ---- the form -----------------------------------------------------------------------------

    static String cleanEmail(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    static boolean validEmail(String s) {
        String e = cleanEmail(s);
        if (e.isEmpty() || e.length() > MAX_EMAIL) return false;
        int at = e.indexOf('@');
        if (at < 1 || at != e.lastIndexOf('@') || at == e.length() - 1) return false;
        String domain = e.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        if (dot < 1 || dot > domain.length() - 3) return false;
        for (int i = 0; i < e.length(); i++) if (Character.isWhitespace(e.charAt(i))) return false;
        return true;
    }

    /** "short", "long" or null. */
    static String passwordIssue(String p) {
        if (p == null || p.length() < MIN_PASSWORD) return "short";
        return p.length() > MAX_PASSWORD ? "long" : null;
    }

    /** What is wrong with a sign-in or create form before anything is sent: "email", "password" or null. A sign-in only needs a non-empty password. */
    static String formIssue(String email, String password, boolean creating) {
        if (!validEmail(email)) return "email";
        if (creating ? passwordIssue(password) != null : password == null || password.isEmpty()) return "password";
        return null;
    }

    // ---- failures -----------------------------------------------------------------------------

    static final String E_NETWORK = "network", E_INVALID = "invalid", E_TAKEN = "taken", E_WEAK = "weak", E_RATE = "rate",
            E_CONFIRM = "confirm", E_UNAVAILABLE = "unavailable", E_SESSION = "session", E_ERROR = "error";

    /** A failed call as one of the codes above (status 0 is "no network"; the message is the server's, never the request). */
    static String error(int status, String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (status == 0) return E_NETWORK;
        if (m.contains("confirm_required") || m.contains("email_not_confirmed")) return E_CONFIRM;
        if (m.contains("invalid_credentials") || m.contains("invalid login")) return E_INVALID;
        if (m.contains("email_exists") || m.contains("user_already_exists") || m.contains("already registered")
                || m.contains("already been registered")) return E_TAKEN;
        if (m.contains("weak_password") || m.contains("password should") || m.contains("password is too")) return E_WEAK;
        if (status == 429 || m.contains("rate_limit") || m.contains("rate limit") || m.contains("too_many")) return E_RATE;
        if (m.contains("pgrst202") || m.contains("could not find the function") || (status == 404 && m.contains("delete_my_account"))) return E_UNAVAILABLE;
        if (status == 401 || m.contains("auth_lost") || m.contains("no_session") || m.contains("session_not_found")) return E_SESSION;
        return E_ERROR;
    }

    // ---- sign out -----------------------------------------------------------------------------

    /**
     * What a sign-out must take off the TV (the names WatchmeReset gives its steps, plus the account marker): the data
     * stays in the account, nothing on the server is deleted.
     */
    static final String[] SIGN_OUT_WIPES = {"sync link", "profiles", "lists, ratings, progress and settings", "catalog answers",
        "pictures", "saved picks", "account marker"};

    /**
     * Ends this device's session (best effort: offline or already ended is fine), then runs every wipe step, each
     * allowed to fail without stopping the rest. The wipes always run: a sign-out that stopped halfway would leave
     * someone's lists on the TV.
     */
    static ResetRules.Result signOut(ResetRules.Step logout, String[] names, ResetRules.Step[] wipes) {
        try {
            logout.run();
        } catch (Exception | LinkageError e) {
            // offline, or the session was already gone
        }
        return ResetRules.run(names, wipes);
    }

    // ---- delete account -----------------------------------------------------------------------

    static final long DELETE_DELAY_MS = 5_000, DELETE_WINDOW_MS = 30_000;

    /**
     * Delete account needs the confirm button pressed twice with at least 5 seconds between (a TV has no practical way
     * to type DELETE). The first press arms it; a press before the delay does nothing and does not restart it; the one
     * after fires, unless 30 seconds passed (then that press arms it again). Leaving the screen disarms it.
     */
    static final class DeleteGate {
        private long armedAt = -1;

        /** True when this press deletes. */
        boolean press(long now) {
            if (armedAt >= 0 && now - armedAt >= DELETE_WINDOW_MS) armedAt = -1;
            if (armedAt < 0) {
                armedAt = now;
                return false;
            }
            if (now - armedAt < DELETE_DELAY_MS) return false;
            armedAt = -1;
            return true;
        }

        boolean armed() {
            return armedAt >= 0;
        }

        /** Whole seconds until the second press counts (0 when it does), or -1 when not armed. */
        int secondsLeft(long now) {
            if (armedAt < 0) return -1;
            long left = DELETE_DELAY_MS - (now - armedAt);
            return left <= 0 ? 0 : (int) ((left + 999) / 1000);
        }

        void disarm() {
            armedAt = -1;
        }
    }

    // ---- secrets ------------------------------------------------------------------------------

    /** Anything that may go to a log: passwords, tokens and the Authorization header hidden. */
    static String redact(String s) {
        if (s == null) return "";
        String out = s.replaceAll("(?i)(\"?(?:password|access_token|refresh_token|apikey)\"?\\s*[:=]\\s*)\"[^\"]*\"", "$1\"***\"");
        out = out.replaceAll("(?i)(password|access_token|refresh_token)=[^&\\s]+", "$1=***");
        return out.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer ***");
    }
}
