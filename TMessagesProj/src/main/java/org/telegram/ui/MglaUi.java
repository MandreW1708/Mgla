package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
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
}
