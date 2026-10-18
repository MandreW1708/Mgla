package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaTask;
import org.telegram.messenger.MglaTaskRepeat;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MglaTaskCell extends FrameLayout {

    private final CheckView checkView;
    private final TextView titleView;
    private final LinearLayout content;
    private final LinearLayout metaLayout;
    private final TextView dueView;
    private final TextView repeatView;
    private final ImageView remindIcon;
    private final View groupDot;
    private final GradientDrawable groupDotDrawable;
    private final TextView groupNameView;
    private final TextView subProgressView;
    private final TextView noteView;
    private final TextView openButton;

    private final SimpleDateFormat dueFormat = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
    private final Date dateInstance = new Date();

    private MglaTask currentTask;
    private OnClickListener checkClickListener;
    private OnClickListener openClickListener;

    public MglaTaskCell(Context context) {
        super(context);
        setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));

        FrameLayout checkContainer = new FrameLayout(context);
        checkView = new CheckView(context);
        checkContainer.addView(checkView, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        checkContainer.setOnClickListener(v -> {
            checkView.setChecked(!checkView.isChecked(), true);
            if (checkClickListener != null) {
                checkClickListener.onClick(MglaTaskCell.this);
            }
        });
        addView(checkContainer, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(0, dp(10), 0, dp(10));
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 56, 0, 16, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        metaLayout = new LinearLayout(context);
        metaLayout.setOrientation(LinearLayout.HORIZONTAL);
        metaLayout.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(metaLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        dueView = makeMetaText(context);
        metaLayout.addView(dueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

        remindIcon = makeMetaIcon(context, R.drawable.outline_message_time_24);
        metaLayout.addView(remindIcon, LayoutHelper.createLinear(14, 14, 0, 0, 10, 0));

        repeatView = makeMetaText(context);
        metaLayout.addView(repeatView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

        groupDotDrawable = new GradientDrawable();
        groupDotDrawable.setShape(GradientDrawable.OVAL);
        groupDot = new View(context);
        groupDot.setBackground(groupDotDrawable);
        metaLayout.addView(groupDot, LayoutHelper.createLinear(8, 8, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        groupNameView = makeMetaText(context);
        metaLayout.addView(groupNameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

        subProgressView = makeMetaText(context);
        metaLayout.addView(subProgressView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

        noteView = makeMetaText(context);
        metaLayout.addView(noteView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        openButton = new TextView(context);
        openButton.setText("Открыть");
        openButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        openButton.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        openButton.setTextColor(accent);
        openButton.setGravity(Gravity.CENTER);
        openButton.setSingleLine(true);
        openButton.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable openBg = new GradientDrawable();
        openBg.setCornerRadius(dp(16));
        openBg.setColor(ColorUtils.setAlphaComponent(accent, 26));
        openButton.setBackground(openBg);
        openButton.setVisibility(GONE);
        openButton.setOnClickListener(v -> {
            if (openClickListener != null) {
                openClickListener.onClick(MglaTaskCell.this);
            }
        });
        addView(openButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
    }

    private TextView makeMetaText(Context context) {
        TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        return textView;
    }

    private ImageView makeMetaIcon(Context context, int resId) {
        ImageView imageView = new ImageView(context);
        imageView.setImageResource(resId);
        imageView.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        return imageView;
    }

    public MglaTask getTask() {
        return currentTask;
    }

    public void setOnCheckClickListener(OnClickListener listener) {
        checkClickListener = listener;
    }

    public void setOnOpenClickListener(OnClickListener listener) {
        openClickListener = listener;
    }

    public void setTask(MglaTask task, MglaTask.Group group) {
        currentTask = task;
        boolean done = task.done;
        checkView.setChecked(done, false);

        boolean linked = task.dialogId != 0;
        openButton.setVisibility(linked ? VISIBLE : GONE);
        FrameLayout.LayoutParams contentLp = (FrameLayout.LayoutParams) content.getLayoutParams();
        int rightMargin = dp(linked ? 92 : 16);
        if (contentLp.rightMargin != rightMargin) {
            contentLp.rightMargin = rightMargin;
            content.setLayoutParams(contentLp);
        }

        titleView.setText(task.title);
        titleView.getPaint().setStrikeThruText(done);
        titleView.setTextColor(Theme.getColor(done ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));

        boolean hasMeta = false;
        if (task.dueAt > 0) {
            dateInstance.setTime(task.dueAt);
            dueView.setText(dueFormat.format(dateInstance));
            boolean overdue = task.dueAt < System.currentTimeMillis() && !done && !task.hasRepeat();
            dueView.setTextColor(Theme.getColor(overdue ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText));
            dueView.setVisibility(VISIBLE);
            hasMeta = true;
        } else {
            dueView.setVisibility(GONE);
        }

        if (task.remindAt > 0) {
            remindIcon.setVisibility(VISIBLE);
            hasMeta = true;
        } else {
            remindIcon.setVisibility(GONE);
        }

        String repeatLabel = MglaTaskRepeat.label(task);
        if (repeatLabel != null) {
            repeatView.setText("↻ " + repeatLabel);
            repeatView.setVisibility(VISIBLE);
            hasMeta = true;
        } else {
            repeatView.setVisibility(GONE);
        }

        if (group != null) {
            groupDotDrawable.setColor(group.color);
            groupDot.setVisibility(VISIBLE);
            groupNameView.setText(group.name);
            groupNameView.setVisibility(VISIBLE);
            hasMeta = true;
        } else {
            groupDot.setVisibility(GONE);
            groupNameView.setVisibility(GONE);
        }

        if (task.subItems != null && !task.subItems.isEmpty()) {
            int doneCount = 0;
            for (MglaTask.SubItem item : task.subItems) {
                if (item.done) {
                    doneCount++;
                }
            }
            subProgressView.setText(doneCount + "/" + task.subItems.size());
            subProgressView.setVisibility(VISIBLE);
            hasMeta = true;
        } else {
            subProgressView.setVisibility(GONE);
        }

        if (!TextUtils.isEmpty(task.note)) {
            noteView.setText(task.note);
            noteView.setVisibility(VISIBLE);
            hasMeta = true;
        } else {
            noteView.setVisibility(GONE);
        }

        metaLayout.setVisibility(hasMeta ? VISIBLE : GONE);
    }

    private static class CheckView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private boolean checked;
        private float progress;
        private ValueAnimator animator;

        CheckView(Context context) {
            super(context);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean checked, boolean animate) {
            this.checked = checked;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (animate) {
                animator = ValueAnimator.ofFloat(progress, checked ? 1f : 0f);
                animator.setDuration(150);
                animator.setInterpolator(new DecelerateInterpolator());
                animator.addUpdateListener(a -> {
                    progress = (float) a.getAnimatedValue();
                    invalidate();
                });
                animator.start();
            } else {
                progress = checked ? 1f : 0f;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - dp(1);
            if (progress < 1f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                paint.setAlpha((int) (255 * (1f - progress)));
                canvas.drawCircle(cx, cy, radius - dp(1), paint);
            }
            if (progress > 0f) {
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Theme.getColor(Theme.key_chat_messagePanelSend));
                canvas.drawCircle(cx, cy, radius * progress, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha((int) (255 * progress));
                checkPath.reset();
                checkPath.moveTo(cx - radius * 0.42f, cy);
                checkPath.lineTo(cx - radius * 0.08f, cy + radius * 0.36f);
                checkPath.lineTo(cx + radius * 0.46f, cy - radius * 0.32f);
                canvas.drawPath(checkPath, paint);
                paint.setAlpha(255);
            }
        }
    }
}
