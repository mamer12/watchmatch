package io.watchmatch;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.function.Supplier;

import static io.watchmatch.SettingsRows.*;

/**
 * The settings' main page and its groups: the home screen switch at the top, then Home screen, Look, {catalog}, Match
 * Centre, Search, Remote buttons, Screensaver, Accessibility, Performance, About and TV Settings, roughly by how often
 * people use them. Every row says what it does in plain words; the side panel shows it (SettingsPreview).
 * Prefs keys and behavior are the ones the settings always had; only where they live changed.
 */
final class SettingsGroups {
    private SettingsGroups() {}

    private static final String[] LANGUAGES = {"auto", "pt", "en", "es", "ar"}, UNITS = {"auto", "C", "F"},
            THEMES = {"auto", "light", "dark"}, DIGITS = {"auto", "western", "arabic"}, PERFORMANCE = {"auto", "full", "lite"},
            CLOCKS = {"auto", "12", "24"}, TEXT_SIZES = {"normal", "large", "larger"};

    static Page main(Host h) {
        return new Page("main", () -> Config.APP_NAME, () -> Ui.tr("OK abre  ·  Voltar sai dos ajustes", "OK opens  ·  Back leaves the settings"), p -> {
            p.add(modeCard(h));
            p.add(group(h, "home", () -> Ui.tr("Tela inicial", "Home screen"), () -> homeSummary(h),
                    () -> Ui.tr("As fileiras, as predefinições, a prateleira de apps e os widgets da aba Início.",
                            "The rows, presets, the apps shelf and the widgets on the Home tab."), () -> homeScreen(h)));
            p.add(group(h, "palette", () -> Ui.tr("Aparência", "Look"), SettingsGroups::lookSummary,
                    () -> Ui.tr("Tema, cor de destaque, relógio, algarismos, idioma e temperatura.",
                            "Theme, accent colour, clock, digits, language and temperature."), () -> look(h)));
            p.add(group(h, "movie", () -> WatchMe.NAME, SettingsGroups::catalogSummary,
                    () -> Ui.tr("Os ajustes do {catalog} e as fileiras dele no Início.", "{catalog}'s own settings and its rows on Home."),
                    () -> catalog(h)));
            p.add(group(h, "soccer", () -> Ui.tr("Central de Jogos", "Match Centre"), SettingsLists::matchSummary,
                    () -> Ui.tr("Seus times, os campeonatos, os alertas de gol e o horário de silêncio.",
                            "Your teams, the leagues, goal alerts and quiet hours."), () -> SettingsLists.matchCentre(h)));
            p.add(group(h, "search", () -> Ui.tr("Busca", "Search"), () -> Ui.tr("{spark}: diga o que quer assistir", "{spark}: say what you feel like watching"),
                    () -> Ui.tr("O que o {spark} entende quando você fala ou digita.", "What {spark} understands when you speak or type."),
                    () -> search(h)));
            p.add(group(h, "remote", () -> Ui.tr("Botões do Controle", "Remote Buttons"), () -> SettingsLists.remoteSummary(h),
                    () -> Ui.tr("O app que cada botão de app do controle abre.", "The app each of the remote's app buttons opens."),
                    () -> SettingsLists.remote(h)));
            p.add(group(h, "bedtime", () -> Ui.tr("Tela de Descanso", "Screensaver"), () -> h.freeActive()
                            ? Ui.tr("Céu, relógio e clima  ·  Ver agora", "Sky, clock and weather  ·  Preview")
                            : Ui.tr("A da TV  ·  Ver agora", "The TV's own  ·  Preview"),
                    () -> Ui.tr("Veja a tela de descanso aqui mesmo, sem mudar nada na TV.", "See the screensaver right here, without changing anything on the TV."),
                    () -> screensaver(h)));
            p.add(group(h, "accessibility", () -> Ui.tr("Acessibilidade", "Accessibility"), SettingsGroups::a11ySummary,
                    () -> Ui.tr("Tamanho do texto, alto contraste e menos movimento.", "Text size, high contrast and less motion."),
                    () -> accessibility(h)));
            p.add(group(h, "speed", () -> Ui.tr("Desempenho", "Performance"), SettingsGroups::perfSummary,
                    () -> Ui.tr("Efeitos completos ou leves, e quanto o {app} pesa contra o Início original.",
                            "Full or lite effects, and how {app} compares with the original home screen."), () -> performance(h)));
            p.add(group(h, "info", () -> Ui.tr("Sobre", "About"), () -> aboutSummary(h),
                    () -> Ui.tr("Versão, avisos, recomendações, TMDB e licenças.", "Version, notices, recommendations, TMDB and licenses."),
                    () -> about(h)));
            p.add(new Row(() -> Ui.tr("Ajustes da TV", "TV Settings")).icon("tv_settings")
                    .value(() -> Ui.tr("Imagem, som, rede e contas", "Picture, sound, network and accounts"))
                    .about(() -> Ui.tr("Abre os ajustes da própria TV, fora do {app}.", "Opens the TV's own settings, outside {app}."))
                    .opens(() -> h.launch(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        }).main();
    }

    /** A group on the main page: its symbol, name and a summary of its values; the preview lists what's inside. */
    private static Row group(Host h, String icon, Supplier<String> title, Supplier<String> summary, Supplier<String> about,
            Supplier<Page> page) {
        return new Row(title).icon(icon).value(summary).about(about).opens(() -> h.open(page.get())).preview(P_GROUP)
                .lines(() -> inside(page.get()));
    }

    /** A page's rows as {name, value}: the "what's inside" of a group. */
    static List<String[]> inside(Page p) {
        p.rebuild();
        List<String[]> out = new ArrayList<>();
        for (Row r : p.rows) out.add(new String[] {r.label.get(), r.value == null ? "" : r.value.get()});
        return out;
    }

    // MARK: - The home screen switch

    private static Row modeCard(Host h) {
        return new Row(() -> h.freeActive() ? Ui.tr("O {app} é a sua tela inicial", "{app} is your home screen")
                : Ui.tr("Tela inicial original", "Original home screen"))
                .value(() -> h.busy() ? busyText(h) : Ui.tr("Mudar", "Change"))
                .about(() -> modeAbout(h))
                .warn(() -> allowing(h) || !h.busy() && !h.state().optString("error").isEmpty())
                .opens(() -> {
                    if (!h.busy()) h.open(modeChoice(h));
                })
                .preview(P_MODE, "");
    }

    static String busyText(Host h) {
        String busy = h.state().optString("busy");
        return busy.isEmpty() ? Ui.tr("Começando…", "Starting…") : busy;
    }

    private static boolean allowing(Host h) {
        String busy = h.state().optString("busy");
        return h.busy() && (busy.contains("Permitir") || busy.contains("Allow"));
    }

    private static String modeAbout(Host h) {
        if (allowing(h)) return Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK.");
        String error = h.state().optString("error");
        if (!h.busy() && !error.isEmpty()) return error;
        int n = h.promoApps();
        String apps = Ui.digits(String.valueOf(n));
        if (h.freeActive()) {
            return Ui.tr("Início e tela de descanso sem anúncios, " + apps + " apps de propaganda desligados. O Original desfaz tudo.",
                    "Ads-free home and screensaver, " + apps + " promo apps off. Original undoes everything.",
                    "Inicio y protector sin anuncios, " + apps + " apps promocionales desactivadas. Original lo deshace todo.",
                    "رئيسية وشاشة توقف بلا إعلانات، و" + apps + " تطبيقات ترويجية متوقفة. الأصلي يعيد كل شيء.");
        }
        return Ui.tr("Mude para um Início sem anúncios, com a tela de descanso nova. O Original desfaz tudo.",
                "Switch to an ads-free home with the new screensaver. Original undoes everything.");
    }

    static Page modeChoice(Host h) {
        return new Page("mode", () -> Ui.tr("Início", "Home Screen"),
                () -> Ui.tr("A troca leva uns segundos e a tela pisca nas medições.", "Switching takes a few seconds; the screen blinks while it measures."),
                p -> {
                    p.add(new Row(() -> Config.APP_NAME).checked(h::freeActive)
                            .about(() -> Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver"))
                            .ok(() -> choose(h, true)).preview(P_MODE, HomeMode.FREE));
                    p.add(new Row(() -> Ui.tr("Original", "Original")).checked(() -> !h.freeActive())
                            .about(() -> Ui.tr("O Início de fábrica da TV, com tudo de volta como era.", "The TV's factory home screen, with everything back as it was."))
                            .ok(() -> choose(h, false)).preview(P_MODE, HomeMode.ORIGINAL));
                }).focus(h.freeActive() ? 0 : 1);
    }

    private static void choose(Host h, boolean free) {
        if (free != h.freeActive() && !h.busy()) h.requestMode(free);
        h.back();
    }

    // MARK: - Home screen

    private static String homeSummary(Host h) {
        List<String> parts = new ArrayList<>();
        parts.add(HomeRows.presetName(Prefs.homePreset()));
        parts.add(count(h.shelf().size(), "app na prateleira", "apps na prateleira", "app on the shelf", "apps on the shelf",
                "app en el estante", "apps en el estante", "تطبيق واحد على الرف", "تطبيقات على الرف"));
        boolean wx = Prefs.weather(), prices = Prefs.quotes();
        parts.add(wx && prices ? Ui.tr("Clima e cotações", "Weather and prices") : wx ? Ui.tr("Clima", "Weather")
                : prices ? Ui.tr("Cotações", "Prices") : Ui.tr("Sem widgets", "No widgets"));
        return join(parts);
    }

    static Page homeScreen(Host h) {
        return new Page("home", () -> Ui.tr("Tela inicial", "Home screen"), () -> Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), p -> {
            p.add(new Row(() -> Ui.tr("Fileiras e predefinições", "Rows and presets")).value(() -> HomeRows.presetName(Prefs.homePreset()))
                    .about(() -> Ui.tr("Quais fileiras aparecem sob os apps, e em que ordem.", "Which rows show under the apps, and in what order."))
                    .opens(() -> h.open(SettingsLists.homeRows(h))).preview(P_ROWS, ""));
            p.add(new Row(() -> Ui.tr("Prateleira", "Shelf"))
                    .value(() -> count(h.shelf().size(), "app", "apps", "app", "apps", "app", "apps", "تطبيق واحد", "تطبيقات"))
                    .about(() -> Ui.tr("Os apps da primeira fileira, na ordem que você escolher.", "The apps in the first row, in the order you pick."))
                    .opens(() -> h.open(SettingsLists.shelf(h))).preview(P_SHELF, ""));
            p.add(new Row(() -> Ui.tr("Widget do clima", "Weather widget")).value(() -> onOff(Prefs.weather()))
                    .about(() -> Prefs.weather() ? Ui.tr("O clima aparece ao lado da data.", "Shows the weather next to the date.")
                            : Ui.tr("Só a data aparece ao lado do relógio.", "Only the date shows by the clock."))
                    .change(d -> {
                        Prefs.setWeather(!Prefs.weather());
                        dataChanged(h);
                    }).preview(P_WIDGETS, "weather"));
            p.add(new Row(() -> Ui.tr("Widget de cotações", "Prices widget")).value(() -> onOff(Prefs.quotes()))
                    .about(() -> Prefs.quotes() ? Ui.tr("Os preços das moedas aparecem na gaveta de baixo.", "Coin prices show in the drawer at the bottom.")
                            : Ui.tr("Nenhum preço no Início.", "No prices on the home screen."))
                    .change(d -> {
                        Prefs.setQuotes(!Prefs.quotes());
                        dataChanged(h);
                    }).preview(P_WIDGETS, "prices"));
            int coins = "BRL".equalsIgnoreCase(Config.CURRENCY.trim()) ? 4 : 3;
            for (int i = 0; i < coins; i++) {
                final int coin = i;
                p.add(new Row(() -> HomeData.coinName(coin)).value(() -> onOff(Prefs.quotes() && Prefs.coin(coin)))
                        .locked(() -> !Prefs.quotes())
                        .about(() -> coinAbout(coin))
                        .change(d -> {
                            Prefs.setCoin(coin, !Prefs.coin(coin));
                            dataChanged(h);
                        }).preview(P_WIDGETS, coin).arg("coin"));
            }
            p.add(new Row(() -> Ui.tr("Saudação", "Greeting")).value(() -> Moments.greeting(Moments.now()))
                    .about(() -> Ui.tr("Muda com a hora do dia, acima das fileiras.", "Changes with the time of day, above the rows."))
                    .preview(P_WIDGETS, "greeting"));
        });
    }

    private static String coinAbout(int coin) {
        if (!Prefs.quotes()) return Ui.tr("Ligue antes o widget de cotações.", "Turn on the prices widget first.");
        String name = HomeData.coinName(coin);
        if (!Prefs.coin(coin)) return Ui.tr(name + " fica fora da gaveta.", name + " stays out of the drawer.",
                name + " queda fuera del cajón.", "لا يظهر " + Ui.isolate(name) + " في الدرج.");
        return Ui.tr("O preço do " + name + " aparece na gaveta.", name + "'s price shows in the drawer.",
                "El precio de " + name + " aparece en el cajón.", "يظهر سعر " + Ui.isolate(name) + " في الدرج.");
    }

    /** Settings the home screen's data depends on (the city's weather, the coins): fetched again. */
    private static void dataChanged(Host h) {
        HomeData.get(h.context()).settingsChanged();
        h.changed();
    }

    // MARK: - Look

    private static String lookSummary() {
        List<String> parts = new ArrayList<>();
        parts.add(languageName(Prefs.language()));
        String clock = Prefs.clock();
        parts.add(clock.equals("12") ? Ui.tr("12 horas", "12-hour") : clock.equals("24") ? Ui.tr("24 horas", "24-hour")
                : Ui.tr("Relógio automático", "Automatic clock"));
        parts.add(Ui.digits("123"));
        String theme = Prefs.theme();
        parts.add(theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark")
                : Ui.tr("Tema automático", "Automatic theme"));
        return join(parts);
    }

    static Page look(Host h) {
        return new Page("look", () -> Ui.tr("Aparência", "Look"), () -> Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), p -> {
            p.add(new Row(() -> Ui.tr("Tema", "Theme")).value(SettingsGroups::themeValue).about(SettingsGroups::themeAbout)
                    .change(d -> {
                        Prefs.setTheme(next(THEMES, Prefs.theme(), d));
                        dataChanged(h);
                    }).preview(P_CARD, "theme"));
            p.add(new Row(() -> Ui.tr("Cor de destaque", "Accent colour")).value(() -> Ui.accentName(Prefs.accent()))
                    .about(SettingsGroups::accentAbout)
                    .change(d -> {
                        Prefs.setAccent(next(Ui.ACCENTS, Prefs.accent(), d));
                        h.changed();
                    }).preview(P_CARD, "accent"));
            p.add(new Row(() -> Ui.tr("Relógio", "Clock")).value(SettingsGroups::clockValue).about(SettingsGroups::clockAbout)
                    .change(d -> {
                        Prefs.setClock(next(CLOCKS, Prefs.clock(), d));
                        h.changed();
                    }).preview(P_CLOCK, "clock"));
            p.add(new Row(() -> Ui.tr("Algarismos", "Digits")).value(SettingsGroups::digitsValue).about(SettingsGroups::digitsAbout)
                    .change(d -> {
                        Prefs.setDigits(next(DIGITS, Prefs.digits(), d));
                        dataChanged(h);
                    }).preview(P_CLOCK, "digits"));
            p.add(new Row(() -> Ui.tr("Idioma", "Language")).value(() -> languageName(Prefs.language())).about(SettingsGroups::languageAbout)
                    .change(d -> {
                        Prefs.setLanguage(next(LANGUAGES, Prefs.language(), d));
                        dataChanged(h);
                    }).preview(P_CLOCK, "language"));
            p.add(new Row(() -> Ui.tr("Temperatura", "Temperature")).value(SettingsGroups::unitValue).about(() -> unitAbout(h))
                    .change(d -> {
                        Prefs.setTemperature(next(UNITS, Prefs.temperature(), d));
                        dataChanged(h);
                    }).preview(P_CLOCK, "temperature"));
        });
    }

    private static String themeValue() {
        String theme = Prefs.theme();
        return theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
    }

    private static String themeAbout() {
        String theme = Prefs.theme();
        if (theme.equals("light")) return Ui.tr("Sempre o visual claro, de dia.", "Always the bright daytime look.");
        if (theme.equals("dark")) return Ui.tr("Sempre o visual escuro, de noite.", "Always the dark night look.");
        return Ui.tr("Claro de dia, escuro do pôr ao nascer do sol.", "Light by day, dark from sunset to sunrise.");
    }

    private static String accentAbout() {
        String name = Ui.accentName(Prefs.accent()), lower = name.toLowerCase(Ui.locale());
        return Ui.tr("O contorno do que você escolhe fica " + lower + ".", "The ring around what you pick turns " + lower + ".",
                "El borde de lo que eliges se vuelve " + lower + ".", "يصبح إطار ما تختاره باللون ال" + name + ".");
    }

    private static String clockValue() {
        String clock = Prefs.clock();
        return clock.equals("12") ? Ui.tr("12 horas", "12-hour") : clock.equals("24") ? Ui.tr("24 horas", "24-hour") : Ui.tr("Automático", "Automatic");
    }

    /** "7:30 in the evening shows as 19:30", with the clock as it is set. */
    private static String clockAbout() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 19);
        cal.set(Calendar.MINUTE, 30);
        String s = Ui.clock(cal.getTimeInMillis());
        return Ui.digits(Ui.tr("7:30 da noite aparece como " + s + ".", "7:30 in the evening shows as " + s + ".",
                "Las 7:30 de la tarde se ven como " + s + ".", "تظهر الساعة 7:30 مساءً هكذا: " + s));
    }

    private static String digitsValue() {
        String digits = Prefs.digits();
        return digits.equals("western") ? Ui.tr("Ocidentais (123)", "Western (123)")
                : digits.equals("arabic") ? Ui.tr("Arábicos (١٢٣)", "Arabic-Indic (١٢٣)") : Ui.tr("Automático", "Automatic");
    }

    private static String digitsAbout() {
        String digits = Prefs.digits();
        if (digits.equals("auto")) return Ui.tr("١٢٣ em árabe, 123 nos outros idiomas.", "١٢٣ in Arabic, 123 in the other languages.");
        String sample = Ui.digits("123 · 7:30 · 24°");
        return Ui.tr("Os números aparecem assim: " + sample, "Numbers show like this: " + sample, "Los números se ven así: " + sample,
                "تظهر الأرقام هكذا: " + sample);
    }

    private static String languageAbout() {
        if (Prefs.language().equals("auto")) {
            String now = languageName(Ui.language);
            return Ui.tr("Segue o idioma da TV (agora " + now + "), também na busca por voz.",
                    "Follows the TV's language (now " + now + "), voice search too.",
                    "Sigue el idioma del TV (ahora " + now + "), también en la búsqueda por voz.",
                    "يتبع لغة التلفاز (الآن " + Ui.isolate(now) + ")، وكذلك البحث الصوتي.");
        }
        String name = languageName(Prefs.language());
        return Ui.tr("Menus, Início e busca por voz em " + name + ".", "Menus, the home screen and voice search in " + name + ".",
                "Menús, inicio y búsqueda por voz en " + name + ".", "القوائم والرئيسية والبحث الصوتي باللغة " + Ui.isolate(name) + ".");
    }

    private static String unitValue() {
        String unit = Prefs.temperature();
        return unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
    }

    private static String unitAbout(Host h) {
        HomeData.Weather wx = HomeData.get(h.context()).weather;
        String d = HomeData.degrees(wx == null || Double.isNaN(wx.temp) ? 24 : wx.temp);
        if (Prefs.temperature().equals("auto")) {
            return Ui.tr("°F nos EUA, °C nos outros países. Agora: " + d, "°F in the US, °C elsewhere. Now: " + d,
                    "°F en EE. UU., °C en los demás países. Ahora: " + d, "°F في الولايات المتحدة و°C في غيرها. الآن: " + d);
        }
        return Ui.tr("As temperaturas aparecem assim: " + d, "Temperatures show like this: " + d, "Las temperaturas se ven así: " + d,
                "تظهر درجات الحرارة هكذا: " + d);
    }

    // MARK: - {catalog}

    private static String catalogSummary() {
        int shown = 0;
        for (String e : HomeRows.layout()) {
            String id = HomeRows.id(e);
            if (!HomeRows.hidden(e) && (id.equals(HomeRows.PICKS) || id.equals(HomeRows.LIBRARY))) shown++;
        }
        return join(java.util.Arrays.asList(Ui.tr("Filtros, qualidade e legendas", "Filters, quality and subtitles"),
                count(shown, "fileira no Início", "fileiras no Início", "row on Home", "rows on Home", "fila en Inicio", "filas en Inicio",
                        "صف واحد في الرئيسية", "صفوف في الرئيسية")));
    }

    static Page catalog(Host h) {
        return new Page("catalog", () -> WatchMe.NAME, () -> Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), p -> {
            p.add(new Row(() -> Ui.tr("Abrir os ajustes do {catalog}", "Open {catalog} settings"))
                    .about(() -> Ui.tr("Filtro para a família, qualidade, legendas, reprodução automática e prévias, dentro do {catalog}.",
                            "Family filter, quality, subtitles, autoplay and previews, inside {catalog}."))
                    .opens(() -> h.launch(CinemaActivity.launch(h.context()).putExtra(CinemaActivity.EXTRA_SETTINGS, true)))
                    .lines(SettingsGroups::catalogInside));
            p.add(new Row(() -> Ui.tr("Perfil", "Profile", "Perfil", "الملف"))
                    .value(() -> profileLine(h))
                    .about(() -> Ui.tr("Quem está assistindo no {catalog}: a Minha Lista e o Continuar assistindo do Início são dele. OK abre os perfis.",
                            "Who is watching in {catalog}: Home's My List and Continue watching are theirs. OK opens the profiles.",
                            "Quién está viendo en {catalog}: la Mi lista y Seguir viendo de Inicio son suyos. OK abre los perfiles.",
                            "من يشاهد في {catalog}: قائمتي ومتابعة المشاهدة في الرئيسية له. OK يفتح الملفات."))
                    .opens(() -> h.launch(WatchMe.openProfiles(h.context()))));
            if (Sync.enabled()) {
                p.add(new Row(() -> AccountText.account())
                        .value(() -> accountLine(h))
                        .about(() -> Ui.tr("Entre ou crie a conta nos Ajustes do {catalog}: mesmos perfis e progresso em todos os aparelhos.",
                                "Sign in or create the account in {catalog}'s settings: the same profiles and progress on every device.",
                                "Entra o crea la cuenta en los ajustes de {catalog}: los mismos perfiles y progreso en todos tus dispositivos.",
                                "سجّل الدخول أو أنشئ الحساب من إعدادات {catalog}: الملفات والتقدم نفسهما على كل جهاز.")));
            }
            p.add(new Row(() -> Ui.tr("Conectado via", "Connected through", "Conectado a través de", "متصل عبر"))
                    .value(() -> Provider.label(Provider.current()))
                    .about(() -> Provider.fixed()
                            ? Ui.tr("Fixado para teste (provider_override).", "Fixed for troubleshooting (provider_override).",
                                    "Fijado para pruebas (provider_override).", "مثبّت للاختبار (provider_override).")
                            : Ui.tr("Escolhido sozinho: a melhor conexão da sua rede para o {catalog}.",
                                    "Chosen automatically: the best connection on your network for {catalog}.",
                                    "Elegido solo: la mejor conexión de tu red para {catalog}.",
                                    "يُختار تلقائيًا: أفضل اتصال في شبكتك لـ{catalog}."))
                    .lines(SettingsGroups::providerLines));
            p.add(new Row(() -> Ui.tr("Verificar de novo", "Re-check", "Verificar de nuevo", "إعادة الفحص"))
                    .value(() -> Provider.checking() ? Ui.tr("Verificando…", "Checking…", "Comprobando…", "جارٍ الفحص…") : "")
                    .about(() -> Ui.tr("Testa as conexões agora e usa a melhor.", "Tests the connections now and uses the best one.",
                            "Prueba las conexiones ahora y usa la mejor.", "يختبر الاتصالات الآن ويستخدم الأفضل."))
                    .ok(() -> recheck(h))
                    .lines(SettingsGroups::providerLines));
            p.add(new Row(() -> Ui.tr("Redefinir os dados do {catalog}", "Reset {catalog} data", "Restablecer los datos de {catalog}",
                            "إعادة ضبط بيانات {catalog}"))
                    .about(() -> Ui.tr("Apaga Minha Lista, notas, progresso e gostos do {catalog}, depois de perguntar. Os ajustes do {app} ficam.",
                            "Clears {catalog}'s My List, ratings, progress and tastes, after asking. {app}'s own settings stay.",
                            "Borra Mi lista, valoraciones, progreso y gustos de {catalog}, tras preguntar. Los ajustes de {app} se quedan.",
                            "يمسح قائمتي والتقييمات والتقدم والأذواق في {catalog} بعد التأكيد. إعدادات {app} تبقى."))
                    .warn(() -> true)
                    .opens(() -> h.launch(CinemaActivity.launch(h.context()).putExtra(CinemaActivity.EXTRA_RESET, true))));
            p.add(homeRowToggle(h, HomeRows.PICKS, () -> Ui.tr("Uma fileira de sugestões do {catalog} na aba Início.",
                    "A row of {catalog} picks on the Home tab.")));
            p.add(homeRowToggle(h, HomeRows.LIBRARY, () -> Ui.tr("Os títulos novos e em destaque do {catalog} na aba Início.",
                    "{catalog}'s new and featured titles on the Home tab.")));
        });
    }

    private static final java.util.concurrent.ExecutorService PROFILE_READ = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile String profileText = "", accountText = "";
    private static volatile long profileAt;
    private static volatile boolean profileReading;

    /** The active Watchme+ profile's name (read in the background through the provider; "" until it is known). */
    private static String profileLine(final Host h) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!profileReading && (profileText.isEmpty() || now - profileAt > 5_000)) {
            profileReading = true;
            final Context c = h.context();
            PROFILE_READ.execute(() -> {
                WatchMe.Who who = WatchMe.activeProfile(c);
                String text = who == null ? "" : Ui.isolate(who.name) + (who.kids ? " · " + Ui.tr("Crianças", "Kids", "Niños", "أطفال") : "");
                String account = who == null || who.account.isEmpty() ? "" : who.account;
                boolean changed = !text.equals(profileText) || !account.equals(accountText);
                profileText = text;
                accountText = account;
                profileAt = android.os.SystemClock.elapsedRealtime();
                profileReading = false;
                if (changed) h.later(h::changed);
            });
        }
        return profileText;
    }

    /** "Signed in as x" (read-only), or "Guest"; read with the profile above. */
    private static String accountLine(Host h) {
        profileLine(h);
        return accountText.isEmpty() ? AccountText.status("") : AccountText.status(accountText);
    }

    /** Which catalog server is in use and when it last answered (the preview of the "Connected through" rows). */
    private static List<String[]> providerLines() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {Ui.tr("Conectado via", "Connected through", "Conectado a través de", "متصل عبر"), Provider.label(Provider.current())});
        out.add(new String[] {Ui.tr("Última verificação", "Last verified", "Última comprobación", "آخر فحص"), Provider.verified()});
        return out;
    }

    /** Re-check: a detection round now; the rows read again when it ends. */
    private static void recheck(final Host h) {
        Provider.addListener(new Provider.Listener() {
            @Override public void onProviderChanged() {
                if (!Provider.checking()) Provider.removeListener(this);
                h.changed();
            }
        });
        Provider.recheck(h.context());
        h.changed();
    }

    private static List<String[]> catalogInside() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {Ui.tr("Filtro para a família", "Family filter"), ""});
        out.add(new String[] {Ui.tr("Qualidade do vídeo", "Video quality"), ""});
        out.add(new String[] {Ui.tr("Legendas: idioma, tamanho, estilo e cor", "Subtitles: language, size, style and colour"), ""});
        out.add(new String[] {Ui.tr("Reprodução automática", "Autoplay"), ""});
        out.add(new String[] {Ui.tr("Prévias enquanto navega", "Previews while browsing"), ""});
        out.add(new String[] {Ui.tr("Idioma dos títulos", "Title language"), ""});
        return out;
    }

    /** A Home row shown or hidden from here too (the same Home Rows setting). */
    private static Row homeRowToggle(Host h, String id, Supplier<String> about) {
        return new Row(() -> HomeRows.label(id)).value(() -> onOff(!SettingsLists.rowHidden(id)))
                .about(() -> SettingsLists.rowHidden(id) ? Ui.tr("Oculta da aba Início.", "Hidden from the Home tab.") : about.get())
                .change(d -> {
                    SettingsLists.toggleRow(id);
                    h.changed();
                }).preview(P_ROWS, id);
    }

    // MARK: - Search ({spark})

    static Page search(Host h) {
        return new Page("search", () -> Ui.tr("Busca", "Search"), () -> Ui.tr("Fale ou digite uma frase, em árabe ou inglês.",
                "Say or type a sentence, in Arabic or English."), p -> {
            p.add(new Row(() -> Ui.tr("Buscar agora", "Search now"))
                    .about(() -> Ui.tr("Diga ou digite o que quer assistir: o {spark} transforma em filtros.",
                            "Say or type what you feel like watching: {spark} turns it into filters."))
                    .opens(() -> h.launch(new Intent(h.context(), SearchActivity.class))).preview(P_SEARCH, "genre"));
            p.add(info(() -> Ui.tr("Filme ou série", "Movie or series"), () -> Ui.tr("“série” ou “مسلسل” traz só séries; “filmes”, só filmes.",
                    "“series” or “مسلسل” keeps only series; “movies” only films."), "kind"));
            p.add(info(() -> Ui.tr("Gêneros", "Genres"), () -> Ui.tr("Ação, comédia, crime, terror, romance, desenhos e mais.",
                    "Action, comedy, crime, horror, romance, cartoons and more."), "genre"));
            p.add(info(() -> Ui.tr("Quando", "When"), () -> Ui.tr("“novo” (os últimos 3 anos), “antigo” (antes de 2000), uma década, um ano ou um intervalo.",
                    "“new” (the last 3 years), “old” (before 2000), a decade, a year or a range."), "era"));
            p.add(info(() -> Ui.tr("De onde", "Where it's from"), () -> Ui.tr("Árabe, turco, coreano, indiano, japonês, egípcio, do Golfo ou americano.",
                    "Arabic, Turkish, Korean, Indian, Japanese, Egyptian, from the Gulf or American."), "origin"));
            p.add(info(() -> Ui.tr("Títulos", "Titles"), () -> Ui.tr("As palavras que ele não conhece viram o título: “breaking bad”.",
                    "Words it doesn't know are the title: “breaking bad”."), "title"));
            p.add(info(() -> Ui.tr("Onde procura", "Where it looks"), () -> Ui.tr("No {catalog}, nos seus apps de streaming (TMDB) e nos outros apps da TV.",
                    "{catalog}, your streaming apps (TMDB), then the TV's other apps."), "where"));
            p.add(info(() -> Ui.tr("Voz", "Voice"), () -> Ui.tr("Escuta no idioma do ajuste Idioma.", "Listens in the language the Language setting picks."), "voice")
                    .value(() -> Prefs.language().equals("auto") ? Ui.tr("O da TV", "The TV's") : languageName(Prefs.language())));
        });
    }

    private static Row info(Supplier<String> label, Supplier<String> about, String what) {
        return new Row(label).about(about).preview(P_SEARCH, what);
    }

    // MARK: - Screensaver

    static Page screensaver(Host h) {
        return new Page("screensaver", () -> Ui.tr("Tela de Descanso", "Screensaver"), () -> Ui.tr("OK mostra agora.", "OK shows it now."), p -> {
            p.add(new Row(() -> Ui.tr("Ver agora", "Preview"))
                    .about(() -> Ui.tr("Mostra a tela de descanso aqui. Qualquer botão fecha.", "Shows the screensaver right here. Any button closes it."))
                    .opens(h::screensaver).preview(P_SCREENSAVER));
            p.add(new Row(() -> Ui.tr("Em uso", "In use")).value(() -> h.freeActive() ? Config.APP_NAME : Ui.tr("A da TV", "The TV's own"))
                    .about(() -> h.freeActive() ? Ui.tr("O céu, o relógio e o clima enquanto a TV descansa.", "The sky, the clock and the weather while the TV rests.")
                            : Ui.tr("Mude a tela inicial para o {app} para usar esta.", "Switch the home screen to {app} to use this one."))
                    .preview(P_SCREENSAVER));
        });
    }

    // MARK: - Accessibility

    private static String a11ySummary() {
        List<String> parts = new ArrayList<>();
        String size = Prefs.textSize();
        parts.add(size.equals("larger") ? Ui.tr("Texto maior", "Larger text") : size.equals("large") ? Ui.tr("Texto grande", "Large text")
                : Ui.tr("Texto normal", "Normal text"));
        if (Prefs.highContrast()) parts.add(Ui.tr("Alto contraste", "High contrast"));
        if (Prefs.reduceMotion()) parts.add(Ui.tr("Menos movimento", "Less motion"));
        return join(parts);
    }

    static Page accessibility(Host h) {
        return new Page("a11y", () -> Ui.tr("Acessibilidade", "Accessibility"), () -> Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), p -> {
            p.add(new Row(() -> Ui.tr("Tamanho do texto", "Text size")).value(() -> {
                String size = Prefs.textSize();
                return size.equals("larger") ? Ui.tr("Maior", "Larger") : size.equals("large") ? Ui.tr("Grande", "Large") : Ui.tr("Normal", "Normal");
            }).about(() -> {
                String size = Prefs.textSize();
                return size.equals("larger") ? Ui.tr("Texto 30% maior em todas as telas.", "Text 30% bigger on every screen.")
                        : size.equals("large") ? Ui.tr("Texto 15% maior em todas as telas.", "Text 15% bigger on every screen.")
                        : Ui.tr("O texto no tamanho de sempre.", "Text at its usual size.");
            }).change(d -> {
                Prefs.setTextSize(next(TEXT_SIZES, Prefs.textSize(), d));
                h.changed();
            }).preview(P_CARD, "text"));
            p.add(new Row(() -> Ui.tr("Alto contraste", "High contrast")).value(() -> onOff(Prefs.highContrast()))
                    .about(() -> Prefs.highContrast() ? Ui.tr("Texto mais claro sobre fundos mais escuros.", "Brighter text on darker backgrounds.")
                            : Ui.tr("O visual suave de sempre.", "The usual softer look."))
                    .change(d -> {
                        Prefs.setHighContrast(!Prefs.highContrast());
                        h.changed();
                    }).preview(P_CARD, "contrast"));
            p.add(new Row(() -> Ui.tr("Reduzir movimento", "Reduce motion")).value(() -> onOff(Prefs.reduceMotion()))
                    .about(() -> Prefs.reduceMotion() ? Ui.tr("Sem deslizar nem aproximar: o foco pula direto para o lugar.",
                            "No sliding or zooming: the focus jumps straight to its place.")
                            : Ui.tr("O foco desliza e as fotos se movem devagar.", "The focus glides and pictures drift slowly."))
                    .change(d -> {
                        Prefs.setReduceMotion(!Prefs.reduceMotion());
                        h.changed();
                    }).preview(P_MOTION));
        });
    }

    // MARK: - Performance

    private static String perfValue() {
        String perf = Prefs.performance();
        return perf.equals("full") ? Ui.tr("Efeitos completos", "Full effects") : perf.equals("lite") ? Ui.tr("Leve", "Lite") : Ui.tr("Automático", "Automatic");
    }

    private static String perfSummary() {
        if (!Prefs.performance().equals("auto")) return perfValue();
        return join(java.util.Arrays.asList(perfValue(), Ui.lite ? Ui.tr("Leve nesta TV", "Lite on this TV") : Ui.tr("Completo nesta TV", "Full on this TV")));
    }

    static Page performance(Host h) {
        return new Page("performance", () -> Ui.tr("Desempenho", "Performance"), () -> Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), p -> {
            p.add(new Row(() -> Ui.tr("Desempenho", "Performance")).value(SettingsGroups::perfValue).about(() -> {
                String perf = Prefs.performance();
                if (perf.equals("full")) return Ui.tr("Céu em movimento e vidro líquido. Melhor em TVs de 3 GB ou mais.",
                        "Moving sky and liquid glass. Best on TVs with 3 GB or more.");
                if (perf.equals("lite")) return Ui.tr("Efeitos mais simples, mais fluido em TVs de 2 GB.", "Simpler effects, smoother on 2 GB TVs.");
                return Ui.lite ? Ui.tr("Leve em TVs antigas ou de 2 GB, completo nas outras. Nesta TV: leve.",
                        "Lite on older or 2 GB TVs, full on the rest. This TV: lite.")
                        : Ui.tr("Leve em TVs antigas ou de 2 GB, completo nas outras. Nesta TV: completo.",
                        "Lite on older or 2 GB TVs, full on the rest. This TV: full.");
            }).change(d -> {
                Prefs.setPerformance(next(PERFORMANCE, Prefs.performance(), d));
                dataChanged(h);
            }).preview(P_PERF, ""));
            p.add(measure(h, () -> Ui.tr("Botão Início", "Home button"), "homeMs"));
            p.add(measure(h, () -> Ui.tr("Memória livre", "Free memory"), "memAvailableMB"));
            p.add(measure(h, () -> Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"));
        });
    }

    /** A comparison row: {app}'s number as the value, the original's in the description. */
    private static Row measure(Host h, Supplier<String> label, String key) {
        return new Row(label).value(() -> metric(metrics(h, HomeMode.FREE), key)).about(() -> {
            JSONObject original = metrics(h, HomeMode.ORIGINAL);
            if (original == null && metrics(h, HomeMode.FREE) == null) {
                return Ui.tr("Medido quando você muda a tela inicial.", "Measured when you switch the home screen.");
            }
            String o = metric(original, key);
            return Ui.tr("Original: " + o + ". Medido na última troca.", "Original: " + o + ". Measured at the last switch.",
                    "Original: " + o + ". Medido en el último cambio.", "الأصلي: " + Ui.isolate(o) + ". تم القياس عند آخر تبديل.");
        }).preview(P_PERF, key);
    }

    static JSONObject metrics(Host h, String mode) {
        JSONObject m = h.state().optJSONObject("metrics");
        return m == null ? null : m.optJSONObject(mode);
    }

    // MARK: - About

    private static String aboutSummary(Host h) {
        JSONArray notices = h.state().optJSONArray("notices");
        int n = notices == null ? 0 : notices.length();
        return join(java.util.Arrays.asList(Config.APP_NAME + " " + Ui.digits(versionName(h.context())),
                n == 0 ? Ui.tr("Nenhum aviso", "No notices") : count(n, "aviso", "avisos", "notice", "notices", "aviso", "avisos", "إشعار واحد", "إشعارات")));
    }

    static Page about(Host h) {
        return new Page("about", () -> Ui.tr("Sobre", "About"), () -> Ui.tr("OK abre os avisos.", "OK opens the notices."), p -> {
            p.add(new Row(() -> Ui.tr("Versão", "Version")).value(() -> Ui.digits(versionName(h.context())))
                    .about(() -> Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver")).preview(P_ABOUT));
            p.add(new Row(() -> Ui.tr("Avisos", "Notices")).value(() -> {
                JSONArray notices = h.state().optJSONArray("notices");
                return Ui.digits(String.valueOf(notices == null ? 0 : notices.length()));
            }).about(() -> Ui.tr("O que cada troca de tela inicial fez, do mais novo ao mais antigo.", "What each home screen switch did, newest first."))
                    .opens(() -> h.open(notices(h))).lines(() -> noticeLines(h, 6)));
            p.add(new Row(() -> Ui.tr("Recomendações", "Recommendations")).value(() -> recsValue(h)).about(() -> recsAbout(h)).preview(P_ABOUT));
            p.add(new Row(() -> "TMDB").about(() -> Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.")).preview(P_ABOUT));
            p.add(new Row(() -> Ui.tr("Licenças", "Licenses")).value(() -> "MIT")
                    .about(() -> Ui.tr("{app}: licença MIT. Fonte Inter: SIL Open Font License. Material Symbols: Apache 2.0.",
                            "{app}: MIT License. Inter font: SIL Open Font License. Material Symbols: Apache 2.0.")).preview(P_ABOUT));
        });
    }

    private static String recsValue(Host h) {
        if (!h.hasKey()) return Ui.tr("Desligado", "Off");
        if (!h.recs().optString("error").isEmpty()) return Ui.tr("Erro", "Error");
        return h.recs().optInt("count") > 0 ? Ui.tr("Ligado", "On") : Ui.tr("Buscando…", "Fetching…");
    }

    private static String recsAbout(Host h) {
        JSONObject recs = h.recs();
        String error = recs.optString("error");
        int count = recs.optInt("count");
        if (!h.hasKey()) return Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties).");
        if (!error.isEmpty()) return Ui.tr("Erro: ", "Error: ") + error + ".";
        if (count > 0) return Ui.digits(Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                "On · " + count + " titles from the TV's apps, 3 new ones every hour."));
        return Ui.tr("Buscando os títulos…", "Fetching titles…");
    }

    private static List<String[]> noticeLines(Host h, int max) {
        List<String[]> out = new ArrayList<>();
        JSONArray notices = h.state().optJSONArray("notices");
        for (int i = 0; notices != null && i < notices.length() && out.size() < max; i++) {
            JSONObject o = notices.optJSONObject(i);
            if (o != null) out.add(new String[] {o.optString("text"), ago(o.optLong("at"))});
        }
        if (out.isEmpty()) out.add(new String[] {Ui.tr("Nenhum ainda.", "None yet."), ""});
        return out;
    }

    static Page notices(Host h) {
        return new Page("notices", () -> Ui.tr("Avisos", "Notices"), () -> Ui.tr("Do mais novo ao mais antigo.", "Newest first."), p -> {
            for (String[] line : noticeLines(h, 30)) {
                p.add(new Row(() -> line[0]).value(() -> line[1]).about(() -> line[0]).lines(ArrayList::new));
            }
        });
    }
}
