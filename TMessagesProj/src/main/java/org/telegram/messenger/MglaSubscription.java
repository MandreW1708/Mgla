package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Mgla subscription entitlements for the AI tab, translate, Ultra bypass, OpenRouter.
 * Plan ids match hub {@code free|premium|premium_plus|premium_ultra}.
 * <p>
 * Source of truth: hub {@code plans} + {@code expires} (Unix ms UTC) from features poll.
 * Local paid grants without a future expiry are rejected.
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
    private static final String KEY_EXPIRES_PREFIX = "mgla_subscription_expires_";
    /** Last expiry timestamp for which the «ending soon» chats hint was dismissed. */
    private static final String KEY_EXPIRY_WARN_DISMISSED_PREFIX = "mgla_sub_expiry_warn_dismissed_";
    private static final long EXPIRY_WARN_WINDOW_MS = 24L * 60L * 60L * 1000L;

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
        String plan;
        if (p.contains(key)) {
            plan = normalize(p.getString(key, PLAN_FREE));
        } else if (p.contains(KEY_PLAN_LEGACY)) {
            plan = normalize(p.getString(KEY_PLAN_LEGACY, PLAN_FREE));
            p.edit().putString(key, plan).remove(KEY_PLAN_LEGACY).apply();
        } else {
            return PLAN_FREE;
        }
        if (PLAN_FREE.equals(plan)) {
            return PLAN_FREE;
        }
        long expiresAt = p.getLong(KEY_EXPIRES_PREFIX + userId, 0L);
        if (expiresAt <= 0L || expiresAt <= System.currentTimeMillis()) {
            clearLocalGrant(userId);
            return PLAN_FREE;
        }
        return plan;
    }

    /** Unix ms UTC when the paid plan ends; 0 if free / unknown. */
    public static long getExpiresAtMs(long userId) {
        if (userId <= 0 || PLAN_FREE.equals(getPlanIdForUser(userId))) {
            return 0L;
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return 0L;
        }
        long expiresAt = p.getLong(KEY_EXPIRES_PREFIX + userId, 0L);
        return expiresAt > System.currentTimeMillis() ? expiresAt : 0L;
    }

    public static long getExpiresAtMs() {
        UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
        if (uc == null || !uc.isClientActivated()) {
            return 0L;
        }
        return getExpiresAtMs(uc.getClientUserId());
    }

    /**
     * Show the DialogsActivity hint when a paid plan ends within 24 hours
     * and the user hasn't dismissed it for this expiry timestamp.
     */
    public static boolean shouldShowExpiryWarning() {
        UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
        if (uc == null || !uc.isClientActivated()) {
            return false;
        }
        long userId = uc.getClientUserId();
        String plan = getPlanIdForUser(userId);
        if (PLAN_FREE.equals(plan)) {
            return false;
        }
        long expiresAt = getExpiresAtMs(userId);
        if (expiresAt <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (expiresAt <= now || expiresAt - now > EXPIRY_WARN_WINDOW_MS) {
            return false;
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return true;
        }
        long dismissedFor = p.getLong(KEY_EXPIRY_WARN_DISMISSED_PREFIX + userId, 0L);
        return dismissedFor != expiresAt;
    }

    public static void dismissExpiryWarning() {
        UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
        if (uc == null || !uc.isClientActivated()) {
            return;
        }
        long userId = uc.getClientUserId();
        long expiresAt = getExpiresAtMs(userId);
        if (expiresAt <= 0L) {
            return;
        }
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putLong(KEY_EXPIRY_WARN_DISMISSED_PREFIX + userId, expiresAt).apply();
        }
        notifySubscriptionHintChanged();
    }

    public static void notifySubscriptionHintChanged() {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaSubscriptionHintChanged));
    }

    /** Human-readable plan label for the current account, e.g. «Premium+». */
    public static String currentPlanLabel() {
        return labelForPlan(getPlanId());
    }

    /** Local date-time string for UI (device timezone), or empty. */
    public static String formatExpiresAt(long expiresAtMs) {
        if (expiresAtMs <= 0L) {
            return "";
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
            return fmt.format(new Date(expiresAtMs));
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** UTC string for diagnostics. */
    public static String formatExpiresAtUtc(long expiresAtMs) {
        if (expiresAtMs <= 0L) {
            return "";
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt.format(new Date(expiresAtMs)) + " UTC";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Clears local entitlements to Free. Paid grants must come from hub sync
     * via {@link #applyFromFeaturesJson(String)}.
     */
    public static void setPlanId(String planId) {
        int account = UserConfig.selectedAccount;
        UserConfig uc = UserConfig.getInstance(account);
        if (uc == null || !uc.isClientActivated()) {
            return;
        }
        if (!PLAN_FREE.equals(normalize(planId))) {
            // Local paid activation is disabled — only hub can grant.
            return;
        }
        setPlanIdForUser(uc.getClientUserId(), PLAN_FREE, 0L);
    }

    public static void setPlanIdForUser(long userId, String planId, long expiresAtMs) {
        if (userId <= 0) {
            return;
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        String next = normalize(planId);
        long expires = expiresAtMs;
        if (!PLAN_FREE.equals(next)) {
            if (expires <= 0L || expires <= System.currentTimeMillis()) {
                next = PLAN_FREE;
                expires = 0L;
            }
        } else {
            expires = 0L;
        }
        String key = KEY_PLAN_PREFIX + userId;
        String expKey = KEY_EXPIRES_PREFIX + userId;
        String prev = normalize(p.getString(key, PLAN_FREE));
        long prevExp = p.getLong(expKey, 0L);
        if (next.equals(prev) && prevExp == expires && p.contains(key)) {
            syncPremiumController(userId, next, expires);
            return;
        }
        final boolean unlockedAi = PLAN_FREE.equals(prev) && !PLAN_FREE.equals(next);
        final boolean lostPaid = !PLAN_FREE.equals(prev) && PLAN_FREE.equals(next);
        SharedPreferences.Editor ed = p.edit().putString(key, next).remove(KEY_PLAN_LEGACY);
        if (expires > 0L) {
            ed.putLong(expKey, expires);
        } else {
            ed.remove(expKey);
        }
        ed.apply();
        syncPremiumController(userId, next, expires);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (unlockedAi) {
                    MglaMainTabsConfig.ensureAiTabIncluded();
                }
                enforceUltraBypassEligibility();
                if (lostPaid) {
                    MglaChatsConfig.clearAutoTranslateIfUnauthorized();
                    MglaMemoryConfig.clearIfUnauthorized();
                    org.telegram.ui.MglaGlassConfig.clearPremiumBackStyleForUser(userId);
                    org.telegram.ui.MglaIconThemes.clearIfUnauthorized();
                }
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.mglaMainTabsChanged);
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.mglaSubscriptionHintChanged);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void clearLocalGrant(long userId) {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        String key = KEY_PLAN_PREFIX + userId;
        String prev = normalize(p.getString(key, PLAN_FREE));
        if (PLAN_FREE.equals(prev) && !p.contains(KEY_EXPIRES_PREFIX + userId)) {
            return;
        }
        p.edit().putString(key, PLAN_FREE).remove(KEY_EXPIRES_PREFIX + userId).apply();
        syncPremiumController(userId, PLAN_FREE, 0L);
        final long revokedUserId = userId;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                enforceUltraBypassEligibility();
                MglaChatsConfig.clearAutoTranslateIfUnauthorized();
                MglaMemoryConfig.clearIfUnauthorized();
                org.telegram.ui.MglaGlassConfig.clearPremiumBackStyleForUser(revokedUserId);
                org.telegram.ui.MglaIconThemes.clearIfUnauthorized();
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.mglaMainTabsChanged);
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.mglaSubscriptionHintChanged);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Ultra-обход is install-wide. Keep the user's {@code ws_ultra_bypass} pref;
     * only switch the relay host when effective Ultra path changes (entitlement
     * flicker must not wipe the toggle — otherwise Ultra never auto-returns).
     */
    private static volatile Boolean lastUltraPathEffective;

    public static void enforceUltraBypassEligibility() {
        try {
            boolean effective = org.telegram.utils.wsbypass.MglaWsConfig.isUltraBypassPrefEnabled()
                    && hasUltraOnAnyAccount();
            Boolean prev = lastUltraPathEffective;
            if (prev != null && prev == effective) {
                return;
            }
            lastUltraPathEffective = effective;
            if (prev == null && effective) {
                // Cold start already on Ultra path — no reconnect needed.
                return;
            }
            org.telegram.utils.wsbypass.MglaWsBypass.getInstance().onRelayHostChanged();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Apply hub features payload. Prefer {@code plans} + {@code expires} (per tg_id).
     */
    public static void applyFromFeaturesJson(String body) {
        if (TextUtils.isEmpty(body)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject plans = root.optJSONObject("plans");
            JSONObject expires = root.optJSONObject("expires");
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
                    String plan = normalize(plans.optString(key, PLAN_FREE));
                    long expMs = 0L;
                    if (expires != null && expires.has(key)) {
                        expMs = expires.optLong(key, 0L);
                    }
                    setPlanIdForUser(userId, plan, expMs);
                }
                return;
            }
            if (root.has("plan")) {
                String plan = normalize(root.optString("plan", PLAN_FREE));
                long expMs = 0L;
                if (expires != null) {
                    // Legacy single-account: use first expires value if present.
                    Iterator<String> it = expires.keys();
                    if (it.hasNext()) {
                        expMs = expires.optLong(it.next(), 0L);
                    }
                }
                UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
                if (uc != null && uc.isClientActivated()) {
                    setPlanIdForUser(uc.getClientUserId(), plan, expMs);
                }
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

    /** Задачи / «Напомнить…» — any paid Mgla plan on the current account. */
    public static boolean canUseTasks() {
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

    /** Media attachments in AI chat are disabled for all plans. */
    public static boolean canAttachMedia() {
        return false;
    }

    /** Auto-translate of entire chats — any paid Mgla plan (not Free). */
    public static boolean canUseAutoTranslate() {
        return !PLAN_FREE.equals(getPlanId());
    }

    /** Живая память — any paid Mgla plan on the currently selected account. */
    public static boolean canUseLiveMemory() {
        return canUseLiveMemoryForAccount(UserConfig.selectedAccount);
    }

    /** Живая память for a specific account (extractor / per-dialog gates). */
    public static boolean canUseLiveMemoryForAccount(int account) {
        return !PLAN_FREE.equals(getPlanIdForAccount(account));
    }

    /** Premium back animations (Поверхность and later) — any paid plan. */
    public static boolean canUsePremiumAnimations() {
        return !PLAN_FREE.equals(getPlanId());
    }

    /** True if any activated account has a paid plan (install-wide features). */
    public static boolean hasPaidOnAnyAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!PLAN_FREE.equals(getPlanIdForAccount(a))) {
                return true;
            }
        }
        return false;
    }

    /**
     * «Применить тему под иконку» — any paid plan on any account
     * (theme is install-wide, same as Ultra bypass entitlement scope).
     */
    public static boolean canUseIconThemes() {
        return hasPaidOnAnyAccount();
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
    private static void syncPremiumController(long userId, String planId, long expiresAtMs) {
        try {
            String plan = normalize(planId);
            if (PLAN_FREE.equals(plan) || expiresAtMs <= 0L) {
                MglaPremiumController.revoke(userId);
                return;
            }
            int mapped = MglaPremiumController.PLAN_PREMIUM;
            if (PLAN_PREMIUM_PLUS.equals(plan)) {
                mapped = MglaPremiumController.PLAN_PLUS;
            } else if (PLAN_PREMIUM_ULTRA.equals(plan)) {
                mapped = MglaPremiumController.PLAN_ULTRA;
            }
            MglaPremiumController.grant(userId, mapped, expiresAtMs);
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
