package org.telegram.ui.Components.chat.buttons;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class ActionBarBackUnreadBadge extends View {

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int count;
    private int circleWidth;
    private String countText;

    public ActionBarBackUnreadBadge(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(dp(11));
        textPaint.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
    }

    public void setCount(int count) {
        if (this.count == count) {
            return;
        }
        this.count = count;
        if (count <= 0) {
            countText = null;
            circleWidth = 0;
            setVisibility(GONE);
        } else {
            countText = AndroidUtilities.formatWholeNumber(count, 0);
            circleWidth = Math.max(dp(18), dp(10) + (int) Math.ceil(textPaint.measureText(countText)));
            setVisibility(VISIBLE);
        }
        requestLayout();
        invalidate();
    }

    public void updateColors() {
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (count <= 0) {
            setMeasuredDimension(0, 0);
            return;
        }
        setMeasuredDimension(circleWidth, dp(18));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (count <= 0 || countText == null) {
            return;
        }
        backgroundPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter, resourcesProvider));
        textPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText, resourcesProvider));

        final float cy = getMeasuredHeight() / 2f;
        rect.set(0, cy - dp(9), circleWidth, cy + dp(9));
        canvas.drawRoundRect(rect, dp(9), dp(9), backgroundPaint);
        canvas.drawText(countText, circleWidth / 2f, cy + dp(4), textPaint);
    }
}
