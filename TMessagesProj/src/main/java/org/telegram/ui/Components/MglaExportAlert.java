package org.telegram.ui.Components;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatExportManager;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

public class MglaExportAlert extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private TextView percentTextView;
    private LineProgressView lineProgressView;
    private RLottieImageView imageView;
    private TextView infoTextView;
    private TextView detailTextView;
    private BottomSheetCell cancelButton;
    private BottomSheetCell doneButton;
    private boolean completed;
    private boolean canceled;

    public static class BottomSheetCell extends FrameLayout {
        private View background;
        private TextView textView;
        private RLottieImageView imageView;
        private LinearLayout linearLayout;
        private Theme.ResourcesProvider resourcesProvider;

        public BottomSheetCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            background = new View(context);
            background.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(4), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
            addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 16, 16, 16, 16));

            linearLayout = new LinearLayout(context);
            linearLayout.setOrientation(LinearLayout.HORIZONTAL);
            addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            imageView = new RLottieImageView(context);
            imageView.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(20), getThemedColor(Theme.key_featuredStickers_buttonText)));
            imageView.setScaleType(ImageView.ScaleType.CENTER);
            imageView.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.MULTIPLY));
            imageView.setAnimation(R.raw.import_check, 26, 26);
            imageView.setScaleX(0.8f);
            imageView.setScaleY(0.8f);
            linearLayout.addView(imageView, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));

            textView = new TextView(context);
            textView.setLines(1);
            textView.setSingleLine(true);
            textView.setGravity(Gravity.CENTER);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTypeface(AndroidUtilities.bold());
            linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(80), MeasureSpec.EXACTLY));
        }

        public void setText(CharSequence text) {
            textView.setText(text);
        }

        public void setButtonColor(int color, int pressedColor) {
            background.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(4), color, pressedColor));
        }

        protected int getThemedColor(int key) {
            return Theme.getColor(key, resourcesProvider);
        }
    }

    public MglaExportAlert(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        setApplyBottomPadding(false);
        setApplyTopPadding(false);

        FrameLayout frameLayout = new FrameLayout(context);
        setCustomView(frameLayout);

        TextView titleTextView = new TextView(context);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleTextView.setSingleLine(true);
        titleTextView.setEllipsize(TextUtils.TruncateAt.END);
        titleTextView.setText("Экспорт чата");
        frameLayout.addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 17, 20, 17, 0));

        imageView = new RLottieImageView(context);
        imageView.setAutoRepeat(true);
        imageView.setAnimation(R.raw.import_loop, 120, 120);
        imageView.playAnimation();
        frameLayout.addView(imageView, LayoutHelper.createFrame(160, 160, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 17, 79, 17, 0));

        percentTextView = new TextView(context);
        percentTextView.setTypeface(AndroidUtilities.bold());
        percentTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        percentTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        frameLayout.addView(percentTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 17, 262, 17, 0));

        lineProgressView = new LineProgressView(getContext());
        lineProgressView.setProgressColor(getThemedColor(Theme.key_featuredStickers_addButton));
        lineProgressView.setBackColor(getThemedColor(Theme.key_dialogLineProgressBackground));
        frameLayout.addView(lineProgressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 4, Gravity.LEFT | Gravity.TOP, 50, 307, 50, 0));

        detailTextView = new TextView(context);
        detailTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        detailTextView.setTypeface(AndroidUtilities.bold());
        detailTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        detailTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        frameLayout.addView(detailTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 17, 340, 17, 0));

        infoTextView = new TextView(context);
        infoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        infoTextView.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        infoTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        infoTextView.setText("Экспортируются сообщения и медиа из чата. Не закрывайте приложение во время экспорта.");
        frameLayout.addView(infoTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 30, 388, 30, 44));

        // Cancel button
        cancelButton = new BottomSheetCell(context, resourcesProvider);
        cancelButton.setText("Отменить экспорт");
        cancelButton.setButtonColor(getThemedColor(Theme.key_dialogButton), getThemedColor(Theme.key_dialogButton));
        cancelButton.background.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
            builder.setTitle("Отменить экспорт?");
            builder.setMessage("Экспорт будет остановлен, частичный файл будет удалён.");
            builder.setPositiveButton(LocaleController.getString("Cancel", R.string.Cancel), (dialog, which) -> {
                ChatExportManager.cancelExport();
            });
            builder.setNegativeButton("Нет", null);
            builder.show();
        });
        frameLayout.addView(cancelButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.TOP, 34, 247, 34, 0));

        // Done button (hidden initially)
        doneButton = new BottomSheetCell(context, resourcesProvider);
        doneButton.setText("Готово");
        doneButton.setVisibility(View.INVISIBLE);
        doneButton.background.setPivotY(AndroidUtilities.dp(48));
        doneButton.background.setScaleY(0.04f);
        doneButton.background.setOnClickListener(v -> dismiss());
        frameLayout.addView(doneButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.TOP, 34, 247, 34, 0));

        updateState();

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportProgressChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportCompleted);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportCanceled);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportFailed);
    }

    private void updateState() {
        ChatExportManager.ExportState state = ChatExportManager.getCurrentState();
        if (state == null) {
            percentTextView.setText("0%");
            lineProgressView.setProgress(0, false);
            detailTextView.setText("");
            return;
        }

        if (state.completed) {
            setCompleted(state);
        } else if (state.canceled) {
            setCanceled();
        } else if (state.error != null) {
            setError(state);
        } else {
            percentTextView.setText(String.format("%d%%", state.percent));
            lineProgressView.setProgress(state.percent / 100.0f, true);
            if (state.total > 0) {
                String detail = "Обработано: " + state.progress + " из " + state.total + " сообщений";
                if (state.mediaTotal > 0) {
                    detail += "\nМедиа: " + state.mediaProcessed + " из " + state.mediaTotal;
                    if (!state.currentMediaInfo.isEmpty()) {
                        detail += " (скачивается: " + state.currentMediaInfo + ")";
                    }
                }
                detailTextView.setText(detail);
            } else {
                detailTextView.setText("Подготовка...");
            }
        }
    }

    private void setCompleted(ChatExportManager.ExportState state) {
        if (completed) return;
        completed = true;
        imageView.setAutoRepeat(false);
        imageView.setAnimation(R.raw.import_finish, 120, 120);
        imageView.playAnimation();

        percentTextView.setText("100%");
        lineProgressView.setProgress(1.0f, true);
        detailTextView.setText("Экспорт завершён!");
        infoTextView.setText("Файл сохранён: " + state.outputFilePath);

        cancelButton.setVisibility(View.GONE);
        doneButton.setVisibility(View.VISIBLE);
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.setDuration(250);
        animatorSet.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        animatorSet.playTogether(
            ObjectAnimator.ofFloat(doneButton.linearLayout, View.TRANSLATION_Y, AndroidUtilities.dp(8), 0)
        );
        doneButton.background.animate().scaleY(1.0f).setInterpolator(new OvershootInterpolator(1.02f)).setDuration(250).start();
        doneButton.imageView.animate().scaleY(1.0f).scaleX(1.0f).setInterpolator(new OvershootInterpolator(1.02f)).setDuration(250).start();
        doneButton.imageView.playAnimation();
        animatorSet.start();
    }

    private void setCanceled() {
        if (canceled || completed) return;
        canceled = true;
        imageView.setAutoRepeat(false);
        percentTextView.setText("Отменено");
        lineProgressView.setProgress(0, true);
        detailTextView.setText("Экспорт отменён");
        infoTextView.setText("Частичный файл удалён.");

        cancelButton.setVisibility(View.GONE);
        doneButton.setVisibility(View.VISIBLE);
        doneButton.setText("Закрыть");
        doneButton.background.animate().scaleY(1.0f).setInterpolator(new OvershootInterpolator(1.02f)).setDuration(250).start();
        doneButton.imageView.animate().scaleY(1.0f).scaleX(1.0f).setInterpolator(new OvershootInterpolator(1.02f)).setDuration(250).start();
    }

    private void setError(ChatExportManager.ExportState state) {
        if (completed || canceled) return;
        imageView.setAutoRepeat(false);
        percentTextView.setText("Ошибка");
        lineProgressView.setProgress(0, true);
        detailTextView.setText("Ошибка экспорта");
        infoTextView.setText(state.error != null ? state.error : "Неизвестная ошибка");

        cancelButton.setVisibility(View.GONE);
        doneButton.setVisibility(View.VISIBLE);
        doneButton.setText("Закрыть");
        doneButton.background.animate().scaleY(1.0f).setInterpolator(new OvershootInterpolator(1.02f)).setDuration(250).start();
        doneButton.imageView.animate().scaleY(1.0f).scaleX(1.0f).setInterpolator(new OvershootInterpolator(1.02f)).setDuration(250).start();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mglaExportProgressChanged ||
            id == NotificationCenter.mglaExportCompleted ||
            id == NotificationCenter.mglaExportCanceled ||
            id == NotificationCenter.mglaExportFailed) {
            updateState();
        }
    }

    @Override
    public void dismissInternal() {
        super.dismissInternal();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportProgressChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportCompleted);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportCanceled);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportFailed);
    }
}