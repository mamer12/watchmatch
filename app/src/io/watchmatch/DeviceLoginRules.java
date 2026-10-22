package io.watchmatch;

/**
 * "Sign in this TV with your phone" without Android (DeviceLoginRulesCheck): the code's look, the QR link, the poll schedule
 * and the flow's state machine (request, pending, approved, add this TV's data?, linking, ready; a code that expires is
 * replaced; closing the screen cancels everything). Time is always passed in, so the check needs no clock.
 */
final class DeviceLoginRules {
    private DeviceLoginRules() {}

    static final long TTL_MS = 10 * 60_000L, POLL_FAST_MS = 3_000, POLL_SLOW_MS = 6_000, SLOW_AFTER_MS = 2 * 60_000L;

    /** "ABCDEFGH" as "ABCD EFGH" (anything else as it is). */
    static String group(String code) {
        return code != null && code.length() == SyncRules.CODE_LENGTH ? code.substring(0, 4) + " " + code.substring(4) : code == null ? "" : code;
    }

    /** What the QR code holds: the site with ?tv=CODE (the site's trailing slashes are dropped). */
    static String qrUrl(String site, String code) {
        String base = site == null ? "" : site.trim().replaceAll("/+$", "");
        return base + "/?tv=" + code;
    }

    /** Wait before asking again: 3 s, then 6 s once the code has been showing for 2 minutes. */
    static long pollDelay(long shownForMs) {
        return shownForMs >= SLOW_AFTER_MS ? POLL_SLOW_MS : POLL_FAST_MS;
    }

    /** "9:41" for the time left (never negative). */
    static String clock(long leftMs) {
        long s = Math.max(0, (leftMs + 999) / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    static final int IDLE = 0, REQUESTING = 1, PENDING = 2, ASK = 3, LINKING = 4, READY = 5, ERROR = 6, CLOSED = 7;

    /** One run of the screen. Main thread only; `gen` ties each answer to the request or poll it belongs to. */
    static final class Flow {
        int state = IDLE, gen, step, plan;
        String code = "", space = "", error = "";
        boolean offline, fetching;
        long shownAt, lastPollAt;

        /** A code is wanted (first time, retry or refresh). Returns the generation its answer must carry. */
        int request() {
            if (state == CLOSED) return gen;
            state = REQUESTING;
            code = "";
            error = "";
            offline = false;
            return ++gen;
        }

        void requested(int g, String newCode, long now) {
            if (state != REQUESTING || g != gen) return;
            if (!SyncRules.isCompleteCode(newCode)) {
                state = ERROR;
                error = AccountRules.E_ERROR;
                return;
            }
            state = PENDING;
            code = newCode;
            shownAt = lastPollAt = now;
        }

        void requestFailed(int g, String failure) {
            if (state != REQUESTING || g != gen) return;
            state = ERROR;
            error = failure == null ? AccountRules.E_ERROR : failure;
        }

        long remaining(long now) {
            return state == PENDING ? Math.max(0, TTL_MS - (now - shownAt)) : 0;
        }

        /** Once a second: true when the code ran out and a new one must be requested (request() was just called). */
        boolean tick(long now) {
            if (state != PENDING || now - shownAt < TTL_MS) return false;
            request();
            return true;
        }

        boolean pollDue(long now) {
            return state == PENDING && now - lastPollAt >= pollDelay(now - shownAt);
        }

        /** A poll goes out now; returns its generation. */
        int polling(long now) {
            lastPollAt = now;
            return gen;
        }

        /** The poll's answer: a space id once the phone approved, null while waiting. `step` is AccountRules.mergeStep. */
        void polled(int g, String approvedSpace, int mergeStep) {
            if (state != PENDING || g != gen) return;
            offline = false;
            if (approvedSpace == null || approvedSpace.isEmpty()) return;
            space = approvedSpace;
            step = mergeStep;
            if (mergeStep == AccountRules.STEP_ASK) {
                state = ASK;
            } else {
                plan = AccountRules.linkPlan(mergeStep, true);
                state = LINKING;
            }
        }

        /** A poll failed: no internet keeps waiting (and says so); a lost session needs a new code. */
        void pollFailed(int g, String failure) {
            if (state != PENDING || g != gen) return;
            if (AccountRules.E_SESSION.equals(failure)) {
                state = ERROR;
                error = failure;
            } else {
                offline = true;
            }
        }

        /** The answer to "Add this TV's data to your account?". */
        void chose(boolean add) {
            if (state != ASK) return;
            plan = AccountRules.linkPlan(step, add);
            state = LINKING;
        }

        void fetching() {
            if (state == LINKING) fetching = true;
        }

        void linked() {
            if (state == LINKING) state = READY;
        }

        void linkFailed(String failure) {
            if (state != LINKING) return;
            state = ERROR;
            error = failure == null ? AccountRules.E_ERROR : failure;
        }

        /** Closing the screen cancels: nothing that answers later counts, nothing is polled again. */
        void close() {
            state = CLOSED;
            gen++;
        }

        boolean active() {
            return state != CLOSED && state != READY;
        }
    }
}
