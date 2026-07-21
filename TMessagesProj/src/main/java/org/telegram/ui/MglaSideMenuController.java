package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.R;

import java.util.ArrayList;

public class MglaSideMenuController {

    private static final String PREFS = "mgla_config";
    private static final String ORDER_KEY = "side_menu_order";
    private static final String ENABLED_PREFIX = "side_menu_enabled_";
    private static final String DIVIDER_COUNT_KEY = "side_menu_divider_count";

    public static final int ITEM_PROFILE = 0;
    public static final int ITEM_SAVED = 1;
    public static final int ITEM_NEW_GROUP = 2;
    public static final int ITEM_CONTACTS = 3;
    public static final int ITEM_CALLS = 4;
    public static final int ITEM_SETTINGS = 5;
    public static final int ITEM_QR = 6;
    public static final int ITEM_BROWSER = 7;
    public static final int ITEM_ARCHIVE = 8;
    public static final int ITEM_NEW_CHANNEL = 9;
    public static final int ITEM_DIVIDER = 100;

    // Разделители: 100, 101, 102, ... (множественные)
    public static final int DIVIDER_BASE = 100;

    public static final int[] DEFAULT_ORDER = new int[] {
        ITEM_PROFILE,
        ITEM_SAVED,
        ITEM_NEW_GROUP,
        ITEM_CONTACTS,
        ITEM_CALLS,
        ITEM_DIVIDER,
        ITEM_SETTINGS,
        ITEM_QR,
        ITEM_BROWSER,
        ITEM_ARCHIVE,
        ITEM_NEW_CHANNEL
    };

    // Элементы, скрытые по умолчанию
    private static final int[] DEFAULT_HIDDEN = new int[] {
        ITEM_ARCHIVE,
        ITEM_NEW_GROUP,
        ITEM_SAVED,
        ITEM_NEW_CHANNEL,
        ITEM_CALLS
    };

    public static String getTitle(int item) {
        if (isDivider(item)) return "Разделитель";
        switch (item) {
            case ITEM_PROFILE: return "Профиль";
            case ITEM_SAVED: return "Избранное";
            case ITEM_NEW_GROUP: return "Новая группа";
            case ITEM_CONTACTS: return "Контакты";
            case ITEM_CALLS: return "Звонки";
            case ITEM_SETTINGS: return "Настройки";
            case ITEM_QR: return "Сканировать QR";
            case ITEM_BROWSER: return "Браузер";
            case ITEM_ARCHIVE: return "Архив";
            case ITEM_NEW_CHANNEL: return "Создать канал";
        }
        return "Элемент " + item;
    }

    public static int getIcon(int item) {
        if (isDivider(item)) return R.drawable.msg_divider_icon;
        switch (item) {
            case ITEM_PROFILE: return R.drawable.msg_openprofile;
            case ITEM_SAVED: return R.drawable.outline_saved_24;
            case ITEM_NEW_GROUP: return R.drawable.outline_groups_24;
            case ITEM_CONTACTS: return R.drawable.msg_contacts;
            case ITEM_CALLS: return R.drawable.msg_calls;
            case ITEM_SETTINGS: return R.drawable.msg_settings_old;
            case ITEM_QR: return R.drawable.msg_qrcode;
            case ITEM_BROWSER: return R.drawable.outline_header_search;
            case ITEM_ARCHIVE: return R.drawable.msg_archive;
            case ITEM_NEW_CHANNEL: return R.drawable.settings_channel;
        }
        return 0;
    }

    public static boolean isDivider(int item) {
        return item >= DIVIDER_BASE;
    }

    public static boolean isEnabled(Context context, int item) {
        if (context == null) return !isDefaultHidden(item);
        SharedPreferences prefs = getPrefs(context);
        return prefs.getBoolean(ENABLED_PREFIX + item, !isDefaultHidden(item));
    }

    private static boolean isDefaultHidden(int item) {
        for (int hidden : DEFAULT_HIDDEN) {
            if (hidden == item) return true;
        }
        return false;
    }

    public static void setEnabled(Context context, int item, boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(ENABLED_PREFIX + item, enabled).apply();
    }

    public static ArrayList<Integer> getOrder(Context context) {
        ArrayList<Integer> result = new ArrayList<>();
        String saved = context == null ? null : getPrefs(context).getString(ORDER_KEY, null);
        if (!TextUtils.isEmpty(saved)) {
            String[] parts = saved.split(",");
            for (String part : parts) {
                try {
                    int item = Integer.parseInt(part.trim());
                    if (isKnownItem(item) && !result.contains(item)) {
                        result.add(item);
                    }
                } catch (Exception ignore) {
                }
            }
        }
        for (int item : DEFAULT_ORDER) {
            if (!result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    public static void saveOrder(Context context, ArrayList<Integer> order) {
        if (context == null || order == null) return;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(order.get(i));
        }
        getPrefs(context).edit().putString(ORDER_KEY, builder.toString()).apply();
    }

    public static ArrayList<Integer> getVisibleItems(Context context) {
        ArrayList<Integer> order = getOrder(context);
        ArrayList<Integer> result = new ArrayList<>();
        for (int item : order) {
            if (isEnabled(context, item)) {
                result.add(item);
            }
        }
        return result;
    }

    public static ArrayList<Integer> getHiddenItems(Context context) {
        ArrayList<Integer> order = getOrder(context);
        ArrayList<Integer> result = new ArrayList<>();
        for (int item : order) {
            if (!isEnabled(context, item)) {
                result.add(item);
            }
        }
        return result;
    }

    public static int addDivider(Context context) {
        if (context == null) return ITEM_DIVIDER;
        SharedPreferences prefs = getPrefs(context);
        int count = prefs.getInt(DIVIDER_COUNT_KEY, 0);
        int newId = DIVIDER_BASE + 1 + count;
        prefs.edit().putInt(DIVIDER_COUNT_KEY, count + 1).apply();
        setEnabled(context, newId, true);
        return newId;
    }

    public static void removeDivider(Context context, int item) {
        if (context == null || !isDivider(item)) return;
        SharedPreferences prefs = getPrefs(context);
        prefs.edit().remove(ENABLED_PREFIX + item).apply();
    }

    private static boolean isKnownItem(int item) {
        for (int known : DEFAULT_ORDER) {
            if (known == item) return true;
        }
        return isDivider(item);
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}