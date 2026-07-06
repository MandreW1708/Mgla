package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;

public class MglaSpyConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_SAVE_DELETED = "spy_save_deleted_messages";
    public static final String KEY_GHOST_MODE = "spy_ghost_mode";
    private static final String KEY_DELETED_NOTIFY_PREFIX = "del_notify_";

    private static String deletedNotifyKey(int account, long dialogId, long topicId) {
        return KEY_DELETED_NOTIFY_PREFIX + account + "_" + dialogId + "_" + topicId;
    }

    public static int getDeletedNotifyWatermark(int account, long dialogId, long topicId) {
        SharedPreferences prefs = prefs();
        return prefs != null ? prefs.getInt(deletedNotifyKey(account, dialogId, topicId), 0) : 0;
    }

    public static void setDeletedNotifyWatermark(int account, long dialogId, long topicId, int deletedDate) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().putInt(deletedNotifyKey(account, dialogId, topicId), deletedDate).apply();
    }

    public static void clearDeletedNotifyWatermarks() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_DELETED_NOTIFY_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSaveDeletedMessagesEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_SAVE_DELETED, false);
    }

    public static void setSaveDeletedMessagesEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().putBoolean(KEY_SAVE_DELETED, enabled).apply();
        if (!enabled) {
            MglaDeletedMessagesStorage.clearAllDeletedMessagesForAllAccounts();
            clearDeletedNotifyWatermarks();
        }
        updatePushBackgroundSettings();
    }

    public static void updatePushBackgroundSettings() {
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.startPushService();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    ConnectionsManager cm = ConnectionsManager.getInstance(a);
                    cm.setPushConnectionEnabled(cm.isPushConnectionEnabled());
                }
            }
        });
    }

    public static boolean isGhostModeEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_GHOST_MODE, false);
    }

    public static void setGhostModeEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().putBoolean(KEY_GHOST_MODE, enabled).apply();
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    MessagesController.getInstance(a).onGhostModeChanged(enabled);
                }
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ghostModeChanged);
        });
    }
}
