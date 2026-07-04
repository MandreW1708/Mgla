package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;

public class MglaSpyConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_SAVE_DELETED = "spy_save_deleted_messages";
    public static final String KEY_GHOST_MODE = "spy_ghost_mode";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSaveDeletedMessagesEnabled() {
        return ApplicationLoader.applicationContext != null && prefs().getBoolean(KEY_SAVE_DELETED, false);
    }

    public static void setSaveDeletedMessagesEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_SAVE_DELETED, enabled).apply();
        if (!enabled) {
            MglaDeletedMessagesStorage.clearAllDeletedMessagesForAllAccounts();
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
        return ApplicationLoader.applicationContext != null && prefs().getBoolean(KEY_GHOST_MODE, false);
    }

    public static void setGhostModeEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_GHOST_MODE, enabled).apply();
    }
}
