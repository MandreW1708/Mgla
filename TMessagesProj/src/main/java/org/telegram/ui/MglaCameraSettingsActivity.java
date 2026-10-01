package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Настройки камеры: выбор API кнопками «1 / 2 / X», описание выбранного API,
 * для CameraX — дополнительные опции (60 fps).
 * Порядок api: 0 — Camera1, 1 — Camera2, 2 — CameraX (SharedConfig.cameraApi).
 */
public class MglaCameraSettingsActivity extends BaseFragment {

    private static final int API_1 = 0;
    private static final int API_2 = 1;
    private static final int API_X = 2;

    private int selectedApi;
    private LinearLayout advancedBlock;
    private LinearLayout descContainer;
    private TextView descTitle;
    private TextView descText;
    private TextView descRecommend;

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

        selectedApi = SharedConfig.cameraApi;

        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int hintColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);
        // плавное выдвижение/сокрытие блока доп. настроек
        LayoutTransition layoutTransition = new LayoutTransition();
        layoutTransition.enableTransitionType(LayoutTransition.APPEARING);
        layoutTransition.enableTransitionType(LayoutTransition.DISAPPEARING);
        layoutTransition.enableTransitionType(LayoutTransition.CHANGING);
        layoutTransition.setDuration(220);
        rootLayout.setLayoutTransition(layoutTransition);

        // --- блок API ---
        LinearLayout apiBlock = createBlock(context);
        rootLayout.addView(apiBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        TextView titleView = new TextView(context);
        titleView.setText("API");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(textColor);
        apiBlock.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 2));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        apiBlock.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 10, 20, 18));

        // столбец кнопок 1 / 2 / X
        LinearLayout buttonColumn = new LinearLayout(context);
        buttonColumn.setOrientation(LinearLayout.VERTICAL);
        row.addView(buttonColumn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        ApiButton[] buttons = new ApiButton[3];
        String[] labels = {"1", "2", "X"};
        for (int i = 0; i < 3; i++) {
            final int api = i;
            buttons[i] = new ApiButton(context, labels[i], accent);
            buttons[i].setOnClickListener(v -> select(api, buttons));
            buttonColumn.addView(buttons[i], LayoutHelper.createLinear(56, 56, 0, 0, 0, i == 2 ? 0 : 12));
        }

        // описание выбранного API
        descContainer = new LinearLayout(context);
        descContainer.setOrientation(LinearLayout.VERTICAL);
        descContainer.setGravity(Gravity.CENTER);
        row.addView(descContainer, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 36, 0, 0, 0));

        descTitle = new TextView(context);
        descTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        descTitle.setTypeface(AndroidUtilities.bold());
        descContainer.addView(descTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        descText = new TextView(context);
        descText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        descText.setTextColor(hintColor);
        descContainer.addView(descText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));

        descRecommend = new TextView(context);
        descRecommend.setText("Рекомендуется");
        descRecommend.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        descRecommend.setTypeface(AndroidUtilities.bold());
        descRecommend.setTextColor(accent);
        descContainer.addView(descRecommend, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 0));

        applyDescription(selectedApi, false);
        for (int i = 0; i < 3; i++) {
            buttons[i].setSelectedState(i == selectedApi, false);
        }

        // --- доп. настройки (только CameraX) ---
        advancedBlock = createBlock(context, "Расширенные настройки");
        advancedBlock.setVisibility(selectedApi == API_X ? View.VISIBLE : View.GONE);
        rootLayout.addView(advancedBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        TextCheckCell fps60Cell = new TextCheckCell(context);
        fps60Cell.setBackground(null);
        fps60Cell.setTextAndCheck("60 FPS", SharedConfig.cameraX60Fps, false);
        fps60Cell.setOnClickListener(v -> {
            SharedConfig.toggleCameraX60Fps();
            fps60Cell.setChecked(SharedConfig.cameraX60Fps);
        });
        advancedBlock.addView(fps60Cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = rootLayout;
        return fragmentView;
    }

    private void select(int api, ApiButton[] buttons) {
        if (api == selectedApi) {
            return;
        }
        selectedApi = api;
        SharedConfig.setCameraApi(api);
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setSelectedState(i == api, true);
        }
        applyDescription(api, true);
        // блок доп. настроек выезжает/убирается самим LayoutTransition
        advancedBlock.setVisibility(api == API_X ? View.VISIBLE : View.GONE);
    }

    private void applyDescription(int api, boolean animated) {
        String title;
        String text;
        switch (api) {
            case API_2:
                title = "Camera 2 (Telegram)";
                text = "Продвинутый API. Быстрый запуск и стабильная работа на большинстве современных устройств.";
                break;
            case API_X:
                title = "CameraX";
                text = "Современный API от Google. Плавный предпросмотр и поддержка записи в 60 кадров в секунду.";
                break;
            default:
                title = "Telegram";
                text = "Классический движок камеры. Максимальная совместимость и предсказуемая работа на любых устройствах.";
                break;
        }
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        descTitle.setTextColor(api == API_X ? accent : textColor);
        descRecommend.setVisibility(api == API_X ? View.VISIBLE : View.GONE);

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

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }

    /**
     * Квадратная кнопка выбора API: скруглённый квадрат, у выбранной —
     * акцентная рамка и подсветка заливки (анимированный переход).
     */
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
