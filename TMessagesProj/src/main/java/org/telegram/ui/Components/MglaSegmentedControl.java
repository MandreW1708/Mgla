package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Two-option segmented control with a sliding oval/pill behind the selected label.
 */
public class MglaSegmentedControl extends FrameLayout {

    public interface Callback {
        void onOptionSelected(int index);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ovalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF ovalRect = new RectF();
    private final LinearLayout labelsRow;
    private final TextView[] labels = new TextView[2];

    private int selectedIndex;
    private float animatedIndex;
    private ValueAnimator animator;
    private Callback callback;

    public MglaSegmentedControl(Context context) {
        super(context);
        setWillNotDraw(false);
        setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        labelsRow = new LinearLayout(context);
        labelsRow.setOrientation(LinearLayout.HORIZONTAL);
        labelsRow.setGravity(Gravity.CENTER_VERTICAL);
        addView(labelsRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36));

        for (int i = 0; i < 2; i++) {
            final int index = i;
            TextView label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setSingleLine();
            label.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
            label.setOnClickListener(v -> setSelectedIndex(index, true));
            labels[i] = label;
            labelsRow.addView(label, new LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f));
        }

        updateColors();
    }

    public void setOptions(String left, String right, int selected) {
        labels[0].setText(left);
        labels[1].setText(right);
        selectedIndex = selected == 1 ? 1 : 0;
        animatedIndex = selectedIndex;
        updateLabelColors();
        invalidate();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index, boolean animate) {
        index = index == 1 ? 1 : 0;
        if (selectedIndex == index && (!animate || animator == null || !animator.isRunning())) {
            animatedIndex = index;
            updateLabelColors();
            invalidate();
            return;
        }
        final boolean changed = selectedIndex != index;
        selectedIndex = index;
        if (animator != null) {
            animator.cancel();
        }
        if (!animate) {
            animatedIndex = index;
            updateLabelColors();
            invalidate();
        } else {
            animator = ValueAnimator.ofFloat(animatedIndex, index);
            animator.setDuration(220);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.addUpdateListener(a -> {
                animatedIndex = (Float) a.getAnimatedValue();
                updateLabelColors();
                invalidate();
            });
            animator.start();
        }
        if (changed && callback != null) {
            callback.onOptionSelected(selectedIndex);
        }
    }

    private void updateColors() {
        // Same as Mgla settings block background so the control doesn't sit on a gray plate.
        trackPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ovalPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        updateLabelColors();
    }

    private void updateLabelColors() {
        int selectedColor = Theme.getColor(Theme.key_featuredStickers_buttonText);
        int idleColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        for (int i = 0; i < labels.length; i++) {
            float t = 1f - Math.min(1f, Math.abs(animatedIndex - i));
            labels[i].setTextColor(androidx.core.graphics.ColorUtils.blendARGB(idleColor, selectedColor, t));
            labels[i].setTypeface(t > 0.5f ? AndroidUtilities.bold() : null);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float right = getWidth() - getPaddingRight();
        float bottom = top + AndroidUtilities.dp(36);
        float radius = AndroidUtilities.dp(18);
        trackRect.set(left, top, right, bottom);
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

        float segmentWidth = (right - left) / 2f;
        float ovalLeft = left + segmentWidth * animatedIndex;
        float inset = AndroidUtilities.dp(3);
        ovalRect.set(ovalLeft + inset, top + inset, ovalLeft + segmentWidth - inset, bottom - inset);
        canvas.drawRoundRect(ovalRect, radius - inset, radius - inset, ovalPaint);

        super.dispatchDraw(canvas);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = getPaddingTop() + AndroidUtilities.dp(36) + getPaddingBottom();
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        int contentW = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        labelsRow.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(36), MeasureSpec.EXACTLY)
        );
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int x = getPaddingLeft();
        int y = getPaddingTop();
        labelsRow.layout(x, y, x + labelsRow.getMeasuredWidth(), y + labelsRow.getMeasuredHeight());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateColors();
    }
}
