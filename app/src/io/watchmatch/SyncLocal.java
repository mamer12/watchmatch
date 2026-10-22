package io.watchmatch;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The app's library as sync sees it and where remote changes land: Progress, My List, ratings and a few Settings
 * (CinemaPrefs) of every profile, and the profiles list. Reads and writes go through the stores' own functions, scoped
 * to the profile with ProfilePrefs.with; the engine's baselines keep what it applies from being sent back.
 */
final class SyncLocal implements SyncEngine.Local, SyncEngine.Sink {
    private final Context c;
    private final int epoch;

    SyncLocal(Context c, int epoch) {
        this.c = c.getApplicationContext();
        this.epoch = epoch;
    }

    /** False once Reset or Disconnect happened: what was still in flight must not touch the library. */
    private boolean current() {
        return epoch == Sync.epoch();
    }

    // ---- titles -----------------------------------------------------------------------------

    static Library.Title title(SyncMap.T t) {
        Library.Title o = new Library.Title();
        o.id = t.id;
        o.title = t.title;
        o.other = t.other;
        o.overview = t.overview;
        o.year = t.year;
        o.stars = t.stars;
        o.genres = t.genres;
        o.poster = Endpoints.image(t.poster);
        o.cover = Endpoints.image(t.cover);
        o.imdb = t.imdb;
        o.rootId = t.rootId;
        o.series = t.series;
        o.season = t.season;
        o.episode = t.episode;
        o.minutes = t.minutes;
        return o;
    }

    static SyncMap.T neutral(Library.Title o) {
        SyncMap.T t = new SyncMap.T();
        t.id = o.id;
        t.title = o.title;
        t.other = o.other;
        t.overview = o.overview;
        t.year = o.year;
        t.stars = o.stars;
        t.genres = o.genres;
        t.poster = o.poster;
        t.cover = o.cover;
        t.imdb = o.imdb;
        t.rootId = o.rootId;
        t.series = o.series;
        t.season = o.season;
        t.episode = o.episode;
        t.minutes = o.minutes;
        return t;
    }

    // ---- Local ------------------------------------------------------------------------------

    @Override public Map<String, SyncEngine.Snapshot> snapshots() {
        Map<String, SyncEngine.Snapshot> out = new LinkedHashMap<>();
        for (ProfileRules.Profile p : Profiles.live(c)) out.put(p.id, ProfilePrefs.with(p.id, this::snapshot));
        for (String pid : Profiles.wipePending(c)) {
            SyncEngine.Snapshot s = new SyncEngine.Snapshot();
            s.wipe = true;
            out.put(pid, s);
        }
        return out;
    }

    /** The library of the profile this thread is scoped to (ProfilePrefs.with). */
    private SyncEngine.Snapshot snapshot() {
        SyncEngine.Snapshot s = new SyncEngine.Snapshot();
        for (Progress.Entry e : Progress.everything(c)) {
            SyncMap.P p = new SyncMap.P();
            p.title = neutral(e.title);
            p.rootTitle = e.rootTitle;
            p.rootPoster = e.rootPoster;
            p.position = e.position;
            p.duration = e.duration;
            p.at = e.at;
            p.upNext = e.upNext;
            p.hidden = e.hidden;
            p.from = e.from;
            s.progress.add(p);
        }
        for (Library.Title t : CinemaPrefs.myList(c)) s.myList.add(neutral(t));
        s.ratings.putAll(CinemaPrefs.ratings(c));
        for (String key : SyncMap.SETTING_KEYS) s.settings.put(key, setting(key));
        return s;
    }

    @Override public List<ProfileRules.Profile> profiles() {
        return Profiles.all(c);
    }

    @Override public SyncEngine.Sink sink() {
        return this;
    }

    @Override public String playing() {
        String id = Sync.playingId();
        return id == null ? null : ProfileRules.prefixKey(Profiles.activeId(c), id);
    }

    @Override public boolean arabicFirst() {
        return Library.arabicFirst;
    }

    @Override public boolean deleted(String pid) {
        return Profiles.isDeleted(c, pid);
    }

    @Override public void wiped(Set<String> pids) {
        Profiles.wipeDone(c, pids);
    }

    /** The saved value (never the one a Kids profile forces: that is not what the person chose). */
    private Object setting(String key) {
        switch (key) {
            case "level": return CinemaPrefs.rawLevel(c);
            case "titleLang": return CinemaPrefs.arabicTitles(c);
            case "familySkips": return CinemaPrefs.rawSkipScenes(c);
            case "autoplayNext": return CinemaPrefs.autoplay(c);
            case "subtitleLang": return CinemaPrefs.subtitles(c);
            case "subtitleSize": return CinemaPrefs.subtitleSize(c);
            case "subtitleColor": return CinemaPrefs.subtitleYellow(c);
            default: return CinemaPrefs.subtitleStyle(c);
        }
    }

    // ---- Sink -------------------------------------------------------------------------------

    @Override public boolean progress(String pid, SyncMap.RP rp) {
        return current() && ProfilePrefs.with(pid, () -> seen(Progress.applyRemote(c, rp)));
    }

    @Override public boolean myList(String pid, String id, SyncMap.T title) {
        return current() && ProfilePrefs.with(pid, () -> seen(CinemaPrefs.applyRemoteMyList(c, id, title == null ? null : title(title))));
    }

    @Override public boolean rating(String pid, String id, int tv) {
        return current() && ProfilePrefs.with(pid, () ->
                seen(CinemaPrefs.applyRemoteRating(c, id, tv, tv == CinemaPrefs.RATE_NONE ? null : known(id))));
    }

    /**
     * Data arrived for this profile: its welcome questions were answered somewhere else, so its first launch here must
     * not clear it (CinemaActivity.profileReady). On the scoped profile's thread.
     */
    private boolean seen(boolean changed) {
        if (changed && !CinemaPrefs.welcomed(c)) CinemaPrefs.setWelcomed(c);
        return changed;
    }

    /** The title with this id from My List or the history, for the liked titles; null when nothing here has it. */
    private Library.Title known(String id) {
        List<Library.Title> mine = CinemaPrefs.myList(c);
        for (Library.Title t : mine) if (t.id.equals(id)) return t;
        for (Progress.Entry e : Progress.everything(c)) if (e.title.id.equals(id)) return e.title;
        return null;
    }

    @Override public Object setting(String pid, String key, Object remote) {
        if (!current()) return null;
        return ProfilePrefs.with(pid, () -> {
            Object now = SyncMap.fromRemote(key, remote, setting(key));
            if (now == null) return null;
            switch (key) {
                case "level": CinemaPrefs.setLevel(c, (Integer) now); break;
                case "titleLang":
                    CinemaPrefs.setArabicTitles(c, (Boolean) now);
                    if (pid.equals(Profiles.activeId(c))) Library.arabicFirst = (Boolean) now;
                    break;
                case "familySkips": CinemaPrefs.setSkipScenes(c, (Boolean) now); break;
                case "autoplayNext": CinemaPrefs.setAutoplay(c, (Boolean) now); break;
                case "subtitleLang": CinemaPrefs.setSubtitles(c, (String) now); break;
                case "subtitleSize": CinemaPrefs.setSubtitleSize(c, (Integer) now); break;
                case "subtitleColor": CinemaPrefs.setSubtitleYellow(c, (Boolean) now); break;
                default: CinemaPrefs.setSubtitleStyle(c, (Integer) now);
            }
            return now;
        });
    }

    @Override public boolean profiles(List<ProfileRules.Profile> remote) {
        return current() && Profiles.applyRemote(c, remote);
    }
}
