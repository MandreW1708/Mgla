package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaTypingAnimator;

import java.util.ArrayList;
import java.util.Random;

public class MglaTextAnimationActivity extends BaseFragment {

    private static final String[] DEMO_PHRASES = {
        "Привет! Как дела? 👋",
        "Mgla печатает красиво ✨",
        "Каждая буква появляется плавно",
        "Попробуйте другой эффект ниже"
    };

    private final ArrayList<EffectCardView> effectCards = new ArrayList<>();
    private final ArrayList<TextView> easingChips = new ArrayList<>();
    private final ArrayList<ColorDotView> colorDots = new ArrayList<>();
    private final Random random = new Random();

    private EditTextBoldCursor previewField;
    private LinearLayout settingsContainer;
    private View cascadeDelayContainer;
    private int demoPhrase;
    private int demoPos;
    private boolean demoRunning;
    private final Runnable demoStep = this::runDemoStep;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Анимация текста");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + dp(16));
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        rootLayout.addView(createPreviewBlock(context), blockParams(8));

        LinearLayout switchBlock = MglaUi.createBlock(context, null);
        TextCheckCell enabledCell = new TextCheckCell(context);
        enabledCell.setBackground(null);
        enabledCell.setTextAndValueAndCheck("Анимация текста", "Плавное появление символов в поле ввода", MglaTextAnimConfig.isEnabled(), true, false);
        enabledCell.setOnClickListener(v -> {
            boolean value = !MglaTextAnimConfig.isEnabled();
            MglaTextAnimConfig.setEnabled(value);
            enabledCell.setChecked(value);
            updateEnabledState(true);
            restartDemo();
        });
        switchBlock.addView(enabledCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(switchBlock, blockParams(16));

        settingsContainer = new LinearLayout(context);
        settingsContainer.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(settingsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        settingsContainer.addView(createEffectsBlock(context), blockParams(16));
        settingsContainer.addView(createParamsBlock(context), blockParams(16));
        settingsContainer.addView(createColorBlock(context), blockParams(16));
        settingsContainer.addView(createCascadeBlock(context), blockParams(16));

        LinearLayout resetBlock = MglaUi.createBlock(context, null);
        TextSettingsCell resetCell = new TextSettingsCell(context);
        resetCell.setBackground(null);
        resetCell.setText("Сбросить настройки", false);
        resetCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        resetCell.setCanDisable(false);
        resetCell.setOnClickListener(v -> {
            MglaTextAnimConfig.reset();
            presentFragment(new MglaTextAnimationActivity(), true, true);
        });
        resetBlock.addView(resetCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(resetBlock, blockParams(16));

        updateEnabledState(false);
        fragmentView = scrollView;
        return fragmentView;
    }

    private LinearLayout.LayoutParams blockParams(int top) {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, top, 16, 0);
    }

    // region Preview

    private View createPreviewBlock(Context context) {
        LinearLayout block = MglaUi.createBlock(context, "Предпросмотр");

        FrameLayout inputFrame = new FrameLayout(context);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(22));
        inputBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        inputFrame.setBackground(inputBg);

        previewField = new EditTextBoldCursor(context);
        previewField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        previewField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        previewField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        previewField.setHint("Напишите что-нибудь…");
        previewField.setCursorColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        previewField.setCursorSize(dp(20));
        previewField.setCursorWidth(1.5f);
        previewField.setBackground(null);
        previewField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        previewField.setMaxLines(4);
        previewField.setPadding(dp(18), dp(12), dp(18), dp(12));
        previewField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                stopDemo();
                previewField.setText("");
            }
        });
        MglaTypingAnimator.attach(previewField);
        inputFrame.addView(previewField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        block.addView(inputFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setText("Нажмите на поле и напечатайте сами");
        row.addView(hint, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView replay = createPill(context, "Повторить", true);
        replay.setOnClickListener(v -> {
            previewField.clearFocus();
            AndroidUtilities.hideKeyboard(previewField);
            demoPhrase = 0;
            restartDemo();
        });
        row.addView(replay, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));

        block.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 10, 16, 14));
        return block;
    }

    private void restartDemo() {
        if (previewField == null || previewField.hasFocus()) {
            return;
        }
        stopDemo();
        previewField.setText("");
        demoPos = 0;
        demoRunning = true;
        AndroidUtilities.runOnUIThread(demoStep, 400);
    }

    private void stopDemo() {
        demoRunning = false;
        AndroidUtilities.cancelRunOnUIThread(demoStep);
    }

    private void runDemoStep() {
        if (!demoRunning || previewField == null) {
            return;
        }
        String phrase = DEMO_PHRASES[demoPhrase % DEMO_PHRASES.length];
        if (demoPos >= phrase.length()) {
            demoPhrase++;
            demoPos = 0;
            previewField.setText("");
            AndroidUtilities.runOnUIThread(demoStep, 350);
            return;
        }
        int cp = phrase.codePointAt(demoPos);
        int len = Character.charCount(cp);
        Editable text = previewField.getText();
        text.append(phrase, demoPos, demoPos + len);
        demoPos += len;
        long delay = demoPos >= phrase.length() ? 1700 : 55 + random.nextInt(85);
        if (cp == ' ') {
            delay += 40;
        }
        AndroidUtilities.runOnUIThread(demoStep, delay);
    }

    // endregion

    // region Effects

    private View createEffectsBlock(Context context) {
        LinearLayout block = MglaUi.createBlock(context, "Эффект");
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        int columns = 3;
        LinearLayout row = null;
        for (int i = 0; i < MglaTextAnimConfig.EFFECT_NAMES.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 84));
            }
            EffectCardView card = new EffectCardView(context, i);
            final int effect = i;
            card.setOnClickListener(v -> {
                MglaTextAnimConfig.setEffect(effect);
                for (EffectCardView c : effectCards) {
                    c.invalidate();
                }
                restartDemo();
            });
            effectCards.add(card);
            row.addView(card, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        int rest = MglaTextAnimConfig.EFFECT_NAMES.length % columns;
        if (rest != 0 && row != null) {
            row.addView(new View(context), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, columns - rest));
        }
        block.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 12));
        return block;
    }

    private class EffectCardView extends View {
        private static final String SAMPLE = "Mgla";
        private static final int CHAR_DELAY = 110;
        private static final int HOLD = 1100;
        private static final int FADE_OUT = 280;

        private final int effect;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final long startTime = SystemClock.uptimeMillis();

        EffectCardView(Context context, int effect) {
            super(context);
            this.effect = effect;
            textPaint.setTextSize(dp(21));
            textPaint.setTypeface(AndroidUtilities.bold());
            namePaint.setTextSize(dp(12));
            namePaint.setTextAlign(Paint.Align.CENTER);
            setContentDescription(MglaTextAnimConfig.EFFECT_NAMES[effect]);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean selected = MglaTextAnimConfig.getEffect() == effect;
            int accent = MglaTextAnimConfig.getAccentColor();
            int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

            rect.set(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4));
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(selected ? withAlpha(accent, 0x22) : Theme.getColor(Theme.key_windowBackgroundGray));
            canvas.drawRoundRect(rect, dp(14), dp(14), bgPaint);
            if (selected) {
                bgPaint.setStyle(Paint.Style.STROKE);
                bgPaint.setStrokeWidth(dp(2));
                bgPaint.setColor(accent);
                rect.inset(dp(1), dp(1));
                canvas.drawRoundRect(rect, dp(13), dp(13), bgPaint);
            }

            int duration = MglaTextAnimConfig.getDuration();
            float strength = MglaTextAnimConfig.getStrength() / 100f;
            int easing = MglaTextAnimConfig.getEasing();
            int n = SAMPLE.length();
            long cycle = (long) CHAR_DELAY * (n - 1) + duration + HOLD + FADE_OUT;
            long t = (SystemClock.uptimeMillis() - startTime) % cycle;
            float fade = t > cycle - FADE_OUT ? 1f - (t - (cycle - FADE_OUT)) / (float) FADE_OUT : 1f;

            float total = textPaint.measureText(SAMPLE);
            float x = (getWidth() - total) / 2f;
            float y = getHeight() * 0.5f;
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            for (int i = 0; i < n; i++) {
                String ch = SAMPLE.substring(i, i + 1);
                float w = textPaint.measureText(ch);
                float p = (t - (long) i * CHAR_DELAY) / (float) duration;
                if (p > 0f) {
                    textPaint.setColor(textColor);
                    textPaint.clearShadowLayer();
                    textPaint.setTextSkewX(0f);
                    textPaint.baselineShift = 0;
                    textPaint.bgColor = 0;
                    if (p < 1f) {
                        MglaTypingAnimator.applyEffect(textPaint, effect, p, strength, easing, accent, i * 7919 + 3);
                    }
                    textPaint.setAlpha((int) (textPaint.getAlpha() * fade));
                    if (textPaint.bgColor != 0) {
                        markerPaint.setColor(textPaint.bgColor);
                        markerPaint.setAlpha((int) (markerPaint.getAlpha() * fade));
                        canvas.drawRect(x, y + fm.ascent * 0.8f, x + w, y + fm.descent, markerPaint);
                    }
                    canvas.drawText(ch, x, y + textPaint.baselineShift, textPaint);
                }
                x += w;
            }

            namePaint.setColor(selected ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            namePaint.setTypeface(selected ? AndroidUtilities.bold() : null);
            canvas.drawText(MglaTextAnimConfig.EFFECT_NAMES[effect], getWidth() / 2f, getHeight() - dp(13), namePaint);

            if (isAttachedToWindow() && getVisibility() == VISIBLE) {
                postInvalidateOnAnimation();
            }
        }
    }

    // endregion

    // region Params

    private View createParamsBlock(Context context) {
        LinearLayout block = MglaUi.createBlock(context, "Параметры");

        addSlider(context, block, "Длительность", MglaTextAnimConfig.getDuration(), 150, 1200,
            v -> v + " мс", MglaTextAnimConfig::setDuration);
        block.addView(MglaUi.createDivider(context));
        addSlider(context, block, "Сила эффекта", MglaTextAnimConfig.getStrength(), 20, 200,
            v -> v + "%", MglaTextAnimConfig::setStrength);
        block.addView(MglaUi.createDivider(context));

        block.addView(createLabel(context, "Плавность"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(18), 0, dp(18), 0);
        for (int i = 0; i < MglaTextAnimConfig.EASING_NAMES.length; i++) {
            final int easing = i;
            TextView chip = createPill(context, MglaTextAnimConfig.EASING_NAMES[i], false);
            chip.setOnClickListener(v -> {
                MglaTextAnimConfig.setEasing(easing);
                updateChips();
                restartDemo();
            });
            easingChips.add(chip);
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
        }
        scroll.addView(chips);
        block.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 16));
        updateChips();
        return block;
    }

    private void addSlider(Context context, LinearLayout block, String title, int value, int min, int max,
                           org.telegram.messenger.Utilities.CallbackReturn<Integer, CharSequence> format,
                           org.telegram.messenger.Utilities.Callback<Integer> onChange) {
        block.addView(createLabel(context, title), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        SlideIntChooseView slider = new SlideIntChooseView(context, null);
        slider.set(Math.max(min, Math.min(max, value)), SlideIntChooseView.Options.make(0, min, max, format), onChange);
        block.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private TextView createLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setText(text);
        label.setPadding(dp(22), dp(12), dp(22), 0);
        return label;
    }

    private TextView createPill(Context context, String text, boolean accent) {
        TextView pill = new TextView(context);
        pill.setText(text);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(16), 0, dp(16), 0);
        if (accent) {
            pill.setTypeface(AndroidUtilities.bold());
            int color = Theme.getColor(Theme.key_featuredStickers_addButton);
            pill.setTextColor(color);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(withAlpha(color, 0x1F));
            pill.setBackground(bg);
        }
        return pill;
    }

    private void updateChips() {
        int selected = MglaTextAnimConfig.getEasing();
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        for (int i = 0; i < easingChips.size(); i++) {
            TextView chip = easingChips.get(i);
            boolean on = i == selected;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(17));
            bg.setColor(on ? accent : Theme.getColor(Theme.key_windowBackgroundGray));
            chip.setBackground(bg);
            chip.setTextColor(on ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            chip.setTypeface(on ? AndroidUtilities.bold() : null);
        }
    }

    // endregion

    // region Color

    private View createColorBlock(Context context) {
        LinearLayout block = MglaUi.createBlock(context, "Цвет эффекта");

        TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        note.setText("Используется в эффектах «Свечение», «Вспышка», «Маркер» и «Микс». Первый цвет — акцент темы.");
        block.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 4));

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setPadding(dp(14), 0, dp(14), 0);
        for (int i = 0; i < MglaTextAnimConfig.COLORS.length; i++) {
            final int index = i;
            ColorDotView dot = new ColorDotView(context, i);
            dot.setOnClickListener(v -> {
                MglaTextAnimConfig.setColorIndex(index);
                for (ColorDotView d : colorDots) {
                    d.invalidate();
                }
                for (EffectCardView c : effectCards) {
                    c.invalidate();
                }
                restartDemo();
            });
            colorDots.add(dot);
            dots.addView(dot, LayoutHelper.createLinear(46, 46));
        }
        scroll.addView(dots);
        block.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 12));
        return block;
    }

    private static class ColorDotView extends View {
        private final int index;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ColorDotView(Context context, int index) {
            super(context);
            this.index = index;
            setContentDescription(index == 0 ? "Акцент темы" : "Цвет " + index);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int color = MglaTextAnimConfig.resolveColor(index);
            boolean selected = MglaTextAnimConfig.getColorIndex() == index;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, selected ? dp(12) : dp(15), paint);
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                canvas.drawCircle(cx, cy, dp(17), paint);
            }
            if (index == 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                paint.setTextSize(dp(11));
                paint.setTypeface(AndroidUtilities.bold());
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("A", cx, cy + dp(4), paint);
            }
        }
    }

    // endregion

    // region Cascade

    private View createCascadeBlock(Context context) {
        LinearLayout block = MglaUi.createBlock(context, "Вставка текста");

        TextCheckCell cascadeCell = new TextCheckCell(context);
        cascadeCell.setBackground(null);
        cascadeCell.setTextAndValueAndCheck("Каскад", "Вставленный текст появляется по буквам, волной", MglaTextAnimConfig.isCascadeEnabled(), true, false);
        block.addView(cascadeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout delayContainer = new LinearLayout(context);
        delayContainer.setOrientation(LinearLayout.VERTICAL);
        delayContainer.addView(MglaUi.createDivider(context));
        addSlider(context, delayContainer, "Задержка между буквами", MglaTextAnimConfig.getCascadeDelay(), 5, 80,
            v -> v + " мс", MglaTextAnimConfig::setCascadeDelay);

        TextView paste = createPill(context, "Показать вставку", true);
        paste.setOnClickListener(v -> {
            if (previewField == null) {
                return;
            }
            stopDemo();
            previewField.clearFocus();
            AndroidUtilities.hideKeyboard(previewField);
            previewField.setText("");
            previewField.getText().append("Вставленный текст появляется красивой волной 🌊");
        });
        delayContainer.addView(paste, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.START, 22, 4, 0, 14));
        block.addView(delayContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        cascadeDelayContainer = delayContainer;
        delayContainer.setVisibility(MglaTextAnimConfig.isCascadeEnabled() ? View.VISIBLE : View.GONE);

        cascadeCell.setOnClickListener(v -> {
            boolean value = !MglaTextAnimConfig.isCascadeEnabled();
            MglaTextAnimConfig.setCascadeEnabled(value);
            cascadeCell.setChecked(value);
            cascadeDelayContainer.setVisibility(value ? View.VISIBLE : View.GONE);
        });
        return block;
    }

    // endregion

    private void updateEnabledState(boolean animated) {
        if (settingsContainer == null) {
            return;
        }
        boolean enabled = MglaTextAnimConfig.isEnabled();
        float alpha = enabled ? 1f : 0.45f;
        if (animated) {
            settingsContainer.animate().alpha(alpha).setDuration(200).start();
        } else {
            settingsContainer.setAlpha(alpha);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public void onResume() {
        super.onResume();
        restartDemo();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopDemo();
    }

    @Override
    public void onFragmentDestroy() {
        stopDemo();
        super.onFragmentDestroy();
    }
}
