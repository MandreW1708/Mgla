package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;

public class MglaCameraSettingsActivity extends BaseFragment {

    public MglaCameraSettingsActivity() {
        this(null);
    }

    public MglaCameraSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Камера");
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

        LinearLayout advancedBlock = createBlock(context, "Расширенные настройки");
        advancedBlock.setVisibility(SharedConfig.cameraApi == 2 ? View.VISIBLE : View.GONE);

        TextCheckCell fps60Cell = new TextCheckCell(context);
        fps60Cell.setBackground(null);
        fps60Cell.setTextAndCheck("60 кадров в секунду при записи", SharedConfig.cameraX60Fps, false);
        fps60Cell.setOnClickListener(v -> {
            SharedConfig.toggleCameraX60Fps();
            fps60Cell.setChecked(SharedConfig.cameraX60Fps);
        });
        advancedBlock.addView(fps60Cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell apiHeader = new HeaderCell(context, 22);
        apiHeader.setBackground(null);
        apiHeader.setText("API камеры");
        rootLayout.addView(apiHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        final ApiOptionView[] optionViews = new ApiOptionView[3];
        optionViews[0] = createApiOption(context, IconBackgroundColors.GRAY.top, IconBackgroundColors.GRAY.bottom,
            "C1", "Camera 1 API", "Максимальная совместимость со старыми устройствами", false);
        optionViews[1] = createApiOption(context, IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
            "C2", "Camera 2 API", "Стабильная работа на большинстве устройств", false);
        optionViews[2] = createApiOption(context, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
            "CX", "CameraX", "Современный API с расширенными настройками", true);

        for (int i = 0; i < optionViews.length; i++) {
            final int api = i;
            optionViews[i].setOnClickListener(v -> {
                SharedConfig.setCameraApi(api);
                for (int j = 0; j < optionViews.length; j++) {
                    optionViews[j].setChecked(j == api, true);
                }
                advancedBlock.setVisibility(api == 2 ? View.VISIBLE : View.GONE);
            });
            rootLayout.addView(optionViews[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, i == 0 ? 0 : 8, 16, 0));
        }
        for (int i = 0; i < optionViews.length; i++) {
            optionViews[i].setChecked(i == SharedConfig.cameraApi, false);
        }

        rootLayout.addView(advancedBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
    }

    private ApiOptionView createApiOption(Context context, int colorTop, int colorBottom, String badge, String title, String subtitle, boolean recommended) {
        return new ApiOptionView(context, colorTop, colorBottom, badge, title, subtitle, recommended);
    }

    /**
     * Карточка выбора API камеры: градиентный бейдж, название, описание,
     * плашка «Рекомендуется» и анимированный радио-индикатор.
     */
    private static class ApiOptionView extends FrameLayout {

        private final GradientDrawable background = new GradientDrawable();
        private final RadioButton radioButton;
        private final TextView badgeView;
        private boolean checked;
        private int currentBgColor;
        private final int strokeColor;
        private final int checkedBgColor;
        private final int uncheckedBgColor;
        private ValueAnimator animator;

        ApiOptionView(Context context, int colorTop, int colorBottom, String badge, String title, String subtitle, boolean recommended) {
            super(context);

            strokeColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            checkedBgColor = changeColorAlpha(strokeColor, 0.12f);
            uncheckedBgColor = Theme.getColor(Theme.key_windowBackgroundWhite);

            setPadding(dp(14), dp(10), dp(10), dp(10));
            setClipToOutline(true);
            setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

            background.setCornerRadius(dp(10));
            background.setColor(uncheckedBgColor);
            currentBgColor = uncheckedBgColor;
            setBackground(background);

            android.graphics.drawable.RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(changeColorAlpha(strokeColor, 0.20f)), null, null);
            setForeground(ripple);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            // Градиентный бейдж с буквенным обозначением API
            badgeView = new TextView(context);
            badgeView.setText(badge);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setTextColor(0xFFFFFFFF);
            badgeView.setGravity(Gravity.CENTER);
            GradientDrawable badgeBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{colorTop, colorBottom});
            badgeBg.setCornerRadius(dp(11));
            badgeView.setBackground(badgeBg);
            content.addView(badgeView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            LinearLayout textLayout = new LinearLayout(context);
            textLayout.setOrientation(LinearLayout.VERTICAL);

            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleRow.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            if (recommended) {
                TextView badgeTag = new TextView(context);
                badgeTag.setText("РЕКОМЕНДУЕТСЯ");
                badgeTag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
                badgeTag.setTypeface(AndroidUtilities.bold());
                badgeTag.setTextColor(0xFFFFFFFF);
                badgeTag.setGravity(Gravity.CENTER);
                badgeTag.setPadding(dp(6), dp(1), dp(6), dp(2));
                GradientDrawable tagBg = new GradientDrawable();
                tagBg.setCornerRadius(dp(5));
                tagBg.setColor(strokeColor);
                badgeTag.setBackground(tagBg);
                titleRow.addView(badgeTag, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            }

            textLayout.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView subtitleView = new TextView(context);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            content.addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            radioButton = new RadioButton(context);
            radioButton.setSize(dp(20));
            radioButton.setColor(Theme.getColor(Theme.key_radioBackground), strokeColor);
            content.addView(radioButton, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

            setClickable(true);
            setFocusable(true);
        }

        public void setChecked(boolean checked, boolean animated) {
            if (this.checked == checked) {
                return;
            }
            this.checked = checked;
            radioButton.setChecked(checked, animated);

            int fromColor = currentBgColor;
            int toColor = checked ? checkedBgColor : uncheckedBgColor;
            int fromStroke = checked ? 0 : 1;
            int toStroke = checked ? 1 : 0;

            if (animator != null) {
                animator.cancel();
            }
            if (!animated) {
                currentBgColor = toColor;
                background.setColor(toColor);
                background.setStroke(dp(checked ? 1.5f : 0), checked ? strokeColor : 0x00000000);
                return;
            }
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(180);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            final int startColor = fromColor;
            animator.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                currentBgColor = lerpColor(startColor, toColor, t);
                background.setColor(currentBgColor);
                float strokeT = lerp(fromStroke, toStroke, t);
                background.setStroke(Math.round(dp(1.5f) * strokeT), changeColorAlpha(strokeColor, strokeT));
            });
            animator.start();
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static int lerpColor(int a, int b, float t) {
            int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff, aa = (a >>> 24) & 0xff;
            int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff, ba = (b >>> 24) & 0xff;
            int r = Math.round(ar + (br - ar) * t);
            int g = Math.round(ag + (bg - ag) * t);
            int bl = Math.round(ab + (bb - ab) * t);
            int al = Math.round(aa + (ba - aa) * t);
            return (al << 24) | (r << 16) | (g << 8) | bl;
        }

        private static int changeColorAlpha(int color, float alpha) {
            int a = Math.round(((color >>> 24) & 0xff) * alpha);
            return (a << 24) | (color & 0x00ffffff);
        }
    }

    private LinearLayout createBlock(Context context, String title) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(bg);
        block.setClipToOutline(true);
        block.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        HeaderCell header = new HeaderCell(context, 22);
        header.setBackground(null);
        header.setText(title);
        block.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return block;
    }
}
