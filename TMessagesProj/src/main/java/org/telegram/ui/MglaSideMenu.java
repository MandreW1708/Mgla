package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.MglaSideMenuConfig;
import org.telegram.messenger.MglaSpyConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SavedMessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.web.WebBrowserSettings;

import java.util.ArrayList;
import java.util.Collections;

public class MglaSideMenu extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int NAV_PROFILE = 0;
    private static final int NAV_SAVED = 1;
    private static final int NAV_NEW_GROUP = 2;
    private static final int NAV_CONTACTS = 3;
    private static final int NAV_CALLS = 4;
    private static final int NAV_SETTINGS = 5;
    private static final int NAV_QR = 6;
    private static final int NAV_BROWSER = 7;

    private final LaunchActivity activity;
    private View scrim;
    private LinearLayout panel;
    private boolean isOpen;
    private boolean isTablet;
    private boolean observerRegistered;

    private BackupImageView avatarView;
    private TextView nameText;
    private TextView usernameText;
    private TextView phoneText;
    private ImageView themeToggleBtn;
    private TextView mglaSettingsBtn;
    private TextView ghostButtonLabel;
    private View ghostButton;
    private LinearLayout accountsBlock;
    private LinearLayout accountsListContainer;
    private LinearLayout profileSection;
    private LinearLayout navContainer;

    private int panelWidth;
    private float openProgress;
    private ValueAnimator progressAnimator;
    private VelocityTracker velocityTracker;
    private int trackingPointerId = -1;
    private float trackingStartRawX;
    private float trackingStartRawY;
    private boolean maybeTracking;
    private boolean isTracking;
    private boolean trackingOpenGesture;
    private boolean trackingCloseGesture;

    private static final CubicBezierInterpolator MENU_INTERPOLATOR = CubicBezierInterpolator.EASE_OUT_QUINT;
    private static final long MENU_ANIMATION_DURATION = 280;

    public MglaSideMenu(Context context, LaunchActivity activity) {
        super(context);
        this.activity = activity;
        this.isTablet = AndroidUtilities.isTablet();
        panelWidth = isTablet ? dp(320) : Math.min(dp(320), AndroidUtilities.displaySize.x - dp(56));

        scrim = new View(context);
        scrim.setBackgroundColor(0x80000000);
        scrim.setVisibility(GONE);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> close(true));
        addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        panel = buildPanel(context);
        addView(panel, LayoutHelper.createFrame(panelWidth / dp(1), LayoutHelper.MATCH_PARENT, Gravity.LEFT));

        ViewCompat.setOnApplyWindowInsetsListener(panel, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            panel.setPadding(0, bars.top, 0, 0);
            panel.setClipToPadding(true);
            if (bars.top > 0) {
                AndroidUtilities.statusBarHeight = bars.top;
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(this);

        setVisibility(GONE);
    }

    private LinearLayout buildPanel(Context context) {
        LinearLayout p = new LinearLayout(context);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setElevation(dp(8));

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        panelBg.setCornerRadii(new float[]{0, 0, dp(20), dp(20), 0, 0, 0, 0});
        p.setBackground(panelBg);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(buildProfileSection(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(buildGhostButton(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 8));
        content.addView(buildAccountsBlock(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 12));
        content.addView(buildNavSection(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, dp(16)));

        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        p.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return p;
    }

    private View buildProfileSection(Context context) {
        profileSection = new LinearLayout(context);
        profileSection.setOrientation(LinearLayout.VERTICAL);
        profileSection.setPadding(dp(20), dp(12), dp(20), dp(12));

        FrameLayout profileBlock = new FrameLayout(context);

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(32));
        profileBlock.addView(avatarView, LayoutHelper.createFrame(64, 64, Gravity.LEFT | Gravity.TOP));

        LinearLayout actionButtons = new LinearLayout(context);
        actionButtons.setOrientation(LinearLayout.VERTICAL);

        themeToggleBtn = new ImageView(context);
        themeToggleBtn.setScaleType(ImageView.ScaleType.CENTER);
        themeToggleBtn.setBackground(createRectSelector());
        themeToggleBtn.setOnClickListener(v -> toggleTheme());
        actionButtons.addView(themeToggleBtn, LayoutHelper.createLinear(40, 40));

        mglaSettingsBtn = new TextView(context);
        mglaSettingsBtn.setText("M");
        mglaSettingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        mglaSettingsBtn.setTypeface(AndroidUtilities.bold());
        mglaSettingsBtn.setGravity(Gravity.CENTER);
        mglaSettingsBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        mglaSettingsBtn.setBackground(createRectSelector());
        mglaSettingsBtn.setOnClickListener(v -> openFragment(new MglaSettingsActivity()));
        actionButtons.addView(mglaSettingsBtn, LayoutHelper.createLinear(40, 40, 0, 4, 0, 0));

        profileBlock.addView(actionButtons, LayoutHelper.createFrame(40, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        nameText = new TextView(context);
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        nameText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameText.setTypeface(AndroidUtilities.bold());
        nameText.setSingleLine(true);
        texts.addView(nameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        usernameText = new TextView(context);
        usernameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        usernameText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        usernameText.setSingleLine(true);
        texts.addView(usernameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        phoneText = new TextView(context);
        phoneText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        phoneText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        phoneText.setSingleLine(true);
        texts.addView(phoneText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        profileBlock.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 70, 48, 0));
        profileSection.addView(profileBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View.OnClickListener openProfile = v -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
            openFragment(new ProfileActivity(args));
        };
        profileSection.setBackground(createRectSelector());
        profileSection.setOnClickListener(openProfile);
        avatarView.setOnClickListener(openProfile);
        nameText.setOnClickListener(openProfile);
        usernameText.setOnClickListener(openProfile);
        phoneText.setOnClickListener(openProfile);

        return profileSection;
    }

    private View buildGhostButton(Context context) {
        FrameLayout row = new FrameLayout(context);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(Color.TRANSPARENT);
        bg.setStroke(dp(1.5f), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.setBackground(bg);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.mgla_ic_eye);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        ghostButtonLabel = new TextView(context);
        ghostButtonLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        ghostButtonLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        ghostButtonLabel.setTypeface(AndroidUtilities.bold());
        row.addView(ghostButtonLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 36, 0, 0, 0));

        ghostButton = row;
        row.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isGhostModeEnabled();
            MglaSpyConfig.setGhostModeEnabled(newVal);
            updateGhostButton();
        });
        return row;
    }

    private View buildAccountsBlock(Context context) {
        accountsBlock = new LinearLayout(context);
        accountsBlock.setOrientation(LinearLayout.VERTICAL);
        accountsBlock.setPadding(dp(12), dp(8), dp(12), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        accountsBlock.setBackground(bg);

        accountsListContainer = new LinearLayout(context);
        accountsListContainer.setOrientation(LinearLayout.VERTICAL);
        accountsBlock.addView(accountsListContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        accountsBlock.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(0.5f), 4, 4, 4, 4));

        FrameLayout addRow = new FrameLayout(context);
        addRow.setPadding(dp(4), dp(8), dp(4), dp(8));
        addRow.setBackground(createRectSelector());
        addRow.setOnClickListener(v -> openAddAccount());

        FrameLayout plusWrap = new FrameLayout(context);
        GradientDrawable plusBg = new GradientDrawable();
        plusBg.setShape(GradientDrawable.OVAL);
        plusBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        plusWrap.setBackground(plusBg);

        TextView plus = new TextView(context);
        plus.setText("+");
        plus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        plus.setTextColor(0xFFFFFFFF);
        plus.setGravity(Gravity.CENTER);
        plusWrap.addView(plus, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        addRow.addView(plusWrap, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        TextView addLabel = new TextView(context);
        addLabel.setText(LocaleController.getString(R.string.AddAccount));
        addLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        addRow.addView(addLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 48, 0, 0, 0));

        accountsBlock.addView(addRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return accountsBlock;
    }

    private View buildNavSection(Context context) {
        navContainer = new LinearLayout(context);
        navContainer.setOrientation(LinearLayout.VERTICAL);
        navContainer.setClipChildren(true);
        navContainer.setClipToPadding(true);

        addNavItem(context, R.drawable.msg_openprofile, "Профиль", NAV_PROFILE);
        addNavItem(context, R.drawable.outline_saved_24, LocaleController.getString(R.string.SavedMessages), NAV_SAVED);
        addNavItem(context, R.drawable.outline_groups_24, LocaleController.getString(R.string.NewGroup), NAV_NEW_GROUP);
        addNavItem(context, R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), NAV_CONTACTS);
        addNavItem(context, R.drawable.msg_calls, LocaleController.getString(R.string.Calls), NAV_CALLS);

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        navContainer.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(0.5f), 20, 8, 20, 8));

        addNavItem(context, R.drawable.msg_settings_old, LocaleController.getString(R.string.Settings), NAV_SETTINGS);
        addNavItem(context, R.drawable.msg_qrcode, LocaleController.getString(R.string.ScanQrCode), NAV_QR);
        addNavItem(context, R.drawable.outline_header_search, LocaleController.getString(R.string.BrowserSettingsTitle), NAV_BROWSER);

        return navContainer;
    }

    private void addNavItem(Context context, int iconRes, String title, int id) {
        FrameLayout row = new FrameLayout(context);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        row.setClickable(true);
        row.setBackground(createRectSelector());

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setSingleLine(true);
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, 0, 0, 0));

        row.setOnClickListener(v -> handleNavClick(id));
        navContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void handleNavClick(int id) {
        switch (id) {
            case NAV_PROFILE: {
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
                openFragment(new ProfileActivity(args));
                break;
            }
            case NAV_SAVED:
                close(true);
                AndroidUtilities.runOnUIThread(SavedMessagesController::openSavedMessages, 220);
                break;
            case NAV_NEW_GROUP:
                openFragment(new GroupCreateActivity(new Bundle()));
                break;
            case NAV_CONTACTS:
                openFragment(new ContactsActivity(null));
                break;
            case NAV_CALLS:
                openFragment(new CallLogActivity());
                break;
            case NAV_SETTINGS:
                openFragment(new SettingsActivity());
                break;
            case NAV_QR:
                close(true);
                AndroidUtilities.runOnUIThread(() -> {
                    BaseFragment fragment = activity.getActionBarLayout().getLastFragment();
                    if (fragment != null) {
                        QrActivity.openCameraScanActivity(fragment);
                    }
                }, 220);
                break;
            case NAV_BROWSER:
                openFragment(new WebBrowserSettings(null));
                break;
        }
    }

    private void openFragment(BaseFragment fragment) {
        close(true);
        AndroidUtilities.runOnUIThread(() -> activity.presentFragment(fragment), 220);
    }

    private void switchToAccount(int account) {
        if (account == UserConfig.selectedAccount) {
            return;
        }
        close(true);
        AndroidUtilities.runOnUIThread(() -> activity.switchToAccount(account, true), 220);
    }

    private void openAddAccount() {
        int freeAccount = -1;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                freeAccount = a;
                break;
            }
        }
        if (freeAccount >= 0) {
            openFragment(new LoginActivity(freeAccount));
        }
    }

    private void toggleTheme() {
        SharedPreferences preferences = getContext().getSharedPreferences("themeconfig", Activity.MODE_PRIVATE);
        String dayThemeName = preferences.getString("lastDayTheme", "Blue");
        if (Theme.getTheme(dayThemeName) == null || Theme.getTheme(dayThemeName).isDark()) {
            dayThemeName = "Blue";
        }
        String nightThemeName = preferences.getString("lastDarkTheme", "Dark Blue");
        if (Theme.getTheme(nightThemeName) == null || !Theme.getTheme(nightThemeName).isDark()) {
            nightThemeName = "Dark Blue";
        }
        Theme.ThemeInfo activeTheme = Theme.getActiveTheme();
        if (dayThemeName.equals(nightThemeName)) {
            if (activeTheme.isDark() || dayThemeName.equals("Dark Blue") || dayThemeName.equals("Night")) {
                dayThemeName = "Blue";
            } else {
                nightThemeName = "Dark Blue";
            }
        }
        boolean toDark = dayThemeName.equals(activeTheme.getKey());
        Theme.ThemeInfo themeInfo = Theme.getTheme(toDark ? nightThemeName : dayThemeName);
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.needSetDayNightTheme, themeInfo, false, null, -1, toDark, null, null, null, false
        );
        updateThemeIcon();
    }

    private void updateThemeIcon() {
        boolean isDark = Theme.isCurrentThemeDark();
        themeToggleBtn.setImageResource(isDark ? R.drawable.mgla_ic_moon : R.drawable.mgla_ic_sun);
        themeToggleBtn.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
    }

    private void updateGhostButton() {
        if (ghostButtonLabel == null) {
            return;
        }
        boolean enabled = MglaSpyConfig.isGhostModeEnabled();
        ghostButtonLabel.setText(enabled ? "Выключить режим призрака" : "Включить режим призрака");
    }

    public void open() {
        if (isOpen) {
            return;
        }
        cancelProgressAnimator();
        applySystemInsets();
        ViewCompat.requestApplyInsets(this);
        registerObserver();
        refreshData();
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).bringChildToFront(this);
        }
        isOpen = false;
        openProgress = 0;
        setVisibility(VISIBLE);
        scrim.setVisibility(VISIBLE);
        panel.setTranslationX(-panelWidth);
        scrim.setAlpha(0f);
        animateProgress(1f);
    }

    public void close(boolean animated) {
        if (!isOpen && openProgress <= 0 && !isTracking && progressAnimator == null) {
            return;
        }
        if (animated) {
            animateProgress(0f);
        } else {
            cancelProgressAnimator();
            setOpenProgress(0);
            finishClose();
        }
    }

    public void close() {
        close(true);
    }

    public boolean isOpen() {
        return isOpen;
    }

    public boolean isVisibleOrAnimating() {
        return isOpen || openProgress > 0 || isTracking || progressAnimator != null;
    }

    public boolean isHandlingOpenGesture() {
        return trackingOpenGesture && (maybeTracking || isTracking);
    }

    public boolean handleOpenGesture(MotionEvent ev) {
        if (!MglaSideMenuConfig.isEnabled() || isOpen) {
            return false;
        }
        if (isTracking && trackingCloseGesture) {
            return false;
        }
        if (ev == null) {
            resetGestureTracking();
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!trackingOpenGesture && !isTracking && ev.getRawX() > dp(20)) {
                    return false;
                }
                trackingPointerId = ev.getPointerId(0);
                trackingStartRawX = ev.getRawX();
                trackingStartRawY = ev.getRawY();
                maybeTracking = true;
                trackingOpenGesture = true;
                trackingCloseGesture = false;
                ensureVelocityTracker();
                velocityTracker.addMovement(ev);
                setVisibility(VISIBLE);
                scrim.setVisibility(VISIBLE);
                setOpenProgress(0);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!trackingOpenGesture || ev.getPointerId(0) != trackingPointerId) {
                    return false;
                }
                ensureVelocityTracker();
                velocityTracker.addMovement(ev);
                float dx = ev.getRawX() - trackingStartRawX;
                float dy = Math.abs(ev.getRawY() - trackingStartRawY);
                if (maybeTracking && !isTracking && dx >= AndroidUtilities.getPixelsInCM(0.25f, true) && dx > dy * 1.2f) {
                    isTracking = true;
                    maybeTracking = false;
                    cancelProgressAnimator();
                    registerObserver();
                    refreshData();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (maybeTracking && !isTracking && dy >= AndroidUtilities.getPixelsInCM(0.25f, true) && dy > Math.abs(dx)) {
                    resetGestureTracking();
                    finishClose();
                    return false;
                }
                if (isTracking) {
                    setOpenProgress(Math.max(0, dx / panelWidth));
                    return true;
                }
                return maybeTracking;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!trackingOpenGesture || ev.getPointerId(0) != trackingPointerId) {
                    return false;
                }
                if (isTracking) {
                    ensureVelocityTracker();
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    finishOpenDrag(velocityTracker.getXVelocity());
                } else {
                    resetGestureTracking();
                    finishClose();
                }
                return isTracking || maybeTracking;
            default:
                return false;
        }
    }

    private boolean handleCloseGesture(MotionEvent ev) {
        if (!isOpen || ev == null || isTracking && trackingOpenGesture) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                trackingPointerId = ev.getPointerId(0);
                trackingStartRawX = ev.getRawX();
                trackingStartRawY = ev.getRawY();
                maybeTracking = true;
                trackingCloseGesture = true;
                trackingOpenGesture = false;
                ensureVelocityTracker();
                velocityTracker.addMovement(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!trackingCloseGesture || ev.getPointerId(0) != trackingPointerId) {
                    return false;
                }
                ensureVelocityTracker();
                velocityTracker.addMovement(ev);
                float dx = ev.getRawX() - trackingStartRawX;
                float dy = Math.abs(ev.getRawY() - trackingStartRawY);
                if (maybeTracking && !isTracking && dx <= -AndroidUtilities.getPixelsInCM(0.25f, true) && Math.abs(dx) > dy * 1.2f) {
                    isTracking = true;
                    maybeTracking = false;
                    cancelProgressAnimator();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (maybeTracking && !isTracking && dy >= AndroidUtilities.getPixelsInCM(0.25f, true) && dy > Math.abs(dx)) {
                    resetGestureTracking();
                    return false;
                }
                if (isTracking) {
                    setOpenProgress(Math.max(0, 1f + dx / panelWidth));
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!trackingCloseGesture || ev.getPointerId(0) != trackingPointerId) {
                    return false;
                }
                if (isTracking) {
                    ensureVelocityTracker();
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    finishCloseDrag(velocityTracker.getXVelocity());
                    return true;
                }
                resetGestureTracking();
                return false;
            default:
                return false;
        }
    }

    private void finishOpenDrag(float velocityX) {
        boolean shouldOpen = openProgress > 0.35f || velocityX > 800;
        animateProgress(shouldOpen ? 1f : 0f);
        resetGestureTracking();
    }

    private void finishCloseDrag(float velocityX) {
        boolean shouldClose = openProgress < 0.65f || velocityX < -800;
        animateProgress(shouldClose ? 0f : 1f);
        resetGestureTracking();
    }

    private void ensureVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
    }

    private void resetGestureTracking() {
        maybeTracking = false;
        isTracking = false;
        trackingOpenGesture = false;
        trackingCloseGesture = false;
        trackingPointerId = -1;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void cancelProgressAnimator() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
    }

    private void animateProgress(float target) {
        cancelProgressAnimator();
        if (!SharedConfig.animationsEnabled()) {
            setOpenProgress(target);
            if (target >= 1f) {
                finishOpen();
            } else {
                finishClose();
            }
            return;
        }
        float start = openProgress;
        if (Math.abs(start - target) < 0.001f) {
            if (target >= 1f) {
                finishOpen();
            } else {
                finishClose();
            }
            return;
        }
        progressAnimator = ValueAnimator.ofFloat(start, target);
        progressAnimator.addUpdateListener(animation -> setOpenProgress((float) animation.getAnimatedValue()));
        progressAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                progressAnimator = null;
                if (target >= 1f) {
                    finishOpen();
                } else {
                    finishClose();
                }
            }
        });
        progressAnimator.setDuration(MENU_ANIMATION_DURATION);
        progressAnimator.setInterpolator(MENU_INTERPOLATOR);
        progressAnimator.start();
    }

    private void setOpenProgress(float progress) {
        openProgress = Utilities.clamp(progress, 0, 1);
        if (openProgress <= 0) {
            panel.setTranslationX(-panelWidth);
            scrim.setAlpha(0f);
            if (!isTracking && progressAnimator == null) {
                scrim.setVisibility(GONE);
                setVisibility(GONE);
            }
            return;
        }
        setVisibility(VISIBLE);
        scrim.setVisibility(VISIBLE);
        panel.setTranslationX(-panelWidth * (1f - openProgress));
        scrim.setAlpha(openProgress);
    }

    private void finishOpen() {
        isOpen = true;
        openProgress = 1f;
        setOpenProgress(1f);
    }

    private void finishClose() {
        isOpen = false;
        openProgress = 0f;
        unregisterObserver();
        resetGestureTracking();
        setOpenProgress(0f);
        setVisibility(GONE);
        scrim.setVisibility(GONE);
    }

    private Drawable createRectSelector() {
        return Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL);
    }

    private void refreshData() {
        int account = UserConfig.selectedAccount;
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        avatarView.setForUserOrChat(user, avatarDrawable);

        nameText.setText(UserObject.getUserName(user));

        String username = user != null && user.username != null ? user.username : "";
        if (!username.isEmpty()) {
            usernameText.setText("@" + username);
            usernameText.setVisibility(VISIBLE);
        } else {
            usernameText.setVisibility(GONE);
        }

        String phone = user != null && user.phone != null && !user.phone.isEmpty()
                ? PhoneFormat.getInstance().format("+" + user.phone)
                : "";
        phoneText.setText(phone);
        phoneText.setVisibility(phone.isEmpty() ? GONE : VISIBLE);

        refreshAccountsList();
        updateThemeIcon();
        updateGhostButton();
        updatePanelColors();
    }

    private void refreshAccountsList() {
        if (accountsListContainer == null) {
            return;
        }
        accountsListContainer.removeAllViews();
        ArrayList<Integer> accountNumbers = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            if (l1 > l2) {
                return 1;
            } else if (l1 < l2) {
                return -1;
            }
            return 0;
        });
        int selectedAccount = UserConfig.selectedAccount;
        Context context = getContext();
        for (int i = 0; i < accountNumbers.size(); i++) {
            final int account = accountNumbers.get(i);
            TLRPC.User accountUser = UserConfig.getInstance(account).getCurrentUser();

            FrameLayout accountRow = new FrameLayout(context);
            accountRow.setPadding(dp(4), dp(8), dp(4), dp(8));
            accountRow.setBackground(createRectSelector());
            accountRow.setOnClickListener(v -> switchToAccount(account));

            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(18));
            AvatarDrawable drawable = new AvatarDrawable(accountUser);
            avatar.setForUserOrChat(accountUser, drawable);
            accountRow.addView(avatar, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setSingleLine(true);
            name.setText(UserObject.getUserName(accountUser));
            accountRow.addView(name, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 48, 0, 40, 0));

            int unread = MessagesStorage.getInstance(account).getMainUnreadCount();
            if (account == selectedAccount) {
                ImageView check = new ImageView(context);
                check.setImageResource(R.drawable.msg_check_s);
                check.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), PorterDuff.Mode.SRC_IN));
                accountRow.addView(check, LayoutHelper.createFrame(18, 18, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
            } else if (unread > 0) {
                TextView counter = new TextView(context);
                counter.setText(String.valueOf(unread));
                counter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                counter.setTextColor(0xFFFFFFFF);
                counter.setGravity(Gravity.CENTER);
                counter.setTypeface(AndroidUtilities.bold());
                GradientDrawable counterBg = new GradientDrawable();
                counterBg.setCornerRadius(dp(10));
                counterBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                counter.setBackground(counterBg);
                counter.setPadding(dp(6), dp(2), dp(6), dp(2));
                accountRow.addView(counter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
            }

            accountsListContainer.addView(accountRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void updatePanelColors() {
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        nameText.setTextColor(textColor);
        usernameText.setTextColor(grayText);
        phoneText.setTextColor(grayText);
        ghostButtonLabel.setTextColor(textColor);
        if (mglaSettingsBtn != null) {
            mglaSettingsBtn.setTextColor(textColor);
        }

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        panelBg.setCornerRadii(new float[]{0, 0, dp(20), dp(20), 0, 0, 0, 0});
        panel.setBackground(panelBg);

        GradientDrawable accountsBg = new GradientDrawable();
        accountsBg.setCornerRadius(dp(14));
        accountsBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        accountsBlock.setBackground(accountsBg);

        if (ghostButton != null) {
            GradientDrawable ghostBg = new GradientDrawable();
            ghostBg.setCornerRadius(dp(22));
            ghostBg.setColor(Color.TRANSPARENT);
            ghostBg.setStroke(dp(1.5f), textColor);
            ghostButton.setBackground(ghostBg);
        }
    }

    private void applySystemInsets() {
        Context ctx = getContext();
        if (ctx == null || panel == null) {
            return;
        }
        int top = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        if (insets == null && activity != null && activity.getWindow() != null) {
            insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
        }
        if (insets != null) {
            top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
        }
        if (top <= 0) {
            AndroidUtilities.fillStatusBarHeight(ctx, AndroidUtilities.statusBarHeight == 0);
            top = AndroidUtilities.statusBarHeight;
        }
        if (top <= 0) {
            top = AndroidUtilities.getStatusBarHeight(ctx);
        }
        if (top > 0) {
            AndroidUtilities.statusBarHeight = top;
        }
        panel.setPadding(0, top, 0, 0);
        panel.setClipToPadding(true);
    }

    private void registerObserver() {
        if (!observerRegistered) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ghostModeChanged);
            observerRegistered = true;
        }
    }

    private void unregisterObserver() {
        if (observerRegistered) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ghostModeChanged);
            observerRegistered = false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!MglaSideMenuConfig.isEnabled()) {
            return false;
        }
        if (!isOpen && handleOpenGesture(ev)) {
            return true;
        }
        if (openProgress > 0 || isOpen) {
            return super.dispatchTouchEvent(ev);
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isOpen || ev == null) {
            return false;
        }
        handleCloseGesture(ev);
        return isTracking && trackingCloseGesture;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isTracking && trackingCloseGesture) {
            return handleCloseGesture(event);
        }
        return false;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        isTablet = AndroidUtilities.isTablet();
        panelWidth = isTablet ? dp(320) : Math.min(dp(320), AndroidUtilities.displaySize.x - dp(56));
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.width = panelWidth;
        panel.setLayoutParams(lp);
        applySystemInsets();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ghostModeChanged) {
            updateGhostButton();
        }
    }
}
