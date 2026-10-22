package io.watchmatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The sync backend over REST (Supabase): anonymous sign-in, token refresh, RPCs and table reads and writes. HTTPS only,
 * no Android (the live check runs it on a plain JVM). Blocking: never call it on the UI thread.
 */
final class SyncClient implements SyncEngine.Api {
    /**
     * The tokens of the guest (anonymous) or the account (email and password) in use; expiresAt in ms. `email` is "" for
     * a guest. The password is never kept: only these tokens are.
     */
    static final class Session {
        final String access, refresh, userId, email;
        final long expiresAt;

        Session(String access, String refresh, String userId, long expiresAt) {
            this(access, refresh, userId, expiresAt, "");
        }

        Session(String access, String refresh, String userId, long expiresAt, String email) {
            this.access = access;
            this.refresh = refresh;
            this.userId = userId;
            this.expiresAt = expiresAt;
            this.email = email == null ? "" : email;
        }

        Session withEmail(String e) {
            return new Session(access, refresh, userId, expiresAt, e);
        }
    }

    /** Where the session is kept (durably: a lost refresh token means a lost account). save(null) forgets it. */
    interface Store {
        Session load();
        void save(Session s);
    }

    /** status 0 is "no network". */
    static final class HttpException extends Exception {
        final int status;

        HttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** The one HTTP call; replaced in checks. */
    interface Transport {
        Response send(String method, String url, Map<String, String> headers, String body) throws IOException;
    }

    private static final long REFRESH_MARGIN_MS = 60_000;
    private static final int CONNECT_MS = 15_000, READ_MS = 25_000;

    private final String url, key;
    private final Store store;
    private final LongSupplier now;
    private final Transport transport;

    SyncClient(String url, String key, Store store, LongSupplier now) {
        this(url, key, store, now, SyncClient::httpsSend);
    }

    SyncClient(String url, String key, Store store, LongSupplier now, Transport transport) {
        this.url = url.replaceAll("/+$", "");
        this.key = key;
        this.store = store;
        this.now = now;
        this.transport = transport;
    }

    /**
     * Who wrote a row, as the engine tells its own rows from others' (updated_by). A guest is one device, so its id is
     * that; an account is shared by every device signed in to it, so no row can be told as "mine" by its writer: null,
     * and what another device wrote is applied (an echo of this device's own row is the same data, a no-op).
     */
    @Override public String userId() {
        Session s = store.load();
        return s == null || !s.email.isEmpty() ? null : s.userId;
    }

    boolean signedIn() {
        return store.load() != null;
    }

    /** The account's email, or "" for a guest (or no session). */
    String email() {
        Session s = store.load();
        return s == null ? "" : s.email;
    }

    // ---- the wire ---------------------------------------------------------------------------

    private static Response httpsSend(String method, String url, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod(method);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(status, in == null ? "" : read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Response http(String method, String path, String body, String token, Map<String, String> extra) throws HttpException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("apikey", key);
        h.put("Content-Type", "application/json");
        if (extra != null) h.putAll(extra);
        if (token != null) h.put("Authorization", "Bearer " + token);
        try {
            return transport.send(method, url + path, h, body);
        } catch (IOException e) {
            throw new HttpException(0, String.valueOf(e.getMessage()));
        }
    }

    private static HttpException failure(Response r) {
        String message = "http_" + r.status;
        try {
            Map<String, Object> j = MiniJson.map(MiniJson.parse(r.body));
            for (String k : new String[] {"message", "msg", "error_description"}) {
                if (!MiniJson.str(j.get(k)).isEmpty()) {
                    message = MiniJson.str(j.get(k));
                    break;
                }
            }
            // The auth service's own code ("invalid_credentials", "weak_password"...) leads, PostgREST's stays as it is.
            String code = MiniJson.str(j.get("error_code"));
            if (!code.isEmpty()) message = code + ": " + message;
        } catch (RuntimeException e) {
            // no JSON body
        }
        return new HttpException(r.status, message);
    }

    // ---- the session ------------------------------------------------------------------------

    private Session parseSession(String body) throws HttpException {
        try {
            Map<String, Object> j = MiniJson.map(MiniJson.parse(body));
            String access = MiniJson.str(j.get("access_token")), refresh = MiniJson.str(j.get("refresh_token"));
            Map<String, Object> u = MiniJson.map(j.get("user"));
            String user = MiniJson.str(u.get("id")), email = MiniJson.str(u.get("email"));
            if (access.isEmpty() || refresh.isEmpty() || user.isEmpty()) throw new HttpException(500, "bad_auth_response");
            long expires = j.get("expires_at") instanceof Number ? MiniJson.lng(j.get("expires_at")) * 1000
                    : now.getAsLong() + (j.get("expires_in") instanceof Number ? MiniJson.lng(j.get("expires_in")) : 3600) * 1000;
            return new Session(access, refresh, user, expires, email);
        } catch (IllegalArgumentException e) {
            throw new HttpException(500, "bad_auth_response");
        }
    }

    /** Makes the anonymous account (once: only when starting or joining a space). */
    synchronized Session signUp() throws HttpException {
        Response r = http("POST", "/auth/v1/signup", "{}", null, null);
        if (r.status < 200 || r.status >= 300) throw failure(r);
        Session s = parseSession(r.body);
        store.save(s);
        return s;
    }

    // ---- accounts (email and password) --------------------------------------------------------

    private static String credentials(String email, String password) {
        return MiniJson.write(MiniJson.obj("email", email, "password", password));
    }

    /** Signs in with email and password and keeps the new session (the old one, a guest's, is replaced). */
    synchronized Session signInPassword(String email, String password) throws HttpException {
        Response r = http("POST", "/auth/v1/token?grant_type=password", credentials(email, password), null, null);
        if (r.status < 200 || r.status >= 300) throw failure(r);
        Session s = parseSession(r.body);
        store.save(s);
        return s;
    }

    /** Makes an account with no guest session behind it; "confirm_required" when the project wants the email confirmed first. */
    synchronized Session signUpPassword(String email, String password) throws HttpException {
        Response r = http("POST", "/auth/v1/signup", credentials(email, password), null, null);
        if (r.status < 200 || r.status >= 300) throw failure(r);
        if (!r.body.contains("\"access_token\"")) throw new HttpException(200, "confirm_required");
        Session s = parseSession(r.body);
        store.save(s);
        return s;
    }

    /** The guest becomes this account in place: same user id, same space, same data. */
    synchronized Session upgrade(String email, String password) throws HttpException {
        Response r = authed("PUT", "/auth/v1/user", credentials(email, password), null, false);
        if (r.status < 200 || r.status >= 300) throw failure(r);
        String now;
        try {
            now = MiniJson.str(MiniJson.map(MiniJson.parse(r.body)).get("email"));
        } catch (IllegalArgumentException e) {
            throw new HttpException(500, "bad_auth_response");
        }
        if (!now.equalsIgnoreCase(email)) throw new HttpException(200, "confirm_required");
        Session s = store.load().withEmail(now);
        store.save(s);
        return s;
    }

    void changePassword(String password) throws HttpException {
        Response r = authed("PUT", "/auth/v1/user", MiniJson.write(MiniJson.obj("password", password)), null, false);
        if (r.status < 200 || r.status >= 300) throw failure(r);
    }

    /** Ends this device's session only ("local": the other devices stay signed in). */
    void logout() throws HttpException {
        Response r = authed("POST", "/auth/v1/logout?scope=local", null, null, false);
        if (r.status < 200 || r.status >= 300) throw failure(r);
    }

    /** The refresh token is single-use: the new session is saved before anything else is done with it. */
    private synchronized Session refresh() throws HttpException {
        Session before = store.load();
        if (before == null) throw new HttpException(401, "no_session");
        Response r = http("POST", "/auth/v1/token?grant_type=refresh_token",
                MiniJson.write(MiniJson.obj("refresh_token", before.refresh)), null, null);
        if (r.status < 200 || r.status >= 300) {
            if (r.status == 400 || r.status == 401 || r.status == 403) {
                store.save(null);
                throw new HttpException(401, "auth_lost");
            }
            throw failure(r);
        }
        Session s = parseSession(r.body);
        store.save(s);
        return s;
    }

    /** A valid access token. Only starting or joining a space may sign up; syncing never makes a new account. */
    private synchronized String accessToken(boolean allowSignUp) throws HttpException {
        Session s = store.load();
        if (s == null) {
            if (!allowSignUp) throw new HttpException(401, "no_session");
            s = signUp();
        }
        if (s.expiresAt - now.getAsLong() < REFRESH_MARGIN_MS) s = refresh();
        return s.access;
    }

    /** An authenticated call; one refresh and retry on 401. */
    private Response authed(String method, String path, String body, Map<String, String> extra, boolean allowSignUp) throws HttpException {
        String token = accessToken(allowSignUp);
        Response r = http(method, path, body, token, extra);
        if (r.status == 401) {
            token = refresh().access;
            r = http(method, path, body, token, extra);
        }
        return r;
    }

    // ---- calls ------------------------------------------------------------------------------

    /** An RPC: its JSON result (String, List, Map...) or null. */
    Object rpc(String name, Map<String, Object> body, boolean allowSignUp) throws HttpException {
        Response r = authed("POST", "/rest/v1/rpc/" + name, MiniJson.write(body == null ? new LinkedHashMap<String, Object>() : body), null, allowSignUp);
        if (r.status < 200 || r.status >= 300) throw failure(r);
        if (r.body == null || r.body.trim().isEmpty()) return null;
        try {
            return MiniJson.parse(r.body);
        } catch (IllegalArgumentException e) {
            throw new HttpException(500, "bad_response");
        }
    }

    @Override public void upsert(String table, List<Map<String, Object>> rows) throws HttpException {
        if (rows.isEmpty()) return;
        String on = "watch_progress".equals(table) ? "space_id,item_key" : "settings".equals(table) ? "space_id,key" : "space_id,title_id";
        Map<String, String> prefer = new LinkedHashMap<>();
        prefer.put("Prefer", "resolution=merge-duplicates,return=minimal,missing=default");
        Response r = authed("POST", "/rest/v1/" + table + "?on_conflict=" + enc(on), MiniJson.write(rows), prefer, false);
        if (r.status < 200 || r.status >= 300) throw failure(r);
    }

    /** Rows with updated_at at or after the cursor (rows of one batch share a time), oldest first. */
    @Override public List<Map<String, Object>> pull(String table, String space, String cursor, int limit) throws HttpException {
        String since = cursor == null || cursor.isEmpty() ? "" : "&updated_at=gte." + enc(cursor);
        Response r = authed("GET", "/rest/v1/" + table + "?space_id=eq." + enc(space) + since + "&order=updated_at.asc&limit=" + limit,
                null, null, false);
        if (r.status < 200 || r.status >= 300) throw failure(r);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        try {
            for (Object o : MiniJson.list(MiniJson.parse(r.body))) if (o instanceof Map) out.add(MiniJson.map(o));
        } catch (IllegalArgumentException e) {
            throw new HttpException(500, "bad_response");
        }
        return out;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
