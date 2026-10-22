package io.watchmatch;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The catalog (the Shabakaty app) keeps your history to itself, so this accessibility service, which sees only the catalog's
 * screens (res/xml/catalog_helper.xml), notes each title page opened there (its name and year) for the home screen's
 * "Continue watching", with its poster and number from the catalog's public catalog. It never presses anything.
 * It runs in the home screen's process (:home).
 */
public class CatalogHelper extends AccessibilityService {
    private static final String ID = Endpoints.PKG + ":id/";
    private static String search() { return Endpoints.searchBase() + "AdvancedSearch?level=0&page=0"; }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();


    private String lastNoted = "";

    static boolean installed(Context c) {
        return HomeApps.launchIntent(c.getPackageManager(), Endpoints.PKG) != null;
    }

    @Override public void onInterrupt() {}

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null || !Endpoints.PKG.contentEquals(event.getPackageName())) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) note(root);
    }

    private static AccessibilityNodeInfo first(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(ID + id);
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    // MARK: - Noting what's opened

    /** On a title page (it has the Play / Download buttons): its name and year, once per title. */
    private void note(AccessibilityNodeInfo root) {
        if (first(root, "details_overview_actions") == null) return;
        AccessibilityNodeInfo name = first(root, "title_text"), year = first(root, "categoryNameTV");
        if (name == null || name.getText() == null) return;
        final String title = name.getText().toString().trim();
        final String y = year == null || year.getText() == null ? "" : year.getText().toString().trim();
        if (title.isEmpty() || (title + '|' + y).equals(lastNoted)) return;
        lastNoted = title + '|' + y;
        final Context app = getApplicationContext();
        IO.execute(() -> {
            Catalog.Entry e = lookUp(title, y);
            if (e != null) Watched.add(app, e);
        });
    }

    /** The title in the catalog's public catalog (poster, both names), movies first, then series. */
    private static Catalog.Entry lookUp(String title, String year) {
        for (String type : new String[] {"movies", "series"}) {
            try {
                String q = URLEncoder.encode(title, "UTF-8");
                JSONArray items = new JSONArray(get(search() + "&type=" + type + "&videoTitle=" + q + "&staffTitle=" + q));
                JSONObject best = null;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    boolean same = title.equalsIgnoreCase(o.optString("ar_title").trim()) || title.equalsIgnoreCase(o.optString("en_title").trim());
                    if (same && (year.isEmpty() || year.equals(o.optString("year")))) { best = o; break; }
                    if (same && best == null) best = o;
                }
                if (best == null) continue;
                Catalog.Entry e = new Catalog.Entry();
                String en = best.optString("en_title").trim();
                e.title = en.isEmpty() ? title : en;
                e.sub = WatchMe.NAME;
                String img = best.optString("imgMediumThumbObjUrl", "");
                e.image = e.still = img.isEmpty() ? best.optString("imgObjUrl", "") : img;
                e.pkg = Endpoints.PKG;
                e.query = title;
                e.id = best.optString("nb", "");
                if (e.id.isEmpty()) e.id = null;
                return e.still.isEmpty() ? null : e;
            } catch (Exception ex) {
                AppLog.w("catalog look-up: " + ex);
            }
        }
        return null;
    }

    private static String get(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
