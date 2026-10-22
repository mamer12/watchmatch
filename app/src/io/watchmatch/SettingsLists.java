package io.watchmatch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.Set;
import java.util.concurrent.Executors;

import static io.watchmatch.SettingsRows.*;

/**
 * The settings pages that edit lists: the shelf, the Home tab's rows and their presets, the remote's buttons and the
 * app each opens, Match Centre with your teams and leagues. OK adds, removes or picks; ← → move or change.
 */
final class SettingsLists {
    private SettingsLists() {}

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final String[] ALERTS = {"all", "goals", "off"};

    /** Never "the catalog" on screen: its app (and the standalone catalog) go by the catalog's name. */
    static String appName(String pkg, String label) {
        return Endpoints.PKG.equals(pkg) || CinemaActivity.STANDALONE.equals(pkg) ? WatchMe.NAME : label;
    }

    /** Installed TV apps by name, after "Nothing" (what a remote button can open): {packages, names}, same order. */
    @SuppressWarnings("unchecked")
    static List<String>[] installedApps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            found.add(new String[] {pkg, appName(pkg, String.valueOf(ri.loadLabel(pm)))});
        }
        Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        List<String> pkgs = new ArrayList<>(), labels = new ArrayList<>();
        pkgs.add("");
        labels.add(Ui.tr("Nada", "Nothing"));
        for (String[] f : found) {
            pkgs.add(f[0]);
            labels.add(f[1]);
        }
        return new List[] {pkgs, labels};
    }

    // MARK: - Shelf

    /** Up to MAX_SHELF apps: OK puts one on the shelf (at the end) or takes it off, ← → move it. Saved at once. */
    static Page shelf(Host h) {
        Context c = h.context();
        final List<String> order = new ArrayList<>(HomeApps.shelfPackages(c));
        final List<String[]> all = new ArrayList<>();
        for (String[] app : HomeApps.shelfCandidates(c)) all.add(new String[] {app[0], appName(app[0], app[1])});
        final Page[] self = new Page[1];
        self[0] = new Page("shelf", () -> Ui.tr("Prateleira", "Shelf"), () -> Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF
                + "). ← → mudam a ordem.", "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden.",
                "اضغط OK للإضافة إلى الرف أو الإزالة منه (حتى " + HomeApps.MAX_SHELF + "). ← → لتغيير الترتيب."), p -> {
            // Selected apps first, in shelf order, then the rest in the order they came.
            List<String[]> rows = new ArrayList<>();
            for (String pkg : order) for (String[] app : all) if (app[0].equals(pkg)) { rows.add(app); break; }
            for (String[] app : all) if (!order.contains(app[0])) rows.add(app);
            for (String[] app : rows) {
                final String pkg = app[0];
                p.add(new Row(() -> app[1]).value(() -> order.contains(pkg) ? place(order.indexOf(pkg) + 1) : Ui.tr("Fora", "Off"))
                        .dim(() -> !order.contains(pkg))
                        .about(() -> {
                            if (!order.contains(pkg)) return order.size() < HomeApps.MAX_SHELF
                                    ? Ui.tr("Fora da prateleira. OK põe no fim.", "Not on the shelf. OK adds it at the end.")
                                    : Ui.tr("A prateleira está cheia: tire um app antes.", "The shelf is full: take an app off first.");
                            String n = Ui.digits(String.valueOf(order.indexOf(pkg) + 1));
                            return Ui.tr("Na prateleira, no lugar " + n + ". ← → mudam o lugar.", "On the shelf, place " + n + ". ← → move it.",
                                    "En el estante, puesto " + n + ". ← → lo mueven.", "على الرف، في المكان " + n + ". ← → لتحريكه.");
                        })
                        .ok(() -> {
                            if (order.contains(pkg)) order.remove(pkg);
                            else if (order.size() < HomeApps.MAX_SHELF) order.add(pkg);
                            else return;
                            shelfSaved(h, self[0], order, all, pkg);
                        })
                        .change(d -> {
                            int at = order.indexOf(pkg), to = at + d;
                            if (at < 0 || to < 0 || to >= order.size()) return;
                            order.remove(at);
                            order.add(to, pkg);
                            shelfSaved(h, self[0], order, all, pkg);
                        })
                        .preview(P_SHELF, pkg));
            }
        });
        return self[0];
    }

    /** Saves the shelf and keeps the focus on the app that moved. */
    private static void shelfSaved(Host h, Page page, List<String> order, List<String[]> all, String pkg) {
        Prefs.setShelf(order);
        List<String[]> named = new ArrayList<>();
        for (String o : order) for (String[] app : all) if (app[0].equals(o)) { named.add(app); break; }
        h.setShelf(named);
        page.rebuild();
        for (int i = 0; i < page.rows.size(); i++) if (page.rows.get(i).arg.equals(pkg)) page.focus = i;
        h.changed();
    }

    // MARK: - Home Rows: OK shows or hides a row, ← → move it; the preset (first row) sets them all at once

    static boolean rowHidden(String id) {
        for (String e : HomeRows.layout()) if (HomeRows.id(e).equals(id)) return HomeRows.hidden(e);
        return false;
    }

    static void toggleRow(String id) {
        List<String> rows = HomeRows.layout();
        for (int i = 0; i < rows.size(); i++) {
            String e = rows.get(i);
            if (HomeRows.id(e).equals(id)) rows.set(i, HomeRows.hidden(e) ? id : "-" + id);
        }
        Prefs.setHomeRows(rows, "custom");
    }

    /** "#2" among the shown rows, or "Hidden". */
    private static String rowValue(String id) {
        int place = 1;
        for (String e : HomeRows.layout()) {
            if (HomeRows.id(e).equals(id)) return HomeRows.hidden(e) ? Ui.tr("Oculta", "Hidden") : place(place);
            if (!HomeRows.hidden(e)) place++;
        }
        return "";
    }

    static Page homeRows(Host h) {
        final Page[] self = new Page[1];
        self[0] = new Page("rows", () -> Ui.tr("Fileiras do Início", "Home Rows"),
                () -> Ui.tr("OK mostra ou oculta. ← → mudam a ordem.", "OK shows or hides. ← → change the order."), p -> {
            p.add(new Row(() -> Ui.tr("Predefinição", "Preset")).value(() -> HomeRows.presetName(Prefs.homePreset()))
                    .about(() -> Ui.tr("Arruma todas as fileiras de uma vez.", "Sets every row at once."))
                    .opens(() -> h.open(presets(h))).preview(P_ROWS, ""));
            for (String entry : HomeRows.layout()) {
                final String id = HomeRows.id(entry);
                p.add(new Row(() -> HomeRows.label(id)).value(() -> rowValue(id)).dim(() -> rowHidden(id))
                        .about(() -> {
                            if (rowHidden(id)) return Ui.tr("Oculta da aba Início. OK mostra.", "Hidden from the Home tab. OK shows it.");
                            String v = rowValue(id), n = v.startsWith("#") ? v.substring(1) : v;
                            return Ui.tr("Fileira " + n + " sob os apps. OK oculta.", "Row " + n + " under the apps. OK hides it.",
                                    "Fila " + n + " bajo las apps. OK la oculta.", "الصف " + n + " تحت التطبيقات. OK لإخفائه.");
                        })
                        .ok(() -> {
                            toggleRow(id);
                            h.changed();
                        })
                        .change(d -> {
                            List<String> rows = HomeRows.layout();
                            int at = -1;
                            for (int i = 0; i < rows.size(); i++) if (HomeRows.id(rows.get(i)).equals(id)) at = i;
                            int to = at + d;
                            if (at < 0 || to < 0 || to >= rows.size()) return;
                            rows.add(to, rows.remove(at));
                            Prefs.setHomeRows(rows, "custom");
                            self[0].rebuild();
                            self[0].focus = to + 1;
                            h.changed();
                        })
                        .preview(P_ROWS, id));
            }
        });
        return self[0];
    }

    /** The rows a preset shows, in order (the built-in order for "default"). */
    static List<String> presetRows(String preset) {
        List<String> rows = HomeRows.preset(preset);
        if (rows == null) return new ArrayList<>(java.util.Arrays.asList(HomeRows.DEFAULT));
        return rows;
    }

    static Page presets(Host h) {
        int current = Math.max(0, java.util.Arrays.asList(HomeRows.PRESETS).indexOf(Prefs.homePreset()));
        return new Page("presets", () -> Ui.tr("Predefinição", "Preset"), () -> Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."), p -> {
            for (final String preset : HomeRows.PRESETS) {
                p.add(new Row(() -> HomeRows.presetName(preset)).checked(() -> Prefs.homePreset().equals(preset))
                        .about(() -> {
                            List<String> names = new ArrayList<>();
                            for (String e : presetRows(preset)) if (!HomeRows.hidden(e)) names.add(HomeRows.label(HomeRows.id(e)));
                            return count(names.size(), "fileira", "fileiras", "row", "rows", "fila", "filas", "صف واحد", "صفوف") + ": "
                                    + android.text.TextUtils.join(Ui.ar ? "، " : ", ", names);
                        })
                        .ok(() -> {
                            Prefs.setHomeRows(HomeRows.preset(preset), preset);
                            h.back();
                        })
                        .preview(P_ROWS, "preset:" + preset));
            }
        }).focus(current);
    }

    // MARK: - Remote Buttons (TCL's app keys)

    static String keyLabel(int i) {
        switch (i) {
            case 0: return Ui.tr("Botão Netflix", "Netflix button");
            case 1: return Ui.tr("Botão YouTube", "YouTube button");
            case 2: return Ui.tr("Botão Prime Video", "Prime Video button");
            default: return Ui.tr("Botão TCL Channel", "TCL Channel button");
        }
    }

    /** What the key opens, by name ("Nothing", or "Not installed" when that app is gone). */
    static String keyApp(Host h, int i) {
        String pkg = Prefs.keyApp(Prefs.KEYS[i]);
        int at = h.appPkgs().indexOf(pkg);
        return at >= 0 ? h.appLabels().get(at) : pkg.isEmpty() ? Ui.tr("Nada", "Nothing") : Ui.tr("Não instalado", "Not installed");
    }

    static String remoteSummary(Host h) {
        if (!RemoteKeys.supported()) return Ui.tr("Só nos controles da TCL", "Only on TCL remotes");
        String[] keys = {"Netflix", "YouTube", "Prime Video"};
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) parts.add(keys[i] + ": " + Ui.isolate(keyApp(h, i)));
        return join(parts);
    }

    static Page remote(Host h) {
        return new Page("remote", () -> Ui.tr("Botões do Controle", "Remote Buttons"),
                () -> Ui.tr("OK escolhe o app. ← → trocam.", "OK picks the app. ← → change it."), p -> {
            for (int i = 0; i < Prefs.KEYS.length; i++) {
                final int key = i;
                p.add(new Row(() -> keyLabel(key)).value(() -> keyApp(h, key))
                        .about(() -> {
                            if (!RemoteKeys.supported()) return Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                                    "These are the TCL remote's buttons: on this TV, the buttons work as they always did.");
                            String pkg = Prefs.keyApp(Prefs.KEYS[key]);
                            if (pkg.isEmpty()) return Ui.tr("O botão faz o que a TV sempre fez.", "The button does what the TV always did.");
                            String app = keyApp(h, key);
                            return Ui.tr("Abre o " + app + ", até de dentro de outros apps.", "Opens " + app + ", even from inside other apps.",
                                    "Abre " + app + ", incluso desde otras apps.", "يفتح " + Ui.isolate(app) + "، حتى من داخل تطبيقات أخرى.");
                        })
                        .change(d -> {
                            List<String> pkgs = h.appPkgs();
                            int n = pkgs.size(), at = pkgs.indexOf(Prefs.keyApp(Prefs.KEYS[key]));
                            if (n == 0) return;
                            int to = at < 0 ? (d > 0 ? 0 : n - 1) : (at + d + n) % n;
                            Prefs.setKeyApp(Prefs.KEYS[key], pkgs.get(to));
                            h.changed();
                        })
                        .opens(() -> h.open(appPicker(h, key)))
                        .preview(P_REMOTE, key));
            }
        });
    }

    /** The app a remote button opens: every TV app, after "Nothing"; the one in use checked. */
    static Page appPicker(Host h, int key) {
        int current = Math.max(0, h.appPkgs().indexOf(Prefs.keyApp(Prefs.KEYS[key])));
        return new Page("app" + key, () -> keyLabel(key), () -> Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."), p -> {
            List<String> pkgs = h.appPkgs(), labels = h.appLabels();
            for (int i = 0; i < pkgs.size(); i++) {
                final String pkg = pkgs.get(i), label = labels.get(i);
                p.add(new Row(() -> label).checked(() -> Prefs.keyApp(Prefs.KEYS[key]).equals(pkg))
                        .about(() -> pkg.isEmpty() ? Ui.tr("O botão faz o que a TV sempre fez.", "The button does what the TV always did.")
                                : Ui.tr("O botão abre o " + label + ".", "The button opens " + label + ".", "El botón abre " + label + ".",
                                "يفتح الزر " + Ui.isolate(label) + "."))
                        .ok(() -> {
                            Prefs.setKeyApp(Prefs.KEYS[key], pkg);
                            h.back();
                        })
                        .preview(P_REMOTE, key).arg(pkg));
            }
        }).focus(current);
    }

    // MARK: - Match Centre: your teams, the leagues, alerts and quiet hours (Prefs win over config.properties)

    /** The leagues ESPN covers, by code, with their names. */
    static final String[][] LEAGUE_LIST = {
        {"uefa.champions", "UEFA Champions League"}, {"uefa.europa", "UEFA Europa League"},
        {"uefa.europa.conf", "UEFA Conference League"}, {"eng.1", "Premier League"}, {"esp.1", "LaLiga"},
        {"ita.1", "Serie A"}, {"ger.1", "Bundesliga"}, {"fra.1", "Ligue 1"}, {"por.1", "Liga Portugal"},
        {"ned.1", "Eredivisie"}, {"ksa.1", "Saudi Pro League"}, {"bra.1", "Brasileirão"},
        {"arg.1", "Liga Profesional (Argentina)"}, {"mex.1", "Liga MX"}, {"usa.1", "MLS"},
        {"conmebol.libertadores", "Copa Libertadores"}, {"eng.fa", "FA Cup"}, {"esp.copa_del_rey", "Copa del Rey"},
        {"fifa.world", "FIFA World Cup"}, {"afc.champions", "AFC Champions League"},
    };

    /** Your teams as saved: names (or parts of names, from config.properties) a team's name contains. */
    static List<String> teamWants() {
        List<String> out = new ArrayList<>();
        for (String t : Prefs.teams().split(",")) if (!t.trim().isEmpty()) out.add(t.trim());
        return out;
    }

    static String leagueName(String code) {
        for (String[] l : LEAGUE_LIST) if (l[0].equals(code)) return l[1];
        return code;
    }

    private static String teamsValue() {
        int teams = teamWants().size();
        return teams == 0 ? Ui.tr("Nenhum", "None") : count(teams, "time", "times", "team", "teams", "equipo", "equipos", "فريق واحد", "فرق");
    }

    private static String leaguesValue() {
        return count(Matches.leagues().size(), "campeonato", "campeonatos", "league", "leagues", "liga", "ligas", "بطولة واحدة", "بطولات");
    }

    static String alertsValue() {
        String alerts = Prefs.matchAlerts();
        return alerts.equals("goals") ? Ui.tr("Só gols", "Goals only") : alerts.equals("off") ? Ui.tr("Desligado", "Off") : Ui.tr("Todos", "All");
    }

    static String matchSummary() {
        List<String> parts = new ArrayList<>();
        parts.add(teamsValue());
        parts.add(leaguesValue());
        String alerts = Prefs.matchAlerts();
        parts.add(alerts.equals("goals") ? Ui.tr("Só gols", "Goals only") : alerts.equals("off") ? Ui.tr("Sem alertas", "No alerts")
                : Ui.tr("Todos os alertas", "All alerts"));
        if (Prefs.quietFrom() >= 0) parts.add(Ui.tr("Silêncio ", "Quiet ", "Silencio ", "هدوء ") + hour(Prefs.quietFrom()) + "–" + hour(Prefs.quietUntil()));
        return join(parts);
    }

    private static String quietAbout() {
        if (Prefs.quietFrom() < 0) return Ui.tr("Os alertas podem aparecer a qualquer hora.", "Alerts can show at any hour.");
        String from = hour(Prefs.quietFrom()), until = hour(Prefs.quietUntil());
        return Ui.tr("Nada aparece das " + from + " às " + until + "; o placar continua sendo acompanhado.",
                "Nothing pops up from " + from + " to " + until + "; scores are still followed.",
                "Nada aparece de " + from + " a " + until + "; el marcador se sigue.",
                "لا يظهر شيء من " + from + " إلى " + until + "، وتبقى النتائج متابَعة.");
    }

    static Page matchCentre(Host h) {
        return new Page("matches", () -> Ui.tr("Central de Jogos", "Match Centre"), () -> Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), p -> {
            p.add(new Row(() -> Ui.tr("Seus times", "Your teams")).value(SettingsLists::teamsValue)
                    .about(() -> Ui.tr("Os jogos deles vêm primeiro, com lembrete e gols.", "Their matches come first, with a reminder and goals."))
                    .opens(() -> h.open(checklist(h, 0))).lines(() -> named(teamWants())));
            p.add(new Row(() -> Ui.tr("Campeonatos", "Leagues")).value(SettingsLists::leaguesValue)
                    .about(() -> Ui.tr("A fileira de jogos segue esses campeonatos.", "The Matches row follows these leagues."))
                    .opens(() -> h.open(checklist(h, 1))).lines(SettingsLists::leagueLines));
            p.add(new Row(() -> Ui.tr("Alertas", "Alerts")).value(SettingsLists::alertsValue).about(() -> {
                String alerts = Prefs.matchAlerts();
                if (alerts.equals("goals")) return Ui.tr("Só um aviso rápido quando alguém marca.", "Only a quick flash when someone scores.");
                if (alerts.equals("off")) return Ui.tr("Nada aparece por cima do que você assiste.", "Nothing pops up over what you watch.");
                return Ui.tr("Um lembrete 10 min antes, o placar ao vivo e cada gol.", "A reminder 10 min before, the live score and every goal.");
            }).change(d -> {
                Prefs.setMatchAlerts(next(ALERTS, Prefs.matchAlerts(), d));
                matchChanged(h);
            }).preview(P_MATCH, "alerts"));
            p.add(new Row(() -> Ui.tr("Silêncio a partir de", "Quiet hours from"))
                    .value(() -> Prefs.quietFrom() < 0 ? Ui.tr("Nunca", "Never") : hour(Prefs.quietFrom()))
                    .about(SettingsLists::quietAbout)
                    .change(d -> {
                        int from = Prefs.quietFrom() + d;       // -1 (never), then 0 to 23
                        Prefs.setQuietFrom(from < -1 ? 23 : from > 23 ? -1 : from);
                        matchChanged(h);
                    }).preview(P_MATCH, "quiet"));
            p.add(new Row(() -> Ui.tr("Silêncio até", "Quiet hours until")).value(() -> hour(Prefs.quietUntil()))
                    .locked(() -> Prefs.quietFrom() < 0)
                    .about(() -> Prefs.quietFrom() < 0 ? Ui.tr("Escolha antes quando o silêncio começa.", "Pick when quiet hours start first.") : quietAbout())
                    .change(d -> {
                        Prefs.setQuietUntil((Prefs.quietUntil() + d + 24) % 24);
                        matchChanged(h);
                    }).preview(P_MATCH, "quiet"));
        });
    }

    private static void matchChanged(Host h) {
        MatchWatch.schedule(h.context());
        h.changed();
    }

    private static List<String[]> named(List<String> names) {
        List<String[]> out = new ArrayList<>();
        for (String n : names) out.add(new String[] {n, ""});
        if (out.isEmpty()) out.add(new String[] {Ui.tr("Nenhum ainda.", "None yet."), ""});
        return out;
    }

    private static List<String[]> leagueLines() {
        List<String> names = new ArrayList<>();
        for (String code : Matches.leagues()) names.add(leagueName(code));
        return named(names);
    }

    // MARK: - Your teams (0) or the leagues (1): checked first. Teams come from the leagues' ESPN lists, a moment later.

    private static boolean checked(int kind, String id) {
        return kind == 0 ? Matches.mine(id) : Matches.leagues().contains(id);
    }

    static Page checklist(Host h, int kind) {
        final List<String[]> items = new ArrayList<>();       // {id, name, detail}
        final boolean[] loading = {kind == 0};
        final Page[] self = new Page[1];
        self[0] = new Page(kind == 0 ? "teams" : "leagues", () -> kind == 0 ? Ui.tr("Seus times", "Your teams") : Ui.tr("Campeonatos", "Leagues"), () -> {
            if (kind == 0 && loading[0]) return Ui.tr("Buscando os times dos campeonatos…", "Fetching the leagues' teams…");
            if (kind == 0 && items.isEmpty()) return Ui.tr("Nenhum time: escolha os campeonatos primeiro.", "No teams: pick the leagues first.");
            return Ui.tr("OK põe ou tira.", "OK adds or removes.");
        }, p -> {
            for (String[] item : items) {
                final String id = item[0];
                p.add(new Row(() -> item[1]).value(() -> item[2]).checked(() -> checked(kind, id)).dim(() -> !checked(kind, id))
                        .about(() -> checked(kind, id)
                                ? kind == 0 ? Ui.tr("Escolhido: os jogos vêm primeiro, com alertas.", "Picked: its matches come first, with alerts.")
                                : Ui.tr("Escolhido: os jogos aparecem na fileira.", "Picked: its matches show in the row.")
                                : Ui.tr("OK põe na lista.", "OK adds it."))
                        .ok(() -> {
                            toggleChecked(h, kind, item);
                            h.changed();
                        })
                        .lines(() -> kind == 0 ? named(teamWants()) : leagueLines()).arg(id));
            }
        });
        if (kind == 1) {
            for (String code : Matches.leagues()) {
                boolean listed = false;
                for (String[] l : LEAGUE_LIST) listed |= l[0].equals(code);
                if (!listed) items.add(new String[] {code, code, ""});
            }
            for (String[] l : LEAGUE_LIST) items.add(new String[] {l[0], l[1], l[0]});
            return self[0];
        }
        setTeams(items, Matches.get(h.context()).fixtureTeams());
        final List<String> leagues = Matches.leagues();
        LOADER.execute(() -> {
            final List<String[]> found = Matches.teams(leagues);
            h.later(() -> {
                loading[0] = false;
                Page page = self[0];
                String focused = page.focused() == null ? null : page.focused().arg;
                if (!found.isEmpty()) setTeams(items, found);
                if (!h.showing(page)) return;
                page.rebuild();
                page.focus = 0;
                for (int i = 0; focused != null && i < page.rows.size(); i++) if (page.rows.get(i).arg.equals(focused)) page.focus = i;
                h.changed();
            });
        });
        return self[0];
    }

    /** Your teams first (also any that no list has), then the rest by name. */
    private static void setTeams(List<String[]> items, List<String[]> teams) {
        List<String[]> mine = new ArrayList<>(), rest = new ArrayList<>();
        for (String[] t : teams) (Matches.mine(t[0]) ? mine : rest).add(new String[] {t[0], t[0], t[1]});
        for (String want : teamWants()) {
            boolean found = false;
            for (String[] t : mine) found |= t[0].toLowerCase(Locale.ROOT).contains(want.toLowerCase(Locale.ROOT));
            if (!found) mine.add(new String[] {want, want, ""});
        }
        Collections.sort(rest, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        items.clear();
        items.addAll(mine);
        items.addAll(rest);
    }

    /** A team or league in or out; saved at once (Matches marks your matches again, the alarm follows). */
    private static void toggleChecked(Host h, int kind, String[] item) {
        boolean on = checked(kind, item[0]);
        if (kind == 0) {
            List<String> wants = new ArrayList<>();
            String name = item[0].toLowerCase(Locale.ROOT);
            for (String w : teamWants()) if (!on || !name.contains(w.toLowerCase(Locale.ROOT))) wants.add(w);
            if (!on) wants.add(item[0]);
            Prefs.setTeams(wants);
        } else {
            List<String> codes = new ArrayList<>(Matches.leagues());
            if (on) codes.remove(item[0]);
            else codes.add(item[0]);
            Prefs.setLeagues(codes);
        }
        Matches.get(h.context()).settingsChanged();
        MatchWatch.schedule(h.context());
    }
}
