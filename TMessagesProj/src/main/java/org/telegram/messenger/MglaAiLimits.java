package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hidden Mgla caps for OpenRouter (not model native limits).
 * Ultra catalog models: per-model input/output/context + 20 requests / day.
 * Lower-plan models (Free / Plus) used by Ultra subscribers are unlimited by this counter.
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
    private static final Set<String> ULTRA_IDS = new HashSet<>();
    private static final String[][] ULTRA_ALIASES = {
            {"deepseek-v4.1-flash", "deepseek/deepseek-v4.1-flash"},
            {"glm-5.3-flash", "z-ai/glm-5.3-flash"},
            {"mimo-v2.6-flash", "xiaomi/mimo-v2.6-flash"},
            {"nex-n2.5-mini", "nex-agi/nex-n2.5-mini"},
            {"qwen3.7-flash", "qwen/qwen3.7-flash"},
            {"gemma-3-4b", "google/gemma-3-4b-it"},
    };

    static {
        putUltra("deepseek/deepseek-v4.1-flash", new ModelBudget(32_000, 4_000, 32_000));
        putUltra("z-ai/glm-5.3-flash", new ModelBudget(32_000, 4_000, 32_000));
        putUltra("xiaomi/mimo-v2.6-flash", new ModelBudget(24_000, 4_000, 24_000));
        putUltra("nex-agi/nex-n2.5-mini", new ModelBudget(48_000, 4_000, 48_000));
        putUltra("qwen/qwen3.7-flash", new ModelBudget(48_000, 4_000, 48_000));
        putUltra("google/gemma-3-4b-it", new ModelBudget(16_000, 2_000, 16_000));
    }

    private static void putUltra(String id, ModelBudget budget) {
        ULTRA_BY_ID.put(id, budget);
        ULTRA_IDS.add(id);
    }

    private static final String PREFS = "mgla_config";
    private static final String KEY_DAY = "ai_ultra_quota_day";
    private static final String KEY_USED = "ai_ultra_quota_used";

    private MglaAiLimits() {
    }

    /** True if this model id belongs to the Ultra catalog (counts toward 20/day). */
    public static boolean isUltraCatalogModel(String modelId) {
        if (TextUtils.isEmpty(modelId)) {
            return false;
        }
        String id = modelId.trim();
        if (ULTRA_IDS.contains(id)) {
            return true;
        }
        String lower = id.toLowerCase(Locale.US);
        for (String[] alias : ULTRA_ALIASES) {
            if (lower.contains(alias[0])) {
                return true;
            }
        }
        return false;
    }

    public static ModelBudget budgetFor(String modelId) {
        if (isUltraCatalogModel(modelId)) {
            ModelBudget ultra = ULTRA_BY_ID.get(modelId.trim());
            if (ultra == null) {
                String lower = modelId.toLowerCase(Locale.US);
                for (String[] alias : ULTRA_ALIASES) {
                    if (lower.contains(alias[0])) {
                        ultra = ULTRA_BY_ID.get(alias[1]);
                        break;
                    }
                }
            }
            return ultra != null ? ultra : new ModelBudget(24_000, 4_000, 24_000);
        }
        return new ModelBudget(DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS, DEFAULT_CONTEXT_TOKENS);
    }

    /** Counter UI: only Ultra subscribers. Quota itself applies only to Ultra catalog models. */
    public static boolean hasDailyQuotaUi() {
        return MglaSubscription.PLAN_PREMIUM_ULTRA.equals(MglaSubscription.getPlanId());
    }

    public static int dailyLimit() {
        return hasDailyQuotaUi() ? ULTRA_DAILY_REQUESTS : Integer.MAX_VALUE;
    }

    public static int usedToday() {
        if (!hasDailyQuotaUi()) {
            return 0;
        }
        rollDayIfNeeded();
        SharedPreferences p = prefs();
        return p == null ? 0 : p.getInt(KEY_USED, 0);
    }

    public static int remainingToday() {
        if (!hasDailyQuotaUi()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, ULTRA_DAILY_REQUESTS - usedToday());
    }

    /** Allow send unless this is an Ultra catalog model and the daily Ultra quota is empty. */
    public static boolean canSendCloudRequest(String modelId) {
        if (!isUltraCatalogModel(modelId)) {
            return true;
        }
        if (!hasDailyQuotaUi()) {
            return true;
        }
        return remainingToday() > 0;
    }

    /** Count only successful Ultra-catalog cloud replies. */
    public static void consumeCloudRequest(String modelId) {
        if (!isUltraCatalogModel(modelId) || !hasDailyQuotaUi()) {
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
        if (!hasDailyQuotaUi()) {
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
