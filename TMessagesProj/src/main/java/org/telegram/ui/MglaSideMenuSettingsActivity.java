package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaSideMenuConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaSideMenuSettingsActivity extends MglaBaseSettingsActivity {

    private TextCheckCell glassCell;
    private TextView opacityTitle;
    private SlideIntChooseView opacitySlide;
    private TextInfoPrivacyCell opacityInfo;

    public MglaSideMenuSettingsActivity() {
        this(null);
    }

    public MglaSideMenuSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Боковое меню");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);

        LinearLayout toggleBlock = MglaUi.createBlock(context, null);

        TextCheckCell enableCell = new TextCheckCell(context);
        enableCell.setBackground(null);
        enableCell.setTextAndCheck("Включить боковое меню", MglaSideMenuConfig.isEnabled(), false);
        enableCell.setOnClickListener(v -> {
            boolean newVal = !MglaSideMenuConfig.isEnabled();
            MglaSideMenuConfig.setEnabled(newVal);
            enableCell.setChecked(newVal);
        });
        toggleBlock.addView(enableCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        toggleBlock.addView(MglaUi.createDivider(context));

        glassCell = new TextCheckCell(context);
        glassCell.setBackground(null);
        glassCell.setTextAndCheck("Жидкое стекло", MglaSideMenuConfig.isGlassEnabled(), false);
        glassCell.setOnClickListener(v -> {
            boolean newVal = !MglaSideMenuConfig.isGlassEnabled();
            MglaSideMenuConfig.setGlassEnabled(newVal);
            glassCell.setChecked(newVal);
            updateOpacityEnabled();
        });
        toggleBlock.addView(glassCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        toggleBlock.addView(MglaUi.createDivider(context));

        opacityTitle = new TextView(context);
        opacityTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        opacityTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        opacityTitle.setText("Непрозрачность фона");
        opacityTitle.setPadding(dp(22), dp(10), dp(22), 0);
        toggleBlock.addView(opacityTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        opacitySlide = new SlideIntChooseView(context, null);
        opacitySlide.set(
                MglaSideMenuConfig.getBackgroundOpacity(),
                SlideIntChooseView.Options.make(0, 0, 100, (val) -> val + "%"),
                MglaSideMenuConfig::setBackgroundOpacity
        );
        toggleBlock.addView(opacitySlide, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(toggleBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        opacityInfo = new TextInfoPrivacyCell(context);
        opacityInfo.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
        rootLayout.addView(opacityInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout elementsBlock = MglaUi.createBlock(context, "Элементы");
        TextSettingsCell configureElementsCell = new TextSettingsCell(context);
        configureElementsCell.setBackground(null);
        configureElementsCell.setText("Настроить элементы", true);
        configureElementsCell.setCanDisable(false);
        configureElementsCell.setOnClickListener(v -> presentFragment(new MglaSideMenuElementsActivity()));
        elementsBlock.addView(configureElementsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(elementsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        TextInfoPrivacyCell infoCell = new TextInfoPrivacyCell(context);
        infoCell.setText("Боковое меню открывается кнопкой в шапке списка чатов.");
        infoCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        rootLayout.addView(infoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateOpacityEnabled();

        fragmentView = rootLayout;
        return fragmentView;
    }

    private void updateOpacityEnabled() {
        boolean glass = MglaSideMenuConfig.isGlassEnabled();
        float alpha = glass ? 0.4f : 1f;
        if (opacityTitle != null) {
            opacityTitle.setAlpha(alpha);
        }
        if (opacitySlide != null) {
            opacitySlide.setAlpha(alpha);
            opacitySlide.setEnabled(!glass);
            // SeekBarView does not always honour View.enabled — block touches while glass is on.
            opacitySlide.setOnTouchListener(glass
                    ? (View v, MotionEvent e) -> true
                    : null);
        }
        if (opacityInfo != null) {
            opacityInfo.setText(glass
                    ? "При включённом жидком стекле плотность регулируется ползунком «Затемнение стекла» во внешнем виде."
                    : "Плотность фона боковой панели без жидкого стекла.");
        }
    }
}
