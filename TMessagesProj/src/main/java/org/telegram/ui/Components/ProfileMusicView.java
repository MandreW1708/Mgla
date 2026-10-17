package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ActionBar.Theme.multAlpha;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ProfileActivity;

public class ProfileMusicView extends View {

    // Same visible height as ProfileActionsView buttons (74 - top 8 - ypadding 12).
    public static final int BUTTON_HEIGHT_DP = 54;
    public static final int HEIGHT_DP = BUTTON_HEIGHT_DP + 12; // + bottom padding like actions

    private final Theme.ResourcesProvider resourcesProvider;
    private final PorterDuffColorFilter filterColorWhite = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
    private final PorterDuffColorFilter filterColorBlack = new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);

    private Text author, title;
    private final Drawable icon;
    private final ImageReceiver coverReceiver = new ImageReceiver(this);

    private final RectF rect = new RectF();
    private final RectF coverRect = new RectF();
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final ButtonBounce bounce = new ButtonBounce(this);
    private final float xpadding = dpf2(14);
    private final float ypadding = dpf2(12);
    private final float roundRadius = dp(16);
    private final float coverInset = dpf2(8);
    private final float coverRadius = dp(10);

    public ProfileMusicView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        icon = context.getResources().getDrawable(R.drawable.files_music).mutate();
        coverReceiver.setInvalidateAll(true);
        coverReceiver.setCrossfadeWithOldImage(true);
        coverReceiver.setCrossfadeDuration(120);

        setColor(null);
        setText("Author", "Title");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        coverReceiver.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        coverReceiver.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(
                dp(HEIGHT_DP),
                MeasureSpec.EXACTLY
            )
        );
    }

    private int textColor = Color.WHITE;
    private float parentExpanded;
    private int backgroundColor;
    private boolean withShadows;

    public void setColor(MessagesController.PeerColor peerColor) {
        int color1, color2;
        if (peerColor == null) {
            color1 = color2 = Theme.getColor(Theme.key_actionBarDefault, resourcesProvider);
        } else {
            color1 = peerColor.getBgColor1(Theme.isCurrentThemeDark());
            color2 = peerColor.getBgColor2(Theme.isCurrentThemeDark());
        }

        if (peerColor == null) {
            backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
            withShadows = true;
        } else {
            backgroundColor = Theme.adaptHSV(ColorUtils.blendARGB(color1, color2, .15f), +.04f, -.09f);
            withShadows = false;
        }
        backgroundPaint.setColor(backgroundColor);
        checkTextColor();
    }

    private void checkTextColor() {
        final boolean useBlackText = parentExpanded < 0.8f && AndroidUtilities.computePerceivedBrightness(backgroundColor) > 0.85f;
        textColor = useBlackText ? Color.BLACK : Color.WHITE;
        icon.setColorFilter(useBlackText ? filterColorBlack : filterColorWhite);
        invalidate();
    }

    public void setParentExpanded(float expanded) {
        if (parentExpanded != expanded) {
            parentExpanded = expanded;
            checkTextColor();
            invalidate();
        }
    }

    public void setMusicDocument(TLRPC.Document document) {
        CharSequence authorText = getAuthor(document);
        CharSequence titleText = getTitle(document);
        if (TextUtils.isEmpty(titleText)) {
            titleText = getString(R.string.AudioUnknownTitle);
        }
        if (TextUtils.isEmpty(authorText)) {
            authorText = getString(R.string.AudioUnknownArtist);
        }
        setText(authorText, titleText);
        bindCover(document);
    }

    private void bindCover(TLRPC.Document document) {
        if (document == null) {
            coverReceiver.clearImage();
            return;
        }
        TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90);
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        final ImageLocation thumbLocation = thumb != null ? ImageLocation.getForDocument(thumb, document) : null;
        final String artworkUrl = MessageObject.getArtworkUrl(document, true);
        if (!TextUtils.isEmpty(artworkUrl)) {
            coverReceiver.setImage(ImageLocation.getForPath(artworkUrl), "38_38", thumbLocation, "38_38", icon, 0, null, null, 0);
        } else if (thumbLocation != null) {
            coverReceiver.setImage(thumbLocation, "38_38", null, null, icon, 0, null, null, 0);
        } else {
            coverReceiver.setImageBitmap(icon);
        }
    }

    public static CharSequence getTitle(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                String title = attribute.title;
                if (title == null || title.length() == 0) {
                    title = FileLoader.getDocumentFileName(document);
                }
                return title;
            }
        }
        String fileName = FileLoader.getDocumentFileName(document);
        if (!TextUtils.isEmpty(fileName)) {
            return fileName;
        }
        return null;
    }

    public static CharSequence getAuthor(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (!attribute.voice) {
                    return attribute.performer;
                }
            }
        }
        return null;
    }

    public void setText(CharSequence author, CharSequence title) {
        this.author = new Text(author, 12);
        this.title = new Text(title, 13, AndroidUtilities.bold());

        setContentDescription(getString(R.string.AccDescrProfileMusic) + " " + title + " — " + author);
    }

    private ProfileActivity.AvatarImageView avatarView;
    private boolean ignoreRect = false;
    private RenderNode renderNode;
    private float renderNodeScale;
    private float renderNodeTranslateY;

    public void drawingBlur(boolean drawing) {
        if (ignoreRect != drawing || renderNode != null) {
            ignoreRect = drawing;
            renderNode = null;
            avatarView = null;
            invalidate();
        }
    }

    public void drawingBlur(RenderNode renderNode, ProfileActivity.AvatarImageView avatarView, float scale, float dy) {
        this.ignoreRect = false;
        this.renderNode = renderNode;
        this.avatarView = avatarView;
        this.renderNodeScale = scale;
        this.renderNodeTranslateY = dy;
        invalidate();
    }

    private float currentHeight;

    public void updatePosition(float y, float newHeight) {
        currentHeight = newHeight;
        setTranslationY(y);
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final float alpha = Utilities.clamp01(currentHeight / dp(24));
        if (alpha <= 0) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            bounce.setPressed(rect.contains(event.getX(), event.getY()));
        } else if (event.getAction() == MotionEvent.ACTION_MOVE && bounce.isPressed()) {
            if (!rect.contains(event.getX(), event.getY())) {
                bounce.setPressed(false);
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            bounce.setPressed(false);
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (bounce.isPressed()) {
                performClick();
            }
            bounce.setPressed(false);
        }
        return bounce.isPressed();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (this.author == null || this.title == null) return;

        final float alpha = Utilities.clamp01(currentHeight / dp(24));
        final float scale = bounce.getScale(0.02f);
        if (alpha <= 0) return;

        final float availableHeight = Math.max(currentHeight, 1f);
        final float cardHeight = Math.min(dp(BUTTON_HEIGHT_DP), Math.max(0f, availableHeight - ypadding));
        if (cardHeight <= 0) return;

        rect.set(
            xpadding,
            0,
            getWidth() - xpadding,
            cardHeight
        );

        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), rect.centerY());

        if (withShadows && SharedConfig.shadowsInSections) {
            backgroundPaint.setShadowLayer(dpf2(2), 0, dpf2(0.33f), multAlpha(0x0a000000, alpha));
            strokePaint.setShadowLayer(dpf2(0.33f), 0, 0, multAlpha(0x0c000000, alpha));
            strokePaint.setColor(0);
        } else {
            backgroundPaint.setShadowLayer(0, 0, 0, 0);
        }
        int wasAlpha = backgroundPaint.getAlpha();
        backgroundPaint.setAlpha((int) (wasAlpha * alpha));
        if (withShadows && SharedConfig.shadowsInSections) {
            canvas.drawRoundRect(rect, roundRadius, roundRadius, strokePaint);
        }
        if (!ignoreRect) {
            canvas.drawRoundRect(rect, roundRadius, roundRadius, backgroundPaint);
        }
        backgroundPaint.setAlpha(wasAlpha);

        clipPath.rewind();
        clipPath.addRoundRect(rect, roundRadius, roundRadius, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);
        if (!ignoreRect && renderNode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated()) {
            canvas.save();
            canvas.translate(0f, renderNodeTranslateY);
            canvas.scale(renderNodeScale, renderNodeScale);
            canvas.drawRenderNode(renderNode);
            canvas.restore();
        }

        final float coverSize = Math.max(0f, cardHeight - coverInset * 2f);
        coverRect.set(
            rect.left + coverInset,
            rect.top + coverInset,
            rect.left + coverInset + coverSize,
            rect.top + coverInset + coverSize
        );
        coverReceiver.setImageCoords(coverRect);
        coverReceiver.setRoundRadius((int) coverRadius);
        coverReceiver.setAlpha(alpha);
        coverReceiver.draw(canvas);

        final float textLeft = coverRect.right + dp(10);
        final float textRight = rect.right - dp(12);
        final float textWidth = Math.max(0f, textRight - textLeft);
        this.title.ellipsize(textWidth);
        this.author.ellipsize(textWidth);

        final float titleY = rect.centerY() - dp(8);
        final float authorY = rect.centerY() + dp(9);
        this.title.draw(canvas, textLeft, titleY, textColor, alpha);
        this.author.draw(canvas, textLeft, authorY, textColor, 0.75f * alpha);

        canvas.restore();
        canvas.restore();
    }
}
