package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.wsbypass.MglaWsBypass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Remote feature flags from mglahub. A feature is blocked if it appears in the
 * cached {@code disabled} set (global kill-switch ∪ personal deny for any local account).
 * <p>
 * Fail-open when no cache yet: everything allowed until the first successful poll.
 * Local toggles that were force-off'd are restored when the hub re-allows the feature.
 */
public final class MglaFeatureFlags {

    public static final String PREFS = "mgla_remote_config";
    private static final String KEY_DISABLED = "disabled_json";
    /** JSON object: feature id → true if local pref was on before remote force-off. */
    private static final String KEY_RESTORE = "force_off_restore_json";
    /** One-shot heal after older builds wiped toggles without a restore map. */
    private static final String KEY_HEALED_DEFAULTS = "healed_force_off_defaults_v1";

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
     * Apply a new disabled set from the hub: update cache/prefs, force-off newly
     * blocked toggles, and restore toggles that the hub re-allowed.
     */
    public static void applyDisabled(Set<String> disabled) {
        Set<String> next = disabled == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(new HashSet<>(disabled));
        Set<String> prev;
        synchronized (lock) {
            prev = cachedDisabled != null ? cachedDisabled : loadFromPrefs();
            cachedDisabled = next;
            persist(next);
        }
        reconcileLocalToggles(prev, next);
        // Always refresh header/UI even if local prefs were already false.
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.mglaHeaderSettingsChanged);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
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

    private static JSONObject loadRestoreMap() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return new JSONObject();
        }
        String raw = prefs.getString(KEY_RESTORE, null);
        if (TextUtils.isEmpty(raw)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static void persistRestoreMap(JSONObject map) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        if (map == null || map.length() == 0) {
            prefs.edit().remove(KEY_RESTORE).apply();
        } else {
            prefs.edit().putString(KEY_RESTORE, map.toString()).apply();
        }
    }

    /**
     * Newly disabled → remember if local toggle was on, then force false.
     * Newly allowed → restore remembered toggle (or AI defaults that were true).
     */
    private static void reconcileLocalToggles(Set<String> prev, Set<String> next) {
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        SharedPreferences config = ApplicationLoader.applicationContext.getSharedPreferences(
            "mgla_config", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = config.edit();
        JSONObject restore = loadRestoreMap();
        boolean configChanged = false;
        boolean restoreChanged = false;
        HashSet<String> toRestart = new HashSet<>();
        HashSet<String> toStop = new HashSet<>();

        for (String feature : BOOLEAN_PREF_FEATURES) {
            boolean wasBlocked = prev.contains(feature);
            boolean nowBlocked = next.contains(feature);
            if (!wasBlocked && nowBlocked) {
                boolean on = config.getBoolean(feature, defaultOn(feature));
                if (on) {
                    try {
                        restore.put(feature, true);
                        restoreChanged = true;
                    } catch (Throwable ignored) {
                    }
                    editor.putBoolean(feature, false);
                    configChanged = true;
                    toStop.add(feature);
                }
            } else if (wasBlocked && !nowBlocked) {
                // Remembered toggle, or AI defaults wiped by older force-off without restore map.
                boolean shouldRestore = restore.optBoolean(feature, false) || defaultOn(feature);
                if (shouldRestore) {
                    editor.putBoolean(feature, true);
                    configChanged = true;
                    toRestart.add(feature);
                }
                if (restore.has(feature)) {
                    restore.remove(feature);
                    restoreChanged = true;
                }
            }
        }

        // Drop restore entries for unknown / obsolete feature ids.
        ArrayList<String> drop = new ArrayList<>();
        Iterator<String> keys = restore.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!contains(BOOLEAN_PREF_FEATURES, key)) {
                drop.add(key);
            }
        }
        for (String key : drop) {
            restore.remove(key);
            restoreChanged = true;
        }

        // Older builds force-off'd prefs without KEY_RESTORE; if cache already empty,
        // wasBlocked is false and nothing restores. Heal AI defaults once.
        SharedPreferences remotePrefs = prefs();
        if (remotePrefs != null && !remotePrefs.getBoolean(KEY_HEALED_DEFAULTS, false)) {
            for (String feature : BOOLEAN_PREF_FEATURES) {
                if (next.contains(feature) || !defaultOn(feature)) {
                    continue;
                }
                if (!config.getBoolean(feature, true)) {
                    editor.putBoolean(feature, true);
                    configChanged = true;
                    toRestart.add(feature);
                }
            }
            remotePrefs.edit().putBoolean(KEY_HEALED_DEFAULTS, true).apply();
        }

        if (configChanged) {
            editor.apply();
        }
        if (restoreChanged) {
            persistRestoreMap(restore);
        }

        AndroidUtilities.runOnUIThread(() -> {
            applyEngineState(toStop, false);
            applyEngineState(toRestart, true);
            if (next.contains(MglaHeaderConfig.KEY_PROXY_IN_HEADER)
                    || next.contains(MglaHeaderConfig.KEY_DOWNLOADS_IN_HEADER)
                    || toRestart.contains(MglaHeaderConfig.KEY_PROXY_IN_HEADER)
                    || toRestart.contains(MglaHeaderConfig.KEY_DOWNLOADS_IN_HEADER)) {
                try {
                    MglaHeaderConfig.notifyChanged();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    /** Features that default to ON in settings UI — restore them when re-allowed. */
    private static boolean defaultOn(String feature) {
        return "ai_enabled".equals(feature)
            || "ai_summary".equals(feature)
            || "ai_retell".equals(feature)
            || "ai_editor".equals(feature);
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static void applyEngineState(Set<String> features, boolean enable) {
        if (features == null || features.isEmpty()) {
            return;
        }
        try {
            if (features.contains("ws_enabled")) {
                MglaWsBypass.getInstance().setEnabled(enable);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (features.contains("dpi_enabled")) {
                MglaDpiBypass.getInstance().setEnabled(enable);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (features.contains("spy_ghost_mode")) {
                MglaSpyConfig.setGhostModeEnabled(enable);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (features.contains("spy_last_online")) {
                MglaSpyConfig.setLastOnlineEnabled(enable);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (features.contains("spy_save_deleted_messages")) {
                MglaSpyConfig.setSaveDeletedMessagesEnabled(enable);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
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
