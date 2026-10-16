package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.math.MathUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.MglaMainTabsConfig;
import org.telegram.messenger.MglaMainTabsConfig.TabId;
import org.telegram.messenger.MglaSpyConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.ui.Components.MglaMainTabsIslandEditor;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FolderDrawable;
import org.telegram.ui.Components.HintsController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.Stories.recorder.HintView2;
import org.telegram.ui.Wallet.WalletActivity;

import java.util.ArrayList;
import java.util.Collections;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class MainTabsActivity extends ViewPagerActivity implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {

    /** @deprecated use {@link #getFragmentsCount()} — kept for a few external callers. */
    public static final int TABS_COUNT = 4;

    private ArrayList<TabId> tabOrder = new ArrayList<>();
    private GlassTabView[] tabs = new GlassTabView[0];

    private int positionOf(TabId id) {
        return tabOrder.indexOf(id);
    }

    private TabId tabAt(int position) {
        if (position < 0 || position >= tabOrder.size()) {
            return null;
        }
        return tabOrder.get(position);
    }

    private GlassTabView tabView(TabId id) {
        int i = positionOf(id);
        return i >= 0 && i < tabs.length ? tabs[i] : null;
    }

    private void loadTabOrder() {
        tabOrder = MglaMainTabsConfig.getEnabledTabs(getUserConfig().showCallsTab);
        if (tabOrder.isEmpty()) {
            tabOrder = MglaMainTabsConfig.defaultOrder(getUserConfig().showCallsTab);
        }
    }

    private static final int ANIMATOR_ID_TABS_VISIBLE = 0;
    private final BoolAnimator animatorTabsVisible = new BoolAnimator(ANIMATOR_ID_TABS_VISIBLE,
        this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);


    private IUpdateLayout updateLayout;
    private boolean dropCallsFragmentAfterPageScroll;

    private UpdateLayoutWrapper updateLayoutWrapper;
    private FrameLayout tabsViewWrapper;
    private LinearLayout tabsBottomRow;
    private MainTabsLayout tabsView;
    private BlurredBackgroundDrawable tabsViewBackground;
    private FrameLayout searchButton;
    private ImageView searchButtonIcon;
    private BlurredBackgroundDrawable searchButtonBackground;
    private View fadeView;
    private boolean pendingOpenSearch;

    public MainTabsActivity() {
        super();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            iBlur3SourceTabGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceTabGlass.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                hash.addF(fragmentPosition.left);
                                hash.addF(fragmentPosition.top);
                                hash.add(fragment.getClassGuid());
                            }
                        }
                    }
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    final int width = fragmentView.getMeasuredWidth();
                    final int height = fragmentView.getMeasuredHeight();

                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                canvas.save();
                                canvas.translate(fragmentPosition.left, fragmentPosition.top);
                                source.draw(canvas, 0, 0, width, height);
                                canvas.restore();
                            }
                        }
                    }
                }
            });
        } else {
            iBlur3SourceTabGlass = null;
        }

        iBlur3SourceColor = new BlurredBackgroundSourceColor();

        Bulletin.Delegate delegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN);
            }
        };

        Bulletin.addDelegate(this, delegate);
        Bulletin.addDelegate(contentView, delegate);
    }

    @Override
    protected FrameLayout createContentView(Context context) {
        return new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                checkUi_tabsPosition();
                checkUi_fadeView();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                final int color = getEstBackgroundColor();
                if (insetLeft != 0) {
                    canvas.drawRect(0, 0, insetLeft, getHeight(), Theme.fillingPaint(color));
                }
                if (insetRight != 0) {
                    canvas.drawRect(getWidth() - insetRight, 0, getWidth(), getHeight(), Theme.fillingPaint(color));
                }

                super.dispatchDraw(canvas);
                blur3_invalidateBlur();
                blur3_updateFadeColors();
            }
        };
    }

    private int getEstBackgroundColor() {
        return ColorUtils.blendARGB(
                getThemedColor(Theme.key_windowBackgroundGray),
                getThemedColor(Theme.key_windowBackgroundWhite),
                viewPager != null ? viewPager.getPositionVisibility(0) : 1);
    }

    private boolean tabletLayout;
    public void updateLayout() {
//        if (tabletLayout == AndroidUtilities.isTablet()) return;
//        tabletLayout = AndroidUtilities.isTablet();
//
//        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
//        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
//        int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
//        if (tabletLayout) {
//            bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
//        }
//        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL);
//        if (tabletLayout) {
//            lp.leftMargin = dp(6);
//            lp.rightMargin = dp(6);
//            lp.topMargin = dp(6);
//        }
//        lp.bottomMargin = bottomMargin;
//
//        viewPager.setLayoutParams(lp);
//        viewPager.setTabletLayout(tabletLayout);
//        checkUi_fadeView();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateTabsIslandMaxWidth();
        updateLayout();
    }

    @Override
    public void onResume() {
        super.onResume();
        blur3_updateColors();
        checkContactsTabBadge();
        checkUnreadCount(true);

        showAccountChangeHint();
    }

    private void checkContactsTabBadge() {
        GlassTabView contactsTab = tabView(TabId.CONTACTS);
        if (tabsView != null && contactsTab != null) {
            final boolean hasPermission = Build.VERSION.SDK_INT >= 23 && ContactsController.hasContactsPermission();
            if (hasPermission) {
                MessagesController.getGlobalNotificationsSettings().edit().putBoolean("askAboutContacts2", true).apply();
            }
            if (Build.VERSION.SDK_INT >= 23 && UserConfig.getInstance(currentAccount).syncContacts && !hasPermission && MessagesController.getGlobalNotificationsSettings().getBoolean("askAboutContacts2", true)) {
                contactsTab.setCounter("!", true, true);
            } else {
                contactsTab.setCounter(null, true, true);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        tabletLayout = false;

        tabsView = new MainTabsLayout(context, resourceProvider);
        tabsView.setClipChildren(false);
        tabsView.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4));

        rebuildTabViews(context, false);
        selectTab(viewPager.getCurrentPosition(), false);

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));

        final ViewPositionWatcher viewPositionWatcher = new ViewPositionWatcher(contentView);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceTabGlass != null ? iBlur3SourceTabGlass : iBlur3SourceColor);
        iBlur3FactoryGlass.setSourceRootView(viewPositionWatcher, contentView);
        iBlur3FactoryGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));

        tabsViewBackground = iBlur3FactoryGlass.create(tabsView, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
        tabsViewBackground.setRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
        tabsViewBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
        tabsView.setBackground(tabsViewBackground);

        searchButton = new FrameLayout(context);
        searchButton.setContentDescription(getString(R.string.Search));
        searchButtonBackground = iBlur3FactoryGlass.create(searchButton, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
        searchButtonBackground.setRadius(dp(MglaMainTabsConfig.SEARCH_BUTTON_SIZE / 2f));
        searchButton.setBackground(searchButtonBackground);
        searchButtonIcon = new ImageView(context);
        searchButtonIcon.setImageResource(R.drawable.outline_header_search);
        searchButtonIcon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_glass_tabUnselected), PorterDuff.Mode.SRC_IN));
        searchButtonIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        searchButton.addView(searchButtonIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        searchButton.setClickable(true);
        searchButton.setEnabled(true);
        searchButton.setOnClickListener(v -> openDialogsSearch());

        BlurredBackgroundDrawableViewFactory iBlur3FactoryFade = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        iBlur3FactoryFade.setSourceRootView(viewPositionWatcher, contentView);

        fadeView = new View(context);
        BlurredBackgroundWithFadeDrawable fadeDrawable = new BlurredBackgroundWithFadeDrawable(iBlur3FactoryFade.create(fadeView, null));
        fadeDrawable.setFadeHeight(dp(60), true);
        fadeView.setBackground(fadeDrawable);

        contentView.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        tabsViewWrapper = new FrameLayout(context);
        tabsViewWrapper.setOnClickListener(v -> {});
        tabsViewWrapper.setClipToPadding(false);
        tabsViewWrapper.setClipChildren(false);

        tabsBottomRow = new LinearLayout(context);
        tabsBottomRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsBottomRow.setGravity(Gravity.BOTTOM);
        tabsBottomRow.setClipChildren(false);
        tabsBottomRow.setClipToPadding(false);
        // Island + search are one WRAP group, centered in the wrapper.
        tabsBottomRow.addView(tabsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS, Gravity.BOTTOM));
        tabsBottomRow.addView(searchButton, LayoutHelper.createLinear(
                MglaMainTabsConfig.SEARCH_BUTTON_SIZE,
                MglaMainTabsConfig.SEARCH_BUTTON_SIZE,
                Gravity.BOTTOM,
                MglaMainTabsConfig.SEARCH_BUTTON_GAP, 0, 0, DialogsActivity.MAIN_TABS_MARGIN));
        tabsViewWrapper.addView(tabsBottomRow, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT,
                DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        contentView.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        applySearchButtonVisibility(false);
        updateTabsIslandMaxWidth();
        syncSearchButtonSideMargins();

        updateLayoutWrapper = new UpdateLayoutWrapper(context);
        contentView.addView(updateLayoutWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayout = ApplicationLoader.applicationLoaderInstance.takeUpdateLayout(getParentActivity(), updateLayoutWrapper);
        if (updateLayout != null) {
            updateLayout.updateAppUpdateViews(currentAccount, false);
        }

        updateLayout();
        checkUnreadCount(false);
        return contentView;
    }

    private void rebuildTabViews(Context context, boolean animated) {
        if (tabsView == null) {
            return;
        }
        loadTabOrder();
        tabsView.clearTabInteractionOverrides();
        tabsView.removeAllViews();
        tabs = new GlassTabView[tabOrder.size()];
        // Density (labels → icon-only) is chosen in MainTabsLayout.onMeasure for the available width.
        for (int i = 0; i < tabOrder.size(); i++) {
            final TabId id = tabOrder.get(i);
            final int position = i;
            final GlassTabView view = MglaMainTabsIslandEditor.createTabView(context, resourceProvider, currentAccount, id);
            tabs[i] = view;
            attachTabListeners(id, view);
            tabsView.addTabToIgnoreClick(view);
            if (id == TabId.SETTINGS) {
                tabsView.addTabToIgnoreLongPressNavigation(view);
            }
            view.setOnClickListener(v -> {
                if (viewPager.isManualScrolling() || viewPager.isTouch()) {
                    return;
                }
                if (viewPager.getCurrentPosition() == position) {
                    final BaseFragment fragment = getCurrentVisibleFragment();
                    if (fragment instanceof MainTabsActivity.TabFragmentDelegate) {
                        ((MainTabsActivity.TabFragmentDelegate) fragment).onParentScrollToTop();
                    }
                    return;
                }
                selectTab(position, true);
                viewPager.scrollToPosition(position);
            });
            tabsView.addView(view);
            tabsView.setViewVisible(view, true, false);
        }
        checkUnreadCount(animated);
        checkContactsTabBadge();
        selectTab(Math.min(viewPager != null ? viewPager.getCurrentPosition() : 0, Math.max(0, tabOrder.size() - 1)), animated);
    }

    /** Drop cached pager pages and rebind after tab order/visibility changes. Keeps DialogsActivity if still present. */
    private void rematerializeTabFragments() {
        DialogsActivity keepDialogs = null;
        for (int a = fragmentsArr.size() - 1; a >= 0; a--) {
            final int position = fragmentsArr.keyAt(a);
            final FragmentState state = fragmentsArr.valueAt(a);
            if (state != null && state.fragment instanceof DialogsActivity) {
                keepDialogs = (DialogsActivity) state.fragment;
                fragmentsArr.remove(position);
            } else {
                dropFragmentAtPosition(position);
            }
        }
        fragmentsArr.clear();
        loadTabOrder();
        final int chatsPos = positionOf(TabId.CHATS);
        if (keepDialogs != null && chatsPos >= 0) {
            putPreservedFragmentAtPosition(chatsPos, keepDialogs);
            dialogsActivity = keepDialogs;
        } else {
            if (keepDialogs != null) {
                keepDialogs.onFragmentDestroy();
                keepDialogs.setParentLayout(null);
            }
            dialogsActivity = null;
        }
    }

    private void attachTabListeners(TabId id, GlassTabView view) {
        switch (id) {
            case CHATS:
                view.setOnLongClickListener(this::openFoldersSelector);
                break;
            case CONTACTS:
                view.setOnLongClickListener(this::openContactsSelector);
                break;
            case CALLS:
                view.setOnLongClickListener(this::openCallsSelector);
                break;
            case PROFILE:
                view.setOnLongClickListener(this::openAccountSelector);
                break;
            case SETTINGS:
                view.setOnLongClickListener(this::openGhostModeSelector);
                break;
            default:
                break;
        }
    }

    private void checkUnreadCount(boolean animated) {
        if (tabsView == null) {
            return;
        }
        GlassTabView chatsTab = tabView(TabId.CHATS);
        if (chatsTab == null) {
            return;
        }
        final int unreadCount = MessagesStorage.getInstance(currentAccount).getMainUnreadCount();
        if (unreadCount > 0) {
            final String unreadCountFmt = LocaleController.formatNumber(unreadCount, ',');
            chatsTab.setCounter(unreadCountFmt, false, animated);
        } else {
            chatsTab.setCounter(null, false, animated);
        }
    }

    public boolean openContactsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.msg_contact_add, getString(R.string.NewContact), () -> {
            new NewContactBottomSheet(this, getContext()).show();
        });
        o.add(R.drawable.msg_calls, getString(R.string.VoipChatRecentCalls), () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            presentFragment(new CallLogActivity(args));
        });
        o.setBlur(true);
        o.translate(0, -dp(4));
        o.setGravity(Gravity.LEFT);
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    public boolean openCallsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.menu_call_create, getString(R.string.GroupCallCreate2), () -> CallLogActivity.openCreateCall(this));
        if (!MglaMainTabsConfig.isCustomized()) {
            if (getUserConfig().showCallsTab) {
                o.add(R.drawable.msg_archive_hide, getString(R.string.HideCallTab), () -> {
                    getUserConfig().setShowCallsTab(false);
                    checkUi_callTabVisible(false, true);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
                });
            } else {
                o.add(R.drawable.menu_add_tab_24, getString(R.string.GroupCallShowInMainTabs), () -> {
                    getUserConfig().setShowCallsTab(true);
                    checkUi_callTabVisible(true, true);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
                });
            }
        }
        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private Integer pendingFolderId;

    private boolean openFoldersSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        if (filters == null || filters.size() <= 1) return false;

        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter folder = filters.get(i);
            final ActionBarMenuSubItem folderItem = new ActionBarMenuSubItem(getParentActivity(), 2, false, false, getResourceProvider());
            folderItem.setPadding(dp(18), 0, dp(18), 0);
            CharSequence title = folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name;
            title = Emoji.replaceEmoji(title, folderItem.getTextView().getPaint().getFontMetricsInt(), false);
            if (!folder.isDefault()) {
                title = MessageObject.replaceAnimatedEmoji(title, folder.entities, folderItem.getTextView().getPaint().getFontMetricsInt());
            }
            final int unreadCount = folder.isDefault()
                    ? MessagesStorage.getInstance(currentAccount).getMainUnreadCount()
                    : folder.unreadCount;
            if (unreadCount > 0) {
                final SpannableStringBuilder titleWithCounter = new SpannableStringBuilder(title);
                final int counterStart = titleWithCounter.length();
                titleWithCounter.append(String.valueOf(unreadCount));
                titleWithCounter.setSpan(
                        new FolderCounterSpan(unreadCount, hasUnmutedUnreadDialogs(folder)),
                        counterStart,
                        titleWithCounter.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                title = titleWithCounter;
                folderItem.setContentDescription(
                        TextUtils.concat(
                                folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name,
                                "\n",
                                LocaleController.formatPluralString("AccDescrUnreadCount", unreadCount)
                        )
                );
            }
            folderItem.setEmojiCacheType(folder.title_noanimate ? AnimatedEmojiDrawable.CACHE_TYPE_NOANIMATE_FOLDER : AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
            final int color = getMessagesController().folderTags ? folder.color : -1;
            folderItem.setTextAndIcon(title, 0, new FolderDrawable(getContext(), R.drawable.msg_folders, color));
            folderItem.getTextView().setEmojiColor(getThemedColor(Theme.key_featuredStickers_addButton));
            folderItem.setMinimumWidth(160);
            folderItem.setOnClickListener(e -> {
                o.dismiss();
                openFolder(folder.id);
            });
            o.addView(folderItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
//        o.setBlur(true);
        o.translate(-dp(8), -dp(4));
        o.setMaxHeight(dp(400));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.setGravity(Gravity.LEFT);
        o.show();

        return true;
    }

    private boolean openGhostModeSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;

        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.addChecked(MglaSpyConfig.isGhostModeEnabled(), "Режим призрака", () -> {
            MglaSpyConfig.setGhostModeEnabled(!MglaSpyConfig.isGhostModeEnabled());
        }, null);
        o.setBlur(true);
        o.translate(0, -dp(4));
        o.setGravity(Gravity.LEFT);
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private boolean hasUnmutedUnreadDialogs(MessagesController.DialogFilter folder) {
        final MessagesController messagesController = getMessagesController();
        final ArrayList<TLRPC.Dialog> dialogs = folder.isDefault()
                ? messagesController.getDialogs(0)
                : messagesController.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (!folder.isDefault()) {
                long dialogId = dialog.id;
                if (DialogObject.isEncryptedDialog(dialogId)) {
                    final TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                    if (encryptedChat != null) {
                        dialogId = encryptedChat.user_id;
                    }
                }
                if (!folder.includesDialog(getAccountInstance(), dialogId, dialog)) {
                    continue;
                }
            }
            if ((messagesController.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark)
                    && !messagesController.isDialogMuted(dialog.id, 0)) {
                return true;
            }
        }
        return false;
    }

    private class FolderCounterSpan extends ReplacementSpan {

        private static final float HEIGHT_DP = 17.333f;
        private final String count;
        private final boolean hasUnmutedUnreadDialogs;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float counterWidth;

        FolderCounterSpan(int count, boolean hasUnmutedUnreadDialogs) {
            this.count = String.valueOf(count);
            this.hasUnmutedUnreadDialogs = hasUnmutedUnreadDialogs;
            textPaint.setTextSize(AndroidUtilities.dpf2(11));
            textPaint.setTypeface(AndroidUtilities.bold());
            counterWidth = Math.max(dp(HEIGHT_DP - 10), textPaint.measureText(this.count)) + dp(10);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return (int) Math.ceil(dp(5) + counterWidth);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            final float left = x + dp(5);
            final float centerY = (top + bottom) / 2f + dp(1);
            final float halfHeight = dp(HEIGHT_DP) / 2f;
            backgroundPaint.setColor(getThemedColor(
                hasUnmutedUnreadDialogs ?
                    Theme.key_featuredStickers_addButton :
                    Theme.key_chats_tabUnreadUnactiveBackground
            ));
            textPaint.setColor(getThemedColor(Theme.key_actionBarDefault));
            AndroidUtilities.rectTmp.set(left, centerY - halfHeight, left + counterWidth, centerY + halfHeight);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, halfHeight, halfHeight, backgroundPaint);
            final Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
            final float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
            canvas.drawText(count, left + (counterWidth - textPaint.measureText(count)) / 2f, baseline, textPaint);
        }

    }

    private void openFolder(int folderId) {
        final int chatsPos = getStartPosition();
        if (viewPager.getCurrentPosition() == chatsPos && dialogsActivity != null) {
            dialogsActivity.scrollToFolder(folderId);
        } else {
            if (dialogsActivity == null) {
                prepareDialogsActivity(null);
            }
            pendingFolderId = folderId;
            selectTab(chatsPos, true);
            viewPager.scrollToPosition(chatsPos);
        }
    }

    public boolean openAccountSelector(View button) {
        final ArrayList<Integer> accountNumbers = new ArrayList<>();

        accountNumbers.clear();
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

        ItemOptions o = ItemOptions.makeOptions(this, button);
        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            o.add(R.drawable.msg_addbot, getString(R.string.AddAccount), () -> {
                int freeAccounts = 0;
                Integer availableAccount = null;
                for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
                    if (!UserConfig.getInstance(a).isClientActivated()) {
                        freeAccounts++;
                        if (availableAccount == null) {
                            availableAccount = a;
                        }
                    }
                }
                if (!UserConfig.hasPremiumOnAccounts()) {
                    freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);
                }
                if (freeAccounts > 0 && availableAccount != null) {
                    presentFragment(new LoginActivity(availableAccount));
                } else if (!UserConfig.hasPremiumOnAccounts()) {
                    showDialog(new LimitReachedBottomSheet(this, getContext(), TYPE_ACCOUNTS, currentAccount, null));
                }
            });
        }

        if (BuildConfig.DEBUG_PRIVATE_VERSION) {
            o.add(R.drawable.menu_download_round, "Dump Canvas", () -> AndroidUtilities.runOnUIThread(this::dumpCanvas, 1000));
        }

        if (accountNumbers.size() > 0) {
            if (o.getItemsCount() > 0) o.addGap();
            for (int acc : accountNumbers) {
                final int account = acc;
                final View btn = accountView(acc, currentAccount == acc);
                btn.setOnClickListener(v -> {
                    if (currentAccount == account) return;
                    o.dismiss();
                    if (LaunchActivity.instance != null) {
                        LaunchActivity.instance.switchToAccount(account, true);
                    }
                });
                o.addView(btn, LayoutHelper.createLinear(230, 48));
            }
        }

        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();

        HintsController.Hint.AccountSwitchHint.doNotShowAgain();

        return true;
    }

    public LinearLayout accountView(int account, boolean selected) {
        final LinearLayout btn = new LinearLayout(getContext());
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 0, 0));

        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);

        final FrameLayout avatarContainer = new FrameLayout(getContext()) {
            private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (selected) {
                    selectedPaint.setStyle(Paint.Style.STROKE);
                    selectedPaint.setStrokeWidth(dp(1.33f));
                    selectedPaint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                    canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, dp(16), selectedPaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        btn.addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final BackupImageView avatarView = new BackupImageView(getContext());
        if (selected) {
            avatarView.setScaleX(0.833f);
            avatarView.setScaleY(0.833f);
        }
        avatarView.setRoundRadius(dp(16));
        avatarView.getImageReceiver().setCurrentAccount(account);
        avatarView.setForUserOrChat(user, avatarDrawable);
        avatarContainer.addView(avatarView, LayoutHelper.createLinear(32, 32, Gravity.CENTER, 1, 1, 1, 1));

        final TextView textView = new TextView(getContext());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        textView.setText(UserObject.getUserName(user));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        btn.addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0));

        return btn;
    }

    @Override
    protected void onViewPagerScrollEnd() {
        if (tabsView != null) {
            selectTab(viewPager.getCurrentPosition(), true);
            setGestureSelectedOverride(0, false);
        }
        blur3_invalidateBlur();

        if (viewPager != null) {
            final int currentPosition = viewPager.getCurrentPosition();
            final int callsPos = positionOf(TabId.CALLS);
            final int settingsPos = positionOf(TabId.SETTINGS);
            final int profilePos = positionOf(TabId.PROFILE);
            final int chatsPos = positionOf(TabId.CHATS);
            if (dropCallsFragmentAfterPageScroll) {
                if (callsPos >= 0 && currentPosition != callsPos) {
                    dropFragmentAtPosition(callsPos);
                }
                if (settingsPos >= 0 && currentPosition != settingsPos) {
                    dropFragmentAtPosition(settingsPos);
                }
                dropCallsFragmentAfterPageScroll = false;
            }
            if (profilePos >= 0 && currentPosition != profilePos) {
                dropFragmentAtPosition(profilePos);
            }
            if (pendingFolderId != null && chatsPos >= 0 && currentPosition == chatsPos && dialogsActivity != null) {
                dialogsActivity.scrollToFolder(pendingFolderId);
                pendingFolderId = null;
            }
            if (pendingOpenSearch && chatsPos >= 0 && currentPosition == chatsPos) {
                pendingOpenSearch = false;
                final DialogsActivity dialogs = resolveDialogsActivity();
                if (dialogs != null) {
                    dialogs.search("", true);
                }
            }
        }

    }

    @Override
    protected void onViewPagerTabAnimationUpdate(boolean manual) {
        final boolean isDragByGesture = !manual;

        if (tabsView != null) {
            final float position = viewPager.getPositionAnimated();
            setGestureSelectedOverride(position, isDragByGesture);
            if (isDragByGesture) {
                selectTab(Math.round(position), true);
            }
        }

        checkUi_fadeView();
        blur3_invalidateBlur();
        contentView.invalidate();
    }


    @Override
    protected int getFragmentsCount() {
        if (tabOrder == null || tabOrder.isEmpty()) {
            loadTabOrder();
        }
        return Math.max(1, tabOrder.size());
    }

    @Override
    protected int getStartPosition() {
        if (tabOrder == null || tabOrder.isEmpty()) {
            loadTabOrder();
        }
        int chats = positionOf(TabId.CHATS);
        return chats >= 0 ? chats : 0;
    }

    private DialogsActivity dialogsActivity;

    @Override
    public boolean onBackPressed(boolean invoked) {
        final boolean result = super.onBackPressed(invoked);
        if (result) {
            final int startPosition = getStartPosition();
            if (viewPager.getCurrentPosition() != startPosition) {
                if (invoked) {
                    viewPager.scrollToPosition(startPosition);
                }
                return false;
            }
        }
        return result;
    }

    public DialogsActivity prepareDialogsActivity(Bundle bundle) {
        if (bundle == null) {
            bundle = new Bundle();
        }

        bundle.putBoolean("hasMainTabs", true);
        dialogsActivity = new DialogsActivity(bundle);
        dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
        putFragmentAtPosition(getStartPosition(), dialogsActivity);
        return dialogsActivity;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        TabId id = tabAt(position);
        if (id == null) {
            return null;
        }
        switch (id) {
            case CONTACTS: {
                Bundle args = new Bundle();
                args.putBoolean("needPhonebook", true);
                args.putBoolean("needFinishFragment", false);
                args.putBoolean("hasMainTabs", true);
                return new ContactsActivity(args);
            }
            case CALLS: {
                Bundle args = new Bundle();
                args.putBoolean("needFinishFragment", false);
                args.putBoolean("hasMainTabs", true);
                return new CallLogActivity(args);
            }
            case SETTINGS: {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", true);
                return new SettingsActivity(args);
            }
            case CHATS: {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", true);
                dialogsActivity = new DialogsActivity(args);
                dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
                return dialogsActivity;
            }
            case PROFILE: {
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                args.putBoolean("my_profile", true);
                args.putBoolean("hasMainTabs", true);
                return new ProfileActivity(args);
            }
            case TASKS:
                return new MglaPlaceholderTabActivity("Задачи", "Раздел задач скоро появится");
            case AI:
                return new MglaPlaceholderTabActivity("ИИ", "Раздел ИИ скоро появится");
            case MUSIC:
                return new MglaPlaceholderTabActivity("Музыка", "Раздел музыки скоро появится");
            default:
                return null;
        }
    }

    public DialogsActivity getDialogsActivity() {
        return dialogsActivity;
    }

    /* */

    public void selectTab(int position, boolean animated) {
        for (int a = 0; a < tabs.length; a++) {
            tabs[a].setSelected(a == position, animated);
        }
    }

    public void setGestureSelectedOverride(float animatedPosition, boolean allow) {
        for (int index = 0; index < tabs.length; index++) {
            final float visibility = Math.max(0, 1f - Math.abs(index - animatedPosition));
            tabs[index].setGestureSelectedOverride(visibility, allow);
        }
        if (tabsView != null) {
            tabsView.invalidate();
        }
    }


    /* * */

    public interface TabFragmentDelegate {
        default boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
            return false;
        }

        default void onParentScrollToTop() {

        }

        default BlurredBackgroundSourceRenderNode getGlassSource() {
            return null;
        }
    }

    @Override
    protected boolean canScrollForward(MotionEvent ev) {
        return canScrollInternal(ev, true);
    }

    @Override
    protected boolean canScrollBackward(MotionEvent ev) {
        return canScrollInternal(ev, false);
    }

    private boolean canScrollInternal(MotionEvent ev, boolean forward) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof TabFragmentDelegate) {
            final TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
            return delegate.canParentTabsSlide(ev, forward);

        }

        return false;
    }


    /* * */

    private int navigationBarHeight;
    private int insetLeft;
    private int insetRight;

    @NonNull
    @Override
    protected WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);

        insetLeft = systemInsets.left;
        insetRight = systemInsets.right;

        navigationBarHeight = systemInsets.bottom;
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        updateLayoutWrapper.setPadding(0, 0, 0, navigationBarHeight);

        ViewGroup.MarginLayoutParams lp;
        {
            final int height = navigationBarHeight + updateLayoutHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
            lp = (ViewGroup.MarginLayoutParams) fadeView.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                fadeView.setLayoutParams(lp);
            }
        }
        {
            int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
            if (tabletLayout) {
                bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            }
            lp = (ViewGroup.MarginLayoutParams) viewPager.getLayoutParams();
            if (lp.bottomMargin != bottomMargin || lp.leftMargin != systemInsets.left || lp.rightMargin != systemInsets.right) {
                lp.leftMargin = systemInsets.left;
                lp.rightMargin = systemInsets.right;
                lp.bottomMargin = bottomMargin;
                viewPager.setLayoutParams(lp);
            }
        }

        tabsViewWrapper.setPadding(systemInsets.left, 0, systemInsets.right, navigationBarHeight);
        updateTabsIslandMaxWidth();

        final WindowInsetsCompat consumed = isUpdateLayoutVisible ?
            insets.inset(0, 0, 0, navigationBarHeight) : insets;

        checkUi_tabsPosition();
        checkUi_fadeView();

        return super.onApplyWindowInsets(v, consumed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.notificationsCountUpdated || id == NotificationCenter.updateInterfaces) {
            checkUnreadCount(fragmentView != null && fragmentView.isAttachedToWindow());
        } else if (id == NotificationCenter.appUpdateLoading) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(null);
                updateLayout.updateAppUpdateViews(currentAccount, true);
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(args);
            }
        } else if (id == NotificationCenter.appUpdateAvailable) {
            if (updateLayout != null && LaunchActivity.instance != null) {
                updateLayout.updateAppUpdateViews(currentAccount, LaunchActivity.instance.getMainFragmentsStackSize() == 1);
            }
        } else if (id == NotificationCenter.needSetDayNightTheme) {
            clearAllHiddenFragments();
        } else if (id == NotificationCenter.callTabsVisibleToggled) {
            if (!MglaMainTabsConfig.isCustomized() && getContext() != null) {
                rematerializeTabFragments();
                rebuildTabViews(getContext(), true);
                if (viewPager != null) {
                    viewPager.rebuild(false);
                    final int chatsPos = getStartPosition();
                    viewPager.scrollToPosition(chatsPos);
                    selectTab(chatsPos, true);
                }
            }
            dropCallsFragmentAfterPageScroll = true;
        } else if (id == NotificationCenter.mglaMainTabsChanged) {
            if (getContext() != null) {
                applySearchButtonVisibility(true);
                ArrayList<TabId> newOrder = MglaMainTabsConfig.getEnabledTabs(getUserConfig().showCallsTab);
                if (newOrder.isEmpty()) {
                    newOrder = MglaMainTabsConfig.defaultOrder(getUserConfig().showCallsTab);
                }
                if (!newOrder.equals(tabOrder)) {
                    rematerializeTabFragments();
                    rebuildTabViews(getContext(), true);
                    if (viewPager != null) {
                        viewPager.rebuild(false);
                        final int chatsPos = getStartPosition();
                        viewPager.scrollToPosition(chatsPos);
                        selectTab(chatsPos, false);
                    }
                }
            }
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            GlassTabView profileTab = tabView(TabId.PROFILE);
            if (profileTab != null) {
                profileTab.updateUserAvatar(currentAccount);
            }
        } else if (id == NotificationCenter.contactsPermissionBadgeCheck) {
            checkContactsTabBadge();
        }
    }

    private NotificationCenter.ObserversGroup observersGroup;

    @Override
    public boolean onFragmentCreate() {
        observersGroup = NotificationCenter.getInstance(currentAccount)
            .createObserversGroup(this)
            .add(NotificationCenter.fileLoaded)
            .add(NotificationCenter.fileLoadProgressChanged)
            .add(NotificationCenter.fileLoadFailed)
            .add(NotificationCenter.notificationsCountUpdated)
            .add(NotificationCenter.updateInterfaces)
            .add(NotificationCenter.callTabsVisibleToggled)
            .add(NotificationCenter.mainUserInfoChanged)
            .add(NotificationCenter.contactsPermissionBadgeCheck)
            .addGlobal(NotificationCenter.mglaMainTabsChanged)
            .addGlobal(NotificationCenter.appUpdateAvailable)
            .addGlobal(NotificationCenter.appUpdateLoading)
            .addGlobal(NotificationCenter.needSetDayNightTheme);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        Bulletin.removeDelegate(this);
        Bulletin.removeDelegate(contentView);

        if (observersGroup != null) {
            observersGroup.removeAllObservers();
            observersGroup = null;
        }

        super.onFragmentDestroy();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TABS_VISIBLE) {
            checkUi_tabsPosition();
            checkUi_fadeView();
        }
    }

    private void checkUi_fadeView() {
        if (viewPager == null || fadeView == null) {
            return;
        }

        final float animatedPosition = viewPager.getPositionAnimated();
        final int profilePos = positionOf(TabId.PROFILE);
        final float isProfile = profilePos < 0 ? 0f
                : 1f - MathUtils.clamp(Math.abs(profilePos - animatedPosition), 0, 1);
        final float hide = 1f - AndroidUtilities.getNavigationBarThirdButtonsFactor(0, 1f, navigationBarHeight);
        float alpha = (1f - isProfile * hide) * animatorTabsVisible.getFloatValue();
        if (tabletLayout) {
            alpha = 0.0f;
        }

        fadeView.setAlpha(alpha);
        fadeView.setTranslationY(isProfile * dp(48));
        fadeView.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
    }

    private void checkUi_tabsPosition() {
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        final int normalY = -(updateLayoutHeight);
        final int hiddenY = normalY + dp(40);

        final float factor = animatorTabsVisible.getFloatValue();

        tabsViewWrapper.setTranslationY(lerp(hiddenY, normalY, factor));
        tabsView.setClickable(factor > 1);
        tabsView.setEnabled(factor > 1);
        tabsView.setAlpha(factor);
        tabsView.setVisibility(factor > 0 ? View.VISIBLE : View.GONE);
        if (searchButton != null && MglaMainTabsConfig.isSearchButtonEnabled()) {
            final boolean interactive = factor > 0.99f;
            searchButton.setAlpha(factor);
            searchButton.setVisibility(factor > 0 ? View.VISIBLE : View.GONE);
            // Must stay clickable when fully shown (factor == 1). Parent tabsView uses factor > 1
            // only because child GlassTabViews handle their own clicks.
            searchButton.setEnabled(interactive);
            searchButton.setClickable(interactive);
        }
    }

    /** Outer inset of the island glass relative to {@link #tabsView} bounds (blur drawable padding). */
    private int getIslandGlassInset() {
        // Must match tabsViewBackground.setPadding(MAIN_TABS_MARGIN - 0.334f).
        return dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f);
    }

    /**
     * Island blur is inset inside tabsView, search pill is not — without compensation the
     * centered layout-box sits optically to the right. Pull search into the dead inset and
     * mirror the same inset on the trailing side so visual margins match.
     */
    private void syncSearchButtonSideMargins() {
        if (searchButton == null) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) searchButton.getLayoutParams();
        if (lp == null) {
            return;
        }
        final boolean show = MglaMainTabsConfig.isSearchButtonEnabled();
        final int inset = getIslandGlassInset();
        final int gap = dp(MglaMainTabsConfig.SEARCH_BUTTON_GAP);
        if (show) {
            // layout gap = visual gap − island's trailing glass inset (may be negative → overlap dead zone)
            lp.leftMargin = gap - inset;
            lp.rightMargin = inset;
        } else {
            lp.leftMargin = gap;
            lp.rightMargin = 0;
        }
        lp.bottomMargin = dp(DialogsActivity.MAIN_TABS_MARGIN);
        searchButton.setLayoutParams(lp);
    }

    private void updateTabsIslandMaxWidth() {
        if (tabsView == null) {
            return;
        }
        int wrapperW = tabsViewWrapper != null && tabsViewWrapper.getWidth() > 0
                ? tabsViewWrapper.getWidth() - tabsViewWrapper.getPaddingLeft() - tabsViewWrapper.getPaddingRight()
                : AndroidUtilities.displaySize.x - insetLeft - insetRight;
        // Leave at least MAIN_TABS_MARGIN on each side of the centered island(+search) group.
        int avail = wrapperW - dp(DialogsActivity.MAIN_TABS_MARGIN * 2);
        if (MglaMainTabsConfig.isSearchButtonEnabled()) {
            avail -= dp(MglaMainTabsConfig.SEARCH_BUTTON_SIZE + MglaMainTabsConfig.SEARCH_BUTTON_GAP);
        }
        tabsView.setMaxWidth(Math.max(dp(180), avail));
        syncSearchButtonSideMargins();
    }

    private void applySearchButtonVisibility(boolean animated) {
        if (searchButton == null || tabsBottomRow == null) {
            return;
        }
        final boolean show = MglaMainTabsConfig.isSearchButtonEnabled();
        // GONE removes the view from LinearLayout measure so the island reclaims width.
        searchButton.setVisibility(show ? View.VISIBLE : View.GONE);
        syncSearchButtonSideMargins();
        updateTabsIslandMaxWidth();
        if (tabsView != null) {
            tabsView.requestLayout();
        }
        tabsBottomRow.requestLayout();
    }

    private DialogsActivity resolveDialogsActivity() {
        if (dialogsActivity != null) {
            return dialogsActivity;
        }
        final int chatsPos = positionOf(TabId.CHATS);
        if (chatsPos < 0) {
            return null;
        }
        final FragmentState state = fragmentsArr.get(chatsPos);
        if (state != null && state.fragment instanceof DialogsActivity) {
            dialogsActivity = (DialogsActivity) state.fragment;
            return dialogsActivity;
        }
        return null;
    }

    private void openDialogsSearch() {
        final int chatsPos = positionOf(TabId.CHATS);
        if (chatsPos < 0) {
            return;
        }
        if (viewPager != null && viewPager.getCurrentPosition() != chatsPos) {
            pendingOpenSearch = true;
            selectTab(chatsPos, true);
            viewPager.scrollToPosition(chatsPos);
            return;
        }
        final DialogsActivity dialogs = resolveDialogsActivity();
        if (dialogs != null) {
            dialogs.search("", true);
        }
    }

    private void checkUi_callTabVisible(boolean callTabsVisible, boolean animated) {
        // Legacy mutual exclusivity — only when the user hasn't customized the island.
        if (MglaMainTabsConfig.isCustomized() || tabsView == null) {
            return;
        }
        GlassTabView settings = tabView(TabId.SETTINGS);
        GlassTabView calls = tabView(TabId.CALLS);
        if (settings != null) {
            tabsView.setViewVisible(settings, !callTabsVisible, animated);
        }
        if (calls != null) {
            tabsView.setViewVisible(calls, callTabsVisible, animated);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = this::blur3_updateColors;
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_dialogBackground));

        return themeDescriptions;
    }

    /* * */

    private class MainTabsActivityControllerImpl implements MainTabsActivityController {
        @Override
        public void setTabsVisible(boolean visible) {
            animatorTabsVisible.setValue(visible, true);
        }
    }


    /* Slide */

    @Override
    public boolean canBeginSlide() {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null && fragment.canBeginSlide();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onBeginSlide();
        }
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onSlideProgress(isOpen, progress);
        }
    }

    @Override
    public Animator getCustomSlideTransition(boolean topFragment, boolean backAnimation, float distanceToMove) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null ? fragment.getCustomSlideTransition(topFragment, backAnimation, distanceToMove) : null;
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.prepareFragmentToSlide(topFragment, beginSlide);
        }
    }


    private HintView2 accountSwitchHint;
    private boolean accountSwitchHintShown;

    private void showAccountChangeHint() {
        if (accountSwitchHintShown) return;

        if (accountSwitchHint == null && HintsController.Hint.AccountSwitchHint.show()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (getContext() == null || tabs == null) return;

                final View v = tabView(TabId.PROFILE);
                if (v == null) {
                    return;
                }
                final float translate = (contentView.getWidth() - ((tabsView.getX() + v.getX()) + v.getWidth()) + v.getWidth() / 2f) / AndroidUtilities.density;

                accountSwitchHint = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
                accountSwitchHint.setTranslationY(-navigationBarHeight + dp(4));
                accountSwitchHint.setPadding(dp(7.33f), 0, dp(7.33f), 0);
                accountSwitchHint.setMultilineText(false);
                accountSwitchHint.setCloseButton(true);
                accountSwitchHint.setText(getString(R.string.SwitchAccountHint));
                accountSwitchHint.setJoint(1, -translate + 7.33f);
                contentView.addView(accountSwitchHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0, 0, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
                accountSwitchHint.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(accountSwitchHint));
                accountSwitchHint.setDuration(8000);
                accountSwitchHint.show();

                HintsController.Hint.AccountSwitchHint.increment();
            }, 1500);
        }

        accountSwitchHintShown = true;
    }


    /* * */

    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceTabGlass;

    private final RectF fragmentPosition = new RectF();
    private void blur3_invalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || iBlur3SourceTabGlass == null || fragmentView == null) {
            return;
        }

        final int width = fragmentView.getMeasuredWidth();
        final int height = fragmentView.getMeasuredHeight();

        iBlur3SourceTabGlass.setSize(width, height);
        iBlur3SourceTabGlass.updateDisplayListIfNeeded();
    }

    private void blur3_updateFadeColors() {
        iBlur3SourceColor.setColor(getEstBackgroundColor());
        if (fadeView != null) {
            fadeView.invalidate();
        }
    }

    private void blur3_updateColors() {
        blur3_updateFadeColors();
        if (tabsViewBackground != null) {
            tabsViewBackground.updateColors();
        }
        if (searchButtonBackground != null) {
            searchButtonBackground.updateColors();
        }
        if (searchButtonIcon != null) {
            searchButtonIcon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_glass_tabUnselected), PorterDuff.Mode.SRC_IN));
        }
        blur3_invalidateBlur();
        if (fadeView != null) {
            fadeView.invalidate();
        }
        if (tabsView != null) {
            tabsView.invalidate();
        }
        if (tabs != null) {
            for (GlassTabView tabView : tabs) {
                tabView.updateColorsLottie();
            }
        }
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }
}
