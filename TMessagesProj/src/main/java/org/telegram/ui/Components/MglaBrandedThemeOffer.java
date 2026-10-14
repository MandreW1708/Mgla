package org.telegram.ui.Components;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

/**
 * Одноразовое окно при первом запуске: предложение поставить одну из фирменных тем Mgla.
 * Тап по варианту сразу применяет тему; «Отмена» (или закрытие окна) возвращает прежнюю тему.
 */
public class MglaBrandedThemeOffer {

    private static final String PREF_SHOWN = "mgla_branded_theme_offer_shown";

    public static void showIfNeeded(LaunchActivity activity) {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (prefs.getBoolean(PREF_SHOWN, false)) {
            return;
        }
        final Theme.ThemeInfo amoledInfo = Theme.getTheme("Burgundy");
        final Theme.ThemeInfo darkInfo = Theme.getTheme("Gray Burgundy");
        if (amoledInfo == null || darkInfo == null) {
            return;
        }
        prefs.edit().putBoolean(PREF_SHOWN, true).apply();
        AndroidUtilities.runOnUIThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            show(activity, amoledInfo, darkInfo);
        }, 600);
    }

    /** Сброс флага «показано» и повторный показ — для отладки через меню отладки. */
    public static void showAgain(LaunchActivity activity) {
        MessagesController.getGlobalMainSettings().edit().remove(PREF_SHOWN).apply();
        showIfNeeded(activity);
    }

    /** Прямой Theme.applyTheme меняет таблицы цветов — видимые экраны нужно перестроить отдельно. */
    private static void rebuildUi(LaunchActivity activity) {
        if (activity == null || activity.getActionBarLayout() == null) {
            return;
        }
        activity.getActionBarLayout().rebuildAllFragmentViews(true, true);
        if (AndroidUtilities.isTablet()) {
            if (activity.getRightActionBarLayout() != null) {
                activity.getRightActionBarLayout().rebuildAllFragmentViews(true, true);
            }
            if (activity.getLayersActionBarLayout() != null) {
                activity.getLayersActionBarLayout().rebuildAllFragmentViews(true, true);
            }
        }
    }

    private static void show(final LaunchActivity activity, final Theme.ThemeInfo amoledInfo, final Theme.ThemeInfo darkInfo) {
        final Context context = activity;

        final Theme.ThemeInfo previousInfo = Theme.getCurrentTheme();
        final Theme.ThemeInfo[] applied = new Theme.ThemeInfo[1];
        final boolean[] resolved = new boolean[1];

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = AndroidUtilities.dp(20);
        content.setPadding(side, AndroidUtilities.dp(16), side, AndroidUtilities.dp(4));

        TextView title = new TextView(context);
        title.setText("Фирменные темы");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView message = new TextView(context);
        message.setText("Попробуй фирменное оформление Mgla — тёмные цвета и фирменные обои. Тему можно сменить в любой момент.");
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        message.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        content.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        content.addView(options, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        final OptionRow[] rows = new OptionRow[2];
        rows[0] = new OptionRow(context, "Фирменная amoled", amoledInfo);
        rows[1] = new OptionRow(context, "Фирменная серая", darkInfo);
        options.addView(rows[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        options.addView(rows[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        View.OnClickListener listener = v -> {
            Theme.ThemeInfo info = v.getTag() instanceof Theme.ThemeInfo ? (Theme.ThemeInfo) v.getTag() : null;
            if (info == null) {
                return;
            }
            applied[0] = info;
            Theme.applyTheme(info);
            rebuildUi(activity);
            for (OptionRow row : rows) {
                row.setSelectedTheme(info);
            }
        };
        rows[0].setOnClickListener(listener);
        rows[1].setOnClickListener(listener);

        Theme.ThemeInfo current = Theme.getCurrentTheme();
        for (OptionRow row : rows) {
            row.setSelectedTheme(current == amoledInfo || current == darkInfo ? current : null);
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(content)
                .setPositiveButton("Установить", (d, which) -> {
                    // Тема уже применена тапом по варианту — просто закрываем окно.
                    resolved[0] = true;
                })
                .setNegativeButton("Отмена", (d, which) -> {
                    resolved[0] = true;
                    if (applied[0] != null) {
                        Theme.applyTheme(previousInfo);
                        rebuildUi(activity);
                    }
                })
                .create();
        dialog.setOnDismissListener(d -> {
            if (!resolved[0] && applied[0] != null) {
                Theme.applyTheme(previousInfo);
                rebuildUi(activity);
            }
        });
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private static class OptionRow extends LinearLayout {

        final Theme.ThemeInfo info;
        final TextView nameView;
        final TextView checkView;

        OptionRow(Context context, String name, Theme.ThemeInfo themeInfo) {
            super(context);
            info = themeInfo;
            setTag(themeInfo);
            setOrientation(HORIZONTAL);
            int hPad = AndroidUtilities.dp(14);
            int vPad = AndroidUtilities.dp(12);
            setPadding(hPad, vPad, hPad, vPad);

            nameView = new TextView(context);
            nameView.setText(name);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setGravity(Gravity.CENTER_VERTICAL);
            nameView.setSingleLine(true);
            addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            checkView = new TextView(context);
            checkView.setText("✓");
            checkView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            checkView.setGravity(Gravity.CENTER);
            checkView.setVisibility(GONE);
            addView(checkView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        void setSelectedTheme(Theme.ThemeInfo selected) {
            boolean isSelected = selected == info;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(10));
            if (isSelected) {
                bg.setColor(0x402E509C);
                bg.setStroke(AndroidUtilities.dp(1), Theme.MGLA_ACCENT_COLOR);
                nameView.setTextColor(Theme.MGLA_ACCENT_COLOR);
                checkView.setTextColor(Theme.MGLA_ACCENT_COLOR);
                checkView.setVisibility(VISIBLE);
            } else {
                bg.setColor(0x14000000);
                bg.setStroke(AndroidUtilities.dp(1), 0x33ffffff);
                nameView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                checkView.setVisibility(GONE);
            }
            setBackground(bg);
        }
    }
}
