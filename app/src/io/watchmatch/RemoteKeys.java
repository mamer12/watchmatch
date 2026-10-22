package io.watchmatch;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/** Opens the app chosen for a TCL remote's app button, since the stock home screen that normally does it is off. */
final class RemoteKeys {
    private RemoteKeys() {}

    static boolean supported() {
        return "TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND);
    }

    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        int src = e.getSource();
        // The YouTube button's code (BUTTON_3) is also a game controller button.
        if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int code = e.getKeyCode();
        if (!owns(code)) return false;
        String pkg = Prefs.keyApp(code);
        if (pkg == null || pkg.isEmpty()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent launch = HomeApps.launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + code + " -> " + pkg + (launch == null ? " (not installed)" : ""));
            if (launch != null) c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    private static boolean owns(int code) {
        for (int k : Prefs.KEYS) if (k == code) return true;
        return false;
    }
}
