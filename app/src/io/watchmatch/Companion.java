package io.watchmatch;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your phone as the TV's magic wand: a small page on the home Wi-Fi (http://TV:8765) where you paste or share a link
 * (YouTube, TOD, Netflix, Apple TV…) and it opens on the TV, or type a title to search for everywhere (SearchActivity). A 6-digit PIN,
 * shown on the TV, keeps the rest of the Wi-Fi out. Runs in the home screen's process; opening apps from behind
 * another app is allowed because WatchMatch may draw over other apps.
 * Only the TV's own Wi-Fi address listens, only private addresses are answered, and the Host must be the TV itself (a
 * rebound DNS name can't reach the page). Five wrong PINs lock the page for everyone, each lock twice as long as the
 * last; the third lock makes a new PIN.
 */
final class Companion {
    private Companion() {}

    static final int PORT = 8765;
    private static final Pattern YOUTUBE = Pattern.compile("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/)|youtu\\.be/)([\\w-]{11})");
    private static final int PIN_DIGITS = 6, WORKERS = 4, MAX_LINE = 8192, MAX_HEADERS = 64, MAX_BODY = 4096;
    private static final int READ_TIMEOUT_MS = 5000, REQUEST_MS = 10_000, CHECK_ADDRESS_MS = 15_000;
    private static final long WRONG_PIN_DELAY_MS = 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile boolean running;
    private static volatile String pin;
    private static Context app;

    /** The address to open on the phone ("http://192.168.1.20:8765"), or null without a network. */
    static String address() {
        InetAddress a = lan();
        return a == null ? null : "http://" + a.getHostAddress() + ":" + PORT;
    }

    /** The TV's address on the home network (private IPv4), or null. */
    private static InetAddress lan() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The PIN the phone's page asks for once (made the first time, then kept until too many wrong ones). */
    static synchronized String pin(Context c) {
        if (pin != null) return pin;
        SharedPreferences sp = c.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        String saved = sp.getString("companionPin", "");
        if (saved.length() != PIN_DIGITS) saved = newPin(sp);   // the old 4-digit PIN too
        return pin = saved;
    }

    private static String newPin(SharedPreferences sp) {
        String made = String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        sp.edit().putString("companionPin", made).apply();
        return made;
    }

    private static synchronized void rotatePin() {
        pin = newPin(app.getSharedPreferences("home-ui", Context.MODE_PRIVATE));
        AppLog.w("phone page: too many wrong PINs, made a new one");
    }

    static synchronized void start(Context context) {
        if (running) return;
        running = true;
        app = context.getApplicationContext();
        pin(app);
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), r -> {
                    Thread w = new Thread(r, "companion-worker");
                    w.setDaemon(true);
                    return w;
                });
        workers.allowCoreThreadTimeOut(true);
        Thread t = new Thread(() -> {
            while (true) {
                InetAddress at = lan();
                if (at == null) {
                    pause(CHECK_ADDRESS_MS);     // no Wi-Fi yet: nothing to listen on
                    continue;
                }
                try {
                    listen(at, workers);
                } catch (Exception e) {
                    AppLog.w("phone page: " + e.getMessage());
                    pause(CHECK_ADDRESS_MS);
                }
            }
        }, "companion");
        t.setDaemon(true);
        t.start();
    }

    /** Serves on `at` until the TV's address changes (a new DHCP lease), then returns to bind the new one. */
    private static void listen(InetAddress at, ThreadPoolExecutor workers) throws IOException {
        final String host = at.getHostAddress() + ":" + PORT;
        try (ServerSocket server = new ServerSocket(PORT, 16, at)) {
            server.setSoTimeout(CHECK_ADDRESS_MS);
            while (at.equals(lan())) {
                final Socket s;
                try {
                    s = server.accept();
                } catch (SocketTimeoutException idle) {
                    continue;
                }
                if (!isPrivate(s.getInetAddress())) {
                    close(s);
                    continue;
                }
                try {
                    workers.execute(() -> {
                        try {
                            s.setSoTimeout(READ_TIMEOUT_MS);
                            serve(s, host);
                        } catch (Exception e) {
                            AppLog.w("phone page: " + e.getMessage());
                        } finally {
                            close(s);
                        }
                    });
                } catch (RejectedExecutionException busy) {
                    close(s);
                }
            }
        }
    }

    /** Home networks only: 10/8, 172.16/12, 192.168/16 (the phone's own address, not anything routed from outside). */
    private static boolean isPrivate(InetAddress a) {
        return a instanceof Inet4Address && a.isSiteLocalAddress();
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // MARK: - HTTP (one small request per connection)

    private static void serve(Socket s, String host) throws Exception {
        InputStream in = s.getInputStream();
        long deadline = System.nanoTime() + REQUEST_MS * 1_000_000L;
        String line = readLine(in, deadline);
        if (line == null) return;
        String[] req = line.split(" ");
        if (req.length < 2) return;
        String method = req[0], path = req[1];
        boolean page = method.equals("GET") && (path.equals("/") || path.startsWith("/?"));
        boolean send = method.equals("POST") && path.equals("/send");
        OutputStream out = s.getOutputStream();
        if (!page && !send) {
            reply(out, "404 Not Found", "text/plain; charset=utf-8", "", null);
            return;
        }
        Map<String, String> headers = readHeaders(in, deadline);
        if (!host.equalsIgnoreCase(headers.containsKey("host") ? headers.get("host").trim() : "")) {
            reply(out, "403 Forbidden", "text/plain; charset=utf-8", "", null);
            return;
        }
        if (page) {
            String nonce = nonce();
            reply(out, "200 OK", "text/html; charset=utf-8", page().replace("<script>", "<script nonce=" + nonce + ">"), nonce);
            return;
        }
        // A send from another site: its Origin or Sec-Fetch-Site says so.
        String origin = headers.get("origin"), site = headers.get("sec-fetch-site");
        if (origin != null && !origin.trim().equalsIgnoreCase("http://" + host)
                || site != null && !site.trim().equalsIgnoreCase("same-origin")) {
            reply(out, "403 Forbidden", "text/plain; charset=utf-8", "", null);
            return;
        }
        int length = 0;
        try {
            length = Math.max(0, Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length").trim() : "0"));
        } catch (NumberFormatException ignored) {
        }
        String form = new String(readUpTo(in, Math.min(length, MAX_BODY), deadline), StandardCharsets.UTF_8);
        int verdict = check(field(form, "pin"));
        if (verdict == LOCKED) {
            long minutes = Math.max(1, (lockedForMs() + 59_999) / 60_000);
            reply(out, "429 Too Many Requests", "text/plain; charset=utf-8", Ui.ar
                    ? Arabic.text("Too many wrong PINs. Try again in ") + minutes + " دقيقة"
                    : Ui.tr("Muitos PINs errados. Tente de novo em ", "Too many wrong PINs. Try again in ",
                            "Demasiados PIN incorrectos. Inténtalo de nuevo en ") + minutes + " min", null);
        } else if (verdict == WRONG) {
            reply(out, "403 Forbidden", "text/plain; charset=utf-8", "wrong PIN", null);
        } else {
            reply(out, "200 OK", "text/plain; charset=utf-8", handle(app, field(form, "text").trim()), null);
        }
    }

    private static String field(String form, String name) {
        for (String kv : form.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** One line, cut at MAX_LINE bytes; null at the end of the stream. A client trickling bytes runs into the deadline. */
    private static String readLine(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < MAX_LINE) {
            if (System.nanoTime() - deadline > 0) throw new SocketTimeoutException("request took too long");
            int b = in.read();
            if (b < 0) return line.size() == 0 ? null : line.toString("UTF-8");
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        return line.toString("UTF-8");
    }

    /** Headers keyed in lower case (the first of each kept). */
    private static Map<String, String> readHeaders(InputStream in, long deadline) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < MAX_HEADERS; i++) {
            String h = readLine(in, deadline);
            if (h == null || h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!headers.containsKey(name)) headers.put(name, h.substring(colon + 1).trim());
        }
        return headers;
    }

    private static byte[] readUpTo(InputStream in, int length, long deadline) throws IOException {
        byte[] body = new byte[length];
        int n = 0;
        while (n < length) {
            if (System.nanoTime() - deadline > 0) throw new SocketTimeoutException("request took too long");
            int r = in.read(body, n, length - n);
            if (r < 0) break;
            n += r;
        }
        return n == length ? body : java.util.Arrays.copyOf(body, n);
    }

    private static String nonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    /** nonce: the page's own script, the only one it may run (with it, the reply carries a Content-Security-Policy). */
    private static void reply(OutputStream out, String status, String type, String body, String nonce) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String csp = nonce == null ? "" : "Content-Security-Policy: default-src 'none'; script-src 'nonce-" + nonce
                + "'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n";
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" + csp
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    // MARK: - Wrong PINs (counted for every phone together)

    private static final int OK = 0, WRONG = 1, LOCKED = 2;
    private static final int LIMIT = 5, ROTATE_AFTER = 3, MAX_DOUBLINGS = 10;
    private static final long FIRST_LOCK_MS = 5 * 60_000L, MAX_LOCK_MS = 24 * 60 * 60_000L;
    private static final Object GUARD = new Object();
    private static int failures, locks;
    private static long lockedUntil;

    /** While locked, even the right PIN is refused. A wrong PIN costs a second, to slow guessing. */
    private static int check(String given) {
        synchronized (GUARD) {
            if (lockedForMs() > 0) return LOCKED;
            String expected = pin;
            if (expected != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                failures = 0;
                locks = 0;
                return OK;
            }
            if (++failures >= LIMIT) {
                failures = 0;
                locks++;
                lockedUntil = SystemClock.elapsedRealtime() + Math.min(MAX_LOCK_MS, FIRST_LOCK_MS << Math.min(locks - 1, MAX_DOUBLINGS));
                if (locks >= ROTATE_AFTER) {
                    locks = 0;
                    rotatePin();
                }
            }
        }
        pause(WRONG_PIN_DELAY_MS);
        return lockedForMs() > 0 ? LOCKED : WRONG;
    }

    private static long lockedForMs() {
        synchronized (GUARD) {
            return Math.max(0, lockedUntil - SystemClock.elapsedRealtime());
        }
    }

    // MARK: - On the TV

    /** Opens what came from the phone; returns what happened, for the phone's page. */
    private static String handle(final Context c, String text) {
        if (text.isEmpty()) return "Nothing to send";
        final Intent intent;
        final String said;
        Matcher yt = YOUTUBE.matcher(text);
        int http = text.indexOf("http");
        if (yt.find()) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + yt.group(1)))
                    .setPackage("com.google.android.youtube.tv");
            said = Ui.tr("Abrindo no YouTube", "Opening in YouTube", "Abriendo en YouTube");
        } else if (http >= 0) {
            String url = text.substring(http).split("\\s")[0];
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            String pkg = url.contains("tod.tv") ? "com.todtv.tod" : null;
            if (pkg != null && HomeApps.launchIntent(c.getPackageManager(), pkg) != null
                    && view.setPackage(pkg).resolveActivity(c.getPackageManager()) == null) {
                view = HomeApps.launchIntent(c.getPackageManager(), pkg);     // TOD's TV app takes no links: TOD itself
            }
            intent = view;
            said = Ui.tr("Abrindo na TV", "Opening on the TV", "Abriendo en la TV");
        } else {
            intent = new Intent(c, SearchActivity.class).putExtra(SearchActivity.EXTRA_QUERY, text);     // everywhere
            said = Ui.tr("Procurando: ", "Searching: ", "Buscando: ") + Ui.isolate(text);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                c.startActivity(intent);
                Toast.makeText(c, Ui.tr("Do celular: ", "From your phone: ", "Del teléfono: ") + Ui.isolate(said), Toast.LENGTH_SHORT).show();
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("from the phone: " + e.getMessage());
            }
        });
        return said;
    }

    /** The phone's page, in Arabic (right to left) when the TV's WatchMatch is. */
    private static String page() {
        if (!Ui.ar) return PAGE;
        return PAGE.replace("<html>", "<html lang=ar dir=rtl>")
                .replace("<h1>Send to TV</h1>", "<h1>أرسل إلى التلفاز</h1>")
                .replace("<p>Paste a YouTube or TOD link, or type a title to search for on the TV.</p>",
                        "<p>الصق رابط يوتيوب أو TOD، أو اكتب اسم عنوان للبحث عنه على التلفاز.</p>")
                .replace("placeholder='PIN on the TV'", "placeholder='رمز PIN الظاهر على التلفاز'")
                .replace("placeholder='Link or title'", "placeholder='رابط أو عنوان'")
                .replace(">Send</button>", ">إرسال</button>")
                .replace("'Sending…'", "'جارٍ الإرسال…'")
                .replace("'Wrong PIN'", "'رمز PIN غير صحيح'")
                .replace("'The TV did not answer'", "'لم يستجب التلفاز'");
    }

    /** `s` safe inside the page's HTML (the app's name comes from config.properties). */
    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The phone's page: one box, one button; the PIN is asked once and kept in the phone's browser. */
    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>" + html(Config.APP_NAME) + "</title><style>"
            + "body{margin:0;font-family:system-ui,sans-serif;background:#0b0b0d;color:#f5f5f7;display:flex;min-height:100vh;"
            + "align-items:center;justify-content:center}main{width:min(92vw,520px)}h1{font-size:26px;margin:0 0 6px}"
            + "p{color:#a1a1a6;margin:0 0 20px}textarea,input{width:100%;box-sizing:border-box;background:#1c1c1e;color:#fff;"
            + "border:1px solid #333;border-radius:16px;padding:16px;font-size:18px}textarea{height:120px;resize:none}"
            + "button{margin-top:14px;width:100%;padding:16px;border:0;border-radius:16px;background:#fff;color:#000;"
            + "font-size:18px;font-weight:600}#msg{margin-top:14px;color:#30d158;min-height:24px}"
            + "#pinbox{display:none}</style></head><body><main><h1>Send to TV</h1>"
            + "<p>Paste a YouTube or TOD link, or type a title to search for on the TV.</p>"
            + "<div id=pinbox><input id=pin inputmode=numeric maxlength=6 placeholder='PIN on the TV'></div>"
            + "<textarea id=text placeholder='Link or title'></textarea><button id=go>Send</button><div id=msg></div>"
            + "<script>var pin=localStorage.getItem('pin')||'';var box=document.getElementById('pinbox');"
            + "if(!pin)box.style.display='block';"
            + "var q=new URLSearchParams(location.search);var shared=q.get('text')||q.get('url')||'';"
            + "if(shared)document.getElementById('text').value=shared;"
            + "document.getElementById('go').onclick=function(){var p=pin||document.getElementById('pin').value;"
            + "var t=document.getElementById('text').value;var m=document.getElementById('msg');m.style.color='#a1a1a6';"
            + "m.textContent='Sending…';fetch('/send',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
            + "body:'pin='+encodeURIComponent(p)+'&text='+encodeURIComponent(t)}).then(function(r){return r.text().then(function(x){"
            + "if(r.status==403){localStorage.removeItem('pin');pin='';box.style.display='block';m.style.color='#ff453a';"
            + "m.textContent='Wrong PIN';return}if(!r.ok){m.style.color='#ff453a';m.textContent=x;return}localStorage.setItem('pin',p);pin=p;box.style.display='none';"
            + "m.style.color='#30d158';m.textContent=x;document.getElementById('text').value=''})})"
            + ".catch(function(){m.style.color='#ff453a';m.textContent='The TV did not answer'})}</script></main></body></html>";
}
