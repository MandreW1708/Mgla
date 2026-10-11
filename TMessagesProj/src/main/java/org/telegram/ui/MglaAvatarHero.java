package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Shared-element style avatar flight: dialogs list → chat header.
 */
public final class MglaAvatarHero {

    private static final long PENDING_TTL_MS = 1200;

    private static long pendingDialogId;
    private static long pendingUntil;
    private static final RectF pendingSrc = new RectF();
    private static float pendingRadius;
    private static String pendingKey;
    private static Bitmap pendingBitmap;
    private static boolean running;

    private MglaAvatarHero() {
    }

    public static void prepareFromDialogCell(DialogCell cell, long dialogId) {
        if (!MglaGlassConfig.isDialogsAvatarHeroEnabled() || cell == null || dialogId == 0 || running) {
            clear();
            return;
        }
        ImageReceiver receiver = cell.avatarImage;
        if (receiver == null || !receiver.hasNotThumb()) {
            clear();
            return;
        }
        int[] loc = new int[2];
        cell.getLocationOnScreen(loc);
        float l = loc[0] + receiver.getImageX();
        float t = loc[1] + receiver.getImageY();
        pendingSrc.set(l, t, l + receiver.getImageWidth(), t + receiver.getImageHeight());
        if (pendingSrc.width() < dp(20) || pendingSrc.height() < dp(20)) {
            clear();
            return;
        }
        pendingRadius = Math.min(pendingSrc.width(), pendingSrc.height()) / 2f;
        int[] rr = receiver.getRoundRadius();
        if (rr != null && rr.length > 0 && rr[0] > 0) {
            pendingRadius = rr[0];
        }
        pendingDialogId = dialogId;
        pendingUntil = System.currentTimeMillis() + PENDING_TTL_MS;
        pendingKey = receiver.getImageKey();
        pendingBitmap = null;
        ImageReceiver.BitmapHolder holder = receiver.getBitmapSafe();
        if (holder != null && holder.bitmap != null && !holder.bitmap.isRecycled()) {
            pendingBitmap = holder.bitmap;
            if (pendingKey != null) {
                ImageLoader.getInstance().putImageToCache(new BitmapDrawable(holder.bitmap), pendingKey, false);
            }
        } else {
            clear();
        }
    }

    public static boolean hasPending(long dialogId) {
        return MglaGlassConfig.isDialogsAvatarHeroEnabled()
                && !running
                && pendingDialogId != 0
                && pendingDialogId == dialogId
                && System.currentTimeMillis() <= pendingUntil
                && pendingBitmap != null
                && !pendingBitmap.isRecycled();
    }

    public static void clear() {
        pendingDialogId = 0;
        pendingUntil = 0;
        pendingKey = null;
        pendingBitmap = null;
        pendingSrc.setEmpty();
    }

    public static void playIfNeeded(BaseFragment fragment, ChatAvatarContainer avatarContainer, long dialogId) {
        if (!(fragment instanceof ChatActivity) || avatarContainer == null || !hasPending(dialogId)) {
            clear();
            return;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            clear();
            return;
        }
        ViewGroup root = null;
        if (activity instanceof LaunchActivity) {
            root = ((LaunchActivity) activity).drawerLayoutContainer;
        }
        if (root == null && activity.getWindow() != null) {
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof ViewGroup) {
                root = (ViewGroup) decor;
            }
        }
        if (root == null) {
            clear();
            return;
        }

        BackupImageView dest = avatarContainer.getAvatarImageView();
        if (dest == null) {
            clear();
            return;
        }

        running = true;
        final RectF srcScreen = new RectF(pendingSrc);
        final float srcRadius = pendingRadius;
        final Bitmap bitmap = pendingBitmap;
        clear();

        dest.setAlpha(0f);
        final ViewGroup overlayRoot = root;

        // Wait until the header avatar has a real size, then fly while tracking it live
        // so the end point matches after the fragment slide settles (no final snap).
        final RectF probe = new RectF();
        final ViewTreeObserver.OnPreDrawListener[] holder = new ViewTreeObserver.OnPreDrawListener[1];
        holder[0] = () -> {
            if (!readDestRect(dest, overlayRoot, probe)) {
                return true;
            }
            dest.getViewTreeObserver().removeOnPreDrawListener(holder[0]);
            RectF src = new RectF(srcScreen);
            int[] rootLoc = new int[2];
            overlayRoot.getLocationOnScreen(rootLoc);
            src.offset(-rootLoc[0], -rootLoc[1]);
            startOverlay(overlayRoot, bitmap, src, srcRadius, dest);
            return true;
        };
        dest.getViewTreeObserver().addOnPreDrawListener(holder[0]);
        dest.invalidate();
    }

    /** Fills {@code out} with destination image bounds in overlay-root coordinates. */
    private static boolean readDestRect(BackupImageView dest, ViewGroup root, RectF out) {
        if (dest.getWidth() <= 0 || dest.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        int[] rootLoc = new int[2];
        dest.getLocationOnScreen(loc);
        root.getLocationOnScreen(rootLoc);
        ImageReceiver ir = dest.getImageReceiver();
        float l, t, r, b;
        if (ir != null && ir.getImageWidth() > 0 && ir.getImageHeight() > 0) {
            l = loc[0] + ir.getImageX() - rootLoc[0];
            t = loc[1] + ir.getImageY() - rootLoc[1];
            r = l + ir.getImageWidth();
            b = t + ir.getImageHeight();
        } else {
            l = loc[0] - rootLoc[0];
            t = loc[1] - rootLoc[1];
            r = l + dest.getWidth();
            b = t + dest.getHeight();
        }
        out.set(l, t, r, b);
        return out.width() >= dp(8) && out.height() >= dp(8);
    }

    private static float readDestRadius(BackupImageView dest, RectF destRect) {
        ImageReceiver ir = dest.getImageReceiver();
        if (ir != null) {
            int[] rr = ir.getRoundRadius();
            if (rr != null && rr.length > 0 && rr[0] > 0) {
                return rr[0];
            }
        }
        return Math.min(destRect.width(), destRect.height()) / 2f;
    }

    private static void startOverlay(ViewGroup root, Bitmap bitmap, RectF src, float srcRadius, BackupImageView dest) {
        final RectF liveDst = new RectF();
        if (!readDestRect(dest, root, liveDst)) {
            dest.setAlpha(1f);
            running = false;
            return;
        }
        final HeroOverlay overlay = new HeroOverlay(root.getContext(), bitmap, src, srcRadius);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setClickable(false);
        overlay.setFocusable(false);
        root.addView(overlay);

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(340);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            if (!readDestRect(dest, root, liveDst)) {
                return;
            }
            overlay.setProgress(p, liveDst, readDestRadius(dest, liveDst));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // One last sync so reveal matches the overlay exactly.
                if (readDestRect(dest, root, liveDst)) {
                    overlay.setProgress(1f, liveDst, readDestRadius(dest, liveDst));
                }
                dest.setAlpha(1f);
                AndroidUtilities.removeFromParent(overlay);
                running = false;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                dest.setAlpha(1f);
                AndroidUtilities.removeFromParent(overlay);
                running = false;
            }
        });
        overlay.setProgress(0f, liveDst, readDestRadius(dest, liveDst));
        animator.start();
    }

    private static final class HeroOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();
        private final RectF drawRect = new RectF();
        private final RectF src;
        private final float srcRadius;
        private final Bitmap bitmap;
        private float radius;

        HeroOverlay(android.content.Context context, Bitmap bitmap, RectF src, float srcRadius) {
            super(context);
            this.bitmap = bitmap;
            this.src = new RectF(src);
            this.srcRadius = srcRadius;
            this.radius = srcRadius;
        }

        void setProgress(float p, RectF dst, float dstRadius) {
            drawRect.set(
                    lerp(src.left, dst.left, p),
                    lerp(src.top, dst.top, p),
                    lerp(src.right, dst.right, p),
                    lerp(src.bottom, dst.bottom, p)
            );
            radius = lerp(srcRadius, dstRadius, p);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            path.rewind();
            path.addRoundRect(drawRect, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(bitmap, null, drawRect, paint);
            canvas.restoreToCount(save);
        }
    }
}
