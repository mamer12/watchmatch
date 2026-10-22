package io.watchmatch;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds the player a the catalog video from a small server on 127.0.0.1, reading ahead with several connections at once.
 * Shabakaty's CDN gives one connection about 24 Mbit/s, and a 4K file averages 19 with peaks well above: alone, the
 * player stalls (and the catalog's own app falls back to a lower quality). Four connections fill the line, and up to
 * AHEAD × CHUNK bytes wait in memory ahead of the picture.
 * On the mirror (Endpoints, the "other" provider) it works differently: nothing is kept. Each range the player asks for
 * is cut into pieces of at most 8 MB, each split in 4 fetched at the same time (4 connections for everything), and
 * handed to the player in order as they arrive (StreamRules has the arithmetic; the Prefs switch parallelFetch turns it off).
 */
final class Streamer {
    /** 2 MB pieces, 24 ahead of each reader (48 MB, ~20 s of 4K), at most BUDGET in memory (72 MB). */
    private static final int CHUNK = 2 << 20, AHEAD = 24, BUDGET = 36, WORKERS = 4, TRIES = 3, LOG_EVERY = 64;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
    /** Connections open for parallel pieces, across every Streamer (the player's and a preview's): fair, first asked first served. */
    private static final Semaphore LINES = new Semaphore(StreamRules.MAX_CONNECTIONS, true);
    private static final ExecutorService PIECES = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "streamer-piece");
        t.setDaemon(true);
        return t;
    });
    /** A link's final address and size, kept while its signed link is good: warmed when a title page opens, used on Play. */
    private static final Map<String, Resolved> RESOLVED = new HashMap<>();
    private static final long RESOLVED_MAX_MS = 30 * 60_000L;

    private static final class Resolved {
        final String[] answer;
        final long at;

        Resolved(String[] answer, long at) {
            this.answer = answer;
            this.at = at;
        }
    }

    private static final class Chunk {
        byte[] data;
        boolean started, failed, pinned;
        long used;
    }

    final long total;
    private final String url;
    private final ServerSocket server;
    private final Map<Integer, Chunk> chunks = new HashMap<>();
    /** Each open connection's current chunk: chunks near one of them are kept and fetched. */
    private final Map<Integer, Integer> readers = new HashMap<>();
    private int nextReader, fetched;
    private long fetchedSince = System.nanoTime();
    private volatile boolean closed;
    /** Parallel pieces, nothing kept (the mirror), or the chunks above. */
    private final boolean parallel;

    /**
     * The file's final address (after the CDN's redirect) and its size; throws when the CDN doesn't say. On the mirror
     * the redirecting host is first replaced by a file host (the same signed link, one host per file), and an answer is
     * reused while its signed link is good, so the redirect is resolved ahead (Library.warmPlayback) and Play skips it.
     */
    static String[] probe(String address) throws IOException {
        long now = TrustedTime.now();
        synchronized (RESOLVED) {
            Resolved known = RESOLVED.get(address);
            if (known != null && now - known.at < RESOLVED_MAX_MS && !StreamRules.isExpiring(known.answer[0], now, StreamRules.EXPIRY_MARGIN_MS)) {
                return known.answer;
            }
            RESOLVED.remove(address);
        }
        Endpoints.Entry p = Endpoints.active();
        IOException last = null;
        List<String> tries = new ArrayList<>();
        if (QualityRules.adaptive(p.id)) {
            for (int i = 0; i < StreamRules.HOST_TRIES; i++) {
                String t = StreamRules.fileHostUrl(address, i, p.host, p.mediaHosts);
                if (t != null && !tries.contains(t)) tries.add(t);
            }
        }
        tries.add(address);
        for (String candidate : tries) {
            try {
                String[] answer = probeOne(candidate);
                synchronized (RESOLVED) { RESOLVED.put(address, new Resolved(answer, now)); }
                return answer;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no ranges");
    }

    /** Forgets a resolved link the server refused (expired or moved). */
    private static void forget(String finalUrl) {
        synchronized (RESOLVED) {
            RESOLVED.values().removeIf(r -> r.answer[0].equals(finalUrl));
        }
    }

    private static String[] probeOne(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Range", "bytes=0-0");
        try {
            int code = c.getResponseCode();
            long total = StreamRules.totalFromContentRange(c.getHeaderField("Content-Range"));
            String finalUrl = c.getURL().toString();
            if (code == 206 && total > 0) return new String[] {finalUrl, String.valueOf(total)};
            if (code != 206) throw new IOException("no ranges (HTTP " + code + ")");
        } finally {
            c.disconnect();
        }
        // No Content-Range: the size from a HEAD.
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setRequestMethod("HEAD");
        h.setConnectTimeout(8000);
        h.setReadTimeout(8000);
        try {
            long size = h.getContentLengthLong();
            if (h.getResponseCode() != 200 || size <= 0) throw new IOException("no size");
            return new String[] {h.getURL().toString(), String.valueOf(size)};
        } finally {
            h.disconnect();
        }
    }

    Streamer(String finalUrl, long size) throws IOException {
        url = finalUrl;
        total = size;
        Endpoints.Entry p = Endpoints.active();
        parallel = StreamRules.parallelWanted(p.id, Prefs.parallelFetch(), finalUrl, p.host, p.mediaHosts);
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        // The file's first and last pieces, right away and kept: players read the index there (MP4s made for
        // download keep it at the end, and the player then jumps between both ends several times while opening).
        if (!parallel) {
            synchronized (this) {
                pin(0);
                pin((int) ((total - 1) / CHUNK));
            }
        }
        Thread accept = new Thread(this::accept, "streamer");
        accept.setDaemon(true);
        accept.start();
        for (int i = 0; i < (parallel ? 0 : WORKERS); i++) {
            Thread t = new Thread(this::work, "streamer-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    private void pin(int index) {
        Chunk c = new Chunk();
        c.pinned = true;
        chunks.put(index, c);
    }

    // MARK: - Speed: bytes over the time at least one line was open (idle waits don't count), for QualityController

    private final Object meter = new Object();
    private int openLines;
    private long busySince, busyNanos, meterBytes;

    private void lineOpened() {
        synchronized (meter) {
            if (openLines++ == 0) busySince = System.nanoTime();
        }
    }

    private void lineClosed() {
        synchronized (meter) {
            if (openLines > 0 && --openLines == 0) busyNanos += System.nanoTime() - busySince;
        }
    }

    private void counted(int n) {
        synchronized (meter) {
            meterBytes += n;
        }
    }

    /** Bytes per second since the last call, or -1 when under 0.4 s of downloading happened since (nothing is reset then). */
    double takeSpeed() {
        synchronized (meter) {
            long now = System.nanoTime(), busy = busyNanos + (openLines > 0 ? now - busySince : 0);
            if (busy < 400_000_000L) return -1;
            double rate = meterBytes * 1e9 / busy;
            meterBytes = 0;
            busyNanos = 0;
            busySince = now;
            return rate;
        }
    }

    String address() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/video.mp4";
    }

    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        synchronized (this) {
            chunks.clear();
            notifyAll();
        }
    }

    // MARK: - The player's side

    private void accept() {
        while (!closed) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "streamer-out");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!closed) AppLog.w("streamer: " + e);
            }
        }
    }

    private void serve(Socket socket) {
        int reader;
        synchronized (this) {
            reader = nextReader++;
        }
        try (Socket s = socket) {
            String request = readHead(s.getInputStream());
            long start = 0, end = total - 1;
            Matcher m = RANGE.matcher(request);
            boolean ranged = m.find();
            if (ranged) {
                start = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2)));
            }
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
            if (start >= total) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total + "\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            String head = (ranged ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + start + "-" + end + "/" + total + "\r\n"
                    : "HTTP/1.1 200 OK\r\n")
                    + "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: " + (end - start + 1)
                    + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (request.startsWith("HEAD")) {
                out.flush();
                return;
            }
            if (parallel) {
                servePieces(out, start, end);
                out.flush();
                return;
            }
            long pos = start;
            while (pos <= end && !closed) {
                int index = (int) (pos / CHUNK);
                byte[] data = await(reader, index);
                int off = (int) (pos - (long) index * CHUNK);
                int n = (int) Math.min(data.length - off, end - pos + 1);
                out.write(data, off, n);
                pos += n;
            }
            out.flush();
        } catch (IOException e) {
            // The player closed the connection: a seek, or the end.
        } finally {
            synchronized (this) {
                readers.remove(reader);
                evict();
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while (b.length() < 8192 && (c = in.read()) >= 0) {
            b.append((char) c);
            if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) break;
        }
        return b.toString();
    }

    /** The chunk's bytes, once downloaded; asks for the ones after it. */
    private synchronized byte[] await(int reader, int index) throws IOException {
        readers.put(reader, index);
        evict();
        int retried = 0;
        while (!closed) {
            Chunk c = chunks.get(index);
            if (c == null) {
                c = new Chunk();
                chunks.put(index, c);
            }
            want(index);
            if (c.data != null) {
                c.used = System.nanoTime();
                return c.data;
            }
            if (c.failed) {
                if (retried++ >= 1) throw new IOException("chunk " + index + " failed");
                c.failed = c.started = false;
                notifyAll();
            }
            try {
                wait(1000);
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        throw new IOException("closed");
    }

    /** Queues the chunks after `index` (the workers take the nearest first). */
    private void want(int index) {
        int last = (int) ((total - 1) / CHUNK);
        for (int i = index; i <= Math.min(last, index + AHEAD); i++) {
            if (!chunks.containsKey(i)) chunks.put(i, new Chunk());
        }
        notifyAll();
    }

    /**
     * Forgets the waiting chunks no reader needs any more (left by a seek), then, over the budget, the downloaded ones
     * read longest ago. Ones just read stay: the player often comes back to them.
     */
    private void evict() {
        Iterator<Map.Entry<Integer, Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Chunk> e = it.next();
            Chunk c = e.getValue();
            if (!c.pinned && !c.started && c.data == null && !near(e.getKey())) it.remove();
        }
        while (chunks.size() > BUDGET) {
            Integer victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<Integer, Chunk> e : chunks.entrySet()) {
                Chunk c = e.getValue();
                if (c.pinned || c.data == null || near(e.getKey())) continue;
                if (c.used < oldest) {
                    oldest = c.used;
                    victim = e.getKey();
                }
            }
            if (victim == null) return;
            chunks.remove(victim);
        }
    }

    private boolean near(int i) {
        for (int at : readers.values()) if (i >= at - 1 && i <= at + AHEAD) return true;
        return false;
    }

    // MARK: - Parallel pieces (the mirror)

    /** One span of at most 8 MB, cut into pieces being fetched. */
    private final class Fetch {
        final StreamRules.Span span;
        final List<Piece> pieces = new ArrayList<>();

        Fetch(StreamRules.Span span) {
            this.span = span;
            for (StreamRules.Span s : StreamRules.split(span, StreamRules.PIECES)) pieces.add(new Piece(s.start, s.end));
        }

        void start() {
            for (Piece p : pieces) PIECES.execute(p);
        }

        void cancel() {
            for (Piece p : pieces) p.cancel();
        }
    }

    /** Answers the player's range: spans of at most 8 MB in a row, each one's pieces fetched together, written in order. */
    private void servePieces(OutputStream out, long start, long end) throws IOException {
        List<Fetch> live = new ArrayList<>();
        try {
            Fetch cur = begin(start, end, live);
            while (cur != null && !closed) {
                Fetch next = null;
                for (int i = 0; i < cur.pieces.size(); i++) {
                    // The next span starts being fetched while the last piece of this one is written.
                    if (i == cur.pieces.size() - 1 && cur.span.end < end) next = begin(cur.span.end + 1, end, live);
                    cur.pieces.get(i).writeTo(out);
                }
                live.remove(cur);
                cur = next;
            }
        } finally {
            for (Fetch f : live) f.cancel();     // the player left (a seek, or the end): free the connections
        }
    }

    private Fetch begin(long from, long end, List<Fetch> live) {
        StreamRules.Span span = StreamRules.clampSpan(from, end, total, StreamRules.MAX_RESPONSE_BYTES);
        if (span == null) return null;
        Fetch f = new Fetch(span);
        live.add(f);
        f.start();
        return f;
    }

    private final class Piece implements Runnable {
        final long from, to;
        private byte[] data;
        private int filled;
        private boolean done, cancelled;
        private IOException error;
        private HttpURLConnection connection;

        Piece(long from, long to) {
            this.from = from;
            this.to = to;
        }

        @Override public void run() {
            try {
                LINES.acquire();
            } catch (InterruptedException e) {
                finish(new IOException("interrupted"));
                return;
            }
            IOException failure = null;
            try {
                byte[] buffer = new byte[(int) (to - from + 1)];
                synchronized (this) { data = buffer; }
                for (int attempt = 0; attempt <= StreamRules.RETRIES && !isCancelled(); attempt++) {
                    failure = null;
                    try {
                        download();
                        break;
                    } catch (IOException e) {
                        failure = e;     // tried again from the byte it reached
                    }
                }
            } finally {
                LINES.release();
            }
            finish(isCancelled() ? new IOException("closed") : failure);
        }

        private void finish(IOException e) {
            synchronized (this) {
                error = e;
                done = true;
                notifyAll();
            }
        }

        private synchronized boolean isCancelled() {
            return cancelled || closed;
        }

        synchronized void cancel() {
            cancelled = true;
            if (connection != null) connection.disconnect();
            notifyAll();
        }

        private void download() throws IOException {
            int at;
            synchronized (this) { at = filled; }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Range", "bytes=" + (from + at) + "-" + to);
            synchronized (this) {
                if (cancelled) throw new IOException("closed");
                connection = c;
            }
            lineOpened();
            try {
                int code = c.getResponseCode();
                if (code == 403 || code == 410) forget(url);     // the signed link ran out
                if (code != 206) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while (!isCancelled() && at < data.length && (n = in.read(data, at, data.length - at)) > 0) {
                        at += n;
                        counted(n);
                        synchronized (this) {
                            filled = at;
                            notifyAll();
                        }
                    }
                }
                if (at < data.length) throw new IOException("short read");
            } finally {
                lineClosed();
                c.disconnect();
            }
        }

        /** The piece's bytes to `out`, as they come in; if it can't be fetched, the rest comes by one plain request. */
        void writeTo(OutputStream out) throws IOException {
            int written = 0, size = (int) (to - from + 1);
            while (written < size) {
                int n;
                IOException failed = null;
                byte[] buffer;
                synchronized (this) {
                    while (filled <= written && !done) {
                        try {
                            wait(1000);
                        } catch (InterruptedException e) {
                            throw new IOException("interrupted");
                        }
                    }
                    n = filled - written;
                    buffer = data;
                    if (n <= 0) failed = error != null ? error : new IOException("short piece");
                }
                if (failed == null) {
                    out.write(buffer, written, n);
                    written += n;
                    continue;
                }
                if (closed || cancelled) throw failed;
                AppLog.w("streamer piece " + from + ": " + failed.getMessage() + ", plain request");
                plain(out, from + written, to);
                return;
            }
        }
    }

    /** The original request, untouched: one connection straight to the player. */
    private void plain(OutputStream out, long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        try {
            if (c.getResponseCode() != 206) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[16384];
                long left = to - from + 1;
                int n;
                while (left > 0 && !closed && (n = in.read(buffer, 0, (int) Math.min(buffer.length, left))) > 0) {
                    out.write(buffer, 0, n);
                    left -= n;
                }
                if (left > 0) throw new IOException("short read");
            }
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Downloads

    private void work() {
        while (!closed) {
            int index;
            Chunk c;
            synchronized (this) {
                index = nearest();
                if (index < 0) {
                    try {
                        wait(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                c = chunks.get(index);
                c.started = true;
            }
            byte[] data = null;
            for (int attempt = 0; attempt < TRIES && data == null && !closed; attempt++) {
                try {
                    data = fetch(index);
                } catch (IOException e) {
                    AppLog.w("streamer chunk " + index + ": " + e.getMessage());
                }
            }
            synchronized (this) {
                if (data != null) c.data = data;
                else c.failed = true;
                if (data != null && ++fetched % LOG_EVERY == 0) logSpeed();
                notifyAll();
            }
        }
    }

    /** Download speed over the last LOG_EVERY chunks (adb logcat -s WatchMatch): is the line fast enough for 4K? */
    private void logSpeed() {
        long now = System.nanoTime();
        double seconds = (now - fetchedSince) / 1e9;
        fetchedSince = now;
        AppLog.w(String.format(java.util.Locale.US, "streamer %.1f Mbit/s, %d chunks in memory", LOG_EVERY * (double) CHUNK * 8 / seconds / 1e6,
                chunks.size()));
    }

    /** The waiting chunk closest after a reader's place (-1 when none). */
    private int nearest() {
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Chunk> e : chunks.entrySet()) {
            Chunk c = e.getValue();
            if (c.started || c.data != null || c.failed) continue;
            if (c.pinned && bestDistance > 1) {     // the ends come early, but after what a reader waits for now
                bestDistance = 1;
                best = e.getKey();
            }
            for (int at : readers.values()) {
                int d = e.getKey() - at;
                if (d >= 0 && d < bestDistance) {
                    bestDistance = d;
                    best = e.getKey();
                }
            }
        }
        return best;
    }

    private byte[] fetch(int index) throws IOException {
        long from = (long) index * CHUNK, to = Math.min(total, from + CHUNK) - 1;
        byte[] data = new byte[(int) (to - from + 1)];
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        lineOpened();
        try {
            if (c.getResponseCode() != 206) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                    if (closed) throw new IOException("closed");
                    off += n;
                    counted(n);
                }
                if (off < data.length) throw new IOException("short read");
            }
            return data;
        } finally {
            lineClosed();
            c.disconnect();
        }
    }
}
