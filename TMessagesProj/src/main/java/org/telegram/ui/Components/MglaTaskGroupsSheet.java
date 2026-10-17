package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaTask;
import org.telegram.messenger.MglaTasksStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class MglaTaskGroupsSheet extends BottomSheet {

    private static final int[] PALETTE_KEYS = new int[] {
        Theme.key_chat_messagePanelSend,
        Theme.key_windowBackgroundWhiteBlueText,
        Theme.key_color_blue,
        Theme.key_color_lightblue,
        Theme.key_color_green,
        Theme.key_color_lightgreen,
        Theme.key_color_yellow,
        Theme.key_color_orange,
        Theme.key_color_red,
        Theme.key_color_purple,
        Theme.key_color_cyan
    };

    private final Runnable onChanged;
    private LinearLayout listLayout;

    private final MglaTasksStorage.Listener storageListener = this::rebuildList;

    public MglaTaskGroupsSheet(Context context, Runnable onChanged) {
        super(context, false);
        this.onChanged = onChanged;

        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setAllowNestedScroll(true);

        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        scrollView.addView(root);

        TextView titleView = new TextView(context);
        titleView.setText("Группы");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, 8));

        TextView addView = new TextView(context);
        addView.setText("+ Новая группа");
        addView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        addView.setTextColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        addView.setGravity(Gravity.CENTER_VERTICAL);
        addView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        addView.setOnClickListener(v -> showNewGroupDialog());
        root.addView(addView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(listLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(scrollView);
        setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground));

        MglaTasksStorage.getInstance().addListener(storageListener);
        MglaTasksStorage.getInstance().ensureLoaded(this::rebuildList);
    }

    private void rebuildList() {
        if (listLayout == null) {
            return;
        }
        listLayout.removeAllViews();
        Context context = getContext();
        ArrayList<MglaTask.Group> groups = MglaTasksStorage.getInstance().getGroups();
        ArrayList<MglaTask> tasks = MglaTasksStorage.getInstance().getTasks();
        for (MglaTask.Group group : groups) {
            int count = 0;
            for (MglaTask task : tasks) {
                if (task.groupId == group.id) {
                    count++;
                }
            }

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));

            View dot = new View(context);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(group.color);
            dot.setBackground(dotBg);
            row.addView(dot, LayoutHelper.createLinear(16, 16, 0, 0, 0, 12, 0));

            TextView nameView = new TextView(context);
            nameView.setText(group.name);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView countView = new TextView(context);
            countView.setText(String.valueOf(count));
            countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            countView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            row.addView(countView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 12, 0, 0, 0));

            row.setOnClickListener(v -> showRenameDialog(group));
            row.setOnLongClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Удалить группу?");
                builder.setMessage(group.name);
                builder.setPositiveButton("Удалить", (d, w) -> {
                    MglaTasksStorage.getInstance().deleteGroup(group.id);
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    rebuildList();
                });
                builder.setNegativeButton("Отмена", null);
                builder.show();
                return true;
            });
            listLayout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void showRenameDialog(MglaTask.Group group) {
        Context context = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Переименовать группу");

        FrameLayout container = new FrameLayout(context);
        container.setPadding(dp(20), dp(8), dp(20), 0);
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setText(group.name);
        editText.setSelection(editText.length());
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton("Сохранить", (d, w) -> {
            String name = editText.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                return;
            }
            group.name = name;
            MglaTasksStorage.getInstance().saveGroup(group);
            if (onChanged != null) {
                onChanged.run();
            }
            rebuildList();
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void showNewGroupDialog() {
        Context context = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Новая группа");

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), 0);

        EditText editText = new EditText(context);
        editText.setHint("Название группы");
        editText.setSingleLine(true);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        int[] colors = new int[PALETTE_KEYS.length];
        for (int a = 0; a < PALETTE_KEYS.length; a++) {
            colors[a] = Theme.getColor(PALETTE_KEYS[a]);
        }
        final int[] selectedColor = new int[] { colors[0] };

        LinearLayout palette = new LinearLayout(context);
        palette.setOrientation(LinearLayout.HORIZONTAL);
        ArrayList<View> dots = new ArrayList<>();
        for (int a = 0; a < colors.length; a++) {
            int color = colors[a];
            View dot = new View(context);
            dot.setBackground(makeColorDot(color, a == 0));
            dot.setOnClickListener(v -> {
                selectedColor[0] = color;
                for (int b = 0; b < dots.size(); b++) {
                    dots.get(b).setBackground(makeColorDot(colors[b], dots.get(b) == v));
                }
            });
            dots.add(dot);
            palette.addView(dot, LayoutHelper.createLinear(28, 28, 0, 0, 8, 8, 0));
        }
        content.addView(palette, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        builder.setView(content);

        builder.setPositiveButton("Создать", (d, w) -> {
            String name = editText.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                return;
            }
            MglaTask.Group group = new MglaTask.Group();
            group.account = UserConfig.selectedAccount;
            group.name = name;
            group.color = selectedColor[0];
            group.sortOrder = MglaTasksStorage.getInstance().getGroups().size();
            MglaTasksStorage.getInstance().saveGroup(group);
            if (onChanged != null) {
                onChanged.run();
            }
            AndroidUtilities.runOnUIThread(this::rebuildList, 300);
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private GradientDrawable makeColorDot(int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (selected) {
            drawable.setStroke(dp(2), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        return drawable;
    }

    @Override
    public void dismissInternal() {
        MglaTasksStorage.getInstance().removeListener(storageListener);
        super.dismissInternal();
    }
}
