package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * UI/state mirror of paid Mgla plans per Telegram user id.
 * Source of truth is hub → {@link MglaSubscription}; this class caches plan + expiry for the Premium screen.
 */
public final class MglaPremiumController {

    public static final int PLAN_NONE = -1;
    public static final int PLAN_PREMIUM = 0;
    public static final int PLAN_PLUS = 1;
    public static final int PLAN_ULTRA = 2;

    /** Legacy sentinel — no longer issued by hub (only fixed durations). */
    public static final long EXPIRES_LIFETIME = 0L;

    private static final String PREFS = "mgla_premium_subs";
    private static final String KEY_PLAN_PREFIX = "plan_";
    private static final String KEY_EXPIRES_PREFIX = "expires_";
    private static final String KEY_SEEDED = "lifetime_ultra_seeded_v1";

    private MglaPremiumController() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    /**
     * Clears obsolete built-in lifetime Ultra seed from older builds.
     * Hub Timed subscriptions are the only source of paid access.
     */
    public static void ensureSeeded() {
        SharedPreferences p = prefs();
        if (!p.getBoolean(KEY_SEEDED, false) && !p.contains("lifetime_ultra_cleared_v2")) {
            // Nothing to clear on fresh installs.
            p.edit().putBoolean("lifetime_ultra_cleared_v2", true).apply();
            return;
        }
        if (p.getBoolean("lifetime_ultra_cleared_v2", false)) {
            return;
        }
        // Drop hardcoded lifetime grants from previous versions.
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) {
            if (key != null && (key.startsWith(KEY_PLAN_PREFIX) || key.startsWith(KEY_EXPIRES_PREFIX))) {
                long userId = 0L;
                try {
                    if (key.startsWith(KEY_PLAN_PREFIX)) {
                        userId = Long.parseLong(key.substring(KEY_PLAN_PREFIX.length()));
                    } else if (key.startsWith(KEY_EXPIRES_PREFIX)) {
                        userId = Long.parseLong(key.substring(KEY_EXPIRES_PREFIX.length()));
                    }
                } catch (NumberFormatException ignored) {
                }
                if (userId == 1004685048L) {
                    long exp = p.getLong(KEY_EXPIRES_PREFIX + userId, -1L);
                    if (exp == EXPIRES_LIFETIME) {
                        e.remove(KEY_PLAN_PREFIX + userId);
                        e.remove(KEY_EXPIRES_PREFIX + userId);
                    }
                }
            }
        }
        e.remove(KEY_SEEDED);
        e.putBoolean("lifetime_ultra_cleared_v2", true);
        e.apply();
    }

    public static int getPlanForAccount(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return PLAN_NONE;
        }
        UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated()) {
            return PLAN_NONE;
        }
        return getPlanForUser(config.getClientUserId());
    }

    public static int getPlanForUser(long userId) {
        if (userId == 0) {
            return PLAN_NONE;
        }
        ensureSeeded();
        // Prefer live MglaSubscription (checks hub expiry).
        try {
            String planId = MglaSubscription.getPlanIdForUser(userId);
            if (MglaSubscription.PLAN_PREMIUM_ULTRA.equals(planId)) {
                return PLAN_ULTRA;
            }
            if (MglaSubscription.PLAN_PREMIUM_PLUS.equals(planId)) {
                return PLAN_PLUS;
            }
            if (MglaSubscription.PLAN_PREMIUM.equals(planId)) {
                return PLAN_PREMIUM;
            }
            return PLAN_NONE;
        } catch (Throwable ignored) {
        }

        SharedPreferences p = prefs();
        int plan = p.getInt(KEY_PLAN_PREFIX + userId, PLAN_NONE);
        if (plan == PLAN_NONE) {
            return PLAN_NONE;
        }
        long expiresAt = p.getLong(KEY_EXPIRES_PREFIX + userId, EXPIRES_LIFETIME);
        if (expiresAt == EXPIRES_LIFETIME || expiresAt < System.currentTimeMillis()) {
            return PLAN_NONE;
        }
        return plan;
    }

    public static boolean isActive(long userId) {
        return getPlanForUser(userId) != PLAN_NONE;
    }

    public static boolean isActiveForAccount(int account) {
        return getPlanForAccount(account) != PLAN_NONE;
    }

    public static boolean hasAtLeast(long userId, int minPlan) {
        return getPlanForUser(userId) >= minPlan;
    }

    public static boolean hasAtLeastForAccount(int account, int minPlan) {
        return getPlanForAccount(account) >= minPlan;
    }

    /** Lifetime grants are no longer issued. */
    public static boolean isLifetime(long userId) {
        return false;
    }

    public static long getExpiresAt(long userId) {
        if (userId == 0) {
            return -1L;
        }
        try {
            long fromSub = MglaSubscription.getExpiresAtMs(userId);
            if (fromSub > 0L) {
                return fromSub;
            }
        } catch (Throwable ignored) {
        }
        ensureSeeded();
        long stored = prefs().getLong(KEY_EXPIRES_PREFIX + userId, -1L);
        if (stored == EXPIRES_LIFETIME || stored <= System.currentTimeMillis()) {
            return -1L;
        }
        return stored;
    }

    /**
     * Cache grant from hub sync.
     * @param expiresAtMs Unix ms UTC; must be in the future (lifetime no longer used)
     */
    public static void grant(long userId, int plan, long expiresAtMs) {
        if (userId == 0 || plan < PLAN_PREMIUM || plan > PLAN_ULTRA) {
            return;
        }
        if (expiresAtMs <= System.currentTimeMillis()) {
            revoke(userId);
            return;
        }
        prefs().edit()
                .putInt(KEY_PLAN_PREFIX + userId, plan)
                .putLong(KEY_EXPIRES_PREFIX + userId, expiresAtMs)
                .apply();
    }

    public static void revoke(long userId) {
        if (userId == 0) {
            return;
        }
        prefs().edit()
                .remove(KEY_PLAN_PREFIX + userId)
                .remove(KEY_EXPIRES_PREFIX + userId)
                .apply();
    }

    public static String planTitle(int plan) {
        switch (plan) {
            case PLAN_PREMIUM:
                return "Premium";
            case PLAN_PLUS:
                return "Premium+";
            case PLAN_ULTRA:
                return "Premium Ultra";
            default:
                return "";
        }
    }

    public static String formatExpiresLabel(long userId) {
        long expiresAt = getExpiresAt(userId);
        if (expiresAt <= 0L) {
            return "";
        }
        String formatted = MglaSubscription.formatExpiresAt(expiresAt);
        return TextUtils.isEmpty(formatted) ? "" : ("до " + formatted);
    }

    /** Светлый акцент плана (Premium=синий, Plus=красный, Ultra=золотой). */
    public static int colorLight(int plan) {
        switch (plan) {
            case PLAN_PREMIUM:
                return 0xFF7EB6FF;
            case PLAN_PLUS:
                return 0xFFFF8A8A;
            case PLAN_ULTRA:
                return 0xFFF5D76E;
            default:
                return 0x00000000;
        }
    }

    public static int colorMid(int plan) {
        switch (plan) {
            case PLAN_PREMIUM:
                return 0xFF3B8CFF;
            case PLAN_PLUS:
                return 0xFFFF3B3B;
            case PLAN_ULTRA:
                return 0xFFD4AF37;
            default:
                return 0x00000000;
        }
    }

    public static int colorDeep(int plan) {
        switch (plan) {
            case PLAN_PREMIUM:
                return 0xFF1E5EFF;
            case PLAN_PLUS:
                return 0xFFE11D1D;
            case PLAN_ULTRA:
                return 0xFFB8860B;
            default:
                return 0x00000000;
        }
    }

    public static int colorDark(int plan) {
        switch (plan) {
            case PLAN_PREMIUM:
                return 0xFF0A3A9E;
            case PLAN_PLUS:
                return 0xFF9B0F0F;
            case PLAN_ULTRA:
                return 0xFF8A6914;
            default:
                return 0x00000000;
        }
    }

    /** Текст на заливке акцента (белый для синего/красного, тёмный для золота). */
    public static int colorOnAccent(int plan) {
        return plan == PLAN_ULTRA ? 0xFF1A1200 : 0xFFFFFFFF;
    }
}
