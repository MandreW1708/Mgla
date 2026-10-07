package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONObject;

/** Parsed response from {@code /mgla-updates/v1/check} (or relay proxy). */
public final class MglaUpdateInfo {

    public final String mglaVersion;
    public final String appVersion;
    public final int versionCode;
    public final String changelog;
    public final long fileSize;
    public final String fileSha256;
    public final boolean mandatory;
    public final String apkPath;

    public MglaUpdateInfo(
            String mglaVersion,
            String appVersion,
            int versionCode,
            String changelog,
            long fileSize,
            String fileSha256,
            boolean mandatory,
            String apkPath
    ) {
        this.mglaVersion = mglaVersion == null ? "" : mglaVersion;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.versionCode = versionCode;
        this.changelog = changelog == null ? "" : changelog;
        this.fileSize = fileSize;
        this.fileSha256 = fileSha256 == null ? "" : fileSha256;
        this.mandatory = mandatory;
        this.apkPath = TextUtils.isEmpty(apkPath) ? "/mgla-updates/v1/apk" : apkPath;
    }

    public static MglaUpdateInfo fromJson(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        if (!root.optBoolean("update", false)) {
            return null;
        }
        return new MglaUpdateInfo(
            root.optString("mgla_version", ""),
            root.optString("app_version", ""),
            root.optInt("version_code", 0),
            root.optString("changelog", ""),
            root.optLong("file_size", 0),
            root.optString("file_sha256", ""),
            root.optBoolean("mandatory", false),
            root.optString("apk_path", "/mgla-updates/v1/apk")
        );
    }

    public String displayVersion() {
        if (!TextUtils.isEmpty(mglaVersion)) {
            return mglaVersion.startsWith("v") || mglaVersion.startsWith("V")
                ? mglaVersion
                : ("v" + mglaVersion);
        }
        if (versionCode > 0) {
            return "build " + versionCode;
        }
        return "";
    }
}
