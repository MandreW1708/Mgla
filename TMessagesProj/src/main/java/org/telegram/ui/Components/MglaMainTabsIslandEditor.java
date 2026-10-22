package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaMainTabsConfig;
import org.telegram.messenger.MglaMainTabsConfig.TabId;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

/**
 * Interactive preview of the bottom main-tabs island: drag to reorder, tap to remove
 * (except locked {@link MglaMainTabsConfig#isLocked}), chips below to add.
 * <p>
 * Plain {@link LinearLayout} — RecyclerListView + ItemTouchHelper was unreliable
 * inside the appearance {@link android.widget.ScrollView}.
 */
@SuppressLint("ViewConstructor")
public class MglaMainTabsIslandEditor extends LinearLayout {

    public interface Listener {
        void onTabsChanged(ArrayList<TabId> order);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final int currentAccount;
    private final Listener listener;

    private final ArrayList<TabId> order = new ArrayList<>();
    private final LinearLayout chipsRow;
    private final LinearLayout tabsRow;
    private final LinearLayout previewRow;
    private final FrameLayout islandWrap;
    private final FrameLayout searchButtonPreview;
    private int previewSelected = 0;
    private boolean searchButtonEnabled;

    private int dragFrom = -1;
    private float dragAnchorX;
    private float dragStartRawX;
    private boolean dragActive;
    private final int touchSlop;

    public MglaMainTabsIslandEditor(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, Listener listener) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.currentAccount = currentAccount;
        this.listener = listener;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(VERTICAL);

        boolean showCalls = UserConfig.getInstance(currentAccount).showCallsTab;
        order.addAll(MglaMainTabsConfig.getEnabledTabs(showCalls));
        ensureRequiredTabs();
        ensurePreviewIndex();

        TextView hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        hintView.setText("Перетащите вкладку, чтобы изменить порядок. Нажмите, чтобы убрать. Максимум 7 вкладок.");
        hintView.setPadding(dp(4), 0, dp(4), dp(10));
        addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        previewRow = new LinearLayout(context);
        previewRow.setOrientation(HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setClipChildren(false);

        islandWrap = new FrameLayout(context);
        islandWrap.setBackground(createIslandBackground());
        islandWrap.setPadding(dp(4), dp(4), dp(4), dp(4));
        islandWrap.setClipChildren(false);

        searchButtonPreview = new FrameLayout(context);
        searchButtonPreview.setBackground(createIslandBackground());
        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider), PorterDuff.Mode.SRC_IN));
        searchIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        searchButtonPreview.addView(searchIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        searchButtonEnabled = MglaMainTabsConfig.isSearchButtonEnabled();

        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsRow.setClipChildren(false);
        islandWrap.addView(tabsRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT));

        previewRow.setGravity(Gravity.CENTER);
        previewRow.addView(islandWrap, LayoutHelper.createLinear(0, DialogsActivity.MAIN_TABS_HEIGHT + 8, 1f, Gravity.CENTER_VERTICAL));
        previewRow.addView(searchButtonPreview, LayoutHelper.createLinear(
                MglaMainTabsConfig.SEARCH_BUTTON_SIZE,
                MglaMainTabsConfig.SEARCH_BUTTON_SIZE,
                Gravity.CENTER_VERTICAL,
                MglaMainTabsConfig.SEARCH_BUTTON_GAP, 0, 0, 0));
        applySearchButtonPreview();
        addView(previewRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT + 8, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        // Width is 0 in the constructor — rebuild once the island has a real size.
        islandWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int ow = or - ol;
            if (w > 0 && w != ow && !dragActive) {
                // post: mutating children during layout leaves the row blank.
                v.post(this::rebuildTabs);
            }
        });

        TextView availableTitle = new TextView(context);
        availableTitle.setText("Добавить");
        availableTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        availableTitle.setTypeface(AndroidUtilities.bold());
        availableTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        availableTitle.setPadding(dp(4), 0, dp(4), dp(8));
        addView(availableTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsScroll.addView(chipsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rebuildTabs();
        rebuildChips();
    }

    private GradientDrawable createIslandBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
        bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), 0.96f));
        bg.setStroke(Math.max(1, dp(1)), Theme.getColor(Theme.key_divider, resourcesProvider));
        return bg;
    }

    private void applySearchButtonPreview() {
        searchButtonPreview.setVisibility(searchButtonEnabled ? VISIBLE : GONE);
        if (!dragActive) {
            rebuildTabs();
        }
        previewRow.requestLayout();
    }

    public void setSearchButtonEnabled(boolean enabled) {
        if (searchButtonEnabled == enabled) {
            return;
        }
        searchButtonEnabled = enabled;
        applySearchButtonPreview();
    }

    private void ensureRequiredTabs() {
        if (!order.contains(TabId.CHATS)) {
            order.add(0, TabId.CHATS);
        }
        if (!order.contains(TabId.SETTINGS)) {
            order.add(TabId.SETTINGS);
        }
    }

    private void ensurePreviewIndex() {
        if (order.isEmpty()) {
            previewSelected = 0;
            return;
        }
        if (previewSelected < 0 || previewSelected >= order.size()) {
            int chats = order.indexOf(TabId.CHATS);
            previewSelected = chats >= 0 ? chats : 0;
        }
    }

    private void disallowParentIntercept() {
        ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
            parent = parent.getParent();
        }
    }

    private int tabCellWidth() {
        int avail = tabsRow.getWidth();
        if (avail <= 0 && islandWrap.getWidth() > 0) {
            avail = islandWrap.getWidth() - islandWrap.getPaddingLeft() - islandWrap.getPaddingRight();
        }
        if (avail <= 0) {
            avail = AndroidUtilities.displaySize.x - dp(64);
            if (searchButtonEnabled) {
                avail -= dp(MglaMainTabsConfig.SEARCH_BUTTON_SIZE + MglaMainTabsConfig.SEARCH_BUTTON_GAP);
            }
        }
        return Math.max(dp(40), avail / Math.max(1, order.size()));
    }

    private void rebuildTabs() {
        tabsRow.removeAllViews();
        final int cellW = tabCellWidth();
        final int total = order.size();
        for (int i = 0; i < total; i++) {
            final TabId id = order.get(i);
            EditorTabCell cell = new EditorTabCell(getContext());
            cell.bind(id, i == previewSelected, total);
            cell.setTag(i);
            cell.setOnClickListener(null);
            cell.setOnTouchListener(this::handleTabTouch);
            tabsRow.addView(cell, new LinearLayout.LayoutParams(cellW, dp(DialogsActivity.MAIN_TABS_HEIGHT)));
        }
    }

    private boolean handleTabTouch(View v, MotionEvent event) {
        if (!(v instanceof EditorTabCell)) {
            return false;
        }
        EditorTabCell cell = (EditorTabCell) v;
        Object tag = cell.getTag();
        if (!(tag instanceof Integer)) {
            return false;
        }
        int index = (Integer) tag;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragFrom = index;
                dragStartRawX = event.getRawX();
                dragAnchorX = event.getRawX();
                dragActive = false;
                cell.setPressed(true);
                return true; // own the whole gesture (tap or drag)
            case MotionEvent.ACTION_MOVE:
                if (dragFrom < 0) {
                    return true;
                }
                float dx = event.getRawX() - dragStartRawX;
                if (!dragActive) {
                    if (Math.abs(dx) < touchSlop) {
                        return true;
                    }
                    // Press + move past slop → drag.
                    dragActive = true;
                    cell.setPressed(false);
                    cell.animate().cancel();
                    cell.setAlpha(0.85f);
                    cell.setScaleX(1.06f);
                    cell.setScaleY(1.06f);
                    cell.setTranslationZ(dp(8)); // don't bringToFront — that reorders LinearLayout children
                    disallowParentIntercept();
                }
                cell.setTranslationX(event.getRawX() - dragAnchorX);
                return true;
            case MotionEvent.ACTION_UP:
                cell.setPressed(false);
                if (dragActive) {
                    commitDrag(cell, event.getRawX());
                } else {
                    tryRemoveAt(index);
                }
                dragFrom = -1;
                dragActive = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cell.setPressed(false);
                if (dragActive) {
                    // Cancel without committing a new order.
                    cell.setTranslationZ(0);
                    cell.setTranslationX(0);
                    cell.setScaleX(1f);
                    cell.setScaleY(1f);
                    cell.setAlpha(1f);
                }
                dragFrom = -1;
                dragActive = false;
                return true;
        }
        return false;
    }

    private void commitDrag(EditorTabCell cell, float rawX) {
        int target = dragFrom;
        int[] loc = new int[2];
        for (int i = 0; i < tabsRow.getChildCount(); i++) {
            View other = tabsRow.getChildAt(i);
            other.getLocationOnScreen(loc);
            float left = loc[0];
            float right = left + other.getWidth();
            float mid = (left + right) / 2f;
            if (rawX < mid) {
                target = i;
                break;
            }
            target = i;
        }
        if (dragFrom >= 0 && target >= 0 && target < order.size() && target != dragFrom) {
            TabId moved = order.remove(dragFrom);
            order.add(target, moved);
            if (previewSelected == dragFrom) {
                previewSelected = target;
            } else if (dragFrom < previewSelected && target >= previewSelected) {
                previewSelected--;
            } else if (dragFrom > previewSelected && target <= previewSelected) {
                previewSelected++;
            }
        }
        cell.setTranslationZ(0);
        cell.setTranslationX(0);
        cell.setScaleX(1f);
        cell.setScaleY(1f);
        cell.setAlpha(1f);
        rebuildTabs();
        persist();
    }

    private void tryRemoveAt(int position) {
        if (position < 0 || position >= order.size()) {
            return;
        }
        TabId id = order.get(position);
        if (MglaMainTabsConfig.isLocked(id)) {
            previewSelected = position;
            rebuildTabs();
            if (id == TabId.CHATS) {
                showHint("А как ты тогда попадешь в чаты?");
            } else if (id == TabId.SETTINGS) {
                showHint("А как ты тогда попадешь в настройки?");
            }
            return;
        }
        order.remove(position);
        ensureRequiredTabs();
        ensurePreviewIndex();
        rebuildTabs();
        rebuildChips();
        persist();
    }

    private void rebuildChips() {
        chipsRow.removeAllViews();
        ArrayList<TabId> hidden = new ArrayList<>();
        for (TabId id : TabId.values()) {
            if (!order.contains(id) && MglaMainTabsConfig.isTabAllowed(id)) {
                hidden.add(id);
            }
        }
        if (hidden.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("Все вкладки добавлены");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            empty.setPadding(dp(4), dp(6), dp(4), dp(6));
            chipsRow.addView(empty);
            return;
        }
        for (TabId id : hidden) {
            TextView chip = new TextView(getContext());
            chip.setText("+ " + MglaMainTabsConfig.titleFor(id));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), 0.12f));
            chip.setBackground(bg);
            final TabId addId = id;
            chip.setOnClickListener(v -> {
                if (!MglaMainTabsConfig.isTabAllowed(addId)) {
                    showHint("Вкладка ИИ доступна с Mgla Premium");
                    return;
                }
                if (order.size() >= MglaMainTabsConfig.MAX_TABS) {
                    showHint("Можно добавить не больше " + MglaMainTabsConfig.MAX_TABS + " вкладок");
                    return;
                }
                order.add(addId);
                rebuildTabs();
                rebuildChips();
                persist();
            });
            chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
    }

    private void persist() {
        if (listener != null) {
            listener.onTabsChanged(new ArrayList<>(order));
        }
    }

    private void showHint(String text) {
        Context ctx = getContext();
        Activity activity = ctx instanceof Activity ? (Activity) ctx : AndroidUtilities.findActivity(ctx);
        if (activity != null) {
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof FrameLayout) {
                BulletinFactory.of((FrameLayout) content, resourcesProvider)
                        .createSimpleBulletin(R.raw.error, text)
                        .show();
                return;
            }
        }
        android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    public static GlassTabView createTabView(Context context, Theme.ResourcesProvider rp, int account, TabId id) {
        switch (id) {
            case CHATS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CHATS, MglaMainTabsConfig.titleFor(id));
            case CONTACTS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CONTACTS, MglaMainTabsConfig.titleFor(id));
            case SETTINGS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.SETTINGS, MglaMainTabsConfig.titleFor(id));
            case CALLS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CALLS, MglaMainTabsConfig.titleFor(id));
            case PROFILE:
                return GlassTabView.createAvatar(context, rp, account, R.string.MainTabsProfile);
            case TASKS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CHECKLIST, MglaMainTabsConfig.titleFor(id));
            case AI:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.MODELS, MglaMainTabsConfig.titleFor(id));
            case MUSIC:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.MUSIC, MglaMainTabsConfig.titleFor(id));
            default:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CHATS, MglaMainTabsConfig.titleFor(id));
        }
    }

    private class EditorTabCell extends FrameLayout {
        public EditorTabCell(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setClipChildren(false);
            setClipToPadding(false);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        }

        void bind(TabId id, boolean selected, int total) {
            removeAllViews();
            GlassTabView view = createTabView(getContext(), resourcesProvider, currentAccount, id);
            // Keep enabled so Lottie/icons actually draw; block input on the cell instead.
            view.setClickable(false);
            view.setFocusable(false);
            view.setDuplicateParentStateEnabled(false);
            view.setSelected(selected, false);
            view.updateColorsLottie();
            if (total >= 6) {
                view.setTextSizeDp(0);
            } else if (total >= 5) {
                view.setTextSizeDp(10);
            } else if (total >= 4) {
                view.setTextSizeDp(11);
            } else {
                view.setTextSizeDp(12);
            }
            addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
    }
}
