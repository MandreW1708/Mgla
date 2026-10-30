package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.MglaCustomHeaderConfig;
import org.telegram.ui.MglaGlassConfig;
import org.telegram.ui.MglaIconThemes;
import org.telegram.ui.MglaPlayerConfig;
import org.telegram.ui.MglaTextAnimConfig;
import java.util.Iterator;

/**
 * Build / apply a cloud settings blob for {@link MglaSettingsSync}.
 * Secrets, tokens, subscription state and remote kill-switches are never included.
 */
public final class MglaSettingsSnapshot {

    public static final int VERSION = 1;

    private static final String PREFS = "mgla_config";
    private static final String META_UPDATED_AT = "settings_sync_updated_at";
    private static final String META_LAST_SYNC_AT = "settings_sync_last_at";
    private static final String META_PENDING_JSON = "settings_sync_pending_json";
    private static final String META_PENDING_AT = "settings_sync_pending_at";

    /** Cooldown between manual cloud uploads. */
    public static final long SYNC_COOLDOWN_MS = 5L * 60L * 1000L;

    private static final String[] BOOL_KEYS = {
            // Appearance / glass
            MglaGlassConfig.PREF_MD3_SWITCHES,
            MglaGlassConfig.PREF_EDITED_ICON,
            MglaGlassConfig.PREF_CLEAN_HEADER,
            MglaGlassConfig.PREF_CLEAN_HEADER_HIDE_BACK_BTN,
            MglaGlassConfig.PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK,
            MglaGlassConfig.PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK,
            MglaGlassConfig.PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL,
            MglaGlassConfig.PREF_DIALOGS_AVATAR_HERO,
            MglaGlassConfig.PREF_DIALOGS_SEARCH_EXPAND,
            MglaGlassConfig.PREF_DIALOGS_FAB_MORPH,
            MglaGlassConfig.PREF_REVERSE_CHAT_OPEN,
            MglaGlassConfig.PREF_CHAT_SEND_BUTTON_MORPH,
            MglaGlassConfig.PREF_CHAT_REACTION_SPRING,
            MglaGlassConfig.PREF_PROFILE_MEDIA_GROW,
            MglaGlassConfig.PREF_SETTINGS_PRESS_SPRING,
            MglaGlassConfig.PREF_FOLDER_ACTIVE_OUTLINE,
            MglaGlassConfig.PREF_FOLDER_NAME_IN_HEADER,
            MglaGlassConfig.PREF_CUSTOM_NOTIFICATION_ICON,
            // Custom header
            "custom_header_show_back", "custom_header_show_avatar", "custom_header_show_title",
            "custom_header_show_subtitle", "custom_header_show_menu", "custom_header_show_community",
            "custom_header_show_timer", "custom_header_glass_back", "custom_header_glass_title",
            "custom_header_glass_menu", "custom_header_glass_pinned", "custom_header_glass_translation",
            "custom_header_show_pinned", "custom_header_show_translation",
            // Chats
            MglaChatsConfig.PREF_HIDE_STICKER_TIME,
            MglaChatsConfig.PREF_HIDE_KEYBOARD_ON_SCROLL,
            MglaChatsConfig.PREF_COMMA_AFTER_MENTION,
            MglaChatsConfig.PREF_CHAT_TIME_SECONDS,
            MglaChatsConfig.PREF_AUTO_TRANSLATE_ENABLED,
            // AI toggles (no API keys)
            "ai_enabled", "ai_summary", "ai_retell", "ai_editor",
            "ai_transcribe_enabled", "ai_transcribe_fallback_telegram",
            // Memory (no quotas / watched lists — device-local)
            "memory_enabled",
            // Tabs / side menu
            "main_tabs_customized", "main_tabs_search_button", "main_tabs_ai_opt_out",
            MglaSideMenuConfig.KEY_ENABLED, MglaSideMenuConfig.KEY_GLASS_ENABLED,
            MglaSideMenuConfig.KEY_COLORED_ICONS,
            // General
            MglaHeaderConfig.KEY_PROXY_IN_HEADER, MglaHeaderConfig.KEY_DOWNLOADS_IN_HEADER,
            MglaAudioConfig.KEY_AUTO_PAUSE,
            MglaSpyConfig.KEY_GHOST_MODE, MglaSpyConfig.KEY_LAST_ONLINE,
            MglaSpyConfig.KEY_SAVE_DELETED, MglaSpyConfig.KEY_HANG_IN_BACKGROUND,
            MglaSpyConfig.KEY_SAVE_PRIVATE, MglaSpyConfig.KEY_SAVE_GROUPS_SMALL,
            MglaSpyConfig.KEY_SAVE_CHANNELS, MglaSpyConfig.KEY_SAVE_GROUPS_LARGE,
            // Icon themes
            "auto_apply_icon_theme",
            // Bypass (no tokens/secrets)
            "ws_enabled", "ws_ultra_bypass",
            "dpi_enabled",
            // Player
            MglaPlayerConfig.PREF_LARGE_COVER, MglaPlayerConfig.PREF_DYNAMIC_COLORS,
            MglaPlayerConfig.PREF_ANIM_OPEN, MglaPlayerConfig.PREF_ANIM_TRACK_CHANGE,
            MglaPlayerConfig.PREF_ANIM_COVER_PULSE, MglaPlayerConfig.PREF_ANIM_BUTTONS,
            MglaPlayerConfig.PREF_ANIM_AMBIENT, MglaPlayerConfig.PREF_ANIM_SEEKBAR,
            MglaPlayerConfig.PREF_ANIM_SEEKBAR_WAVE,
            // Text anim
            "text_anim_enabled", "text_anim_cascade",
    };

    private static final String[] INT_KEYS = {
            MglaGlassConfig.PREF_GLASS_DARKENING_LEVEL,
            MglaGlassConfig.PREF_CHAT_HEADER_STYLE,
            MglaGlassConfig.PREF_CHAT_BOTTOM_PANEL_STYLE,
            "custom_header_preset", "custom_header_avatar_size", "custom_header_title_size",
            "custom_header_title_align", "custom_header_avatar_side", "custom_header_height",
            "custom_header_radius", "custom_header_gap", "custom_header_pad",
            MglaChatsConfig.PREF_RECENT_STICKERS_LIMIT,
            MglaChatsConfig.PREF_DOUBLE_TAP_OUT, MglaChatsConfig.PREF_DOUBLE_TAP_IN,
            MglaChatsConfig.PREF_BOTTOM_BUTTON_MODE,
            "memory_interval_min",
            MglaSideMenuConfig.KEY_BACKGROUND_OPACITY,
            MglaTransferConfig.KEY_DOWNLOAD_MODE, MglaTransferConfig.KEY_UPLOAD_MODE,
            MglaPlayerConfig.PREF_ANIM_SPEED,
            "text_anim_effect", "text_anim_duration", "text_anim_strength",
            "text_anim_easing", "text_anim_color", "text_anim_cascade_delay",
            "dpi_port",
    };

    private static final String[] STRING_KEYS = {
            "main_tabs_order",
            MglaChatsConfig.PREF_AUTO_TRANSLATE_PROVIDER,
            MglaChatsConfig.PREF_AUTO_TRANSLATE_TARGET_LANG,
            "ai_provider",
            "ai_transcribe_model",
            "memory_provider",
            "ws_bypass_region",
            "dpi_strategy_cmd", "dpi_fake_sni",
    };

    private MglaSettingsSnapshot() {
    }

    public static long getLocalUpdatedAt() {
        return prefs().getLong(META_UPDATED_AT, 0L);
    }

    public static void touchLocalUpdatedAt() {
        prefs().edit().putLong(META_UPDATED_AT, System.currentTimeMillis()).apply();
    }

    public static long getLastSyncAt() {
        return prefs().getLong(META_LAST_SYNC_AT, 0L);
    }

    public static void markSyncedNow() {
        prefs().edit().putLong(META_LAST_SYNC_AT, System.currentTimeMillis()).apply();
    }

    /** Milliseconds left before the next sync is allowed; {@code 0} if ready. */
    public static long getSyncCooldownRemainingMs() {
        long last = getLastSyncAt();
        if (last <= 0L) {
            return 0L;
        }
        long left = SYNC_COOLDOWN_MS - (System.currentTimeMillis() - last);
        return Math.max(0L, left);
    }

    public static boolean hasPendingApply() {
        return !TextUtils.isEmpty(prefs().getString(META_PENDING_JSON, null));
    }

    public static long getPendingUpdatedAt() {
        return prefs().getLong(META_PENDING_AT, 0L);
    }

    public static void savePending(JSONObject cloudResponse) {
        if (cloudResponse == null) {
            return;
        }
        JSONObject settings = cloudResponse.optJSONObject("settings");
        if (settings == null) {
            settings = new JSONObject();
        }
        long at = cloudResponse.optLong("updated_at", System.currentTimeMillis());
        prefs().edit()
                .putString(META_PENDING_JSON, cloudResponse.toString())
                .putLong(META_PENDING_AT, at)
                .apply();
    }

    public static JSONObject loadPending() {
        String raw = prefs().getString(META_PENDING_JSON, null);
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static void clearPending() {
        prefs().edit().remove(META_PENDING_JSON).remove(META_PENDING_AT).apply();
    }

    /** Upload payload: local settings with a fresh timestamp (LWW push). */
    public static JSONObject build(long tgId) throws Exception {
        SharedPreferences p = prefs();
        JSONObject settings = new JSONObject();
        for (String key : BOOL_KEYS) {
            if (p.contains(key)) {
                settings.put(key, p.getBoolean(key, false));
            }
        }
        for (String key : INT_KEYS) {
            if (p.contains(key)) {
                settings.put(key, p.getInt(key, 0));
            }
        }
        for (String key : STRING_KEYS) {
            if (p.contains(key)) {
                String v = p.getString(key, null);
                if (v != null) {
                    settings.put(key, v);
                }
            }
        }
        // Per-account predictive back for the syncing (paid) account only.
        if (tgId > 0) {
            String pbKey = MglaGlassConfig.PREF_PREDICTIVE_BACK_STYLE + "_" + tgId;
            if (p.contains(pbKey)) {
                settings.put("predictive_back_style", p.getInt(pbKey, 0));
            }
        }

        long updatedAt = Math.max(getLocalUpdatedAt(), System.currentTimeMillis());
        JSONObject body = new JSONObject();
        body.put("tg_id", tgId);
        body.put("v", VERSION);
        body.put("updated_at", updatedAt);
        body.put("settings", settings);
        return body;
    }

    /**
     * Pull-only request: does not overwrite the cloud document (updated_at = 0).
     * Server returns the stored settings for this tg_id.
     */
    public static JSONObject buildPullRequest(long tgId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("tg_id", tgId);
        body.put("v", VERSION);
        body.put("updated_at", 0);
        body.put("settings", new JSONObject());
        return body;
    }

    /**
     * Apply cloud settings into local prefs. Premium feature gates stay enforced by
     * {@link MglaSubscription} on use — free accounts in the same install cannot unlock AI/etc.
     *
     * @return number of keys written
     */
    public static int apply(JSONObject response, long tgId) throws Exception {
        if (response == null) {
            return 0;
        }
        JSONObject settings = response.optJSONObject("settings");
        if (settings == null) {
            return 0;
        }
        SharedPreferences.Editor ed = prefs().edit();
        int written = 0;
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (TextUtils.isEmpty(key) || !isAllowedKey(key)) {
                continue;
            }
            Object raw = settings.opt(key);
            if (raw == null || JSONObject.NULL.equals(raw)) {
                continue;
            }
            if ("predictive_back_style".equals(key) && tgId > 0) {
                ed.putInt(MglaGlassConfig.PREF_PREDICTIVE_BACK_STYLE + "_" + tgId,
                        settings.optInt(key, 0));
                written++;
                continue;
            }
            if (raw instanceof Boolean) {
                ed.putBoolean(key, (Boolean) raw);
                written++;
            } else if (raw instanceof Integer) {
                ed.putInt(key, (Integer) raw);
                written++;
            } else if (raw instanceof Long) {
                ed.putInt(key, ((Long) raw).intValue());
                written++;
            } else if (raw instanceof Double) {
                ed.putInt(key, ((Double) raw).intValue());
                written++;
            } else if (raw instanceof String) {
                ed.putString(key, (String) raw);
                written++;
            }
        }
        long cloudUpdated = response.optLong("updated_at", 0L);
        if (cloudUpdated > 0) {
            ed.putLong(META_UPDATED_AT, cloudUpdated);
        }
        ed.apply();

        // Refresh in-memory caches and enforce premium eligibility.
        MglaGlassConfig.reloadCacheForSync();
        MglaCustomHeaderConfig.reloadCacheForSync();
        MglaTextAnimConfig.reloadCacheForSync();
        MglaSubscription.enforceUltraBypassEligibility();
        MglaChatsConfig.clearAutoTranslateIfUnauthorized();
        MglaMemoryConfig.clearIfUnauthorized();
        MglaIconThemes.clearIfUnauthorized();

        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaHeaderSettingsChanged);
            } catch (Throwable ignore) {
            }
            try {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaMainTabsChanged);
            } catch (Throwable ignore) {
            }
            try {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ghostModeChanged);
            } catch (Throwable ignore) {
            }
            try {
                MglaGlassConfig.notifyGlassAppearanceChanged();
            } catch (Throwable ignore) {
            }
            try {
                MglaHeaderConfig.notifyChanged();
            } catch (Throwable ignore) {
            }
        });
        return written;
    }

    private static boolean isAllowedKey(String key) {
        if ("predictive_back_style".equals(key)) {
            return true;
        }
        for (String k : BOOL_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        for (String k : INT_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        for (String k : STRING_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
