package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Watchme+ Settings, Sync: the status, starting to sync or joining with a code, adding another device (the code big, a
 * 10-minute countdown and a QR code), the devices, and disconnecting or deleting the synced data (each asks first, Cancel
 * focused). Drawn by CinemaSettings in CinemaView's 1920×1080 space, with its row metrics; right to left mirrors it.
 */
final class SyncScreens {
    private static final int MAIN = 0, CODE = 1, JOIN = 2, CONFIRM = 3;
    private static final int R_START = 0, R_JOIN = 1, R_NOW = 2, R_ADD = 3, R_DISCONNECT = 4, R_DELETE = 5;
    private static final int KEY_COLUMNS = 8, KEY_W = 100, KEY_H = 70, KEY_GAP = 10;
    /** Pairing codes use these (no 0, O, 1, I, L), then the delete key. */
    private static final String KEYS = SyncRules.CODE_ALPHABET;
    private static final int DELETE_KEY = KEYS.length();
    private static final int QR_SIZE = 380;
    private static final int MAX_DEVICES = 5;

    private final CinemaView v;
    private final CinemaSettings host;
    private boolean open, busy, tickPosted;
    private int screen, focus, gen;
    private String message = "";
    private List<Sync.Device> devices = new ArrayList<>();
    // Add another device
    private String code;
    private long codeAt;
    private Bitmap qr;
    // Join with a code
    private final StringBuilder entry = new StringBuilder();
    private int keyIndex;
    // Disconnect / Delete
    private int confirmKind, confirmFocus;
    private Paint codeFont, bigFont, slotFont, keyFont, smallKeyFont, bodyFont;
    private final Paint plain = new Paint();
    private final Runnable tick = this::onTick;

    private void onTick() {
        tickPosted = false;
        if (open && screen == CODE) v.invalidate();
    }

    SyncScreens(CinemaView v, CinemaSettings host) {
        this.v = v;
        this.host = host;
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        gen++;
        open = true;
        screen = MAIN;
        focus = 0;
        busy = false;
        message = "";
        devices = new ArrayList<>();
        if (Sync.isLinked()) loadDevices();
    }

    void close() {
        gen++;
        open = false;
        busy = false;
        recycleQr();
        code = null;
        entry.setLength(0);
    }

    private void recycleQr() {
        if (qr != null) qr.recycle();
        qr = null;
    }

    // MARK: - Actions (each answers on the main thread)

    private void loadDevices() {
        final int g = gen;
        Sync.devices((list, error) -> {
            if (g != gen || list == null) return;
            devices = list;
            v.invalidate();
        });
    }

    private void startSyncing() {
        final int g = gen;
        busy = true;
        message = "";
        Sync.startSpace((ok, error) -> {
            if (g != gen) return;
            busy = false;
            message = error == null ? "" : errorText(error);
            focus = 0;
            if (error == null) loadDevices();
            v.invalidate();
        });
    }

    private void join() {
        final int g = gen;
        busy = true;
        message = "";
        Sync.join(entry.toString(), (ok, error) -> {
            if (g != gen) return;
            busy = false;
            if (error == null) {
                screen = MAIN;
                focus = 0;
                entry.setLength(0);
                loadDevices();
            } else {
                message = errorText(error);
                if ("wrong".equals(error)) entry.setLength(0);
            }
            v.invalidate();
        });
    }

    private void showCode() {
        final int g = gen;
        screen = CODE;
        busy = true;
        message = "";
        code = null;
        recycleQr();
        Sync.newCode((newCode, error) -> {
            if (g != gen) return;
            busy = false;
            if (error != null || newCode == null) {
                message = errorText(error == null ? "error" : error);
            } else {
                code = SyncRules.normalizeCode(newCode);
                codeAt = SystemClock.elapsedRealtime();
                qr = qrBitmap(SyncRules.joinUrl(code));
            }
            v.invalidate();
        });
    }

    private void confirmed() {
        final int g = gen;
        busy = true;
        message = "";
        Sync.Callback<Boolean> done = (ok, error) -> {
            if (g != gen) return;
            busy = false;
            screen = MAIN;
            focus = 0;
            message = error == null ? "" : errorText(error);
            if (error == null && Sync.isLinked()) loadDevices();
            else devices = new ArrayList<>();
            v.invalidate();
        };
        if (confirmKind == 0) Sync.leave(done);
        else Sync.deleteData(done);
    }

    private static String errorText(String error) {
        switch (error) {
            case "wrong":
                return Ui.tr("Código errado, expirado ou já usado. Peça um novo no outro aparelho.",
                        "That code is wrong, expired or already used. Ask for a new one on the other device.",
                        "Ese código es incorrecto, ha caducado o ya se usó. Pide uno nuevo en el otro dispositivo.",
                        "الرمز خاطئ أو منتهي أو مستخدم من قبل. اطلب رمزاً جديداً من الجهاز الآخر.");
            case "too_many_attempts":
                return Ui.tr("Tentativas demais. Espere 10 minutos e tente de novo.", "Too many wrong tries. Wait 10 minutes, then try again.",
                        "Demasiados intentos. Espera 10 minutos e inténtalo de nuevo.", "محاولات خاطئة كثيرة. انتظر 10 دقائق ثم حاول مجدداً.");
            case "network":
                return Ui.tr("Sem conexão com o serviço de sincronização. Verifique a internet e tente de novo.",
                        "Could not reach the sync service. Check your connection and try again.",
                        "No se pudo contactar con el servicio de sincronización. Revisa la conexión e inténtalo de nuevo.",
                        "تعذر الوصول إلى خدمة المزامنة. تحقق من الاتصال وحاول مجدداً.");
            default:
                return Ui.tr("Não deu certo. Tente de novo.", "That didn't work. Please try again.",
                        "No funcionó. Inténtalo de nuevo.", "لم تنجح العملية. حاول مرة أخرى.");
        }
    }

    // MARK: - Keys

    boolean onKey(int code) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (screen == MAIN) close();
            else {
                gen++;     // a pending answer no longer counts
                busy = false;
                screen = MAIN;
                message = "";
                focus = 0;
                if (Sync.isLinked() && devices.isEmpty()) loadDevices();
            }
            return true;
        }
        if (busy) return true;
        switch (screen) {
            case CODE: return codeKey(code);
            case JOIN: return joinKey(code);
            case CONFIRM: return confirmKey(code);
            default: return mainKey(code);
        }
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private int[] rows() {
        return Sync.isLinked() ? new int[] {R_NOW, R_ADD, R_DISCONNECT, R_DELETE} : new int[] {R_START, R_JOIN};
    }

    private boolean mainKey(int code) {
        int[] rows = rows();
        focus = Math.min(focus, rows.length - 1);
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            focus = Math.max(0, focus - 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            focus = Math.min(rows.length - 1, focus + 1);
        } else if (isOk(code)) {
            switch (rows[focus]) {
                case R_START: startSyncing(); break;
                case R_JOIN:
                    screen = JOIN;
                    entry.setLength(0);
                    message = "";
                    keyIndex = 0;
                    break;
                case R_NOW: Sync.syncNow(); break;
                case R_ADD: showCode(); break;
                case R_DISCONNECT:
                case R_DELETE:
                    screen = CONFIRM;
                    confirmKind = rows[focus] == R_DISCONNECT ? 0 : 1;
                    confirmFocus = 0;      // Cancel
                    break;
                default:
            }
        }
        return true;
    }

    private boolean codeKey(int code) {
        if (isOk(code) && !busy && (this.code == null || remainingMs() <= 0)) showCode();
        return true;
    }

    private boolean joinKey(int key) {
        int rows = (KEYS.length() + 1 + KEY_COLUMNS - 1) / KEY_COLUMNS, row = keyIndex / KEY_COLUMNS, col = keyIndex % KEY_COLUMNS;
        int count = KEYS.length() + 1;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:      // the letters stay left to right in every language
                if (keyIndex > 0) keyIndex--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (keyIndex < count - 1) keyIndex++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row > 0) keyIndex -= KEY_COLUMNS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < rows - 1) keyIndex = Math.min(count - 1, keyIndex + KEY_COLUMNS);
                break;
            case KeyEvent.KEYCODE_DEL:
                delete();
                break;
            default:
                if (isOk(key)) {
                    if (keyIndex == DELETE_KEY) delete();
                    else type(KEYS.charAt(keyIndex));
                } else if (key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z) {
                    type((char) ('A' + key - KeyEvent.KEYCODE_A));      // a remote with letters
                } else if (key >= KeyEvent.KEYCODE_2 && key <= KeyEvent.KEYCODE_9) {
                    type((char) ('2' + key - KeyEvent.KEYCODE_2));
                }
        }
        return true;
    }

    private void type(char ch) {
        ch = Character.toUpperCase(ch);
        if (KEYS.indexOf(ch) < 0 || entry.length() >= SyncRules.CODE_LENGTH) return;
        message = "";
        entry.append(ch);
        if (entry.length() == SyncRules.CODE_LENGTH) join();      // eight letters: it goes by itself
    }

    private void delete() {
        if (entry.length() > 0) entry.setLength(entry.length() - 1);
        message = "";
    }

    private boolean confirmKey(int key) {
        switch (Ui.dpad(key)) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                confirmFocus = 1 - confirmFocus;
                break;
            default:
                if (isOk(key)) {
                    if (confirmFocus == 1) confirmed();
                    else screen = MAIN;
                }
        }
        return true;
    }

    // MARK: - Drawing

    private void fonts() {
        if (codeFont != null && codeFont.getTextSize() == 128 * v.k) return;
        float k = v.k;
        codeFont = Ui.text(Ui.displaySemibold, 128 * k, 0xFFFFFFFF);
        codeFont.setLetterSpacing(0.06f);
        bigFont = Ui.text(Ui.semibold, 44 * k, 0xFFFFFFFF);
        slotFont = Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF);
        slotFont.setTextAlign(Paint.Align.CENTER);
        keyFont = Ui.text(Ui.semibold, 36 * k, 0xFFFFFFFF);
        keyFont.setTextAlign(Paint.Align.CENTER);
        smallKeyFont = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        smallKeyFont.setTextAlign(Paint.Align.CENTER);
        bodyFont = Ui.text(Ui.regular, 28 * k, 0xCCFFFFFF);
    }

    void draw(Canvas canvas) {
        fonts();
        float k = v.k, x = CinemaView.L * k;
        String title = screen == CODE ? Ui.tr("Vincular um aparelho visitante", "Link a guest device", "Vincular un dispositivo invitado", "ربط جهاز ضيف")
                : screen == JOIN ? Ui.tr("Entrar com um código", "Join with a code", "Unirse con un código", "الانضمام برمز")
                : screen == CONFIRM ? (confirmKind == 0 ? Ui.tr("Desconectar esta TV?", "Disconnect this TV?", "¿Desconectar este TV?", "فصل هذا التلفاز؟")
                        : Ui.tr("Apagar todos os dados sincronizados?", "Delete all synced data?", "¿Borrar todos los datos sincronizados?",
                                "حذف كل البيانات المتزامنة؟"))
                : Ui.tr("Sincronização", "Sync", "Sincronización", "المزامنة");
        Ui.text(canvas, title, x, (screen == CONFIRM ? 300 : 214) * k, host.headerFont(), v.w);
        switch (screen) {
            case CODE: drawCode(canvas); break;
            case JOIN: drawJoin(canvas); break;
            case CONFIRM: drawConfirm(canvas); break;
            default: drawMain(canvas);
        }
        if (busy) {
            Ui.spinner(canvas, v.w - 120 * k, 190 * k, 22 * k, SystemClock.uptimeMillis() / 1000f, spinnerStroke());
            v.kick();
        }
    }

    private Paint spinnerStroke() {
        plain.setAntiAlias(true);
        plain.setStyle(Paint.Style.STROKE);
        plain.setStrokeWidth(4 * v.k);
        plain.setColor(0xCCFFFFFF);
        return plain;
    }

    private void drawMain(Canvas canvas) {
        float k = v.k, x = CinemaView.L * k;
        int[] rows = rows();
        String first = Sync.isLinked() ? Sync.firstSyncLine() : null;
        Ui.text(canvas, first == null ? Sync.statusLine() : Sync.statusLine() + "  ·  " + first, x, 262 * k, bodyFont, v.w);
        float top = 290;
        for (int i = 0; i < rows.length; i++) {
            boolean focused = i == focus && !busy;
            boolean warn = rows[i] == R_DISCONNECT || rows[i] == R_DELETE;
            host.drawRow(canvas, (top + i * CinemaSettings.ROW_H) * k, focused, rowLabel(rows[i]), Ui.rtl ? "‹" : "›", false, warn);
        }
        float y = top + rows.length * CinemaSettings.ROW_H + 50;
        if (!message.isEmpty()) {
            drawWrapped(canvas, message, x, y * k, 0xFFFFB4A8, 2);
            y += 90;
        }
        if (Sync.isLinked() && !devices.isEmpty()) {
            Ui.text(canvas, Ui.tr("Aparelhos", "Devices", "Dispositivos", "الأجهزة"), x, y * k, host.labelFont(), v.w);
            for (int i = 0; i < Math.min(MAX_DEVICES, devices.size()); i++) {
                Sync.Device d = devices.get(i);
                String line = "•  " + Ui.isolate(d.name.isEmpty() ? "?" : d.name)
                        + (d.me ? "  " + Ui.tr("(esta TV)", "(this TV)", "(este TV)", "(هذا التلفاز)") : "");
                Ui.text(canvas, line, x, (y + 46 + i * 44) * k, bodyFont, v.w);
            }
        } else if (!Sync.isLinked() && message.isEmpty()) {
            drawWrapped(canvas, Ui.tr("Sem conta: os aparelhos se ligam por um código.\nVale para Continuar assistindo, Minha Lista, notas e alguns ajustes.",
                    "No account: devices link with a code.\nIt covers Continue watching, My List, ratings and a few settings.",
                    "Sin cuenta: los dispositivos se vinculan con un código.\nIncluye Seguir viendo, Mi lista, valoraciones y algunos ajustes.",
                    "بدون حساب: تُربط الأجهزة برمز.\nيشمل متابعة المشاهدة وقائمتي والتقييمات وبعض الإعدادات."), x, y * k, 0x99FFFFFF, 2);
        }
    }

    private static String rowLabel(int row) {
        switch (row) {
            case R_START: return Ui.tr("Começar a sincronizar nesta TV", "Start syncing on this TV", "Empezar a sincronizar en este TV",
                    "بدء المزامنة على هذا التلفاز");
            case R_JOIN: return Ui.tr("Entrar com um código", "Join with a code", "Unirse con un código", "الانضمام برمز");
            case R_NOW: return Ui.tr("Sincronizar agora", "Sync now", "Sincronizar ahora", "مزامنة الآن");
            case R_ADD: return Ui.tr("Vincular um aparelho visitante", "Link a guest device", "Vincular un dispositivo invitado", "ربط جهاز ضيف");
            case R_DISCONNECT: return Ui.tr("Desconectar esta TV", "Disconnect this TV", "Desconectar este TV", "فصل هذا التلفاز");
            default: return Ui.tr("Apagar todos os dados sincronizados", "Delete all synced data", "Borrar todos los datos sincronizados",
                    "حذف كل البيانات المتزامنة");
        }
    }

    /** Lines split at \n, left margin x (right to left: mirrored), the first baseline at y. */
    private void drawWrapped(Canvas canvas, String text, float x, float y, int color, int maxLines) {
        bodyFont.setColor(color);
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length && i < maxLines + 3; i++) Ui.text(canvas, lines[i], x, y + i * 40 * v.k, bodyFont, v.w);
        bodyFont.setColor(0xCCFFFFFF);
    }

    private long remainingMs() {
        return SyncRules.PAIRING_TTL_MS - (SystemClock.elapsedRealtime() - codeAt);
    }

    private void drawCode(Canvas canvas) {
        float k = v.k, x = CinemaView.L * k;
        if (code == null) {
            Ui.text(canvas, message.isEmpty() ? Ui.tr("Gerando o código…", "Making the code…", "Creando el código…", "جارٍ إنشاء الرمز…") : message,
                    x, 400 * k, bigFont, v.w);
            return;
        }
        long left = Math.max(0, remainingMs());
        String shown = code.substring(0, 4) + "  " + code.substring(4);
        float w = codeFont.measureText(shown);
        float codeX = Ui.rtl ? v.w - x - w : x;
        canvas.drawText(shown, codeX, 470 * k, codeFont);
        String time = Ui.digits((left / 60_000) + ":" + String.format(java.util.Locale.ROOT, "%02d", (left / 1000) % 60));
        Ui.text(canvas, left > 0
                ? Ui.tr("Expira em " + time, "Expires in " + time, "Caduca en " + time, "ينتهي خلال " + time)
                : Ui.tr("Expirou. Aperte OK para gerar outro.", "Expired. Press OK for a new one.", "Caducó. Pulsa OK para uno nuevo.",
                        "انتهت الصلاحية. اضغط OK لرمز جديد."), x, 540 * k, bigFont, v.w);
        drawWrapped(canvas, Ui.tr("No outro aparelho, abra o {catalog} (ou leia o QR code),\nvá em Ajustes, Sincronização e entre com este código.",
                "On the other device, open {catalog} (or scan the QR code),\nthen Settings, Sync, Join with a code, and enter this one.",
                "En el otro dispositivo, abre {catalog} (o escanea el código QR),\nluego Ajustes, Sincronización y escribe este código.",
                "على الجهاز الآخر افتح {catalog} (أو امسح رمز QR)،\nثم الإعدادات ثم المزامنة ثم الانضمام برمز وأدخل هذا الرمز."), x, 620 * k, 0xCCFFFFFF, 2);
        if (!message.isEmpty()) drawWrapped(canvas, message, x, 760 * k, 0xFFFFB4A8, 2);
        if (qr != null && left > 0) {
            float size = QR_SIZE * k, qx = Ui.rtl ? x : v.w - x - size, qy = 330 * k;
            v.rect.set(qx, qy, qx + size, qy + size);
            plain.setFilterBitmap(false);
            canvas.drawBitmap(qr, null, v.rect, plain);
        }
        if (left > 0 && !tickPosted) {
            tickPosted = true;
            v.postDelayed(tick, 1000);
        }
    }

    private void drawJoin(Canvas canvas) {
        float k = v.k;
        float slotW = 76, slotH = 100, gap = 10, dash = 36;
        float rowW = SyncRules.CODE_LENGTH * (slotW + gap) - gap + dash;
        float panelW = KEY_COLUMNS * (KEY_W + KEY_GAP) - KEY_GAP;
        float left = Ui.rtl ? v.w - CinemaView.L * k - Math.max(rowW, panelW) * k : CinemaView.L * k;
        Ui.text(canvas, Ui.tr("No outro aparelho: Ajustes, Sincronização, Vincular um aparelho visitante.",
                "On the other device: Settings, Sync, Link a guest device.",
                "En el otro dispositivo: Ajustes, Sincronización, Vincular un dispositivo invitado.",
                "على الجهاز الآخر: الإعدادات ثم المزامنة ثم ربط جهاز ضيف."), CinemaView.L * k, 262 * k, bodyFont, v.w);
        float sx = left;
        for (int i = 0; i < SyncRules.CODE_LENGTH; i++) {
            if (i == 4) sx += dash * k;
            boolean current = i == entry.length();
            v.rect.set(sx, 300 * k, sx + slotW * k, (300 + slotH) * k);
            v.fill.setColor(current ? 0x55FFFFFF : 0x26FFFFFF);
            canvas.drawRoundRect(v.rect, 12 * k, 12 * k, v.fill);
            if (i < entry.length()) canvas.drawText(String.valueOf(entry.charAt(i)), v.rect.centerX(), v.rect.centerY() + Ui.halfCap(slotFont), slotFont);
            sx += (slotW + gap) * k;
        }
        for (int i = 0; i <= KEYS.length(); i++) {
            float kx = left + (i % KEY_COLUMNS) * (KEY_W + KEY_GAP) * k, ky = (440 + (i / KEY_COLUMNS) * (KEY_H + KEY_GAP)) * k;
            v.rect.set(kx, ky, kx + KEY_W * k, ky + KEY_H * k);
            boolean focused = i == keyIndex && !busy;
            v.fill.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
            canvas.drawRoundRect(v.rect, 12 * k, 12 * k, v.fill);
            Paint f = i == DELETE_KEY ? smallKeyFont : keyFont;
            f.setColor(focused ? 0xFF0B0B0D : 0xFFFFFFFF);
            String label = i == DELETE_KEY ? Ui.tr("Apagar", "Delete", "Borrar", "حذف") : String.valueOf(KEYS.charAt(i));
            canvas.drawText(label, v.rect.centerX(), v.rect.centerY() + Ui.halfCap(f), f);
        }
        float below = 440 + 4 * (KEY_H + KEY_GAP) + 24;
        if (!message.isEmpty()) drawWrapped(canvas, message, CinemaView.L * k, below * k, 0xFFFFB4A8, 2);
        else if (busy) Ui.text(canvas, Ui.tr("Entrando…", "Joining…", "Uniéndose…", "جارٍ الانضمام…"), CinemaView.L * k, below * k, bodyFont, v.w);
    }

    private void drawConfirm(Canvas canvas) {
        float k = v.k, x = CinemaView.L * k;
        String[] lines = confirmKind == 0
                ? new String[] {
                    Ui.tr("Esta TV deixa de sincronizar.", "This TV stops syncing.", "Este TV deja de sincronizar.", "يتوقف هذا التلفاز عن المزامنة."),
                    Ui.tr("O que está nela fica como está.", "What is on it stays as it is.", "Lo que tiene se queda como está.", "ما عليه يبقى كما هو."),
                    Ui.tr("Os outros aparelhos continuam com os dados deles.", "Your other devices keep their data and keep syncing.",
                            "Tus otros dispositivos conservan sus datos y siguen sincronizando.", "تحتفظ أجهزتك الأخرى ببياناتها وتواصل المزامنة.")}
                : new String[] {
                    Ui.tr("Remove do serviço Continuar assistindo, Minha Lista, notas e ajustes.",
                            "Removes Continue watching, My List, ratings and settings from the sync service.",
                            "Quita Seguir viendo, Mi lista, valoraciones y ajustes del servicio de sincronización.",
                            "يزيل متابعة المشاهدة وقائمتي والتقييمات والإعدادات من خدمة المزامنة."),
                    Ui.tr("Cada aparelho guarda a própria cópia e continua ligado.", "Every device keeps its own copy and stays linked.",
                            "Cada dispositivo conserva su copia y sigue vinculado.", "يحتفظ كل جهاز بنسخته ويبقى مرتبطاً."),
                    Ui.tr("Isto não pode ser desfeito.", "This can't be undone.", "Esto no se puede deshacer.", "لا يمكن التراجع عن هذا.")};
        for (int i = 0; i < lines.length; i++) Ui.text(canvas, "•  " + lines[i], x, (390 + i * 56) * k, host.labelFont(), v.w);
        String[] names = {Ui.tr("Cancelar", "Cancel", "Cancelar", "إلغاء"),
            confirmKind == 0 ? Ui.tr("Desconectar", "Disconnect", "Desconectar", "فصل") : Ui.tr("Apagar", "Delete", "Borrar", "حذف")};
        float bx = x, by = 640 * k, bh = 74 * k;
        Paint f = host.labelFont();
        for (int i = 0; i < 2; i++) {
            float bw = host.valueFont().measureText(names[i]) + 96 * k;
            boolean focused = i == confirmFocus && !busy;
            float left = Ui.rtl ? v.w - bx - bw : bx;
            v.rect.set(left, by, left + bw, by + bh);
            v.fill.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
            canvas.drawRoundRect(v.rect, 14 * k, 14 * k, v.fill);
            f.setColor(focused ? 0xFF0B0B0D : i == 1 ? CinemaView.RED : 0xFFFFFFFF);
            Paint.Align was = f.getTextAlign();
            f.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(names[i], left + bw / 2, by + bh / 2 + Ui.halfCap(f), f);
            f.setTextAlign(was);
            bx += bw + 28 * k;
        }
        if (!message.isEmpty()) drawWrapped(canvas, message, x, 790 * k, 0xFFFFB4A8, 2);
    }

    // MARK: - The QR code

    /** The link as a QR code, black on white with a four-module quiet zone; null when it can't be made (the code shows alone). */
    private Bitmap qrBitmap(String text) {
        try {
            boolean[][] m = Qr.encode(text);
            if (m == null) return null;
            int quiet = 4, modules = m.length + 2 * quiet;
            int px = Math.max(1, Math.round(QR_SIZE * v.k) / modules);
            Bitmap b = Bitmap.createBitmap(modules * px, modules * px, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Canvas c = new Canvas(b);
            Paint dark = new Paint();
            dark.setColor(Color.BLACK);
            for (int y = 0; y < m.length; y++) {
                for (int x = 0; x < m.length; x++) {
                    if (m[y][x]) c.drawRect((x + quiet) * px, (y + quiet) * px, (x + quiet + 1) * px, (y + quiet + 1) * px, dark);
                }
            }
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("qr not made: " + e);
            return null;
        }
    }
}
