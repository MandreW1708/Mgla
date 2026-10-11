package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.InstantCameraViewBase;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Настройки камеры: выбор API «1 / 2» (стоковые Camera1 и Camera2 из Telegram).
 * Camera 2 — стоковый {@link InstantCameraViewBase} на Camera2, Camera 1 — классическая
 * реализация с принудительным старым API ({@link SharedConfig#setUseCamera2Force}).
 */
public class MglaCameraSettingsActivity extends MglaBaseSettingsActivity {

    private static final int API_1 = 0;
    private static final int API_2 = 1;

    private int selectedApi;
    private LinearLayout descContainer;
    private TextView descTitle;
    private TextView descText;

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

        selectedApi = InstantCameraViewBase.isUsingCamera2Implementation()
                || SharedConfig.isUsingCamera2(UserConfig.selectedAccount) ? API_2 : API_1;

        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int hintColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);

        LinearLayout apiBlock = createBlock(context);
        rootLayout.addView(apiBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        apiBlock.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 18));

        LinearLayout buttonColumn = new LinearLayout(context);
        buttonColumn.setOrientation(LinearLayout.VERTICAL);
        row.addView(buttonColumn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView titleView = new TextView(context);
        titleView.setText("API");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(textColor);
        titleView.setGravity(Gravity.CENTER);
        buttonColumn.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        ApiButton[] buttons = new ApiButton[2];
        String[] labels = {"1", "2"};
        for (int i = 0; i < 2; i++) {
            final int api = i;
            buttons[i] = new ApiButton(context, labels[i], accent);
            buttons[i].setOnClickListener(v -> select(api, buttons));
            buttonColumn.addView(buttons[i], LayoutHelper.createLinear(56, 56, 0, 0, 0, i == 1 ? 0 : 12));
        }

        descContainer = new LinearLayout(context);
        descContainer.setOrientation(LinearLayout.VERTICAL);
        descContainer.setGravity(Gravity.CENTER);
        row.addView(descContainer, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 36, 0, 0, 0));

        descTitle = new TextView(context);
        descTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        descTitle.setTypeface(AndroidUtilities.bold());
        descTitle.setTextColor(textColor);
        descContainer.addView(descTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        descText = new TextView(context);
        descText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        descText.setTextColor(hintColor);
        descContainer.addView(descText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));

        applyDescription(selectedApi, false);
        for (int i = 0; i < 2; i++) {
            buttons[i].setSelectedState(i == selectedApi, false);
        }

        fragmentView = rootLayout;
        return fragmentView;
    }

    private void select(int api, ApiButton[] buttons) {
        if (api == selectedApi) {
            return;
        }
        selectedApi = api;
        boolean camera2 = api == API_2;
        // Camera 2 → стоковый InstantCameraView2; Camera 1 → классический InstantCameraView
        // с принудительным старым API. Оба пишутся только здесь — никаких дублей.
        InstantCameraViewBase.setUseCamera2Implementation(camera2);
        SharedConfig.setUseCamera2Force(camera2 ? null : false);
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setSelectedState(i == api, true);
        }
        applyDescription(api, true);
    }

    private void applyDescription(int api, boolean animated) {
        String title;
        String text;
        if (api == API_2) {
            title = "Camera 2";
            text = "Round Video Camera2 (InstantCameraView2): настройки 480p / битрейт / FPS в разделе «Видеосообщения».";
        } else {
            title = "Camera 1";
            text = "Классический InstantCameraView (Camera1 API) для видеосообщений.";
        }

        if (!animated) {
            descTitle.setText(title);
            descText.setText(text);
            descContainer.setAlpha(1f);
            descContainer.setTranslationX(0);
            return;
        }
        descContainer.animate().cancel();
        descContainer.animate()
                .alpha(0f)
                .translationX(dp(8))
                .setDuration(110)
                .setInterpolator(CubicBezierInterpolator.DEFAULT)
                .withEndAction(() -> {
                    descTitle.setText(title);
                    descText.setText(text);
                    descContainer.animate()
                            .alpha(1f)
                            .translationX(0)
                            .setDuration(210)
                            .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                            .start();
                })
                .start();
    }

    private static int adjustAlpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * factor);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private LinearLayout createBlock(Context context) {
        return MglaUi.createBlock(context, null);
    }

    private static class ApiButton extends FrameLayout {

        private final GradientDrawable bg = new GradientDrawable();
        private final TextView label;
        private final int accent;
        private final int strokeNormal;
        private final int fillNormal;
        private final int textNormal;
        private final ArgbEvaluator evaluator = new ArgbEvaluator();

        private float sel;
        private ValueAnimator animator;

        ApiButton(Context context, String text, int accent) {
            super(context);
            this.accent = accent;
            strokeNormal = adjustAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.55f);
            fillNormal = Theme.getColor(Theme.key_windowBackgroundWhite);
            textNormal = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

            bg.setCornerRadius(dp(14));
            setBackground(bg);

            label = new TextView(context);
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
            label.setGravity(Gravity.CENTER);
            addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            updateColors();
        }

        void setSelectedState(boolean selected, boolean animated) {
            float target = selected ? 1f : 0f;
            if (!animated) {
                if (animator != null) {
                    animator.cancel();
                    animator = null;
                }
                sel = target;
                updateColors();
                return;
            }
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(sel, target);
            animator.setDuration(240);
            animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            animator.addUpdateListener(a -> {
                sel = (float) a.getAnimatedValue();
                updateColors();
            });
            animator.start();
        }

        private void updateColors() {
            bg.setStroke(dp(2), (Integer) evaluator.evaluate(sel, strokeNormal, accent));
            bg.setColor((Integer) evaluator.evaluate(sel, fillNormal, adjustAlpha(accent, 0.12f)));
            label.setTextColor((Integer) evaluator.evaluate(sel, textNormal, accent));
            label.setTypeface(sel > 0.5f ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
        }
    }
}
