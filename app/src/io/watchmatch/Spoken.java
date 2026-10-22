package io.watchmatch;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a screen drawn by hand on one Canvas usable with TalkBack: every item the remote can reach is a virtual node,
 * TalkBack's focus and the remote's focus follow each other, and a TalkBack click and an OK press that arrive for the
 * same physical press count once.
 */
final class Spoken extends AccessibilityNodeProvider {
    interface Source {
        /** Appends the ids (positive) of the items reachable now, in reading order. */
        void items(List<Integer> out);
        /** What to say for the item; empty means there is no node. */
        String text(int id);
        /** The item's bounds in the host's coordinates; false when it is off screen. */
        boolean bounds(int id, Rect out);
        /** The item with remote focus, or -1. */
        int focused();
        void focus(int id);
        void click(int id);
    }

    private static final long SAME_PRESS_MS = 600, SETTLE_MS = 600, AGAIN_MS = 400;

    private final View host;
    private final Source source;
    private final AccessibilityManager manager;
    private final List<Integer> ids = new ArrayList<>();
    private final Rect rect = new Rect();
    private final int[] where = new int[2];
    private final Runnable settle = this::sendRoot;
    private int lastFocused = -1, accessibilityFocused = -1;
    private String lastText = "";
    private boolean following;
    private long keyAt, talkBackAt;

    Spoken(View host, Source source) {
        this.host = host;
        this.source = source;
        manager = (AccessibilityManager) host.getContext().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
    }

    /** After every focus move or visible change. */
    void focusMoved() {
        if (!enabled()) return;
        sendRoot();
        host.removeCallbacks(settle);
        host.postDelayed(settle, SETTLE_MS);
        int id = source.focused();
        String text = id < 0 ? "" : source.text(id);
        if (id == lastFocused && text.equals(lastText)) return;
        lastFocused = id;
        lastText = text;
        if (!following && id >= 0) sendVirtual(id, AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    /** When the screen comes back: announce the focused item again. */
    void readAgain() {
        lastFocused = -1;
        host.postDelayed(this::focusMoved, AGAIN_MS);
    }

    /** On the remote's OK. False when TalkBack already clicked for this press: don't activate twice. */
    boolean keyClick() {
        keyAt = SystemClock.uptimeMillis();
        return keyAt - talkBackAt > SAME_PRESS_MS;
    }

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
        if (id == HOST_VIEW_ID) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host);
            host.onInitializeAccessibilityNodeInfo(info);
            ids.clear();
            source.items(ids);
            for (int item : ids) if (source.bounds(item, rect)) info.addChild(host, item);
            return info;
        }
        String text = source.text(id);
        if (text == null || text.isEmpty() || !source.bounds(id, rect)) return null;
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host, id);
        info.setPackageName(host.getContext().getPackageName());
        info.setClassName("android.widget.Button");
        info.setParent(host);
        info.setText(text);
        info.setBoundsInParent(new Rect(rect));
        host.getLocationOnScreen(where);
        Rect screen = new Rect(rect);
        screen.offset(where[0], where[1]);
        info.setBoundsInScreen(screen);
        boolean focused = id == source.focused(), spoken = id == accessibilityFocused;
        info.setEnabled(true);
        info.setVisibleToUser(true);
        info.setImportantForAccessibility(true);
        info.setFocusable(true);
        info.setFocused(focused);
        info.setClickable(true);
        info.addAction(AccessibilityAction.ACTION_CLICK);
        if (!focused) info.addAction(AccessibilityAction.ACTION_FOCUS);
        info.setAccessibilityFocused(spoken);
        info.addAction(spoken ? AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        return info;
    }

    @Override
    public boolean performAction(int id, int action, Bundle args) {
        if (id == HOST_VIEW_ID) return host.performAccessibilityAction(action, args);
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (accessibilityFocused == id) return false;
                accessibilityFocused = id;
                follow(id);
                sendVirtual(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (accessibilityFocused != id) return false;
                accessibilityFocused = -1;
                sendVirtual(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                return true;
            case AccessibilityNodeInfo.ACTION_FOCUS:
                follow(id);
                return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                talkBackAt = SystemClock.uptimeMillis();
                if (talkBackAt - keyAt > SAME_PRESS_MS) {
                    follow(id);
                    source.click(id);
                }
                return true;
            default:
                return false;
        }
    }

    /** Moves the remote's focus to `id` without announcing it a second time. */
    private void follow(int id) {
        following = true;
        try {
            if (source.focused() != id) source.focus(id);
        } finally {
            following = false;
        }
        lastFocused = source.focused();
        lastText = lastFocused < 0 ? "" : source.text(lastFocused);
    }

    private boolean enabled() {
        return manager != null && manager.isEnabled();
    }

    private void sendRoot() {
        ViewParent parent = host.getParent();
        if (parent == null || !enabled()) return;
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        host.onInitializeAccessibilityEvent(e);
        e.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        parent.requestSendAccessibilityEvent(host, e);
    }

    private void sendVirtual(int id, int type) {
        ViewParent parent = host.getParent();
        if (parent == null || !enabled()) return;
        AccessibilityEvent e = AccessibilityEvent.obtain(type);
        e.setSource(host, id);
        e.setPackageName(host.getContext().getPackageName());
        e.setClassName("android.widget.Button");
        e.setEnabled(true);
        e.getText().add(source.text(id));
        parent.requestSendAccessibilityEvent(host, e);
    }
}
