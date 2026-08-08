package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;

public class MglaSpyConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_SAVE_DELETED = "spy_save_deleted_messages";
    public static final String KEY_GHOST_MODE = "spy_ghost_mode";
    private static final String KEY_DELETED_NOTIFY_PREFIX = "del_notify_";

    // Categories for saving deleted messages
    public static final String KEY_SAVE_PRIVATE = "spy_save_deleted_private";
    public static final String KEY_SAVE_GROUPS_SMALL = "spy_save_deleted_groups";
    public static final String KEY_SAVE_CHANNELS = "spy_save_deleted_channels";
    public static final String KEY_SAVE_GROUPS_LARGE = "spy_save_deleted_comments";

    // Chat type constants
    public static final int CHAT_TYPE_PRIVATE = 0;
    public static final int CHAT_TYPE_GROUP_SMALL = 1;
    public static final int CHAT_TYPE_CHANNEL = 2;
    public static final int CHAT_TYPE_GROUP_LARGE = 3;

    public static final String[] CHAT_TYPE_NAMES = {
        "Личные чаты", "Группы до 100 участников", "Каналы", "Группы более 100 участников"
    };

    // Message type keys for saving deleted messages
    public static final String KEY_SAVE_MSG_VOICE = "spy_save_deleted_msg_voice";
    public static final String KEY_SAVE_MSG_ROUND = "spy_save_deleted_msg_round";
    public static final String KEY_SAVE_MSG_TEXT = "spy_save_deleted_msg_text";
    public static final String KEY_SAVE_MSG_PHOTO = "spy_save_deleted_msg_photo";
    public static final String KEY_SAVE_MSG_VIDEO = "spy_save_deleted_msg_video";

    // Message type constants
    public static final int MSG_TYPE_VOICE = 0;
    public static final int MSG_TYPE_ROUND = 1;
    public static final int MSG_TYPE_TEXT = 2;
    public static final int MSG_TYPE_PHOTO = 3;
    public static final int MSG_TYPE_VIDEO = 4;

    public static final String[] MSG_TYPE_NAMES = {
        "Голосовые", "Видеосообщения", "Текстовые", "Фото", "Видео"
    };

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

    public static boolean isSaveDeletedForPrivateEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_SAVE_PRIVATE, true);
    }

    public static void setSaveDeletedForPrivateEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_SAVE_PRIVATE, enabled).apply();
        }
    }

    public static boolean isSaveDeletedForGroupsSmallEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_SAVE_GROUPS_SMALL, true);
    }

    public static void setSaveDeletedForGroupsSmallEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_SAVE_GROUPS_SMALL, enabled).apply();
        }
    }

    public static boolean isSaveDeletedForChannelsEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_SAVE_CHANNELS, true);
    }

    public static void setSaveDeletedForChannelsEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_SAVE_CHANNELS, enabled).apply();
        }
    }

    public static boolean isSaveDeletedForGroupsLargeEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_SAVE_GROUPS_LARGE, true);
    }

    public static void setSaveDeletedForGroupsLargeEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_SAVE_GROUPS_LARGE, enabled).apply();
        }
    }

    public static boolean isSaveDeletedForCategoryEnabled(int chatType) {
        switch (chatType) {
            case CHAT_TYPE_PRIVATE:
                return isSaveDeletedForPrivateEnabled();
            case CHAT_TYPE_GROUP_SMALL:
                return isSaveDeletedForGroupsSmallEnabled();
            case CHAT_TYPE_CHANNEL:
                return isSaveDeletedForChannelsEnabled();
            case CHAT_TYPE_GROUP_LARGE:
                return isSaveDeletedForGroupsLargeEnabled();
            default:
                return true;
        }
    }

    public static boolean isSaveDeletedMsgTypeEnabled(int msgType) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return true;
        switch (msgType) {
            case MSG_TYPE_VOICE:
                return prefs.getBoolean(KEY_SAVE_MSG_VOICE, true);
            case MSG_TYPE_ROUND:
                return prefs.getBoolean(KEY_SAVE_MSG_ROUND, true);
            case MSG_TYPE_TEXT:
                return prefs.getBoolean(KEY_SAVE_MSG_TEXT, true);
            case MSG_TYPE_PHOTO:
                return prefs.getBoolean(KEY_SAVE_MSG_PHOTO, true);
            case MSG_TYPE_VIDEO:
                return prefs.getBoolean(KEY_SAVE_MSG_VIDEO, true);
            default:
                return true;
        }
    }

    public static void setSaveDeletedMsgTypeEnabled(int msgType, boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        String key;
        switch (msgType) {
            case MSG_TYPE_VOICE:
                key = KEY_SAVE_MSG_VOICE;
                break;
            case MSG_TYPE_ROUND:
                key = KEY_SAVE_MSG_ROUND;
                break;
            case MSG_TYPE_TEXT:
                key = KEY_SAVE_MSG_TEXT;
                break;
            case MSG_TYPE_PHOTO:
                key = KEY_SAVE_MSG_PHOTO;
                break;
            case MSG_TYPE_VIDEO:
                key = KEY_SAVE_MSG_VIDEO;
                break;
            default:
                return;
        }
        prefs.edit().putBoolean(key, enabled).apply();
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
