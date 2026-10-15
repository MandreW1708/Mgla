package org.telegram.ui;

import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LauncherIconController.LauncherIcon;

/**
 * Maps launcher icons to built-in Mgla themes (with locked wallpapers).
 * <p>
 * Free for everyone: Основная → Фирм. amoled / Фирм. темная.
 * Other icon themes exist but stay locked until Mgla Premium ships.
 */
public final class MglaIconThemes {

    private MglaIconThemes() {
    }

    /** Icons that show the "Применить тему под иконку" row. */
    public static boolean hasThemeOffer(LauncherIcon icon) {
        return icon == LauncherIcon.DEFAULT
                || icon == LauncherIcon.DYMKA
                || icon == LauncherIcon.GRADIENT
                || icon == LauncherIcon.ZHIDKOST
                || icon == LauncherIcon.UFC
                || icon == LauncherIcon.ABSTRACTION
                || icon == LauncherIcon.IT
                || icon == LauncherIcon.RACE
                || icon == LauncherIcon.SUMMER
                || icon == LauncherIcon.WINTER
                || icon == LauncherIcon.GIRLS;
    }

    /** Основная themes are free; all other icon themes are locked for now. */
    public static boolean isThemeFree(LauncherIcon icon) {
        return icon == LauncherIcon.DEFAULT;
    }

    public static boolean canApplyTheme(LauncherIcon icon) {
        return hasThemeOffer(icon) && isThemeFree(icon);
    }

    /**
     * Applies the single theme linked to a non-DEFAULT icon.
     * Returns false if missing / locked / DEFAULT (use the firm picker instead).
     */
    public static boolean applyThemeForIcon(LauncherIcon icon) {
        if (icon == null || icon == LauncherIcon.DEFAULT || !canApplyTheme(icon)) {
            return false;
        }
        String key = themeKeyForIcon(icon);
        if (key == null) {
            return false;
        }
        Theme.ThemeInfo info = Theme.getTheme(key);
        if (info == null) {
            return false;
        }
        Theme.applyTheme(info);
        return true;
    }

    @Nullable
    public static LauncherIcon getCurrentIcon() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (LauncherIconController.isEnabled(icon)) {
                return icon;
            }
        }
        return LauncherIcon.DEFAULT;
    }

    /** Internal ThemeInfo.name for a non-DEFAULT icon theme. */
    @Nullable
    public static String themeKeyForIcon(LauncherIcon icon) {
        if (icon == null) {
            return null;
        }
        switch (icon) {
            case DYMKA:
                return Theme.MGLA_THEME_DYMKA;
            case GRADIENT:
                return Theme.MGLA_THEME_GRADIENT;
            case ZHIDKOST:
                return Theme.MGLA_THEME_ZHIDKOST;
            case UFC:
                return Theme.MGLA_THEME_UFC;
            case ABSTRACTION:
                return Theme.MGLA_THEME_ABSTRACTION;
            case IT:
                return Theme.MGLA_THEME_IT;
            case RACE:
                return Theme.MGLA_THEME_RACE;
            case SUMMER:
                return Theme.MGLA_THEME_SUMMER;
            case WINTER:
                return Theme.MGLA_THEME_WINTER;
            case GIRLS:
                return Theme.MGLA_THEME_GIRLS;
            default:
                return null;
        }
    }

    @Nullable
    public static String wallpaperAssetForTheme(String themeName) {
        if (themeName == null) {
            return null;
        }
        switch (themeName) {
            case Theme.MGLA_THEME_AMOLED:
            case Theme.MGLA_THEME_DARK:
                return Theme.MGLA_WALLPAPER_ASSET;
            case Theme.MGLA_THEME_DYMKA:
                return "wallpaper_dymka.png";
            case Theme.MGLA_THEME_GRADIENT:
                return "wallpaper_gradient.png";
            case Theme.MGLA_THEME_ZHIDKOST:
                return "wallpaper_zhidkost.png";
            case Theme.MGLA_THEME_UFC:
                return "wallpaper_ufc.png";
            case Theme.MGLA_THEME_ABSTRACTION:
                return "wallpaper_abstraction.png";
            case Theme.MGLA_THEME_IT:
                return "wallpaper_it.png";
            case Theme.MGLA_THEME_RACE:
                return "wallpaper_race.png";
            case Theme.MGLA_THEME_SUMMER:
                return "wallpaper_summer.png";
            case Theme.MGLA_THEME_WINTER:
                return "wallpaper_winter.png";
            case Theme.MGLA_THEME_GIRLS:
                return "wallpaper_girls.png";
            default:
                return null;
        }
    }

    @Nullable
    public static String displayNameForTheme(String themeName) {
        if (themeName == null) {
            return null;
        }
        switch (themeName) {
            case Theme.MGLA_THEME_AMOLED:
                return "Фирм. amoled";
            case Theme.MGLA_THEME_DARK:
                return "Фирм. темная";
            case Theme.MGLA_THEME_DYMKA:
                return "Дымка";
            case Theme.MGLA_THEME_GRADIENT:
                return "Градиент";
            case Theme.MGLA_THEME_ZHIDKOST:
                return "Жидкость";
            case Theme.MGLA_THEME_UFC:
                return "UFC";
            case Theme.MGLA_THEME_ABSTRACTION:
                return "Абстракция";
            case Theme.MGLA_THEME_IT:
                return "IT";
            case Theme.MGLA_THEME_RACE:
                return "Гонка";
            case Theme.MGLA_THEME_SUMMER:
                return "Лето";
            case Theme.MGLA_THEME_WINTER:
                return "Зима";
            case Theme.MGLA_THEME_GIRLS:
                return "Женский";
            default:
                return null;
        }
    }
}
