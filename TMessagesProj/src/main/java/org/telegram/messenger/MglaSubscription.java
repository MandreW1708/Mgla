package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Mgla subscription entitlements for the AI tab and OpenRouter.
 * Plan ids match hub {@code free|premium|premium_plus|premium_ultra}.
 * <p>
 * Entitlements are per Telegram user id (account), not per install/device.
 * Hub {@code plans} map in the features response is the source of truth.
 */
public final class MglaSubscription {

    public static final String PLAN_FREE = "free";
    public static final String PLAN_PREMIUM = "premium";
    public static final String PLAN_PREMIUM_PLUS = "premium_plus";
    public static final String PLAN_PREMIUM_ULTRA = "premium_ultra";

    private static final String PREFS = "mgla_config";
    /** Legacy single-key plan (pre per-account). Migrated on first read. */
    private static final String KEY_PLAN_LEGACY = "mgla_subscription_plan";
    private static final String KEY_PLAN_PREFIX = "mgla_subscription_plan_";

    private MglaSubscription() {
    }

    /** Plan for the currently selected account. */
    public static String getPlanId() {
        return getPlanIdForAccount(UserConfig.selectedAccount);
    }

    public static String getPlanIdForAccount(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return PLAN_FREE;
        }
        UserConfig uc = UserConfig.getInstance(account);
        if (uc == null || !uc.isClientActivated()) {
            return PLAN_FREE;
        }
        return getPlanIdForUser(uc.getClientUserId());
    }

    public static String getPlanIdForUser(long userId) {
        if (userId <= 0) {
            return PLAN_FREE;
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return PLAN_FREE;
        }
        String key = KEY_PLAN_PREFIX + userId;
        if (p.contains(key)) {
            return normalize(p.getString(key, PLAN_FREE));
        }
        // One-shot migrate legacy device-wide plan onto this user if present.
        if (p.contains(KEY_PLAN_LEGACY)) {
            String legacy = normalize(p.getString(KEY_PLAN_LEGACY, PLAN_FREE));
            p.edit().putString(key, legacy).remove(KEY_PLAN_LEGACY).apply();
            return legacy;
        }
        return PLAN_FREE;
    }

    public static void setPlanId(String planId) {
        int account = UserConfig.selectedAccount;
        UserConfig uc = UserConfig.getInstance(account);
        if (uc == null || !uc.isClientActivated()) {
            return;
        }
        setPlanIdForUser(uc.getClientUserId(), planId);
    }

    public static void setPlanIdForUser(long userId, String planId) {
        if (userId <= 0) {
            return;
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        String next = normalize(planId);
        String key = KEY_PLAN_PREFIX + userId;
        String prev = normalize(p.getString(key, PLAN_FREE));
        if (next.equals(prev) && p.contains(key)) {
            syncPremiumController(userId, next);
            return;
        }
        final boolean unlockedAi = PLAN_FREE.equals(prev) && !PLAN_FREE.equals(next);
        p.edit().putString(key, next).remove(KEY_PLAN_LEGACY).apply();
        syncPremiumController(userId, next);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (unlockedAi) {
                    MglaMainTabsConfig.ensureAiTabIncluded();
                }
                enforceUltraBypassEligibility();
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.mglaMainTabsChanged);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Ultra-обход is install-wide: turn it off and reconnect to the standard
     * relay when no account still has Ultra.
     */
    public static void enforceUltraBypassEligibility() {
        try {
            if (hasUltraOnAnyAccount()) {
                return;
            }
            if (!org.telegram.utils.wsbypass.MglaWsConfig.isUltraBypassPrefEnabled()) {
                return;
            }
            org.telegram.utils.wsbypass.MglaWsConfig.setUltraBypassEnabled(false);
            org.telegram.utils.wsbypass.MglaWsBypass.getInstance().onRelayHostChanged();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Apply hub features payload. Prefer {@code plans} (per tg_id). Fallback to
     * legacy single {@code plan} for the selected account only.
     */
    public static void applyFromFeaturesJson(String body) {
        if (TextUtils.isEmpty(body)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject plans = root.optJSONObject("plans");
            if (plans != null) {
                Iterator<String> keys = plans.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (TextUtils.isEmpty(key)) {
                        continue;
                    }
                    long userId;
                    try {
                        userId = Long.parseLong(key.trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (userId <= 0) {
                        continue;
                    }
                    setPlanIdForUser(userId, plans.optString(key, PLAN_FREE));
                }
                return;
            }
            if (root.has("plan")) {
                setPlanId(root.optString("plan", PLAN_FREE));
            }
        } catch (Throwable ignore) {
        }
    }

    public static String normalize(String planId) {
        if (PLAN_PREMIUM.equals(planId)
                || PLAN_PREMIUM_PLUS.equals(planId)
                || PLAN_PREMIUM_ULTRA.equals(planId)) {
            return planId;
        }
        return PLAN_FREE;
    }

    private static int planRank(String planId) {
        switch (normalize(planId)) {
            case PLAN_PREMIUM_ULTRA:
                return 3;
            case PLAN_PREMIUM_PLUS:
                return 2;
            case PLAN_PREMIUM:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * True if any activated account on this install has Ultra.
     * Used for device-wide auto-bypass priority (unlike AI, which is per-account).
     */
    public static boolean hasUltraOnAnyAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (PLAN_PREMIUM_ULTRA.equals(getPlanIdForAccount(a))) {
                return true;
            }
        }
        return false;
    }

    /** Same as {@link #hasUltraOnAnyAccount()} — bypass QoS is install-wide. */
    public static boolean hasBypassPriority() {
        return hasUltraOnAnyAccount();
    }

    /**
     * Highest plan among activated accounts — sent to the WS relay as
     * {@code X-Mgla-Plan} for soft priority scheduling.
     */
    public static String getDeviceBypassPlan() {
        String best = PLAN_FREE;
        int bestRank = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            String plan = getPlanIdForAccount(a);
            int rank = planRank(plan);
            if (rank > bestRank) {
                best = plan;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Free: AI tab / section is closed. */
    public static boolean canAccessAiSection() {
        return !PLAN_FREE.equals(getPlanId());
    }

    /** Premium / Plus / Ultra — local GGUF. */
    public static boolean canUseLocalModels() {
        String plan = getPlanId();
        return PLAN_PREMIUM.equals(plan)
                || PLAN_PREMIUM_PLUS.equals(plan)
                || PLAN_PREMIUM_ULTRA.equals(plan);
    }

    /** Plus / Ultra — OpenRouter cloud models. */
    public static boolean canUseOpenRouter() {
        String plan = getPlanId();
        return PLAN_PREMIUM_PLUS.equals(plan) || PLAN_PREMIUM_ULTRA.equals(plan);
    }

    /** Ultra only — attach images to OpenRouter. */
    public static boolean canAttachMedia() {
        return PLAN_PREMIUM_ULTRA.equals(getPlanId());
    }

    /**
     * Which OpenRouter model list / API key bucket to use.
     * Empty if the plan cannot use OpenRouter.
     */
    public static String openRouterPlanId() {
        String plan = getPlanId();
        if (PLAN_PREMIUM_ULTRA.equals(plan)) {
            return PLAN_PREMIUM_ULTRA;
        }
        if (PLAN_PREMIUM_PLUS.equals(plan)) {
            return PLAN_PREMIUM_PLUS;
        }
        return "";
    }

    public static String labelForPlan(String planId) {
        switch (normalize(planId)) {
            case PLAN_PREMIUM:
                return "Premium";
            case PLAN_PREMIUM_PLUS:
                return "Premium+";
            case PLAN_PREMIUM_ULTRA:
                return "Premium Ultra";
            default:
                return "Free";
        }
    }

    /** Keep MglaPremiumController UI state in sync with hub entitlements. */
    private static void syncPremiumController(long userId, String planId) {
        try {
            String plan = normalize(planId);
            if (PLAN_FREE.equals(plan)) {
                MglaPremiumController.revoke(userId);
                return;
            }
            int mapped = MglaPremiumController.PLAN_PREMIUM;
            if (PLAN_PREMIUM_PLUS.equals(plan)) {
                mapped = MglaPremiumController.PLAN_PLUS;
            } else if (PLAN_PREMIUM_ULTRA.equals(plan)) {
                mapped = MglaPremiumController.PLAN_ULTRA;
            }
            MglaPremiumController.grant(userId, mapped, MglaPremiumController.EXPIRES_LIFETIME);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
