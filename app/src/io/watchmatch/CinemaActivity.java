package io.watchmatch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import java.util.List;

/**
 * The catalog with a Netflix-style screen (CinemaView) and our own player (PlayerActivity), in its own process
 * (:cinema): a problem here never touches the home screen or its safety net. The first launch starts clean and asks
 * what you like (CinemaWelcome).
 */
public class CinemaActivity extends Activity implements CinemaView.Host, CinemaPreview.Listener {
    /** Optional extras: a the catalog title id (its page opens) or words to search for. */
    static final String EXTRA_ID = "io.watchmatch.library.ID", EXTRA_QUERY = "io.watchmatch.library.QUERY";
    /** With EXTRA_ID: straight into the player at this many ms (the home screen's preview of it was playing). */
    static final String EXTRA_PLAY_AT = "io.watchmatch.library.PLAY_AT";
    /** True: opens on its Settings page (WatchMatch's Settings > {catalog}). */
    static final String EXTRA_SETTINGS = "io.watchmatch.library.SETTINGS";
    /** True: opens Settings on the "Reset {catalog} data" confirmation (WatchMatch's Settings > {catalog}). */
    static final String EXTRA_RESET = "io.watchmatch.library.RESET";
    /** True: opens "Who's watching?" (WatchMatch's Settings, Profile). */
    static final String EXTRA_PROFILES = "io.watchmatch.library.PROFILES";
    private static final int WELCOME = 1;
    /** The standalone app (WatchMePlus.apk), which WatchMatch opens when it's installed. */
    static final String STANDALONE = "io.watchmeplus";

    /**
     * Opens Watchme+: the standalone app when it's installed, else this copy inside WatchMatch. WatchMatch's home
     * screen calls this (with EXTRA_ID / EXTRA_QUERY added as wanted).
     */
    static Intent launch(android.content.Context c) {
        Intent standalone = new Intent().setComponent(new android.content.ComponentName(STANDALONE, CinemaActivity.class.getName()));
        boolean installed = standalone.resolveActivity(c.getPackageManager()) != null;
        return (installed ? standalone : new Intent(c, CinemaActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private CinemaView view;
    private CinemaPreview preview;
    /** Another catalog server is in use: the pages load again from it. */
    private final Provider.Listener providerListener = () -> {
        if (view != null) view.catalogChanged();
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        Provider.applyIntent(this, getIntent());
        Profiles.setAdultVerified(false);      // an adult's PIN counts for this visit only
        FrameLayout root = new FrameLayout(this);
        // The preview's video sits under the drawn page, which leaves it uncovered while it plays.
        SurfaceView video = new SurfaceView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        preview = new CinemaPreview(this, video, this);
        view = new CinemaView(this, this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
        view.requestFocus();
        // A title or a search from the home screen goes to the profile that screen showed (the active one); anything
        // else asks who is watching when there are several profiles (or a PIN for the last one).
        Intent in = getIntent();
        boolean direct = in != null && (in.hasExtra(EXTRA_ID) || in.hasExtra(EXTRA_QUERY) || in.hasExtra(EXTRA_SETTINGS)
                || in.hasExtra(EXTRA_RESET));
        boolean plain = !direct && !(in != null && in.getBooleanExtra(EXTRA_PROFILES, false));
        if (plain && view.startAccount()) return;      // the welcome: sign in, create an account or continue as a guest
        if (plain && view.startProfiles()) return;
        profileReady();
        handle(getIntent());
    }

    /** The account screens at the start are over: the same start as before, from the profiles on. */
    @Override public void accountReady() {
        if (view.startProfiles()) return;
        profileReady();
        handle(getIntent());
    }

    /**
     * The profile to show is settled (the start, or the profile screens ended): its first launch clears whatever the
     * catalog had kept and asks the welcome questions (CinemaWelcome).
     */
    @Override public void profileReady() {
        if (CinemaPrefs.welcomed(this)) return;
        CinemaPrefs.resetAll(this);
        Library.arabicFirst = false;
        view.reloadForProfile();
        redoWelcome();
    }

    /** Reset {catalog} data: everything it kept goes, then the welcome questions start over. */
    @Override public void resetWatchme() {
        preview.stop();
        WatchmeReset.run(this);
        redoWelcome();
    }

    /** The welcome questions (first launch, and Settings > Redo my preferences). */
    @Override public void redoWelcome() {
        startActivityForResult(new Intent(this, CinemaWelcome.class), WELCOME);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == WELCOME) view.catalogChanged();     // the answers shape the rows: load them again
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Provider.applyIntent(this, intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_PROFILES, false)) {
            intent.removeExtra(EXTRA_PROFILES);
            view.openProfiles();
            return;
        }
        String id = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        final String query = intent == null ? null : intent.getStringExtra(EXTRA_QUERY);
        final long playAt = intent == null ? -1 : intent.getLongExtra(EXTRA_PLAY_AT, -1);
        if (intent != null && intent.getBooleanExtra(EXTRA_RESET, false)) {
            intent.removeExtra(EXTRA_RESET);
            view.askReset();
            return;
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_SETTINGS, false)) {
            intent.removeExtra(EXTRA_SETTINGS);
            view.openSettings();
            return;
        }
        if (id != null && !id.trim().isEmpty()) {
            Library.get(this).title(id.trim(), t -> {
                if (t != null && playAt >= 0) playFrom(t, playAt);
                else if (t != null) view.openTitle(t);
                else if (query != null) view.openSearch(query);
            });
        } else if (query != null && !query.trim().isEmpty()) {
            view.openSearch(query);
        }
    }

    /** The player at `at` ms: a movie by itself, an episode with its series around it (Next episode works). */
    private void playFrom(final Library.Title t, final long at) {
        if (t.rootId.isEmpty()) {
            startActivity(PlayerActivity.intent(this, java.util.Collections.singletonList(t), 0, null, false)
                    .putExtra(PlayerActivity.EXTRA_AT, at));
            return;
        }
        final Library library = Library.get(this);
        library.title(t.rootId, series -> library.episodes(t.rootId, list -> {
            int index = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(t.id)) index = i;
            if (index < 0) {
                list = java.util.Collections.singletonList(t);
                index = 0;
            }
            startActivity(PlayerActivity.intent(this, list, index, series, false).putExtra(PlayerActivity.EXTRA_AT, at));
        }));
    }

    // MARK: - CinemaView.Host

    @Override public void play(List<Library.Title> list, int index, Library.Title root, boolean fromStart) {
        if (list.isEmpty()) return;
        // The preview of this very video was playing: the player goes on from the same second.
        Library.Title previewed = preview.playing();
        long at = previewed != null && !fromStart ? preview.position() : -1;
        int start = index;
        for (int i = 0; previewed != null && i < list.size(); i++) if (list.get(i).id.equals(previewed.id)) start = i;
        if (previewed == null || !list.get(start).id.equals(previewed.id)) at = -1;
        preview.stop();
        startActivity(PlayerActivity.intent(this, list, at >= 0 ? start : index, root, fromStart).putExtra(PlayerActivity.EXTRA_AT, at));
    }

    @Override public void preview(Library.Title t, android.graphics.RectF where) {
        preview.want(t, where);
    }

    @Override public void preview(Library.Title t, android.graphics.RectF where, long delayMs) {
        preview.want(t, where, delayMs);
    }

    @Override public String previewSubtitle() {
        return preview.subtitleNow();
    }

    @Override public void onPreview(boolean showing) {
        view.onPreview(showing);
    }

    @Override public void exit() {
        finish();
    }

    // MARK: - Lifecycle

    @Override protected void onResume() {
        super.onResume();
        Provider.addListener(providerListener);
        Sync.setListener(changed -> view.syncChanged(changed));
        Sync.resumed(this);
        view.resume();
    }

    @Override protected void onPause() {
        Provider.removeListener(providerListener);
        Sync.setListener(null);
        Sync.paused();
        preview.stop();
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
