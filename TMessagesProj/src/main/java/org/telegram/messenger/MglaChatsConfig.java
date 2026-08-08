package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.math.MathUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public class MglaChatsConfig {

    public static final String PREFS = "mgla_config";
    public static final String PREF_RECENT_STICKERS_LIMIT = "recent_stickers_limit";
    public static final String PREF_HIDE_STICKER_TIME = "hide_sticker_time";
    public static final String PREF_IOS_MENU_STYLE = "ios_menu_style";

    public static final int RECENT_STICKERS_MIN = 10;
    public static final int RECENT_STICKERS_MAX = 200;

    public static int getRecentStickersLimit() {
        return getRecentStickersLimit(UserConfig.selectedAccount);
    }

    public static boolean hasCustomRecentStickersLimit() {
        return getPrefs().contains(PREF_RECENT_STICKERS_LIMIT);
    }

    public static int getRecentStickersLimit(int account) {
        SharedPreferences prefs = getPrefs();
        if (!prefs.contains(PREF_RECENT_STICKERS_LIMIT)) {
            return MessagesController.getInstance(account).maxRecentStickersCount;
        }
        return MathUtils.clamp(prefs.getInt(PREF_RECENT_STICKERS_LIMIT, 30), RECENT_STICKERS_MIN, RECENT_STICKERS_MAX);
    }

    public static void setRecentStickersLimit(int limit) {
        limit = MathUtils.clamp(limit, RECENT_STICKERS_MIN, RECENT_STICKERS_MAX);
        getPrefs().edit().putInt(PREF_RECENT_STICKERS_LIMIT, limit).apply();
        trimRecentStickers(limit);
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            MediaDataController.getInstance(account).loadRecents(MediaDataController.TYPE_IMAGE, false, true, false);
        }
        notifyRecentStickersChanged();
    }

    public static boolean isStickerTimeHidden() {
        return getPrefs().getBoolean(PREF_HIDE_STICKER_TIME, false);
    }

    public static void setStickerTimeHidden(boolean hidden) {
        if (isStickerTimeHidden() == hidden) {
            return;
        }
        getPrefs().edit().putBoolean(PREF_HIDE_STICKER_TIME, hidden).apply();
        notifyStickerTimeChanged();
    }

    public static boolean isIosMenuStyleEnabled() {
        return getPrefs().getBoolean(PREF_IOS_MENU_STYLE, false);
    }

    public static void setIosMenuStyleEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_IOS_MENU_STYLE, enabled).apply();
    }

    public static void notifyRecentStickersChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.recentDocumentsDidLoad, false, MediaDataController.TYPE_IMAGE);
            }
        });
    }

    public static void notifyStickerTimeChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
            }
        });
    }

    private static void trimRecentStickers(int limit) {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            ArrayList<TLRPC.Document> recent = MediaDataController.getInstance(account).getRecentStickersNoCopy(MediaDataController.TYPE_IMAGE);
            while (recent.size() > limit) {
                recent.remove(recent.size() - 1);
            }
        }
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
