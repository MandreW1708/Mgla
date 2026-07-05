package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public class MglaHeaderConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_PROXY_IN_HEADER = "proxy_in_header";
    public static final String KEY_DOWNLOADS_IN_HEADER = "downloads_in_header";

    public static boolean isProxyInHeader() {
        return ApplicationLoader.applicationContext != null
            && prefs().getBoolean(KEY_PROXY_IN_HEADER, false);
    }

    public static boolean isDownloadsInHeader() {
        return ApplicationLoader.applicationContext != null
            && prefs().getBoolean(KEY_DOWNLOADS_IN_HEADER, false);
    }

    public static void setProxyInHeader(boolean enabled) {
        if (isProxyInHeader() == enabled) {
            return;
        }
        prefs().edit().putBoolean(KEY_PROXY_IN_HEADER, enabled).apply();
        notifyChanged();
    }

    public static void setDownloadsInHeader(boolean enabled) {
        if (isDownloadsInHeader() == enabled) {
            return;
        }
        prefs().edit().putBoolean(KEY_DOWNLOADS_IN_HEADER, enabled).apply();
        notifyChanged();
    }

    public static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaHeaderSettingsChanged)
        );
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
