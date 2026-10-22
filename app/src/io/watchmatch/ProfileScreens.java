package io.watchmatch;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watchme+'s profile screens, drawn over the page in CinemaView's 1920×1080 space (right to left mirrors them): "Who's
 * watching?" (round avatars, names, a Manage tile), the PIN pad (a number pad the d-pad drives), the management list,
 * the editor (name with the on-screen keyboard, colour, avatar, Kids, PIN, delete) and the avatar's small menu. The
 * rules are ProfileRules and PinRules; the list is Profiles. Everything the person can do to a profile is here, and a
 * Kids profile cannot reach any of it without an adult's PIN (when one has a PIN).
 */
final class ProfileScreens {
    /** What the view does when the active profile changes or the screens end. */
    interface Host {
        /** The active profile is another one (or its Kids setting changed): everything is read again. */
        void profileChanged();
        /** Back on "Who's watching?" at the very start: leave Watchme+. */
        void exitWatchme();
        /** The screens closed. */
        void profilesClosed();
    }

    private static final int NONE = 0, PICKER = 1, PIN = 2, MANAGE = 3, EDIT = 4, NAME = 5, CONFIRM = 6, MENU = 7;
    private static final int PIN_TARGET = 0, PIN_ADULT = 1, PIN_NEW = 2, PIN_CONFIRM = 3;
    private static final int E_NAME = 0, E_COLOR = 1, E_AVATAR = 2, E_KIDS = 3, E_PIN = 4, E_NOPIN = 5, E_DELETE = 6, E_SAVE = 7;
    private static final float PITCH = 250, AVATAR_R = 92, PAD_W = 150, PAD_H = 64, PAD_GAP = 12;
    private static final ExecutorService HASHING = Executors.newSingleThreadExecutor();

    private final CinemaView v;
    private final CinemaSettings settings;
    private final Host host;
    private int screen = NONE;
    private int focus, row;
    /** Management was opened from "Who's watching?": Back from it returns there. */
    private boolean managedFromPicker;
    /** Back on the picker leaves Watchme+ (the start) instead of just closing. */
    private boolean required;
    /** Where Back from the PIN pad goes. */
    private Runnable pinBack;
    private Runnable pinDone;

    private List<ProfileRules.Profile> shown = new ArrayList<>();
    // PIN pad
    private int pinMode, padFocus;
    private ProfileRules.Profile pinTarget;
    private final StringBuilder pin = new StringBuilder();
    private String firstPin = "", pinMessage = "";
    private boolean busy, tickPosted;
    private String lockKey = "";
    // Editor
    private ProfileRules.Profile draft;
    private boolean isNew, dirtyActive, wipeSynced;
    private int confirmFocus;
    private final StringBuilder nameEntry = new StringBuilder();
    private final OnScreenKeyboard keyboard;
    private Paint head, name, sub, dot, big;
    private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Runnable tick = this::onTick;

    ProfileScreens(CinemaView v, CinemaSettings settings, Host host) {
        this.v = v;
        this.settings = settings;
        this.host = host;
        keyboard = new OnScreenKeyboard(v, new OnScreenKeyboard.Sink() {
            @Override public void type(String s) {
                typeName(s);
            }

            @Override public void delete() {
                if (nameEntry.length() > 0) nameEntry.setLength(nameEntry.length() - 1);
            }

            @Override public void clear() {
                nameEntry.setLength(0);
            }

            @Override public void done() {
                finishName();
            }
        }, true);
        plain.setStyle(Paint.Style.STROKE);
    }

    boolean isOpen() {
        return screen != NONE;
    }

    void sizeChanged() {
        float k = v.k;
        head = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
        head.setTextAlign(Paint.Align.CENTER);
        name = Ui.text(Ui.semibold, 32 * k, 0xCCFFFFFF);
        name.setTextAlign(Paint.Align.CENTER);
        sub = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
        sub.setTextAlign(Paint.Align.CENTER);
        big = Ui.text(Ui.semibold, 36 * k, 0xFFFFFFFF);
        big.setTextAlign(Paint.Align.CENTER);
        dot = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        dot.setTextAlign(Paint.Align.CENTER);
        keyboard.sizeChanged();
    }

    // MARK: - Opening

    /**
     * Watchme+ opens: the picker, a PIN for the last profile, or nothing (it goes straight in). True when a screen is up
     * (the caller waits for profileChanged / profilesClosed before it loads anything for the profile).
     */
    boolean start() {
        android.content.Context c = v.getContext();
        ProfileRules.Start plan = ProfileRules.startPlan(Profiles.all(c), Profiles.ask(c), Profiles.activeId(c));
        if (plan.kind == ProfileRules.START_PICKER) {
            openPicker(true);
            return true;
        }
        ProfileRules.Profile target = Profiles.get(c, plan.id);
        if (target == null) return false;
        if (plan.kind == ProfileRules.START_PIN) {
            final boolean several = Profiles.live(c).size() > 1;
            askPin(PIN_TARGET, target, () -> {
                select(target, true);
            }, () -> {
                if (several) openPicker(true);
                else host.exitWatchme();
            });
            return true;
        }
        if (!plan.id.equals(Profiles.activeId(c))) Profiles.switchTo(c, plan.id);
        Profiles.setAdultVerified(false);
        return false;
    }

    /** "Who's watching?" (from the avatar's menu or WatchMatch's Settings); Back closes it unless it is the start. */
    void openPicker(boolean atStart) {
        required = atStart;
        screen = PICKER;
        shown = Profiles.live(v.getContext());
        focus = 0;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(Profiles.activeId(v.getContext()))) focus = i;
        changed();
    }

    /** The avatar in the top bar: Switch profile, Manage profiles (not for a Kids profile). */
    void openMenu() {
        screen = MENU;
        row = 0;
        changed();
    }

    private boolean showsManage() {
        return !Profiles.kidsActive(v.getContext());
    }

    /** Management, after an adult's PIN when any adult has one. */
    void openManage(final boolean fromPicker) {
        android.content.Context c = v.getContext();
        int gate = ProfileRules.gateForManage(Profiles.all(c), Profiles.adultVerified());
        final Runnable show = () -> {
            managedFromPicker = fromPicker;
            screen = MANAGE;
            shown = Profiles.live(c);
            focus = 0;
            row = 0;
            dirtyActive = false;
            changed();
        };
        if (gate == ProfileRules.GATE_NONE) {
            show.run();
            return;
        }
        askPin(PIN_ADULT, null, show, () -> {
            if (fromPicker) openPicker(required);
            else close();
        });
    }

    /** An adult's PIN for something a Kids profile may not do (Reset, from WatchMatch's Settings); then `then` runs. */
    void requireAdult(final Runnable then) {
        android.content.Context c = v.getContext();
        if (ProfileRules.gateForManage(Profiles.all(c), Profiles.adultVerified()) == ProfileRules.GATE_NONE) {
            then.run();
            return;
        }
        askPin(PIN_ADULT, null, () -> {
            close();
            then.run();
        }, this::close);
    }

    void close() {
        boolean was = screen != NONE;
        screen = NONE;
        busy = false;
        pin.setLength(0);
        v.removeCallbacks(tick);
        tickPosted = false;
        if (was) host.profilesClosed();
        v.kickNow();
    }

    private void changed() {
        v.kickNow();
    }

    // MARK: - Choosing a profile

    private void choose(ProfileRules.Profile target) {
        android.content.Context c = v.getContext();
        int gate = ProfileRules.gateForSwitch(Profiles.all(c), Profiles.active(c), target);
        if (gate == ProfileRules.GATE_NONE) {
            select(target, false);
            return;
        }
        final ProfileRules.Profile t = target;
        final Runnable back = () -> {
            screen = PICKER;
            changed();
        };
        if (gate == ProfileRules.GATE_TARGET) askPin(PIN_TARGET, target, () -> select(t, true), back);
        else askPin(PIN_ADULT, null, () -> select(t, true), back);
    }

    /** `verified`: an adult's PIN was just entered (the management then needs none for this session). */
    private void select(ProfileRules.Profile target, boolean verified) {
        android.content.Context c = v.getContext();
        boolean other = !target.id.equals(Profiles.activeId(c));
        Profiles.switchTo(c, target.id);
        Profiles.setAdultVerified(verified && !target.kids);
        screen = NONE;
        pin.setLength(0);
        if (other) host.profileChanged();
        host.profilesClosed();
        v.kickNow();
    }

    // MARK: - The PIN pad

    private void askPin(int mode, ProfileRules.Profile target, Runnable done, Runnable back) {
        screen = PIN;
        pinMode = mode;
        pinTarget = target;
        pinDone = done;
        pinBack = back;
        pin.setLength(0);
        firstPin = "";
        pinMessage = "";
        padFocus = 4;
        busy = false;
        lockKey = mode == PIN_TARGET && target != null ? target.id : "gate";
        showLock();
        changed();
    }

    private void showLock() {
        PinRules.Lock l = Profiles.lock(v.getContext(), lockKey);
        long left = PinRules.remaining(l, TrustedTime.now());
        if (left > 0 && (pinMode == PIN_TARGET || pinMode == PIN_ADULT)) {
            long s = (left + 999) / 1000;
            pinMessage = Ui.tr("Muitas tentativas. Tente de novo em " + Ui.digits(s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60)),
                    "Too many tries. Try again in " + Ui.digits(s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60)),
                    "Demasiados intentos. Inténtalo de nuevo en " + Ui.digits(s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60)),
                    "محاولات كثيرة. حاول مجدداً بعد " + Ui.digits(s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60)));
            if (!tickPosted) {
                tickPosted = true;
                v.postDelayed(tick, 1000);
            }
        }
    }

    private void onTick() {
        tickPosted = false;
        if (screen != PIN) return;
        if (PinRules.locked(Profiles.lock(v.getContext(), lockKey), TrustedTime.now())) showLock();
        else pinMessage = "";
        v.invalidate();
    }

    private boolean locked() {
        return PinRules.locked(Profiles.lock(v.getContext(), lockKey), TrustedTime.now());
    }

    private void typeDigit(char ch) {
        if (busy || locked() || pin.length() >= PinRules.MAX_DIGITS) return;
        pinMessage = "";
        pin.append(ch);
        if (pin.length() == PinRules.MAX_DIGITS) submitPin();
    }

    private void submitPin() {
        if (busy || !PinRules.validFormat(pin.toString())) return;
        final String entered = pin.toString();
        final android.content.Context c = v.getContext();
        if (pinMode == PIN_NEW) {
            firstPin = entered;
            pin.setLength(0);
            pinMode = PIN_CONFIRM;
            changed();
            return;
        }
        if (pinMode == PIN_CONFIRM) {
            if (entered.equals(firstPin)) {
                final String chosen = entered;
                busy = true;
                HASHING.execute(() -> {
                    final java.util.Map<String, Object> hashed = PinRules.hash(chosen);
                    v.post(() -> {
                        busy = false;
                        draft.pin = hashed;
                        pin.setLength(0);
                        screen = EDIT;
                        changed();
                    });
                });
            } else {
                firstPin = "";
                pin.setLength(0);
                pinMode = PIN_NEW;
                pinMessage = Ui.tr("Os PINs não coincidem. Escolha de novo.", "The PINs don't match. Choose again.",
                        "Los PIN no coinciden. Elige de nuevo.", "الرمزان غير متطابقين. اختر من جديد.");
            }
            changed();
            return;
        }
        if (locked()) return;
        busy = true;
        final List<ProfileRules.Profile> candidates = new ArrayList<>();
        if (pinMode == PIN_TARGET && pinTarget != null) candidates.add(pinTarget);
        else candidates.addAll(ProfileRules.adultsWithPin(Profiles.all(c)));
        HASHING.execute(() -> {
            boolean match = false;
            for (ProfileRules.Profile p : candidates) match |= PinRules.verify(entered, p.pin);
            final boolean ok = match;
            v.post(() -> pinChecked(ok));
        });
        changed();
    }

    private void pinChecked(boolean match) {
        busy = false;
        if (screen != PIN) return;
        android.content.Context c = v.getContext();
        PinRules.Attempt a = PinRules.result(match, Profiles.lock(c, lockKey), TrustedTime.now());
        Profiles.setLock(c, lockKey, a.lock);
        pin.setLength(0);
        if (a.result == PinRules.OK) {
            pinMessage = "";
            Runnable done = pinDone;
            if (done != null) done.run();
            return;
        }
        if (a.result == PinRules.LOCKED) showLock();
        else {
            String left = Ui.digits(String.valueOf(PinRules.triesLeft(a.lock)));
            pinMessage = Ui.tr("PIN errado. Tentativas restantes: " + left, "Wrong PIN. Tries left: " + left,
                    "PIN incorrecto. Intentos restantes: " + left, "رمز خاطئ. المحاولات المتبقية: " + left);
        }
        changed();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (screen == NONE) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME) return false;
        switch (screen) {
            case PICKER: pickerKey(code); break;
            case PIN: pinKey(code); break;
            case MANAGE: manageKey(code); break;
            case EDIT: editKey(code); break;
            case NAME: nameKey(e); break;
            case CONFIRM: confirmKey(code); break;
            default: menuKey(code);
        }
        v.kickNow();
        return true;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void pickerKey(int code) {
        boolean manage = showsManage();
        int count = shown.size() + (manage ? 1 : 0);
        switch (Ui.dpad(code)) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                focus = Math.max(0, focus - 1);
                return;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focus = Math.min(count - 1, focus + 1);
                return;
            case KeyEvent.KEYCODE_BACK:
                if (required) host.exitWatchme();
                else close();
                return;
            default:
        }
        if (!isOk(code)) return;
        if (focus < shown.size()) choose(shown.get(focus));
        else openManage(true);
    }

    private void menuKey(int code) {
        int count = showsManage() ? 2 : 1;
        if (code == KeyEvent.KEYCODE_DPAD_UP) row = Math.max(0, row - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = Math.min(count - 1, row + 1);
        else if (code == KeyEvent.KEYCODE_BACK) close();
        else if (isOk(code)) {
            if (row == 0) openPicker(false);
            else openManage(false);
        }
    }

    private void pinKey(int code) {
        if (code == KeyEvent.KEYCODE_BACK) {
            Runnable back = pinBack;
            pin.setLength(0);
            if (back != null) back.run();
            else close();
            return;
        }
        if (busy) return;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            typeDigit((char) ('0' + code - KeyEvent.KEYCODE_0));
            return;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
            return;
        }
        // The pad: 1 2 3 / 4 5 6 / 7 8 9 / delete 0 OK (the digits keep their places in every language).
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (padFocus % 3 > 0) padFocus--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (padFocus % 3 < 2) padFocus++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (padFocus >= 3) padFocus -= 3;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (padFocus < 9) padFocus += 3;
                break;
            default:
                if (!isOk(code)) return;
                if (padFocus == 9) {
                    if (pin.length() > 0) pin.setLength(pin.length() - 1);
                } else if (padFocus == 11) submitPin();
                else typeDigit(padFocus == 10 ? '0' : (char) ('1' + padFocus));
        }
    }

    private void manageKey(int code) {
        boolean add = ProfileRules.canAdd(Profiles.all(v.getContext()));
        int tiles = shown.size() + (add ? 1 : 0);
        if (code == KeyEvent.KEYCODE_BACK) {
            leaveManage();
            return;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                row = Math.max(0, row - 1);
                return;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = Math.min(2, row + 1);
                return;
            default:
        }
        if (row == 0) {
            switch (Ui.dpad(code)) {
                case KeyEvent.KEYCODE_DPAD_LEFT: focus = Math.max(0, focus - 1); return;
                case KeyEvent.KEYCODE_DPAD_RIGHT: focus = Math.min(tiles - 1, focus + 1); return;
                default:
            }
            if (!isOk(code)) return;
            if (focus < shown.size()) editExisting(shown.get(focus));
            else editNew();
        } else if (row == 1 && (isOk(code) || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            Profiles.setAsk(v.getContext(), !Profiles.ask(v.getContext()));
        } else if (row == 2 && isOk(code)) {
            leaveManage();
        }
    }

    private void leaveManage() {
        if (dirtyActive) host.profileChanged();
        dirtyActive = false;
        if (managedFromPicker) openPicker(required);
        else close();
    }

    // MARK: - The editor

    private void editNew() {
        isNew = true;
        draft = new ProfileRules.Profile();
        draft.name = "";
        draft.color = Profiles.live(v.getContext()).size() % ProfileRules.COLORS;
        draft.avatar = ProfileRules.AVATARS[Profiles.live(v.getContext()).size() % ProfileRules.AVATARS.length];
        openEditor();
    }

    private void editExisting(ProfileRules.Profile p) {
        isNew = false;
        draft = p.copy();
        openEditor();
    }

    private void openEditor() {
        screen = EDIT;
        row = 0;
        changed();
    }

    private int[] editRows() {
        List<Integer> out = new ArrayList<>();
        out.add(E_NAME);
        out.add(E_COLOR);
        out.add(E_AVATAR);
        out.add(E_KIDS);
        out.add(E_PIN);
        if (draft.pin != null) out.add(E_NOPIN);
        if (!isNew && Profiles.live(v.getContext()).size() > 1) out.add(E_DELETE);
        out.add(E_SAVE);
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private void editKey(int code) {
        int[] rows = editRows();
        row = Math.min(row, rows.length - 1);
        int id = rows[row];
        if (code == KeyEvent.KEYCODE_BACK) {
            screen = MANAGE;
            shown = Profiles.live(v.getContext());
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            row = Math.max(0, row - 1);
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            row = Math.min(rows.length - 1, row + 1);
            return;
        }
        int dir = Ui.dpad(code);       // right to left: ← is the next value
        int by = dir == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : dir == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        boolean ok = isOk(code);
        if (!ok && by == 0) return;
        switch (id) {
            case E_NAME:
                if (ok) openName();
                break;
            case E_COLOR:
                draft.color = (draft.color + (ok ? 1 : by) + ProfileRules.COLORS) % ProfileRules.COLORS;
                break;
            case E_AVATAR: {
                int at = 0;
                for (int i = 0; i < ProfileRules.AVATARS.length; i++) if (ProfileRules.AVATARS[i].equals(draft.avatar)) at = i;
                int n = ProfileRules.AVATARS.length;
                draft.avatar = ProfileRules.AVATARS[(at + (ok ? 1 : by) + n) % n];
                break;
            }
            case E_KIDS:
                draft.kids = !draft.kids;
                break;
            case E_PIN:
                if (ok) askPin(PIN_NEW, null, null, () -> {
                    screen = EDIT;
                    changed();
                });
                break;
            case E_NOPIN:
                if (ok) draft.pin = null;
                break;
            case E_DELETE:
                if (ok) {
                    screen = CONFIRM;
                    confirmFocus = 0;
                    wipeSynced = false;
                    focus = 1;
                }
                break;
            default:
                if (ok) save();
        }
    }

    private void save() {
        android.content.Context c = v.getContext();
        if (!ProfileRules.validName(draft.name)) {
            pinMessage = Ui.tr("Dê um nome ao perfil.", "Give the profile a name.", "Ponle un nombre al perfil.", "أعطِ الملف اسماً.");
            row = 0;
            return;
        }
        boolean activeKidsChange = false;
        if (isNew) {
            Profiles.create(c, draft.name, draft.color, draft.avatar, draft.kids, draft.pin);
        } else {
            ProfileRules.Profile old = Profiles.get(c, draft.id);
            activeKidsChange = old != null && old.id.equals(Profiles.activeId(c)) && old.kids != draft.kids;
            Profiles.update(c, draft);
        }
        pinMessage = "";
        dirtyActive |= activeKidsChange;
        screen = MANAGE;
        shown = Profiles.live(c);
        focus = 0;
        row = 0;
    }

    // MARK: - The name

    private void openName() {
        nameEntry.setLength(0);
        nameEntry.append(draft.name);
        keyboard.reset();
        screen = NAME;
    }

    private void typeName(String s) {
        for (int i = 0; i < s.length() && nameEntry.length() < ProfileRules.NAME_MAX; i++) nameEntry.append(s.charAt(i));
    }

    private void finishName() {
        String n = ProfileRules.cleanName(nameEntry.toString());
        if (!n.isEmpty()) draft.name = n;
        screen = EDIT;
    }

    private void nameKey(KeyEvent e) {
        int code = e.getKeyCode();
        int unicode = e.getUnicodeChar();
        if (code == KeyEvent.KEYCODE_BACK) {
            finishName();
            return;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (nameEntry.length() > 0) nameEntry.setLength(nameEntry.length() - 1);
            return;
        }
        if (unicode > 0 && !Character.isISOControl(unicode) && !isOk(code)) {
            typeName(String.valueOf((char) unicode));     // a real keyboard, or a remote with letters
            return;
        }
        keyboard.onKey(code);
    }

    // MARK: - Delete

    private void confirmKey(int code) {
        boolean linked = Sync.isLinked();
        if (code == KeyEvent.KEYCODE_BACK) {
            screen = EDIT;
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && linked) focus = 0;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focus = 1;
        else if (focus == 0 && linked) {
            if (isOk(code) || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) wipeSynced = !wipeSynced;
        } else if (Ui.dpad(code) == KeyEvent.KEYCODE_DPAD_LEFT || Ui.dpad(code) == KeyEvent.KEYCODE_DPAD_RIGHT) {
            confirmFocus = 1 - confirmFocus;
        } else if (isOk(code)) {
            if (confirmFocus == 1) {
                android.content.Context c = v.getContext();
                boolean wasActive = draft.id.equals(Profiles.activeId(c));
                if (Profiles.delete(c, draft.id, linked && wipeSynced) && wasActive) dirtyActive = true;
                shown = Profiles.live(c);
                screen = MANAGE;
                focus = 0;
                row = 0;
            } else {
                screen = EDIT;
            }
        }
    }

    // MARK: - Drawing

    void draw(Canvas c) {
        if (head == null) sizeChanged();
        if (screen == MENU) {
            drawMenu(c);
            return;
        }
        v.fill.setColor(0xFF0B0B0D);
        c.drawRect(0, 0, v.w, v.h, v.fill);
        switch (screen) {
            case PICKER: drawPicker(c); break;
            case PIN: drawPin(c); break;
            case MANAGE: drawManage(c); break;
            case EDIT: drawEdit(c); break;
            case NAME: drawName(c); break;
            default: drawConfirm(c);
        }
    }

    /** The x of the centre of tile i of n in 1920 space, mirrored in right to left. */
    private float tileX(int i, int n) {
        float x = (960 + (i - (n - 1) / 2f) * PITCH) * v.k;
        return Ui.rtl ? v.w - x : x;
    }

    private void drawPicker(Canvas c) {
        float k = v.k;
        c.drawText(Ui.tr("Quem está assistindo?", "Who's watching?", "¿Quién está viendo?", "من يشاهد الآن؟"), v.w / 2f, 300 * k, head);
        boolean manage = showsManage();
        int n = shown.size() + (manage ? 1 : 0);
        for (int i = 0; i < n; i++) {
            float cx = tileX(i, n), cy = 560 * k;
            boolean focused = i == focus;
            if (i < shown.size()) {
                ProfileRules.Profile p = shown.get(i);
                Avatars.draw(c, p.avatar, p.color, cx, cy, AVATAR_R * k * (focused ? 1.08f : 1), focused);
                tag(c, p, cx, cy + (AVATAR_R + 56) * k, focused);
            } else {
                tile(c, cx, cy, focused);
                Symbols.draw(v.getContext(), c, "settings", cx, cy, 64 * k, focused ? 0xFF0B0B0D : 0xCCFFFFFF);
                name.setColor(focused ? 0xFFFFFFFF : 0x99FFFFFF);
                c.drawText(Ui.tr("Gerenciar perfis", "Manage profiles", "Administrar perfiles", "إدارة الملفات"), cx, cy + (AVATAR_R + 56) * k, name);
            }
        }
    }

    /** A profile's name under its avatar, with a lock when it has a PIN and "Kids" when it is one. */
    private void tag(Canvas c, ProfileRules.Profile p, float cx, float y, boolean focused) {
        float k = v.k;
        name.setColor(focused ? 0xFFFFFFFF : 0x99FFFFFF);
        String label = Ui.isolate(p.name);
        c.drawText(label, cx, y, name);
        float half = name.measureText(label) / 2;
        float lx = cx + half + 26 * k;
        if (p.hasPin()) {
            lock(c, lx, y - 12 * k, 14 * k, focused ? 0xFFFFFFFF : 0x99FFFFFF);
        }
        if (p.kids) {
            sub.setColor(focused ? 0xFFFFFFFF : 0x99FFFFFF);
            c.drawText(Ui.tr("Crianças", "Kids", "Niños", "أطفال"), cx, y + 40 * k, sub);
        }
    }

    private void tile(Canvas c, float cx, float cy, boolean focused) {
        float k = v.k, r = AVATAR_R * k;
        v.fill.setColor(focused ? 0xFFFFFFFF : 0x26FFFFFF);
        c.drawCircle(cx, cy, focused ? r * 1.08f : r, v.fill);
    }

    /** A small padlock centred at (cx, cy), s wide. */
    private void lock(Canvas c, float cx, float cy, float s, int color) {
        v.fill.setColor(color);
        box.set(cx - s * 0.5f, cy - s * 0.05f, cx + s * 0.5f, cy + s * 0.6f);
        c.drawRoundRect(box, s * 0.12f, s * 0.12f, v.fill);
        plain.setColor(color);
        plain.setStrokeWidth(s * 0.14f);
        box.set(cx - s * 0.32f, cy - s * 0.6f, cx + s * 0.32f, cy + s * 0.2f);
        c.drawArc(box, 180, 180, false, plain);
        c.drawLine(cx - s * 0.32f, cy - s * 0.2f, cx - s * 0.32f, cy, plain);
        c.drawLine(cx + s * 0.32f, cy - s * 0.2f, cx + s * 0.32f, cy, plain);
    }

    // ---- the PIN pad ----

    private void drawPin(Canvas c) {
        float k = v.k, cx = v.w / 2f;
        String title;
        switch (pinMode) {
            case PIN_ADULT: title = Ui.tr("PIN de um adulto", "A parent's PIN", "PIN de un adulto", "رمز أحد البالغين"); break;
            case PIN_NEW: title = Ui.tr("Escolha um PIN (4 a 6 números)", "Choose a PIN (4 to 6 digits)", "Elige un PIN (4 a 6 dígitos)",
                    "اختر رمزاً (من 4 إلى 6 أرقام)"); break;
            case PIN_CONFIRM: title = Ui.tr("Digite o PIN de novo", "Enter the PIN again", "Escribe el PIN de nuevo", "أدخل الرمز مرة أخرى"); break;
            default: title = Ui.tr("Digite o PIN", "Enter PIN", "Escribe el PIN", "أدخل الرمز");
        }
        if (pinMode == PIN_TARGET && pinTarget != null) {
            Avatars.draw(c, pinTarget.avatar, pinTarget.color, cx, 190 * k, 62 * k, false);
            name.setColor(0xFFFFFFFF);
            c.drawText(Ui.isolate(pinTarget.name), cx, 310 * k, name);
        }
        c.drawText(title, cx, 390 * k, big);
        // Six slots; the digits are only dots.
        for (int i = 0; i < PinRules.MAX_DIGITS; i++) {
            float x = cx + (i - (PinRules.MAX_DIGITS - 1) / 2f) * 62 * k, y = 470 * k;
            v.fill.setColor(i < pin.length() ? 0xFFFFFFFF : 0x33FFFFFF);
            c.drawCircle(x, y, (i < pin.length() ? 14 : 10) * k, v.fill);
        }
        if (!pinMessage.isEmpty()) {
            sub.setColor(0xFFFFB4A8);
            c.drawText(pinMessage, cx, 540 * k, sub);
        }
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", Ui.tr("OK", "OK", "OK", "موافق")};
        float left = cx - (3 * PAD_W + 2 * PAD_GAP) * k / 2, top = 590 * k;
        for (int i = 0; i < 12; i++) {
            float x = left + (i % 3) * (PAD_W + PAD_GAP) * k, y = top + (i / 3) * (PAD_H + PAD_GAP) * k;
            box.set(x, y, x + PAD_W * k, y + PAD_H * k);
            boolean focused = i == padFocus && !busy;
            v.fill.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
            c.drawRoundRect(box, 12 * k, 12 * k, v.fill);
            dot.setColor(focused ? 0xFF0B0B0D : 0xFFFFFFFF);
            c.drawText(labels[i], box.centerX(), box.centerY() + Ui.halfCap(dot), dot);
        }
        if (busy) {
            plain.setColor(0xCCFFFFFF);
            plain.setStrokeWidth(4 * k);
            Ui.spinner(c, cx + 330 * k, 470 * k, 20 * k, SystemClock.uptimeMillis() / 1000f, plain);
            v.kick();
        }
    }

    // ---- management ----

    private void drawManage(Canvas c) {
        float k = v.k;
        c.drawText(Ui.tr("Gerenciar perfis", "Manage profiles", "Administrar perfiles", "إدارة الملفات"), v.w / 2f, 230 * k, head);
        boolean add = ProfileRules.canAdd(Profiles.all(v.getContext()));
        int n = shown.size() + (add ? 1 : 0);
        for (int i = 0; i < n; i++) {
            float cx = tileX(i, n), cy = 450 * k;
            boolean focused = row == 0 && i == focus;
            if (i < shown.size()) {
                ProfileRules.Profile p = shown.get(i);
                Avatars.draw(c, p.avatar, p.color, cx, cy, AVATAR_R * k * (focused ? 1.08f : 1), focused);
                tag(c, p, cx, cy + (AVATAR_R + 56) * k, focused);
            } else {
                tile(c, cx, cy, focused);
                v.drawPlus(c, cx, cy, 30 * k, focused ? 0xFF0B0B0D : 0xCCFFFFFF);
                name.setColor(focused ? 0xFFFFFFFF : 0x99FFFFFF);
                c.drawText(Ui.tr("Adicionar perfil", "Add profile", "Añadir perfil", "إضافة ملف"), cx, cy + (AVATAR_R + 56) * k, name);
            }
        }
        String on = Ui.tr("Ligado", "On", "Activado", "مفعّل"), off = Ui.tr("Desligado", "Off", "Desactivado", "معطّل");
        settings.drawRow(c, 760 * k, row == 1, Ui.tr("Perguntar a cada início", "Ask on every start", "Preguntar en cada inicio", "اسأل عند كل تشغيل"),
                Profiles.ask(v.getContext()) ? on : off, false, false);
        settings.drawRow(c, (760 + CinemaSettings.ROW_H) * k, row == 2, Ui.tr("Concluir", "Done", "Listo", "تم"), "", false, false);
        Ui.text(c, Ui.tr("Cada perfil tem a própria lista, notas, progresso e ajustes de reprodução.",
                "Each profile has its own list, ratings, progress and viewing settings.",
                "Cada perfil tiene su propia lista, valoraciones, progreso y ajustes de reproducción.",
                "لكل ملف قائمته وتقييماته وتقدمه وإعدادات المشاهدة الخاصة به."), CinemaView.L * k, 940 * k, settings.helpFont(), v.w);
    }

    // ---- the editor ----

    private void drawEdit(Canvas c) {
        float k = v.k, x = CinemaView.L * k;
        Ui.text(c, isNew ? Ui.tr("Novo perfil", "New profile", "Perfil nuevo", "ملف جديد") : Ui.tr("Editar perfil", "Edit profile", "Editar perfil", "تعديل الملف"),
                x, 200 * k, settings.headerFont(), v.w);
        int[] rows = editRows();
        for (int i = 0; i < rows.length; i++) {
            int id = rows[i];
            boolean focused = i == row, button = id == E_NAME || id == E_PIN || id == E_NOPIN || id == E_DELETE || id == E_SAVE;
            settings.drawRow(c, (260 + i * CinemaSettings.ROW_H) * k, focused, editLabel(id), editValue(id), focused && !button, id == E_DELETE);
        }
        // The big avatar on the other side.
        float cx = Ui.rtl ? 330 * k : v.w - 330 * k;
        Avatars.draw(c, draft.avatar, draft.color, cx, 470 * k, 150 * k, false);
        name.setColor(0xFFFFFFFF);
        c.drawText(draft.name.isEmpty() ? "…" : Ui.isolate(draft.name), cx, 700 * k, name);
        if (!pinMessage.isEmpty()) {
            sub.setColor(0xFFFFB4A8);
            c.drawText(pinMessage, cx, 760 * k, sub);
        }
        Ui.text(c, editHelp(rows[Math.min(row, rows.length - 1)]), x, (260 + 9 * CinemaSettings.ROW_H + 30) * k, settings.helpFont(), v.w);
    }

    private String editLabel(int id) {
        switch (id) {
            case E_NAME: return Ui.tr("Nome", "Name", "Nombre", "الاسم");
            case E_COLOR: return Ui.tr("Cor", "Colour", "Color", "اللون");
            case E_AVATAR: return Ui.tr("Avatar", "Avatar", "Avatar", "الصورة الرمزية");
            case E_KIDS: return Ui.tr("Perfil infantil", "Kids profile", "Perfil infantil", "ملف الأطفال");
            case E_PIN: return draft.pin == null ? Ui.tr("Definir PIN", "Set PIN", "Definir PIN", "تعيين رمز") : Ui.tr("Mudar PIN", "Change PIN", "Cambiar PIN", "تغيير الرمز");
            case E_NOPIN: return Ui.tr("Remover PIN", "Remove PIN", "Quitar PIN", "إزالة الرمز");
            case E_DELETE: return Ui.tr("Excluir perfil", "Delete profile", "Eliminar perfil", "حذف الملف");
            default: return isNew ? Ui.tr("Criar perfil", "Create profile", "Crear perfil", "إنشاء الملف") : Ui.tr("Salvar", "Save", "Guardar", "حفظ");
        }
    }

    private String editValue(int id) {
        String on = Ui.tr("Ligado", "On", "Activado", "مفعّل"), off = Ui.tr("Desligado", "Off", "Desactivado", "معطّل");
        switch (id) {
            case E_NAME: return draft.name.isEmpty() ? Ui.tr("Toque para digitar", "Press OK to type", "OK para escribir", "اضغط OK للكتابة") : Ui.isolate(draft.name);
            case E_COLOR: return Ui.digits(String.valueOf(draft.color + 1)) + " / " + Ui.digits(String.valueOf(ProfileRules.COLORS));
            case E_AVATAR: {
                int at = 0;
                for (int i = 0; i < ProfileRules.AVATARS.length; i++) if (ProfileRules.AVATARS[i].equals(draft.avatar)) at = i + 1;
                return Ui.digits(String.valueOf(at)) + " / " + Ui.digits(String.valueOf(ProfileRules.AVATARS.length));
            }
            case E_KIDS: return draft.kids ? on : off;
            case E_PIN: return draft.pin == null ? "" : on;
            default: return Ui.rtl ? "‹" : "›";
        }
    }

    private String editHelp(int id) {
        switch (id) {
            case E_KIDS: return Ui.tr("Perfil infantil: filtro de família e cenas sensíveis puladas, sem poder mudar; sem Ajustes de conta.",
                    "Kids profile: the family filter and skipping sensitive scenes are on and can't be changed; no account settings.",
                    "Perfil infantil: filtro familiar y escenas sensibles omitidas, sin poder cambiarlos; sin ajustes de cuenta.",
                    "ملف الأطفال: فلتر العائلة وتخطي المشاهد الحساسة مفعّلان ولا يمكن تغييرهما، ولا إعدادات حساب.");
            case E_PIN: return Ui.tr("Com PIN, só quem sabe entra neste perfil.", "With a PIN, only someone who knows it can open this profile.",
                    "Con PIN, solo quien lo sabe puede abrir este perfil.", "مع الرمز، لا يفتح هذا الملف إلا من يعرفه.");
            case E_DELETE: return Ui.tr("Apaga a lista, notas e progresso deste perfil.", "Deletes this profile's list, ratings and progress.",
                    "Borra la lista, valoraciones y progreso de este perfil.", "يحذف قائمة هذا الملف وتقييماته وتقدمه.");
            default: return "";
        }
    }

    // ---- the name ----

    private void drawName(Canvas c) {
        float k = v.k, x = CinemaView.L * k;
        Ui.text(c, Ui.tr("Nome do perfil", "Profile name", "Nombre del perfil", "اسم الملف"), x, 200 * k, settings.headerFont(), v.w);
        float boxW = OnScreenKeyboard.width() * k, bx = Ui.mx(x, boxW, v.w);
        box.set(bx, 240 * k, bx + boxW, (240 + 84) * k);
        v.fill.setColor(0x26FFFFFF);
        c.drawRoundRect(box, 14 * k, 14 * k, v.fill);
        Paint f = settings.valueFont();
        String text = nameEntry.length() == 0 ? "" : Ui.isolate(nameEntry.toString());
        float start = Ui.rtl ? box.right - 28 * k : x + 28 * k;
        int was = f.getColor();
        Paint.Align align = f.getTextAlign();
        f.setColor(0xFFFFFFFF);
        f.setTextAlign(Ui.rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(text, start, box.centerY() + Ui.halfCap(f), f);
        f.setTextAlign(align);
        f.setColor(was);
        keyboard.draw(c, x, 350, true);
    }

    // ---- delete ----

    private void drawConfirm(Canvas c) {
        float k = v.k, x = CinemaView.L * k;
        Ui.text(c, Ui.tr("Excluir " + Ui.isolate(draft.name) + "?", "Delete " + Ui.isolate(draft.name) + "?",
                "¿Eliminar " + Ui.isolate(draft.name) + "?", "حذف " + Ui.isolate(draft.name) + "؟"), x, 300 * k, settings.headerFont(), v.w);
        String[] lines = {
            Ui.tr("A lista, as notas e o progresso deste perfil", "This profile's list, ratings and progress",
                    "La lista, valoraciones y progreso de este perfil", "قائمة هذا الملف وتقييماته وتقدمه"),
            Ui.tr("Isto não pode ser desfeito.", "This can't be undone.", "Esto no se puede deshacer.", "لا يمكن التراجع عن هذا."),
        };
        for (int i = 0; i < lines.length; i++) Ui.text(c, "•  " + lines[i], x, (390 + i * 56) * k, settings.labelFont(), v.w);
        boolean linked = Sync.isLinked();
        if (linked) {
            settings.drawRow(c, 520 * k, focus == 0, Ui.tr("Apagar também os dados sincronizados", "Also delete synced data",
                    "Borrar también los datos sincronizados", "حذف البيانات المتزامنة أيضاً"),
                    wipeSynced ? Ui.tr("Sim", "Yes", "Sí", "نعم") : Ui.tr("Não", "No", "No", "لا"), false, false);
        }
        String[] names = {Ui.tr("Cancelar", "Cancel", "Cancelar", "إلغاء"), Ui.tr("Excluir", "Delete", "Eliminar", "حذف")};
        float bx = x, by = 640 * k, bh = 74 * k;
        Paint f = settings.labelFont();
        for (int i = 0; i < 2; i++) {
            float bw = settings.valueFont().measureText(names[i]) + 96 * k;
            boolean focused = i == confirmFocus && (focus == 1 || !linked);
            float left = Ui.rtl ? v.w - bx - bw : bx;
            box.set(left, by, left + bw, by + bh);
            v.fill.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
            c.drawRoundRect(box, 14 * k, 14 * k, v.fill);
            f.setColor(focused ? 0xFF0B0B0D : i == 1 ? CinemaView.RED : 0xFFFFFFFF);
            Paint.Align was = f.getTextAlign();
            f.setTextAlign(Paint.Align.CENTER);
            c.drawText(names[i], left + bw / 2, by + bh / 2 + Ui.halfCap(f), f);
            f.setTextAlign(was);
            bx += bw + 28 * k;
        }
        if (!linked) focus = 1;
    }

    // ---- the avatar's menu ----

    private void drawMenu(Canvas c) {
        float k = v.k;
        String[] items = showsManage()
                ? new String[] {Ui.tr("Trocar de perfil", "Switch profile", "Cambiar de perfil", "تبديل الملف"),
                    Ui.tr("Gerenciar perfis", "Manage profiles", "Administrar perfiles", "إدارة الملفات")}
                : new String[] {Ui.tr("Trocar de perfil", "Switch profile", "Cambiar de perfil", "تبديل الملف")};
        float w = 420 * k, h = (items.length * 64 + 24) * k, right = (1920 - 96) * k;
        float left = Ui.rtl ? 96 * k : right - w, top = 150 * k;
        box.set(left, top, left + w, top + h);
        v.fill.setColor(0xF2181819);
        c.drawRoundRect(box, 16 * k, 16 * k, v.fill);
        Paint f = settings.labelFont();
        for (int i = 0; i < items.length; i++) {
            box.set(left + 12 * k, top + (12 + i * 64) * k, left + w - 12 * k, top + (12 + i * 64 + 56) * k);
            boolean focused = i == row;
            v.fill.setColor(focused ? 0xFFFFFFFF : 0);
            c.drawRoundRect(box, 12 * k, 12 * k, v.fill);
            f.setColor(focused ? 0xFF0B0B0D : 0xFFFFFFFF);
            Paint.Align was = f.getTextAlign();
            f.setTextAlign(Paint.Align.CENTER);
            c.drawText(items[i], box.centerX(), box.centerY() + Ui.halfCap(f), f);
            f.setTextAlign(was);
        }
    }
}
