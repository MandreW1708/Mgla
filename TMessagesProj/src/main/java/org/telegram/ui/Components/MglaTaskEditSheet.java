package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MglaTask;
import org.telegram.messenger.MglaTaskRepeat;
import org.telegram.messenger.MglaTasksStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class MglaTaskEditSheet extends BottomSheet {

    private static final long[] REMIND_OFFSETS = new long[] { 0, 10 * 60 * 1000L, 60 * 60 * 1000L, 24 * 60 * 60 * 1000L };
    private static final String[] REMIND_TITLES = new String[] { "В момент срока", "За 10 минут", "За час", "За день", "Не напоминать" };

    private final boolean isNew;
    private final MglaTask editingTask;
    private final Utilities.Callback<MglaTask> onSave;
    private final Runnable onDelete;
    private long dueAt;
    private int remindIndex = 4;
    private int repeatInterval;
    private int repeatUnit;
    private MglaTask.Group selectedGroup;
    private final ArrayList<MglaTask.SubItem> subItems = new ArrayList<>();

    private EditTextBoldCursor titleEdit;
    private EditTextBoldCursor noteEdit;
    private TextView dateValue;
    private LinearLayout dateRow;
    private TextView remindValue;
    private LinearLayout remindRow;
    private TextView repeatValue;
    private LinearLayout repeatRow;
    private TextView groupValue;
    private LinearLayout subItemsLayout;
    private TextView saveButton;

    public MglaTaskEditSheet(Context context, MglaTask task, Utilities.Callback<MglaTask> onSave, Runnable onDelete) {
        super(context, true);
        this.onSave = onSave;
        this.onDelete = onDelete;
        this.isNew = task == null;
        this.editingTask = task;

        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setAllowNestedScroll(true);

        if (task != null) {
            dueAt = task.dueAt;
            repeatInterval = task.repeatInterval;
            repeatUnit = task.repeatUnit;
            if (task.remindAt > 0 && task.dueAt > 0) {
                long diff = task.dueAt - task.remindAt;
                for (int a = 0; a < REMIND_OFFSETS.length; a++) {
                    if (REMIND_OFFSETS[a] == diff) {
                        remindIndex = a;
                        break;
                    }
                }
            }
            for (MglaTask.SubItem item : task.subItems) {
                MglaTask.SubItem copy = new MglaTask.SubItem();
                copy.id = item.id;
                copy.taskId = item.taskId;
                copy.title = item.title;
                copy.done = item.done;
                copy.sortOrder = item.sortOrder;
                subItems.add(copy);
            }
        }

        MglaTasksStorage.getInstance().ensureLoaded(() -> {
            if (task != null && task.groupId != 0) {
                for (MglaTask.Group group : MglaTasksStorage.getInstance().getGroups()) {
                    if (group.id == task.groupId) {
                        selectedGroup = group;
                        break;
                    }
                }
            }
            updateGroupValue();
        });

        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        scrollView.addView(root);

        TextView titleView = new TextView(context);
        titleView.setText(isNew ? "Новая задача" : "Задача");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, 12));

        titleEdit = makeEditText(context, "Название", true);
        titleEdit.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        if (task != null && task.title != null) {
            titleEdit.setText(task.title);
            titleEdit.setSelection(titleEdit.length());
        }
        titleEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSaveButton();
            }
        });
        root.addView(titleEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(makeUnderline(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 0, 12));

        noteEdit = makeEditText(context, "Заметка", false);
        noteEdit.setMinLines(1);
        noteEdit.setMaxLines(3);
        noteEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (task != null && task.note != null) {
            noteEdit.setText(task.note);
        }
        root.addView(noteEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(makeUnderline(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 0, 8));

        dateRow = makeRow(context, "Дата и время", true);
        dateValue = (TextView) dateRow.getTag();
        dateRow.setOnClickListener(v -> showDatePicker());
        dateRow.setOnLongClickListener(v -> {
            if (dueAt != 0) {
                dueAt = 0;
                repeatInterval = 0;
                repeatUnit = 0;
                updateDateValue();
                updateRemindValue();
                updateRepeatValue();
            }
            return true;
        });
        root.addView(dateRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        remindRow = makeRow(context, "Напомнить", false);
        remindValue = (TextView) remindRow.getTag();
        remindRow.setOnClickListener(v -> showRemindPicker());
        root.addView(remindRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        repeatRow = makeRow(context, "Повтор", false);
        repeatValue = (TextView) repeatRow.getTag();
        repeatRow.setOnClickListener(v -> showRepeatPicker());
        root.addView(repeatRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout groupRow = makeRow(context, "Группа", false);
        groupValue = (TextView) groupRow.getTag();
        groupRow.setOnClickListener(v -> showGroupPicker());
        root.addView(groupRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView subItemsTitle = new TextView(context);
        subItemsTitle.setText("Подзадачи");
        subItemsTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subItemsTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        subItemsTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(subItemsTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 4));

        subItemsLayout = new LinearLayout(context);
        subItemsLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(subItemsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (MglaTask.SubItem item : subItems) {
            addSubItemRow(item, false);
        }

        TextView addSubItem = new TextView(context);
        addSubItem.setText("+ Добавить подзадачу");
        addSubItem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addSubItem.setTextColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        addSubItem.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        addSubItem.setGravity(Gravity.CENTER_VERTICAL);
        addSubItem.setPadding(dp(28), 0, 0, 0);
        addSubItem.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        addSubItem.setOnClickListener(v -> {
            MglaTask.SubItem item = new MglaTask.SubItem();
            item.taskId = task != null ? task.id : 0;
            item.sortOrder = subItems.size();
            subItems.add(item);
            addSubItemRow(item, true);
        });
        root.addView(addSubItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        saveButton = new TextView(context);
        saveButton.setText("Сохранить");
        saveButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        saveButton.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        saveButton.setTextColor(0xFFFFFFFF);
        saveButton.setGravity(Gravity.CENTER);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setCornerRadius(dp(20));
        saveBg.setColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        saveButton.setBackground(saveBg);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 12, 0, 0));

        if (!isNew && onDelete != null) {
            TextView deleteButton = new TextView(context);
            deleteButton.setText("Удалить");
            deleteButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            deleteButton.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            deleteButton.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            deleteButton.setGravity(Gravity.CENTER);
            deleteButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            deleteButton.setOnClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Удалить задачу?");
                builder.setMessage(task.title);
                builder.setPositiveButton("Удалить", (d, w) -> {
                    onDelete.run();
                    dismiss();
                });
                builder.setNegativeButton("Отмена", null);
                builder.show();
            });
            root.addView(deleteButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 4, 0, 0));
        }

        setCustomView(scrollView);
        setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground));

        updateDateValue();
        updateRemindValue();
        updateRepeatValue();
        updateGroupValue();
        updateSaveButton();
    }

    private EditTextBoldCursor makeEditText(Context context, String hint, boolean singleLine) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        editText.setHint(hint);
        editText.setBackground(null);
        editText.setPadding(0, dp(10), 0, dp(10));
        editText.setCursorColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        editText.setCursorSize(dp(20));
        if (singleLine) {
            editText.setSingleLine(true);
        }
        return editText;
    }

    private View makeUnderline(Context context) {
        View view = new View(context);
        view.setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        return view;
    }

    private LinearLayout makeRow(Context context, String label, boolean withIcon) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));

        if (withIcon) {
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.outline_message_time_24);
            icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            row.addView(icon, LayoutHelper.createLinear(24, 24, 0, 0, 12, 0));
        }

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout valueContainer = new FrameLayout(context);
        TextView valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueContainer.addView(valueView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        row.addView(valueContainer, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        row.setTag(valueView);
        return row;
    }

    private void showDatePicker() {
        long selfId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        AlertsCreator.createScheduleDatePickerDialog(getContext(), "Дата и время", selfId, dueAt > 0 ? dueAt / 1000 : 0, true, (notify, scheduleDate, scheduleRepeatPeriod) -> {
            if (scheduleDate > 0 && scheduleDate != 0x7ffffffe) {
                dueAt = scheduleDate * 1000L;
                updateDateValue();
                updateRemindValue();
                updateRepeatValue();
            }
        }, null);
    }

    private void showRemindPicker() {
        if (dueAt == 0) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Напомнить");
        builder.setItems(REMIND_TITLES, (d, which) -> {
            remindIndex = which;
            updateRemindValue();
        });
        builder.show();
    }

    private void updateRepeatValue() {
        if (dueAt == 0) {
            repeatValue.setText("Сначала задайте дату");
            repeatRow.setAlpha(0.5f);
            repeatRow.setClickable(false);
        } else {
            String label = MglaTaskRepeat.label(repeatInterval, repeatUnit, dueAt);
            repeatValue.setText(label != null ? label : "Не повторять");
            repeatRow.setAlpha(1f);
            repeatRow.setClickable(true);
        }
    }

    private void showRepeatPicker() {
        if (dueAt == 0) {
            return;
        }
        String[] items = { "Не повторять", "Каждый день", "Каждую неделю", "Каждый месяц", "Свой интервал…" };
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Повтор");
        builder.setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    repeatInterval = 0;
                    repeatUnit = 0;
                    updateRepeatValue();
                    break;
                case 1:
                    repeatInterval = 1;
                    repeatUnit = MglaTaskRepeat.UNIT_DAY;
                    updateRepeatValue();
                    break;
                case 2:
                    repeatInterval = 1;
                    repeatUnit = MglaTaskRepeat.UNIT_WEEK;
                    updateRepeatValue();
                    break;
                case 3:
                    repeatInterval = 1;
                    repeatUnit = MglaTaskRepeat.UNIT_MONTH;
                    updateRepeatValue();
                    break;
                case 4:
                    showCustomRepeatDialog();
                    break;
            }
        });
        builder.show();
    }

    private void showCustomRepeatDialog() {
        Context context = getContext();
        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(8), dp(20), 0);

        EditText intervalEdit = new EditText(context);
        intervalEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalEdit.setHint("Интервал");
        intervalEdit.setText(String.valueOf(repeatInterval > 0 ? repeatInterval : 2));
        intervalEdit.setSelection(intervalEdit.getText().length());
        intervalEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        intervalEdit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        container.addView(intervalEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        LinearLayout unitsRow = new LinearLayout(context);
        unitsRow.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(unitsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        int[] unitValues = { MglaTaskRepeat.UNIT_DAY, MglaTaskRepeat.UNIT_WEEK, MglaTaskRepeat.UNIT_MONTH };
        String[] unitNames = { "Дни", "Недели", "Месяцы" };
        TextView[] unitButtons = new TextView[3];
        int initialUnit = repeatUnit >= MglaTaskRepeat.UNIT_DAY && repeatUnit <= MglaTaskRepeat.UNIT_MONTH ? repeatUnit : MglaTaskRepeat.UNIT_DAY;
        final int[] selectedUnit = { initialUnit };
        for (int a = 0; a < 3; a++) {
            final int unit = unitValues[a];
            TextView button = new TextView(context);
            button.setText(unitNames[a]);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(true);
            button.setOnClickListener(v -> {
                selectedUnit[0] = unit;
                for (int b = 0; b < 3; b++) {
                    styleUnitButton(unitButtons[b], accent, unitValues[b] == selectedUnit[0]);
                }
            });
            unitButtons[a] = button;
            styleUnitButton(button, accent, unit == selectedUnit[0]);
            unitsRow.addView(button, LayoutHelper.createLinear(0, 36, 1f, a == 0 ? 0 : 8, 0, 0, 0));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Свой интервал");
        builder.setView(container);
        builder.setPositiveButton("Готово", (d, w) -> {
            int interval;
            try {
                interval = Integer.parseInt(intervalEdit.getText().toString().trim());
            } catch (NumberFormatException e) {
                interval = 0;
            }
            if (interval < 1) {
                return;
            }
            repeatInterval = Math.min(interval, 999);
            repeatUnit = selectedUnit[0];
            updateRepeatValue();
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void styleUnitButton(TextView button, int accent, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (selected) {
            bg.setColor(accent);
            button.setTextColor(0xFFFFFFFF);
            button.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        } else {
            bg.setColor(ColorUtils.setAlphaComponent(accent, 26));
            button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            button.setTypeface(null);
        }
        button.setBackground(bg);
    }

    private void showGroupPicker() {
        ArrayList<MglaTask.Group> groups = MglaTasksStorage.getInstance().getGroups();
        CharSequence[] items = new CharSequence[groups.size() + 2];
        items[0] = "Без группы";
        for (int a = 0; a < groups.size(); a++) {
            MglaTask.Group group = groups.get(a);
            SpannableString ss = new SpannableString("●  " + group.name);
            ss.setSpan(new ForegroundColorSpan(group.color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[a + 1] = ss;
        }
        items[items.length - 1] = "Новая группа…";

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Группа");
        builder.setItems(items, (d, which) -> {
            if (which == 0) {
                selectedGroup = null;
                updateGroupValue();
            } else if (which == items.length - 1) {
                showNewGroupDialog(groups.size());
            } else {
                selectedGroup = groups.get(which - 1);
                updateGroupValue();
            }
        });
        builder.show();
    }

    private void showNewGroupDialog(int sortOrder) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Новая группа");

        FrameLayout container = new FrameLayout(getContext());
        container.setPadding(dp(20), dp(8), dp(20), 0);
        EditText editText = new EditText(getContext());
        editText.setHint("Название группы");
        editText.setSingleLine(true);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton("Создать", (d, w) -> {
            String name = editText.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                return;
            }
            MglaTask.Group group = new MglaTask.Group();
            group.account = UserConfig.selectedAccount;
            group.name = name;
            group.color = Theme.getColor(Theme.key_chat_messagePanelSend);
            group.sortOrder = sortOrder;
            MglaTasksStorage.getInstance().saveGroup(group);
            selectedGroup = group;
            updateGroupValue();
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void addSubItemRow(MglaTask.SubItem item, boolean focus) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        SubItemCheckView checkView = new SubItemCheckView(context);
        checkView.setChecked(item.done);
        checkView.setOnClickListener(v -> {
            item.done = !item.done;
            checkView.setChecked(item.done);
        });
        row.addView(checkView, LayoutHelper.createLinear(20, 20, 0, 0, 0, 0, 0));

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        editText.setHint("Подзадача");
        editText.setBackground(null);
        editText.setSingleLine(true);
        editText.setPadding(dp(8), dp(6), 0, dp(6));
        editText.setCursorColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        editText.setCursorSize(dp(20));
        if (!TextUtils.isEmpty(item.title)) {
            editText.setText(item.title);
        }
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                item.title = s.toString();
            }
        });
        row.addView(editText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView removeView = new TextView(context);
        removeView.setText("×");
        removeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        removeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        removeView.setGravity(Gravity.CENTER);
        removeView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        removeView.setOnClickListener(v -> {
            subItems.remove(item);
            subItemsLayout.removeView(row);
        });
        row.addView(removeView, LayoutHelper.createLinear(32, 32));

        subItemsLayout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, 2));
        if (focus) {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }
    }

    private void updateDateValue() {
        dateValue.setText(dueAt > 0 ? LocaleController.formatDateTime(dueAt / 1000, true) : "Не задано");
    }

    private void updateRemindValue() {
        if (dueAt == 0) {
            remindValue.setText("Сначала задайте дату");
            remindRow.setAlpha(0.5f);
            remindRow.setClickable(false);
        } else {
            remindValue.setText(REMIND_TITLES[remindIndex]);
            remindRow.setAlpha(1f);
            remindRow.setClickable(true);
        }
    }

    private void updateGroupValue() {
        if (groupValue != null) {
            groupValue.setText(selectedGroup != null ? selectedGroup.name : "Без группы");
        }
    }

    private void updateSaveButton() {
        boolean enabled = !TextUtils.isEmpty(titleEdit.getText().toString().trim());
        saveButton.setAlpha(enabled ? 1f : 0.5f);
        saveButton.setClickable(enabled);
    }

    private void save() {
        String title = titleEdit.getText().toString().trim();
        if (TextUtils.isEmpty(title)) {
            return;
        }
        MglaTask resultTask = editingTask;
        if (resultTask == null) {
            resultTask = new MglaTask();
            resultTask.id = 0;
            resultTask.createdAt = System.currentTimeMillis();
            resultTask.account = UserConfig.selectedAccount;
        }
        resultTask.title = title;
        resultTask.note = noteEdit.getText().toString().trim();
        resultTask.groupId = selectedGroup != null ? selectedGroup.id : 0;
        resultTask.dueAt = dueAt;
        resultTask.remindAt = dueAt > 0 && remindIndex < REMIND_OFFSETS.length ? dueAt - REMIND_OFFSETS[remindIndex] : 0;
        if (dueAt > 0) {
            resultTask.repeatInterval = repeatInterval;
            resultTask.repeatUnit = repeatUnit;
        } else {
            resultTask.repeatInterval = 0;
            resultTask.repeatUnit = 0;
        }
        resultTask.subItems = new ArrayList<>();
        for (MglaTask.SubItem item : subItems) {
            if (!TextUtils.isEmpty(item.title == null ? null : item.title.trim())) {
                item.taskId = resultTask.id;
                resultTask.subItems.add(item);
            }
        }
        onSave.run(resultTask);
        dismiss();
    }

    @Override
    public void show() {
        super.show();
        if (isNew) {
            AndroidUtilities.runOnUIThread(() -> {
                titleEdit.requestFocus();
                AndroidUtilities.showKeyboard(titleEdit);
            }, 200);
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
