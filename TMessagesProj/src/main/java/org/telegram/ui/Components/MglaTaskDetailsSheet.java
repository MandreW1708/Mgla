package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaTask;
import org.telegram.messenger.MglaTaskRepeat;
import org.telegram.messenger.MglaTasksStorage;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MglaTaskDetailsSheet extends BottomSheet {

    private final MglaTask task;
    private final Runnable onEdit;
    private final Runnable onDelete;
    private Runnable onOpenMessage;

    private final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault());
    private final Date dateInstance = new Date();

    public MglaTaskDetailsSheet(Context context, MglaTask task, Runnable onEdit, Runnable onDelete) {
        super(context, false);
        this.task = task;
        this.onEdit = onEdit;
        this.onDelete = onDelete;

        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        scrollView.addView(root);

        TextView titleView = new TextView(context);
        titleView.setText(task.title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextIsSelectable(true);
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        if (!TextUtils.isEmpty(task.note)) {
            TextView noteView = new TextView(context);
            noteView.setText(task.note);
            noteView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            noteView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            noteView.setTextIsSelectable(true);
            root.addView(noteView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        }

        boolean hasMeta = false;
        if (task.dueAt > 0) {
            root.addView(makeMetaRow(context, "Дата и время", formatDateTime(task.dueAt)));
            hasMeta = true;
        }
        if (task.remindAt > 0) {
            String remindText = task.remindAt == task.dueAt
                    ? "В момент срока"
                    : formatDateTime(task.remindAt);
            root.addView(makeMetaRow(context, "Напомнить", remindText));
            hasMeta = true;
        }
        String repeatLabel = MglaTaskRepeat.label(task);
        if (repeatLabel != null) {
            root.addView(makeMetaRow(context, "Повтор", repeatLabel));
            hasMeta = true;
        }
        MglaTask.Group group = null;
        if (task.groupId != 0) {
            for (MglaTask.Group g : MglaTasksStorage.getInstance().getGroups()) {
                if (g.id == task.groupId) {
                    group = g;
                    break;
                }
            }
        }
        if (group != null) {
            root.addView(makeMetaRow(context, "Группа", group.name));
            hasMeta = true;
        }
        if (hasMeta) {
            View divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 12, 0, 4));
        }

        if (task.subItems != null && !task.subItems.isEmpty()) {
            TextView subTitle = new TextView(context);
            subTitle.setText("Подзадачи");
            subTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            subTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            root.addView(subTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 6));

            LinearLayout subLayout = new LinearLayout(context);
            subLayout.setOrientation(LinearLayout.VERTICAL);
            root.addView(subLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            for (MglaTask.SubItem item : task.subItems) {
                subLayout.addView(makeSubItemRow(context, item), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
            }
        }

        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);

        if (task.dialogId != 0) {
            TextView openButton = makeFilledButton(context, "Открыть сообщение", accent);
            openButton.setOnClickListener(v -> {
                dismiss();
                if (onOpenMessage != null) {
                    onOpenMessage.run();
                }
            });
            root.addView(openButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 12, 0, 0));
        }

        TextView editButton = makeFilledButton(context, "Изменить", accent);
        editButton.setOnClickListener(v -> {
            dismiss();
            if (onEdit != null) {
                onEdit.run();
            }
        });
        root.addView(editButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 12, 0, 0));

        if (onDelete != null) {
            TextView deleteButton = new TextView(context);
            deleteButton.setText("Удалить");
            deleteButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            deleteButton.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            deleteButton.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            deleteButton.setGravity(Gravity.CENTER);
            deleteButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            deleteButton.setOnClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle("Удалить задачу?");
                builder.setMessage(task.title);
                builder.setPositiveButton("Удалить", (d, w) -> {
                    dismiss();
                    onDelete.run();
                });
                builder.setNegativeButton("Отмена", null);
                builder.show();
            });
            root.addView(deleteButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 4, 0, 0));
        }

        setCustomView(scrollView);
        setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground));
    }

    public void setOnOpenMessage(Runnable onOpenMessage) {
        this.onOpenMessage = onOpenMessage;
    }

    private String formatDateTime(long time) {
        dateInstance.setTime(time);
        return dateTimeFormat.format(dateInstance);
    }

    private TextView makeFilledButton(Context context, String text, int accent) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(accent);
        button.setBackground(bg);
        return button;
    }

    private View makeMetaRow(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView valueView = new TextView(context);
        valueView.setText(value);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        row.setPadding(0, dp(7), 0, dp(7));
        return row;
    }

    private View makeSubItemRow(Context context, MglaTask.SubItem item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        SubItemCheckView checkView = new SubItemCheckView(context);
        checkView.setChecked(item.done);
        SubItemTitleView titleView = new SubItemTitleView(context, item.title, item.done);
        checkView.setOnClickListener(v -> {
            boolean done = !item.done;
            MglaTasksStorage.getInstance().setSubItemDone(task.id, item.id, done);
            item.done = done;
            checkView.setChecked(done);
            titleView.setStrikeState(done);
        });
        row.addView(checkView, LayoutHelper.createLinear(20, 20));
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        return row;
    }

    private static class SubItemTitleView extends TextView {

        SubItemTitleView(Context context, String text, boolean strike) {
            super(context);
            setText(text);
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            setStrikeState(strike);
        }

        void setStrikeState(boolean strike) {
            getPaint().setStrikeThruText(strike);
            setTextColor(Theme.getColor(strike ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));
        }
    }

    private static class SubItemCheckView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private boolean checked;

        SubItemCheckView(Context context) {
            super(context);
        }

        void setChecked(boolean checked) {
            this.checked = checked;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = size / 2f - dp(1);
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Theme.getColor(Theme.key_chat_messagePanelSend));
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFFFFFFFF);
                checkPath.reset();
                checkPath.moveTo(cx - radius * 0.42f, cy);
                checkPath.lineTo(cx - radius * 0.08f, cy + radius * 0.36f);
                checkPath.lineTo(cx + radius * 0.46f, cy - radius * 0.32f);
                canvas.drawPath(checkPath, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                canvas.drawCircle(cx, cy, radius, paint);
            }
        }
    }
}
