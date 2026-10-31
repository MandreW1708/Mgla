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
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.InstantCameraViewBase;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.recorder.round.RoundVideoSession;
import org.telegram.utils.settings.SharedSettings;

/**
 * Настройки камеры для видеосообщений (кружков): Camera1 / Camera2.
 * CAMERAX_OFF: кнопка X и ветка CameraX временно отключены.
 */
public class MglaCameraSettingsActivity extends MglaBaseSettingsActivity {

    private static final int API_1 = 0;
    private static final int API_2 = 1;
    // CAMERAX_OFF
    // private static final int API_X = 2;

    private static final int[] BITRATES = {
            750_000,
            1_000_000,
            1_200_000,
            2_000_000
    };

    private int selectedApi;
    private LinearLayout descContainer;
    private TextView descTitle;
    private TextView descText;
    private LinearLayout recordingBlock;
    private TextSettingsCell outputResolutionCell;
    private TextSettingsCell cameraResolutionCell;
    private TextSettingsCell frameRateCell;
    private TextSettingsCell bitrateCell;

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

        selectedApi = resolveSelectedApi();

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

        // CAMERAX_OFF: was {"1", "2", "X"} with 3 buttons
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
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setSelectedState(i == selectedApi, false);
        }

        recordingBlock = createBlock(context, "Запись кружка");
        rootLayout.addView(recordingBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));

        outputResolutionCell = new TextSettingsCell(context);
        outputResolutionCell.setBackground(null);
        recordingBlock.addView(outputResolutionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        outputResolutionCell.setOnClickListener(v -> showChoice(
                LocaleController.getString(R.string.RoundVideoOutputResolution),
                new CharSequence[]{"480p", "360p"},
                index -> {
                    SharedSettings.roundVideoOutputResolution.set(index == 0
                            ? RoundVideoSession.OutputResolution.P480
                            : RoundVideoSession.OutputResolution.P360);
                    refreshRecordingCells();
                }
        ));

        recordingBlock.addView(MglaUi.createDivider(context));
        cameraResolutionCell = new TextSettingsCell(context);
        cameraResolutionCell.setBackground(null);
        recordingBlock.addView(cameraResolutionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        cameraResolutionCell.setOnClickListener(v -> showChoice(
                LocaleController.getString(R.string.RoundVideoCameraResolution),
                new CharSequence[]{
                        LocaleController.getString(R.string.RoundVideoCameraResolutionHigh),
                        LocaleController.getString(R.string.RoundVideoCameraResolutionMedium),
                        LocaleController.getString(R.string.RoundVideoCameraResolutionLow)
                },
                index -> {
                    SharedSettings.roundVideoCameraResolution.set(
                            RoundVideoSession.CameraResolution.values()[index]
                    );
                    refreshRecordingCells();
                }
        ));

        recordingBlock.addView(MglaUi.createDivider(context));
        frameRateCell = new TextSettingsCell(context);
        frameRateCell.setBackground(null);
        recordingBlock.addView(frameRateCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        frameRateCell.setOnClickListener(v -> showChoice(
                LocaleController.getString(R.string.RoundVideoFrameRate),
                new CharSequence[]{"30 FPS", "60 FPS"},
                index -> {
                    SharedSettings.roundVideoFrameRate.set(index == 0
                            ? RoundVideoSession.FrameRate.FPS_30
                            : RoundVideoSession.FrameRate.FPS_60);
                    refreshRecordingCells();
                }
        ));

        recordingBlock.addView(MglaUi.createDivider(context));
        bitrateCell = new TextSettingsCell(context);
        bitrateCell.setBackground(null);
        recordingBlock.addView(bitrateCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        bitrateCell.setOnClickListener(v -> {
            CharSequence[] labelsBitrate = new CharSequence[BITRATES.length];
            for (int i = 0; i < BITRATES.length; i++) {
                labelsBitrate[i] = formatBitrate(BITRATES[i]);
            }
            showChoice(
                    LocaleController.getString(R.string.RoundVideoBitrate),
                    labelsBitrate,
                    index -> {
                        SharedSettings.roundVideoVideoBitrate.set(BITRATES[index]);
                        refreshRecordingCells();
                    }
            );
        });

        refreshRecordingCells();
        updateRecordingBlockVisibility(false);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(rootLayout, LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP
        ));
        fragmentView = scrollView;
        return fragmentView;
    }

    private void refreshRecordingCells() {
        if (outputResolutionCell == null) {
            return;
        }
        outputResolutionCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoOutputResolution),
                SharedSettings.roundVideoOutputResolution.get().getSize() + "p",
                false
        );
        cameraResolutionCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoCameraResolution),
                cameraResolutionLabel(SharedSettings.roundVideoCameraResolution.get()),
                false
        );
        frameRateCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoFrameRate),
                SharedSettings.roundVideoFrameRate.get().getValue() + " FPS",
                false
        );
        bitrateCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoBitrate),
                formatBitrate(SharedSettings.roundVideoVideoBitrate.get()),
                false
        );
    }

    private void updateRecordingBlockVisibility(boolean animated) {
        if (recordingBlock == null) {
            return;
        }
        // CAMERAX_OFF: was API_2 || API_X
        boolean visible = selectedApi == API_2;
        if (!animated) {
            recordingBlock.setVisibility(visible ? View.VISIBLE : View.GONE);
            recordingBlock.setAlpha(1f);
            return;
        }
        recordingBlock.animate().cancel();
        if (visible) {
            recordingBlock.setVisibility(View.VISIBLE);
            recordingBlock.setAlpha(0f);
            recordingBlock.animate()
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .start();
        } else {
            recordingBlock.animate()
                    .alpha(0f)
                    .setDuration(160)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .withEndAction(() -> recordingBlock.setVisibility(View.GONE))
                    .start();
        }
    }

    private void showChoice(String title, CharSequence[] choices, ChoiceHandler handler) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(choices, (dialog, index) -> handler.onChoice(index));
        showDialog(builder.create());
    }

    private static String formatBitrate(int bitrate) {
        if (bitrate % 1_000_000 == 0) {
            return (bitrate / 1_000_000) + " Mbps";
        }
        if (bitrate > 1_000_000) {
            return String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000f);
        }
        return (bitrate / 1_000) + " kbps";
    }

    private static String cameraResolutionLabel(RoundVideoSession.CameraResolution resolution) {
        if (resolution == RoundVideoSession.CameraResolution.HIGH) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionHigh);
        } else if (resolution == RoundVideoSession.CameraResolution.MEDIUM) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionMedium);
        }
        return LocaleController.getString(R.string.RoundVideoCameraResolutionLow);
    }

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    private static int resolveSelectedApi() {
        // CAMERAX_OFF
        // if (InstantCameraViewBase.isUsingCameraXImplementation()) {
        //     return API_X;
        // }
        if (InstantCameraViewBase.isUsingCamera2Implementation()) {
            return API_2;
        }
        return API_1;
    }

    private void select(int api, ApiButton[] buttons) {
        if (api == selectedApi) {
            return;
        }
        selectedApi = api;
        // CAMERAX_OFF
        // if (api == API_X) {
        //     InstantCameraViewBase.setUseCameraXImplementation(true);
        //     SharedConfig.setUseCamera2Force(null);
        // } else
        if (api == API_2) {
            InstantCameraViewBase.setUseCamera2Implementation(true);
            SharedConfig.setUseCamera2Force(null);
        } else {
            InstantCameraViewBase.setUseCamera2Implementation(false);
            SharedConfig.setUseCamera2Force(false);
        }
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setSelectedState(i == api, true);
        }
        applyDescription(api, true);
        updateRecordingBlockVisibility(true);
    }

    private void applyDescription(int api, boolean animated) {
        String title;
        String text;
        // CAMERAX_OFF
        // if (api == API_X) {
        //     title = "Camera X";
        //     text = "Google CameraX для кружков. Качество, FPS и битрейт — в блоке «Запись кружка» ниже.";
        // } else
        if (api == API_2) {
            title = "Camera 2";
            text = "Стоковый InstantCameraView2 (Camera2). Параметры записи — в блоке «Запись кружка» ниже.";
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

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
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
