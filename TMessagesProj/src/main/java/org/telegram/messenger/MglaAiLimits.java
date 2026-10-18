package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hidden Mgla caps for OpenRouter (not model native limits).
 * Ultra: per-model input/output/context + 20 cloud requests / day.
 */
public final class MglaAiLimits {

    public static final int ULTRA_DAILY_REQUESTS = 20;
    /** Rough chars-per-token for silent clipping (mixed RU/EN). */
    public static final int CHARS_PER_TOKEN = 3;

    /** Default Free / Premium+ cloud caps. */
    public static final int DEFAULT_INPUT_TOKENS = 2_000;
    public static final int DEFAULT_OUTPUT_TOKENS = 768;
    public static final int DEFAULT_CONTEXT_TOKENS = 2_000;

    public static final class ModelBudget {
        public final int inputTokens;
        public final int outputTokens;
        public final int contextTokens;

        public ModelBudget(int inputTokens, int outputTokens, int contextTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.contextTokens = contextTokens;
        }

        public int inputChars() {
            return inputTokens * CHARS_PER_TOKEN;
        }

        public int contextChars() {
            return contextTokens * CHARS_PER_TOKEN;
        }
    }

    private static final Map<String, ModelBudget> ULTRA_BY_ID = new HashMap<>();
    private static final String[][] ULTRA_ALIASES = {
            {"deepseek-v4.1-flash", "deepseek/deepseek-v4.1-flash"},
            {"glm-5.3-flash", "z-ai/glm-5.3-flash"},
            {"mimo-v2.6-flash", "xiaomi/mimo-v2.6-flash"},
            {"nex-n2.5-mini", "nex-agi/nex-n2.5-mini"},
            {"qwen3.7-flash", "qwen/qwen3.7-flash"},
            {"gemma-3-4b", "google/gemma-3-4b-it"},
    };

    static {
        ULTRA_BY_ID.put("deepseek/deepseek-v4.1-flash", new ModelBudget(32_000, 4_000, 32_000));
        ULTRA_BY_ID.put("z-ai/glm-5.3-flash", new ModelBudget(32_000, 4_000, 32_000));
        ULTRA_BY_ID.put("xiaomi/mimo-v2.6-flash", new ModelBudget(24_000, 4_000, 24_000));
        ULTRA_BY_ID.put("nex-agi/nex-n2.5-mini", new ModelBudget(48_000, 4_000, 48_000));
        ULTRA_BY_ID.put("qwen/qwen3.7-flash", new ModelBudget(48_000, 4_000, 48_000));
        ULTRA_BY_ID.put("google/gemma-3-4b-it", new ModelBudget(16_000, 2_000, 16_000));
    }

    private static final String PREFS = "mgla_config";
    private static final String KEY_DAY = "ai_ultra_quota_day";
    private static final String KEY_USED = "ai_ultra_quota_used";

    private MglaAiLimits() {
    }

    public static ModelBudget budgetFor(String modelId) {
        if (MglaSubscription.PLAN_PREMIUM_ULTRA.equals(MglaSubscription.getPlanId())) {
            ModelBudget ultra = resolveUltra(modelId);
            if (ultra != null) {
                return ultra;
            }
            // Unknown ultra model — conservative mid tier.
            return new ModelBudget(24_000, 4_000, 24_000);
        }
        return new ModelBudget(DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS, DEFAULT_CONTEXT_TOKENS);
    }

    private static ModelBudget resolveUltra(String modelId) {
        if (TextUtils.isEmpty(modelId)) {
            // Auto: allow the largest Ultra budget; hub picks the model.
            return ULTRA_BY_ID.get("nex-agi/nex-n2.5-mini");
        }
        String id = modelId.trim();
        ModelBudget exact = ULTRA_BY_ID.get(id);
        if (exact != null) {
            return exact;
        }
        String lower = id.toLowerCase(Locale.US);
        for (String[] alias : ULTRA_ALIASES) {
            if (lower.contains(alias[0])) {
                return ULTRA_BY_ID.get(alias[1]);
            }
        }
        return null;
    }

    /** Daily cloud (OpenRouter) quota applies only to Premium Ultra. */
    public static boolean hasDailyQuota() {
        return MglaSubscription.PLAN_PREMIUM_ULTRA.equals(MglaSubscription.getPlanId());
    }

    public static int dailyLimit() {
        return hasDailyQuota() ? ULTRA_DAILY_REQUESTS : Integer.MAX_VALUE;
    }

    public static int usedToday() {
        if (!hasDailyQuota()) {
            return 0;
        }
        rollDayIfNeeded();
        SharedPreferences p = prefs();
        return p == null ? 0 : p.getInt(KEY_USED, 0);
    }

    public static int remainingToday() {
        if (!hasDailyQuota()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, ULTRA_DAILY_REQUESTS - usedToday());
    }

    public static boolean canSendCloudRequest() {
        return !hasDailyQuota() || remainingToday() > 0;
    }

    /** Call after a successful OpenRouter send starts (counts toward the day). */
    public static void consumeCloudRequest() {
        if (!hasDailyQuota()) {
            return;
        }
        rollDayIfNeeded();
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        int used = p.getInt(KEY_USED, 0);
        p.edit().putInt(KEY_USED, used + 1).apply();
    }

    public static String counterLabel() {
        if (!hasDailyQuota()) {
            return "";
        }
        return usedToday() + "/" + ULTRA_DAILY_REQUESTS;
    }

    private static void rollDayIfNeeded() {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        int today = todayKey();
        if (p.getInt(KEY_DAY, 0) != today) {
            p.edit().putInt(KEY_DAY, today).putInt(KEY_USED, 0).apply();
        }
    }

    private static int todayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
