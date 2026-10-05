package org.telegram.ui.Components;

import android.graphics.Color;
import android.os.SystemClock;
import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.MglaTextAnimConfig;

import java.util.ArrayList;
import java.util.Random;

/**
 * Animates characters as they are typed into an EditText (Mgla «Анимация текста»).
 * <p>
 * Each new character gets a {@link TypingSpan} that only changes draw state (alpha, color,
 * baseline shift, shadow, skew, background), never metrics, so line breaking and text shaping
 * stay intact. Whole-text replacements via {@code setText} (drafts, editing, clearing) are
 * not animated: TextView swaps the Editable instance for those.
 */
public class MglaTypingAnimator implements TextWatcher {

    private static final int MAX_ANIMATED_CHARS = 300;
    private static final int MAX_CASCADE_TOTAL_MS = 1200;
    private static final int[] MIX_EFFECTS = {
        MglaTextAnimConfig.EFFECT_RISE, MglaTextAnimConfig.EFFECT_DROP, MglaTextAnimConfig.EFFECT_SPRING,
        MglaTextAnimConfig.EFFECT_WAVE, MglaTextAnimConfig.EFFECT_GLOW, MglaTextAnimConfig.EFFECT_FLASH,
        MglaTextAnimConfig.EFFECT_MARKER, MglaTextAnimConfig.EFFECT_SKEW
    };

    private final TextView view;
    private final Invalidator invalidator = new Invalidator();
    private final ArrayList<SavedSpan> saved = new ArrayList<>();
    private final Random random = new Random();
    private final Runnable frame = this::onFrame;

    private CharSequence beforeText;
    private String removed;
    private int changeStart;
    private int changeCount;
    private boolean pending;
    private boolean running;

    private MglaTypingAnimator(TextView view) {
        this.view = view;
    }

    public static MglaTypingAnimator attach(TextView view) {
        MglaTypingAnimator animator = new MglaTypingAnimator(view);
        view.addTextChangedListener(animator);
        return animator;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        pending = false;
        saved.clear();
        if (!MglaTextAnimConfig.isEnabled() || count > MAX_ANIMATED_CHARS * 4) {
            return;
        }
        beforeText = s;
        changeStart = start;
        removed = count > 0 ? s.subSequence(start, start + count).toString() : "";
        // IMEs replace the whole composing word on every key; remember which characters of it
        // are still animating so the replacement doesn't cut their animation short.
        if (count > 0 && s instanceof Spanned) {
            Spanned sp = (Spanned) s;
            for (TypingSpan span : sp.getSpans(start, start + count, TypingSpan.class)) {
                int st = sp.getSpanStart(span);
                int en = sp.getSpanEnd(span);
                if (st >= start && en <= start + count) {
                    saved.add(new SavedSpan(span, st - start, en - st));
                }
            }
        }
        pending = true;
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        changeCount = count;
    }

    @Override
    public void afterTextChanged(Editable e) {
        if (!pending) {
            return;
        }
        pending = false;
        CharSequence was = beforeText;
        beforeText = null;
        if (e != was) {
            saved.clear();
            return;
        }
        int insEnd = changeStart + changeCount;
        if (insEnd > e.length()) {
            saved.clear();
            return;
        }
        String inserted = changeCount > 0 ? e.subSequence(changeStart, insEnd).toString() : "";
        int rl = removed.length();
        int il = inserted.length();
        int max = Math.min(rl, il);
        int prefix = 0;
        while (prefix < max && removed.charAt(prefix) == inserted.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix && removed.charAt(rl - 1 - suffix) == inserted.charAt(il - 1 - suffix)) {
            suffix++;
        }

        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (SavedSpan s : saved) {
            int pos;
            if (s.offset + s.length <= prefix) {
                pos = changeStart + s.offset;
            } else if (s.offset >= rl - suffix) {
                pos = changeStart + s.offset - rl + il;
            } else {
                continue;
            }
            if (s.span.isFinished(now) || e.getSpanStart(s.span) >= 0 || pos < 0 || pos + s.length > e.length()) {
                continue;
            }
            e.setSpan(s.span, pos, pos + s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            any = true;
        }
        saved.clear();

        int from = changeStart + prefix;
        int to = insEnd - suffix;
        int n = to - from;
        if (n > 0 && n <= MAX_ANIMATED_CHARS) {
            int effect = MglaTextAnimConfig.getEffect();
            int duration = MglaTextAnimConfig.getDuration();
            float strength = MglaTextAnimConfig.getStrength() / 100f;
            int easing = MglaTextAnimConfig.getEasing();
            int accent = MglaTextAnimConfig.getAccentColor();
            int step = n > 1 && MglaTextAnimConfig.isCascadeEnabled()
                ? Math.min(MglaTextAnimConfig.getCascadeDelay(), MAX_CASCADE_TOTAL_MS / n) : 0;
            int index = 0;
            for (int i = from; i < to; ) {
                int cp = Character.codePointAt(e, i);
                int len = Character.charCount(cp);
                if (!Character.isWhitespace(cp) && i + len <= to) {
                    e.setSpan(new TypingSpan(now + (long) index * step, duration, effect, strength, easing, accent, random.nextInt()),
                        i, i + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    index++;
                    any = true;
                }
                i += len;
            }
        }
        if (any) {
            start();
        }
    }

    private void start() {
        if (!running) {
            running = true;
            view.postOnAnimation(frame);
        }
    }

    private void onFrame() {
        CharSequence text = view.getText();
        if (!(text instanceof Spannable)) {
            running = false;
            return;
        }
        Spannable sp = (Spannable) text;
        boolean attached = view.isAttachedToWindow();
        long now = SystemClock.uptimeMillis();
        int min = Integer.MAX_VALUE;
        int max = -1;
        for (TypingSpan span : sp.getSpans(0, sp.length(), TypingSpan.class)) {
            if (!attached || span.isFinished(now)) {
                sp.removeSpan(span);
                continue;
            }
            min = Math.min(min, sp.getSpanStart(span));
            max = Math.max(max, sp.getSpanEnd(span));
        }
        if (max > min && min >= 0) {
            // Re-setting a CharacterStyle makes TextView re-record the cached text blocks of
            // that range; a plain invalidate() would redraw stale display lists.
            sp.removeSpan(invalidator);
            sp.setSpan(invalidator, min, max, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.invalidate();
            view.postOnAnimation(frame);
        } else {
            sp.removeSpan(invalidator);
            running = false;
        }
    }

    /**
     * Applies {@code effect} at raw progress {@code t} (0..1) to a paint already set up with the
     * final text color. Shared by the input field and the settings previews.
     */
    public static void applyEffect(TextPaint tp, int effect, float t, float strength, int easing, int accent, int seed) {
        if (effect == MglaTextAnimConfig.EFFECT_MIX) {
            effect = MIX_EFFECTS[((seed % MIX_EFFECTS.length) + MIX_EFFECTS.length) % MIX_EFFECTS.length];
        }
        float e = ease(easing, t);
        float ec = clamp01(e);
        float appear = clamp01(ease(MglaTextAnimConfig.EASING_SMOOTH, Math.min(1f, t * 1.8f)));
        float offset = AndroidUtilities.dp(9) * strength;
        int baseColor = tp.getColor();
        int baseAlpha = Color.alpha(baseColor);
        float alpha = 1f;
        float shift = 0f;
        switch (effect) {
            case MglaTextAnimConfig.EFFECT_FADE:
                alpha = ec;
                break;
            case MglaTextAnimConfig.EFFECT_RISE:
                alpha = appear;
                shift = offset * (1f - e);
                break;
            case MglaTextAnimConfig.EFFECT_DROP:
                alpha = appear;
                shift = -offset * (1f - e);
                break;
            case MglaTextAnimConfig.EFFECT_SPRING:
                alpha = appear;
                shift = offset * 1.3f * (1f - spring(t));
                break;
            case MglaTextAnimConfig.EFFECT_WAVE:
                alpha = appear;
                shift = -offset * (float) Math.sin(t * Math.PI * 3) * (1f - t);
                break;
            case MglaTextAnimConfig.EFFECT_GLOW: {
                alpha = ec;
                tp.setColor(blend(accent, baseColor, Math.min(1f, ec * 1.4f)));
                float radius = AndroidUtilities.dp(10) * strength * (1f - ec);
                if (radius > 0.5f) {
                    tp.setShadowLayer(radius, 0, 0, withAlpha(accent, (int) (230 * (1f - ec))));
                }
                break;
            }
            case MglaTextAnimConfig.EFFECT_FLASH:
                alpha = appear;
                tp.setColor(blend(accent, baseColor, ec));
                break;
            case MglaTextAnimConfig.EFFECT_MARKER:
                alpha = appear;
                tp.bgColor = withAlpha(accent, (int) (255 * 0.4f * Math.min(1f, strength) * (1f - ec)));
                break;
            case MglaTextAnimConfig.EFFECT_DISSOLVE: {
                alpha = ec * ec;
                float radius = AndroidUtilities.dp(7) * strength * (1f - ec);
                if (radius > 0.5f) {
                    // A non-opaque shadow color keeps its own alpha instead of the paint's.
                    tp.setShadowLayer(radius, 0, 0, withAlpha(baseColor, (int) (200 * (1f - ec))));
                }
                break;
            }
            case MglaTextAnimConfig.EFFECT_SKEW:
                alpha = ec;
                tp.setTextSkewX(tp.getTextSkewX() - 0.7f * Math.min(strength, 2f) * (1f - e));
                shift = offset * 0.35f * (1f - e);
                break;
        }
        tp.setAlpha((int) (baseAlpha * clamp01(alpha)));
        tp.baselineShift += (int) shift;
    }

    public static float ease(int easing, float t) {
        t = clamp01(t);
        switch (easing) {
            case MglaTextAnimConfig.EASING_SPRING: {
                float tension = 2.2f;
                float x = t - 1f;
                return x * x * ((tension + 1f) * x + tension) + 1f;
            }
            case MglaTextAnimConfig.EASING_SOFT:
                return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            case MglaTextAnimConfig.EASING_ELASTIC:
                return 1f - (float) (Math.pow(2, -9 * t) * Math.cos(t * Math.PI * 3.2));
            case MglaTextAnimConfig.EASING_LINEAR:
                return t;
            case MglaTextAnimConfig.EASING_SMOOTH:
            default: {
                float x = 1f - t;
                return 1f - x * x * x;
            }
        }
    }

    private static float spring(float t) {
        return 1f - (float) (Math.exp(-6 * t) * Math.cos(t * Math.PI * 3.2));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(254, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private static int blend(int from, int to, float p) {
        float q = 1f - p;
        return Color.argb(Color.alpha(to),
            (int) (Color.red(from) * q + Color.red(to) * p),
            (int) (Color.green(from) * q + Color.green(to) * p),
            (int) (Color.blue(from) * q + Color.blue(to) * p));
    }

    public static final class TypingSpan extends CharacterStyle implements UpdateAppearance, NoCopySpan {
        private final long startTime;
        private final int duration;
        private final int effect;
        private final float strength;
        private final int easing;
        private final int accent;
        private final int seed;

        TypingSpan(long startTime, int duration, int effect, float strength, int easing, int accent, int seed) {
            this.startTime = startTime;
            this.duration = duration;
            this.effect = effect;
            this.strength = strength;
            this.easing = easing;
            this.accent = accent;
            this.seed = seed;
        }

        boolean isFinished(long now) {
            return now >= startTime + duration;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            long now = SystemClock.uptimeMillis();
            if (now < startTime) {
                tp.setAlpha(0);
                return;
            }
            float t = (now - startTime) / (float) duration;
            if (t < 1f) {
                applyEffect(tp, effect, t, strength, easing, accent, seed);
            }
        }
    }

    private static final class Invalidator extends CharacterStyle implements NoCopySpan {
        @Override
        public void updateDrawState(TextPaint tp) {
        }
    }

    private static final class SavedSpan {
        final TypingSpan span;
        final int offset;
        final int length;

        SavedSpan(TypingSpan span, int offset, int length) {
            this.span = span;
            this.offset = offset;
            this.length = length;
        }
    }
}
