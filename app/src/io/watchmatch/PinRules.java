package io.watchmatch;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * A profile's PIN without Android (ProfileRulesCheck runs it on a plain JVM): the format (4 to 6 digits), the PBKDF2
 * hash that is stored and synced ({salt, iter, hash}, all base64), a constant-time check, and the lockout (5 wrong tries
 * lock the PIN for 5 minutes). The lockout is a value: Profiles keeps it on disk and passes the time (TrustedTime).
 * The web app uses the same parameters (PBKDF2-HMAC-SHA256, 20000 iterations, 16-byte salt, 32-byte hash), so a PIN set
 * on one device opens the profile on the others.
 */
final class PinRules {
    private PinRules() {}

    static final int MIN_DIGITS = 4, MAX_DIGITS = 6, ITERATIONS = 20_000, SALT_BYTES = 16, HASH_BYTES = 32, MAX_TRIES = 5;
    static final long LOCK_MS = 5 * 60_000L;

    static boolean validFormat(String pin) {
        if (pin == null || pin.length() < MIN_DIGITS || pin.length() > MAX_DIGITS) return false;
        for (int i = 0; i < pin.length(); i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        return true;
    }

    static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BYTES * 8);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 missing: " + e);
        }
    }

    /** A new PIN as stored: a fresh random salt. */
    static Map<String, Object> hash(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return hash(pin, salt);
    }

    static Map<String, Object> hash(String pin, byte[] salt) {
        return MiniJson.obj("salt", Base64.getEncoder().encodeToString(salt), "iter", (long) ITERATIONS,
                "hash", Base64.getEncoder().encodeToString(derive(pin, salt, ITERATIONS)));
    }

    /** True when `pin` is the one `stored` was made from. Constant time in the hash; false for anything malformed. */
    static boolean verify(String pin, Map<String, Object> stored) {
        if (stored == null || !validFormat(pin)) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(MiniJson.str(stored.get("salt")));
            byte[] want = Base64.getDecoder().decode(MiniJson.str(stored.get("hash")));
            long iter = MiniJson.lng(stored.get("iter"));
            if (salt.length == 0 || want.length == 0 || iter < 1 || iter > 1_000_000) return false;
            return MessageDigest.isEqual(want, derive(pin, salt, (int) iter));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when `stored` looks like a PIN hash (what a synced row may carry). */
    static boolean wellFormed(Map<String, Object> stored) {
        if (stored == null) return false;
        try {
            return Base64.getDecoder().decode(MiniJson.str(stored.get("salt"))).length > 0
                    && Base64.getDecoder().decode(MiniJson.str(stored.get("hash"))).length > 0 && MiniJson.lng(stored.get("iter")) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- the lockout ------------------------------------------------------------------------

    /** Wrong tries so far and, when locked, until when (ms by TrustedTime). */
    static final class Lock {
        static final Lock NONE = new Lock(0, 0);
        final int fails;
        final long until;

        Lock(int fails, long until) {
            this.fails = fails;
            this.until = until;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Lock && ((Lock) o).fails == fails && ((Lock) o).until == until;
        }

        @Override public int hashCode() {
            return fails * 31 + (int) until;
        }
    }

    static boolean locked(Lock l, long now) {
        return remaining(l, now) > 0;
    }

    /** Ms left; never more than the lockout itself (a clock set back must not lock for longer). */
    static long remaining(Lock l, long now) {
        return Math.max(0, Math.min(l.until - now, LOCK_MS));
    }

    /** The lock after a wrong PIN at `now`: the 5th wrong try locks and starts the count again. */
    static Lock afterWrong(Lock l, long now) {
        if (locked(l, now)) return l;
        int fails = (l.until > 0 && l.until <= now ? 0 : l.fails) + 1;
        return fails >= MAX_TRIES ? new Lock(0, now + LOCK_MS) : new Lock(fails, 0);
    }

    static final int LOCKED = 0, OK = 1, WRONG = 2;

    /** What entering `pin` does: the answer and the lock after it. A locked PIN is not even checked. */
    static final class Attempt {
        final int result;
        final Lock lock;

        Attempt(int result, Lock lock) {
            this.result = result;
            this.lock = lock;
        }
    }

    static Attempt attempt(String pin, Map<String, Object> stored, Lock lock, long now) {
        if (locked(lock, now)) return new Attempt(LOCKED, lock);
        return result(verify(pin, stored), lock, now);
    }

    /** The same once the PIN was checked (the screens do the slow hashing off the UI thread): `match` is its outcome. */
    static Attempt result(boolean match, Lock lock, long now) {
        if (locked(lock, now)) return new Attempt(LOCKED, lock);
        if (match) return new Attempt(OK, Lock.NONE);
        Lock after = afterWrong(lock, now);
        return new Attempt(locked(after, now) ? LOCKED : WRONG, after);
    }

    /** Tries left before the lock (5 when fresh). */
    static int triesLeft(Lock l) {
        return Math.max(0, MAX_TRIES - l.fails);
    }
}
