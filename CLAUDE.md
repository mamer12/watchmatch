# WatchMatch: notes for Claude

WatchMatch is a home screen and screensaver for Google TV and Android TV that replaces the stock ones, plus a
settings screen that switches between WatchMatch and the TV's original home. It is a plain Android app in Java,
built without Gradle by `build.sh`. The website is in `docs/` (static HTML, CSS and one small `app.js`, in English
and Arabic: change both pages together).

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say. On the TV they need *USB debugging* on
   (Settings, System, About: tap the build number 7 times; then Developer options).
2. **Tools:** JDK 17+, Android command-line tools (build-tools 34+, platform android-34) and adb. Check what's there
   (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17 and the Android tools from the package manager, then the same `sdkmanager` line. Windows: use WSL.
3. **Settings:** the first `./build.sh` copies `config.example.properties` to `config.properties`. Fill in `latitude`,
   `longitude`, `city`, `tmdb_region`, `teams`/`leagues` if they follow football, and `language`, `currency`,
   `temperature`, `show_crypto` to match. `app_name` and `spark_name` rename the launcher and the smart search.
4. **Keys are theirs and secret:** `tmdb_key` (free, themoviedb.org, Settings, API), `youtube_key` and the optional
   `youtube_client_id` / `youtube_client_secret`. Ask them to paste these into `config.properties` themselves. Don't
   ask for them in the chat, never print them, never commit them. If one is pasted in the chat anyway, put it only in
   `config.properties`. Without a TMDB key everything works except the picks and the screensaver photos.
5. **Build:** `./build.sh` must end with `Built WatchMatch.apk`. Keep `watchmatch.keystore`: updates must be signed
   with the same key. (`build-watchmeplus.sh` makes a second APK of the same code under another package id.)
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r WatchMatch.apk`.
7. **TCL only:** run
   `adb shell "appops set io.watchmatch AUTO_START allow || appops set io.watchmatch APP_AUTO_START allow"`
   (some firmware names it `APP_AUTO_START`; this tries both). TCL's `TclAppBoot` blocks background jobs by default,
   so the switch and the watchdog would never run; the log shows `forbid bind service ... ModeJob`.
8. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo and demo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL the remote's
   Netflix, YouTube and Prime buttons keep working through a small accessibility service (*Remote Buttons* picks what
   each opens); *Original* turns it off.
9. **They switch on the TV:** *WatchMatch*, *Home Screen*, *WatchMatch*. The app uses the TV's own ADB on 127.0.0.1
   with its own key, so the TV asks once more to allow debugging. Never switch modes from the computer.
10. **Wi-Fi safety:** if the switch stops before changing anything and asks them to forget the Wi-Fi network and join
    again, it found a network saved by an app it would turn off. Have them do it in Settings (Network & Internet).
    Saved networks out of range don't show there: find them in `adb shell dumpsys wifi` (blocks start with `ID:`;
    look for `cname=com.google.android.tungsten.setupwraith`) and, with their OK, run
    `adb shell cmd wifi forget-network <id>`.
11. **Check:** `adb logcat -s WatchMatch` shows what the switch did.

## Adapting it to another TV brand

Read first, change after. These commands only read:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: the brand's system pieces: picture, sound, inputs and HDMI-CEC, tuner and live TV, remote and
  Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeAppsRules`: `LIVE_TV_APPS` (the brand's live TV app), `HIDDEN` (apps to hide from All Apps) and the default
  shelf apps.
- Build, install, have them switch on the TV, read `adb logcat -s WatchMatch`, then have them switch back to
  *Original* once and confirm everything returned.

TVs that don't run Google TV or Android TV: suggest a Google TV streamer on HDMI and set WatchMatch up on that.

## If it was uninstalled while switched on

The apps it turned off stay off. Bring them back with `adb shell pm enable <package>` (the `DEBLOAT` list and the
stock home), then `adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 28 (Android 9), targetSdk 33. No Gradle, no libraries: only the Android SDK.
- The home screen is drawn by hand on one Canvas (`HomeView`); glass and sky are AGSL shaders on Android 13+ with a
  fallback for 9 to 12. Keep 60 fps: no allocations in `onDraw`, no work on the UI thread. Weak TVs get Lite mode
  (simpler effects, same layout), picked automatically and overridable in Settings, Performance.
- `HomeMode` runs each switch as a background `ModeJob` in the main process; the UI lives in the `:home` process.
  `Watchdog` goes back to the stock home after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("pt", "en")` or `Ui.tr(pt, en, es)`. Spanish is in `Spanish.java` and Arabic in
  `Arabic.java`, both keyed by the English text. Add every new string in all of them. Arabic mirrors the whole
  interface (`Ui.rtl`, `Ui.mx`), so use those helpers for positions instead of assuming left to right.
- Product names are never typed into copy: write `{app}`, `{catalog}` and `{spark}` and `Ui` fills them from config.
- Settings come from `config.properties` through the generated `out/gen/io/watchmatch/Config.java` (never edit it).
  What the person changes on the TV (`Prefs`, the Settings screens) wins over those first values.
- Sync (Watchme+: progress, My List, ratings, a few settings; Supabase over REST) is off unless `supabase_url` and
  `supabase_anon_key` are in `config.properties` (public keys; never a service-role key). `Sync` runs in the process that
  owns Watchme+'s data (`:cinema`, or the main process of WatchMePlus.apk); `Progress` and `CinemaPrefs` call
  `Sync.nudge/flush` where they save, and `SyncEngine`, `SyncMap`, `SyncRules`, `SyncClient` are pure (checked on a JVM;
  keep `SyncMap` in step with the web app's `src/sync/mapping.ts`). `SyncLiveCheck` runs against the real backend.
- Profiles (Watchme+, max 6): `ProfileRules` and `PinRules` are pure (the model, Kids forcing, who enters which PIN, key
  prefixes); `Profiles` keeps the list, the active id and the PIN lockouts in `profiles.json`, readable by every process.
  Per-profile data lives in `ProfilePrefs` files (main keeps the names used before profiles, so nothing moved); the
  TV's own settings (quality, previews) stay in main's settings file. Sync scopes its work to a profile with
  `ProfilePrefs.with`, and its keys are `<id>~<key>` and `pref:<id>:<name>`. Screens: `ProfileScreens`, `Avatars`,
  `OnScreenKeyboard` (shared with Search). Keep `ProfileRules` in step with the web app.
- Accounts (Watchme+, email and password on top of Sync): `AccountRules` is pure (start plan, create/sign-in decisions, merge
  choice, error codes, sign-out wipe set, the 5-second delete gate, `redact`); `Account` runs each action on the sync thread
  (`Sync.submit`) and keeps `account.json` (email, guest chosen), readable by every process; `AccountScreens` and
  `AccountText` are the screens and words; `OnScreenKeyboard` has a full mode for email and passwords. A password lives
  only in a form's buffer and in its one request: never stored, never logged (`AccountRulesCheck` greps for it). An account's
  user id is shared by all its devices, so `SyncClient.userId()` is null for it (else devices would skip each other's rows).
  `AccountLiveCheck` runs against the real project and skips what it can't do.
- Self-checks are small Java programs in `app/test/io/`; read their headers to run them.
- After any change, `./build.sh` must end with `Built WatchMatch.apk`.

## Always

- Never commit `config.properties`, `*.keystore` or any key.
- Never turn off an app that owns a saved Wi-Fi network (`wifiOwners` in `HomeMode`): Android deletes its networks.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
