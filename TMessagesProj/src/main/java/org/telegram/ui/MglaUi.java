package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Общие UI-константы и хелперы экранов настроек Mgla.
 *
 * Новые блоки настроек создавать только через {@link #createBlock} —
 * тогда закругление и оформление всех блоков будут одинаковыми.
 */
public final class MglaUi {

    /** Радиус закругления блоков настроек Mgla (как в разделе «Искусственный интеллект»). */
    public static final int BLOCK_CORNER_RADIUS_DP = 16;

    private MglaUi() {
    }

    /** Стандартный белый фон блока настроек с закруглением {@link #BLOCK_CORNER_RADIUS_DP} dp. */
    public static GradientDrawable createBlockBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(BLOCK_CORNER_RADIUS_DP));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return bg;
    }

    /**
     * Блок настроек: вертикальный LinearLayout с белым фоном,
     * закруглённым на {@link #BLOCK_CORNER_RADIUS_DP} dp.
     *
     * @param title текст заголовка блока или null, если заголовок не нужен
     */
    public static LinearLayout createBlock(Context context, String title) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(createBlockBackground());
        block.setClipToOutline(true);
        block.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        if (title != null) {
            HeaderCell header = new HeaderCell(context, 22);
            header.setBackground(null);
            header.setText(title);
            block.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        return block;
    }

    /** Стандартный разделитель между пунктами блока: линия 1dp с отступами 21dp слева и справа. */
    public static View createDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        divider.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 21, 0, 21, 0));
        return divider;
    }

    /**
     * Диалог ввода текста.
     *
     * @param message   пояснение над полем или null
     * @param multiline многострочный ввод (иначе одна строка)
     * @param numeric   только цифры
     * @param onSave    вызывается с введённым текстом при нажатии «Сохранить»
     */
    public static void showInputDialog(BaseFragment fragment, String title, String message, String initial,
                                       boolean multiline, boolean numeric, Utilities.Callback<String> onSave) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        if (message != null) {
            TextView messageView = new TextView(context);
            messageView.setText(message);
            messageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            messageView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            container.addView(messageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 4));
        }

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, multiline ? 14 : 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setBackground(Theme.createEditTextDrawable(context, true));
        if (numeric) {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            editText.setSingleLine(true);
        } else if (multiline) {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editText.setTypeface(Typeface.MONOSPACE);
            editText.setMinLines(3);
            editText.setMaxLines(10);
            editText.setGravity(Gravity.TOP | Gravity.START);
        } else {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editText.setSingleLine(true);
        }
        editText.setText(initial == null ? "" : initial);
        editText.setSelection(editText.length());
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 8));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setView(container);
        builder.setPositiveButton("Сохранить", (dialog, which) -> onSave.run(editText.getText().toString()));
        builder.setNegativeButton("Отмена", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        fragment.showDialog(dialog);
    }
}
