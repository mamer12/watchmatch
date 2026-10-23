package io.watchmatch;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Switches the TV between "original" (the factory home screen, screensaver and apps) and "free" (this app is the home
 * screen and the screensaver, a list of promo apps is turned off). All of it goes through the TV's own adbd (Adb) in
 * background jobs; the state is one JSON file shared by the main process and the :home process.
 * The way back must always work: "original" re-enables everything recorded, and Watchdog's revert gives the home back.
 */
final class HomeMode {
    private HomeMode() {}

    static final String PKG = "io.watchmatch";
    static final String HOME_ACTIVITY = PKG + "/" + PKG + ".HomeActivity";
    static final String SETTINGS_ACTIVITY = PKG + "/" + PKG + ".SettingsActivity";
    static final String DREAM = PKG + "/" + PKG + (Build.VERSION.SDK_INT == 30 ? ".ScreensaverAlone" : ".Screensaver");
    static final String FREE = "free", ORIGINAL = "original";
    static final String TV_LAUNCHER = "com.google.android.tvlauncher";
    static final String TV_RECOMMENDATIONS = "com.google.android.tvrecommendations";
    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity";
    static final String GOOGLE_SETUP = "com.google.android.tungsten.setupwraith";

    /** Promo, demo, store-mode and vendor apps turned off in free mode (when installed). */
    static final String[] DEBLOAT = {
        "com.google.android.play.games", "com.google.android.youtube.tvmusic",
        "com.tcl.magiconnectfree", "com.tcl.miracast",          // TCL screen mirroring
        "com.tcl.waterfall.overseas", "com.tcl.channelplus",    // TCL Channel and its Live TV
        "com.tcl.messagebox", "com.tcl.usercenter",             // TCL messages and account
        "com.tcl.exhibit",                                      // store demo
        "com.tcl.esticker", "com.tcl.hotelmenu", "com.tcl.repairguide",
        "com.tcl.ocean.instructions",                           // manual
        "com.tcl.logkit",
    };

    /** Turned off only when disable_assistant is set in config.properties. */
    static final String[] ASSISTANT = {"com.google.android.katniss", "com.android.hotwordenrollment.okgoogle"};

    /** System pieces that must never be turned off: checked before every disable. */
    static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
        "com.tcl.ttvs", "com.tvos", "com.tcl.systemserver", "com.tcl.systemui.plugin", "com.tcl.tv",
        "com.tcl.tvinput", "com.tcl.hearaid", "com.dolby.android.audio.service", "com.tcl.globalkeyoverlay",
        "com.tcl.autopair", "com.tcl.UpdatePeripheral", "com.tcl.dashboard", "com.tcl.suspension",
        "com.tcl.providers.config", "com.tcl.gamebar", "com.tcl.airplay2", "com.mirakulo.astrotv.service",
        "com.tcl.initsetup", "com.tcl.useragreement", "com.tcl.factory.view", "com.tcl.guard", "com.tcl.t_solo",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.apps.mediashell", "com.google.android.tv.remote.service",
        "com.google.android.inputmethod.latin", "com.google.android.webview", "com.android.tv.settings",
        "com.android.systemui", "android", PKG,
        "org.droidtv", "org.droidtv.ambisvc", "org.droidtv.ui.strings", "org.droidtv.tv.packageinfo",
        "org.droidtv.icp", "org.droidtv.gel", "org.droidtv.eum",
        "com.mediatek.tv.service", "com.mediatek.tvinput", "com.mediatek.network", "com.mediatek.wwtv.tvcenter",
        "com.mediatek.wwtv.setupwizard", "com.mediatek.tv.factory", "mediatek.factorymenu.ui",
        "com.tpv.xmic.ots.cms.client"));

    private static final String REMOTE_SERVICE = PKG + "/" + PKG + ".RemoteKeyService";
    private static final String CATALOG_HELPER = PKG + "/" + PKG + ".CatalogHelper";
    private static final String GO_HOME = "am start -a android.intent.action.MAIN -c android.intent.category.HOME";
    private static final String HOME_QUERY = "cmd package query-activities --brief --user 0"
            + " -a android.intent.action.MAIN -c android.intent.category.HOME";
    private static final long MEMORY_DELAY_MS = 120000;
    private static final int MAX_NOTICES = 20;

    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();
    private static final Pattern HOLDER = Pattern.compile("[\\w.]+(;[\\w.]+)*");
    private static final Pattern SERVICE_ENTRY = Pattern.compile("[\\w.]+/[\\w.$]+");
    private static final Pattern WIFI_OWNER = Pattern.compile("cname=([\\w.]+)");
    private static final Pattern TOTAL_TIME = Pattern.compile("TotalTime: (\\d+)");
    private static final Pattern MEM_AVAILABLE = Pattern.compile("MemAvailable:\\s+(\\d+) kB");
    private static final Pattern TOTAL_PSS = Pattern.compile("TOTAL PSS:\\s+(\\d+)");

    // ---- Requests (any process, never block)

    static void request(Context c, String mode) {
        ModeJob.schedule(c, ModeJob.APPLY, FREE.equals(mode) ? FREE : ORIGINAL, 0);
    }

    static void requestRevertHome(Context c) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0);
    }

    static void bootCheck(Context c) {
        ensureRemoteService(c, 30000);
        ModeJob.schedule(c, ModeJob.VERIFY, null, 45000);
    }

    static void verify(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0);
    }

    static void ensureRemoteService(Context c, long delayMs) {
        if (!neededServices(c).isEmpty()) ModeJob.schedule(c, ModeJob.REMOTE, null, delayMs);
    }

    // ---- State file

    private static File stateFile(Context c) { return new File(c.getFilesDir(), "home-state.json"); }

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            try (FileInputStream in = new FileInputStream(stateFile(c))) {
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
                return new JSONObject(new String(all.toByteArray(), StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return defaultState();
            }
        }
    }

    private static JSONObject defaultState() {
        JSONObject s = new JSONObject();
        try { s.put("mode", ORIGINAL); } catch (JSONException ignored) { }
        return s;
    }

    static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File target = stateFile(c), tmp = new File(target.getPath() + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(s.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.getFD().sync();
                }
                if (!tmp.renameTo(target)) AppLog.w("could not save the mode state");
            } catch (IOException e) {
                AppLog.w("could not save the mode state: " + e.getMessage());
            }
        }
    }

    /** Read-modify-write of one field, so the other process's changes aren't lost. */
    private static void put(Context c, String key, Object value) {
        synchronized (FILE_LOCK) {
            JSONObject s = state(c);
            try { s.put(key, value); } catch (JSONException ignored) { }
            save(c, s);
        }
    }

    private static void busy(Context c, String text) {
        synchronized (FILE_LOCK) {
            JSONObject s = state(c);
            try { s.put("busy", text); s.put("error", ""); } catch (JSONException ignored) { }
            save(c, s);
        }
    }

    private static void done(Context c, String error) {
        synchronized (FILE_LOCK) {
            JSONObject s = state(c);
            s.remove("busy");
            try { s.put("error", error == null ? "" : error); } catch (JSONException ignored) { }
            save(c, s);
        }
        if (error != null) {
            AppLog.w(error);
            notice(c, error);
        }
    }

    static void notice(Context c, String text) {
        AppLog.w("notice: " + text);
        synchronized (FILE_LOCK) {
            JSONObject s = state(c);
            JSONArray fresh = new JSONArray();
            try {
                fresh.put(new JSONObject().put("at", TrustedTime.now()).put("text", text));
                JSONArray old = s.optJSONArray("notices");
                for (int i = 0; old != null && i < old.length() && fresh.length() < MAX_NOTICES; i++) fresh.put(old.get(i));
                s.put("notices", fresh);
            } catch (JSONException ignored) { }
            save(c, s);
        }
    }

    static List<String> stringList(JSONObject s, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = s == null ? null : s.optJSONArray(key);
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static void putList(Context c, String key, Set<String> values) {
        put(c, key, new JSONArray(new ArrayList<>(new TreeSet<>(values))));
    }

    private static void putMetric(Context c, String mode, String field, long value) {
        synchronized (FILE_LOCK) {
            JSONObject s = state(c);
            try {
                JSONObject all = s.optJSONObject("metrics");
                if (all == null) all = new JSONObject();
                JSONObject m = all.optJSONObject(mode);
                if (m == null) m = new JSONObject();
                m.put(field, value);
                m.put("at", TrustedTime.now());
                all.put(mode, m);
                s.put("metrics", all);
            } catch (JSONException ignored) { }
            save(c, s);
        }
    }

    // ---- Connecting

    private static Adb connect(final Context c) throws IOException {
        busy(c, Ui.tr("Conectando…", "Connecting…"));
        Adb adb = Adb.connect(c, 120000, () -> busy(c, Ui.tr(
                "Aceite o aviso “Permitir depuração” na TV (marque “Sempre permitir”).",
                "Accept “Allow USB debugging?” on the TV (tick “Always allow”).")));
        if (!state(c).optBoolean("adbAuthorized")) put(c, "adbAuthorized", true);
        return adb;
    }

    // ---- Shell queries

    private static Set<String> packages(Adb adb, String flags) throws IOException {
        return parsePackages(adb.shell(flags.isEmpty() ? "pm list packages" : "pm list packages " + flags));
    }

    static Set<String> parsePackages(String out) {
        Set<String> set = new HashSet<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) set.add(line.substring(8).trim());
        }
        return set;
    }

    private static String homeHolder(Adb adb) throws IOException {
        String holder = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim();
        if (HOLDER.matcher(holder).matches()) return holder;
        String[] lines = adb.shell("cmd package resolve-activity --brief --user 0"
                + " -a android.intent.action.MAIN -c android.intent.category.HOME").trim().split("\n");
        String last = lines[lines.length - 1].trim();
        if (last.contains("/") && !last.startsWith("android/")) return packageOf(last);
        return "";
    }

    /** The TV's own home activity, or "" when it's unknown or already ours. */
    private static String currentHome(Adb adb) throws IOException {
        String holder = homeHolder(adb);
        if (holder.isEmpty() || holder.contains(PKG)) return "";
        for (String line : adb.shell(HOME_QUERY).split("\n")) {
            line = line.trim();
            if (line.startsWith(holder + "/")) return line;
        }
        return "";
    }

    private static String realHome(String home) {
        // Some TCL Android 14 TVs hold the HOME role with Google TV's recovery activity while launcherx answers Home.
        return home.startsWith(GOOGLE_SETUP + "/") ? GOOGLE_HOME : home;
    }

    private static boolean setupIsHome(Adb adb) throws IOException {
        return adb.shell(HOME_QUERY).contains(GOOGLE_SETUP + "/");
    }

    static Set<String> wifiOwners(Adb adb) throws IOException {
        Set<String> owners = new HashSet<>();
        Matcher m = WIFI_OWNER.matcher(adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'"));
        while (m.find()) owners.add(m.group(1));
        return owners;
    }

    private static String defaultDream(Adb adb) throws IOException {
        String d = adb.shell("settings get secure screensaver_default_component").trim();
        return d.contains("/") && !d.startsWith(PKG + "/") ? d : "";
    }

    static String packageOf(String component) {
        if (component == null || component.isEmpty()) return null;
        int slash = component.indexOf('/');
        return slash < 0 ? component : component.substring(0, slash);
    }

    /** What free mode turns off, in order: the TV's home, its recommendations, setup, the screensaver, the lists. */
    static List<String> targets(String home, String dream, boolean setupHome, boolean withAssistant) {
        List<String> list = new ArrayList<>();
        String homePkg = packageOf(home), dreamPkg = packageOf(dream);
        if (homePkg != null && !homePkg.equals(PKG)) list.add(homePkg);
        if (TV_LAUNCHER.equals(homePkg)) list.add(TV_RECOMMENDATIONS);
        if (setupHome) list.add(GOOGLE_SETUP);
        if (dreamPkg != null && !dreamPkg.equals(PKG) && !dreamPkg.equals(homePkg)) list.add(dreamPkg);
        list.addAll(Arrays.asList(DEBLOAT));
        if (withAssistant) list.addAll(Arrays.asList(ASSISTANT));
        return list;
    }

    private static List<String> targets(String home, String dream, boolean setupHome) {
        return targets(home, dream, setupHome, Config.DISABLE_ASSISTANT);
    }

    private static void allowAutoStart(Adb adb, Set<String> present) {
        for (String pkg : new String[] {PKG, CinemaActivity.STANDALONE}) {
            if (!present.contains(pkg)) continue;
            try {
                adb.shell("appops set " + pkg + " APP_AUTO_START allow || appops set " + pkg + " AUTO_START allow");
                adb.shell("appops set " + pkg + " APP_ASSOC_START allow");
            } catch (IOException e) {
                AppLog.w("could not allow auto-start for " + pkg + ": " + e.getMessage());
            }
        }
    }

    private static void goHome(Adb adb) {
        try { adb.shell(GO_HOME); } catch (IOException e) { AppLog.w("could not open the home screen: " + e.getMessage()); }
    }

    // ---- Switching

    static void applyNow(Context c, String mode) {
        synchronized (RUN) {
            if (FREE.equals(mode)) applyFree(c); else applyOriginal(c);
        }
    }

    private static final String WIFI_REFUSAL_PT = "a configuração inicial da TV salvou o Wi-Fi, e desligá-la apagaria essas redes. Em Configurações → Rede e Internet, esqueça a rede e conecte de novo (as fora de alcance não aparecem lá: o Claude Code apaga pelo computador) e tente outra vez.";
    private static final String WIFI_REFUSAL_EN = "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. In Settings → Network & Internet, forget the network and connect again (networks out of range don't show there: Claude Code can remove them from your computer), then try once more.";
    private static final String WIFI_REFUSAL_ES = "la configuración inicial del TV guardó el Wi-Fi, y desactivarla borraría esas redes. En Ajustes → Red e Internet, olvida la red y vuelve a conectarte (las fuera de alcance no aparecen ahí: Claude Code las borra desde la computadora) y vuelve a intentarlo.";
    private static final String WIFI_REFUSAL_AR = "الإعداد الأول للتلفاز حفظ شبكة Wi-Fi، وإيقافه سيمحو هذه الشبكات. من الإعدادات ← الشبكة والإنترنت، انسَ الشبكة واتصل بها من جديد (الشبكات خارج النطاق لا تظهر هناك: يمكن لـ Claude Code حذفها من الكمبيوتر)، ثم حاول مرة أخرى.";

    private static void applyFree(Context c) {
        try (Adb adb = connect(c)) {
            JSONObject st = state(c);
            String home = st.optString("originalHome", "");
            if (home.isEmpty() || home.startsWith(PKG + "/")) home = realHome(currentHome(adb));
            String dream = st.optString("originalDream", "");
            if (dream.isEmpty()) dream = defaultDream(adb);
            put(c, "originalHome", home);
            put(c, "originalDream", dream);

            Set<String> owners = wifiOwners(adb);
            boolean setupHome = setupIsHome(adb);
            String homePkg = packageOf(home);
            if ((setupHome && owners.contains(GOOGLE_SETUP)) || (homePkg != null && owners.contains(homePkg))) {
                throw new IOException(Ui.tr(WIFI_REFUSAL_PT, WIFI_REFUSAL_EN, WIFI_REFUSAL_ES, WIFI_REFUSAL_AR));
            }

            JSONObject metrics = state(c).optJSONObject("metrics");
            if ((metrics == null || !metrics.has(ORIGINAL)) && !home.isEmpty()) {
                busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
                measureQuietly(c, adb, ORIGINAL, true);
            }

            busy(c, Ui.tr("Trocando o Início e a tela de descanso…", "Switching the home screen and screensaver…"));
            String set = adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
            if (!set.contains("Success")) {
                throw new IOException(Ui.tr("o Android não aceitou o novo Início", "Android didn't accept the new home screen")
                        + " (" + set.trim() + ")");
            }
            adb.shell("settings put secure screensaver_components " + DREAM);
            adb.shell("appops set " + PKG + " GET_USAGE_STATS allow");        // the Recent apps row
            adb.shell("appops set " + PKG + " SYSTEM_ALERT_WINDOW allow");    // Match Centre overlays

            List<String> targets = targets(home, dream, setupHome);
            Set<String> disabled = new TreeSet<>(stringList(state(c), "disabled"));
            Set<String> present = packages(adb, "");
            allowAutoStart(adb, present);
            for (int i = 0; i < targets.size(); i++) {
                String pkg = targets.get(i);
                busy(c, Ui.tr("Desligando apps (" + (i + 1) + "/" + targets.size() + ")…",
                        "Turning off apps (" + (i + 1) + "/" + targets.size() + ")…"));
                if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)) continue;
                String out = adb.shell("pm disable-user --user 0 " + pkg);
                if (out.contains("disabled-user")) disabled.add(pkg);
                else AppLog.w("could not turn off " + pkg + ": " + out.trim());
            }

            Watchdog.clear(c);
            synchronized (FILE_LOCK) {
                JSONObject s = state(c);
                s.put("mode", FREE);
                s.put("since", TrustedTime.now());
                s.put("homeReverted", false);
                s.put("disabled", new JSONArray(new ArrayList<>(disabled)));
                s.put("setupHome", setupHome);
                save(c, s);
            }
            try { remoteService(c, adb, true); } catch (IOException e) { AppLog.w("accessibility services: " + e.getMessage()); }
            notice(c, Ui.tr("{app} ligado: Início e tela de descanso novos, " + disabled.size() + " apps desligados.",
                    "{app} is on: new home screen and screensaver, " + disabled.size() + " apps turned off."));
            busy(c, Ui.tr("Medindo o {app}…", "Measuring {app}…"));
            measureQuietly(c, adb, FREE, false);
            done(c, null);
            goHome(adb);
            ModeJob.schedule(c, ModeJob.MEMORY, FREE, MEMORY_DELAY_MS);
        } catch (IOException | JSONException e) {
            done(c, Ui.tr("Não deu para ligar o {app}: ", "Couldn't turn on {app}: ") + e.getMessage());
        }
    }

    private static void applyOriginal(Context c) {
        try (Adb adb = connect(c)) {
            busy(c, Ui.tr("Religando os apps…", "Turning the apps back on…"));
            JSONObject st = state(c);
            Set<String> toEnable = new TreeSet<>(stringList(st, "disabled"));
            Set<String> recorded = new HashSet<>(toEnable);
            Set<String> mine = new HashSet<>(Arrays.asList(DEBLOAT));
            mine.addAll(Arrays.asList(ASSISTANT));
            for (String pkg : targets(st.optString("originalHome", ""), st.optString("originalDream", ""), true)) {
                if (!mine.contains(pkg) || recorded.contains(pkg)) toEnable.add(pkg);
            }
            Set<String> present = packages(adb, "");
            List<String> failed = new ArrayList<>();
            int i = 0;
            for (String pkg : toEnable) {
                i++;
                busy(c, Ui.tr("Religando apps (" + i + "/" + toEnable.size() + ")…",
                        "Turning apps back on (" + i + "/" + toEnable.size() + ")…"));
                if (!present.contains(pkg)) continue;
                if (!adb.shell("pm enable " + pkg).contains("new state: enabled")) failed.add(pkg);
            }
            busy(c, Ui.tr("Devolvendo o Início original…", "Restoring the original home screen…"));
            boolean homeOk = restoreHome(adb, st.optString("originalHome", ""));
            adb.shell("settings delete secure screensaver_components");
            try { remoteService(c, adb, false); } catch (IOException e) { AppLog.w("accessibility services: " + e.getMessage()); }
            synchronized (FILE_LOCK) {
                JSONObject s = state(c);
                s.put("mode", ORIGINAL);
                s.put("since", TrustedTime.now());
                s.put("homeReverted", false);
                s.put("disabled", new JSONArray(failed));
                save(c, s);
            }
            if (failed.isEmpty() && homeOk) {
                notice(c, Ui.tr("Modo original: Início, tela de descanso e apps de fábrica de volta.",
                        "Original mode: the factory home screen, screensaver and apps are back."));
            } else {
                List<String> left = new ArrayList<>(failed);
                if (!homeOk) left.add(Ui.tr("o Início original", "the original home screen"));
                notice(c, Ui.tr("Modo original, mas não consegui religar: ", "Original mode, but I couldn't turn back on: ")
                        + join(left, ", ") + ".");
            }
            busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
            measureQuietly(c, adb, ORIGINAL, false);
            done(c, null);
            goHome(adb);
            ModeJob.schedule(c, ModeJob.MEMORY, ORIGINAL, MEMORY_DELAY_MS);
        } catch (IOException | JSONException e) {
            done(c, Ui.tr("Não deu para voltar ao original: ", "Couldn't go back to the original: ") + e.getMessage());
        }
    }

    /** Gives the TV's own home screen the HOME role again; false when nothing worked. */
    private static boolean restoreHome(Adb adb, String home) throws IOException {
        if (home.isEmpty() && adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) home = GOOGLE_HOME;
        if (!home.isEmpty()) {
            adb.shell("pm enable " + packageOf(home));
            if (adb.shell("cmd package set-home-activity " + home).contains("Success")) return true;
        }
        adb.shell("cmd role remove-role-holder --user 0 android.app.role.HOME " + PKG);
        return !homeHolder(adb).contains(PKG);
    }

    static void revertHomeNow(Context app) {
        synchronized (RUN) {
            try (Adb adb = Adb.connect(app, 10000, null)) {
                String home = state(app).optString("originalHome", "");
                adb.shell("pm enable " + GOOGLE_SETUP);   // Google TV's recovery home must exist to fall back on
                restoreHome(adb, home);
                Set<String> disabled = new TreeSet<>(stringList(state(app), "disabled"));
                String homePkg = packageOf(home);
                if (homePkg != null) disabled.remove(homePkg);
                disabled.remove(GOOGLE_SETUP);
                putList(app, "disabled", disabled);
                put(app, "homeReverted", true);
                notice(app, Ui.tr("O Início do {app} caiu 3 vezes seguidas; voltei para o Início original.",
                        "{app}'s home screen crashed 3 times in a row; I switched back to the original one."));
                goHome(adb);
            } catch (IOException e) {
                AppLog.w("could not give the home screen back: " + e.getMessage());
            }
        }
    }

    // ---- Accessibility services

    private static List<String> neededServices(Context c) {
        List<String> list = new ArrayList<>();
        if (RemoteKeys.supported()) list.add(REMOTE_SERVICE);
        if (CatalogHelper.installed(c)) list.add(CATALOG_HELPER);
        return list;
    }

    /** The new enabled_accessibility_services list (others first, ours last), or null if an entry looks unsafe. */
    static List<String> mergeServices(String current, List<String> ours) {
        List<String> merged = new ArrayList<>();
        String text = current.trim();
        if (!text.equals("null")) {
            for (String e : text.split(":")) {
                if (e.isEmpty() || e.equals(REMOTE_SERVICE) || e.equals(CATALOG_HELPER)) continue;
                if (!SERVICE_ENTRY.matcher(e).matches()) return null;   // it goes into a shell command
                merged.add(e);
            }
        }
        merged.addAll(ours);
        return merged;
    }

    private static void remoteService(Context c, Adb adb, boolean on) throws IOException {
        List<String> ours = on ? neededServices(c) : Collections.<String>emptyList();
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        List<String> merged = mergeServices(current, ours);
        if (merged == null) {
            AppLog.w("accessibility services left as they are: unexpected entry");
            return;
        }
        String joined = join(merged, ":");
        if (joined.equals(current.equals("null") ? "" : current)) return;
        if (on && !hasOurs(current)) {
            put(c, "accessibilityWasOff", !adb.shell("settings get secure accessibility_enabled").trim().equals("1"));
        }
        if (merged.isEmpty()) adb.shell("settings delete secure enabled_accessibility_services");
        else adb.shell("settings put secure enabled_accessibility_services '" + joined + "'");
        if (!ours.isEmpty()) adb.shell("settings put secure accessibility_enabled 1");
        else if (merged.isEmpty() && state(c).optBoolean("accessibilityWasOff")) {
            adb.shell("settings put secure accessibility_enabled 0");
        }
        AppLog.w("our accessibility services: " + (ours.isEmpty() ? "off" : join(ours, ":")));
    }

    private static boolean hasOurs(String current) {
        for (String e : current.split(":")) if (e.startsWith(PKG + "/")) return true;
        return false;
    }

    static void ensureRemoteServiceNow(Context app) {
        if (neededServices(app).isEmpty() || !FREE.equals(state(app).optString("mode", ORIGINAL))) return;
        try (Adb adb = Adb.connect(app, 10000, null)) {
            remoteService(app, adb, true);
        } catch (IOException e) {
            AppLog.w("remote buttons service: " + e.getMessage());
        }
    }

    // ---- Verifying

    static void verifyNow(Context app) {
        synchronized (RUN) {
            JSONObject st = state(app);
            String stored = st.optString("fingerprint", "");
            boolean updated = !stored.isEmpty() && !stored.equals(Build.FINGERPRINT);
            put(app, "fingerprint", Build.FINGERPRINT);
            if (!FREE.equals(st.optString("mode", ORIGINAL))) return;
            try (Adb adb = Adb.connect(app, 10000, null)) {
                repair(app, adb, state(app), updated);
            } catch (IOException e) {
                notice(app, Ui.tr("Não consegui conferir o {app}: ", "Couldn't check {app}: ") + e.getMessage());
            }
        }
    }

    private static void repair(Context app, Adb adb, JSONObject st, boolean updated) throws IOException {
        Set<String> present = packages(adb, "");
        allowAutoStart(adb, present);
        Set<String> disabledNow = packages(adb, "-d");
        Set<String> owners = wifiOwners(adb);
        boolean reverted = st.optBoolean("homeReverted");
        Set<String> disabled = new TreeSet<>(stringList(st, "disabled"));

        String home = st.optString("originalHome", "");
        Set<String> wanted = new TreeSet<>(disabled);
        wanted.addAll(targets(home, st.optString("originalDream", ""), st.optBoolean("setupHome", true)));
        if (reverted) {
            if (packageOf(home) != null) wanted.remove(packageOf(home));
            wanted.remove(GOOGLE_SETUP);
        }
        if (!Config.DISABLE_ASSISTANT) {
            for (String pkg : ASSISTANT) {
                wanted.remove(pkg);
                if (disabled.remove(pkg) && disabledNow.contains(pkg)) adb.shell("pm enable " + pkg);   // the TV's voice recognizer
            }
        }

        List<String> fixed = new ArrayList<>();
        for (String pkg : wanted) {
            if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)) continue;
            if (disabledNow.contains(pkg)) {
                disabled.add(pkg);
            } else if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                disabled.add(pkg);
                fixed.add(pkg);
            }
        }
        if (!reverted && !homeHolder(adb).contains(PKG)) {
            adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
            fixed.add(Ui.tr("o Início", "the home screen"));
        }
        if (!adb.shell("settings get secure screensaver_components").contains(DREAM)) {
            adb.shell("settings put secure screensaver_components " + DREAM);
            fixed.add(Ui.tr("a tela de descanso", "the screensaver"));
        }
        putList(app, "disabled", disabled);
        if (!fixed.isEmpty()) {
            notice(app, (updated ? Ui.tr("A atualização da TV religou ", "The TV update turned back on ")
                    : Ui.tr("Desliguei o que faltava: ", "Turned off what was missing: ")) + join(fixed, ", ") + ".");
        } else if (updated) {
            notice(app, Ui.tr("A TV foi atualizada; o {app} continua igual.", "The TV was updated; {app} is still on."));
        }
    }

    // ---- Measurements

    private static void measureQuietly(Context c, Adb adb, String mode, boolean both) {
        try {
            measureHomeTime(c, adb, mode);
            if (both) measureMemory(c, adb, mode);
        } catch (IOException e) {
            AppLog.w("measurement failed: " + e.getMessage());
        }
    }

    static long median(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static void measureHomeTime(Context c, Adb adb, String mode) throws IOException {
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
            SystemClock.sleep(500);
            Matcher m = TOTAL_TIME.matcher(adb.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"));
            if (m.find()) samples.add(Long.parseLong(m.group(1)));
            SystemClock.sleep(600);
        }
        adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
        if (!samples.isEmpty()) putMetric(c, mode, "homeMs", median(samples));
    }

    private static void measureMemory(Context c, Adb adb, String mode) throws IOException {
        Matcher avail = MEM_AVAILABLE.matcher(adb.shell("cat /proc/meminfo"));
        if (avail.find()) putMetric(c, mode, "memAvailableMB", Long.parseLong(avail.group(1)) / 1024);
        List<String> processes = new ArrayList<>();
        if (FREE.equals(mode)) {
            processes.add(PKG + ":home");
        } else {
            JSONObject st = state(c);
            String pkg = packageOf(st.optString("originalHome", ""));
            if (pkg != null) { processes.add(pkg); processes.add(pkg + ":coreservices"); }
        }
        long kb = 0;
        for (String p : processes) {
            Matcher m = TOTAL_PSS.matcher(adb.shell("dumpsys meminfo " + p));
            if (m.find()) kb += Long.parseLong(m.group(1));
        }
        if (kb > 0) putMetric(c, mode, "homePssMB", kb / 1024);
    }

    static void measureMemoryNow(Context c, String mode) {
        synchronized (RUN) {
            if (!mode.equals(state(c).optString("mode", ORIGINAL))) return;
            try (Adb adb = Adb.connect(c, 10000, null)) {
                measureMemory(c, adb, mode);
            } catch (IOException e) {
                AppLog.w("memory measurement failed: " + e.getMessage());
            }
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) { if (sb.length() > 0) sb.append(sep); sb.append(p); }
        return sb.toString();
    }
}
