package io.watchmatch;

import android.app.Application;

/** Gives Prefs a context in every process, and starts TrustedTime in each (and, in the two screens' processes, Provider). */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        TrustedTime.start(this);
        String process = Application.getProcessName();
        if (process != null && process.indexOf(':') > 0) Provider.start(this);     // :home and :cinema read the catalog
        Sync.start(this);     // only where Watchme+'s data lives, and only in a build with a sync backend
    }
}
