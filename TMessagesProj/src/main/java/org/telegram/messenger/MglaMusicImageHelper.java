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
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

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

    public static String replaceYandexCoverSize(String url, String newSize) {
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        int queryIdx = url.indexOf('?');
        String base = queryIdx >= 0 ? url.substring(0, queryIdx) : url;
        String query = queryIdx >= 0 ? url.substring(queryIdx) : "";
        int lastSlash = base.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash >= base.length() - 1) {
            return url;
        }
        String suffix = base.substring(lastSlash + 1);
        if (suffix.equals("%%") || suffix.equals("orig") || suffix.equals("origin") || suffix.matches("m?\\d+x\\d+")) {
            return base.substring(0, lastSlash + 1) + newSize + query;
        }
        return base + "/" + newSize + query;
    }

    public static String replaceYandexCoverSize(String url, int newSize) {
        if (newSize >= 1000) {
            return replaceYandexCoverSize(url, "1000x1000");
        }
        return replaceYandexCoverSize(url, newSize + "x" + newSize);
    }

    public static File findFallbackDiskCover(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        String[] candidateSizes = new String[]{"orig", "origin", "1000x1000", "m1000x1000", "600x600", "400x400", "300x300", "200x200", "100x100"};
        int[] candidateSides = new int[]{1000, 1000, 1000, 1000, 600, 400, DISK_COVER_SIDE, DISK_COVER_SIDE, DISK_COVER_SIDE};
        for (int i = 0; i < candidateSizes.length; i++) {
            String candidateUrl = replaceYandexCoverSize(url, candidateSizes[i]);
            File f = diskFileFor(candidateUrl, candidateSides[i]);
            if (f.exists() && f.length() > 64) {
                return f;
            }
            if (candidateSides[i] != DISK_COVER_SIDE) {
                File f2 = diskFileFor(candidateUrl, DISK_COVER_SIDE);
                if (f2.exists() && f2.length() > 64) {
                    return f2;
                }
            }
        }
        return null;
    }

    /**
     * Dedicated loader for expanded player sheet.
     * When online: loads full high-resolution (1000x1000 / orig / 600x600) cover, while immediately showing cached low-res preview.
     * When offline: immediately falls back to downloaded low-quality cover on disk (e.g. 300x300 offline cache).
     */
    public static void bindPlayerCover(ImageView imageView, MglaYandexMusicModel.Track track, int placeholderResId, ImageCallback callback) {
        if (imageView == null) return;
        if (track == null || TextUtils.isEmpty(track.coverUri)) {
            imageView.setTag(null);
            if (placeholderResId != 0) {
                imageView.setImageResource(placeholderResId);
            } else {
                imageView.setImageDrawable(null);
            }
            if (callback != null) {
                callback.onFailed();
            }
            return;
        }

        final String trackId = !TextUtils.isEmpty(track.id) ? track.id : track.coverUri;
        final String highResUrl = track.getCoverUrl(1000);
        final String requestKey = "player_cover_" + trackId;
        final String highResMemKey = "high_" + trackId;

        Object currentTag = imageView.getTag();
        Drawable currentDrawable = imageView.getDrawable();
        boolean hasCurrentBitmap = currentDrawable instanceof BitmapDrawable
                && ((BitmapDrawable) currentDrawable).getBitmap() != null
                && !((BitmapDrawable) currentDrawable).getBitmap().isRecycled();

        // 1. High-res memory cache check: instant display if available
        Bitmap cachedHighRes = memoryCache.get(highResMemKey);
        if (cachedHighRes != null && !cachedHighRes.isRecycled()) {
            if ((requestKey + "_high").equals(currentTag) && hasCurrentBitmap
                    && ((BitmapDrawable) currentDrawable).getBitmap() == cachedHighRes) {
                return;
            }
            imageView.setTag(requestKey + "_high");
            imageView.setImageBitmap(cachedHighRes);
            if (callback != null) {
                callback.onLoaded(cachedHighRes);
            }
            return;
        }

        // If already showing high-res for this track, don't re-download or downgrade
        if ((requestKey + "_high").equals(currentTag) && hasCurrentBitmap) {
            return;
        }

        imageView.setTag(requestKey + "_low");

        // 2. Fallback disk preview
        File fallbackDisk = findFallbackDiskCover(highResUrl);
        Bitmap previewBmp = null;
        if (fallbackDisk != null && fallbackDisk.exists() && fallbackDisk.length() > 64) {
            previewBmp = BitmapFactory.decodeFile(fallbackDisk.getAbsolutePath());
        }
        if (previewBmp != null) {
            imageView.setImageBitmap(previewBmp);
            if (callback != null) {
                callback.onLoaded(previewBmp);
            }
        } else if (!hasCurrentBitmap && placeholderResId != 0) {
            imageView.setImageResource(placeholderResId);
        }

        final boolean isOnline = ApplicationLoader.isNetworkOnline();
        if (!isOnline) {
            if (previewBmp == null) {
                if (placeholderResId != 0) {
                    imageView.setImageResource(placeholderResId);
                }
                if (callback != null) {
                    callback.onFailed();
                }
            }
            return;
        }

        // 3. Online: download full quality cover in background
        musicImageQueue.postRunnable(() -> {
            try {
                File highResDisk = diskFileFor(highResUrl, 1000);
                if (highResDisk.exists() && highResDisk.length() > 64) {
                    Bitmap diskHighRes = BitmapFactory.decodeFile(highResDisk.getAbsolutePath());
                    if (diskHighRes != null) {
                        memoryCache.put(highResMemKey, diskHighRes);
                        AndroidUtilities.runOnUIThread(() -> {
                            if ((requestKey + "_low").equals(imageView.getTag()) || (requestKey + "_high").equals(imageView.getTag())) {
                                imageView.setTag(requestKey + "_high");
                                imageView.setImageBitmap(diskHighRes);
                                if (callback != null) {
                                    callback.onLoaded(diskHighRes);
                                }
                            }
                        });
                        return;
                    }
                }

                Bitmap fullBmp = downloadAndStore(highResUrl, 1000, highResDisk);
                if (fullBmp != null) {
                    memoryCache.put(highResMemKey, fullBmp);
                    AndroidUtilities.runOnUIThread(() -> {
                        if ((requestKey + "_low").equals(imageView.getTag()) || (requestKey + "_high").equals(imageView.getTag())) {
                            imageView.setTag(requestKey + "_high");
                            imageView.setImageBitmap(fullBmp);
                            if (callback != null) {
                                callback.onLoaded(fullBmp);
                            }
                        }
                    });
                }
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
            File fallback = findFallbackDiskCover(url);
            if (fallback != null && fallback.exists() && fallback.length() > 64) {
                if (!ApplicationLoader.isNetworkOnline() || maxSide <= DISK_COVER_SIDE) {
                    raw = BitmapFactory.decodeFile(fallback.getAbsolutePath());
                }
            }
        }
        if (raw == null && ApplicationLoader.isNetworkOnline()) {
            raw = downloadAndStore(url, maxSide, disk);
        }
        if (raw == null) {
            File fallback = findFallbackDiskCover(url);
            if (fallback != null && fallback.exists() && fallback.length() > 64) {
                raw = BitmapFactory.decodeFile(fallback.getAbsolutePath());
            }
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

    private static HttpURLConnection openWithRedirects(String urlStr) throws Exception {
        String currentUrl = urlStr;
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection conn = MglaDirectHttp.open(currentUrl);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "YandexMusic/2024");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (!TextUtils.isEmpty(location)) {
                    if (location.startsWith("//")) {
                        location = "https:" + location;
                    } else if (!location.startsWith("http://") && !location.startsWith("https://")) {
                        URL base = new URL(currentUrl);
                        location = new URL(base, location).toString();
                    }
                    currentUrl = location;
                    continue;
                }
            }
            return conn;
        }
        return MglaDirectHttp.open(currentUrl);
    }

    private static Bitmap tryDownloadSingleUrl(String url, int maxSide, File disk) {
        try {
            HttpURLConnection conn = openWithRedirects(url);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                conn.disconnect();
                return null;
            }
            InputStream is = conn.getInputStream();
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
                File parent = disk.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                File tmp = new File(disk.getAbsolutePath() + ".tmp");
                FileOutputStream fos = new FileOutputStream(tmp);
                int quality = maxSide >= 600 ? 94 : JPEG_QUALITY;
                raw.compress(Bitmap.CompressFormat.JPEG, quality, fos);
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

    private static Bitmap downloadAndStore(String url, int maxSide, File disk) {
        if (!ApplicationLoader.isNetworkOnline() || TextUtils.isEmpty(url)) {
            return null;
        }
        List<String> urlsToTry = new ArrayList<>();
        urlsToTry.add(url);
        if (maxSide >= 600) {
            String[] candidateSizes = new String[]{"1000x1000", "orig", "origin", "m1000x1000", "600x600", "400x400"};
            for (String sz : candidateSizes) {
                String candidate = replaceYandexCoverSize(url, sz);
                if (!urlsToTry.contains(candidate)) {
                    urlsToTry.add(candidate);
                }
            }
        } else {
            String[] candidateSizes = new String[]{"400x400", "300x300", "200x200"};
            for (String sz : candidateSizes) {
                String candidate = replaceYandexCoverSize(url, sz);
                if (!urlsToTry.contains(candidate)) {
                    urlsToTry.add(candidate);
                }
            }
        }

        for (String targetUrl : urlsToTry) {
            Bitmap bmp = tryDownloadSingleUrl(targetUrl, maxSide, disk);
            if (bmp != null) {
                return bmp;
            }
        }
        return null;
    }

    /**
     * Downscale in place when larger than {@code maxSide}. Recycles the oversized source when a
     * new bitmap is created — do not pass a bitmap still held by the memory cache.
     */
    public static Bitmap ensureMaxSide(Bitmap raw, int maxSide) {
        if (raw == null || maxSide <= 0) {
            return raw;
        }
        if (raw.getWidth() <= maxSide && raw.getHeight() <= maxSide) {
            return raw;
        }
        try {
            float scale = Math.min(maxSide / (float) raw.getWidth(), maxSide / (float) raw.getHeight());
            int w = Math.max(1, Math.round(raw.getWidth() * scale));
            int h = Math.max(1, Math.round(raw.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(raw, w, h, true);
            if (scaled != null && scaled != raw) {
                raw.recycle();
                return scaled;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return raw;
    }

    /**
     * Bitmap safe for {@code RemoteViews} / binder (~1 MB transaction cap).
     * Scales a copy (never recycles {@code src}), then optional rounded corners.
     */
    public static Bitmap forRemoteViews(Bitmap src, int maxSide, float radiusPx) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        Bitmap working = src;
        boolean ownsWorking = false;
        try {
            if (maxSide > 0 && (src.getWidth() > maxSide || src.getHeight() > maxSide)) {
                float scale = Math.min(maxSide / (float) src.getWidth(), maxSide / (float) src.getHeight());
                int w = Math.max(1, Math.round(src.getWidth() * scale));
                int h = Math.max(1, Math.round(src.getHeight() * scale));
                working = Bitmap.createScaledBitmap(src, w, h, true);
                ownsWorking = working != null && working != src;
                if (working == null) {
                    return null;
                }
            }
            if (radiusPx > 0) {
                Bitmap rounded = getRoundedCornerBitmap(working, radiusPx);
                if (ownsWorking && rounded != null && rounded != working) {
                    working.recycle();
                }
                return rounded;
            }
            return working;
        } catch (Throwable e) {
            FileLog.e(e);
            if (ownsWorking && working != null && !working.isRecycled()) {
                working.recycle();
            }
            return null;
        }
    }

    /** Blocking load for notification / media session / widget artwork. Call off the main thread. */
    public static Bitmap loadBitmapSync(String url, int maxSide) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            int side = maxSide > 0 ? maxSide : DISK_COVER_SIDE;
            File disk = diskFileFor(url, side);
            if (disk.exists() && disk.length() > 64) {
                Bitmap fromDisk = BitmapFactory.decodeFile(disk.getAbsolutePath());
                if (fromDisk != null) {
                    return ensureMaxSide(fromDisk, side);
                }
            }
            File fallback = findFallbackDiskCover(url);
            if (fallback != null && fallback.exists() && fallback.length() > 64) {
                if (!ApplicationLoader.isNetworkOnline() || side <= DISK_COVER_SIDE) {
                    Bitmap fallbackBmp = BitmapFactory.decodeFile(fallback.getAbsolutePath());
                    if (fallbackBmp != null) {
                        return ensureMaxSide(fallbackBmp, side);
                    }
                }
            }
            if (!ApplicationLoader.isNetworkOnline()) {
                if (fallback != null && fallback.exists() && fallback.length() > 64) {
                    return ensureMaxSide(BitmapFactory.decodeFile(fallback.getAbsolutePath()), side);
                }
                return null;
            }
            Bitmap loaded = downloadAndStore(url, side, disk);
            if (loaded == null && fallback != null && fallback.exists() && fallback.length() > 64) {
                return ensureMaxSide(BitmapFactory.decodeFile(fallback.getAbsolutePath()), side);
            }
            return ensureMaxSide(loaded, side);
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
