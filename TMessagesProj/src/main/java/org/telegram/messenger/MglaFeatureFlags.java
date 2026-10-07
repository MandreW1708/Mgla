package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.wsbypass.MglaWsBypass;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Remote feature flags from mglahub. A feature is blocked if it appears in the
 * cached {@code disabled} set (global kill-switch ∪ personal deny for any local account).
 * <p>
 * Fail-open when no cache yet: everything allowed until the first successful poll.
 */
public final class MglaFeatureFlags {

    public static final String PREFS = "mgla_remote_config";
    private static final String KEY_DISABLED = "disabled_json";

    /** Boolean prefs in {@code mgla_config} that map 1:1 to feature ids. */
    private static final String[] BOOLEAN_PREF_FEATURES = {
        "ai_enabled",
        "ai_summary",
        "ai_retell",
        "ai_editor",
        "ai_transcribe_enabled",
        "ws_enabled",
        "dpi_enabled",
        "spy_ghost_mode",
        "spy_last_online",
        "spy_save_deleted_messages",
        "chat_time_seconds",
        "hide_keyboard_on_scroll",
        "comma_after_mention",
        "text_anim_enabled",
        "proxy_in_header",
        "downloads_in_header",
        "haptic_enabled",
        "mgla_popup_notifications_enabled",
        "audio_autopause",
        "edited_icon_enabled",
    };

    private static final Object lock = new Object();
    private static volatile Set<String> cachedDisabled = null;

    private MglaFeatureFlags() {
    }

    public static boolean isAllowed(String featureId) {
        if (TextUtils.isEmpty(featureId)) {
            return true;
        }
        return !getDisabled().contains(featureId);
    }

    public static Set<String> getDisabled() {
        Set<String> local = cachedDisabled;
        if (local != null) {
            return local;
        }
        synchronized (lock) {
            if (cachedDisabled != null) {
                return cachedDisabled;
            }
            cachedDisabled = loadFromPrefs();
            return cachedDisabled;
        }
    }

    /**
     * Apply a new disabled set from the hub: update cache/prefs and force-off local toggles.
     */
    public static void applyDisabled(Set<String> disabled) {
        Set<String> next = disabled == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(new HashSet<>(disabled));
        synchronized (lock) {
            cachedDisabled = next;
            persist(next);
        }
        forceOff(next);
    }

    private static Set<String> loadFromPrefs() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return Collections.emptySet();
        }
        String raw = prefs.getString(KEY_DISABLED, null);
        if (TextUtils.isEmpty(raw)) {
            return Collections.emptySet();
        }
        try {
            JSONArray arr = new JSONArray(raw);
            HashSet<String> out = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, null);
                if (!TextUtils.isEmpty(id)) {
                    out.add(id);
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }

    private static void persist(Set<String> disabled) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        JSONArray arr = new JSONArray();
        for (String id : disabled) {
            arr.put(id);
        }
        prefs.edit().putString(KEY_DISABLED, arr.toString()).apply();
    }

    private static void forceOff(Set<String> disabled) {
        if (disabled.isEmpty()) {
            return;
        }
        SharedPreferences config = ApplicationLoader.applicationContext.getSharedPreferences(
            "mgla_config", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = config.edit();
        boolean changed = false;
        for (String feature : BOOLEAN_PREF_FEATURES) {
            if (!disabled.contains(feature)) {
                continue;
            }
            if (config.getBoolean(feature, false)) {
                editor.putBoolean(feature, false);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }

        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (disabled.contains("ws_enabled")) {
                    MglaWsBypass.getInstance().setEnabled(false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                if (disabled.contains("dpi_enabled")) {
                    MglaDpiBypass.getInstance().setEnabled(false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                if (disabled.contains("spy_ghost_mode")) {
                    MglaSpyConfig.setGhostModeEnabled(false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                if (disabled.contains("spy_last_online")) {
                    MglaSpyConfig.setLastOnlineEnabled(false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                if (disabled.contains("spy_save_deleted_messages")) {
                    MglaSpyConfig.setSaveDeletedMessagesEnabled(false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Parse hub response {@code {"v":1,"disabled":[...]}}. */
    public static Set<String> parseDisabledJson(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("disabled");
        HashSet<String> out = new HashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, null);
                if (!TextUtils.isEmpty(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
