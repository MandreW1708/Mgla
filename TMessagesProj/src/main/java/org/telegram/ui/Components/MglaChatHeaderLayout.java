package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import org.telegram.ui.MglaCustomHeaderConfig;
import org.telegram.ui.MglaGlassConfig;

/**
 * Single source of truth for chat header visibility / geometry across
 * Standard, Clean, Telegram and Custom styles. Prevents overlapping elements.
 */
public final class MglaChatHeaderLayout {

    public final int style;
    public final boolean showBack;
    public final boolean showAvatar;
    public final boolean showTitle;
    public final boolean showSubtitle;
    public final boolean showMenu;
    public final boolean showCommunity;
    public final boolean showTimer;
    public final boolean drawGlassBack;
    public final boolean drawGlassTitle;
    public final boolean drawGlassMenu;
    public final boolean drawGlassPinned;
    public final boolean drawGlassTranslation;
    public final boolean showPinned;
    public final boolean showTranslation;
    public final boolean telegramLike; // avatar+title in one left pill, menu on right
    public final boolean titleCentered;
    public final boolean avatarAtStart;
    public final boolean avatarAtEnd;
    public final int avatarSizeDp;
    public final int titleTextSizeSp;
    public final int subtitleTextSizeSp;
    public final int headerExtraHeightDp;
    public final int gapDp;
    public final int padDp;
    /** Absolute corner radius in dp for glass pills (round≈capsule, square≈soft rect). */
    public final int glassRadiusDp;
    /** @deprecated use {@link #glassRadiusDp}; kept for preview factor math. */
    public final float glassCornerFactor;

    private MglaChatHeaderLayout(
            int style,
            boolean showBack,
            boolean showAvatar,
            boolean showTitle,
            boolean showSubtitle,
            boolean showMenu,
            boolean showCommunity,
            boolean showTimer,
            boolean drawGlassBack,
            boolean drawGlassTitle,
            boolean drawGlassMenu,
            boolean drawGlassPinned,
            boolean drawGlassTranslation,
            boolean showPinned,
            boolean showTranslation,
            boolean telegramLike,
            boolean titleCentered,
            boolean avatarAtStart,
            boolean avatarAtEnd,
            int avatarSizeDp,
            int titleTextSizeSp,
            int subtitleTextSizeSp,
            int headerExtraHeightDp,
            int gapDp,
            int padDp,
            int glassRadiusDp
    ) {
        this.style = style;
        this.showBack = showBack;
        this.showAvatar = showAvatar;
        this.showTitle = showTitle;
        this.showSubtitle = showSubtitle;
        this.showMenu = showMenu;
        this.showCommunity = showCommunity;
        this.showTimer = showTimer;
        this.drawGlassBack = drawGlassBack;
        this.drawGlassTitle = drawGlassTitle;
        this.drawGlassMenu = drawGlassMenu;
        this.drawGlassPinned = drawGlassPinned;
        this.drawGlassTranslation = drawGlassTranslation;
        this.showPinned = showPinned;
        this.showTranslation = showTranslation;
        this.telegramLike = telegramLike;
        this.titleCentered = titleCentered;
        this.avatarAtStart = avatarAtStart;
        this.avatarAtEnd = avatarAtEnd;
        this.avatarSizeDp = avatarSizeDp;
        this.titleTextSizeSp = titleTextSizeSp;
        this.subtitleTextSizeSp = subtitleTextSizeSp;
        this.headerExtraHeightDp = headerExtraHeightDp;
        this.gapDp = gapDp;
        this.padDp = padDp;
        this.glassRadiusDp = glassRadiusDp;
        this.glassCornerFactor = glassRadiusDp >= 20 ? 0.5f : (glassRadiusDp >= 14 ? 0.35f : 0.18f);
    }

    public static MglaChatHeaderLayout resolve() {
        final int style = MglaGlassConfig.getChatHeaderStyle();
        switch (style) {
            case MglaGlassConfig.HEADER_STYLE_CLEAN:
                return forClean();
            case MglaGlassConfig.HEADER_STYLE_TELEGRAM:
                return forTelegram();
            case MglaGlassConfig.HEADER_STYLE_CUSTOM:
                return forCustom();
            case MglaGlassConfig.HEADER_STYLE_STANDARD:
            default:
                return forStandard();
        }
    }

    /** Always builds from {@link MglaCustomHeaderConfig} (settings preview). */
    public static MglaChatHeaderLayout resolveCustom() {
        return forCustom();
    }

    private static MglaChatHeaderLayout forStandard() {
        return new MglaChatHeaderLayout(
                MglaGlassConfig.HEADER_STYLE_STANDARD,
                true, true, true, true, true, false, true,
                true, true, true, true, true,
                true, true,
                false, true, false, true,
                42, 17, 13, 0, 4, 10, 23
        );
    }

    private static MglaChatHeaderLayout forClean() {
        // Clean «Убрать фон» only removes glass fill — panels/controls stay visible.
        final boolean hideBack = MglaGlassConfig.isCleanHeaderHideBackBtnEnabled();
        final boolean hideTitle = MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled();
        final boolean hidePinned = MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled();
        final boolean hideTranslation = MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled();
        return new MglaChatHeaderLayout(
                MglaGlassConfig.HEADER_STYLE_CLEAN,
                true, true, true, true, true, false, true,
                !hideBack, !hideTitle, false, !hidePinned, !hideTranslation,
                true, true,
                false, true, false, true,
                42, 17, 13, 0, 4, 10, 23
        );
    }

    private static MglaChatHeaderLayout forTelegram() {
        return new MglaChatHeaderLayout(
                MglaGlassConfig.HEADER_STYLE_TELEGRAM,
                true, true, true, true, true, true, true,
                true, true, true, true, true,
                true, true,
                true, false, true, false,
                42, 17, 13, 0, 4, 10, 23
        );
    }

    private static MglaChatHeaderLayout forCustom() {
        // Toggles stay independent; layout only allocates space for visible pieces.
        boolean showBack = MglaCustomHeaderConfig.isShowBack();
        boolean showAvatar = MglaCustomHeaderConfig.isShowAvatar();
        boolean showTitle = MglaCustomHeaderConfig.isShowTitle();
        boolean showSubtitle = MglaCustomHeaderConfig.isShowSubtitle();
        boolean showMenu = MglaCustomHeaderConfig.isShowMenu();
        boolean showCommunity = MglaCustomHeaderConfig.isShowCommunity();
        boolean showTimer = MglaCustomHeaderConfig.isShowTimer();
        boolean showPinned = MglaCustomHeaderConfig.isShowPinned();
        boolean showTranslation = MglaCustomHeaderConfig.isShowTranslation();

        boolean glassBack = MglaCustomHeaderConfig.isGlassBack();
        boolean glassTitle = MglaCustomHeaderConfig.isGlassTitle();
        boolean glassMenu = MglaCustomHeaderConfig.isGlassMenu();
        boolean glassPinned = MglaCustomHeaderConfig.isGlassPinned();
        boolean glassTranslation = MglaCustomHeaderConfig.isGlassTranslation();

        int avatarSide = MglaCustomHeaderConfig.getAvatarSide();
        if (!showAvatar) {
            avatarSide = MglaCustomHeaderConfig.AVATAR_SIDE_HIDDEN;
        }
        boolean avatarAtStart = showAvatar && avatarSide == MglaCustomHeaderConfig.AVATAR_SIDE_START;
        boolean avatarAtEnd = showAvatar && avatarSide == MglaCustomHeaderConfig.AVATAR_SIDE_END;
        boolean titleCentered = MglaCustomHeaderConfig.getTitleAlign() == MglaCustomHeaderConfig.TITLE_ALIGN_CENTER;

        // Telegram-like content packing when avatar is on the start of the title pill.
        boolean telegramLike = avatarAtStart;

        int avatarSizeDp;
        switch (MglaCustomHeaderConfig.getAvatarSize()) {
            case MglaCustomHeaderConfig.AVATAR_SIZE_S: avatarSizeDp = 32; break;
            case MglaCustomHeaderConfig.AVATAR_SIZE_L: avatarSizeDp = 48; break;
            case MglaCustomHeaderConfig.AVATAR_SIZE_XL: avatarSizeDp = 56; break;
            case MglaCustomHeaderConfig.AVATAR_SIZE_M:
            default: avatarSizeDp = 42; break;
        }

        boolean largeTitle = MglaCustomHeaderConfig.getTitleSize() == MglaCustomHeaderConfig.TITLE_SIZE_LARGE;
        int titleSp = largeTitle ? 19 : 17;
        int subtitleSp = largeTitle ? 14 : 13;

        int extraH;
        switch (MglaCustomHeaderConfig.getHeaderHeight()) {
            case MglaCustomHeaderConfig.HEIGHT_COMPACT: extraH = -4; break;
            case MglaCustomHeaderConfig.HEIGHT_TALL: extraH = 18; break;
            case MglaCustomHeaderConfig.HEIGHT_NORMAL:
            default: extraH = 0; break;
        }
        if (MglaCustomHeaderConfig.getLayoutPreset() == MglaCustomHeaderConfig.PRESET_HERO) {
            extraH = Math.max(extraH, 22);
        }

        int radiusDp;
        switch (MglaCustomHeaderConfig.getGlassRadius()) {
            case MglaCustomHeaderConfig.RADIUS_SOFT: radiusDp = 14; break;
            case MglaCustomHeaderConfig.RADIUS_SQUARE: radiusDp = 8; break;
            case MglaCustomHeaderConfig.RADIUS_ROUND:
            default: radiusDp = 23; break;
        }

        return new MglaChatHeaderLayout(
                MglaGlassConfig.HEADER_STYLE_CUSTOM,
                showBack, showAvatar, showTitle, showSubtitle, showMenu, showCommunity, showTimer,
                glassBack, glassTitle, glassMenu, glassPinned, glassTranslation,
                showPinned, showTranslation,
                telegramLike, titleCentered, avatarAtStart, avatarAtEnd,
                avatarSizeDp, titleSp, subtitleSp, extraH,
                MglaCustomHeaderConfig.getGapDp(),
                MglaCustomHeaderConfig.getPadDp(),
                radiusDp
        );
    }

    public int avatarSizePx() {
        return dp(avatarSizeDp);
    }

    public int gapPx() {
        return dp(gapDp);
    }

    public int padPx() {
        return dp(padDp);
    }

    public int extraHeightPx() {
        return dp(headerExtraHeightDp);
    }

    public int glassRadiusPx() {
        return dp(glassRadiusDp);
    }
}
