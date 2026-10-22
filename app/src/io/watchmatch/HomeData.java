package io.watchmatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The crypto prices and the weather behind the home screen, the screensaver and the settings preview. Cached in
 * SharedPreferences so a screen opens with numbers; fetched only when a visible screen asks. No API keys.
 */
final class HomeData {
    static final String[] COINS = {"BTC", "ETH", "SOL", "USDT"};
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};

    interface Listener { void onData(); }

    /** One forecast, as the parser left it. */
    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static final long PRICE_TTL = 5 * 60_000L, WEATHER_TTL = 30 * 60_000L;
    private static final int TIMEOUT_MS = 6000, MAX_HOURS = 6;
    private static final String TIME_FORMAT = "yyyy-MM-dd'T'HH:mm";

    private static HomeData instance;

    final double[] prices = new double[4], changes = new double[4];
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final Context app;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    private HomeData(Context c) {
        app = c;
        Arrays.fill(prices, Double.NaN);
        Arrays.fill(changes, Double.NaN);
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            if (Prefs.quotes()) {
                String body = cache.getString("prices", null);
                if (body != null && parsePrices(body)) pricesAt = cache.getLong("pricesAt", 0);
            }
            String body = cache.getString("weather", null);
            if (body != null && place().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(body);
                weatherAt = cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {
            // A damaged cache is only a reason to fetch again.
        }
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
    }

    void refreshIfStale() {
        long now = TrustedTime.now();
        if (coinCount() > 0 && stale(pricesAt, PRICE_TTL, now) && !fetchingPrices) {
            fetchingPrices = true;
            net.execute(this::fetchPrices);
        }
        if (stale(weatherAt, WEATHER_TTL, now) && !fetchingWeather) {
            fetchingWeather = true;
            net.execute(this::fetchWeather);
        }
    }

    private static boolean stale(long at, long ttl, long now) {
        return now - at > ttl || now < at;
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onData();
        });
    }

    // Prices

    private static String quote() {
        String c = currency();
        return c.equals("USD") ? "USDT" : c;
    }

    private static String currency() {
        return Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    }

    private void fetchPrices() {
        try {
            int[] on = active();
            StringBuilder symbols = new StringBuilder();
            for (int i : on) {
                if (symbols.length() > 0) symbols.append(',');
                symbols.append("%22").append(COINS[i]).append(quote()).append("%22");
            }
            String body = http("https://api.binance.com/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D");
            if (parsePrices(body)) {
                cache.edit().putString("prices", body).putLong("pricesAt", TrustedTime.now()).apply();
                pricesAt = TrustedTime.now();
            } else {
                pricesAt = 0; // a cache from another currency: fetch again
            }
            notifyListeners();
        } catch (Exception e) {
            AppLog.w("prices: " + e.getMessage());
        } finally {
            fetchingPrices = false;
        }
    }

    /** Stores the quotes in `body`; false when none of them is one of ours. */
    private boolean parsePrices(String body) throws JSONException {
        JSONArray list = new JSONArray(body);
        boolean any = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.optString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote())) continue;
                double last = o.has("lastPrice") ? o.optDouble("lastPrice") : o.optDouble("price");
                double open = o.optDouble("openPrice");
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
                any = true;
            }
        }
        return any;
    }

    // Weather

    private String place() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private void fetchWeather() {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude="
                    + Prefs.longitude() + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
                    + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";
            String body = http(url);
            Weather w = parseWeather(body);
            weather = w;
            long now = TrustedTime.now();
            weatherAt = now;
            cache.edit().putString("weather", body).putLong("weatherAt", now).putString("weatherPlace", place()).apply();
            notifyListeners();
        } catch (Exception e) {
            AppLog.w("weather: " + e.getMessage());
        } finally {
            fetchingWeather = false;
        }
    }

    private static Weather parseWeather(String body) throws JSONException {
        JSONObject root = new JSONObject(body);
        TimeZone zone = root.has("timezone") ? TimeZone.getTimeZone(root.optString("timezone")) : TimeZone.getDefault();
        SimpleDateFormat parser = new SimpleDateFormat(TIME_FORMAT, Locale.US);
        parser.setTimeZone(zone);
        Weather w = new Weather();
        JSONObject cur = root.optJSONObject("current");
        if (cur != null) {
            w.temp = cur.optDouble("temperature_2m", Double.NaN);
            w.code = cur.optInt("weather_code", -1);
            w.isDay = cur.optInt("is_day", 1) == 1;
        }
        JSONObject daily = root.optJSONObject("daily");
        if (daily != null) {
            w.max = first(daily.optJSONArray("temperature_2m_max"));
            w.min = first(daily.optJSONArray("temperature_2m_min"));
            w.sunrise = time(parser, daily.optJSONArray("sunrise"), 0);
            w.sunset = time(parser, daily.optJSONArray("sunset"), 0);
        }
        JSONObject hourly = root.optJSONObject("hourly");
        if (hourly != null) addHours(w, hourly, parser);
        return w;
    }

    private static void addHours(Weather w, JSONObject hourly, SimpleDateFormat parser) {
        JSONArray times = hourly.optJSONArray("time"), temps = hourly.optJSONArray("temperature_2m");
        JSONArray codes = hourly.optJSONArray("weather_code"), days = hourly.optJSONArray("is_day");
        if (times == null || temps == null || codes == null) return;
        long now = TrustedTime.now();
        for (int i = 0; i < times.length() && w.hours.size() < MAX_HOURS; i++) {
            long at = time(parser, times, i);
            if (at <= now) continue;
            w.hours.add(at);
            w.hourTemp.add(temps.optDouble(i, Double.NaN));
            w.hourCode.add(codes.optInt(i, -1));
            w.hourDay.add(days == null || days.optInt(i, 1) == 1);
        }
    }

    private static double first(JSONArray a) {
        return a == null ? Double.NaN : a.optDouble(0, Double.NaN);
    }

    private static long time(SimpleDateFormat parser, JSONArray a, int i) {
        if (a == null) return 0;
        try {
            return parser.parse(a.optString(i)).getTime();
        } catch (ParseException | NullPointerException e) {
            return 0;
        }
    }

    // HTTP

    private static String userAgent() {
        StringBuilder b = new StringBuilder();
        for (char ch : Config.APP_NAME.toCharArray()) if (ch >= 0x21 && ch <= 0x7E) b.append(ch);
        return b.length() == 0 ? "WatchMatch" : b.toString();
    }

    private static String http(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", userAgent());
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    // Day and night

    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        Weather w = weather;
        long day = Shaders.timeOfDay(now, 0);
        return day < Shaders.timeOfDay(w.sunrise, 6 * 3_600_000L) || day >= Shaders.timeOfDay(w.sunset, 18 * 3_600_000L);
    }

    // Which coins, and how to write things

    private static int[] activeCoins;
    private static int activeVersion = -1;

    static synchronized int[] active() {
        if (activeCoins == null || activeVersion != Prefs.version) {
            boolean brl = currency().equals("BRL");
            int[] tmp = new int[COINS.length];
            int n = 0;
            for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || brl)) tmp[n++] = i;
            activeCoins = Arrays.copyOf(tmp, n);
            activeVersion = Prefs.version;
        }
        return activeCoins;
    }

    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    static String coinName(int i) {
        return i == 3 ? Ui.tr("Dólar", "Dollar") : COIN_NAMES[i];
    }

    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10000) return Ui.digits(String.format(Ui.locale(), "%.1fk", v / 1000));
        if (v >= 1000) return Ui.digits(String.format(Ui.locale(), "%.2fk", v / 1000));
        return Ui.digits(String.format(Ui.locale(), "%.2f", v));
    }

    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10000) return Ui.digits(String.format(Ui.locale(), "%.1fk", v / 1000));
        if (v >= 1000) return Ui.digits(String.format(Ui.locale(), "%.2fk", v / 1000));
        if (v >= 100) return Ui.digits(String.format(Ui.locale(), "%.0f", v));
        return Ui.digits(String.format(Ui.locale(), "%.2f", v));
    }

    static String brl(double v) {
        String cur = currency();
        final String symbol;
        switch (cur) {
            case "USD": symbol = "$"; break;
            case "BRL": symbol = "R$ "; break;
            case "EUR": symbol = "€"; break;
            case "GBP": symbol = "£"; break;
            case "JPY": symbol = "¥"; break;
            default: symbol = cur + " ";
        }
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> v >= 1000 ? symbol + String.format(Ui.locale(), "%,.0f", v)
                : symbol + String.format(Ui.locale(), "%.2f", v));
    }

    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        String unit = Prefs.temperature();
        boolean f = unit.equals("F") || (unit.equals("auto") && "US".equals(Locale.getDefault().getCountry()));
        long shown = Math.round(f ? v * 9 / 5 + 32 : v);
        return Ui.isolate(Ui.digits(shown + "°"));
    }

    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> (v >= 0 ? "+" : "−")
                + String.format(Ui.locale(), "%.1f%%", Math.abs(v)));
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> {
            java.util.Date d = new java.util.Date(at);
            if (Ui.en) return new SimpleDateFormat("h a", Locale.US).format(d);
            if (Ui.es || Ui.ar) return new SimpleDateFormat("HH:mm", Ui.locale()).format(d);
            return new SimpleDateFormat("HH'h'", Ui.locale()).format(d);
        });
    }

    /** "5 min ago" once `at` is older than two refresh periods; empty while it is fresh or unknown. */
    static String staleness(long at, long everyMs) {
        if (at == 0) return "";
        long age = TrustedTime.now() - at;
        if (age < 2 * everyMs) return "";
        long minutes = age / 60_000;
        boolean mins = minutes < 60;
        long n = mins ? minutes : minutes / 60;
        String s;
        if (Ui.ar) s = "منذ " + n + (mins ? " د" : " س");
        else if (Ui.es) s = "hace " + n + (mins ? " min" : " h");
        else if (Ui.en) s = n + (mins ? " min ago" : " h ago");
        else s = "há " + n + (mins ? " min" : " h");
        return Ui.digits(s);
    }

    static String condition(int code, boolean day) {
        if (code < 0) return "";
        int g; // 0 clear, 1 mostly, 2 partly, 3 cloudy, 4 fog, 5 drizzle, 6 heavy, 7 snow, 8 storm, 9 rain
        if (code <= 3) g = code;
        else if (code == 45 || code == 48) g = 4;
        else if (code >= 51 && code <= 57) g = 5;
        else if (code == 65 || code == 67 || code == 82) g = 6;
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) g = 7;
        else if (code >= 95) g = 8;
        else g = 9;
        String[] words;
        if (Ui.ar) words = new String[] {day ? "صحو" : "ليلة صافية", "صحو غالبًا", "غائم جزئيًا", "غائم", "ضباب", "رذاذ",
                "أمطار غزيرة", "ثلوج", "عواصف رعدية", "مطر"};
        else if (Ui.en) words = new String[] {"Clear", day ? "Mostly Sunny" : "Mostly Clear", "Partly Cloudy", "Cloudy",
                "Fog", "Drizzle", "Heavy Rain", "Snow", "Thunderstorms", "Rain"};
        else if (Ui.es) words = new String[] {day ? "Despejado" : "Noche despejada", "Mayormente despejado",
                "Parcialmente nublado", "Nublado", "Niebla", "Llovizna", "Lluvia fuerte", "Nieve", "Tormenta", "Lluvia"};
        else words = new String[] {day ? "Céu limpo" : "Noite limpa", "Poucas nuvens", "Parcialmente nublado", "Nublado",
                "Neblina", "Garoa", "Chuva forte", "Neve", "Tempestade", "Chuva"};
        return words[g];
    }
}
