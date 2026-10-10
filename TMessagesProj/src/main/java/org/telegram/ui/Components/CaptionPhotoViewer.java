package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ActionBar.Theme.RIPPLE_MASK_CIRCLE_20DP;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.MglaStats;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.AiAssistant;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.DarkThemeResourceProvider;
import org.telegram.ui.Stories.recorder.CaptionContainerView;
import org.telegram.ui.Stories.recorder.HintView2;

public class CaptionPhotoViewer extends CaptionContainerView {

    private boolean addPhotoVisible;
    private final ImageView addPhotoButton;

    private boolean timerVisible;
    private final ImageView timerButton;
    private final PeriodDrawable timerDrawable;
    private ItemOptions timerPopup;
    private AiButtonDrawable aiButtonIcon;
    private ImageView aiButton;
    public HintView2 aiHint;

    private int timer = 0;
    private final int SHOW_ONCE = 0x7FFFFFFF;
    private final int[] values = new int[] { SHOW_ONCE, 3, 10, 30, 0 };

    private final HintView2 hint;
    private final Runnable applyCaption;

    private final RectF moveButtonBounds = new RectF();
    private Drawable moveButtonIcon;
    private final AnimatedTextView.AnimatedTextDrawable moveButtonText = new AnimatedTextView.AnimatedTextDrawable();
    private final ButtonBounce moveButtonBounce = new ButtonBounce(this);

    private BlurredBackgroundDrawable backgroundForCaptionButton;

    /** Mgla AI rewrite button (same prefs / prompts as chat {@code ChatActivityEnterView}). */
    private ImageView aiEditorButton;

    @Override
    protected int getEditTextStyle() {
        return EditTextEmoji.STYLE_PHOTOVIEWER;
    }

    @Override
    protected boolean useChatLikeExpandedCaption() {
        return true;
    }

    public CaptionPhotoViewer(
        Context context,
        FrameLayout rootView,
        SizeNotifierFrameLayout sizeNotifierFrameLayout,
        FrameLayout containerView,
        Theme.ResourcesProvider resourcesProvider,
        BlurringShader.BlurManager blurManager,
        Runnable applyCaption
    ) {
        super(context, rootView, sizeNotifierFrameLayout, containerView, resourcesProvider, blurManager);
        this.applyCaption = applyCaption;
        // Match chat input row / apply disc height (38dp): tighter text padding + centered 38dp controls.
        if (editText != null) {
            editText.setTranslationY(0);
            editText.getEditText().setPadding(0, dp(6), 0, dp(6));
            editText.getEditText().setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            // Hint ("Add caption…") — true vertical center in the 38dp row (stock path nudges it up).
            editText.getEditText().hintLayoutCenterVertically = true;
            View emojiButton = editText.getEmojiButton();
            if (emojiButton != null) {
                emojiButton.setTranslationY(0);
                FrameLayout.LayoutParams emojiLp = (FrameLayout.LayoutParams) emojiButton.getLayoutParams();
                if (emojiLp != null) {
                    emojiLp.width = dp(38);
                    emojiLp.height = dp(38);
                    // BOTTOM in a 38dp row == vertical center; stays on the last line when multiline.
                    emojiLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
                    emojiButton.setLayoutParams(emojiLp);
                }
            }
            FrameLayout.LayoutParams textLp = (FrameLayout.LayoutParams) editText.getEditText().getLayoutParams();
            if (textLp != null) {
                if (textLp.leftMargin == dp(40)) {
                    textLp.leftMargin = dp(38);
                }
                textLp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
                editText.getEditText().setLayoutParams(textLp);
            }
        }
        setupAiEditorButton(context);

        moveButtonText.setTextSize(dp(14));
        moveButtonText.setOverrideFullWidth(AndroidUtilities.displaySize.x);
        moveButtonText.setTextColor(0xFFFFFFFF);
        if (isAtTop()) {
            moveButtonText.setText(getString(R.string.MoveCaptionDown));
            moveButtonIcon = context.getResources().getDrawable(R.drawable.menu_link_below);
        } else {
            moveButtonText.setText(getString(R.string.MoveCaptionUp));
            moveButtonIcon = context.getResources().getDrawable(R.drawable.menu_link_above);
        }

        addPhotoButton = new ImageView(context);
        addPhotoButton.setImageResource(R.drawable.filled_add_photo);
        addPhotoButton.setScaleType(ImageView.ScaleType.CENTER);
        addPhotoButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        addPhotoButton.setBackground(Theme.createSelectorDrawable(Theme.ACTION_BAR_WHITE_SELECTOR_COLOR, RIPPLE_MASK_CIRCLE_20DP, dp(18)));
        setAddPhotoVisible(false, false);
        addView(addPhotoButton, LayoutHelper.createFrame(44, 44, Gravity.LEFT | (isAtTop() ? Gravity.TOP : Gravity.BOTTOM), 14, isAtTop() ? 6 : 0, 0, isAtTop() ? 0 : 6));

        timerButton = new ImageView(context);
        timerButton.setImageDrawable(timerDrawable = new PeriodDrawable());
        timerButton.setBackground(Theme.createSelectorDrawable(Theme.ACTION_BAR_WHITE_SELECTOR_COLOR, RIPPLE_MASK_CIRCLE_20DP, dp(18)));
        timerButton.setScaleType(ImageView.ScaleType.CENTER);
        setTimerVisible(false, false);
        addView(timerButton, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | (isAtTop() ? Gravity.TOP : Gravity.BOTTOM), 0, isAtTop() ? 6 : 0, 10, isAtTop() ? 0 : 6));

        hint = new HintView2(context, isAtTop() ? HintView2.DIRECTION_TOP : HintView2.DIRECTION_BOTTOM);
        hint.setRounding(12);
        hint.setPadding(dp(12), dp(isAtTop() ? 8 : 0), dp(12), dp(isAtTop() ? 0 : 8));
        hint.setJoint(1, -21);
        hint.setMultilineText(true);
        addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 80, Gravity.RIGHT | (isAtTop() ? Gravity.TOP : Gravity.BOTTOM)));

        aiButton = new ImageView(context);
        aiButton.setImageDrawable(aiButtonIcon = new AiButtonDrawable(context));
        aiButton.setScaleType(ImageView.ScaleType.CENTER);
        aiButton.setColorFilter(new PorterDuffColorFilter(0xBBFFFFFF, PorterDuff.Mode.MULTIPLY));
        aiButton.setBackground(Theme.createSelectorDrawable(0x40FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP, dp(16)));
        addView(aiButton, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.RIGHT, 8, 0, 8, 0));
        aiButton.setContentDescription(getString(R.string.AIEditor));
        ScaleStateListAnimator.apply(aiButton);
        editText.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable editable) {
                showAiButton(editText.getEditText().getLineCount() > 2 && editable != null && !TextUtils.isEmpty(editable.toString().trim()));
            }
        });
        aiButton.setVisibility(View.GONE);
        aiButton.setAlpha(0.0f);
        aiButton.setScaleX(0.6f);
        aiButton.setScaleY(0.6f);
        aiButton.setOnClickListener(v -> {
            MessagesController.getGlobalMainSettings().edit().putInt("aihintshown", 3).apply();
            new AIEditorAlert(getContext(), new DarkThemeResourceProvider())
                .setText(editText.getText())
                .setOnUse(text -> {
                    editText.setText(text);
                    editText.setSelection(text.length(), text.length());
                })
                .setOnSend(0, true, (text, scheduleDate, scheduleRepeatPeriod, notify) -> {
                    editText.setText(text);
                    done();
                })
                .show();
        });

        timerButton.setOnClickListener(e -> {
            if (timerPopup != null && timerPopup.isShown()) {
                timerPopup.dismiss();
                timerPopup = null;
                return;
            }
            hint.hide();

            timerPopup = ItemOptions.makeOptions(rootView, new DarkThemeResourceProvider(), timerButton);
            timerPopup.setDimAlpha(0);
            timerPopup.addText(getString(R.string.TimerPeriodHint), 13, dp(200));
            timerPopup.addGap();
            for (int value : values) {
                String text;
                if (value == 0) {
                    text = getString(R.string.TimerPeriodDoNotDelete);
                } else if (value == SHOW_ONCE) {
                    text = getString(R.string.TimerPeriodOnce);
                } else {
                    text = LocaleController.formatPluralString("Seconds", value);
                }
                timerPopup.add(0, text, () -> changeTimer(value));
                if (this.timer == value) {
                    timerPopup.putCheck();
                }
            }
            timerPopup.show();
        });
    }

    @Override
    protected void onLineCountChanged(int oldLineCount, int newLineCount) {
        final CharSequence text = getText();
        showAiButton(newLineCount > 2 && text != null && !TextUtils.isEmpty(text.toString().trim()));
        if (shownAiButton && (oldLineCount < 3) != (newLineCount < 3)) {
            invalidate();
        }
    }

    @Override
    public void clear() {
        super.clear();
        showAiButton(false);
    }

    @Override
    public void setText(CharSequence text) {
        super.setText(text);
    }

    private final AnimatedFloat lineCountAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    private final AnimatedFloat moveButtonAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat moveButtonExpandedAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
    private boolean moveButtonVisible;
    private boolean moveButtonExpanded;

    public void expandMoveButton() {
        AndroidUtilities.cancelRunOnUIThread(collapseMoveButton);
        moveButtonExpanded = MessagesController.getInstance(currentAccount).shouldShowMoveCaptionHint();
        if (moveButtonExpanded) {
            MessagesController.getInstance(currentAccount).incrementMoveCaptionHint();
            invalidate();
            AndroidUtilities.runOnUIThread(collapseMoveButton, 5000);
        }
    }

    private final Runnable collapseMoveButton = () -> {
        if (moveButtonExpanded) {
            moveButtonExpanded = false;
            invalidate();
        }
    };

    protected void openedKeyboard() {
        expandMoveButton();
    }

    @Override
    public void updateKeyboard(int keyboardHeight) {
        final boolean wasOpen = super.toKeyboardShow;
        super.updateKeyboard(keyboardHeight);
        if (!wasOpen && keyboardNotifier.keyboardVisible()) {
            openedKeyboard();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (aiButton != null) {
            aiButton.setTranslationX(-dp(4 * (1f - keyboardT)));
            aiButton.setTranslationY(
                (isAtTop() ? bounds.bottom - dp(44) : bounds.top) +
                (isAtTop() ? 1 : -1) * dp(3) * Utilities.clamp01(-lineCountAnimated.set(editText.getEditText().getLineCount()) + 4)
            );
        }

        final float moveButtonAlpha = moveButtonAnimated.set(moveButtonVisible, !showMoveButton());
        final float moveButtonExpanded = moveButtonExpandedAnimated.set(this.moveButtonExpanded);
        if (moveButtonAlpha > 0.0f) {
            float s = moveButtonBounce.getScale(.03f);
            final int offset = dp(4 * (1f - keyboardT));
            if (isAtTop()) {
                moveButtonBounds.set(offset + dp(7), bounds.bottom + dp(10), offset + dp(7 + 37) + (moveButtonText.getCurrentWidth() + dp(11)) * moveButtonExpanded, bounds.bottom + dp(10 + 32));
            } else {
                moveButtonBounds.set(offset + dp(7), bounds.top - dp(32 + 10), offset + dp(7 + 37) + (moveButtonText.getCurrentWidth() + dp(11)) * moveButtonExpanded, bounds.top - dp(10));
            }
            if (moveButtonAlpha < 1) {
                canvas.saveLayerAlpha(moveButtonBounds, (int) (0xFF * moveButtonAlpha), Canvas.ALL_SAVE_FLAG);
            } else {
                canvas.save();
            }
            canvas.scale(s, s, moveButtonBounds.centerX(), moveButtonBounds.centerY());
            canvas.clipRect(moveButtonBounds);
            float r = dpf2(16f);

            if (factoryForMentions != null) {
                if (backgroundForCaptionButton == null) {
                    backgroundForCaptionButton = factoryForMentions.create(this)
                        .setColorProvider(BlurredBackgroundProviderImpl.photoViewer(resourcesProvider))
                        .setPadding(dp(5))
                        .setRadius(dp(16));
                }

                moveButtonBounds.round(AndroidUtilities.rectTmp2);
                AndroidUtilities.rectTmp2.inset(-dp(5), -dp(5));
                backgroundForCaptionButton.setBounds(AndroidUtilities.rectTmp2);
                backgroundForCaptionButton.draw(canvas);
            }
            moveButtonIcon.setBounds((int) (moveButtonBounds.left + dp(9)), (int) (moveButtonBounds.centerY() - dp(10)), (int) (moveButtonBounds.left + dp(9 + 20)), (int) (moveButtonBounds.centerY() + dp(10)));
            moveButtonIcon.draw(canvas);
            moveButtonText.setBounds(moveButtonBounds.left + dp(37), moveButtonBounds.top, moveButtonBounds.right, moveButtonBounds.bottom);
            moveButtonText.setAlpha((int) (0xFF * moveButtonExpanded));
            moveButtonText.draw(canvas);
            canvas.restore();
        }
    }

    public void setOnAddPhotoClick(View.OnClickListener listener) {
        addPhotoButton.setOnClickListener(listener);
    }

    public void setAddPhotoVisible(boolean visible, boolean animated) {
        addPhotoVisible = visible;
        addPhotoButton.animate().cancel();
        if (animated) {
            addPhotoButton.setVisibility(View.VISIBLE);
            addPhotoButton.animate().alpha(visible ? 1f : 0f).translationX(visible ? 0 : dp(-8)).withEndAction(() -> {
                if (!visible) {
                    timerButton.setVisibility(View.GONE);
                }
            }).start();
        } else {
            addPhotoButton.setVisibility(visible ? View.VISIBLE : View.GONE);
            addPhotoButton.setAlpha(visible ? 1f : 0f);
            addPhotoButton.setTranslationX(visible ? 0 : dp(-8));
        }
        updateEditTextLeft();

        MarginLayoutParams lp = (MarginLayoutParams) editText.getLayoutParams();
        lp.rightMargin = dp(32 + (addPhotoVisible && timerVisible ? 33 : 0));
        editText.setLayoutParams(lp);
    }

    @Override
    protected int getEditTextLeft() {
        return addPhotoVisible ? dp(31) : 0;
    }

    private boolean isVideo;
    public void setIsVideo(boolean isVideo) {
        this.isVideo = isVideo;
    }

    @Override
    protected void onTextChange() {
        if (applyCaption != null) {
            applyCaption.run();
        }
    }

    public void setTimerVisible(boolean visible, boolean animated) {
        timerVisible = visible;
        timerButton.animate().cancel();
        if (animated) {
            timerButton.setVisibility(View.VISIBLE);
            timerButton.animate().alpha(visible ? 1f : 0f).translationX(visible ? 0 : dp(8)).withEndAction(() -> {
                if (!visible) {
                    timerButton.setVisibility(View.GONE);
                }
            }).start();
        } else {
            timerButton.setVisibility(visible ? View.VISIBLE : View.GONE);
            timerButton.setAlpha(visible ? 1f : 0f);
            timerButton.setTranslationX(visible ? 0 : dp(8));
        }

        MarginLayoutParams lp = (MarginLayoutParams) editText.getLayoutParams();
        lp.rightMargin = dp(32 + (addPhotoVisible && timerVisible ? 33 : 0));
        editText.setLayoutParams(lp);
    }

    public boolean hasTimer() {
        return timerVisible && timer > 0;
    }

    public void setTimer(int value) {
        this.timer = value;
        timerDrawable.setValue(timer == SHOW_ONCE ? 1 : Math.max(1, timer), timer > 0, true);
        if (hint != null) {
            hint.hide();
        }
    }

    private void changeTimer(int value) {
        if (this.timer == value) {
            return;
        }
        setTimer(value);
        if (onTTLChange != null) {
            onTTLChange.run(value);
        }
        CharSequence text;
        if (value == 0) {
            text = getString(isVideo ? R.string.TimerPeriodVideoKeep : R.string.TimerPeriodPhotoKeep);
            hint.setMaxWidthPx(getMeasuredWidth());
            hint.setMultilineText(false);
            hint.setInnerPadding(13, 4, 10, 4);
            hint.setIconMargin(0);
            hint.setIconTranslate(0, -dp(1));
        } else if (value == SHOW_ONCE) {
            text = getString(isVideo ? R.string.TimerPeriodVideoSetOnce : R.string.TimerPeriodPhotoSetOnce);
            hint.setMaxWidthPx(getMeasuredWidth());
            hint.setMultilineText(false);
            hint.setInnerPadding(13, 4, 10, 4);
            hint.setIconMargin(0);
            hint.setIconTranslate(0, -dp(1));
        } else if (value > 0) {
            text = AndroidUtilities.replaceTags(LocaleController.formatPluralString(isVideo ? "TimerPeriodVideoSetSeconds" : "TimerPeriodPhotoSetSeconds", value));
            hint.setMultilineText(true);
            hint.setMaxWidthPx(HintView2.cutInFancyHalf(text, hint.getTextPaint()));
            hint.setInnerPadding(12, 7, 11, 7);
            hint.setIconMargin(2);
            hint.setIconTranslate(0, 0);
        } else {
            return;
        }
        hint.setTranslationY((-Math.min(dp(34), getEditTextHeight()) - dp(14)) * (isAtTop() ? -1.0f : 1.0f));
        hint.setText(text);
        final int iconResId = value > 0 ? R.raw.fire_on : R.raw.fire_off;
        RLottieDrawable icon = new RLottieDrawable(iconResId, dp(34), dp(34));
        icon.start();
        hint.setIcon(icon);
        hint.show();

        moveButtonExpanded = false;
        AndroidUtilities.cancelRunOnUIThread(collapseMoveButton);
        invalidate();
    }

    @Override
    protected void onEditHeightChange(int height) {
        hint.setTranslationY((-Math.min(dp(34), height) - dp(10)) * (isAtTop() ? -1.0f : 1.0f));
    }

    @Override
    protected boolean clipChild(View child) {
        return child != hint && child != applyButton && child != applyGlassButton;
    }

    private Utilities.Callback<Integer> onTTLChange;
    public void setOnTimerChange(Utilities.Callback<Integer> onTTLChange) {
        this.onTTLChange = onTTLChange;
    }

    @Override
    protected int getCaptionLimit() {
        return UserConfig.getInstance(currentAccount).isPremium() ? getCaptionPremiumLimit() : getCaptionDefaultLimit();
    }

    @Override
    protected int getCaptionDefaultLimit() {
        return MessagesController.getInstance(currentAccount).captionLengthLimitDefault;
    }

    @Override
    protected int getCaptionPremiumLimit() {
        return MessagesController.getInstance(currentAccount).captionLengthLimitPremium;
    }

    @Override
    protected void beforeUpdateShownKeyboard(boolean show) {
        if (!show) {
            timerButton.setVisibility(timerVisible ? View.VISIBLE : View.GONE);
            addPhotoButton.setVisibility(addPhotoVisible ? View.VISIBLE : View.GONE);
        }
        if (hint != null) {
            hint.hide();
        }
    }

    @Override
    protected void onUpdateShowKeyboard(float keyboardT) {
        timerButton.setAlpha(1f - keyboardT);
        addPhotoButton.setAlpha(1f - keyboardT);
        if (aiEditorButton != null) {
            aiEditorButton.setAlpha(keyboardT);
            aiEditorButton.setVisibility(keyboardT > 0.01f ? View.VISIBLE : View.GONE);
        }
    }

    private void setupAiEditorButton(Context context) {
        SharedPreferences mglaPrefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        boolean aiEditorEnabled = MglaFeatureFlags.isAllowed("ai_enabled")
            && MglaFeatureFlags.isAllowed("ai_editor")
            && mglaPrefs.getBoolean("ai_enabled", true)
            && mglaPrefs.getBoolean("ai_editor", false);
        if (!aiEditorEnabled || editText == null) {
            return;
        }

        aiEditorButton = new ImageView(context);
        aiEditorButton.setScaleType(ImageView.ScaleType.CENTER);
        aiEditorButton.setImageResource(R.drawable.menu_rewrite);
        aiEditorButton.setColorFilter(new PorterDuffColorFilter(0xBBFFFFFF, PorterDuff.Mode.SRC_IN));
        aiEditorButton.setBackground(Theme.createSelectorDrawable(0x40FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP, dp(16)));
        aiEditorButton.setContentDescription("AI-редактор");
        aiEditorButton.setClickable(true);
        aiEditorButton.setFocusable(true);
        aiEditorButton.setAlpha(0f);
        aiEditorButton.setVisibility(View.GONE);
        aiEditorButton.setOnClickListener(v -> {
            if (editText == null) {
                return;
            }
            String text = editText.getText() != null ? editText.getText().toString() : "";
            if (TextUtils.isEmpty(text.trim())) {
                return;
            }

            AlertDialog.Builder menu = new AlertDialog.Builder(context, new DarkThemeResourceProvider());
            menu.setTitle("AI-редактор");
            menu.setItems(new CharSequence[]{"Исправить ошибки", "Укоротить", "Переделать в спокойное"}, new int[]{R.drawable.menu_proofread, R.drawable.msg_forward, R.drawable.menu_rewrite}, (dialog, which) -> {
                String prompt;
                switch (which) {
                    case 0: prompt = "Ниже текст с возможными ошибками. Твоя задача — ТОЛЬКО исправить орфографию и грамматику. НЕ отвечай на текст, НЕ комментируй его, НЕ продолжай диалог. Верни ИСКЛЮЧИТЕЛЬНО исправленный текст, без пояснений:\n\n---\n"; break;
                    case 1: prompt = "Ниже текст, который нужно сократить. Твоя задача — ТОЛЬКО сократить, сохранив смысл. НЕ отвечай на текст, НЕ комментируй его, НЕ продолжай диалог. Верни ИСКЛЮЧИТЕЛЬНО сокращённый текст, без пояснений:\n\n---\n"; break;
                    case 2: prompt = "Ниже текст, который нужно переписать в спокойном дружелюбном тоне. Твоя задача — ТОЛЬКО переписать, убрав агрессию и негатив. НЕ отвечай на текст, НЕ комментируй его, НЕ продолжай диалог. Верни ИСКЛЮЧИТЕЛЬНО переписанный текст, без пояснений:\n\n---\n"; break;
                    default: return;
                }
                MglaStats.count("ai:editor:" + which);
                aiEditorButton.setEnabled(false);
                aiEditorButton.setAlpha(0.5f);

                AiAssistant.getInstance().sendMessage(prompt + text,
                    new AiAssistant.AiCallback() {
                        @Override
                        public void onResponse(String edited) {
                            if (editText != null) {
                                editText.setText(edited.trim());
                                editText.setSelection(edited.trim().length(), edited.trim().length());
                            }
                            aiEditorButton.setEnabled(true);
                            aiEditorButton.setAlpha(keyboardT);
                        }

                        @Override
                        public void onError(String error) {
                            aiEditorButton.setEnabled(true);
                            aiEditorButton.setAlpha(keyboardT);
                            if (error != null) {
                                BaseFragment fragment = LaunchActivity.getLastFragment();
                                if (fragment != null) {
                                    BulletinFactory.of(fragment).createErrorBulletin(error).show();
                                }
                            }
                        }
                    }
                );
            });
            menu.show();
        });

        // Tucked against the 38dp emoji button — same vertical alignment as emoji in the 38dp row.
        editText.addView(aiEditorButton, LayoutHelper.createFrame(38, 38, Gravity.BOTTOM | Gravity.LEFT, 28, 0, 0, 0));

        // Make room for the AI button in the text field (emoji is 38dp).
        FrameLayout.LayoutParams textLp = (FrameLayout.LayoutParams) editText.getEditText().getLayoutParams();
        if (textLp != null) {
            textLp.leftMargin = dp(28 + 38);
            editText.getEditText().setLayoutParams(textLp);
        }
    }

    @Override
    protected void afterUpdateShownKeyboard(boolean show) {
        timerButton.setVisibility(!show && timerVisible ? View.VISIBLE : View.GONE);
        addPhotoButton.setVisibility(!show && addPhotoVisible ? View.VISIBLE : View.GONE);
        if (show) {
            timerButton.setVisibility(View.GONE);
            addPhotoButton.setVisibility(View.GONE);
        }
    }

    @Override
    protected int additionalKeyboardHeight() {
        return 0;
    }

    @Override
    public void updateColors(Theme.ResourcesProvider resourcesProvider) {
        super.updateColors(resourcesProvider);
        timerDrawable.updateColors(0xffffffff, Theme.getColor(Theme.key_chat_editMediaButton, resourcesProvider), 0xffffffff);
    }

    @Override
    protected void setupMentionContainer() {

    }

    protected boolean showMoveButton() {
        return false;
    }

    public void setShowMoveButtonVisible(boolean visible, boolean animated) {
        if (moveButtonVisible == visible && animated) return;
        moveButtonVisible = visible;
        if (!animated) {
            moveButtonAnimated.set(visible, true);
        }
        invalidate();
    }

    protected void onMoveButtonClick() {

    }

    @Override
    public int getEditTextHeight() {
        return super.getEditTextHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            moveButtonBounce.setPressed(moveButtonAnimated.get() > 0 && moveButtonBounds.contains(event.getX(), event.getY()));
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (moveButtonBounce.isPressed() && (moveButtonAnimated.get() <= 0 || !moveButtonBounds.contains(event.getX(), event.getY()))) {
                moveButtonBounce.setPressed(false);
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (moveButtonBounce.isPressed()) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    onMoveButtonClick();
                    moveButtonText.setText(getString(isAtTop() ? R.string.MoveCaptionDown : R.string.MoveCaptionUp), true);
                }
                moveButtonBounce.setPressed(false);
                return true;
            }
        }
        return moveButtonBounce.isPressed() || super.dispatchTouchEvent(event);
    }


    private boolean shownAiButton;
    private void showAiButton(boolean show_) {
        final boolean show = show_;

        if (shownAiButton == show) return;
        if (show) {
            MessagesController.getInstance(currentAccount).getTonesController().load();
        }
        shownAiButton = show;
        aiButton.setVisibility(View.VISIBLE);
        aiButton.animate()
            .alpha(show ? 1.0f : 0.0f)
            .scaleX(show ? 1.0f : 0.6f)
            .scaleY(show ? 1.0f : 0.6f)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .setDuration(420)
            .withEndAction(() -> {
                if (!show) {
                    aiButton.setVisibility(View.GONE);
                }
            })
            .start();
        if (show) {
            aiButton.postDelayed(aiButtonIcon::animate, 220);

            if (aiHint != null) {
                aiHint.hide();
                aiHint = null;
            }

            if (
                MessagesController.getGlobalMainSettings().getInt("aihintshown", 0) < 3
            ) {
                final HintView2 thisHint = aiHint = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
                aiHint.setMultilineText(true);
                aiHint.setText(getString(R.string.AIEditorHint));
                aiHint.setJointPx(1f, -aiButton.getWidth() / 2f + dp(4));
                addView(aiHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.TOP, 0, -200 + 4, 0, 0));
                aiHint.setOnHiddenListener(() -> removeView(thisHint));
                aiHint.setDuration(4000L);
                aiHint.show();
                MessagesController.getGlobalMainSettings().edit().putInt("aihintshown",
                    MessagesController.getGlobalMainSettings().getInt("aihintshown", 0) + 1
                ).apply();
            }
        } else {
            if (aiHint != null) {
                aiHint.hide();
                aiHint = null;
            }
        }
    }
}
