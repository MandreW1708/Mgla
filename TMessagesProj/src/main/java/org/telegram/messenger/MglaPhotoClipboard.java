package org.telegram.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Puts an image on the system clipboard as a content URI. The image is copied into
 * {@code cacheDir/mgla_clipboard/} (exposed by the app FileProvider) so the clip stays valid
 * even if Telegram later evicts the original from its media cache.
 */
public final class MglaPhotoClipboard {

    private static final String DIR = "mgla_clipboard";

    private MglaPhotoClipboard() {
    }

    /** Local file of a downloaded photo message, or {@code null} if it isn't on disk yet. */
    public static File getMessageFile(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return null;
        }
        String path = message.messageOwner.attachPath;
        if (!TextUtils.isEmpty(path)) {
            File f = new File(path);
            if (f.exists()) {
                return f;
            }
        }
        File f = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        return f != null && f.exists() ? f : null;
    }

    /**
     * Copies {@code file} if it is a readable image, otherwise {@code fallback} (e.g. the bitmap
     * currently on screen). {@code done} runs on the UI thread with the result.
     */
    public static void copy(File file, Bitmap fallback, Utilities.Callback<Boolean> done) {
        String ext = file != null && file.exists() ? imageExtension(file) : null;
        Bitmap bitmap = null;
        if (ext == null) {
            if (fallback == null || fallback.isRecycled()) {
                done.run(false);
                return;
            }
            // The on-screen bitmap may be recycled while we encode it in the background.
            try {
                bitmap = fallback.copy(fallback.getConfig() != null ? fallback.getConfig() : Bitmap.Config.ARGB_8888, false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (bitmap == null) {
                done.run(false);
                return;
            }
        }
        final Bitmap toEncode = bitmap;
        Utilities.globalQueue.postRunnable(() -> {
            File out = null;
            try {
                File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("can't create " + dir);
                }
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        f.delete();
                    }
                }
                out = new File(dir, "photo_" + System.currentTimeMillis() + "." + (toEncode != null ? "png" : ext));
                if (toEncode != null) {
                    try (OutputStream os = new FileOutputStream(out)) {
                        toEncode.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    toEncode.recycle();
                } else {
                    try (InputStream is = new FileInputStream(file); OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                out = null;
            }
            final File result = out;
            AndroidUtilities.runOnUIThread(() -> done.run(result != null && setClip(result)));
        });
    }

    private static boolean setClip(File file) {
        try {
            Context context = ApplicationLoader.applicationContext;
            Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(context.getContentResolver(), "Фото", uri));
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** File extension matching the actual image format, or {@code null} if it isn't a supported image. */
    private static String imageExtension(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outMimeType == null) {
            return null;
        }
        switch (opts.outMimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            default: return null;
        }
    }
}
