package io.watchmatch;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sync rules that need no Android (SyncRulesCheck runs them on a plain JVM): pairing codes, the outbox and how it
 * coalesces, row times, which remote rows win, and when progress may be sent. The web app (watch-me-plus, src/sync)
 * follows the same rules; keep them in step.
 */
final class SyncRules {
    private SyncRules() {}

    static final String[] TABLES = {"watch_progress", "my_list", "ratings", "settings"};
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 8;
    static final long PAIRING_TTL_MS = 10 * 60_000L;
    static final String JOIN_ORIGIN = "https://watch-me-plus.vercel.app";
    /** Progress goes out at most this often while playing; anything else after a short pause. */
    static final long PROGRESS_PUSH_MS = 15_000, PUSH_DEBOUNCE_MS = 1_500, POLL_MS = 60_000;
    static final int PAGE_SIZE = 1000, PUSH_BATCH = 200;

    // ---- pairing codes ----------------------------------------------------------------------

    /** "k7m2 qx4p", "K7M2-QX4P" and "k7m2qx4p" all become "K7M2QX4P" (anything but letters and digits is dropped). */
    static String normalizeCode(String input) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; input != null && i < input.length() && b.length() < CODE_LENGTH; i++) {
            char c = Character.toUpperCase(input.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    static boolean isCompleteCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) return false;
        for (int i = 0; i < code.length(); i++) if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) return false;
        return true;
    }

    /** "K7M2QX4P" to "K7M2-QX4P", for reading aloud. */
    static String formatCode(String code) {
        return code.length() == CODE_LENGTH ? code.substring(0, 4) + "-" + code.substring(4) : code;
    }

    static String joinUrl(String code) {
        return JOIN_ORIGIN + "/?join=" + code;
    }

    // ---- times ------------------------------------------------------------------------------

    /** An ISO time as the server writes it ("2026-10-22T10:16:02.5+00:00", "...Z") in ms; 0 when it is not one. */
    static long parseTime(Object v) {
        if (!(v instanceof String)) return 0;
        try {
            return OffsetDateTime.parse((String) v).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static String isoTime(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    /** When the change was made: its client_ts, else the server's updated_at. */
    static long rowTs(Map<String, Object> row) {
        long t = parseTime(row.get("client_ts"));
        return t > 0 ? t : parseTime(row.get("updated_at"));
    }

    static long serverMs(Map<String, Object> row) {
        return parseTime(row.get("updated_at"));
    }

    /** The key of a row in its table: item_key, key or title_id. */
    static String keyOf(String table, Map<String, Object> row) {
        Object v = row.get("watch_progress".equals(table) ? "item_key" : "settings".equals(table) ? "key" : "title_id");
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    // ---- the outbox -------------------------------------------------------------------------

    /** One queued change. `row` is the table's row without space_id and client_ts. */
    static final class Item {
        final String table, key;
        final Map<String, Object> row;
        /** When the person made the change (ms): sent as client_ts, so an old offline replay loses. */
        final long ts;

        Item(String table, String key, Map<String, Object> row, long ts) {
            this.table = table;
            this.key = key;
            this.row = row;
            this.ts = ts;
        }

        String id() {
            return table + "|" + key;
        }
    }

    /** Newer changes replace older ones for the same row (which keeps its place in the queue). */
    static List<Item> coalesce(List<Item> outbox, List<Item> items) {
        Map<String, Item> map = new LinkedHashMap<>();
        for (Item o : outbox) map.put(o.id(), o);
        for (Item i : items) {
            Item old = map.get(i.id());
            if (old == null || i.ts >= old.ts) map.put(i.id(), i);
        }
        return new ArrayList<>(map.values());
    }

    /** The next batch to send: the first item's table, up to PUSH_BATCH of its items, in queue order. */
    static List<Item> nextBatch(List<Item> outbox) {
        List<Item> out = new ArrayList<>();
        if (outbox.isEmpty()) return out;
        String table = outbox.get(0).table;
        for (Item i : outbox) {
            if (i.table.equals(table) && out.size() < PUSH_BATCH) out.add(i);
        }
        return out;
    }

    /** What is left after `sent` went out; a newer change to the same row (another ts) stays queued. */
    static List<Item> removeSent(List<Item> outbox, List<Item> sent) {
        List<Item> out = new ArrayList<>();
        for (Item o : outbox) {
            boolean gone = false;
            for (Item s : sent) if (s.table.equals(o.table) && s.key.equals(o.key) && s.ts == o.ts) gone = true;
            if (!gone) out.add(o);
        }
        return out;
    }

    /** The row as sent: its columns, the space and the time of the change. */
    static Map<String, Object> toRow(String spaceId, Item i) {
        Map<String, Object> m = new LinkedHashMap<>(i.row);
        m.put("space_id", spaceId);
        m.put("client_ts", isoTime(i.ts));
        return m;
    }

    // ---- which remote rows win --------------------------------------------------------------

    /**
     * A row from the server replaces local data unless this device wrote it itself, it has a newer change for the same
     * item still queued (pendingTs, null = none; an upload at first link carries 0, so the server wins), or it is the
     * video playing right now (progress never moves under it).
     */
    static boolean shouldApply(Map<String, Object> row, String selfUid, Long pendingTs, boolean playingNow) {
        Object by = row.get("updated_by");
        if (selfUid != null && selfUid.equals(by)) return false;
        if (pendingTs != null && pendingTs > rowTs(row)) return false;
        return !playingNow;
    }

    // ---- when to send -----------------------------------------------------------------------

    /**
     * Milliseconds from `now` until the outbox should go out. Progress alone waits for the 15 s since the last progress
     * push (and the debounce); My List, ratings and settings go after the debounce.
     */
    static long pushDelay(long now, long lastProgressPush, boolean onlyProgress) {
        if (!onlyProgress) return PUSH_DEBOUNCE_MS;
        return Math.max(PUSH_DEBOUNCE_MS, lastProgressPush + PROGRESS_PUSH_MS - now);
    }

    /** The wait before retry number `failures` (1 = the first retry), same steps as Backoff. */
    static long retryDelay(int failures) {
        return Backoff.delayFor(failures);
    }

    // ---- errors -----------------------------------------------------------------------------

    /** Joining went wrong: "wrong", "too_many_attempts", "network" or "error". */
    static String joinError(int status, String message) {
        if ("wrong".equals(message)) return "wrong";
        if (message != null && message.contains("too_many_attempts")) return "too_many_attempts";
        return status == 0 ? "network" : "error";
    }
}
