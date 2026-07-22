package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

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

        LinearLayout block = createBlock(context, "API камеры");

        org.telegram.ui.Components.NumberPicker picker = new org.telegram.ui.Components.NumberPicker(context);
        picker.setMinValue(0);
        picker.setMaxValue(2);
        picker.setDisplayedValues(new String[]{"Camera 1 api", "Camera 2 api", "Camera x"});
        picker.setValue(SharedConfig.cameraApi);

        LinearLayout advancedBlock = createBlock(context, "Расширенные настройки");
        advancedBlock.setVisibility(SharedConfig.cameraApi == 2 ? View.VISIBLE : View.GONE);

        TextCheckCell seamlessCell = new TextCheckCell(context);
        seamlessCell.setBackground(null);
        seamlessCell.setTextAndCheck("Бесшовное переключение", SharedConfig.cameraXSeamlessSwitch, true);
        seamlessCell.setOnClickListener(v -> {
            SharedConfig.toggleCameraXSeamlessSwitch();
            seamlessCell.setChecked(SharedConfig.cameraXSeamlessSwitch);
        });
        advancedBlock.addView(seamlessCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell fps60Cell = new TextCheckCell(context);
        fps60Cell.setBackground(null);
        fps60Cell.setTextAndCheck("Расширенный диапазон фпс (60 фпс)", SharedConfig.cameraX60Fps, true);
        fps60Cell.setOnClickListener(v -> {
            SharedConfig.toggleCameraX60Fps();
            fps60Cell.setChecked(SharedConfig.cameraX60Fps);
        });
        advancedBlock.addView(fps60Cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell stabilizationCell = new TextCheckCell(context);
        stabilizationCell.setBackground(null);
        stabilizationCell.setTextAndCheck("Стабилизация", SharedConfig.cameraXStabilization, true);
        stabilizationCell.setOnClickListener(v -> {
            SharedConfig.toggleCameraXStabilization();
            stabilizationCell.setChecked(SharedConfig.cameraXStabilization);
        });
        advancedBlock.addView(stabilizationCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell mirrorCell = new TextCheckCell(context);
        mirrorCell.setBackground(null);
        mirrorCell.setTextAndCheck("Зеркальный режим", SharedConfig.cameraXMirror, true);
        mirrorCell.setOnClickListener(v -> {
            SharedConfig.toggleCameraXMirror();
            mirrorCell.setChecked(SharedConfig.cameraXMirror);
        });
        advancedBlock.addView(mirrorCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        boolean hasWideAngle = hasWideAngleCamera(context);
        if (hasWideAngle) {
            TextCheckCell wideAngleCell = new TextCheckCell(context);
            wideAngleCell.setBackground(null);
            wideAngleCell.setTextAndCheck("Начинать с широкого угла", SharedConfig.cameraXStartWide, false);
            wideAngleCell.setOnClickListener(v -> {
                SharedConfig.toggleCameraXStartWide();
                wideAngleCell.setChecked(SharedConfig.cameraXStartWide);
            });
            advancedBlock.addView(wideAngleCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        picker.setOnValueChangedListener((picker1, oldVal, newVal) -> {
            SharedConfig.setCameraApi(newVal);
            advancedBlock.setVisibility(newVal == 2 ? View.VISIBLE : View.GONE);
        });

        block.addView(picker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 8));

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        rootLayout.addView(advancedBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
    }

    private boolean hasWideAngleCamera(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (manager == null) return false;
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        if (focalLengths != null) {
                            for (float focal : focalLengths) {
                                if (focal < 3.0f) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                return manager.getCameraIdList().length > 2;
            } catch (Throwable ignored) {
            }
        }
        return false;
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
