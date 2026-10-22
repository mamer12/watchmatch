package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Watchme+'s account screens, drawn over the page in CinemaView's 1920×1080 space (right to left mirrors them): the welcome
 * (Sign in, Create account, Continue as guest), the email and password forms with the full on-screen keyboard, "Add this TV's
 * data to your account?", "Getting your profiles…", and the Account page of Settings (Sign out, Change password, Delete
 * account; for a guest, Create account and Sign in). The rules are AccountRules, the calls are Account; the password lives
 * in a form's buffer only until the form closes.
 */
final class AccountScreens {
    interface Host {
        /** The account part of the start is over (atStart), or a sign-in from Settings finished: on to the profiles. */
        void accountDone(boolean atStart);
        /** Back on the welcome, or the TV was signed out or the account deleted: leave Watchme+. */
        void exitWatchme();
        /** The screens closed back to Settings. */
        void accountClosed();
    }

    private static final int NONE = 0, WELCOME = 1, FORM = 2, ASK = 3, WORK = 4, HOME = 5, OUT = 6, DEL = 7, WEB = 8, PHONE = 9;
    private static final int K_SIGNIN = 0, K_CREATE = 1, K_PASSWORD = 2;
    private static final int I_EMAIL = 0, I_PW = 1, I_SHOW = 2, I_SUBMIT = 3, I_FORGOT = 4;
    private static final int H_OUT = 0, H_PASSWORD = 1, H_DELETE = 2, H_CREATE = 3, H_SIGNIN = 4, H_PHONE = 5, H_OTHER = 6;
    private static final int QR_SIZE = 400;

    private final CinemaView v;
    private final CinemaSettings settings;
    private final Host host;
    private int screen = NONE, from = NONE, focus, formKind, item, target;
    private boolean atStart, inKeys, pending, working, show;
    private String message = "";
    private final StringBuilder emailBuf = new StringBuilder(), pwBuf = new StringBuilder();
    private final OnScreenKeyboard keyboard;
    private final AccountRules.DeleteGate gate = new AccountRules.DeleteGate();
    private final Runnable tick = this::onTick;
    private boolean tickPosted;
    private Paint head, sub, button, codeFont;
    // Sign in with the phone
    private DeviceLogin phone;
    private int phoneBack;
    private Bitmap qr;
    private String qrFor = "", codeText = "", expiresText = "";

    AccountScreens(CinemaView v, CinemaSettings settings, Host host) {
        this.v = v;
        this.settings = settings;
        this.host = host;
        keyboard = new OnScreenKeyboard(v, new OnScreenKeyboard.Sink() {
            @Override public void type(String s) {
                typeText(s);
            }

            @Override public void delete() {
                StringBuilder b = buffer();
                if (b.length() > 0) b.setLength(b.length() - 1);
            }

            @Override public void clear() {
                wipe(buffer());
            }

            @Override public void done() {
                if (currentId() == I_EMAIL) {
                    item = indexOf(I_PW);
                    target = 1;
                } else {
                    inKeys = false;
                    item = indexOf(I_SUBMIT);
                }
            }
        }, true, true);
    }

    boolean isOpen() {
        return screen != NONE;
    }

    void sizeChanged() {
        float k = v.k;
        head = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
        head.setTextAlign(Paint.Align.CENTER);
        sub = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
        sub.setTextAlign(Paint.Align.CENTER);
        button = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
        button.setTextAlign(Paint.Align.CENTER);
        codeFont = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
        codeFont.setTextAlign(Paint.Align.CENTER);
        codeFont.setLetterSpacing(0.06f);
        keyboard.sizeChanged();
    }

    // MARK: - Opening and closing

    /** Watchme+ opens: the welcome (or the password again for an account whose session ended). True when a screen is up. */
    boolean start() {
        android.content.Context c = v.getContext();
        int plan = Account.startPlan(c);
        if (plan == AccountRules.PLAN_GO) return false;
        atStart = true;
        if (plan == AccountRules.PLAN_SIGN_IN_AGAIN) {
            from = WELCOME;
            openForm(K_SIGNIN);
            emailBuf.append(Account.email(c));
            item = indexOf(I_PW);
            message = AccountText.error(AccountRules.E_SESSION);
        } else {
            screen = WELCOME;
            focus = 0;
        }
        v.kickNow();
        return true;
    }

    /** Settings, Account. */
    void openHome() {
        atStart = false;
        screen = HOME;
        focus = 0;
        message = "";
        v.kickNow();
    }

    private void close() {
        closePhone();
        screen = NONE;
        pending = working = false;
        wipe(pwBuf);
        gate.disarm();
        v.removeCallbacks(tick);
        tickPosted = false;
        v.kickNow();
    }

    private void openForm(int kind) {
        if (screen != FORM) from = screen == NONE ? WELCOME : screen;
        screen = FORM;
        formKind = kind;
        item = 0;
        target = kind == K_PASSWORD ? 1 : 0;
        inKeys = false;
        pending = working = show = false;
        message = "";
        wipe(pwBuf);
        if (kind != K_SIGNIN || emailBuf.length() > 0 && from == HOME) emailBuf.setLength(0);
        keyboard.reset();
    }

    private static void wipe(StringBuilder b) {
        for (int i = 0; i < b.length(); i++) b.setCharAt(i, '\0');
        b.setLength(0);
    }

    // MARK: - The form

    private int[] items() {
        switch (formKind) {
            case K_PASSWORD: return new int[] {I_PW, I_SHOW, I_SUBMIT};
            case K_CREATE: return new int[] {I_EMAIL, I_PW, I_SHOW, I_SUBMIT};
            default: return new int[] {I_EMAIL, I_PW, I_SHOW, I_SUBMIT, I_FORGOT};
        }
    }

    private int currentId() {
        int[] ids = items();
        return ids[Math.min(item, ids.length - 1)];
    }

    private int indexOf(int id) {
        int[] ids = items();
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
        return 0;
    }

    private StringBuilder buffer() {
        return target == 0 && formKind != K_PASSWORD ? emailBuf : pwBuf;
    }

    private void typeText(String s) {
        StringBuilder b = buffer();
        int max = b == emailBuf ? AccountRules.MAX_EMAIL : AccountRules.MAX_PASSWORD;
        for (int i = 0; i < s.length() && b.length() < max; i++) b.append(s.charAt(i));
        message = "";
    }

    private void submit() {
        if (pending) return;
        boolean creating = formKind == K_CREATE;
        String email = AccountRules.cleanEmail(emailBuf.toString()), password = pwBuf.toString();
        if (formKind == K_PASSWORD) {
            if (AccountRules.passwordIssue(password) != null) {
                message = AccountText.issue("password", true);
                return;
            }
        } else {
            String issue = AccountRules.formIssue(email, password, creating);
            if (issue != null) {
                message = AccountText.issue(issue, creating);
                return;
            }
        }
        pending = true;
        message = "";
        android.content.Context c = v.getContext();
        Account.Done done = new Account.Done() {
            @Override public void done(Account.Result r) {
                formResult(r);
            }

            @Override public void progress() {
                if (screen == FORM || screen == ASK) working = true;
                v.kickNow();
            }
        };
        if (formKind == K_PASSWORD) Account.changePassword(password, done);
        else if (creating) Account.create(c, email, password, done);
        else Account.signIn(c, email, password, done);
        v.kickNow();
    }

    private void formResult(Account.Result r) {
        pending = working = false;
        if (screen != FORM && screen != ASK) return;
        if (r.ask) {
            screen = ASK;
            focus = 0;
        } else if (r.error != null) {
            screen = FORM;
            message = AccountText.error(r.error);
        } else {
            succeeded();
        }
        v.kickNow();
    }

    private void succeeded() {
        int kind = formKind;
        wipe(pwBuf);
        if (kind == K_PASSWORD || (kind == K_CREATE && !atStart)) {
            v.toast(kind == K_PASSWORD ? Ui.tr("Senha alterada", "Password changed", "Contraseña cambiada", "تم تغيير كلمة المرور")
                    : Ui.tr("Conta criada", "Account created", "Cuenta creada", "تم إنشاء الحساب"));
            screen = HOME;
            focus = 0;
            return;
        }
        boolean start = atStart;
        close();
        host.accountDone(start);
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (screen == NONE) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME) return false;
        switch (screen) {
            case WELCOME: welcomeKey(code); break;
            case FORM: formKey(e); break;
            case ASK: askKey(code); break;
            case HOME: homeKey(code); break;
            case OUT: confirmKey(code, false); break;
            case DEL: confirmKey(code, true); break;
            case WEB: if (code == KeyEvent.KEYCODE_BACK || isOk(code)) screen = FORM; break;
            case PHONE: phoneKey(code); break;
            default:      // WORK: nothing to press
        }
        v.kickNow();
        return true;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void welcomeKey(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_UP) focus = Math.max(0, focus - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focus = Math.min(3, focus + 1);
        else if (code == KeyEvent.KEYCODE_BACK) host.exitWatchme();
        else if (isOk(code)) {
            if (focus == 0) {
                openPhone(WELCOME);
            } else if (focus == 3) {
                Account.markGuest(v.getContext());
                close();
                host.accountDone(true);
            } else {
                from = WELCOME;
                openForm(focus == 1 ? K_SIGNIN : K_CREATE);
            }
        }
    }

    private void formKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (pending) return;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (inKeys) inKeys = false;
            else {
                wipe(pwBuf);
                screen = from == HOME ? HOME : WELCOME;
                focus = 0;
            }
            return;
        }
        if (inKeys) {
            keysKey(e);
            return;
        }
        int[] ids = items();
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            item = Math.max(0, item - 1);
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            item = Math.min(ids.length - 1, item + 1);
            return;
        }
        int id = ids[Math.min(item, ids.length - 1)];
        boolean field = id == I_EMAIL || id == I_PW;
        if (field && Ui.dpad(code) == KeyEvent.KEYCODE_DPAD_RIGHT || isOk(code) && field) {
            target = id == I_EMAIL ? 0 : 1;
            inKeys = true;
            return;
        }
        if (!isOk(code)) return;
        if (id == I_SHOW) show = !show;
        else if (id == I_SUBMIT) submit();
        else if (id == I_FORGOT) screen = WEB;
    }

    private void keysKey(KeyEvent e) {
        int code = e.getKeyCode();
        int unicode = e.getUnicodeChar();
        if (code == KeyEvent.KEYCODE_DEL) {
            StringBuilder b = buffer();
            if (b.length() > 0) b.setLength(b.length() - 1);
            return;
        }
        if (unicode > 0 && !Character.isISOControl(unicode) && !isOk(code)) {
            typeText(String.valueOf((char) unicode));      // a real keyboard, or a remote with letters
            return;
        }
        if (keyboard.leaves(code)) {
            inKeys = false;
            return;
        }
        if (keyboard.onKey(code) == OnScreenKeyboard.EXIT_UP) inKeys = false;
    }

    private void askKey(int code) {
        if (pending) return;
        int d = Ui.dpad(code);
        if (d == KeyEvent.KEYCODE_DPAD_LEFT || d == KeyEvent.KEYCODE_DPAD_RIGHT) focus = 1 - focus;
        else if (code == KeyEvent.KEYCODE_BACK) {
            Account.cancelSignIn();
            screen = FORM;
        } else if (isOk(code)) {
            pending = true;
            Account.chooseData(v.getContext(), focus == 0, new Account.Done() {
                @Override public void done(Account.Result r) {
                    formResult(r);
                }

                @Override public void progress() {
                    working = true;
                    v.kickNow();
                }
            });
        }
    }

    private int[] homeRows() {
        android.content.Context c = v.getContext();
        if (!Account.signedInEmail(c).isEmpty()) return new int[] {H_OUT, H_PASSWORD, H_DELETE};
        return Account.linkedByPhone(c) ? new int[] {H_OUT, H_OTHER} : new int[] {H_PHONE, H_CREATE, H_SIGNIN};
    }

    private void homeKey(int code) {
        int[] rows = homeRows();
        focus = Math.min(focus, rows.length - 1);
        if (code == KeyEvent.KEYCODE_DPAD_UP) focus = Math.max(0, focus - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focus = Math.min(rows.length - 1, focus + 1);
        else if (code == KeyEvent.KEYCODE_BACK) {
            close();
            host.accountClosed();
        } else if (isOk(code)) {
            switch (rows[focus]) {
                case H_PHONE:
                case H_OTHER:
                    openPhone(HOME);
                    break;
                case H_CREATE:
                    openForm(K_CREATE);
                    break;
                case H_SIGNIN:
                    openForm(K_SIGNIN);
                    break;
                case H_PASSWORD:
                    openForm(K_PASSWORD);
                    break;
                case H_OUT:
                    screen = OUT;
                    focus = 0;
                    message = "";
                    break;
                default:
                    screen = DEL;
                    focus = 0;
                    message = "";
                    gate.disarm();
            }
        }
    }

    /** Sign out and Delete: Cancel is focused first; Delete's second button needs two presses, 5 seconds apart. */
    private void confirmKey(int code, boolean delete) {
        if (pending) return;
        int d = Ui.dpad(code);
        if (d == KeyEvent.KEYCODE_DPAD_LEFT || d == KeyEvent.KEYCODE_DPAD_RIGHT) {
            focus = 1 - focus;
            gate.disarm();
        } else if (code == KeyEvent.KEYCODE_BACK) {
            gate.disarm();
            screen = HOME;
            focus = 0;
        } else if (isOk(code)) {
            if (focus == 0) {
                gate.disarm();
                screen = HOME;
                return;
            }
            if (!delete) {
                pending = true;
                Account.signOut(v.getContext(), r -> {
                    pending = false;
                    host.exitWatchme();
                });
            } else if (gate.press(SystemClock.elapsedRealtime())) {
                pending = true;
                message = "";
                Account.deleteAccount(v.getContext(), r -> {
                    pending = false;
                    if (r.error == null) {
                        host.exitWatchme();
                    } else {
                        message = AccountText.error(r.error);
                        gate.disarm();
                        v.kickNow();
                    }
                });
            } else if (!tickPosted) {
                tickPosted = true;
                v.postDelayed(tick, 500);
            }
        }
    }

    // MARK: - Sign in with the phone

    /** Settings, Sync (a guest's "Sign in with your phone"): the same screen, then the profile picker. */
    void openPhoneFromSettings() {
        atStart = false;
        openPhone(NONE);
    }

    private void openPhone(int back) {
        closePhone();
        phoneBack = back;
        screen = PHONE;
        focus = 0;
        message = "";
        phone = new DeviceLogin(v.getContext(), this::onPhoneChanged);
        phone.open();
        v.kickNow();
    }

    private void closePhone() {
        if (phone != null) phone.close();
        phone = null;
        if (qr != null) qr.recycle();
        qr = null;
        qrFor = codeText = expiresText = "";
    }

    private void leavePhone() {
        closePhone();
        if (phoneBack == NONE) {
            close();
            host.accountClosed();
        } else {
            screen = phoneBack;
            focus = 0;
        }
    }

    /** The flow moved (a code, a second, an answer): what is drawn is prepared here, so drawing makes nothing. */
    private void onPhoneChanged() {
        if (screen != PHONE || phone == null) return;
        DeviceLoginRules.Flow f = phone.flow();
        if (f.state == DeviceLoginRules.READY) {
            boolean start = atStart;
            close();
            host.accountDone(start);
            return;
        }
        if (f.state == DeviceLoginRules.PENDING) {
            if (!f.code.equals(qrFor)) {
                if (qr != null) qr.recycle();
                qrFor = f.code;
                codeText = DeviceLoginRules.group(f.code);
                qr = QrBitmap.make(DeviceLoginRules.qrUrl(Config.SITE_URL, f.code), Math.round(QR_SIZE * v.k));
            }
            expiresText = DeviceLoginText.expiresIn(DeviceLoginRules.clock(f.remaining(SystemClock.elapsedRealtime())));
        }
        v.kickNow();
    }

    private void phoneKey(int code) {
        DeviceLoginRules.Flow f = phone == null ? null : phone.flow();
        if (f == null || f.state == DeviceLoginRules.LINKING) return;      // the TV is being signed in: nothing to press
        if (f.state == DeviceLoginRules.ASK) {
            int d = Ui.dpad(code);
            if (d == KeyEvent.KEYCODE_DPAD_LEFT || d == KeyEvent.KEYCODE_DPAD_RIGHT) focus = 1 - focus;
            else if (isOk(code)) phone.chooseData(focus == 0);
            return;      // Back does nothing here: the phone already approved, so the TV must be told what to do with its data
        }
        if (code == KeyEvent.KEYCODE_BACK) leavePhone();
        else if (isOk(code) && f.state == DeviceLoginRules.ERROR) phone.retry();
    }

    private void onTick() {
        tickPosted = false;
        if (screen != DEL || !gate.armed()) return;
        v.invalidate();
        tickPosted = true;
        v.postDelayed(tick, 500);
    }

    // MARK: - Drawing

    void draw(Canvas c) {
        if (head == null) sizeChanged();
        v.fill.setColor(0xFF0B0B0D);
        c.drawRect(0, 0, v.w, v.h, v.fill);
        if (pending && working || screen == WORK) {
            drawWork(c);
            return;
        }
        switch (screen) {
            case WELCOME: drawWelcome(c); break;
            case FORM: drawForm(c); break;
            case ASK: drawAsk(c); break;
            case HOME: drawHome(c); break;
            case OUT: drawOut(c); break;
            case DEL: drawDelete(c); break;
            case PHONE: drawPhone(c); break;
            default: drawWeb(c);
        }
    }

    private void headline(Canvas c, String text, float y) {
        Ui.text(c, text, CinemaView.L * v.k, y * v.k, settings.headerFont(), v.w);
    }

    private void line(Canvas c, String text, float y) {
        settings.labelFont().setColor(0xCCFFFFFF);
        Ui.text(c, text, CinemaView.L * v.k, y * v.k, settings.labelFont(), v.w);
    }

    private void note(Canvas c, String text, float y, int color) {
        Paint f = settings.helpFont();
        int was = f.getColor();
        f.setColor(color);
        Ui.text(c, text, CinemaView.L * v.k, y * v.k, f, v.w);
        f.setColor(was);
    }

    /** A button of `w` × 80 (1920 space) with its top-left at (left, top), mirrored in right to left. */
    private void drawButton(Canvas c, float left, float top, float w, String label, boolean focused, boolean red, boolean dim) {
        float k = v.k, h = 80 * k, x = Ui.mx(left * k, w * k, v.w);
        v.rect.set(x, top * k, x + w * k, top * k + h);
        v.fill.setColor(focused ? (dim ? 0xCCFFFFFF : 0xFFFFFFFF) : 0x33FFFFFF);
        c.drawRoundRect(v.rect, 14 * k, 14 * k, v.fill);
        button.setColor(focused ? 0xFF0B0B0D : red ? CinemaView.RED : dim ? 0x80FFFFFF : 0xFFFFFFFF);
        c.drawText(label, v.rect.centerX(), v.rect.centerY() + Ui.halfCap(button), button);
    }

    private void drawWelcome(Canvas c) {
        float k = v.k;
        c.drawText(Ui.tr("Bem-vindo ao {catalog}", "Welcome to {catalog}", "Te damos la bienvenida a {catalog}", "مرحباً بك في {catalog}"),
                v.w / 2f, 300 * k, head);
        c.drawText(DeviceLoginText.welcomeLine(), v.w / 2f, 370 * k, sub);
        String[] names = {DeviceLoginText.phone(), DeviceLoginText.emailPassword(), AccountText.create(), AccountText.guest()};
        float w = 680;
        for (int i = 0; i < names.length; i++) drawButton(c, (1920 - w) / 2, 440 + i * 108, w, names[i], focus == i, false, false);
    }

    private void drawPhone(Canvas c) {
        float k = v.k;
        DeviceLoginRules.Flow f = phone == null ? new DeviceLoginRules.Flow() : phone.flow();
        if (f.state == DeviceLoginRules.ASK) {
            drawAsk(c);
            return;
        }
        if (f.state == DeviceLoginRules.LINKING) {
            c.drawText(f.fetching ? Ui.tr("Buscando seus perfis…", "Getting your profiles…", "Obteniendo tus perfiles…", "جارٍ جلب ملفاتك…")
                    : DeviceLoginText.signingIn(), v.w / 2f, 520 * k, head);
            return;
        }
        headline(c, DeviceLoginText.title(), 200);
        String[] steps = DeviceLoginText.steps();
        for (int i = 0; i < steps.length; i++) line(c, steps[i], 340 + i * 64);
        float size = QR_SIZE * k, x = CinemaView.L * k;
        float qx = Ui.rtl ? x : v.w - x - size, qy = 270 * k;
        if (f.state == DeviceLoginRules.PENDING) {
            if (qr != null) {
                v.rect.set(qx, qy, qx + size, qy + size);
                c.drawBitmap(qr, null, v.rect, null);
            }
            c.drawText(codeText, qx + size / 2, qy + size + 90 * k, codeFont);
            note(c, expiresText, 560, 0x99FFFFFF);
            if (f.offline) note(c, DeviceLoginText.offline(), 620, 0xFFFFB4A8);
        } else if (f.state == DeviceLoginRules.ERROR) {
            note(c, DeviceLoginText.error(f.error), 560, 0xFFFFB4A8);
            drawButton(c, CinemaView.L, 640, 420, DeviceLoginText.retry(), true, false, false);
        } else {
            note(c, DeviceLoginText.gettingCode(), 560, 0x99FFFFFF);
        }
    }

    private void drawWork(Canvas c) {
        c.drawText(Ui.tr("Buscando seus perfis…", "Getting your profiles…", "Obteniendo tus perfiles…", "جارٍ جلب ملفاتك…"),
                v.w / 2f, 520 * v.k, head);
    }

    // ---- the form ----

    private String fieldText(StringBuilder b, boolean secret) {
        int max = 34;
        String text = secret && !show ? new String(new char[Math.min(b.length(), max)]).replace('\0', '•')
                : b.length() > max ? "…" + b.substring(b.length() - max) : b.toString();
        return secret && !show ? text : Ui.isolate(text);
    }

    private void drawField(Canvas c, float top, String label, StringBuilder b, boolean secret, boolean focused, boolean typing) {
        float k = v.k, x = CinemaView.L * k, boxW = 980 * k, bx = Ui.mx(x, boxW, v.w);
        settings.labelFont().setColor(0xCCFFFFFF);
        Ui.text(c, label, x, (top - 14) * k, settings.labelFont(), v.w);
        v.rect.set(bx, top * k, bx + boxW, (top + 84) * k);
        v.fill.setColor(typing ? 0x40FFFFFF : 0x26FFFFFF);
        c.drawRoundRect(v.rect, 14 * k, 14 * k, v.fill);
        if (focused || typing) {
            v.stroke.setStrokeWidth(3 * k);
            v.stroke.setColor(0xFFFFFFFF);
            c.drawRoundRect(v.rect, 14 * k, 14 * k, v.stroke);
        }
        Paint f = settings.valueFont();
        int was = f.getColor();
        Paint.Align align = f.getTextAlign();
        f.setColor(0xFFFFFFFF);
        f.setTextAlign(Ui.rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(fieldText(b, secret), Ui.rtl ? v.rect.right - 28 * k : v.rect.left + 28 * k, v.rect.centerY() + Ui.halfCap(f), f);
        f.setTextAlign(align);
        f.setColor(was);
    }

    private void drawForm(Canvas c) {
        float k = v.k;
        String title = formKind == K_PASSWORD ? Ui.tr("Mudar senha", "Change password", "Cambiar contraseña", "تغيير كلمة المرور")
                : formKind == K_CREATE ? AccountText.create() : AccountText.signIn();
        headline(c, title, 200);
        int[] ids = items();
        float y = 300;
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            boolean focused = i == item && !inKeys, typing = inKeys && i == item;
            switch (id) {
                case I_EMAIL:
                    drawField(c, 290, Ui.tr("E-mail", "Email", "Correo", "البريد الإلكتروني"), emailBuf, false, focused, typing);
                    break;
                case I_PW:
                    drawField(c, formKind == K_PASSWORD ? 290 : 430, formKind == K_PASSWORD
                            ? Ui.tr("Nova senha", "New password", "Contraseña nueva", "كلمة المرور الجديدة")
                            : Ui.tr("Senha", "Password", "Contraseña", "كلمة المرور"), pwBuf, true, focused, typing);
                    break;
                case I_SHOW:
                    y = formKind == K_PASSWORD ? 410 : 560;
                    settings.drawRow(c, y * k, focused, Ui.tr("Mostrar a senha", "Show password", "Mostrar contraseña", "إظهار كلمة المرور"),
                            show ? Ui.tr("Sim", "Yes", "Sí", "نعم") : Ui.tr("Não", "No", "No", "لا"), false, false);
                    break;
                case I_SUBMIT: {
                    float top = formKind == K_PASSWORD ? 490 : 640;
                    String label = pending ? (formKind == K_CREATE ? Ui.tr("Criando…", "Creating…", "Creando…", "جارٍ الإنشاء…")
                            : formKind == K_PASSWORD ? Ui.tr("Salvando…", "Saving…", "Guardando…", "جارٍ الحفظ…")
                            : Ui.tr("Entrando…", "Signing in…", "Entrando…", "جارٍ تسجيل الدخول…"))
                            : formKind == K_PASSWORD ? Ui.tr("Salvar", "Save", "Guardar", "حفظ") : title;
                    drawButton(c, CinemaView.L, top, 420, label, focused, false, pending);
                    break;
                }
                default:
                    settings.drawRow(c, 740 * k, focused, Ui.tr("Esqueci a senha", "Forgot password?", "¿Olvidaste la contraseña?", "نسيت كلمة المرور؟"),
                            Ui.rtl ? "‹" : "›", false, false);
            }
        }
        if (!message.isEmpty()) note(c, message, formKind == K_PASSWORD ? 610 : 830, 0xFFFFB4A8);
        else if (formKind == K_CREATE) {
            note(c, Ui.tr("Pelo menos 8 caracteres. Seus perfis e progresso já neste aparelho ficam na conta.",
                    "At least 8 characters. The profiles and progress already on this TV stay in the account.",
                    "Al menos 8 caracteres. Los perfiles y el progreso que ya hay en este TV pasan a la cuenta.",
                    "8 أحرف على الأقل. الملفات والتقدم الموجودان على هذا التلفاز يبقيان في الحساب."), 830, 0x99FFFFFF);
        }
        keyboard.draw(c, (1920 - 110 - OnScreenKeyboard.width()) * k, 230, inKeys);
    }

    private void drawWeb(Canvas c) {
        float k = v.k;
        headline(c, Ui.tr("Redefina a senha no site", "Reset your password on the website", "Restablece tu contraseña en el sitio web",
                "أعد تعيين كلمة المرور على الموقع"), 300);
        Paint f = settings.valueFont();
        int was = f.getColor();
        Paint.Align align = f.getTextAlign();
        f.setColor(0xFFFFFFFF);
        f.setTextAlign(Ui.rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(Ui.isolate(AccountText.site()), Ui.rtl ? v.w - CinemaView.L * k : CinemaView.L * k, 400 * k, f);
        f.setTextAlign(align);
        f.setColor(was);
        line(c, Ui.tr("Abra no celular ou computador, toque em “Esqueci a senha” e depois entre aqui.",
                "Open it on your phone or computer, choose “Forgot password”, then sign in here.",
                "Ábrelo en el móvil o el ordenador, elige “¿Olvidaste la contraseña?” y luego entra aquí.",
                "افتحه على هاتفك أو حاسوبك واختر «نسيت كلمة المرور» ثم سجّل الدخول هنا."), 490);
        drawButton(c, CinemaView.L, 600, 280, "OK", true, false, false);
    }

    private void drawAsk(Canvas c) {
        headline(c, Ui.tr("Adicionar os dados desta TV à sua conta?", "Add this TV's data to your account?",
                "¿Añadir los datos de este TV a tu cuenta?", "إضافة بيانات هذا التلفاز إلى حسابك؟"), 300);
        line(c, "•  " + Ui.tr("Minha Lista, notas e progresso desta TV se juntam aos da conta.",
                "This TV's My List, ratings and progress join the account's.",
                "Mi lista, valoraciones y progreso de este TV se unen a los de la cuenta.",
                "قائمتي وتقييمات وتقدم هذا التلفاز تنضم إلى ما في الحساب."), 390);
        line(c, "•  " + Ui.tr("Nada na conta é apagado.", "Nothing in the account is removed.", "No se borra nada de la cuenta.",
                "لا يُحذف شيء من الحساب."), 446);
        line(c, "•  " + Ui.tr("Se não adicionar, esta TV começa só com o que a conta já tem.",
                "If you don't add it, this TV starts with only what the account already has.",
                "Si no lo añades, este TV empieza solo con lo que ya tiene la cuenta.",
                "إن لم تضفها، يبدأ هذا التلفاز بما في الحساب فقط."), 502);
        String[] names = {Ui.tr("Adicionar", "Add", "Añadir", "إضافة"), Ui.tr("Não adicionar", "Don't add", "No añadir", "عدم الإضافة")};
        float left = CinemaView.L;
        for (int i = 0; i < 2; i++) {
            float w = settings.valueFont().measureText(names[i]) / v.k + 96;
            drawButton(c, left, 620, w, names[i], focus == i, false, false);
            left += w + 28;
        }
    }

    // ---- Settings, Account ----

    private void drawHome(Canvas c) {
        float k = v.k;
        headline(c, AccountText.account(), 200);
        line(c, DeviceLoginText.status(v.getContext()), 290);
        int[] rows = homeRows();
        focus = Math.min(focus, rows.length - 1);
        for (int i = 0; i < rows.length; i++) {
            String label;
            switch (rows[i]) {
                case H_OUT: label = Account.linkedByPhone(v.getContext()) ? DeviceLoginText.signOutTv()
                        : Ui.tr("Sair", "Sign out", "Cerrar sesión", "تسجيل الخروج"); break;
                case H_PHONE: label = DeviceLoginText.phone(); break;
                case H_OTHER: label = DeviceLoginText.linkOther(); break;
                case H_PASSWORD: label = Ui.tr("Mudar senha", "Change password", "Cambiar contraseña", "تغيير كلمة المرور"); break;
                case H_DELETE: label = Ui.tr("Excluir conta", "Delete account", "Eliminar cuenta", "حذف الحساب"); break;
                case H_CREATE: label = AccountText.create(); break;
                default: label = AccountText.signIn();
            }
            settings.drawRow(c, (360 + i * CinemaSettings.ROW_H) * k, i == focus, label, Ui.rtl ? "‹" : "›", false, rows[i] == H_DELETE);
        }
        String help;
        switch (rows[focus]) {
            case H_OUT: help = Ui.tr("Seus dados continuam na conta. Esta TV esquece os perfis e listas.",
                    "Your data stays in your account. This TV forgets its profiles and lists.",
                    "Tus datos siguen en tu cuenta. Este TV olvida los perfiles y listas.",
                    "تبقى بياناتك في حسابك. ينسى هذا التلفاز الملفات والقوائم."); break;
            case H_PHONE: help = DeviceLoginText.phoneHelp(); break;
            case H_OTHER: help = Ui.tr("Mostra o QR code de novo para vincular esta TV a outra conta.",
                    "Shows the QR code again to link this TV to a different account.",
                    "Muestra el código QR otra vez para vincular este TV a otra cuenta.",
                    "يعرض رمز QR مجدداً لربط هذا التلفاز بحساب آخر."); break;
            case H_DELETE: help = Ui.tr("Apaga a conta e tudo nela, em todos os aparelhos.", "Deletes the account and everything in it, on every device.",
                    "Elimina la cuenta y todo lo que tiene, en todos los dispositivos.", "يحذف الحساب وكل ما فيه على جميع الأجهزة."); break;
            case H_CREATE: help = Ui.tr("Entre com o mesmo e-mail e senha em qualquer aparelho: perfis e progresso vão junto.",
                    "Sign in with the same email and password on any device: profiles and progress follow.",
                    "Entra con el mismo correo y contraseña en cualquier dispositivo: perfiles y progreso te siguen.",
                    "سجّل الدخول بالبريد وكلمة المرور نفسيهما على أي جهاز: الملفات والتقدم يتبعانك."); break;
            case H_SIGNIN: help = Ui.tr("Entre em uma conta que já existe.", "Sign in to an account that already exists.",
                    "Entra en una cuenta que ya existe.", "سجّل الدخول إلى حساب موجود."); break;
            default: help = "";
        }
        note(c, help, 360 + 5 * CinemaSettings.ROW_H + 30, 0x99FFFFFF);
        if (Account.linkedByPhone(v.getContext())) note(c, DeviceLoginText.manage(), 360 + 5 * CinemaSettings.ROW_H + 90, 0x99FFFFFF);
    }

    private void drawButtons(Canvas c, String[] names, float top, boolean[] red, boolean[] dim) {
        float left = CinemaView.L;
        for (int i = 0; i < names.length; i++) {
            float w = Math.max(settings.valueFont().measureText(names[i]) / v.k + 96, 220);
            drawButton(c, left, top, w, names[i], focus == i, red[i], dim[i]);
            left += w + 28;
        }
    }

    private void drawOut(Canvas c) {
        headline(c, Ui.tr("Sair da conta?", "Sign out?", "¿Cerrar sesión?", "تسجيل الخروج؟"), 300);
        line(c, "•  " + DeviceLoginText.status(v.getContext()), 390);
        line(c, "•  " + Ui.tr("Seus dados continuam na sua conta.", "Your data stays in your account.", "Tus datos siguen en tu cuenta.",
                "تبقى بياناتك في حسابك."), 446);
        line(c, "•  " + Ui.tr("Esta TV esquece os perfis, listas e progresso e volta à tela de boas-vindas.",
                "This TV forgets its profiles, lists and progress and goes back to the welcome screen.",
                "Este TV olvida los perfiles, listas y progreso y vuelve a la pantalla de bienvenida.",
                "ينسى هذا التلفاز الملفات والقوائم والتقدم ويعود إلى شاشة الترحيب."), 502);
        String go = pending ? Ui.tr("Saindo…", "Signing out…", "Cerrando sesión…", "جارٍ الخروج…") : Ui.tr("Sair", "Sign out", "Cerrar sesión", "تسجيل الخروج");
        drawButtons(c, new String[] {AccountText.cancel(), go}, 620, new boolean[] {false, true}, new boolean[] {false, pending});
    }

    private void drawDelete(Canvas c) {
        headline(c, Ui.tr("Excluir a conta?", "Delete account?", "¿Eliminar la cuenta?", "حذف الحساب؟"), 300);
        line(c, "•  " + Ui.tr("A conta, os perfis, listas, notas e progresso somem de todos os aparelhos.",
                "The account, its profiles, lists, ratings and progress are removed from every device.",
                "La cuenta, sus perfiles, listas, valoraciones y progreso se eliminan de todos los dispositivos.",
                "يُحذف الحساب وملفاته وقوائمه وتقييماته وتقدمه من جميع الأجهزة."), 390);
        line(c, "•  " + Ui.tr("Isto não pode ser desfeito.", "This can't be undone.", "Esto no se puede deshacer.", "لا يمكن التراجع عن هذا."), 446);
        int left = gate.secondsLeft(SystemClock.elapsedRealtime());
        String go;
        boolean dim = false;
        if (pending) {
            go = Ui.tr("Excluindo…", "Deleting…", "Eliminando…", "جارٍ الحذف…");
            dim = true;
        } else if (left < 0) {
            go = Ui.tr("Excluir conta", "Delete account", "Eliminar cuenta", "حذف الحساب");
        } else if (left > 0) {
            go = Ui.tr("Aguarde " + Ui.digits(String.valueOf(left)), "Wait " + Ui.digits(String.valueOf(left)),
                    "Espera " + Ui.digits(String.valueOf(left)), "انتظر " + Ui.digits(String.valueOf(left)));
            dim = true;
        } else {
            go = Ui.tr("Pressione de novo para excluir", "Press again to delete", "Pulsa otra vez para eliminar", "اضغط مرة أخرى للحذف");
        }
        drawButtons(c, new String[] {AccountText.cancel(), go}, 580, new boolean[] {false, true}, new boolean[] {false, dim});
        if (!message.isEmpty()) note(c, message, 720, 0xFFFFB4A8);
    }
}
