package io.watchmatch;

import android.content.Context;
import android.graphics.Rect;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * SearchActivity's results, scrolled as Home's rows: the focused row with its title at the top, under the box and the chips, the rows
 * above it out of sight (not cut through), the last one too. Drawn only inside its own bounds (the page clips it), so
 * nothing scrolls over the box.
 */
final class SearchRows extends ScrollView {
    SearchRows(Context c) {
        super(c);
    }

    /** Room under the rows, a screen of it, so the last row's title can go to the top as well. */
    @Override protected void onMeasure(int w, int h) {
        int room = MeasureSpec.getSize(h);
        if (getChildCount() > 0) {
            View list = getChildAt(0);
            if (list.getPaddingBottom() != room) list.setPadding(0, 0, 0, room);
        }
        super.onMeasure(w, h);
    }

    /** No row below (or above): the focus stays, and the rows don't scroll on into that room. */
    @Override public boolean arrowScroll(int direction) {
        View current = findFocus();
        if (current == this) current = null;
        if (FocusFinder.getInstance().findNextFocus(this, current, direction) == null) return false;
        return super.arrowScroll(direction);
    }

    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
        if (getChildCount() == 0) return 0;
        ViewGroup list = (ViewGroup) getChildAt(0);
        int y = r.top;
        for (int i = 1; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v instanceof HorizontalScrollView && r.top >= v.getTop() && r.top < v.getBottom()) {
                y = list.getChildAt(i - 1).getTop();        // its title
                break;
            }
        }
        int max = Math.max(0, list.getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom()));
        return Math.max(0, Math.min(y, max)) - getScrollY();
    }

    /**
     * A row of cards that scrolls the focused one only up to the side margin (as Home's rows), so it never grows, with
     * its ring and words, past the screen's edge.
     */
    static final class Edge extends HorizontalScrollView {
        private final int margin;
        private final Rect room = new Rect();

        Edge(Context c, int margin) {
            super(c);
            this.margin = margin;
            setHorizontalScrollBarEnabled(false);
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            room.set(r.left - margin, r.top, r.right + margin, r.bottom);
            return super.computeScrollDeltaToGetChildRectOnScreen(room);
        }
    }
}
