package io.watchmatch;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The home screen. It runs in the ":home" process so a crash here can't take down the main-process safety net
 * (Watchdog), and hosts one HomeView plus a 1x1 surface where the Watchme+ row previews play.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            HomeMode.requestRevertHome(this);
            TextView notice = new TextView(this);
            notice.setText(Ui.tr(
                "O Início do {app} teve um problema.\nVoltando para o Início original da TV…",
                "{app}'s home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            notice.setTextColor(Color.WHITE);
            notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            notice.setGravity(Gravity.CENTER);
            notice.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(notice);
            return;
        }
        Ui.init(this);
        Provider.applyIntent(this, getIntent());
        home = new HomeView(this);
        FrameLayout root = new FrameLayout(this);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(1, 1));
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        home.setPreview(new CinemaPreview(this, surface, home::onPreview));
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            // Changed in another screen while away (language, city...): rebuild with the new values.
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();
            return;
        }
        if (home != null) home.resume();
    }

    @Override
    protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Provider.applyIntent(this, intent);
        if (home != null) home.reset(true);
    }

    @Override
    protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (RemoteKeys.onKey(this, e)) return true;
        if (home != null && home.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }
}
