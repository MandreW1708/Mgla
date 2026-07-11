package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public class MglaSideMenuConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_ENABLED = "side_menu_enabled";

    public static boolean isEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
            AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaHeaderSettingsChanged)
            );
        }
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}