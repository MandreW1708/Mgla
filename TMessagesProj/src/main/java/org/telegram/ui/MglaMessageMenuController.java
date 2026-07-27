package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class MglaMessageMenuController {

    private static final String PREFS = "mgla_config";
    private static final String ORDER_KEY = "message_menu_order";
    private static final String ENABLED_PREFIX = "message_menu_enabled_";

    public static final int[] DEFAULT_ORDER = new int[] {
        ChatActivity.OPTION_REPLY,
        ChatActivity.OPTION_COPY,
        ChatActivity.OPTION_FORWARD,
        ChatActivity.OPTION_EDIT,
        ChatActivity.OPTION_PIN,
        ChatActivity.OPTION_UNPIN,
        ChatActivity.OPTION_TRANSLATE,
        ChatActivity.OPTION_AI_SUMMARY,
        ChatActivity.OPTION_AI_REPLY_POLITE,
        ChatActivity.OPTION_AI_EXPLAIN_SIMPLE,
        ChatActivity.OPTION_AI_TRANSLATE,
        ChatActivity.OPTION_AI_TASKS,
        ChatActivity.OPTION_DELETE
    };

    public static String getTitle(int option) {
        switch (option) {
            case ChatActivity.OPTION_REPLY: return "Ответить";
            case ChatActivity.OPTION_COPY: return "Копировать";
            case ChatActivity.OPTION_FORWARD: return "Переслать";
            case ChatActivity.OPTION_EDIT: return "Изменить";
            case ChatActivity.OPTION_PIN: return "Закрепить";
            case ChatActivity.OPTION_UNPIN: return "Открепить";
            case ChatActivity.OPTION_TRANSLATE: return "Перевести";
            case ChatActivity.OPTION_AI_SUMMARY: return "Краткая Сводка";
            case ChatActivity.OPTION_AI_REPLY_POLITE: return "Ответить вежливо";
            case ChatActivity.OPTION_AI_EXPLAIN_SIMPLE: return "Объяснить проще";
            case ChatActivity.OPTION_AI_TRANSLATE: return "AI-перевод";
            case ChatActivity.OPTION_AI_TASKS: return "Выделить задачи";
            case ChatActivity.OPTION_DELETE: return "Удалить";
        }
        return "Пункт " + option;
    }

    public static boolean isEnabled(Context context, int option) {
        if (context == null) return true;
        if (option == ChatActivity.OPTION_AI_SUMMARY && !getPrefs(context).getBoolean("ai_summary", true)) {
            return false;
        }
        return getPrefs(context).getBoolean(ENABLED_PREFIX + option, true);
    }

    public static void setEnabled(Context context, int option, boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(ENABLED_PREFIX + option, enabled).apply();
        if (option == ChatActivity.OPTION_AI_SUMMARY) {
            getPrefs(context).edit().putBoolean("ai_summary", enabled).apply();
        }
    }

    public static ArrayList<Integer> getOrder(Context context) {
        ArrayList<Integer> result = new ArrayList<>();
        String saved = context == null ? null : getPrefs(context).getString(ORDER_KEY, null);
        if (!TextUtils.isEmpty(saved)) {
            String[] parts = saved.split(",");
            for (String part : parts) {
                try {
                    int option = Integer.parseInt(part.trim());
                    if (isKnownOption(option) && !result.contains(option)) {
                        result.add(option);
                    }
                } catch (Exception ignore) {
                }
            }
        }
        for (int option : DEFAULT_ORDER) {
            if (!result.contains(option)) {
                result.add(option);
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

    public static void applyToMenu(Context context, ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        if (context == null || icons == null || items == null || options == null || options.isEmpty()) {
            return;
        }
        for (int i = options.size() - 1; i >= 0; i--) {
            int option = options.get(i);
            if (isKnownOption(option) && !isEnabled(context, option)) {
                options.remove(i);
                items.remove(i);
                icons.remove(i);
            }
        }

        ArrayList<Integer> order = getOrder(context);
        HashMap<Integer, Integer> orderIndex = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            orderIndex.put(order.get(i), i);
        }

        ArrayList<MenuEntry> entries = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            entries.add(new MenuEntry(icons.get(i), items.get(i), options.get(i), i));
        }
        Collections.sort(entries, (a, b) -> {
            int ai = orderIndex.containsKey(a.option) ? orderIndex.get(a.option) : Integer.MAX_VALUE / 2 + a.originalIndex;
            int bi = orderIndex.containsKey(b.option) ? orderIndex.get(b.option) : Integer.MAX_VALUE / 2 + b.originalIndex;
            return Integer.compare(ai, bi);
        });

        icons.clear();
        items.clear();
        options.clear();
        for (MenuEntry entry : entries) {
            icons.add(entry.icon);
            items.add(entry.item);
            options.add(entry.option);
        }
    }

    private static boolean isKnownOption(int option) {
        for (int known : DEFAULT_ORDER) {
            if (known == option) return true;
        }
        return false;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static class MenuEntry {
        final int icon;
        final CharSequence item;
        final int option;
        final int originalIndex;

        MenuEntry(int icon, CharSequence item, int option, int originalIndex) {
            this.icon = icon;
            this.item = item;
            this.option = option;
            this.originalIndex = originalIndex;
        }
    }
}
