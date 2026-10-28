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
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.graphics.ColorUtils;
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
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.QrShare.QrShareActivity;
import org.telegram.ui.Wallet.WalletActivity;
import org.telegram.ui.web.WebBrowserSettings;

import java.util.ArrayList;
import java.util.Collections;

public class MglaSideMenu extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final LaunchActivity activity;
    private View scrim;
    private FrameLayout panelHost;
    private LinearLayout panel;
    private ScrollView panelScroll;
    private boolean isOpen;
    private boolean isTablet;
    private boolean observerRegistered;

    private BackupImageView avatarView;
    private TextView nameText;
    private TextView usernameText;
    private TextView phoneText;
    private ImageView themeToggleBtn;
    private TextView ghostButtonLabel;
    private View ghostButton;
    private LinearLayout accountsBlock;
    private LinearLayout accountsPinnedContainer;
    private LinearLayout accountsExpandableContainer;
    private FrameLayout accountsExpandClip;
    private View accountsDivider;
    private View addAccountRow;
    private ImageView accountsExpandBtn;
    private boolean accountsExpanded = true;
    private ValueAnimator accountsHeightAnimator;
    private boolean themeIconIsDark;
    private LinearLayout profileSection;
    private FrameLayout profileWrap;
    private LinearLayout navContainer;
    private final ArrayList<View> navRows = new ArrayList<>();
    private final ArrayList<ImageView> navIcons = new ArrayList<>();
    private final ArrayList<FrameLayout> navIconWells = new ArrayList<>();
    private final ArrayList<TextView> navLabels = new ArrayList<>();
    private final ArrayList<View> accountRows = new ArrayList<>();
    private final ArrayList<View> animatedViews = new ArrayList<>();

    @Nullable
    private BlurredBackgroundDrawable panelGlass;
    @Nullable
    private BlurredBackgroundDrawableViewFactory glassFactoryRef;
    @Nullable
    private View glassSourceRoot;
    private int glassAttachRetryToken;
    /** While true, glass keeps the fully-open sample so the slide-in is glassy from frame one. */
    private boolean glassFrozenForOpenAnim;

    private int panelWidthPx;
    private float openProgress;
    private boolean isAnimating;
    private ValueAnimator progressAnimator;
    private int trackingPointerId = -1;
    private float trackingStartRawX;
    private float trackingStartRawY;
    private boolean drawerMaybeTracking;
    private boolean drawerTracking;
    private boolean drawerIntercepted;

    private float dragStartProgress;
    private VelocityTracker velocityTracker;

    private static final CubicBezierInterpolator MENU_INTERPOLATOR = CubicBezierInterpolator.EASE_OUT_QUINT;
    private static final long MENU_ANIMATION_DURATION = 280;
    private static final int PANEL_WIDTH_DP = 320;
    private final int drawerTouchSlop;
    private final int[] tempLocation = new int[2];

    public MglaSideMenu(Context context, LaunchActivity activity) {
        super(context);
        this.activity = activity;
        this.isTablet = AndroidUtilities.isTablet();
        panelWidthPx = dp(PANEL_WIDTH_DP);
        drawerTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setClickable(true);
        setFocusable(true);

        scrim = new View(context);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(GONE);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> close(true));
        addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        panelHost = new FrameLayout(context);
        panelHost.setClipToPadding(false);
        panelHost.setClipChildren(false);
        GradientDrawable hostBg = new GradientDrawable();
        hostBg.setColor(Theme.getColor(Theme.key_chats_menuBackground));
        hostBg.setCornerRadii(new float[]{0, 0, dp(24), dp(24), dp(24), dp(24), 0, 0});
        panelHost.setBackground(hostBg);
        panel = buildPanel(context);
        panelHost.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // Full height edge-to-edge panel — left margin made it look like only a thin strip opens.
        addView(panelHost, LayoutHelper.createFrame(PANEL_WIDTH_DP, LayoutHelper.MATCH_PARENT, Gravity.LEFT));

        ViewCompat.setOnApplyWindowInsetsListener(panelHost, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // keep the panel itself unpadded so the glass header reaches the very top;
            // the top inset is applied to the profile wrapper's content instead
            panel.setPadding(0, 0, 0, 0);
            panel.setClipToPadding(true);
            if (profileWrap != null) {
                profileWrap.setPadding(dp(10), bars.top + dp(8), dp(10), dp(8));
            }
            if (panelScroll != null) {
                panelScroll.setPadding(0, 0, 0, bars.bottom);
            }
            if (bars.top > 0) {
                AndroidUtilities.statusBarHeight = bars.top;
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(this);

        panelHost.setTranslationX(-panelWidthPx);
        openProgress = 0f;
        scrim.setAlpha(0f);
        setVisibility(GONE);
    }

    private LinearLayout buildPanel(Context context) {
        LinearLayout p = new LinearLayout(context);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setElevation(dp(10));
        p.setClipToPadding(false);
        p.setClipChildren(false);

        p.setBackgroundColor(Color.TRANSPARENT);

        ScrollView scrollView = new ScrollView(context);
        panelScroll = scrollView;
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setClipChildren(false);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipToPadding(false);
        content.setClipChildren(false);
        content.setPadding(0, 0, 0, dp(20));

        content.addView(buildProfileSection(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(buildGhostButton(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 6, 8, 4));
        content.addView(buildAccountsBlock(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10, 4, 10, 10));
        content.addView(buildNavSection(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));

        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        p.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return p;
    }

    private View buildProfileSection(Context context) {
        profileWrap = new FrameLayout(context);
        profileWrap.setPadding(dp(10), dp(8), dp(10), dp(8));

        profileSection = new LinearLayout(context);
        profileSection.setOrientation(LinearLayout.VERTICAL);
        profileSection.setPadding(dp(14), dp(14), dp(48), dp(14));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(28));
        profileSection.addView(avatarView, LayoutHelper.createLinear(56, 56));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        nameText = new TextView(context);
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        nameText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameText.setTypeface(AndroidUtilities.bold());
        nameText.setSingleLine(true);
        texts.addView(nameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        usernameText = new TextView(context);
        usernameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        usernameText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        usernameText.setSingleLine(true);
        texts.addView(usernameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        phoneText = new TextView(context);
        phoneText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        phoneText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        phoneText.setSingleLine(true);
        texts.addView(phoneText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        profileSection.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        View.OnClickListener openProfile = v -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
            openFragment(new ProfileActivity(args));
        };
        profileSection.setBackground(null);
        profileSection.setOnClickListener(openProfile);
        avatarView.setOnClickListener(openProfile);
        nameText.setOnClickListener(openProfile);
        usernameText.setOnClickListener(openProfile);
        phoneText.setOnClickListener(openProfile);
        profileWrap.addView(profileSection, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        themeToggleBtn = new ImageView(context);
        themeToggleBtn.setScaleType(ImageView.ScaleType.CENTER);
        themeToggleBtn.setBackground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(Color.WHITE, 70), Theme.RIPPLE_MASK_CIRCLE_20DP));
        themeToggleBtn.setOnClickListener(v -> toggleTheme());
        profileWrap.addView(themeToggleBtn, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.TOP, 0, 18, 18, 0));
        registerAnimatedView(profileWrap);

        return profileWrap;
    }

    private View buildGhostButton(Context context) {
        FrameLayout row = new FrameLayout(context);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setClickable(true);
        row.setBackground(createRoundedSelector(dp(14)));

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.mgla_ic_eye);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        ghostButtonLabel = new TextView(context);
        ghostButtonLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        ghostButtonLabel.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        ghostButtonLabel.setSingleLine(true);
        row.addView(ghostButtonLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, 0, 0, 0));

        ghostButton = row;
        row.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isGhostModeEnabled();
            MglaSpyConfig.setGhostModeEnabled(newVal);
            updateGhostButton();
        });
        registerAnimatedView(row);
        return row;
    }

    private View buildAccountsBlock(Context context) {
        accountsBlock = new LinearLayout(context);
        accountsBlock.setOrientation(LinearLayout.VERTICAL);
        accountsBlock.setPadding(dp(8), dp(4), dp(8), dp(4));
        accountsBlock.setClipToPadding(false);
        accountsBlock.setClipChildren(false);
        accountsBlock.setBackground(null);

        FrameLayout pinnedWrap = new FrameLayout(context);
        accountsPinnedContainer = new LinearLayout(context);
        accountsPinnedContainer.setOrientation(LinearLayout.VERTICAL);
        pinnedWrap.addView(accountsPinnedContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        accountsExpandBtn = new ImageView(context);
        accountsExpandBtn.setImageResource(R.drawable.arrow_more);
        accountsExpandBtn.setScaleType(ImageView.ScaleType.CENTER);
        accountsExpandBtn.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
        accountsExpandBtn.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        accountsExpandBtn.setOnClickListener(v -> setAccountsExpanded(!accountsExpanded, true));
        pinnedWrap.addView(accountsExpandBtn, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        accountsBlock.addView(pinnedWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        accountsExpandClip = new FrameLayout(context);
        accountsExpandClip.setClipChildren(true);
        accountsExpandableContainer = new LinearLayout(context);
        accountsExpandableContainer.setOrientation(LinearLayout.VERTICAL);
        accountsExpandClip.addView(accountsExpandableContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        accountsBlock.addView(accountsExpandClip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        accountsDivider = new View(context);
        accountsDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        accountsExpandableContainer.addView(accountsDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(0.5f), 4, 4, 4, 4));

        FrameLayout addRow = new FrameLayout(context);
        addRow.setPadding(dp(4), dp(8), dp(4), dp(8));
        addRow.setBackground(createRectSelector());
        addRow.setOnClickListener(v -> openAddAccount());
        addAccountRow = addRow;

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
        addLabel.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        addRow.addView(addLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, 0, 0, 0));

        accountsExpandableContainer.addView(addRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        registerAnimatedView(accountsBlock);

        return accountsBlock;
    }

    private View buildNavSection(Context context) {
        navContainer = new LinearLayout(context);
        navContainer.setOrientation(LinearLayout.VERTICAL);
        navContainer.setClipChildren(false);
        navContainer.setClipToPadding(false);
        rebuildNavSection();
        return navContainer;
    }

    private void rebuildNavSection() {
        if (navContainer == null) {
            return;
        }
        Context context = navContainer.getContext();
        for (View row : navRows) {
            animatedViews.remove(row);
        }
        navContainer.removeAllViews();
        navRows.clear();
        navIcons.clear();
        navIconWells.clear();
        navLabels.clear();

        ArrayList<Integer> visibleItems = MglaSideMenuController.getVisibleItems(context);
        for (int i = 0; i < visibleItems.size(); i++) {
            int item = visibleItems.get(i);
            if (MglaSideMenuController.isDivider(item)) {
                View divider = new View(context);
                divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
                navContainer.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 22, 8, 22, 8));
            } else {
                addNavItem(context, MglaSideMenuController.getIcon(item), MglaSideMenuController.getTitle(item), item);
            }
        }
        updatePanelColors();
        updateAnimatedViewsProgress();
    }

    private ImageView addNavItem(Context context, int iconRes, String title, int id) {
        FrameLayout row = new FrameLayout(context);
        row.setMinimumHeight(dp(50));
        row.setPadding(dp(12), dp(8), dp(16), dp(8));
        row.setClickable(true);
        row.setBackground(createRoundedSelector(dp(14)));

        FrameLayout iconWell = new FrameLayout(context);
        GradientDrawable wellBg = new GradientDrawable();
        wellBg.setShape(GradientDrawable.OVAL);
        wellBg.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0x18));
        iconWell.setBackground(wellBg);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
        iconWell.addView(icon, LayoutHelper.createFrame(20, 20, Gravity.CENTER));
        row.addView(iconWell, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        label.setSingleLine(true);
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 48, 0, 0, 0));

        row.setOnClickListener(v -> handleNavClick(id));
        navContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 1, 6, 1));
        navRows.add(row);
        navIcons.add(icon);
        navIconWells.add(iconWell);
        navLabels.add(label);
        registerAnimatedView(row);
        return icon;
    }

    private Drawable createRoundedSelector(int radius) {
        return Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), radius, radius);
    }

    private void handleNavClick(int id) {
        switch (id) {
            case MglaSideMenuController.ITEM_PROFILE: {
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
                openFragment(new ProfileActivity(args));
                break;
            }
            case MglaSideMenuController.ITEM_SAVED:
                close(true);
                AndroidUtilities.runOnUIThread(SavedMessagesController::openSavedMessages, 220);
                break;
            case MglaSideMenuController.ITEM_NEW_GROUP:
                openFragment(new GroupCreateActivity(new Bundle()));
                break;
            case MglaSideMenuController.ITEM_CONTACTS:
                openFragment(new ContactsActivity(null));
                break;
            case MglaSideMenuController.ITEM_CALLS:
                openFragment(new CallLogActivity());
                break;
            case MglaSideMenuController.ITEM_SETTINGS:
                openFragment(new SettingsActivity());
                break;
            case MglaSideMenuController.ITEM_QR:
                close(true);
                AndroidUtilities.runOnUIThread(() -> {
                    BaseFragment fragment = activity.getActionBarLayout().getLastFragment();
                    if (fragment != null) {
                        QrShareActivity.openCameraScanActivity(fragment);
                    }
                }, 220);
                break;
            case MglaSideMenuController.ITEM_BROWSER:
                openFragment(new WebBrowserSettings(null));
                break;
            case MglaSideMenuController.ITEM_ARCHIVE: {
                Bundle args = new Bundle();
                args.putInt("folderId", 1);
                openFragment(new DialogsActivity(args));
                break;
            }
            case MglaSideMenuController.ITEM_NEW_CHANNEL:
                openFragment(new ChannelCreateActivity(new Bundle()));
                break;
            case MglaSideMenuController.ITEM_MEMORY:
                openFragment(new MglaMemoryActivity());
                break;
            case MglaSideMenuController.ITEM_MGLA_SETTINGS:
                openFragment(new MglaSettingsActivity());
                break;
            case MglaSideMenuController.ITEM_INVITE:
                openFragment(new InviteContactsActivity());
                break;
            case MglaSideMenuController.ITEM_PROXY:
                openFragment(new ProxyListActivity());
                break;
            case MglaSideMenuController.ITEM_WALLET:
                openFragment(new WalletActivity());
                break;
            case MglaSideMenuController.ITEM_MUSIC: {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", false);
                openFragment(new MglaMusicActivity(args));
                break;
            }
            case MglaSideMenuController.ITEM_TASKS: {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", false);
                openFragment(new MglaTasksActivity(args));
                break;
            }
            case MglaSideMenuController.ITEM_AI: {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", false);
                openFragment(new MglaAiChatActivity(args));
                break;
            }
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
        updateThemeIcon(true);
    }

    private void updateThemeIcon() {
        updateThemeIcon(false);
    }

    private void updateThemeIcon(boolean animated) {
        boolean isDark = Theme.isCurrentThemeDark();
        int iconRes = isDark ? R.drawable.mgla_ic_moon : R.drawable.mgla_ic_sun;
        // the toggle lives on the accent profile header — keep the icon white
        int color = Color.WHITE;
        if (!animated || themeToggleBtn.getDrawable() == null) {
            themeIconIsDark = isDark;
            themeToggleBtn.setScaleX(1f);
            themeToggleBtn.setScaleY(1f);
            themeToggleBtn.setAlpha(1f);
            themeToggleBtn.setRotation(0f);
            themeToggleBtn.setImageResource(iconRes);
            themeToggleBtn.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            return;
        }
        if (themeIconIsDark == isDark) {
            themeToggleBtn.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            return;
        }
        themeIconIsDark = isDark;
        themeToggleBtn.animate().cancel();
        themeToggleBtn.animate()
                .scaleX(0.35f)
                .scaleY(0.35f)
                .alpha(0f)
                .rotation(isDark ? 90f : -90f)
                .setDuration(140)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                .withEndAction(() -> {
                    themeToggleBtn.setImageResource(iconRes);
                    themeToggleBtn.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                    themeToggleBtn.setRotation(isDark ? -90f : 90f);
                    themeToggleBtn.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .rotation(0f)
                            .setDuration(180)
                            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                            .start();
                })
                .start();
    }

    private void updateGhostButton() {
        if (ghostButtonLabel == null) {
            return;
        }
        boolean enabled = MglaSpyConfig.isGhostModeEnabled();
        ghostButtonLabel.setText(enabled ? "Выключить режим призрака" : "Включить режим призрака");
    }

    public void open() {
        if (isOpen && openProgress >= 1f && !isAnimating) {
            return;
        }
        cancelAnimation();
        applySystemInsets();
        ViewCompat.requestApplyInsets(this);
        registerObserver();
        rebuildNavSection();
        refreshData();
        bringMenuToFront();
        isOpen = false;
        setVisibility(VISIBLE);
        scrim.setVisibility(VISIBLE);
        ensurePanelLaidOut(() -> {
            float closed = getClosedTranslationX();
            panelHost.setTranslationX(closed);
            openProgress = 0f;
            scrim.setAlpha(0f);
            updateAnimatedViewsProgress();
            // Warm blur + glass display list before the slide so the panel opens already glassy.
            prepareLiquidGlassForOpen();
            animatePanelTo(0f, true);
        });
    }

    public void close(boolean animated) {
        if (!isOpen && !isAnimating && getVisibility() != VISIBLE) {
            return;
        }
        glassFrozenForOpenAnim = false;
        if (!animated) {
            cancelAnimation();
            hideImmediate();
            return;
        }
        isOpen = false;
        animatePanelTo(getClosedTranslationX(), false);
    }

    public void close() {
        close(true);
    }

    public boolean isOpen() {
        return isOpen;
    }

    public boolean isDragging() {
        return drawerTracking;
    }

    public boolean isVisibleOrAnimating() {
        return isOpen || isAnimating || drawerTracking || getVisibility() == VISIBLE;
    }

    private float getGestureEdgeWidth() {
        int screenWidth = getMeasuredWidth();
        if (screenWidth <= 0) {
            screenWidth = AndroidUtilities.displaySize != null ? AndroidUtilities.displaySize.x : 0;
        }
        if (screenWidth <= 0 && activity != null) {
            screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        }
        // Full screen width — swipe from anywhere opens the side menu.
        return Math.max(screenWidth, dp(1));
    }

    private float getLocalTouchX(MotionEvent ev) {
        View root = activity != null && activity.frameLayout != null ? activity.frameLayout : this;
        root.getLocationOnScreen(tempLocation);
        return ev.getRawX() - tempLocation[0];
    }

    public boolean handleGlobalTouchEvent(MotionEvent ev) {
        if (ev == null || !MglaSideMenuConfig.isEnabled()) {
            return false;
        }
        if (!canHandleDrawerGesture() && !drawerTracking && !isOpen) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                resetDrawerTracking();
                trackingPointerId = ev.getPointerId(0);
                trackingStartRawX = ev.getRawX();
                trackingStartRawY = ev.getRawY();
                ensureVelocityTracker();
                velocityTracker.addMovement(ev);
                if (isOpen) {
                    drawerMaybeTracking = true;
                } else if (getLocalTouchX(ev) <= getGestureEdgeWidth()) {
                    drawerMaybeTracking = true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!drawerMaybeTracking && !drawerTracking) {
                    return false;
                }
                if (ev.getPointerId(0) != trackingPointerId) {
                    return drawerTracking;
                }
                ensureVelocityTracker();
                velocityTracker.addMovement(ev);
                float dx = ev.getRawX() - trackingStartRawX;
                float dy = Math.abs(ev.getRawY() - trackingStartRawY);
                if (!drawerTracking) {
                    if (!isOpen && dx > drawerTouchSlop && dx > dy) {
                        startDragOpen(ev.getRawX());
                    } else if (isOpen && dx < -drawerTouchSlop && Math.abs(dx) > dy) {
                        startDragClose(ev.getRawX());
                    } else if (dy > drawerTouchSlop * 2 && dy > Math.abs(dx) * 1.5f) {
                        resetDrawerTracking();
                        return false;
                    }
                }
                if (drawerTracking) {
                    float closed = getClosedTranslationX();
                    float translation = dragStartTranslationX + (ev.getRawX() - trackingStartRawX);
                    translation = Math.max(closed, Math.min(0f, translation));
                    setPanelTranslation(translation);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!drawerTracking) {
                    resetDrawerTracking();
                    return false;
                }
                float velocityX = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    velocityX = velocityTracker.getXVelocity();
                }
                float closed = getClosedTranslationX();
                float current = panelHost.getTranslationX();
                boolean shouldOpen;
        // Any noticeable open swipe completes fully — no stuck half-open panel.
        if (dragOpening) {
            shouldOpen = current > closed * 0.92f || velocityX > 400 || (ev.getRawX() - trackingStartRawX) > dp(24);
        } else {
            shouldOpen = !(current < closed * 0.08f || velocityX < -400 || (ev.getRawX() - trackingStartRawX) < -dp(24));
        }
                resetDrawerTracking();
                animatePanelTo(shouldOpen ? 0f : closed, shouldOpen);
                return true;
            }
            default:
                return drawerTracking;
        }
    }

    private boolean dragOpening;
    private float dragStartTranslationX;

    private void startDragOpen(float rawX) {
        drawerTracking = true;
        drawerIntercepted = true;
        dragOpening = true;
        cancelAnimation();
        applySystemInsets();
        registerObserver();
        rebuildNavSection();
        refreshData();
        bringMenuToFront();
        setVisibility(VISIBLE);
        scrim.setVisibility(VISIBLE);
        isOpen = false;
        float closed = getClosedTranslationX();
        panelHost.setTranslationX(closed);
        openProgress = 0f;
        scrim.setAlpha(0f);
        updateAnimatedViewsProgress();
        prepareLiquidGlassForOpen();
        trackingStartRawX = rawX;
        dragStartTranslationX = closed;
        // Re-sync after first layout if width was unknown.
        if (panel.getWidth() <= 0) {
            panel.post(() -> {
                if (!drawerTracking || !dragOpening) {
                    return;
                }
                float newClosed = getClosedTranslationX();
                float shown = panelHost.getTranslationX() - dragStartTranslationX;
                dragStartTranslationX = newClosed;
                prepareLiquidGlassForOpen();
                setPanelTranslation(newClosed + shown);
            });
        }
    }

    private void startDragClose(float rawX) {
        drawerTracking = true;
        drawerIntercepted = true;
        dragOpening = false;
        cancelAnimation();
        trackingStartRawX = rawX;
        dragStartTranslationX = panelHost.getTranslationX();
    }

    private void ensurePanelLaidOut(Runnable after) {
        if (panelHost.getWidth() > 0) {
            after.run();
            return;
        }
        setVisibility(VISIBLE);
        panelHost.post(after);
    }

    private float getClosedTranslationX() {
        int width = panelHost.getWidth();
        if (width <= 0) {
            width = panelHost.getMeasuredWidth();
        }
        if (width <= 0) {
            width = panelWidthPx;
        }
        return -width;
    }

    private void setPanelTranslation(float translationX) {
        float closed = getClosedTranslationX();
        if (closed >= 0) {
            closed = -panelWidthPx;
        }
        translationX = Math.max(closed, Math.min(0f, translationX));
        panelHost.setTranslationX(translationX);
        openProgress = 1f - (translationX / closed);
        openProgress = Utilities.clamp(openProgress, 1f, 0f);
        scrim.setAlpha(openProgress);
        updateAnimatedViewsProgress();
        if (panelGlass != null && openProgress > 0.001f && !glassFrozenForOpenAnim) {
            syncGlassSourceOffset();
            invalidateGlass();
        }
    }

    private void animatePanelTo(float targetTranslationX, boolean opening) {
        cancelAnimation();
        bringMenuToFront();
        setVisibility(VISIBLE);
        scrim.setVisibility(VISIBLE);
        float startX = panelHost.getTranslationX();
        float closed = getClosedTranslationX();
        if (opening) {
            targetTranslationX = 0f;
        } else {
            targetTranslationX = closed;
        }
        if (Math.abs(startX - targetTranslationX) < 1f) {
            setPanelTranslation(targetTranslationX);
            if (opening) {
                showOpened();
            } else {
                hideImmediate();
            }
            return;
        }
        isAnimating = true;
        final float target = targetTranslationX;
        progressAnimator = ValueAnimator.ofFloat(startX, target);
        progressAnimator.addUpdateListener(a -> setPanelTranslation((float) a.getAnimatedValue()));
        progressAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (progressAnimator != animation) {
                    return;
                }
                progressAnimator = null;
                isAnimating = false;
                setPanelTranslation(target);
                if (opening) {
                    showOpened();
                } else {
                    hideImmediate();
                }
            }
        });
        progressAnimator.setDuration(MENU_ANIMATION_DURATION);
        progressAnimator.setInterpolator(MENU_INTERPOLATOR);
        progressAnimator.start();
    }

    private void resetDrawerTracking() {
        drawerMaybeTracking = false;
        drawerTracking = false;
        drawerIntercepted = false;
        trackingPointerId = -1;
        dragStartProgress = 0f;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void ensureVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
    }

    private boolean canHandleDrawerGesture() {
        if (isOpen || isAnimating || drawerTracking) {
            return true;
        }
        BaseFragment fragment = LaunchActivity.getLastFragmentIncludeMainTabs();
        if (fragment instanceof DialogsActivity) {
            return ((DialogsActivity) fragment).canOpenMglaSideMenuByGesture();
        }
        return false;
    }

    private void bringMenuToFront() {
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).bringChildToFront(this);
        }
        elevate();
    }

    private void elevate() {
        setElevation(dp(16));
        setTranslationZ(dp(16));
    }

    private void cancelAnimation() {
        if (progressAnimator != null) {
            progressAnimator.removeAllListeners();
            progressAnimator.removeAllUpdateListeners();
            progressAnimator.cancel();
            progressAnimator = null;
        }
        panelHost.animate().cancel();
        scrim.animate().cancel();
        isAnimating = false;
    }

    private void showOpened() {
        isOpen = true;
        openProgress = 1f;
        glassFrozenForOpenAnim = false;
        panelHost.setTranslationX(0f);
        scrim.setAlpha(1f);
        updateAnimatedViewsProgress();
        setVisibility(VISIBLE);
        scrim.setVisibility(VISIBLE);
        // Unfreeze and resync to the live pose now that the panel is at rest.
        prepareLiquidGlassForOpen();
    }

    private void hideImmediate() {
        isOpen = false;
        isAnimating = false;
        openProgress = 0f;
        glassFrozenForOpenAnim = false;
        unregisterObserver();
        resetDrawerTracking();
        panelHost.setTranslationX(getClosedTranslationX());
        scrim.setAlpha(0f);
        updateAnimatedViewsProgress();
        setVisibility(GONE);
        scrim.setVisibility(GONE);
        invalidateDialogsBlur();
    }

    private void invalidateDialogsBlur() {
        DialogsActivity dialogs = resolveDialogsActivity();
        if (dialogs != null) {
            dialogs.invalidateBlur3ForSideMenu();
        }
    }

    @Nullable
    private DialogsActivity resolveDialogsActivity() {
        BaseFragment last = LaunchActivity.getLastFragment();
        if (last instanceof DialogsActivity) {
            return (DialogsActivity) last;
        }
        if (last instanceof MainTabsActivity) {
            DialogsActivity dialogs = ((MainTabsActivity) last).getDialogsActivity();
            if (dialogs != null) {
                return dialogs;
            }
        }
        MainTabsActivity tabs = LaunchActivity.findFragment(MainTabsActivity.class);
        return tabs != null ? tabs.getDialogsActivity() : null;
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
        syncGlassSourceOffset();
        invalidateGlass();
    }

    private void refreshAccountsList() {
        if (accountsPinnedContainer == null || accountsExpandableContainer == null) {
            return;
        }
        accountsPinnedContainer.removeAllViews();
        // Keep divider + addAccountRow; remove only account rows before them.
        for (int i = accountsExpandableContainer.getChildCount() - 1; i >= 0; i--) {
            View child = accountsExpandableContainer.getChildAt(i);
            if (child != accountsDivider && child != addAccountRow) {
                accountsExpandableContainer.removeViewAt(i);
            }
        }
        accountRows.clear();

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

        // Insert other accounts above divider.
        int insertIndex = 0;
        for (int i = 0; i < accountNumbers.size(); i++) {
            final int account = accountNumbers.get(i);
            boolean isSelected = account == selectedAccount;
            FrameLayout accountRow = createAccountRow(context, account, isSelected);
            accountRows.add(accountRow);
            if (isSelected) {
                accountsPinnedContainer.addView(accountRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            } else {
                accountsExpandableContainer.addView(accountRow, insertIndex++, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        boolean multiAccount = accountNumbers.size() > 1;
        if (accountsExpandBtn != null) {
            accountsExpandBtn.setVisibility(multiAccount ? VISIBLE : GONE);
        }
        if (!multiAccount) {
            accountsExpanded = true;
        }
        applyAccountsExpandedState(false);
        rebuildAnimatedViews();
    }

    private FrameLayout createAccountRow(Context context, int account, boolean isSelected) {
        TLRPC.User accountUser = UserConfig.getInstance(account).getCurrentUser();
        FrameLayout accountRow = new FrameLayout(context);
        accountRow.setPadding(dp(4), dp(8), dp(4), dp(8));
        accountRow.setBackground(createRectSelector());
        accountRow.setTag(account);
        accountRow.setOnClickListener(v -> switchToAccount(account));

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(18));
        AvatarDrawable drawable = new AvatarDrawable(accountUser);
        avatar.setForUserOrChat(accountUser, drawable);
        accountRow.addView(avatar, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        float nameLeft = 40;
        if (isSelected) {
            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.msg_check_s);
            check.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), PorterDuff.Mode.SRC_IN));
            check.setTag("account_check");
            accountRow.addView(check, LayoutHelper.createFrame(18, 18, Gravity.LEFT | Gravity.CENTER_VERTICAL, 42, 0, 0, 0));
            nameLeft = 64;
        }

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        name.setSingleLine(true);
        name.setText(UserObject.getUserName(accountUser));
        accountRow.addView(name, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, nameLeft, 0, isSelected ? 44 : 12, 0));

        if (!isSelected) {
            int unread = MessagesStorage.getInstance(account).getMainUnreadCount();
            if (unread > 0) {
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
        }
        return accountRow;
    }

    private void setAccountsExpanded(boolean expanded, boolean animated) {
        if (accountsExpanded == expanded) {
            return;
        }
        accountsExpanded = expanded;
        applyAccountsExpandedState(animated);
    }

    private void cancelAccountsHeightAnimator() {
        if (accountsHeightAnimator != null) {
            accountsHeightAnimator.removeAllListeners();
            accountsHeightAnimator.removeAllUpdateListeners();
            accountsHeightAnimator.cancel();
            accountsHeightAnimator = null;
        }
    }

    private void setAccountsExpandClipHeight(int height) {
        if (accountsExpandClip == null) {
            return;
        }
        ViewGroup.LayoutParams lp = accountsExpandClip.getLayoutParams();
        if (lp == null) {
            return;
        }
        int newHeight = height;
        if (height != ViewGroup.LayoutParams.WRAP_CONTENT && height != ViewGroup.LayoutParams.MATCH_PARENT) {
            newHeight = Math.max(0, height);
        }
        if (lp.height == newHeight) {
            return;
        }
        lp.height = newHeight;
        accountsExpandClip.setLayoutParams(lp);
    }

    private int measureAccountsExpandableHeight() {
        if (accountsExpandableContainer == null || accountsBlock == null) {
            return 0;
        }
        int width = accountsBlock.getWidth() - accountsBlock.getPaddingLeft() - accountsBlock.getPaddingRight();
        if (width <= 0) {
            width = accountsExpandClip != null ? accountsExpandClip.getWidth() : 0;
        }
        if (width <= 0) {
            width = dp(280);
        }
        accountsExpandableContainer.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        return Math.max(0, accountsExpandableContainer.getMeasuredHeight());
    }

    private void applyAccountsExpandedState(boolean animated) {
        if (accountsExpandClip == null || accountsExpandableContainer == null) {
            return;
        }

        View check = accountsPinnedContainer != null ? accountsPinnedContainer.findViewWithTag("account_check") : null;
        if (check != null) {
            check.setVisibility(accountsExpanded ? VISIBLE : GONE);
        }

        if (accountsExpandBtn != null) {
            accountsExpandBtn.animate().cancel();
            float rotation = accountsExpanded ? 180f : 0f;
            if (animated) {
                accountsExpandBtn.animate().rotation(rotation).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            } else {
                accountsExpandBtn.setRotation(rotation);
            }
        }

        cancelAccountsHeightAnimator();

        if (!animated) {
            accountsExpandableContainer.setAlpha(1f);
            accountsExpandClip.setVisibility(accountsExpanded ? VISIBLE : GONE);
            setAccountsExpandClipHeight(accountsExpanded ? ViewGroup.LayoutParams.WRAP_CONTENT : 0);
            return;
        }

        accountsExpandClip.setVisibility(VISIBLE);
        final int fullHeight = measureAccountsExpandableHeight();
        if (fullHeight <= 0) {
            accountsExpandableContainer.setAlpha(1f);
            accountsExpandClip.setVisibility(accountsExpanded ? VISIBLE : GONE);
            setAccountsExpandClipHeight(accountsExpanded ? ViewGroup.LayoutParams.WRAP_CONTENT : 0);
            return;
        }

        int currentHeight = accountsExpandClip.getLayoutParams() != null ? accountsExpandClip.getLayoutParams().height : 0;
        if (currentHeight == ViewGroup.LayoutParams.WRAP_CONTENT || currentHeight < 0) {
            currentHeight = accountsExpandClip.getHeight();
        }
        if (currentHeight < 0) {
            currentHeight = 0;
        }

        final int startHeight;
        final int endHeight;
        if (accountsExpanded) {
            startHeight = Math.min(currentHeight, fullHeight);
            endHeight = fullHeight;
            accountsExpandableContainer.setAlpha(Math.max(0.2f, startHeight / (float) fullHeight));
            setAccountsExpandClipHeight(startHeight);
        } else {
            startHeight = currentHeight > 0 ? currentHeight : fullHeight;
            endHeight = 0;
            setAccountsExpandClipHeight(startHeight);
        }

        accountsHeightAnimator = ValueAnimator.ofInt(startHeight, endHeight);
        accountsHeightAnimator.addUpdateListener(a -> {
            int value = (int) a.getAnimatedValue();
            setAccountsExpandClipHeight(value);
            accountsExpandableContainer.setAlpha(Utilities.clamp(value / (float) fullHeight, 1f, 0f));
        });
        accountsHeightAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (accountsHeightAnimator != animation) {
                    return;
                }
                accountsHeightAnimator = null;
                accountsExpandableContainer.setAlpha(1f);
                if (accountsExpanded) {
                    setAccountsExpandClipHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                    accountsExpandClip.setVisibility(VISIBLE);
                } else {
                    setAccountsExpandClipHeight(0);
                    accountsExpandClip.setVisibility(GONE);
                }
            }
        });
        accountsHeightAnimator.setDuration(260);
        accountsHeightAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        accountsHeightAnimator.start();
    }

    private void updatePanelColors() {
        // Called from rebuildNavSection during buildPanel, before `panel` field is assigned.
        if (nameText == null || ghostButtonLabel == null) {
            return;
        }
        final boolean dark = Theme.isCurrentThemeDark();
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int itemText = Theme.getColor(Theme.key_chats_menuItemText);
        int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        // Glass profile capsule: light text on accent-tinted surface.
        nameText.setTextColor(Color.WHITE);
        usernameText.setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 210));
        phoneText.setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 210));
        ghostButtonLabel.setTextColor(itemText);

        if (panelGlass == null) {
            applySolidPanelBackground(dark);
        } else {
            panelGlass.updateColors();
            if (panelHost != null && panelHost.getBackground() != panelGlass) {
                panelHost.setBackground(panelGlass);
            }
            if (panel != null) {
                panel.setBackgroundColor(Color.TRANSPARENT);
            }
        }

        if (profileWrap != null) {
            GradientDrawable profileBg = new GradientDrawable();
            profileBg.setColor(ColorUtils.setAlphaComponent(accent, dark ? 0xB3 : 0xCC));
            profileBg.setCornerRadius(dp(20));
            profileWrap.setBackground(profileBg);
        }

        if (accountsBlock != null) {
            GradientDrawable accountsBg = new GradientDrawable();
            accountsBg.setCornerRadius(dp(18));
            accountsBg.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhite), dark ? 0x1A : 0x59));
            accountsBg.setStroke(dp(1), ColorUtils.blendARGB(accent, Color.BLACK, 0.25f));
            accountsBlock.setBackground(accountsBg);
        }

        if (accountsExpandBtn != null) {
            accountsExpandBtn.setColorFilter(new PorterDuffColorFilter(grayText, PorterDuff.Mode.SRC_IN));
        }
        if (accountsDivider != null) {
            accountsDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        }

        if (ghostButton != null) {
            ghostButton.setBackground(createRoundedSelector(dp(14)));
        }
        if (navContainer != null) {
            navContainer.setBackground(null);
        }

        int wellColor = ColorUtils.setAlphaComponent(grayText, dark ? 0x28 : 0x18);
        for (int i = 0; i < navRows.size(); i++) {
            navRows.get(i).setBackground(createRoundedSelector(dp(14)));
            navIcons.get(i).setColorFilter(new PorterDuffColorFilter(grayText, PorterDuff.Mode.SRC_IN));
            navLabels.get(i).setTextColor(itemText);
            if (i < navIconWells.size()) {
                GradientDrawable wellBg = new GradientDrawable();
                wellBg.setShape(GradientDrawable.OVAL);
                wellBg.setColor(wellColor);
                navIconWells.get(i).setBackground(wellBg);
            }
        }
        invalidateGlass();
    }

    /**
     * Capture blur + attach glass + build the liquid-glass display list before the panel
     * slides in, so the first visible frames are already glassy (not solid → glass at the end).
     */
    private void prepareLiquidGlassForOpen() {
        if (!MglaSideMenuConfig.isGlassEnabled()) {
            clearPanelGlass();
            glassFrozenForOpenAnim = false;
            return;
        }
        invalidateDialogsBlur();
        attachLiquidGlass();
        warmPanelGlassDisplayList();
        // Keep the open-pose sample during the slide; live tracking would resample off-screen
        // and make the panel look solid until it fully settles.
        glassFrozenForOpenAnim = !isOpen || openProgress < 0.999f || isAnimating || drawerTracking;
    }

    private void attachLiquidGlass() {
        if (!MglaSideMenuConfig.isGlassEnabled()) {
            clearPanelGlass();
            return;
        }

        GlassBind bind = resolveGlassBind();
        if (bind == null || bind.factory == null) {
            // Factory may not be ready yet (cold start / fragment not created). Retry shortly.
            scheduleGlassAttachRetry();
            if (panelGlass == null) {
                applySolidPanelBackground(Theme.isCurrentThemeDark());
            }
            return;
        }

        glassSourceRoot = bind.sourceRoot;
        final boolean factoryChanged = glassFactoryRef != bind.factory;
        // One full-panel glass on the host — separate profile/accounts glass looked like
        // only the top/bottom strips were glassy.
        if (panelGlass == null || factoryChanged) {
            glassFactoryRef = bind.factory;
            BlurredBackgroundProvider panelProvider = new BlurredBackgroundProviderBuilder(null)
                    .setBackgroundColor((r, isDark) -> {
                        // Side-menu liquid glass follows appearance «Затемнение стекла».
                        int base = Theme.getColor(Theme.key_chats_menuBackground);
                        float a = MglaGlassConfig.getGlassBackgroundAlpha(isDark);
                        return ColorUtils.setAlphaComponent(base, Math.round(255 * a));
                    })
                    .setStrokeColorTop((r, isDark) -> 0)
                    .setStrokeColorBottom((r, isDark) -> 0)
                    .setStrokeWidth(0, 0)
                    .setShadowColor((r, isDark) -> 0)
                    .build();
            // Menu lives outside fragment contentView; sync offsets manually like popup glass.
            panelGlass = bind.factory.create((View) null, panelProvider);
            panelGlass.setRadius(0, dp(24), dp(24), 0);
            panelGlass.setThickness(dp(14));
            panelGlass.setIntensity(0.88f);
        }

        if (panelHost != null) {
            panelHost.setBackground(panelGlass);
        }
        if (panel != null) {
            panel.setBackgroundColor(Color.TRANSPARENT);
        }
        syncGlassSourceOffset();
        invalidateGlass();
    }

    private void warmPanelGlassDisplayList() {
        if (panelGlass == null || panelHost == null) {
            return;
        }
        final int w = panelHost.getWidth() > 0 ? panelHost.getWidth() : panelHost.getMeasuredWidth();
        final int h = panelHost.getHeight() > 0 ? panelHost.getHeight() : panelHost.getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // Background bounds are only assigned on draw; set them now so we can build the
        // liquid-glass RenderNode while the panel is still off-screen.
        panelGlass.setBounds(0, 0, w, h);
        panelGlass.updateColors();
        // Sample as if the panel is already open — matches what the user will see as it slides in.
        syncGlassSourceOffsetAsOpen();
        if (panelGlass instanceof BlurredBackgroundDrawableRenderNode) {
            BlurredBackgroundDrawableRenderNode node = (BlurredBackgroundDrawableRenderNode) panelGlass;
            node.invalidateDisplayList();
            node.updateDisplayList();
        }
        panelHost.invalidate();
    }

    private void scheduleGlassAttachRetry() {
        final int token = ++glassAttachRetryToken;
        AndroidUtilities.runOnUIThread(() -> {
            if (token != glassAttachRetryToken) {
                return;
            }
            if (!MglaSideMenuConfig.isGlassEnabled() || !isVisibleOrAnimating()) {
                return;
            }
            if (panelGlass != null && glassFactoryRef != null) {
                return;
            }
            prepareLiquidGlassForOpen();
        }, 48);
    }

    private void clearPanelGlass() {
        glassAttachRetryToken++;
        panelGlass = null;
        glassFactoryRef = null;
        glassSourceRoot = null;
        applySolidPanelBackground(Theme.isCurrentThemeDark());
    }

    private void applySolidPanelBackground(boolean dark) {
        if (panelHost == null) {
            return;
        }
        GradientDrawable panelBg = new GradientDrawable();
        int base = Theme.getColor(Theme.key_chats_menuBackground);
        int alpha = Math.round(255 * MglaSideMenuConfig.getBackgroundOpacityAlpha());
        panelBg.setColor(ColorUtils.setAlphaComponent(base, alpha));
        panelBg.setCornerRadii(new float[]{0, 0, dp(24), dp(24), dp(24), dp(24), 0, 0});
        panelHost.setBackground(panelBg);
        if (panel != null) {
            panel.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    /** Refresh solid opacity / glass tint when settings or appearance darkening change. */
    private void applyAppearanceFromSettings() {
        if (!isVisibleOrAnimating()) {
            return;
        }
        if (MglaSideMenuConfig.isGlassEnabled()) {
            if (panelGlass == null) {
                prepareLiquidGlassForOpen();
            } else {
                panelGlass.updateColors();
                invalidateGlass();
            }
        } else {
            clearPanelGlass();
        }
        updatePanelColors();
    }

    private static final class GlassBind {
        final BlurredBackgroundDrawableViewFactory factory;
        @Nullable
        final View sourceRoot;

        GlassBind(BlurredBackgroundDrawableViewFactory factory, @Nullable View sourceRoot) {
            this.factory = factory;
            this.sourceRoot = sourceRoot;
        }
    }

    @Nullable
    private GlassBind resolveGlassBind() {
        BaseFragment last = LaunchActivity.getLastFragment();

        // Prefer the currently visible chat/dialogs factory so sampling matches the UI behind the panel.
        if (last instanceof ChatActivity) {
            BlurredBackgroundDrawableViewFactory factory = ((ChatActivity) last).getLiquidGlassFactory();
            if (factory != null) {
                return new GlassBind(factory, last.getFragmentView());
            }
        }
        if (last instanceof DialogsActivity) {
            BlurredBackgroundDrawableViewFactory factory = ((DialogsActivity) last).getLiquidGlassFactory();
            if (factory != null) {
                return new GlassBind(factory, last.getFragmentView());
            }
        }
        if (last instanceof MainTabsActivity) {
            DialogsActivity dialogs = ((MainTabsActivity) last).getDialogsActivity();
            if (dialogs != null && dialogs.getLiquidGlassFactory() != null) {
                return new GlassBind(dialogs.getLiquidGlassFactory(), dialogs.getFragmentView());
            }
            BlurredBackgroundDrawableViewFactory tabsFactory = ((MainTabsActivity) last).getLiquidGlassFactory();
            if (tabsFactory != null) {
                return new GlassBind(tabsFactory, last.getFragmentView());
            }
        }

        // From Settings / Profile / etc.: always fall back to Dialogs (has side-menu blur strip).
        DialogsActivity dialogs = resolveDialogsActivity();
        if (dialogs != null && dialogs.getLiquidGlassFactory() != null) {
            return new GlassBind(dialogs.getLiquidGlassFactory(), dialogs.getFragmentView());
        }

        MainTabsActivity tabs = LaunchActivity.findFragment(MainTabsActivity.class);
        if (tabs != null && tabs.getLiquidGlassFactory() != null) {
            return new GlassBind(tabs.getLiquidGlassFactory(), tabs.getFragmentView());
        }
        return null;
    }

    private void syncGlassSourceOffset() {
        syncGlassSourceOffset(panelHost != null ? panelHost.getTranslationX() : 0f,
                panelHost != null ? panelHost.getTranslationY() : 0f);
    }

    /** Offset as if translation were 0 — used to pre-build glass before the open animation. */
    private void syncGlassSourceOffsetAsOpen() {
        syncGlassSourceOffset(0f, 0f);
    }

    private void syncGlassSourceOffset(float assumeTranslationX, float assumeTranslationY) {
        if (panelHost == null || panelGlass == null) {
            return;
        }
        final int[] hostLoc = new int[2];
        panelHost.getLocationInWindow(hostLoc);
        // getLocationInWindow includes the current translation; remap to the assumed pose.
        final float hostX = hostLoc[0] - panelHost.getTranslationX() + assumeTranslationX;
        final float hostY = hostLoc[1] - panelHost.getTranslationY() + assumeTranslationY;
        if (glassSourceRoot != null && glassSourceRoot.isAttachedToWindow()) {
            final int[] rootLoc = new int[2];
            glassSourceRoot.getLocationInWindow(rootLoc);
            panelGlass.setSourceOffset(hostX - rootLoc[0], hostY - rootLoc[1]);
        } else {
            panelGlass.setSourceOffset(Math.max(0f, hostX), Math.max(0f, hostY));
        }
    }

    private void invalidateGlass() {
        if (panelGlass instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) panelGlass).invalidateDisplayList();
        }
        if (panelHost != null) {
            panelHost.invalidate();
        }
    }

    private void applySystemInsets() {
        Context ctx = getContext();
        if (ctx == null || panel == null) {
            return;
        }
        int top = 0;
        int bottom = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        if (insets == null && activity != null && activity.getWindow() != null) {
            insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
        }
        if (insets != null) {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            top = bars.top;
            bottom = bars.bottom;
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
        if (bottom <= 0) {
            bottom = AndroidUtilities.navigationBarHeight;
        }
        panel.setPadding(0, 0, 0, 0);
        panel.setClipToPadding(true);
        if (profileWrap != null) {
            profileWrap.setPadding(dp(10), top + dp(8), dp(10), dp(8));
        }
        if (panelScroll != null) {
            panelScroll.setPadding(0, 0, 0, bottom);
        }
    }

    private void registerObserver() {
        if (!observerRegistered) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ghostModeChanged);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaHeaderSettingsChanged);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
            observerRegistered = true;
        }
    }

    private void unregisterObserver() {
        if (observerRegistered) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ghostModeChanged);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaHeaderSettingsChanged);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
            observerRegistered = false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getVisibility() != VISIBLE) {
            return false;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return isVisibleOrAnimating() || super.onTouchEvent(event);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        isTablet = AndroidUtilities.isTablet();
        panelWidthPx = dp(PANEL_WIDTH_DP);
        ViewGroup.LayoutParams lp = panelHost.getLayoutParams();
        if (lp != null) {
            lp.width = panelWidthPx;
            panelHost.setLayoutParams(lp);
        }
        applySystemInsets();
        if (isOpen) {
            panelHost.setTranslationX(0f);
            openProgress = 1f;
        } else {
            panelHost.setTranslationX(getClosedTranslationX());
            openProgress = 0f;
        }
        scrim.setAlpha(openProgress);
        updateAnimatedViewsProgress();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ghostModeChanged) {
            updateGhostButton();
        } else if (id == NotificationCenter.mglaHeaderSettingsChanged
                || id == NotificationCenter.didSetNewTheme) {
            applyAppearanceFromSettings();
        }
    }

    private void registerAnimatedView(View view) {
        if (view != null && !animatedViews.contains(view)) {
            animatedViews.add(view);
        }
    }

    private void rebuildAnimatedViews() {
        animatedViews.clear();
        registerAnimatedView(profileWrap);
        registerAnimatedView(ghostButton);
        registerAnimatedView(accountsBlock);
        for (int i = 0; i < accountRows.size(); i++) {
            registerAnimatedView(accountRows.get(i));
        }
        for (int i = 0; i < navRows.size(); i++) {
            registerAnimatedView(navRows.get(i));
        }
        updateAnimatedViewsProgress();
    }

    private void updateAnimatedViewsProgress() {
        final int count = animatedViews.size();
        // shrink the stagger step when there are many views, otherwise the last
        // items never reach full progress (start + 0.42 would exceed 1.0)
        final float step = count > 1 ? Math.min(0.05f, 0.58f / (count - 1)) : 0f;
        for (int i = 0; i < count; i++) {
            View view = animatedViews.get(i);
            if (view == null) {
                continue;
            }
            float start = i * step;
            float itemProgress = Utilities.clamp((openProgress - start) / 0.42f, 1f, 0f);
            view.setAlpha(itemProgress);
            view.setTranslationY(dp(18) * (1f - itemProgress));
            view.setScaleX(0.97f + 0.03f * itemProgress);
            view.setScaleY(0.97f + 0.03f * itemProgress);
        }
    }
}
