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
    public static final String PREF_DOUBLE_TAP_OUT = "double_tap_out";
    public static final String PREF_DOUBLE_TAP_IN = "double_tap_in";

    public static final int RECENT_STICKERS_MIN = 10;
    public static final int RECENT_STICKERS_MAX = 200;

    // Действия двойного тапа по сообщению
    public static final int DBL_TAP_NONE = 0;
    public static final int DBL_TAP_REACTION = 1;
    public static final int DBL_TAP_REPLY = 2;
    public static final int DBL_TAP_COPY = 3;
    public static final int DBL_TAP_FORWARD = 4;
    public static final int DBL_TAP_EDIT = 5;
    public static final int DBL_TAP_PIN = 6;
    public static final int DBL_TAP_TRANSLATE = 7;

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

    public static int getDoubleTapAction(boolean outgoing) {
        int action = getPrefs().getInt(outgoing ? PREF_DOUBLE_TAP_OUT : PREF_DOUBLE_TAP_IN, DBL_TAP_REACTION);
        return (action >= DBL_TAP_NONE && action <= DBL_TAP_TRANSLATE) ? action : DBL_TAP_REACTION;
    }

    public static void setDoubleTapAction(boolean outgoing, int action) {
        getPrefs().edit().putInt(outgoing ? PREF_DOUBLE_TAP_OUT : PREF_DOUBLE_TAP_IN, action).apply();
    }

    // Нижняя кнопка в каналах (показывается снизу по центру)
    public static final String PREF_BOTTOM_BUTTON_MODE = "bottom_button_mode";
    public static final String PREF_HIDE_KEYBOARD_ON_SCROLL = "hide_keyboard_on_scroll";
    public static final String PREF_COMMA_AFTER_MENTION = "comma_after_mention";

    public static final int BOTTOM_BUTTON_MUTE = 0;
    public static final int BOTTOM_BUTTON_HIDE = 1;
    public static final int BOTTOM_BUTTON_DISCUSS = 2;

    public static int getBottomButtonMode() {
        int mode = getPrefs().getInt(PREF_BOTTOM_BUTTON_MODE, BOTTOM_BUTTON_MUTE);
        return (mode >= BOTTOM_BUTTON_MUTE && mode <= BOTTOM_BUTTON_DISCUSS) ? mode : BOTTOM_BUTTON_MUTE;
    }

    public static void setBottomButtonMode(int mode) {
        getPrefs().edit().putInt(PREF_BOTTOM_BUTTON_MODE, mode).apply();
    }

    public static String getBottomButtonModeTitle(int mode) {
        switch (mode) {
            case BOTTOM_BUTTON_HIDE: return "Скрыть (кнопку)";
            case BOTTOM_BUTTON_DISCUSS: return "Обсудить";
        }
        return "Выкл звук";
    }

    public static boolean isHideKeyboardOnScroll() {
        return getPrefs().getBoolean(PREF_HIDE_KEYBOARD_ON_SCROLL, false);
    }

    public static void setHideKeyboardOnScroll(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_HIDE_KEYBOARD_ON_SCROLL, enabled).apply();
    }

    public static boolean isCommaAfterMention() {
        return getPrefs().getBoolean(PREF_COMMA_AFTER_MENTION, false);
    }

    public static void setCommaAfterMention(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_COMMA_AFTER_MENTION, enabled).apply();
    }

    /** Суффикс после вставки упоминания: с запятой или просто пробел. */
    public static String getMentionSuffix() {
        return isCommaAfterMention() ? ", " : " ";
    }

    public static final int[] DOUBLE_TAP_ACTIONS = {
        DBL_TAP_NONE, DBL_TAP_REACTION, DBL_TAP_REPLY, DBL_TAP_COPY,
        DBL_TAP_FORWARD, DBL_TAP_EDIT, DBL_TAP_PIN, DBL_TAP_TRANSLATE
    };

    public static String getDoubleTapActionTitle(int action) {
        switch (action) {
            case DBL_TAP_REACTION: return "Быстрая реакция";
            case DBL_TAP_REPLY: return "Ответить";
            case DBL_TAP_COPY: return "Копировать";
            case DBL_TAP_FORWARD: return "Переслать";
            case DBL_TAP_EDIT: return "Редактировать";
            case DBL_TAP_PIN: return "Закрепить";
            case DBL_TAP_TRANSLATE: return "Перевести";
        }
        return "Ничего";
    }

    public static int getDoubleTapActionIcon(int action) {
        switch (action) {
            case DBL_TAP_REACTION: return R.drawable.msg_reactions;
            case DBL_TAP_REPLY: return R.drawable.msg_reply_small;
            case DBL_TAP_COPY: return R.drawable.msg_copy;
            case DBL_TAP_FORWARD: return R.drawable.msg_forward;
            case DBL_TAP_EDIT: return R.drawable.msg_edit;
            case DBL_TAP_PIN: return R.drawable.msg_pin;
            case DBL_TAP_TRANSLATE: return R.drawable.msg_translate;
        }
        return R.drawable.msg_cancel;
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
