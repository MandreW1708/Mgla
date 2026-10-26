package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;

/**
 * Async cover loader with memory + disk cache for Yandex Music.
 * Disk files live under Telegram media cache (follows Data &amp; Storage path).
 */
public final class MglaMusicImageHelper {

    private static final int CACHE_SIZE = 24 * 1024 * 1024; // 24 MB memory
    private static final int DISK_COVER_SIDE = 240; // good on xxhdpi lists, ~30–60 KB JPEG each
    private static final int JPEG_QUALITY = 82;

    public static final DispatchQueue musicImageQueue = new DispatchQueue("mglaMusicImageQueue");

    private static final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap != null ? bitmap.getByteCount() : 0;
        }
    };

    public interface ImageCallback {
        void onLoaded(Bitmap bitmap);
        void onFailed();
    }

    private MglaMusicImageHelper() {
    }

    public static Bitmap getFromCache(String key) {
        if (key == null) return null;
        return memoryCache.get(key);
    }

    public static void putInCache(String key, Bitmap bitmap) {
        if (key != null && bitmap != null) {
            memoryCache.put(key, bitmap);
        }
    }

    private static File getCoverDiskDir() {
        File base = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
        if (base == null) {
            base = ApplicationLoader.applicationContext.getCacheDir();
        }
        File dir = new File(base, "mgla_ym_covers");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static String diskKey(String url, int maxSide) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest((url + "|" + maxSide).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable e) {
            return Integer.toHexString(url.hashCode()) + "_" + maxSide;
        }
    }

    private static File diskFileFor(String url, int maxSide) {
        return new File(getCoverDiskDir(), diskKey(url, maxSide) + ".jpg");
    }

    public static void bindCover(ImageView imageView, String url, int roundRadiusDp, int placeholderResId) {
        bindCover(imageView, url, roundRadiusDp, placeholderResId, DISK_COVER_SIDE);
    }

    public static void bindCover(ImageView imageView, String url, int roundRadiusDp, int placeholderResId, int maxSide) {
        if (imageView == null) return;
        if (TextUtils.isEmpty(url)) {
            imageView.setTag(null);
            if (placeholderResId != 0) {
                imageView.setImageResource(placeholderResId);
            } else {
                imageView.setImageDrawable(null);
            }
            return;
        }

        final String cacheKey = url + "_r" + roundRadiusDp + "_s" + maxSide;

        // Already showing this cover — don't reset to placeholder (prevents scroll jank).
        Object currentTag = imageView.getTag();
        Drawable current = imageView.getDrawable();
        if (cacheKey.equals(currentTag) && current instanceof BitmapDrawable
                && ((BitmapDrawable) current).getBitmap() != null
                && !((BitmapDrawable) current).getBitmap().isRecycled()) {
            return;
        }

        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            imageView.setTag(cacheKey);
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setTag(cacheKey);
        if (!(current instanceof BitmapDrawable) && placeholderResId != 0) {
            imageView.setImageResource(placeholderResId);
        }

        musicImageQueue.postRunnable(() -> {
            try {
                Bitmap processed = loadProcessedCover(url, roundRadiusDp, maxSide);
                if (processed != null) {
                    memoryCache.put(cacheKey, processed);
                    AndroidUtilities.runOnUIThread(() -> {
                        Object tag = imageView.getTag();
                        if (cacheKey.equals(tag)) {
                            imageView.setImageBitmap(processed);
                        }
                    });
                    return;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                Object tag = imageView.getTag();
                if (cacheKey.equals(tag) && placeholderResId != 0
                        && !(imageView.getDrawable() instanceof BitmapDrawable)) {
                    imageView.setImageResource(placeholderResId);
                }
            });
        });
    }

    public static void bindCircleCover(ImageView imageView, String url, int placeholderResId) {
        bindCover(imageView, url, -1, placeholderResId, DISK_COVER_SIDE);
    }

    /** Prefetch + persist cover for offline (list-quality JPEG). Safe off UI thread. */
    public static void prefetchCoverToDisk(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        musicImageQueue.postRunnable(() -> {
            try {
                loadProcessedCover(url, 0, DISK_COVER_SIDE);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static Bitmap loadProcessedCover(String url, int roundRadiusDp, int maxSide) {
        // Memory first (raw keyed without round for disk reuse path)
        String memKey = url + "_r" + roundRadiusDp + "_s" + maxSide;
        Bitmap mem = memoryCache.get(memKey);
        if (mem != null && !mem.isRecycled()) {
            return mem;
        }

        File disk = diskFileFor(url, maxSide);
        Bitmap raw = null;
        if (disk.exists() && disk.length() > 64) {
            raw = BitmapFactory.decodeFile(disk.getAbsolutePath());
        }
        if (raw == null) {
            raw = downloadAndStore(url, maxSide, disk);
        }
        if (raw == null) {
            return null;
        }

        Bitmap processed;
        if (roundRadiusDp < 0) {
            processed = getCircleBitmap(raw);
            if (processed != raw) {
                raw.recycle();
            }
        } else if (roundRadiusDp > 0) {
            processed = getRoundedCornerBitmap(raw, AndroidUtilities.dp(roundRadiusDp));
            if (processed != raw) {
                raw.recycle();
            }
        } else {
            processed = raw;
        }
        return processed;
    }

    private static Bitmap downloadAndStore(String url, int maxSide, File disk) {
        try {
            HttpURLConnection conn = MglaDirectHttp.open(url);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "YandexMusic/2024");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream is = conn.getInputStream();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            // Need mark/reset or two-pass — decode fully then scale for simplicity.
            Bitmap raw = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            if (raw == null) {
                return null;
            }
            if (maxSide > 0 && (raw.getWidth() > maxSide || raw.getHeight() > maxSide)) {
                float scale = Math.min(maxSide / (float) raw.getWidth(), maxSide / (float) raw.getHeight());
                int w = Math.max(1, Math.round(raw.getWidth() * scale));
                int h = Math.max(1, Math.round(raw.getHeight() * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(raw, w, h, true);
                if (scaled != raw) {
                    raw.recycle();
                }
                raw = scaled;
            }
            try {
                File tmp = new File(disk.getAbsolutePath() + ".tmp");
                FileOutputStream fos = new FileOutputStream(tmp);
                raw.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                fos.close();
                if (disk.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    disk.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(disk);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            return raw;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Blocking load for notification / media session artwork. Call off the main thread. */
    public static Bitmap loadBitmapSync(String url, int maxSide) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            Bitmap fromDisk = null;
            int side = maxSide > 0 ? maxSide : DISK_COVER_SIDE;
            File disk = diskFileFor(url, side);
            if (disk.exists() && disk.length() > 64) {
                fromDisk = BitmapFactory.decodeFile(disk.getAbsolutePath());
                if (fromDisk != null) {
                    return fromDisk;
                }
            }
            Bitmap loaded = downloadAndStore(url, side, disk);
            return loaded;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static Bitmap getCircleBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);

            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawARGB(0, 0, 0, 0);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);

            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            int left = (bitmap.getWidth() - size) / 2;
            int top = (bitmap.getHeight() - size) / 2;
            Rect src = new Rect(left, top, left + size, top + size);
            Rect dst = new Rect(0, 0, size, size);
            canvas.drawBitmap(bitmap, src, dst, paint);

            return output;
        } catch (Throwable e) {
            return bitmap;
        }
    }

    public static Bitmap getRoundedCornerBitmap(Bitmap bitmap, float radiusPx) {
        if (bitmap == null) return null;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);

            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            final Rect rect = new Rect(0, 0, width, height);
            final RectF rectF = new RectF(rect);

            canvas.drawARGB(0, 0, 0, 0);
            canvas.drawRoundRect(rectF, radiusPx, radiusPx, paint);

            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(bitmap, rect, rect, paint);

            return output;
        } catch (Throwable e) {
            return bitmap;
        }
    }
}
