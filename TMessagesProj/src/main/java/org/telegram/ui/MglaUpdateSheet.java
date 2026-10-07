package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MglaDirectHttp;
import org.telegram.messenger.MglaUpdateChecker;
import org.telegram.messenger.MglaUpdateInfo;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

import javax.net.ssl.HttpsURLConnection;

/**
 * Mgla-branded update bottom sheet: version, changelog, download + install.
 */
public final class MglaUpdateSheet {

    public interface DismissListener {
        void onDismissed();
    }

    private MglaUpdateSheet() {
    }

    public static void show(Activity activity, MglaUpdateInfo info, DismissListener listener) {
        if (activity == null || info == null) {
            if (listener != null) {
                listener.onDismissed();
            }
            return;
        }

        Context context = activity;

        FrameLayout root = new FrameLayout(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(16));

        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setCornerRadius(AndroidUtilities.dp(2));
        handleBg.setColor(Theme.getColor(Theme.key_sheet_scrollUp));
        handle.setBackground(handleBg);
        column.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 14));

        // Brand badge
        FrameLayout badge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{0xFFF0B45A, 0xFFC47A22}
        );
        badgeBg.setCornerRadius(AndroidUtilities.dp(18));
        badge.setBackground(badgeBg);
        TextView badgeLetter = new TextView(context);
        badgeLetter.setText("M");
        badgeLetter.setTextColor(0xFF1A1208);
        badgeLetter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        badgeLetter.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        badgeLetter.setGravity(Gravity.CENTER);
        badge.addView(badgeLetter, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        column.addView(badge, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        TextView title = new TextView(context);
        title.setText("Доступно обновление Mgla");
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        TextView version = new TextView(context);
        String verLine = info.displayVersion();
        if (!TextUtils.isEmpty(info.appVersion)) {
            verLine = verLine + "  ·  TG " + info.appVersion;
        }
        if (info.versionCode > 0) {
            verLine = verLine + "  ·  #" + info.versionCode;
        }
        version.setText(verLine);
        version.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
        version.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(version, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        TextView changelog = new TextView(context);
        changelog.setText(TextUtils.isEmpty(info.changelog) ? "Улучшения и исправления." : info.changelog);
        changelog.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        changelog.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        changelog.setLineSpacing(AndroidUtilities.dp(2), 1f);
        scroll.addView(changelog, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160, 0, 0, 0, 16));

        ProgressButton downloadBtn = new ProgressButton(context);
        String sizeHint = info.fileSize > 0
            ? "  (" + AndroidUtilities.formatFileSize(info.fileSize) + ")"
            : "";
        downloadBtn.setLabel(LocaleController.getString(R.string.AppUpdateDownloadNow) + sizeHint);
        column.addView(downloadBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 8));

        TextView later = new TextView(context);
        later.setText(info.mandatory ? "" : LocaleController.getString(R.string.AppUpdateRemindMeLater));
        later.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        later.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        later.setGravity(Gravity.CENTER);
        later.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(4));
        later.setVisibility(info.mandatory ? View.GONE : View.VISIBLE);
        column.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false);
        builder.setCustomView(root);
        builder.setApplyBottomPadding(false);
        BottomSheet sheet = builder.create();
        sheet.setCanDismissWithSwipe(!info.mandatory);
        sheet.setCanDismissWithTouchOutside(!info.mandatory);
        sheet.setOnDismissListener(d -> {
            if (listener != null) {
                listener.onDismissed();
            }
        });

        later.setOnClickListener(v -> {
            MglaUpdateChecker.dismiss(info);
            sheet.dismiss();
        });

        downloadBtn.setOnClickListener(v -> {
            if (downloadBtn.isBusy()) {
                return;
            }
            if (!ensureInstallPermission(activity)) {
                return;
            }
            startDownload(activity, info, downloadBtn, sheet);
        });

        try {
            sheet.show();
        } catch (Throwable e) {
            FileLog.e(e);
            if (listener != null) {
                listener.onDismissed();
            }
        }
    }

    private static boolean ensureInstallPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) {
            return true;
        }
        try {
            if (activity.getPackageManager().canRequestPackageInstalls()) {
                return true;
            }
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            Toast.makeText(
                activity,
                "Разрешите установку из этого источника, затем нажмите «Скачать» снова",
                Toast.LENGTH_LONG
            ).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    private static void startDownload(Activity activity, MglaUpdateInfo info, ProgressButton button, BottomSheet sheet) {
        button.setBusy(true, 0f);
        Utilities.globalQueue.postRunnable(() -> {
            File outDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "mgla_updates");
            if (!outDir.exists() && !outDir.mkdirs()) {
                failUi(button, "Не удалось создать папку загрузки");
                return;
            }
            File apk = new File(outDir, "mgla-update.apk");
            if (apk.exists()) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }
            HttpsURLConnection conn = null;
            try {
                String url = MglaUpdateChecker.apkDownloadUrl(info);
                conn = MglaDirectHttp.openHttps(url);
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(300_000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-Mgla-Token", MglaUpdateChecker.apkAuthToken());
                conn.connect();
                String host = Uri.parse(url).getHost();
                if (MglaUpdateChecker.apkPinIsRelay()) {
                    if (MglaWsConfig.DEFAULT_RELAY_HOST.equalsIgnoreCase(host)) {
                        MglaUpdateChecker.verifySpkiPin(conn.getServerCertificates(), MglaWsConfig.RELAY_SPKI_SHA256_BASE64);
                    }
                } else if (MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host)) {
                    MglaUpdateChecker.verifySpkiPin(conn.getServerCertificates(), MglaWsConfig.HUB_SPKI_SHA256_BASE64);
                }
                int code = conn.getResponseCode();
                if (code != 200) {
                    failUi(button, "Ошибка загрузки HTTP " + code);
                    return;
                }
                long total = Build.VERSION.SDK_INT >= 24 ? conn.getContentLengthLong() : conn.getContentLength();
                if (total <= 0 && info.fileSize > 0) {
                    total = info.fileSize;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long read = 0;
                    int n;
                    long lastUi = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                        read += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 80) {
                            lastUi = now;
                            float p = total > 0 ? Math.min(1f, read / (float) total) : -1f;
                            AndroidUtilities.runOnUIThread(() -> button.setBusy(true, p));
                        }
                    }
                }
                if (!TextUtils.isEmpty(info.fileSha256)) {
                    String actual = bytesToHex(digest.digest());
                    if (!actual.equalsIgnoreCase(info.fileSha256)) {
                        //noinspection ResultOfMethodCallIgnored
                        apk.delete();
                        failUi(button, "Контрольная сумма APK не совпала");
                        return;
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    button.setBusy(false, 1f);
                    button.setLabel("Установить");
                    if (!installApk(activity, apk)) {
                        failUi(button, "Не удалось открыть установщик");
                    } else if (sheet != null && !info.mandatory) {
                        sheet.dismiss();
                    }
                });
            } catch (Throwable e) {
                FileLog.e("Mgla update download failed", e);
                failUi(button, e.getMessage() != null ? e.getMessage() : "Ошибка загрузки");
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static void failUi(ProgressButton button, String msg) {
        AndroidUtilities.runOnUIThread(() -> {
            button.setBusy(false, 0f);
            button.setLabel(LocaleController.getString(R.string.AppUpdateDownloadNow));
            try {
                Context ctx = ApplicationLoader.applicationContext;
                if (ctx != null && !TextUtils.isEmpty(msg)) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static boolean installApk(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                activity,
                ApplicationLoader.getApplicationId() + ".provider",
                apk
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static final class ProgressButton extends FrameLayout {
        private final TextView label;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean busy;
        private float progress = -1f;

        ProgressButton(Context context) {
            super(context);
            setWillNotDraw(false);
            fillPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            trackPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.25f));
            label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setTypeface(AndroidUtilities.bold());
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 12));
        }

        void setLabel(String text) {
            label.setText(text);
        }

        boolean isBusy() {
            return busy;
        }

        void setBusy(boolean busy, float progress) {
            this.busy = busy;
            this.progress = progress;
            setEnabled(!busy);
            if (busy) {
                if (progress >= 0f) {
                    label.setText(Math.round(progress * 100) + "%");
                } else {
                    label.setText("Загрузка…");
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!busy || progress < 0f) {
                return;
            }
            float radius = AndroidUtilities.dp(12);
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, radius, radius, trackPaint);
            rect.right = getWidth() * Math.max(0.02f, Math.min(1f, progress));
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
        }
    }
}
