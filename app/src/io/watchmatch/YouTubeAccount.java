package io.watchmatch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Signing in to YouTube on the TV, read-only (Google's sign-in for TVs: a code entered at google.com/device on a phone),
 * for "Recommended for you": WatchMatch's own picks from what YouTube lets other apps see (your likes, your
 * subscriptions). Your watch history and YouTube's own recommendations aren't open to other apps. The sign-in (a refresh token) stays
 * in this app's files on the TV. Blocking calls: off the main thread.
 */
final class YouTubeAccount {
    private YouTubeAccount() {}

    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final String PKG = "com.google.android.youtube.tv";
    private static final int CHANNELS = 25, PER_CHANNEL = 2;
    /** Recommended for you: up to SEEDS titles to find similar videos for (search costs YouTube's quota), PICKS shown. */
    private static final int SEEDS = 4, SEEDS_WATCHED = 2, PER_SEED = 6, PICKS = 20;

    private static String access;
    private static long accessUntil;

    /** A sign-in in progress: what to show (the code and where to enter it), and what to ask Google for meanwhile. */
    static final class Code {
        String userCode, url, deviceCode;
        int interval;
        long until;
    }

    static boolean configured() {
        return !Config.YOUTUBE_CLIENT_ID.trim().isEmpty() && !Config.YOUTUBE_CLIENT_SECRET.trim().isEmpty();
    }

    static boolean signedIn(Context c) {
        return configured() && file(c).exists();
    }

    static void signOut(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        access = null;
    }

    /** Starts a sign-in: the code to enter at the URL. */
    static Code start() throws Exception {
        JSONObject o = post("https://oauth2.googleapis.com/device/code",
                "client_id=" + enc(Config.YOUTUBE_CLIENT_ID.trim()) + "&scope=" + enc(SCOPE));
        Code code = new Code();
        code.userCode = o.getString("user_code");
        code.url = o.optString("verification_url", "https://www.google.com/device");
        code.deviceCode = o.getString("device_code");
        code.interval = Math.max(5, o.optInt("interval", 5));
        code.until = System.currentTimeMillis() + o.optInt("expires_in", 1800) * 1000L;
        return code;
    }

    /** One check: true once the person approved on the phone (the sign-in is then saved); false while waiting. */
    static boolean poll(Context c, Code code) throws Exception {
        JSONObject o;
        try {
            o = post("https://oauth2.googleapis.com/token", "client_id=" + enc(Config.YOUTUBE_CLIENT_ID.trim())
                    + "&client_secret=" + enc(Config.YOUTUBE_CLIENT_SECRET.trim()) + "&device_code=" + enc(code.deviceCode)
                    + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("authorization_pending") || m.contains("slow_down")) return false;
            throw e;
        }
        String refresh = o.optString("refresh_token", "");
        if (refresh.isEmpty()) throw new IOException("Google didn't send a sign-in");
        Durable.write(file(c), refresh);     // whole or not at all, even if the TV is unplugged right then
        access = o.optString("access_token", null);
        accessUntil = android.os.SystemClock.elapsedRealtime() + (o.optLong("expires_in", 3600) - 60) * 1000L;
        return true;
    }

    // MARK: - Recommended for you

    /**
     * WatchMatch's own picks (YouTube's recommendations aren't open to other apps): videos like the ones you liked
     * lately and the ones you opened from Home (`watched`, newest first), mixed with what's new from the channels you
     * subscribe to, leaving out what you already liked or watched. Empty when not signed in.
     */
    static List<Catalog.Entry> recommended(Context c, List<Catalog.Entry> watched) throws Exception {
        String token = token(c);
        if (token == null) return new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> seeds = new ArrayList<>();
        for (Catalog.Entry e : watched) {
            if (e.video == null) continue;
            seen.add(e.video);
            if (seeds.size() < SEEDS_WATCHED) seeds.add(e.title);
        }
        JSONArray liked = get("videos?part=snippet&myRating=like&maxResults=10", token).optJSONArray("items");
        for (int i = 0; liked != null && i < liked.length(); i++) {
            JSONObject v = liked.getJSONObject(i);
            seen.add(v.optString("id"));
            if (seeds.size() < SEEDS) seeds.add(v.getJSONObject("snippet").optString("title"));
        }
        // One list per seed (YouTube's search for the title's words), plus the subscriptions' new videos; in turns.
        List<List<String>> lists = new ArrayList<>();
        for (String seed : seeds) {
            String q = words(seed);
            if (q.isEmpty()) continue;
            List<String> ids = new ArrayList<>();
            try {
                JSONArray hits = get("search?part=id&type=video&maxResults=" + PER_SEED + "&q=" + enc(q), token).optJSONArray("items");
                for (int i = 0; hits != null && i < hits.length(); i++) ids.add(hits.getJSONObject(i).getJSONObject("id").optString("videoId"));
            } catch (IOException e) {
                AppLog.w("YouTube search: " + e.getMessage());
            }
            lists.add(ids);
        }
        lists.add(subscriptionUploads(token));
        List<String> picked = new ArrayList<>();
        for (int round = 0; picked.size() < PICKS; round++) {
            boolean any = false;
            for (List<String> l : lists) {
                if (round >= l.size()) continue;
                any = true;
                String id = l.get(round);
                if (!id.isEmpty() && seen.add(id) && picked.size() < PICKS) picked.add(id);
            }
            if (!any) break;
        }
        if (picked.isEmpty()) return new ArrayList<>();
        StringBuilder ids = new StringBuilder();
        for (String id : picked) ids.append(ids.length() == 0 ? "" : ",").append(id);
        return entries(get("videos?part=snippet&id=" + enc(ids.toString()), token).optJSONArray("items"));
    }

    /** YouTube's search (for the TV's search screen), or empty when not signed in. */
    static List<Catalog.Entry> search(Context c, String query) throws Exception {
        String token = token(c);
        if (token == null || query.trim().isEmpty()) return new ArrayList<>();
        JSONArray hits = get("search?part=id&type=video&maxResults=12&q=" + enc(query.trim()), token).optJSONArray("items");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; hits != null && i < hits.length(); i++) {
            ids.append(ids.length() == 0 ? "" : ",").append(hits.getJSONObject(i).getJSONObject("id").optString("videoId"));
        }
        if (ids.length() == 0) return new ArrayList<>();
        return entries(get("videos?part=snippet&id=" + enc(ids.toString()), token).optJSONArray("items"));
    }

    /** The newest uploads of the channels you subscribe to (newest first). */
    private static List<String> subscriptionUploads(String token) throws Exception {
        JSONArray subs = get("subscriptions?part=snippet&mine=true&order=relevance&maxResults=" + CHANNELS, token).optJSONArray("items");
        final List<JSONObject> videos = new ArrayList<>();
        for (int i = 0; subs != null && i < subs.length(); i++) {
            String channel = subs.getJSONObject(i).getJSONObject("snippet").getJSONObject("resourceId").optString("channelId");
            if (!channel.startsWith("UC")) continue;
            try {
                // A channel's uploads list is its id with "UU" for "UC" (cheaper than a search).
                JSONArray items = get("playlistItems?part=contentDetails&maxResults=" + PER_CHANNEL + "&playlistId=UU"
                        + channel.substring(2), token).optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) videos.add(items.getJSONObject(j).getJSONObject("contentDetails"));
            } catch (IOException e) {
                AppLog.w("YouTube channel " + channel + ": " + e.getMessage());
            }
        }
        Collections.sort(videos, (a, b) -> b.optString("videoPublishedAt").compareTo(a.optString("videoPublishedAt")));
        List<String> out = new ArrayList<>();
        for (JSONObject v : videos) out.add(v.optString("videoId"));
        return out;
    }

    /** A title's first few words, without hashtags, emoji and punctuation: what to search for. */
    private static String words(String title) {
        String clean = title.replaceAll("#\\S+", " ").replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        String[] w = clean.split(" ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length && i < 6; i++) b.append(i == 0 ? "" : " ").append(w[i]);
        return b.toString();
    }

    private static List<Catalog.Entry> entries(JSONArray items) throws Exception {
        List<Catalog.Entry> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject v = items.getJSONObject(i), sn = v.optJSONObject("snippet");
            JSONObject th = sn == null ? null : sn.optJSONObject("thumbnails");
            JSONObject small = th == null ? null : th.optJSONObject("medium");
            if (small == null) continue;
            Catalog.Entry e = new Catalog.Entry();
            e.title = sn.optString("title");
            e.sub = sn.optString("channelTitle");
            e.image = e.still = small.optString("url");
            JSONObject big = th.optJSONObject("maxres");
            if (big != null) e.wide = big.optString("url");
            e.overview = sn.optString("description").split("\n")[0];
            e.pkg = PKG;
            e.video = v.optString("id");
            out.add(e);
        }
        return out;
    }

    // MARK: - Tokens and requests

    /** A current access token (renewed from the saved sign-in when needed), or null when not signed in. */
    private static synchronized String token(Context c) throws Exception {
        if (!signedIn(c)) return null;
        if (access != null && android.os.SystemClock.elapsedRealtime() < accessUntil) return access;
        String refresh;
        try (FileInputStream in = new FileInputStream(file(c))) {
            refresh = read(in).trim();
        }
        JSONObject o;
        try {
            o = post("https://oauth2.googleapis.com/token", "client_id=" + enc(Config.YOUTUBE_CLIENT_ID.trim())
                    + "&client_secret=" + enc(Config.YOUTUBE_CLIENT_SECRET.trim()) + "&refresh_token=" + enc(refresh)
                    + "&grant_type=refresh_token");
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("invalid_grant")) signOut(c);     // revoked in the Google account
            throw e;
        }
        access = o.getString("access_token");
        accessUntil = android.os.SystemClock.elapsedRealtime() + (o.optLong("expires_in", 3600) - 60) * 1000L;
        return access;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "youtube-signin");
    }

    private static JSONObject get(String path, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return answer(c);
    }

    private static JSONObject post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = c.getOutputStream()) {
            out.write(form.getBytes(StandardCharsets.UTF_8));
        }
        return answer(c);
    }

    /** The JSON answer; an error answer becomes an IOException carrying Google's error code. */
    private static JSONObject answer(HttpURLConnection c) throws Exception {
        try {
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = in == null ? "" : read(in);
            if (code >= 400) {
                String error = body;
                try {
                    Object e = new JSONObject(body).opt("error");
                    error = e instanceof JSONObject ? ((JSONObject) e).optString("message") : String.valueOf(e);
                } catch (Exception ignored) {
                }
                throw new IOException("HTTP " + code + ": " + error);
            }
            return new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
