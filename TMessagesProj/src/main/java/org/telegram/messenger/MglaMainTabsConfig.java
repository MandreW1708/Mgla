package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Order + visibility of the bottom main-tabs island (chats / contacts / …).
 */
public final class MglaMainTabsConfig {

    public static final String PREFS = "mgla_config";
    private static final String KEY_ORDER = "main_tabs_order";
    private static final String KEY_CUSTOMIZED = "main_tabs_customized";
    private static final String KEY_SEARCH_BUTTON = "main_tabs_search_button";

    public static final int MIN_TABS = 2;
    public static final int MAX_TABS = 7;

    /** Square search pill next to the island (same height as the island). */
    public static final int SEARCH_BUTTON_SIZE = 56;
    /** Gap between island and search button (side screen margins use MAIN_TABS_MARGIN). */
    public static final int SEARCH_BUTTON_GAP = 4;

    public enum TabId {
        CHATS("chats"),
        CONTACTS("contacts"),
        SETTINGS("settings"),
        CALLS("calls"),
        PROFILE("profile"),
        TASKS("tasks"),
        AI("ai");

        public final String key;

        TabId(String key) {
            this.key = key;
        }

        @Nullable
        public static TabId fromKey(String key) {
            if (key == null) {
                return null;
            }
            for (TabId id : values()) {
                if (id.key.equals(key)) {
                    return id;
                }
            }
            return null;
        }
    }

    private MglaMainTabsConfig() {
    }

    public static boolean isCustomized() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_CUSTOMIZED, false);
    }

    public static boolean isSearchButtonEnabled() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_SEARCH_BUTTON, false);
    }

    public static void setSearchButtonEnabled(boolean enabled) {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        p.edit().putBoolean(KEY_SEARCH_BUTTON, enabled).apply();
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaMainTabsChanged));
    }

    /** Enabled tabs in display order. */
    public static ArrayList<TabId> getEnabledTabs(boolean showCallsFallback) {
        SharedPreferences p = prefs();
        if (p != null && p.getBoolean(KEY_CUSTOMIZED, false)) {
            ArrayList<TabId> parsed = sanitize(parseOrder(p.getString(KEY_ORDER, null)));
            if (parsed.size() >= MIN_TABS) {
                return parsed;
            }
        }
        return defaultOrder(showCallsFallback);
    }

    public static ArrayList<TabId> getHiddenTabs(boolean showCallsFallback) {
        ArrayList<TabId> enabled = getEnabledTabs(showCallsFallback);
        ArrayList<TabId> hidden = new ArrayList<>();
        for (TabId id : TabId.values()) {
            if (!enabled.contains(id)) {
                hidden.add(id);
            }
        }
        return hidden;
    }

    public static void setEnabledTabs(List<TabId> order) {
        ArrayList<TabId> cleaned = sanitize(order);
        if (cleaned.size() < MIN_TABS) {
            return;
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cleaned.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cleaned.get(i).key);
        }
        p.edit()
                .putBoolean(KEY_CUSTOMIZED, true)
                .putString(KEY_ORDER, sb.toString())
                .apply();
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaMainTabsChanged));
    }

    public static void resetToDefault() {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        p.edit().remove(KEY_CUSTOMIZED).remove(KEY_ORDER).remove(KEY_SEARCH_BUTTON).apply();
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaMainTabsChanged));
    }

    public static String titleFor(TabId id) {
        switch (id) {
            case CHATS:
                return LocaleController.getString(R.string.MainTabsChats);
            case CONTACTS:
                return LocaleController.getString(R.string.MainTabsContacts);
            case SETTINGS:
                return LocaleController.getString(R.string.Settings);
            case CALLS:
                return LocaleController.getString(R.string.MainTabsCalls);
            case PROFILE:
                return LocaleController.getString(R.string.MainTabsProfile);
            case TASKS:
                return "Задачи";
            case AI:
                return "ИИ";
            default:
                return id.key;
        }
    }

    public static ArrayList<TabId> defaultOrder(boolean showCalls) {
        ArrayList<TabId> list = new ArrayList<>(4);
        list.add(TabId.CHATS);
        list.add(TabId.CONTACTS);
        list.add(showCalls ? TabId.CALLS : TabId.SETTINGS);
        list.add(TabId.PROFILE);
        return list;
    }

    /** Chats and Settings are always present in the island. */
    public static boolean isLocked(TabId id) {
        return id == TabId.CHATS || id == TabId.SETTINGS;
    }

    private static ArrayList<TabId> sanitize(List<TabId> order) {
        LinkedHashSet<TabId> set = new LinkedHashSet<>();
        if (order != null) {
            for (TabId id : order) {
                if (id != null) {
                    set.add(id);
                }
            }
        }
        // Required tabs must stay even if the saved order somehow dropped them.
        if (!set.contains(TabId.CHATS)) {
            ArrayList<TabId> withChats = new ArrayList<>();
            withChats.add(TabId.CHATS);
            withChats.addAll(set);
            set.clear();
            set.addAll(withChats);
        }
        if (!set.contains(TabId.SETTINGS)) {
            set.add(TabId.SETTINGS);
        }
        ArrayList<TabId> out = new ArrayList<>(set);
        while (out.size() > MAX_TABS) {
            boolean removed = false;
            for (int i = out.size() - 1; i >= 0; i--) {
                if (!isLocked(out.get(i))) {
                    out.remove(i);
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                break;
            }
        }
        return out;
    }

    private static ArrayList<TabId> parseOrder(String raw) {
        ArrayList<TabId> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        String[] parts = raw.split(",");
        LinkedHashSet<TabId> set = new LinkedHashSet<>();
        for (String part : parts) {
            TabId id = TabId.fromKey(part.trim().toLowerCase(Locale.US));
            if (id != null) {
                set.add(id);
            }
        }
        out.addAll(set);
        if (out.size() > MAX_TABS) {
            return new ArrayList<>(out.subList(0, MAX_TABS));
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
