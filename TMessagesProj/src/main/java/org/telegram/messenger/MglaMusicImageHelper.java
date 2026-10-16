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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * High-performance async image loader with in-memory LRU cache and rounded corner rendering
 * specifically designed for Yandex Music album covers and avatars in Mgla.
 */
public final class MglaMusicImageHelper {

    private static final int CACHE_SIZE = 20 * 1024 * 1024; // 20 MB memory cache
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

    public static void bindCover(ImageView imageView, String url, int roundRadiusDp, int placeholderResId) {
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

        final String cacheKey = url + "_r" + roundRadiusDp;
        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            imageView.setTag(cacheKey);
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setTag(cacheKey);
        if (placeholderResId != 0) {
            imageView.setImageResource(placeholderResId);
        }

        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection conn = MglaDirectHttp.open(url);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "YandexMusic/2024");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    InputStream is = conn.getInputStream();
                    Bitmap raw = BitmapFactory.decodeStream(is);
                    is.close();

                    if (raw != null) {
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

                        memoryCache.put(cacheKey, processed);

                        AndroidUtilities.runOnUIThread(() -> {
                            Object tag = imageView.getTag();
                            if (cacheKey.equals(tag)) {
                                imageView.setImageBitmap(processed);
                            }
                        });
                        return;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static void bindCircleCover(ImageView imageView, String url, int placeholderResId) {
        bindCover(imageView, url, -1, placeholderResId);
    }

    /** Blocking load for notification / media session artwork. Call off the main thread. */
    public static Bitmap loadBitmapSync(String url, int maxSide) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            HttpURLConnection conn = MglaDirectHttp.open(url);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "YandexMusic/2024");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream is = conn.getInputStream();
            Bitmap raw = BitmapFactory.decodeStream(is);
            is.close();
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
                return scaled;
            }
            return raw;
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
