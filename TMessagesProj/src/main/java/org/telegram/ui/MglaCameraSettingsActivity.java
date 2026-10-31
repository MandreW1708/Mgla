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
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.InstantCameraViewBase;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.recorder.round.RoundVideoSession;
import org.telegram.utils.settings.SharedSettings;

/**
 * Настройки камеры для видеосообщений (кружков): Camera1 / Camera2 / CameraX.
 */
public class MglaCameraSettingsActivity extends MglaBaseSettingsActivity {

    private static final int API_1 = 0;
    private static final int API_2 = 1;
    private static final int API_X = 2;

    private static final int[] CAMERA2_BITRATES = {
            750_000,
            1_000_000,
            1_200_000,
            2_000_000
    };

    private static final int[] CAMERAX_BITRATES = {
            1_200_000,
            1_800_000,
            2_500_000,
            3_500_000
    };

    private int selectedApi;
    private LinearLayout descContainer;
    private TextView descTitle;
    private TextView descText;

    private LinearLayout camera2Block;
    private TextSettingsCell c2OutputCell;
    private TextSettingsCell c2CameraCell;
    private TextSettingsCell c2FpsCell;
    private TextSettingsCell c2BitrateCell;

    private LinearLayout cameraXBlock;
    private TextSettingsCell cxPresetCell;
    private TextSettingsCell cxOutputCell;
    private TextSettingsCell cxCaptureCell;
    private TextSettingsCell cxFpsCell;
    private TextSettingsCell cxBitrateCell;
    private TextCheckCell cxStabilizationCell;
    private TextCheckCell cxCompositionCell;

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

        ApiButton[] buttons = new ApiButton[3];
        String[] labels = {"1", "2", "X"};
        for (int i = 0; i < 3; i++) {
            final int api = i;
            buttons[i] = new ApiButton(context, labels[i], accent);
            buttons[i].setOnClickListener(v -> select(api, buttons));
            buttonColumn.addView(buttons[i], LayoutHelper.createLinear(56, 56, 0, 0, 0, i == 2 ? 0 : 12));
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

        camera2Block = createBlock(context, "Запись кружка");
        rootLayout.addView(camera2Block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
        buildCamera2Rows(context);

        cameraXBlock = createBlock(context, "CameraX Pro");
        rootLayout.addView(cameraXBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));
        buildCameraXRows(context);

        refreshCamera2Cells();
        refreshCameraXCells();
        updateSettingsVisibility(false);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(rootLayout, LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP
        ));
        fragmentView = scrollView;
        return fragmentView;
    }

    private void buildCamera2Rows(Context context) {
        c2OutputCell = addValueRow(context, camera2Block);
        c2OutputCell.setOnClickListener(v -> showChoice(
                LocaleController.getString(R.string.RoundVideoOutputResolution),
                new CharSequence[]{"480p", "360p"},
                index -> {
                    SharedSettings.roundVideoOutputResolution.set(index == 0
                            ? RoundVideoSession.OutputResolution.P480
                            : RoundVideoSession.OutputResolution.P360);
                    refreshCamera2Cells();
                }
        ));

        camera2Block.addView(MglaUi.createDivider(context));
        c2CameraCell = addValueRow(context, camera2Block);
        c2CameraCell.setOnClickListener(v -> showChoice(
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
                    refreshCamera2Cells();
                }
        ));

        camera2Block.addView(MglaUi.createDivider(context));
        c2FpsCell = addValueRow(context, camera2Block);
        c2FpsCell.setOnClickListener(v -> showChoice(
                LocaleController.getString(R.string.RoundVideoFrameRate),
                new CharSequence[]{"30 FPS", "60 FPS"},
                index -> {
                    SharedSettings.roundVideoFrameRate.set(index == 0
                            ? RoundVideoSession.FrameRate.FPS_30
                            : RoundVideoSession.FrameRate.FPS_60);
                    refreshCamera2Cells();
                }
        ));

        camera2Block.addView(MglaUi.createDivider(context));
        c2BitrateCell = addValueRow(context, camera2Block);
        c2BitrateCell.setOnClickListener(v -> {
            CharSequence[] labelsBitrate = new CharSequence[CAMERA2_BITRATES.length];
            for (int i = 0; i < CAMERA2_BITRATES.length; i++) {
                labelsBitrate[i] = formatBitrate(CAMERA2_BITRATES[i]);
            }
            showChoice(
                    LocaleController.getString(R.string.RoundVideoBitrate),
                    labelsBitrate,
                    index -> {
                        SharedSettings.roundVideoVideoBitrate.set(CAMERA2_BITRATES[index]);
                        refreshCamera2Cells();
                    }
            );
        });
    }

    private void buildCameraXRows(Context context) {
        cxPresetCell = addValueRow(context, cameraXBlock);
        cxPresetCell.setOnClickListener(v -> showChoice(
                "Пресет качества",
                new CharSequence[]{"Cinema", "Ultra", "Balanced", "Efficient"},
                index -> {
                    applyCameraXPreset(RoundVideoSession.CameraXPreset.values()[index]);
                    refreshCameraXCells();
                }
        ));

        cameraXBlock.addView(MglaUi.createDivider(context));
        cxOutputCell = addValueRow(context, cameraXBlock);
        cxOutputCell.setOnClickListener(v -> showChoice(
                "Качество кружка",
                new CharSequence[]{
                        "HQ (захват↑, encode ≤640 note)",
                        "480p note",
                        "360p note"
                },
                index -> {
                    RoundVideoSession.OutputResolution value =
                            index == 0 ? RoundVideoSession.OutputResolution.P720
                                    : index == 1 ? RoundVideoSession.OutputResolution.P480
                                    : RoundVideoSession.OutputResolution.P360;
                    SharedSettings.roundVideoCameraXOutputResolution.set(value);
                    markCameraXCustomIfNeeded();
                    refreshCameraXCells();
                }
        ));

        cameraXBlock.addView(MglaUi.createDivider(context));
        cxCaptureCell = addValueRow(context, cameraXBlock);
        cxCaptureCell.setOnClickListener(v -> showChoice(
                "Захват сенсора",
                new CharSequence[]{"Ultra", "High", "Medium", "Efficient"},
                index -> {
                    SharedSettings.roundVideoCameraXCapture.set(
                            RoundVideoSession.CameraXCapture.values()[index]
                    );
                    markCameraXCustomIfNeeded();
                    refreshCameraXCells();
                }
        ));

        cameraXBlock.addView(MglaUi.createDivider(context));
        cxFpsCell = addValueRow(context, cameraXBlock);
        cxFpsCell.setOnClickListener(v -> showChoice(
                "Частота кадров",
                new CharSequence[]{"24 FPS Cinema", "30 FPS", "60 FPS"},
                index -> {
                    RoundVideoSession.FrameRate value =
                            index == 0 ? RoundVideoSession.FrameRate.FPS_24
                                    : index == 1 ? RoundVideoSession.FrameRate.FPS_30
                                    : RoundVideoSession.FrameRate.FPS_60;
                    SharedSettings.roundVideoCameraXFrameRate.set(value);
                    markCameraXCustomIfNeeded();
                    refreshCameraXCells();
                }
        ));

        cameraXBlock.addView(MglaUi.createDivider(context));
        cxBitrateCell = addValueRow(context, cameraXBlock);
        cxBitrateCell.setOnClickListener(v -> showChoice(
                "Битрейт Studio",
                new CharSequence[]{
                        formatBitrate(CAMERAX_BITRATES[0]) + " Standard",
                        formatBitrate(CAMERAX_BITRATES[1]) + " High",
                        formatBitrate(CAMERAX_BITRATES[2]) + " Pro",
                        formatBitrate(CAMERAX_BITRATES[3]) + " Studio"
                },
                index -> {
                    SharedSettings.roundVideoCameraXBitrate.set(CAMERAX_BITRATES[index]);
                    markCameraXCustomIfNeeded();
                    refreshCameraXCells();
                }
        ));

        cameraXBlock.addView(MglaUi.createDivider(context));
        cxStabilizationCell = new TextCheckCell(context);
        cxStabilizationCell.setBackground(null);
        cameraXBlock.addView(cxStabilizationCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        cxStabilizationCell.setOnClickListener(v -> {
            boolean next = !SharedSettings.roundVideoCameraXStabilization.get();
            SharedSettings.roundVideoCameraXStabilization.set(next);
            cxStabilizationCell.setChecked(next);
        });

        cameraXBlock.addView(MglaUi.createDivider(context));
        cxCompositionCell = new TextCheckCell(context);
        cxCompositionCell.setBackground(null);
        cameraXBlock.addView(cxCompositionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        cxCompositionCell.setOnClickListener(v -> {
            boolean next = !SharedSettings.roundVideoCameraXComposition.get();
            SharedSettings.roundVideoCameraXComposition.set(next);
            cxCompositionCell.setChecked(next);
        });
    }

    private TextSettingsCell addValueRow(Context context, LinearLayout block) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackground(null);
        block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private void applyCameraXPreset(RoundVideoSession.CameraXPreset preset) {
        SharedSettings.roundVideoCameraXPreset.set(preset);
        if (preset == RoundVideoSession.CameraXPreset.CINEMA) {
            SharedSettings.roundVideoCameraXOutputResolution.set(RoundVideoSession.OutputResolution.P720);
            SharedSettings.roundVideoCameraXCapture.set(RoundVideoSession.CameraXCapture.ULTRA);
            SharedSettings.roundVideoCameraXFrameRate.set(RoundVideoSession.FrameRate.FPS_24);
            SharedSettings.roundVideoCameraXBitrate.set(3_500_000);
            SharedSettings.roundVideoCameraXStabilization.set(true);
            SharedSettings.roundVideoCameraXComposition.set(true);
        } else if (preset == RoundVideoSession.CameraXPreset.ULTRA) {
            SharedSettings.roundVideoCameraXOutputResolution.set(RoundVideoSession.OutputResolution.P720);
            SharedSettings.roundVideoCameraXCapture.set(RoundVideoSession.CameraXCapture.ULTRA);
            SharedSettings.roundVideoCameraXFrameRate.set(RoundVideoSession.FrameRate.FPS_60);
            SharedSettings.roundVideoCameraXBitrate.set(2_500_000);
            SharedSettings.roundVideoCameraXStabilization.set(true);
            SharedSettings.roundVideoCameraXComposition.set(true);
        } else if (preset == RoundVideoSession.CameraXPreset.BALANCED) {
            SharedSettings.roundVideoCameraXOutputResolution.set(RoundVideoSession.OutputResolution.P480);
            SharedSettings.roundVideoCameraXCapture.set(RoundVideoSession.CameraXCapture.HIGH);
            SharedSettings.roundVideoCameraXFrameRate.set(RoundVideoSession.FrameRate.FPS_30);
            SharedSettings.roundVideoCameraXBitrate.set(1_800_000);
            SharedSettings.roundVideoCameraXStabilization.set(true);
            SharedSettings.roundVideoCameraXComposition.set(true);
        } else {
            SharedSettings.roundVideoCameraXOutputResolution.set(RoundVideoSession.OutputResolution.P360);
            SharedSettings.roundVideoCameraXCapture.set(RoundVideoSession.CameraXCapture.EFFICIENT);
            SharedSettings.roundVideoCameraXFrameRate.set(RoundVideoSession.FrameRate.FPS_30);
            SharedSettings.roundVideoCameraXBitrate.set(1_200_000);
            SharedSettings.roundVideoCameraXStabilization.set(false);
            SharedSettings.roundVideoCameraXComposition.set(true);
        }
    }

    private void markCameraXCustomIfNeeded() {
        // Keep last selected preset label only when values still match; otherwise UI shows «Свой».
        RoundVideoSession.CameraXPreset matched = matchCameraXPreset();
        if (matched != null) {
            SharedSettings.roundVideoCameraXPreset.set(matched);
        }
    }

    private RoundVideoSession.CameraXPreset matchCameraXPreset() {
        RoundVideoSession.OutputResolution out = SharedSettings.roundVideoCameraXOutputResolution.get();
        RoundVideoSession.CameraXCapture capture = SharedSettings.roundVideoCameraXCapture.get();
        RoundVideoSession.FrameRate fps = SharedSettings.roundVideoCameraXFrameRate.get();
        int bitrate = SharedSettings.roundVideoCameraXBitrate.get();
        if (out == RoundVideoSession.OutputResolution.P720
                && capture == RoundVideoSession.CameraXCapture.ULTRA
                && fps == RoundVideoSession.FrameRate.FPS_24
                && bitrate == 3_500_000) {
            return RoundVideoSession.CameraXPreset.CINEMA;
        }
        if (out == RoundVideoSession.OutputResolution.P720
                && capture == RoundVideoSession.CameraXCapture.ULTRA
                && fps == RoundVideoSession.FrameRate.FPS_60
                && bitrate == 2_500_000) {
            return RoundVideoSession.CameraXPreset.ULTRA;
        }
        if (out == RoundVideoSession.OutputResolution.P480
                && capture == RoundVideoSession.CameraXCapture.HIGH
                && fps == RoundVideoSession.FrameRate.FPS_30
                && bitrate == 1_800_000) {
            return RoundVideoSession.CameraXPreset.BALANCED;
        }
        if (out == RoundVideoSession.OutputResolution.P360
                && capture == RoundVideoSession.CameraXCapture.EFFICIENT
                && fps == RoundVideoSession.FrameRate.FPS_30
                && bitrate == 1_200_000) {
            return RoundVideoSession.CameraXPreset.EFFICIENT;
        }
        return null;
    }

    private void refreshCamera2Cells() {
        if (c2OutputCell == null) {
            return;
        }
        c2OutputCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoOutputResolution),
                SharedSettings.roundVideoOutputResolution.get().getSize() + "p",
                false
        );
        c2CameraCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoCameraResolution),
                camera2ResolutionLabel(SharedSettings.roundVideoCameraResolution.get()),
                false
        );
        c2FpsCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoFrameRate),
                SharedSettings.roundVideoFrameRate.get().getValue() + " FPS",
                false
        );
        c2BitrateCell.setTextAndValue(
                LocaleController.getString(R.string.RoundVideoBitrate),
                formatBitrate(SharedSettings.roundVideoVideoBitrate.get()),
                false
        );
    }

    private void refreshCameraXCells() {
        if (cxPresetCell == null) {
            return;
        }
        RoundVideoSession.CameraXPreset matched = matchCameraXPreset();
        if (matched != null) {
            SharedSettings.roundVideoCameraXPreset.set(matched);
        }
        cxPresetCell.setTextAndValue(
                "Пресет качества",
                matched == null ? "Свой" : cameraXPresetLabel(matched),
                false
        );
        cxOutputCell.setTextAndValue(
                "Качество кружка",
                cameraXOutputLabel(SharedSettings.roundVideoCameraXOutputResolution.get()),
                false
        );
        cxCaptureCell.setTextAndValue(
                "Захват сенсора",
                cameraXCaptureLabel(SharedSettings.roundVideoCameraXCapture.get()),
                false
        );
        cxFpsCell.setTextAndValue(
                "Частота кадров",
                SharedSettings.roundVideoCameraXFrameRate.get().getValue() + " FPS",
                false
        );
        cxBitrateCell.setTextAndValue(
                "Битрейт Studio",
                formatBitrate(SharedSettings.roundVideoCameraXBitrate.get()),
                false
        );
        cxStabilizationCell.setTextAndCheck(
                "Стабилизация видео",
                SharedSettings.roundVideoCameraXStabilization.get(),
                false
        );
        cxCompositionCell.setTextAndCheck(
                "Pro-оверлеи композиции",
                SharedSettings.roundVideoCameraXComposition.get(),
                false
        );
    }

    private void updateSettingsVisibility(boolean animated) {
        setBlockVisible(camera2Block, selectedApi == API_2, animated);
        setBlockVisible(cameraXBlock, selectedApi == API_X, animated);
    }

    private void setBlockVisible(LinearLayout block, boolean visible, boolean animated) {
        if (block == null) {
            return;
        }
        if (!animated) {
            block.setVisibility(visible ? View.VISIBLE : View.GONE);
            block.setAlpha(1f);
            return;
        }
        block.animate().cancel();
        if (visible) {
            block.setVisibility(View.VISIBLE);
            block.setAlpha(0f);
            block.animate()
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .start();
        } else {
            block.animate()
                    .alpha(0f)
                    .setDuration(160)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .withEndAction(() -> block.setVisibility(View.GONE))
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

    private static String camera2ResolutionLabel(RoundVideoSession.CameraResolution resolution) {
        if (resolution == RoundVideoSession.CameraResolution.HIGH) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionHigh);
        } else if (resolution == RoundVideoSession.CameraResolution.MEDIUM) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionMedium);
        }
        return LocaleController.getString(R.string.RoundVideoCameraResolutionLow);
    }

    private static String cameraXPresetLabel(RoundVideoSession.CameraXPreset preset) {
        if (preset == RoundVideoSession.CameraXPreset.CINEMA) {
            return "Cinema";
        }
        if (preset == RoundVideoSession.CameraXPreset.ULTRA) {
            return "Ultra";
        }
        if (preset == RoundVideoSession.CameraXPreset.BALANCED) {
            return "Balanced";
        }
        return "Efficient";
    }

    private static String cameraXOutputLabel(RoundVideoSession.OutputResolution resolution) {
        if (resolution == RoundVideoSession.OutputResolution.P720) {
            return "HQ note";
        }
        if (resolution == RoundVideoSession.OutputResolution.P480) {
            return "480p note";
        }
        return "360p note";
    }

    private static String cameraXCaptureLabel(RoundVideoSession.CameraXCapture capture) {
        if (capture == RoundVideoSession.CameraXCapture.ULTRA) {
            return "Ultra";
        }
        if (capture == RoundVideoSession.CameraXCapture.HIGH) {
            return "High";
        }
        if (capture == RoundVideoSession.CameraXCapture.MEDIUM) {
            return "Medium";
        }
        return "Efficient";
    }

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    private static int resolveSelectedApi() {
        if (InstantCameraViewBase.isUsingCameraXImplementation()) {
            return API_X;
        }
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
        if (api == API_X) {
            InstantCameraViewBase.setUseCameraXImplementation(true);
            SharedConfig.setUseCamera2Force(null);
        } else if (api == API_2) {
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
        updateSettingsVisibility(true);
    }

    private void applyDescription(int api, boolean animated) {
        String title;
        String text;
        if (api == API_X) {
            title = "Camera X";
            text = "Как в pro-клиентах: Ultra-захват + стабилизация, encode в лимит кружка (≤640). Настройки — в «CameraX Pro».";
        } else if (api == API_2) {
            title = "Camera 2";
            text = "Стоковый InstantCameraView2. Базовые параметры — в «Запись кружка» (те же, что в экспериментальных).";
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
