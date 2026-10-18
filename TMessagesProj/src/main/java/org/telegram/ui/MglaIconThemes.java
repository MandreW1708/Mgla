package org.telegram.ui;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LauncherIconController.LauncherIcon;

/**
 * Maps launcher icons to built-in Mgla themes (with locked wallpapers + accent palettes).
 * <p>
 * Free for everyone: Основная → Фирм. amoled / Фирм. темная.
 * Other icon themes are available now; Premium gating can be re-enabled later.
 */
public final class MglaIconThemes {

    /** Accent / bubble colors for a Mgla theme. */
    public static final class Palette {
        /** UI accent (links, controls, hue remap). */
        public final int accent;
        /** Optional second accent (0 = unused). */
        public final int accent2;
        /** Outgoing bubble start. */
        public final int bubble;
        /** Outgoing bubble gradient end (0 = flat bubble). */
        public final int bubble2;
        /** true → amoled.attheme (pure black), false → night.attheme (dark gray). */
        public final boolean amoledBase;

        public Palette(int accent, int accent2, int bubble, int bubble2, boolean amoledBase) {
            this.accent = accent;
            this.accent2 = accent2;
            this.bubble = bubble;
            this.bubble2 = bubble2;
            this.amoledBase = amoledBase;
        }
    }

    private static final String PREFS = "mgla_config";
    private static final String PREF_AUTO_APPLY_ICON_THEME = "auto_apply_icon_theme";

    /** Gradient / UFC accent (exact; forced in Theme.applyMglaAccent — no hue wash). */
    private static final int GRADIENT_UFC_ACCENT = 0xFF4A0004;
    /** Dymka accent. */
    private static final int DYMKA_ACCENT = 0xFF290303;
    /** Жидкость accent. */
    private static final int ZHIDKOST_ACCENT = 0xFF1F0202;
    /** Dymka: switches / buttons (same family as accent). */
    public static final int DYMKA_SWITCH = DYMKA_ACCENT;
    /** Dymka: section headers. */
    public static final int DYMKA_HEADER = DYMKA_ACCENT;
    private static final int RED = 0xFFE53935;
    private static final int GRAY = 0xFF9AA0A6;
    // Darker forest green — was too neon at Material green.
    private static final int GREEN = 0xFF0F4A28;
    private static final int SKY = 0xFF38BDF8;
    private static final int ORANGE = 0xFFF97316;
    private static final int WINTER_DEEP = 0xFF1E3A8A;
    private static final int WINTER_ICE = 0xFF60A5FA;
    private static final int PINK = 0xFFEC4899;
    private static final int PURPLE = 0xFFA855F7;

    private static Boolean autoApplyCached;

    // Firm amoled stays pure black; icon themes use night.attheme (dark gray), like «Фирм. темная».
    private static final Palette FIRM = new Palette(
            Theme.MGLA_ACCENT_COLOR, 0, Theme.MGLA_ACCENT_COLOR, 0, true);
    private static final Palette DYMKA = new Palette(DYMKA_ACCENT, 0, DYMKA_ACCENT, 0, false);
    private static final Palette GRADIENT = new Palette(GRADIENT_UFC_ACCENT, 0, GRADIENT_UFC_ACCENT, 0, false);
    private static final Palette ZHIDKOST = new Palette(ZHIDKOST_ACCENT, 0, ZHIDKOST_ACCENT, 0, false);
    private static final Palette UFC = new Palette(GRADIENT_UFC_ACCENT, 0, GRADIENT_UFC_ACCENT, 0, false);
    private static final Palette ABSTRACTION = new Palette(GRAY, 0, GRAY, 0, false);
    private static final Palette IT = new Palette(GREEN, 0, GREEN, 0, false);
    private static final Palette RACE = new Palette(RED, 0, RED, 0, false);
    private static final Palette SUMMER = new Palette(SKY, ORANGE, ORANGE, SKY, false);
    private static final Palette WINTER = new Palette(WINTER_DEEP, WINTER_ICE, WINTER_DEEP, WINTER_ICE, false);
    private static final Palette GIRLS = new Palette(PINK, PURPLE, PINK, PURPLE, false);

    private MglaIconThemes() {
    }

    /** Palette for a Mgla theme key; firm blue for unknown / firm themes. */
    public static Palette paletteForTheme(@Nullable String themeName) {
        if (themeName == null) {
            return FIRM;
        }
        switch (themeName) {
            case Theme.MGLA_THEME_AMOLED:
                return FIRM;
            case Theme.MGLA_THEME_DARK:
                return new Palette(Theme.MGLA_ACCENT_COLOR, 0, Theme.MGLA_ACCENT_COLOR, 0, false);
            case Theme.MGLA_THEME_DYMKA:
                return DYMKA;
            case Theme.MGLA_THEME_GRADIENT:
                return GRADIENT;
            case Theme.MGLA_THEME_ZHIDKOST:
                return ZHIDKOST;
            case Theme.MGLA_THEME_UFC:
                return UFC;
            case Theme.MGLA_THEME_ABSTRACTION:
                return ABSTRACTION;
            case Theme.MGLA_THEME_IT:
                return IT;
            case Theme.MGLA_THEME_RACE:
                return RACE;
            case Theme.MGLA_THEME_SUMMER:
                return SUMMER;
            case Theme.MGLA_THEME_WINTER:
                return WINTER;
            case Theme.MGLA_THEME_GIRLS:
                return GIRLS;
            default:
                return FIRM;
        }
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

    /**
     * Until Mgla Premium ships, every icon theme is available to everyone.
     * Keep this hook so Premium can gate non-DEFAULT icons later.
     */
    public static boolean isThemeFree(LauncherIcon icon) {
        return hasThemeOffer(icon);
    }

    public static boolean canApplyTheme(LauncherIcon icon) {
        return hasThemeOffer(icon);
    }

    public static boolean isAutoApplyEnabled() {
        if (autoApplyCached == null) {
            SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
            autoApplyCached = p.getBoolean(PREF_AUTO_APPLY_ICON_THEME, false);
        }
        return autoApplyCached;
    }

    public static void setAutoApplyEnabled(boolean enabled) {
        if (autoApplyCached != null && autoApplyCached == enabled) {
            return;
        }
        autoApplyCached = enabled;
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0)
                .edit()
                .putBoolean(PREF_AUTO_APPLY_ICON_THEME, enabled)
                .commit(); // commit: must be on disk before launcher-icon swap may kill the process
        if (enabled) {
            // Persist theme for the currently selected icon right away.
            applyThemeForIconQuiet(getCurrentIcon());
        }
    }

    /**
     * Applies and persists the theme for {@code icon}.
     * Safe to call before {@link LauncherIconController#setIcon} — uses commit-style theme save
     * and does not rebuild UI (the process often dies when the launcher icon changes).
     * <p>
     * Pins the same theme as both day and night. Otherwise AUTO_NIGHT_TYPE_SYSTEM (default on
     * API 29+) keeps showing the last night theme (usually «Dark Blue») after restart — common
     * on Realme/ColorOS where system dark mode stays on.
     */
    public static boolean applyThemeForIconQuiet(LauncherIcon icon) {
        if (icon == null || !hasThemeOffer(icon)) {
            return false;
        }
        try {
            Theme.ThemeInfo info;
            if (icon == LauncherIcon.DEFAULT) {
                // Основная → фирм. amoled by default (picker is only for manual firm choice).
                info = Theme.getTheme(Theme.MGLA_THEME_AMOLED);
            } else {
                String key = themeKeyForIcon(icon);
                info = key != null ? Theme.getTheme(key) : null;
            }
            if (info == null) {
                return false;
            }
            // Day theme + active colors.
            Theme.applyTheme(info, true, false);
            // Pin as night theme so system dark mode does not fall back to Dark Blue.
            Theme.setCurrentNightTheme(info);
            // applyTheme / saveAutoNight use apply() (async). Icon swap / OEM process kill
            // often happens before the flush — force both keys to disk.
            MessagesController.getGlobalMainSettings().edit()
                    .putString("theme", info.getKey())
                    .putString("nighttheme", info.getKey())
                    .commit();
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Applies the theme for a non-DEFAULT icon (or firm amoled for DEFAULT).
     * Prefer {@link #applyThemeForIconQuiet} before launcher-icon swaps.
     */
    public static boolean applyThemeForIcon(LauncherIcon icon) {
        return applyThemeForIconQuiet(icon);
    }

    /**
     * On cold start / resume: if auto-apply is on, ensure the <em>active</em> theme matches the
     * current icon. Must use {@link Theme#getActiveTheme()} — {@link Theme#getCurrentTheme()} is
     * only the day slot and stays correct while night mode still shows Dark Blue.
     */
    public static void syncThemeToCurrentIconIfNeeded() {
        if (!isAutoApplyEnabled()) {
            return;
        }
        LauncherIcon icon = getCurrentIcon();
        if (!hasThemeOffer(icon)) {
            return;
        }
        Theme.ThemeInfo active = Theme.getActiveTheme();
        String expectedKey = icon == LauncherIcon.DEFAULT
                ? Theme.MGLA_THEME_AMOLED
                : themeKeyForIcon(icon);
        if (expectedKey == null || active == null) {
            return;
        }
        // For DEFAULT also accept firm dark as a valid match (user may have picked it earlier).
        if (icon == LauncherIcon.DEFAULT) {
            if (Theme.MGLA_THEME_AMOLED.equals(active.name) || Theme.MGLA_THEME_DARK.equals(active.name)) {
                return;
            }
        } else if (expectedKey.equals(active.name)) {
            return;
        }
        applyThemeForIconQuiet(icon);
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
