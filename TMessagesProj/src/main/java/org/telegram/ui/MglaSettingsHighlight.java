package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;

/**
 * Scrolls to and flashes a settings row on Mgla screens, located by its visible title.
 * Mgla screens are hand-built (ScrollView/RecyclerListView without named *Row fields),
 * so {@link AndroidUtilities#scrollToFragmentRow} can't target them.
 */
public final class MglaSettingsHighlight {

    private static final int FIRST_ATTEMPT_DELAY_MS = 350;
    private static final int RETRY_DELAY_MS = 150;
    private static final int MAX_ATTEMPTS = 10;

    private MglaSettingsHighlight() {
    }

    public static void highlight(BaseFragment fragment, String title) {
        if (fragment == null || TextUtils.isEmpty(title)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> attempt(fragment, title, 0), FIRST_ATTEMPT_DELAY_MS);
    }

    private static void attempt(BaseFragment fragment, String title, int attempt) {
        if (fragment.isFinished || attempt >= MAX_ATTEMPTS) {
            return;
        }
        View root = fragment.getFragmentView();
        if (root == null || !root.isAttachedToWindow() || root.getWidth() == 0) {
            AndroidUtilities.runOnUIThread(() -> attempt(fragment, title, attempt + 1), RETRY_DELAY_MS);
            return;
        }
        View label = findLabel(root, title.trim());
        if (label == null) {
            // RecyclerView only lays out visible rows — page down and look again.
            RecyclerView rv = findFirst(root, RecyclerView.class);
            if (rv != null && rv.canScrollVertically(1)) {
                rv.scrollBy(0, (int) (rv.getHeight() * 0.7f));
            }
            AndroidUtilities.runOnUIThread(() -> attempt(fragment, title, attempt + 1), RETRY_DELAY_MS);
            return;
        }
        View row = resolveRow(label);
        int scrollDelay = scrollToRow(row);
        AndroidUtilities.runOnUIThread(() -> flash(row), scrollDelay);
    }

    private static View findLabel(View view, String title) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        CharSequence text = null;
        if (view instanceof TextView) {
            text = ((TextView) view).getText();
        } else if (view instanceof SimpleTextView) {
            text = ((SimpleTextView) view).getText();
        }
        if (text != null && title.equalsIgnoreCase(text.toString().trim())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findLabel(group.getChildAt(i), title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T findFirst(View view, Class<T> cls) {
        if (cls.isInstance(view)) {
            return cls.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findFirst(group.getChildAt(i), cls);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The whole cell: a RecyclerView item, or the nearest clickable ancestor. */
    private static View resolveRow(View label) {
        View current = label;
        while (true) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View) || isScroller((View) parent)) {
                break;
            }
            if (parent instanceof RecyclerView) {
                return current;
            }
            if (current != label && current.hasOnClickListeners()) {
                return current;
            }
            current = (View) parent;
        }
        View parent = label.getParent() instanceof View ? (View) label.getParent() : null;
        return parent != null && !isScroller(parent) ? parent : label;
    }

    private static boolean isScroller(View view) {
        return view instanceof ScrollView || view instanceof NestedScrollView || view instanceof RecyclerView;
    }

    /** @return delay before flashing, so the highlight is visible after scrolling. */
    private static int scrollToRow(View row) {
        ViewParent parent = row.getParent();
        if (parent instanceof RecyclerView) {
            RecyclerView rv = (RecyclerView) parent;
            int delta = row.getTop() - (rv.getHeight() - row.getHeight()) / 2;
            if (Math.abs(delta) > dp(8)) {
                rv.smoothScrollBy(0, delta);
                return 300;
            }
            return 0;
        }
        int y = 0;
        View current = row;
        while (current != null) {
            ViewParent p = current.getParent();
            if (p instanceof ScrollView || p instanceof NestedScrollView) {
                ViewGroup scroller = (ViewGroup) p;
                int target = Math.max(0, y - (scroller.getHeight() - row.getHeight()) / 2);
                if (Math.abs(target - scroller.getScrollY()) <= dp(8)) {
                    return 0;
                }
                if (scroller instanceof ScrollView) {
                    ((ScrollView) scroller).smoothScrollTo(0, target);
                } else {
                    ((NestedScrollView) scroller).smoothScrollTo(0, target);
                }
                return 300;
            }
            y += current.getTop();
            current = p instanceof View ? (View) p : null;
        }
        return 0;
    }

    private static void flash(View row) {
        if (!row.isAttachedToWindow() || row.getWidth() == 0) {
            return;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(10));
        drawable.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        drawable.setBounds(dp(4), dp(2), row.getWidth() - dp(4), row.getHeight() - dp(2));
        drawable.setAlpha(0);
        row.getOverlay().add(drawable);

        // Two pulses, like the stock list highlight but readable on custom cells.
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f, 0.35f, 1f, 0f);
        animator.setDuration(1600);
        animator.addUpdateListener(a -> {
            drawable.setAlpha((int) (0x40 * (float) a.getAnimatedValue()));
            row.invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                row.getOverlay().remove(drawable);
            }
        });
        animator.start();
    }
}
