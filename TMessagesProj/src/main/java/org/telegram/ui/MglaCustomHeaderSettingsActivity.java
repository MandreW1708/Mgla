package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaChatHeaderPreview;

import java.util.ArrayList;

public class MglaCustomHeaderSettingsActivity extends MglaBaseSettingsActivity {

    private MglaChatHeaderPreview headerPreview;
    private final ArrayList<Runnable> refreshBindings = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Своя шапка");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        LinearLayout previewBlock = createBlock(context, "Превью");
        headerPreview = new MglaChatHeaderPreview(context);
        headerPreview.setPadding(dp(12), dp(4), dp(12), dp(8));
        headerPreview.setCustomPreviewFromConfig();
        previewBlock.addView(headerPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(previewBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        addPresetsBlock(rootLayout, context);
        addElementsBlock(rootLayout, context);
        addGlassBlock(rootLayout, context);
        addLayoutBlock(rootLayout, context);
        addPanelsBlock(rootLayout, context);

        LinearLayout resetBlock = MglaUi.createBlock(context, null);
        TextSettingsCell resetCell = new TextSettingsCell(context);
        resetCell.setBackground(null);
        resetCell.setText("Сбросить пресет", false);
        resetCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        resetCell.setOnClickListener(v -> {
            MglaCustomHeaderConfig.resetToPresetDefaults();
            refreshAll();
        });
        resetBlock.addView(resetCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(resetBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));

        fragmentView = scrollView;
        return fragmentView;
    }

    private void refreshAll() {
        for (int i = 0; i < refreshBindings.size(); i++) {
            refreshBindings.get(i).run();
        }
        if (headerPreview != null) {
            headerPreview.setCustomPreviewFromConfig();
        }
    }

    private void touchPreview() {
        if (headerPreview != null) {
            headerPreview.setCustomPreviewFromConfig();
        }
    }

    private void addPresetsBlock(LinearLayout root, Context context) {
        LinearLayout presetsBlock = createBlock(context, "Пресет");
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(12), dp(8), dp(12), dp(12));
        final int[] presets = {
                MglaCustomHeaderConfig.PRESET_STANDARD,
                MglaCustomHeaderConfig.PRESET_TELEGRAM,
                MglaCustomHeaderConfig.PRESET_CENTER,
                MglaCustomHeaderConfig.PRESET_COMPACT,
                MglaCustomHeaderConfig.PRESET_MINIMAL,
                MglaCustomHeaderConfig.PRESET_HERO
        };
        final TextView[] chipViews = new TextView[presets.length];
        final Runnable refreshChips = () -> {
            final int cur = MglaCustomHeaderConfig.getLayoutPreset();
            for (int i = 0; i < presets.length; i++) {
                final boolean on = presets[i] == cur;
                chipViews[i].setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                        dp(16),
                        on ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.getColor(Theme.key_windowBackgroundWhite),
                        ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 40)
                ));
                chipViews[i].setTextColor(on
                        ? Theme.getColor(Theme.key_featuredStickers_buttonText)
                        : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
        };
        for (int i = 0; i < presets.length; i++) {
            final int preset = presets[i];
            TextView chip = new TextView(context);
            chip.setText(MglaCustomHeaderConfig.presetLabel(preset));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setOnClickListener(v -> {
                MglaCustomHeaderConfig.setLayoutPreset(preset);
                refreshAll();
            });
            chipViews[i] = chip;
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, i == presets.length - 1 ? 0 : 8, 0));
        }
        scroll.addView(chips);
        presetsBlock.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(presetsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
        refreshBindings.add(refreshChips);
        refreshChips.run();
    }

    private void addElementsBlock(LinearLayout root, Context context) {
        LinearLayout block = createBlock(context, "Элементы");
        addCheck(block, context, "Кнопка назад", MglaCustomHeaderConfig::isShowBack, MglaCustomHeaderConfig::setShowBack);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Аватар", MglaCustomHeaderConfig::isShowAvatarRaw, MglaCustomHeaderConfig::setShowAvatar);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Ник", MglaCustomHeaderConfig::isShowTitle, MglaCustomHeaderConfig::setShowTitle);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Статус", MglaCustomHeaderConfig::isShowSubtitle, MglaCustomHeaderConfig::setShowSubtitle);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Кнопки меню", MglaCustomHeaderConfig::isShowMenu, MglaCustomHeaderConfig::setShowMenu);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Стрелка сообщества", MglaCustomHeaderConfig::isShowCommunity, MglaCustomHeaderConfig::setShowCommunity);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Таймер / Stars", MglaCustomHeaderConfig::isShowTimer, MglaCustomHeaderConfig::setShowTimer);
        root.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
    }

    private void addGlassBlock(LinearLayout root, Context context) {
        LinearLayout block = createBlock(context, "Стекло");
        addCheck(block, context, "Фон кнопки назад", MglaCustomHeaderConfig::isGlassBack, MglaCustomHeaderConfig::setGlassBack);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Фон ника", MglaCustomHeaderConfig::isGlassTitle, MglaCustomHeaderConfig::setGlassTitle);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Фон меню", MglaCustomHeaderConfig::isGlassMenu, MglaCustomHeaderConfig::setGlassMenu);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Фон закрепа", MglaCustomHeaderConfig::isGlassPinned, MglaCustomHeaderConfig::setGlassPinned);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Фон перевода", MglaCustomHeaderConfig::isGlassTranslation, MglaCustomHeaderConfig::setGlassTranslation);
        root.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
    }

    private void addLayoutBlock(LinearLayout root, Context context) {
        LinearLayout block = createBlock(context, "Компоновка");

        addSectionLabel(block, context, "Выравнивание ника");
        addRadioGroup(block, context,
                new String[]{"Слева", "По центру"},
                new int[]{MglaCustomHeaderConfig.TITLE_ALIGN_START, MglaCustomHeaderConfig.TITLE_ALIGN_CENTER},
                MglaCustomHeaderConfig::getTitleAlign,
                MglaCustomHeaderConfig::setTitleAlign);

        block.addView(MglaUi.createDivider(context));
        addSectionLabel(block, context, "Позиция аватара");
        addRadioGroup(block, context,
                new String[]{"В начале", "Справа", "Скрыт"},
                new int[]{MglaCustomHeaderConfig.AVATAR_SIDE_START, MglaCustomHeaderConfig.AVATAR_SIDE_END, MglaCustomHeaderConfig.AVATAR_SIDE_HIDDEN},
                MglaCustomHeaderConfig::getAvatarSide,
                MglaCustomHeaderConfig::setAvatarSide);

        block.addView(MglaUi.createDivider(context));
        addSectionLabel(block, context, "Размер аватара");
        addRadioGroup(block, context,
                new String[]{"S", "M", "L", "XL"},
                new int[]{MglaCustomHeaderConfig.AVATAR_SIZE_S, MglaCustomHeaderConfig.AVATAR_SIZE_M, MglaCustomHeaderConfig.AVATAR_SIZE_L, MglaCustomHeaderConfig.AVATAR_SIZE_XL},
                MglaCustomHeaderConfig::getAvatarSize,
                MglaCustomHeaderConfig::setAvatarSize);

        block.addView(MglaUi.createDivider(context));
        addSectionLabel(block, context, "Размер ника");
        addRadioGroup(block, context,
                new String[]{"Обычный", "Крупный"},
                new int[]{MglaCustomHeaderConfig.TITLE_SIZE_NORMAL, MglaCustomHeaderConfig.TITLE_SIZE_LARGE},
                MglaCustomHeaderConfig::getTitleSize,
                MglaCustomHeaderConfig::setTitleSize);

        block.addView(MglaUi.createDivider(context));
        addSectionLabel(block, context, "Высота шапки");
        addRadioGroup(block, context,
                new String[]{"Компакт", "Обычная", "Высокая"},
                new int[]{MglaCustomHeaderConfig.HEIGHT_COMPACT, MglaCustomHeaderConfig.HEIGHT_NORMAL, MglaCustomHeaderConfig.HEIGHT_TALL},
                MglaCustomHeaderConfig::getHeaderHeight,
                MglaCustomHeaderConfig::setHeaderHeight);

        block.addView(MglaUi.createDivider(context));
        addSectionLabel(block, context, "Скругление стекла");
        addRadioGroup(block, context,
                new String[]{"Круглое", "Мягкое", "Квадрат"},
                new int[]{MglaCustomHeaderConfig.RADIUS_ROUND, MglaCustomHeaderConfig.RADIUS_SOFT, MglaCustomHeaderConfig.RADIUS_SQUARE},
                MglaCustomHeaderConfig::getGlassRadius,
                MglaCustomHeaderConfig::setGlassRadius);

        root.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
    }

    private void addPanelsBlock(LinearLayout root, Context context) {
        LinearLayout block = createBlock(context, "Панели под шапкой");
        addCheck(block, context, "Блок закрепа", MglaCustomHeaderConfig::isShowPinned, MglaCustomHeaderConfig::setShowPinned);
        block.addView(MglaUi.createDivider(context));
        addCheck(block, context, "Панель перевода", MglaCustomHeaderConfig::isShowTranslation, MglaCustomHeaderConfig::setShowTranslation);
        root.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
    }

    private interface BoolGetter { boolean get(); }
    private interface BoolSetter { void set(boolean v); }
    private interface IntGetter { int get(); }
    private interface IntSetter { void set(int v); }

    private void addCheck(LinearLayout block, Context context, String title, BoolGetter getter, BoolSetter setter) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackground(null);
        cell.setTextAndCheck(title, getter.get(), false);
        cell.setOnClickListener(v -> {
            boolean next = !cell.isChecked();
            setter.set(next);
            cell.setChecked(next);
            touchPreview();
        });
        refreshBindings.add(() -> cell.setChecked(getter.get()));
        block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addSectionLabel(LinearLayout block, Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        label.setPadding(dp(22), dp(10), dp(22), dp(4));
        block.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addRadioGroup(LinearLayout block, Context context, String[] labels, int[] values, IntGetter getter, IntSetter setter) {
        final RadioCell[] cells = new RadioCell[labels.length];
        final Runnable refresh = () -> {
            final int cur = getter.get();
            for (int i = 0; i < cells.length; i++) {
                cells[i].setChecked(values[i] == cur, true);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int value = values[i];
            RadioCell cell = new RadioCell(context);
            cell.setBackground(null);
            cell.setText(labels[i], getter.get() == value, i < labels.length - 1);
            cell.setOnClickListener(v -> {
                setter.set(value);
                refresh.run();
                touchPreview();
            });
            cells[i] = cell;
            block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        refreshBindings.add(refresh);
    }

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }
}
