package io.watchmatch;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the `spark-tonight` Edge Function (the AI chat behind Spark Tonight). The session's token and the project's URL
 * and public key are the sync backend's; without them, or on any failure, the answer is a reason and the chat goes on
 * with the fixed questions. HTTPS only, timeouts, the same checks on the answer as the web app. Blocking: never call it
 * on the UI thread. No Android (a check runs it against a fake transport).
 */
final class TonightApi {
    static final int CONNECT_MS = 8_000, READ_MS = 20_000;

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /** The one HTTP call; replaced in checks. */
    interface Transport {
        Response send(String method, String url, Map<String, String> headers, String body) throws IOException;
    }

    /** A valid access token, or null when there is no session. */
    interface Token {
        String get();
    }

    private final String url, key;
    private final Token token;
    private final Transport transport;

    TonightApi(String url, String key, Token token) {
        this(url, key, token, TonightApi::send);
    }

    TonightApi(String url, String key, Token token, Transport transport) {
        this.url = url == null ? "" : url.replaceAll("/+$", "");
        this.key = key == null ? "" : key;
        this.token = token;
        this.transport = transport;
    }

    /** Built with a backend: an https URL and a public key. */
    boolean configured() {
        return url.startsWith("https://") && !key.isEmpty();
    }

    /** The AI can be asked: a backend and a session. May refresh the token (network): off the main thread. */
    boolean ready() {
        if (!configured()) return false;
        try {
            String t = token.get();
            return t != null && !t.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    TonightProtocol.Answer ask(List<TonightProtocol.Msg> messages, String summaryJson) {
        if (!configured()) return TonightProtocol.Answer.failed(TonightProtocol.Fail.NOT_CONFIGURED);
        String access;
        try {
            access = token.get();
        } catch (RuntimeException e) {
            access = null;
        }
        if (access == null || access.isEmpty()) return TonightProtocol.Answer.failed(TonightProtocol.Fail.NO_SESSION);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("apikey", key);
        headers.put("Authorization", "Bearer " + access);
        headers.put("Content-Type", "application/json");
        Response r;
        try {
            r = transport.send("POST", url + "/functions/v1/spark-tonight", headers,
                    TonightProtocol.requestBody(messages, summaryJson));
        } catch (SocketTimeoutException e) {
            return TonightProtocol.Answer.failed(TonightProtocol.Fail.TIMEOUT);
        } catch (IOException e) {
            return TonightProtocol.Answer.failed(TonightProtocol.Fail.UNAVAILABLE);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body = MiniJson.map(MiniJson.parse(r.body));
        } catch (RuntimeException e) {
            // not JSON: the status decides
        }
        boolean refused = Boolean.FALSE.equals(body.get("ok"));
        if (r.status < 200 || r.status >= 300 || refused) {
            return TonightProtocol.Answer.failed(TonightProtocol.failFor(r.status, MiniJson.str(body.get("reason"))));
        }
        TonightProtocol.Reply reply = TonightProtocol.sanitizeReply(body);
        return reply == null ? TonightProtocol.Answer.failed(TonightProtocol.Fail.BAD_RESPONSE) : TonightProtocol.Answer.ok(reply);
    }

    private static Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod(method);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int status = c.getResponseCode();
            java.io.InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return new Response(status, "");
            try (java.io.InputStream i = in) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = i.read(buf)) > 0 && out.size() < 64_000; ) out.write(buf, 0, n);
                return new Response(status, new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }
}
