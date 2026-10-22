package io.watchmatch;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the home screen offers to open: the app shelf, the recent apps, the drawer, All Apps, Live TV and the HDMI
 * inputs, how each one is opened, and each one's artwork (a 16:9 card, and for known streaming apps a coloured logo
 * with no background).
 */
final class HomeApps {
    private HomeApps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, SETTINGS = 4, ANDROID_SETTINGS = 5, INPUTS = 7;
    static final int TILE_W = 384, TILE_H = 216;
    static final int MAX_SHELF = HomeAppsRules.MAX_SHELF;
    static final String[] LIVE_TV_APPS = HomeAppsRules.LIVE_TV_APPS;

    private static final String MEDIATEK = "com.mediatek.wwtv.tvcenter";
    private static final int LOGO_VERSION = 2, LOGO_MAX = 512, LOGO_SAMPLE_TARGET = 640;
    private static final long RECENT_WINDOW = 14L * 86_400_000L;
    private static final int[] BASE_COLORS = {0xFF2B2E3A, 0xFF17191F};

    static final class Item {
        int kind;
        String key, label, pkg, inputId, number;
        String symbol;
        Bitmap tile;
        Bitmap logo;
        Bitmap logoDark;
        float logoInk;
        float logoScale = 1;
        boolean connected = true;
    }

    // Per-process artwork caches
    private static final Map<String, Bitmap> TILES = new HashMap<>();
    private static final Map<String, Bitmap> SYMBOL_TILES = new HashMap<>();
    private static final Map<String, Bitmap> BUNDLED = new HashMap<>();
    private static final Map<String, Bitmap> LIFTED = new HashMap<>();

    // Launching and lists

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    static List<String> shelfPackages(final Context c) {
        final PackageManager pm = c.getPackageManager();
        return HomeAppsRules.shelfPackages(Prefs.shelf(), new Predicate<String>() {
            @Override public boolean test(String p) {
                return launchIntent(pm, p) != null;
            }
        });
    }

    static List<Item> shelf(Context c, boolean extract) {
        PackageManager pm = c.getPackageManager();
        List<Item> out = new ArrayList<>();
        for (String pkg : shelfPackages(c)) {
            if (out.size() >= MAX_SHELF) break;
            Intent launch = launchIntent(pm, pkg);
            if (launch == null) continue;
            String[] k = HomeAppsRules.known(pkg);
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            if (k != null) {
                it.key = k[0];
                it.label = k[1];
                it.tile = appTile(c, k[0], pkg, Integer.parseInt(k[3], 16) | 0xFF000000);
                it.logo = shelfLogo(c, k[0], pkg, k[4], extract);
                if (it.logo != null) it.logoInk = ink(it.logo);
                it.logoDark = bundledLogo(c, k[0] + "-dark");
                it.logoScale = Float.parseFloat(k[5]);
            } else {
                it.key = pkg;
                it.label = labelOf(pm, launch, pkg);
                it.tile = appTile(c, null, pkg, 0);
            }
            out.add(it);
        }
        return out;
    }

    private static String labelOf(PackageManager pm, Intent launch, String pkg) {
        ResolveInfo r = pm.resolveActivity(launch, 0);
        CharSequence l = r == null ? null : r.loadLabel(pm);
        return l == null || l.length() == 0 ? pkg : l.toString();
    }

    /** {package, name}: the known apps that are installed, then every other TV app by name. */
    static List<String[]> shelfCandidates(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] k : HomeAppsRules.KNOWN) {
            if (launchIntent(pm, k[2]) != null && seen.add(k[2])) out.add(new String[] {k[2], k[1]});
        }
        List<String[]> rest = new ArrayList<>();
        for (ResolveInfo r : leanbackApps(pm)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || HomeAppsRules.HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            rest.add(new String[] {pkg, r.loadLabel(pm).toString()});
        }
        sortByName(rest);
        out.addAll(rest);
        return out;
    }

    private static void sortByName(List<String[]> list) {
        Collections.sort(list, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                return a[1].compareToIgnoreCase(b[1]);
            }
        });
    }

    private static List<ResolveInfo> leanbackApps(PackageManager pm) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        return pm.queryIntentActivities(i, 0);
    }

    static List<Item> recent(Context c, int max) {
        final PackageManager pm = c.getPackageManager();
        List<Item> out = new ArrayList<>();
        Map<String, Long> last = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = TrustedTime.now();
            List<UsageStats> stats = usm == null ? null
                    : usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - RECENT_WINDOW, now);
            if (stats == null) return out;
            for (UsageStats s : stats) {
                if (s.getTotalTimeInForeground() <= 0) continue;
                Long old = last.get(s.getPackageName());
                if (old == null || s.getLastTimeUsed() > old) last.put(s.getPackageName(), s.getLastTimeUsed());
            }
        } catch (RuntimeException e) {
            return out;
        }
        Predicate<String> leanback = new Predicate<String>() {
            @Override public boolean test(String p) {
                return pm.getLeanbackLaunchIntentForPackage(p) != null;
            }
        };
        // More than asked for are checked: a package whose label can't be read is skipped.
        for (String pkg : HomeAppsRules.recentOrder(last, c.getPackageName(), leanback, Integer.MAX_VALUE)) {
            if (out.size() >= max) break;
            String[] k = HomeAppsRules.known(pkg);
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            if (k != null) {
                it.key = k[0];
                it.label = k[1];
                it.tile = appTile(c, k[0], pkg, Integer.parseInt(k[3], 16) | 0xFF000000);
            } else {
                try {
                    it.label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                it.key = pkg;
                it.tile = appTile(c, null, pkg, 0);
            }
            out.add(it);
        }
        return out;
    }

    static List<Item> shortcuts(Context c) {
        List<Item> out = new ArrayList<>();
        if (liveTv(c) != null) out.add(liveTvItem(c));
        out.addAll(inputs(c, true));
        out.add(action(ALL_APPS, "all", Ui.tr("Todos os Apps", "All Apps"), "apps"));
        return out;
    }

    static List<Item> allInputs(Context c) {
        List<Item> out = new ArrayList<>();
        if (liveTv(c) != null) out.add(liveTvItem(c));
        out.addAll(inputs(c, false));
        return out;
    }

    private static Item liveTvItem(Context c) {
        Item it = action(LIVE_TV, "live", Ui.tr("TV ao Vivo", "Live TV"), "tv");
        it.tile = symbolTile(c, "live", "tv");
        return it;
    }

    static List<Item> drawerShortcuts() {
        List<Item> out = new ArrayList<>();
        out.add(action(SETTINGS, "settings", Ui.tr("Ajustes do Início", "Home Settings"), "settings"));
        out.add(action(ANDROID_SETTINGS, "tv-settings", Ui.tr("Ajustes da TV", "TV Settings"), "tv_settings"));
        return out;
    }

    static Item settings() {
        return action(SETTINGS, "settings", Ui.tr("Ajustes", "Settings"), "settings");
    }

    static Item action(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    static Item app(String pkg, String label) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        return it;
    }

    /** Every TV app that isn't on the shelf's list of known ones, by name, then this app's own settings. */
    static List<Item> others(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : leanbackApps(pm)) {
            String pkg = r.activityInfo.packageName;
            if (HomeAppsRules.known(pkg) != null || java.util.Arrays.asList(LIVE_TV_APPS).contains(pkg)
                    || pkg.equals(c.getPackageName()) || HomeAppsRules.HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            found.add(new String[] {pkg, r.loadLabel(pm).toString()});
        }
        sortByName(found);
        List<Item> out = new ArrayList<>();
        for (String[] f : found) {
            Item it = app(f[0], f[1]);
            it.tile = appTile(c, null, f[0], 0);
            out.add(it);
        }
        Item own = action(SETTINGS, "watchmatch-settings", Config.APP_NAME, "brand");
        own.tile = symbolTile(c, "watchmatch-settings", "brand");
        out.add(own);
        return out;
    }

    // HDMI and Live TV

    private static List<Item> inputs(Context c, boolean onlyConnected) {
        List<Item> out = new ArrayList<>();
        TvInputManager tv = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tv == null) return out;
        try {
            List<TvInputInfo> ports = new ArrayList<>();
            Map<String, String> cec = new HashMap<>();
            for (TvInputInfo info : tv.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                String parent = info.getParentId();
                if (parent == null) ports.add(info);
                else cec.put(parent, String.valueOf(info.loadLabel(c)).trim());
            }
            Collections.sort(ports, new Comparator<TvInputInfo>() {
                @Override public int compare(TvInputInfo a, TvInputInfo b) {
                    return a.getId().compareTo(b.getId());
                }
            });
            for (int i = 0; i < ports.size(); i++) {
                Item it = port(c, tv, ports.get(i), i, cec, onlyConnected);
                if (it != null) out.add(it);
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return a.number.compareTo(b.number);
            }
        });
        return out;
    }

    private static Item port(Context c, TvInputManager tv, TvInputInfo info, int index, Map<String, String> cec,
                             boolean onlyConnected) {
        String id = info.getId();
        String own = String.valueOf(info.loadLabel(c));
        String number = String.valueOf(index + 1);
        for (char ch : own.toCharArray()) {
            if (Character.isDigit(ch)) {
                number = String.valueOf(ch);
                break;
            }
        }
        boolean connected;
        try {
            connected = tv.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED;
        } catch (RuntimeException e) {
            connected = false;
        }
        if (onlyConnected && !connected) return null;
        String custom = "";
        try {
            CharSequence l = info.loadCustomLabel(c);
            if (l != null) custom = l.toString().trim();
        } catch (RuntimeException ignored) {
            // No custom label.
        }
        String device = cec.get(id);
        String label = !custom.isEmpty() ? custom : device != null && !device.isEmpty() && connected ? device
                : "HDMI " + number;
        Item it = action(INPUT, "hdmi" + number, label,
                connected || !custom.isEmpty() ? HomeAppsRules.deviceSymbol(label) : "hdmi");
        it.inputId = id;
        it.number = number;
        it.connected = connected;
        it.tile = symbolTile(c, it.key + ":" + it.symbol, it.symbol);
        return it;
    }

    static String deviceSymbol(String name) {
        return HomeAppsRules.deviceSymbol(name);
    }

    static Intent liveTv(Context c) {
        PackageManager pm = c.getPackageManager();
        String tuner = tunerInput(c);
        Intent mediaTek = launchIntent(pm, MEDIATEK);
        if (tuner != null && mediaTek != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(tuner));
            i.setComponent(mediaTek.getComponent());
            return i;
        }
        if (tuner != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (i.resolveActivity(pm) != null) return i;
        }
        if (mediaTek != null) return mediaTek;
        for (String p : LIVE_TV_APPS) {
            Intent i = launchIntent(pm, p);
            if (i != null) return i;
        }
        return null;
    }

    static String tunerInput(Context c) {
        TvInputManager tv = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tv == null) return null;
        String system = null, fallback = null;
        try {
            for (TvInputInfo info : tv.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                String id = info.getId();
                if (fallback == null) fallback = id;
                ApplicationInfo ai = info.getServiceInfo().applicationInfo;
                if (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (id.contains("/HW")) return id;
                    if (system == null) system = id;
                }
            }
        } catch (RuntimeException ignored) {
            // Whatever was found so far stands.
        }
        return system != null ? system : fallback;
    }

    // Opening

    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {
        Intent intent;
        switch (it.kind) {
            case APP:
                if (HomeAppsRules.LIBRARY.equals(it.pkg) || HomeAppsRules.WATCHME_STANDALONE.equals(it.pkg)) {
                    intent = CinemaActivity.launch(a);
                } else {
                    intent = launchIntent(a.getPackageManager(), it.pkg);
                }
                if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                break;
            case LIVE_TV:
                intent = liveTv(a);
                break;
            case INPUT:
                intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
                break;
            case SETTINGS:
                intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class));
                break;
            case ANDROID_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            default:
                return false;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ActivityOptions o = ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1));
            a.startActivity(intent, o.toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.label + ": " + e.getMessage());
            return false;
        }
    }

    // Artwork: cards

    private static Bitmap appTile(Context c, String key, String pkg, int brand) {
        String id = key != null ? key : pkg;
        synchronized (TILES) {
            Bitmap t = TILES.get(id);
            if (t != null) return t;
            Bitmap logo = key == null ? null : bundledLogo(c, key);
            if (logo != null) t = logoTile(logo, brand);
            if (t == null) t = bannerTile(c, pkg);
            if (t == null) t = iconTile(c, pkg);
            if (t != null) t.setHasMipMap(true);
            TILES.put(id, t);
            return t;
        }
    }

    private static Bitmap blank() {
        return Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
    }

    private static void darkBase(Canvas cv) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, TILE_H, BASE_COLORS[0], BASE_COLORS[1], Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, TILE_W, TILE_H, p);
    }

    private static Bitmap logoTile(Bitmap logo, int brand) {
        Bitmap t = blank();
        Canvas cv = new Canvas(t);
        darkBase(cv);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        int glowColor = (brand & 0x00FFFFFF) | (Math.round(0.38f * 255) << 24);
        glow.setShader(new RadialGradient(TILE_W / 2f, TILE_H * 1.15f, TILE_W * 0.75f, glowColor,
                brand & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, TILE_W, TILE_H, glow);
        boolean wide = logo.getWidth() > 1.35f * logo.getHeight();
        float boxW = TILE_W * (wide ? 0.62f : 0.40f), boxH = TILE_H * (wide ? 0.42f : 0.56f);
        float s = Math.min(boxW / logo.getWidth(), boxH / logo.getHeight());
        float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(logo, null, new RectF((TILE_W - lw) / 2, (TILE_H - lh) / 2, (TILE_W + lw) / 2, (TILE_H + lh) / 2), f);
        return t;
    }

    private static Bitmap bannerTile(Context c, String pkg) {
        Drawable banner = banner(c, pkg);
        if (banner == null) return null;
        Bitmap t = blank();
        Canvas cv = new Canvas(t);
        cv.drawColor(0xFF15161C);
        banner.setBounds(0, 0, TILE_W, TILE_H);
        banner.draw(cv);
        return t;
    }

    private static Drawable banner(Context c, String pkg) {
        if (HomeAppsRules.LIBRARY.equals(pkg)) return c.getDrawable(R.drawable.cinema_banner);
        PackageManager pm = c.getPackageManager();
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                if (ai.getBannerResource() != 0) {
                    Drawable d = pm.getResourcesForApplication(pkg)
                            .getDrawableForDensity(ai.getBannerResource(), DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) return d;
                }
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private static Bitmap iconTile(Context c, String pkg) {
        Drawable icon;
        try {
            icon = c.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        Bitmap t = blank();
        Canvas cv = new Canvas(t);
        darkBase(cv);
        if (icon instanceof AdaptiveIconDrawable) {
            float side = 0.56f * TILE_H, left = (TILE_W - side) / 2, top = (TILE_H - side) / 2, r = side * 0.2237f;
            Path clip = new Path();
            clip.addRoundRect(new RectF(left, top, left + side, top + side), r, r, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
            int pad = Math.round(side / 4);
            for (Drawable layer : new Drawable[] {ad.getBackground(), ad.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(Math.round(left) - pad, Math.round(top) - pad, Math.round(left + side) + pad,
                        Math.round(top + side) + pad);
                layer.draw(cv);
            }
            cv.restore();
        } else {
            int side = Math.round(0.46f * TILE_H), left = (TILE_W - side) / 2, top = (TILE_H - side) / 2;
            icon.setBounds(left, top, left + side, top + side);
            icon.draw(cv);
        }
        return t;
    }

    /** A card with only a white symbol on the dark base. */
    private static Bitmap symbolTile(Context c, String id, String symbol) {
        synchronized (SYMBOL_TILES) {
            Bitmap t = SYMBOL_TILES.get(id);
            if (t != null) return t;
            t = blank();
            Canvas cv = new Canvas(t);
            darkBase(cv);
            Bitmap s = Symbols.get(c, symbol);
            if (s != null) {
                // The symbol fits a circle of radius 0.36 of the card's height.
                float points = 0.72f * TILE_H * 144f / Math.max(s.getWidth(), s.getHeight());
                Symbols.draw(c, cv, symbol, TILE_W / 2f, TILE_H / 2f, points, 0xF2FFFFFF);
            }
            t.setHasMipMap(true);
            SYMBOL_TILES.put(id, t);
            return t;
        }
    }

    // Artwork: logos

    /** The bundled logos/<name>.png, trimmed to its ink and at most 512 px on a side; null when there is none. */
    private static Bitmap bundledLogo(Context c, String name) {
        synchronized (BUNDLED) {
            if (BUNDLED.containsKey(name)) return BUNDLED.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("logos/" + name + ".png")) {
                byte[] data = readAll(in);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample >= LOGO_SAMPLE_TARGET) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (raw != null) b = finishLogo(raw);
            } catch (IOException | RuntimeException ignored) {
                // No such logo: the card shows the banner or the icon.
            }
            if (b != null) b.setHasMipMap(true);
            BUNDLED.put(name, b);
            return b;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Bitmap finishLogo(Bitmap raw) {
        int w = raw.getWidth(), h = raw.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] px = new int[w * h];
        raw.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) <= 10) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) return null;
        Bitmap cut = Bitmap.createBitmap(raw, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        int big = Math.max(cut.getWidth(), cut.getHeight());
        if (big <= LOGO_MAX) return cut;
        float s = (float) LOGO_MAX / big;
        return Bitmap.createScaledBitmap(cut, Math.max(1, Math.round(cut.getWidth() * s)),
                Math.max(1, Math.round(cut.getHeight() * s)), true);
    }

    /** The coloured logo of a known app: bundled, else on disk, else lifted from its banner (only when `extract`). */
    private static Bitmap shelfLogo(Context c, String key, String pkg, String mode, boolean extract) {
        Bitmap bundled = bundledLogo(c, key);
        if (bundled != null) return bundled;
        File dir = new File(c.getCacheDir(), "logos");
        long updated = 0;
        try {
            updated = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Treated as never updated.
        }
        String id = key + "-" + updated + "-v" + LOGO_VERSION;
        synchronized (LIFTED) {
            if (LIFTED.containsKey(id)) return LIFTED.get(id);
            File png = new File(dir, id + ".png"), none = new File(dir, id + ".png.none");
            Bitmap b = null;
            if (png.exists()) {
                b = BitmapFactory.decodeFile(png.getPath());
            } else if (none.exists()) {
                b = null;
            } else if (!extract) {
                return null;
            } else {
                b = liftAndSave(c, pkg, mode, dir, png, none);
            }
            if (b != null) b.setHasMipMap(true);
            LIFTED.put(id, b);
            return b;
        }
    }

    private static Bitmap liftAndSave(Context c, String pkg, String mode, File dir, File png, File none) {
        dir.mkdirs();
        Bitmap result = null;
        Drawable banner = banner(c, pkg);
        if (banner != null) {
            final int w = 768, h = 432;
            Bitmap canvasBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(canvasBmp);
            cv.drawColor(0xFF15161C);
            banner.setBounds(0, 0, w, h);
            banner.draw(cv);
            int[] px = new int[w * h];
            canvasBmp.getPixels(px, 0, w, 0, 0, w, h);
            canvasBmp.recycle();
            BannerLift.Result r = BannerLift.lift(px, w, h, mode.isEmpty() ? null : mode);
            if (r != null) result = Bitmap.createBitmap(r.pixels, r.width, r.height, Bitmap.Config.ARGB_8888);
        }
        try {
            if (result == null) {
                none.createNewFile();
            } else {
                try (FileOutputStream out = new FileOutputStream(png)) {
                    result.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
        } catch (IOException e) {
            AppLog.w("logo not cached: " + e.getMessage());
        }
        return result;
    }

    private static float ink(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return HomeAppsRules.ink(px, w, h);
    }
}
