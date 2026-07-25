package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatExportManager;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

public class MglaExportContextView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private FrameLayout frameLayout;
    private View shadow;
    private View selector;
    private RLottieImageView importingImageView;
    private TextView titleTextView;
    private LineProgressView progressView;
    private ImageView closeButton;
    private BaseFragment fragment;

    private AnimatorSet animatorSet;
    private boolean visible;
    private float topPadding;
    private int lastPercent = -1;

    public MglaExportContextView(Context context, BaseFragment parentFragment) {
        super(context);
        this.fragment = parentFragment;
        createView();
        setVisibility(GONE);
    }

    private void createView() {
        final Context context = getContext();

        frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_inappPlayerBackground));
        addView(frameLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));

        selector = new View(context);
        selector.setBackground(Theme.getSelectorDrawable(false));
        frameLayout.addView(selector, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        shadow = new View(context);
        shadow.setBackgroundResource(R.drawable.blockpanel_shadow);
        addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.LEFT | Gravity.TOP, 0, 38, 0, 0));

        importingImageView = new RLottieImageView(context);
        importingImageView.setScaleType(ImageView.ScaleType.CENTER);
        importingImageView.setAutoRepeat(true);
        importingImageView.setAnimation(R.raw.import_progress, 30, 30);
        importingImageView.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(22), Theme.getColor(Theme.key_inappPlayerPlayPause)));
        addView(importingImageView, LayoutHelper.createFrame(22, 22, Gravity.TOP | Gravity.LEFT, 8, 8, 0, 0));

        titleTextView = new TextView(context);
        titleTextView.setMaxLines(1);
        titleTextView.setLines(1);
        titleTextView.setSingleLine(true);
        titleTextView.setEllipsize(TextUtils.TruncateAt.END);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleTextView.setTypeface(Typeface.DEFAULT);
        titleTextView.setTextColor(Theme.getColor(Theme.key_inappPlayerTitle));
        titleTextView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 38, 0, 38, 0));

        progressView = new LineProgressView(context);
        progressView.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        progressView.setBackColor(Theme.getColor(Theme.key_dialogLineProgressBackground));
        progressView.setVisibility(GONE);
        addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3, Gravity.LEFT | Gravity.BOTTOM, 38, 0, 38, 4));

        closeButton = new ImageView(context);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setImageResource(R.drawable.ic_ab_back);
        closeButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_inappPlayerClose), PorterDuff.Mode.MULTIPLY));
        closeButton.setRotation(45);
        closeButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_inappPlayerPlayPause) & 0x19ffffff, 1, AndroidUtilities.dp(14)));
        addView(closeButton, LayoutHelper.createFrame(36, 38, Gravity.TOP | Gravity.RIGHT));
        closeButton.setOnClickListener(v -> {
            if (fragment == null || fragment.getParentActivity() == null) {
                ChatExportManager.cancelExport();
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            builder.setTitle("Отменить экспорт?");
            builder.setMessage("Экспорт будет остановлен, частичный файл будет удалён.");
            builder.setPositiveButton(LocaleController.getString("Cancel", R.string.Cancel), (dialog, which) -> {
                ChatExportManager.cancelExport();
            });
            builder.setNegativeButton("Нет", null);
            builder.show();
        });

        frameLayout.setOnClickListener(v -> {
            if (fragment != null && fragment.getParentActivity() != null) {
                MglaExportAlert alert = new MglaExportAlert(fragment.getParentActivity(), fragment.getResourceProvider());
                fragment.showDialog(alert);
            }
        });
    }

    public void checkExport() {
        ChatExportManager.ExportState state = ChatExportManager.getCurrentState();

        boolean shouldShow = state != null && state.running && !state.canceled && !state.completed;

        if (shouldShow) {
            if (!visible) {
                showPanel();
            }
            if (state.percent != lastPercent) {
                lastPercent = state.percent;
                if (state.total > 0) {
                    String text = "Экспорт: " + state.percent + "% (" + state.progress + "/" + state.total + " сообщ.";
                    if (state.mediaTotal > 0) {
                        text += ", " + state.mediaProcessed + "/" + state.mediaTotal + " медиа";
                    }
                    titleTextView.setText(text);
                    progressView.setVisibility(VISIBLE);
                    progressView.setProgress(state.percent / 100.0f, true);
                } else {
                    titleTextView.setText("Подготовка экспорта...");
                    progressView.setVisibility(GONE);
                }
            }
        } else {
            if (visible) {
                hidePanel();
            }
            lastPercent = -1;
        }
    }

    private void showPanel() {
        visible = true;
        setVisibility(VISIBLE);
        importingImageView.playAnimation();
        if (animatorSet != null) {
            animatorSet.cancel();
            animatorSet = null;
        }
        animatorSet = new AnimatorSet();
        animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", AndroidUtilities.dp2(38)));
        animatorSet.setDuration(200);
        animatorSet.start();
    }

    private void hidePanel() {
        if (animatorSet != null) {
            animatorSet.cancel();
            animatorSet = null;
        }
        animatorSet = new AnimatorSet();
        animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", 0));
        animatorSet.setDuration(200);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
                importingImageView.stopAnimation();
            }
        });
        animatorSet.start();
        visible = false;
    }

    public void setTopPadding(float value) {
        topPadding = value;
    }

    public float getTopPadding() {
        return topPadding;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mglaExportProgressChanged ||
            id == NotificationCenter.mglaExportStarted ||
            id == NotificationCenter.mglaExportCompleted ||
            id == NotificationCenter.mglaExportCanceled ||
            id == NotificationCenter.mglaExportFailed) {
            checkExport();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportProgressChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportStarted);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportCompleted);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportCanceled);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportFailed);
        checkExport();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportProgressChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportStarted);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportCompleted);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportCanceled);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportFailed);
    }

    public void updateColors() {
        if (frameLayout != null) {
            frameLayout.setBackgroundColor(Theme.getColor(Theme.key_inappPlayerBackground));
        }
        if (importingImageView != null) {
            importingImageView.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(22), Theme.getColor(Theme.key_inappPlayerPlayPause)));
        }
        if (titleTextView != null) {
            titleTextView.setTextColor(Theme.getColor(Theme.key_inappPlayerTitle));
        }
        if (closeButton != null) {
            closeButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_inappPlayerClose), PorterDuff.Mode.MULTIPLY));
        }
        if (progressView != null) {
            progressView.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            progressView.setBackColor(Theme.getColor(Theme.key_dialogLineProgressBackground));
        }
    }
}