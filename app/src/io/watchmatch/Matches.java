package io.watchmatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Match Centre's data: the week's matches of the competitions you follow, with crests, kick-off and, while they're on,
 * the live score (ESPN's public scoreboard, no key). Your teams' matches come first. Fetched every few hours, every
 * minute while one of them is live; kept on disk.
 */
final class Matches {
    static final int PRE = 0, LIVE = 1, DONE = 2;

    static final class Side {
        String name = "", shortName = "", logo = "";
        String score = "";
    }

    static final class Match {
        String id = "", league = "", leagueName = "";
        final Side home = new Side(), away = new Side();
        long start;
        int state;
        String clock = "";          // "67'", "HT", "FT"
        boolean mine;               // one of your teams plays

        String title() { return home.name + " vs " + away.name; }
    }

    interface Listener { void onMatches(); }

    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    private static final int DAYS = 7;
    private static final long EVERY = 3 * 3600_000L, LIVE_EVERY = 60_000L;

    private static Matches instance;

    private final File file;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Match> matches = new ArrayList<>();
    private long fetchedAt;
    private boolean fetching;
    /** A fetch that failed waits before the next (5 s, 15 s… then 10 min); the matches on disk stay meanwhile. */
    private final Backoff backoff = new Backoff();
    private final Runnable retry = () -> {
        if (!listeners.isEmpty()) refreshIfStale();
    };

    static synchronized Matches get(Context c) {
        if (instance == null) instance = new Matches(c.getApplicationContext());
        return instance;
    }

    private Matches(Context c) {
        file = new File(c.getFilesDir(), "matches.json");
        load();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** From now on (and the ones still on), soonest first, yours before the others at the same time of day. */
    List<Match> upcoming() {
        List<Match> out = new ArrayList<>();
        long now = TrustedTime.now();
        for (Match m : matches) if (m.state == LIVE || (m.state == PRE && m.start > now - 3 * 3600_000L)) out.add(m);
        return out;
    }

    /** Your team's next match (or the one on now) within `withinMs`, or null. */
    Match myNext(long withinMs) {
        long now = TrustedTime.now();
        for (Match m : upcoming()) if (m.mine && (m.state == LIVE || m.start < now + withinMs)) return m;
        return null;
    }

    boolean anyLive() {
        for (Match m : matches) if (m.state == LIVE) return true;
        return false;
    }

    /** The network came up or the clock was set: fetched again now if the last try failed. */
    void retryNow() {
        backoff.reset();
        refreshIfStale();
    }

    void refreshIfStale() {
        if (fetching || backoff.waiting()) return;
        long now = TrustedTime.now();
        boolean liveSoon = anyLive();
        for (Match m : matches) liveSoon |= m.state == PRE && Math.abs(m.start - now) < 5 * 60_000L;
        long every = liveSoon ? LIVE_EVERY : EVERY;
        if (now - fetchedAt < every && now >= fetchedAt) return;
        final boolean todayOnly = liveSoon && now - fetchedAt < EVERY;      // live: only today's scoreboards
        fetching = true;
        io.execute(() -> {
            try {
                java.util.Set<String> missing = new java.util.HashSet<>();
                List<Match> fresh = fetch(todayOnly ? 1 : DAYS, missing);
                if (todayOnly || !missing.isEmpty()) fresh = merge(fresh);    // a league that didn't answer keeps its matches
                matches = fresh;
                long at = TrustedTime.now();
                if (!todayOnly && missing.isEmpty()) fetchedAt = at;
                else fetchedAt = Math.min(fetchedAt, at - EVERY + LIVE_EVERY);
                if (missing.isEmpty()) backoff.succeeded(); else failed();
                save();
            } catch (Exception e) {
                failed();
                AppLog.w("matches: " + e);
            } finally {
                fetching = false;
                main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onMatches(); });
            }
        });
    }

    private void failed() {
        backoff.failed();
        main.removeCallbacks(retry);
        main.postDelayed(retry, backoff.delay());
    }

    // MARK: - ESPN

    /** The next `days` days' matches; the leagues whose boards didn't all answer go in `missing`. */
    private List<Match> fetch(int days, java.util.Set<String> missing) throws Exception {
        List<Match> out = new ArrayList<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        int failures = 0, tries = 0;
        for (String league : leagues()) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(TrustedTime.now());
            for (int d = 0; d < days; d++) {
                tries++;
                try {
                    JSONObject body = new JSONObject(get(API + league + "/scoreboard?dates=" + day.format(cal.getTime())));
                    parse(body, league, out);
                } catch (IOException e) {
                    failures++;
                    missing.add(league);
                }
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
        if (tries > 0 && failures == tries) throw new IOException("ESPN didn't answer");
        // De-duplicate (a match can sit on two days' boards around midnight), then by kick-off.
        List<Match> unique = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Match m : out) if (seen.add(m.id)) unique.add(m);
        Collections.sort(unique, (a, b) -> Long.compare(a.start, b.start));
        return unique;
    }

    /** Today's fresh scoreboards over the week already known. */
    private List<Match> merge(List<Match> today) {
        List<Match> out = new ArrayList<>(today);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Match m : today) ids.add(m.id);
        for (Match m : matches) if (!ids.contains(m.id)) out.add(m);
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private static void parse(JSONObject body, String league, List<Match> out) throws Exception {
        JSONArray leagues = body.optJSONArray("leagues");
        String leagueName = leagues != null && leagues.length() > 0 ? leagues.getJSONObject(0).optString("name") : league;
        JSONArray events = body.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
            JSONArray teams = comp.optJSONArray("competitors");
            if (teams == null || teams.length() < 2) continue;
            Match m = new Match();
            m.id = e.optString("id");
            m.league = league;
            m.leagueName = leagueName;
            m.start = time(e.optString("date"));
            JSONObject type = e.getJSONObject("status").getJSONObject("type");
            String state = type.optString("state");
            m.state = "in".equals(state) ? LIVE : "post".equals(state) ? DONE : PRE;
            m.clock = m.state == LIVE ? e.getJSONObject("status").optString("displayClock", type.optString("shortDetail"))
                    : type.optString("shortDetail");
            for (int t = 0; t < teams.length(); t++) {
                JSONObject c = teams.getJSONObject(t), team = c.getJSONObject("team");
                Side s = "away".equals(c.optString("homeAway")) ? m.away : m.home;
                s.name = team.optString("displayName");
                s.shortName = team.optString("shortDisplayName", s.name);
                s.logo = team.optString("logo");
                s.score = c.optString("score");
            }
            m.mine = mine(m.home.name) || mine(m.away.name);
            out.add(m);
        }
    }

    /** One of your teams (Prefs.teams: picked on the TV, else config.properties): a name containing one of them. */
    static boolean mine(String team) {
        for (String t : Prefs.teams().split(",")) {
            String want = t.trim().toLowerCase(Locale.ROOT);
            if (!want.isEmpty() && team.toLowerCase(Locale.ROOT).contains(want)) return true;
        }
        return false;
    }

    /** The competitions followed (Prefs.leagues: picked on the TV, else config.properties). */
    static List<String> leagues() {
        List<String> out = new ArrayList<>();
        for (String l : Prefs.leagues().split(",")) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }

    // MARK: - Match Centre settings

    private String fetchedLeagues = Prefs.leagues();

    /**
     * Teams or leagues changed on the TV: your matches marked again at once; new leagues fetched on the next refresh
     * (the home screen's, or the alarm's).
     */
    void settingsChanged() {
        for (Match m : matches) m.mine = mine(m.home.name) || mine(m.away.name);
        if (!Prefs.leagues().equals(fetchedLeagues)) {
            fetchedLeagues = Prefs.leagues();
            fetchedAt = 0;
            backoff.reset();
        }
        io.execute(this::save);
    }

    /** The teams in those competitions ({name, competition}), from ESPN; in the background. Empty when it can't. */
    static List<String[]> teams(List<String> leagues) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String league : leagues) {
            try {
                JSONObject body = new JSONObject(get(API + league + "/teams"));
                JSONArray sports = body.optJSONArray("sports");
                JSONObject sport = sports == null || sports.length() == 0 ? null : sports.optJSONObject(0);
                JSONArray ls = sport == null ? null : sport.optJSONArray("leagues");
                JSONObject l = ls == null || ls.length() == 0 ? null : ls.optJSONObject(0);
                JSONArray teams = l == null ? null : l.optJSONArray("teams");
                String name = l == null ? league : l.optString("name", league);
                for (int i = 0; teams != null && i < teams.length(); i++) {
                    JSONObject t = teams.getJSONObject(i).optJSONObject("team");
                    String team = t == null ? "" : t.optString("displayName");
                    if (!team.isEmpty() && seen.add(team)) out.add(new String[] {team, name});
                }
            } catch (Exception e) {
                AppLog.w("teams of " + league + ": " + e);
            }
        }
        return out;
    }

    /** The teams in the matches already known (when ESPN's team lists can't be read). */
    List<String[]> fixtureTeams() {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Match m : matches) {
            for (Side s : new Side[] {m.home, m.away}) if (!s.name.isEmpty() && seen.add(s.name)) out.add(new String[] {s.name, m.leagueName});
        }
        return out;
    }

    /** "2026-10-21T19:00Z" → epoch ms. */
    private static long time(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 16)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** "Tue 22:00" (today: "22:00"; tomorrow: "Tomorrow 22:00"). */
    static String when(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        now.setTimeInMillis(TrustedTime.now());
        then.setTimeInMillis(at);
        String time = new SimpleDateFormat("HH:mm", Ui.locale()).format(new Date(at));
        int days = then.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
                + 365 * (then.get(Calendar.YEAR) - now.get(Calendar.YEAR));
        if (days == 0) return Ui.tr("Hoje ", "Today ", "Hoy ") + time;
        if (days == 1) return Ui.tr("Amanhã ", "Tomorrow ", "Mañana ") + time;
        return new SimpleDateFormat("EEE", Ui.locale()).format(new Date(at)) + " " + time;
    }

    // MARK: - Disk and network

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Match m : matches) {
                a.put(new JSONObject().put("id", m.id).put("league", m.league).put("leagueName", m.leagueName)
                        .put("start", m.start).put("state", m.state).put("clock", m.clock).put("mine", m.mine)
                        .put("home", side(m.home)).put("away", side(m.away)));
            }
            Durable.write(file, new JSONObject().put("matches", a).put("at", fetchedAt).toString());
        } catch (Exception e) {
            AppLog.w("matches not saved: " + e);
        }
    }

    private static JSONObject side(Side s) throws Exception {
        return new JSONObject().put("name", s.name).put("short", s.shortName).put("logo", s.logo).put("score", s.score);
    }

    private void load() {
        String saved = Durable.read(file);
        if (saved == null) return;
        try {
            JSONObject o = new JSONObject(saved);
            fetchedAt = o.optLong("at");
            JSONArray a = o.optJSONArray("matches");
            List<Match> list = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Match m = new Match();
                m.id = j.optString("id");
                m.league = j.optString("league");
                m.leagueName = j.optString("leagueName");
                m.start = j.optLong("start");
                m.state = j.optInt("state");
                m.clock = j.optString("clock");
                m.mine = j.optBoolean("mine");
                load(j.optJSONObject("home"), m.home);
                load(j.optJSONObject("away"), m.away);
                list.add(m);
            }
            matches = list;
        } catch (Exception e) {
            AppLog.w("matches not read: " + e);
        }
    }

    private static void load(JSONObject j, Side s) {
        if (j == null) return;
        s.name = j.optString("name");
        s.shortName = j.optString("short");
        s.logo = j.optString("logo");
        s.score = j.optString("score");
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("ESPN returned HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
