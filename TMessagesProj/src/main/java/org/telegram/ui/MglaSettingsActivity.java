package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.MglaMemoryConfig;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.wsbypass.MglaWsBypass;
public class MglaSettingsActivity extends MglaBaseSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_PRIVACY = 1;
    private static final int MENU_TERMS = 2;

    private SettingCell dpiBypassCell;
    private SettingCell memoryCell;
    private View memoryDivider;

    public MglaSettingsActivity() {
        this(null);
    }

    public MglaSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.dpiBypassChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaHeaderSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaSubscriptionHintChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.dpiBypassChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaHeaderSettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaSubscriptionHintChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        MglaMemoryConfig.clearIfUnauthorized();
        updateMemoryCell();
        updateDpiBypassCell();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dpiBypassChanged) {
            updateDpiBypassCell();
        } else if (id == NotificationCenter.mglaHeaderSettingsChanged
                || id == NotificationCenter.mglaSubscriptionHintChanged) {
            MglaMemoryConfig.clearIfUnauthorized();
            updateMemoryCell();
        }
    }

    private void updateDpiBypassCell() {
        if (dpiBypassCell != null) {
            dpiBypassCell.set(
                IconBackgroundColors.BURGUNDY.top, IconBackgroundColors.BURGUNDY.bottom,
                R.drawable.mgla_dpi_shield, "Подключение без VPN", null,
                bypassStatusLabel(), false, null
            );
        }
    }

    private void openMemory() {
        if (!MglaMemoryConfig.isActive()) {
            return;
        }
        if (!MglaSubscription.canUseLiveMemory()) {
            showLiveMemoryUpsell();
            return;
        }
        if (!MglaMemoryConfig.isEnabled()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                "Сначала включите живую память в разделе «Искусственный интеллект»").show();
            MglaAiSettingsActivity ai = new MglaAiSettingsActivity();
            presentFragment(ai);
            ai.highlightMemoryToggle();
            return;
        }
        presentFragment(new MglaMemoryActivity());
    }

    private void showLiveMemoryUpsell() {
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.star_premium_2,
                "Живая память доступна с любой подпиской Mgla Premium",
                "Оформить",
                () -> presentFragment(new MglaPremiumActivity())
        ).show();
    }

    private void updateMemoryCell() {
        boolean allowed = MglaMemoryConfig.isActive();
        int visibility = allowed ? View.VISIBLE : View.GONE;
        if (memoryDivider != null) {
            memoryDivider.setVisibility(visibility);
        }
        if (memoryCell == null) {
            return;
        }
        memoryCell.setVisibility(visibility);
        if (!allowed) {
            return;
        }
        boolean unlocked = MglaSubscription.canUseLiveMemory();
        boolean on = unlocked && MglaMemoryConfig.isEnabled();
        memoryCell.set(
            IconBackgroundColors.BURGUNDY.top, IconBackgroundColors.BURGUNDY.bottom,
            R.drawable.msg_saved, liveMemoryPremiumLabel(getContext()), null,
            on ? "Вкл" : "Выкл", false, "alpha"
        );
        // Без подписки (и при выкл.) пункт выглядит неактивным; клик → upsell / включение.
        memoryCell.setAlpha(on ? 1f : 0.4f);
        memoryCell.setEnabled(true);
        memoryCell.setClickable(true);
    }

    /** Gold premium star to the left of «Живая память». */
    private static CharSequence liveMemoryPremiumLabel(Context context) {
        SpannableStringBuilder sb = new SpannableStringBuilder("  Живая память");
        Drawable star = context.getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
        int size = dp(16);
        star.setBounds(0, 0, size, size);
        star.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_starsGradient1), PorterDuff.Mode.SRC_IN));
        sb.setSpan(new ImageSpan(star, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static String bypassStatusLabel() {
        if (MglaWsBypass.getInstance().isEnabled()) {
            return "Авто";
        }
        if (MglaDpiBypass.getInstance().isEnabled()) {
            return "ByeDPI";
        }
        return "Выкл";
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки Mgla");
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.setContentDescription("Ещё");
        other.addSubItem(MENU_PRIVACY, "Политика конфиденциальности");
        other.addSubItem(MENU_TERMS, "Пользовательское соглашение");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_PRIVACY) {
                    openUrl(BuildVars.MGLA_PRIVACY_POLICY_URL);
                } else if (id == MENU_TERMS) {
                    openUrl(BuildVars.MGLA_TERMS_OF_SERVICE_URL);
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        rootLayout.addView(createHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 8));

        LinearLayout mainBlock = createBlock(context, "Главное");
        addMenuItem(mainBlock, () -> presentFragment(new MglaMainSettingsActivity()),
            IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom, R.drawable.settings_features, "Общие настройки");
        mainBlock.addView(MglaUi.createDivider(context));
        addMenuItem(mainBlock, () -> presentFragment(new MglaChatsSettingsActivity()),
            IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom, R.drawable.filled_chatlist2, "Чаты");
        mainBlock.addView(MglaUi.createDivider(context));
        addMenuItem(mainBlock, () -> presentFragment(new MglaNotificationsSettingsActivity()),
            IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom, R.drawable.settings_sounds, "Уведомления");
        mainBlock.addView(MglaUi.createDivider(context));
        addMenuItem(mainBlock, () -> presentFragment(new MglaAppearanceSettingsActivity()),
            IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.msg_palette, "Внешний вид");
        mainBlock.addView(MglaUi.createDivider(context));
        addMenuItem(mainBlock, () -> presentFragment(new MglaPlayerSettingsActivity()),
            IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.files_music, "Плеер");
        mainBlock.addView(MglaUi.createDivider(context));
        addMenuItem(mainBlock, () -> presentFragment(new MglaCameraSettingsActivity()),
            IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.filled_premium_camera, "Камера");
        mainBlock.addView(MglaUi.createDivider(context));
        dpiBypassCell = addMenuItem(mainBlock, () -> presentFragment(new MglaDpiBypassActivity()),
            IconBackgroundColors.BURGUNDY.top, IconBackgroundColors.BURGUNDY.bottom, R.drawable.mgla_dpi_shield, "Подключение без VPN",
            bypassStatusLabel());
        mainBlock.addView(MglaUi.createDivider(context));
        addMenuItem(mainBlock, () -> presentFragment(new MglaAiSettingsActivity()),
            IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.input_ai, "Искусственный интеллект");
        memoryDivider = MglaUi.createDivider(context);
        mainBlock.addView(memoryDivider);
        memoryCell = addMenuItem(mainBlock, this::openMemory,
            IconBackgroundColors.BURGUNDY.top, IconBackgroundColors.BURGUNDY.bottom, R.drawable.msg_saved,
            liveMemoryPremiumLabel(context), "Выкл", "alpha");
        updateMemoryCell();
        rootLayout.addView(mainBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        LinearLayout extraBlock = createBlock(context, "Дополнительно");
        addMenuItem(extraBlock, () -> openTelegramUsername(BuildVars.MGLA_DEV_CHANNEL_USERNAME),
            IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.settings_channel, "Канал разработчика");
        extraBlock.addView(MglaUi.createDivider(context));
        addMenuItem(extraBlock, this::openDonate,
            IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.wallet_buy, "Поддержать проект");
        extraBlock.addView(MglaUi.createDivider(context));
        addMenuItem(extraBlock, () -> openMglaGuide(),
            IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom, R.drawable.menu_website, "Инструкция");
        extraBlock.addView(MglaUi.createDivider(context));
        addMenuItem(extraBlock, this::openSupport,
            IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom, R.drawable.msg_help, "Поддержка");
        rootLayout.addView(extraBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        TextInfoPrivacyCell statsNote = new TextInfoPrivacyCell(context);
        statsNote.setText("Собираются только обезличенные модели устройств и частота использования функций, а также Telegram ID — для выдачи подписки. Личные сообщения, контакты и содержимое чатов не собираются.");
        statsNote.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        rootLayout.addView(statsNote, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, AndroidUtilities.navigationBarHeight + 8));

        fragmentView = scrollView;
        return fragmentView;
    }

    private View createHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout iconContainer = new FrameLayout(context);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setCornerRadius(dp(18));
        iconBg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        iconContainer.setBackground(iconBg);
        iconContainer.setClipToOutline(true);
        iconContainer.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        ImageView iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Never bind R.mipmap.ic_launcher directly — adaptive icons are huge to rasterize.
        // Returning to this hub from subsections was stalling ~1s on every back animation.
        iconView.setImageDrawable(new BitmapDrawable(context.getResources(), getHubIconBitmap(context)));
        iconContainer.addView(iconView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        header.addView(iconContainer, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        TextView titleView = new TextView(context);
        titleView.setText("Mgla");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        header.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 4));

        LinearLayout versionRow = new LinearLayout(context);
        versionRow.setOrientation(LinearLayout.HORIZONTAL);
        versionRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView versionView = new TextView(context);
        versionView.setText(BuildVars.MGLA_VERSION_STRING);
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        versionView.setGravity(Gravity.CENTER);
        versionRow.addView(versionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        TextView betaBadge = new TextView(context);
        betaBadge.setText("beta");
        betaBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        betaBadge.setTypeface(AndroidUtilities.bold());
        betaBadge.setTextColor(0xFFFFFFFF);
        betaBadge.setGravity(Gravity.CENTER);
        betaBadge.setPadding(dp(5), dp(1), dp(5), dp(2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(4));
        badgeBg.setColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        betaBadge.setBackground(badgeBg);
        versionRow.addView(betaBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        header.addView(versionRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        return header;
    }

    private static Bitmap hubIconBitmap;

    private static Bitmap getHubIconBitmap(Context context) {
        if (hubIconBitmap != null && !hubIconBitmap.isRecycled()) {
            return hubIconBitmap;
        }
        final int size = dp(80);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        try {
            Drawable src = context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
            src.setBounds(0, 0, size, size);
            src.draw(canvas);
        } catch (Exception e) {
            Drawable fallback = context.getResources().getDrawable(R.drawable.msg_fave).mutate();
            fallback.setBounds(dp(20), dp(20), size - dp(20), size - dp(20));
            fallback.draw(canvas);
        }
        return hubIconBitmap = bitmap;
    }

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }

    private SettingCell addMenuItem(LinearLayout block, Runnable onClick, int iconColorTop, int iconColorBottom, int icon, CharSequence title) {
        return addMenuItem(block, onClick, iconColorTop, iconColorBottom, icon, title, null, null);
    }

    private SettingCell addMenuItem(LinearLayout block, Runnable onClick, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence value) {
        return addMenuItem(block, onClick, iconColorTop, iconColorBottom, icon, title, value, null);
    }

    private SettingCell addMenuItem(LinearLayout block, Runnable onClick, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence value, String badgeText) {
        SettingCell cell = new SettingCell(getContext(), null);
        cell.set(iconColorTop, iconColorBottom, icon, title, null, value, false, badgeText);
        if (onClick != null) {
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(v -> onClick.run());
        } else {
            cell.setAlpha(0.4f);
        }
        block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private void openUrl(String url) {
        if (getParentActivity() == null || TextUtils.isEmpty(url)) {
            return;
        }
        Browser.openUrl(getParentActivity(), url);
    }

    private void openTelegramUsername(String username) {
        if (getParentActivity() == null || TextUtils.isEmpty(username)) {
            return;
        }
        Browser.openUrl(getParentActivity(), "https://t.me/" + username);
    }

    private void openSupport() {
        String username = BuildVars.MGLA_SUPPORT_USERNAME;
        if (TextUtils.isEmpty(username)) {
            return;
        }
        try {
            getMessagesController().openByUserName(username, this, 1);
        } catch (Throwable ignored) {
            openTelegramUsername(username);
        }
    }

    private void openMglaGuide() {
        openUrl(BuildVars.MGLA_GUIDE_URL);
    }

    private void openDonate() {
        openUrl("https://pay.cloudtips.ru/p/98e2d5e0");
    }

    public static class SettingCell extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final Background iconBackground;
        private final FrameLayout iconLayout;
        private final ImageView iconView;
        private final LinearLayout textLayout;
        private final LinearLayout titleRow;
        private final TextView titleView;
        private final TextView betaBadge;
        private final TextView subtitleView;
        private final TextView valueView;
        private final Switch switchView;

        public SettingCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);

            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);

            iconLayout = new FrameLayout(context);
            iconLayout.setBackground(iconBackground = new Background());

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconLayout.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

            textLayout = new LinearLayout(context);
            textLayout.setOrientation(LinearLayout.VERTICAL);

            titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleRow.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

            betaBadge = new TextView(context);
            betaBadge.setText("beta");
            betaBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            betaBadge.setTypeface(AndroidUtilities.bold());
            betaBadge.setTextColor(0xFFFFFFFF);
            betaBadge.setGravity(Gravity.CENTER);
            betaBadge.setPadding(dp(5), dp(1), dp(5), dp(2));
            betaBadge.setVisibility(GONE);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(4));
            betaBadge.setBackground(badgeBg);
            titleRow.addView(betaBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

            textLayout.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);

            switchView = new Switch(context);
            switchView.setVisibility(View.GONE);

            addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL | Gravity.LEFT, 18, 0, 0, 0));
            addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, 18, 0, 20, 0));
            addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            addView(switchView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            iconBackground.setDrawBorder(resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark());
            if (betaBadge.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) betaBadge.getBackground()).setColor(Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider));
            }
        }

        private boolean twoLines;

        private Object object;

        public void set(
            int iconColorTop, int iconColorBottom, int icon,
            CharSequence title,
            CharSequence subtitle,
            CharSequence value
        ) {
            set(iconColorTop, iconColorBottom, icon, title, subtitle, value, false, null);
        }

        public void set(
            int iconColorTop, int iconColorBottom, int icon,
            CharSequence title,
            CharSequence subtitle,
            CharSequence value,
            boolean checked
        ) {
            set(iconColorTop, iconColorBottom, icon, title, subtitle, value, checked, null);
        }

        public void set(
            int iconColorTop, int iconColorBottom, int icon,
            CharSequence title,
            CharSequence subtitle,
            CharSequence value,
            boolean checked,
            String badgeText
        ) {
            iconLayout.setVisibility(icon != 0 ? View.VISIBLE : View.GONE);
            titleView.setTranslationX(icon == 0 ? dp(2) : 0);
            subtitleView.setTranslationX(icon == 0 ? dp(2) : 0);

            iconBackground.setColor(iconColorTop, iconColorBottom);
            iconView.setImageResource(icon);
            titleView.setText(title);
            final boolean showBadge = !TextUtils.isEmpty(badgeText);
            if (showBadge) {
                betaBadge.setText(badgeText);
            }
            betaBadge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
            subtitleView.setVisibility((twoLines = !TextUtils.isEmpty(subtitle)) ? View.VISIBLE : View.GONE);
            subtitleView.setText(subtitle);
            valueView.setVisibility(View.GONE);
            switchView.setVisibility(View.GONE);
            if ("__switch__".equals(value)) {
                switchView.setOnCheckedChangeListener(null);
                switchView.setChecked(checked, false);
                switchView.setVisibility(View.VISIBLE);
                switchView.setOnCheckedChangeListener((v, c) -> {
                    if (object instanceof Utilities.Callback) {
                        ((Utilities.Callback<Boolean>) object).run(c);
                    }
                });
            } else {
                valueView.setVisibility(!TextUtils.isEmpty(value) ? View.VISIBLE : View.GONE);
                valueView.setText(value);
                valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            }
            if (showBadge && betaBadge.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) betaBadge.getBackground()).setColor(Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider));
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(twoLines ? 60 : 50), MeasureSpec.EXACTLY)
            );
        }

        public static class Background extends Drawable {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private android.graphics.LinearGradient gradient, strokeGradient;
            private final android.graphics.Matrix matrix = new android.graphics.Matrix();

            public Background() {
                strokePaint.setStyle(Paint.Style.STROKE);
                strokeGradient = new android.graphics.LinearGradient(0, 0, 0, dp(28), new int[] { 0x4dffffff, 0, 0x1affffff }, new float[] { 0, 0.5f, 1 }, android.graphics.Shader.TileMode.CLAMP);
                strokePaint.setShader(strokeGradient);
            }

            public void setColor(int topColor, int bottomColor) {
                gradient = new android.graphics.LinearGradient(0, 0, 0, dp(28), new int[] { topColor, bottomColor }, new float[] { 0, 1 }, android.graphics.Shader.TileMode.CLAMP);
                paint.setShader(gradient);
            }

            private boolean border;
            public void setDrawBorder(boolean drawBorder) {
                this.border = drawBorder;
            }

            public void draw(@NonNull Canvas canvas) {
                final float r = dp(10);
                AndroidUtilities.rectTmp.set(getBounds());
                matrix.reset();
                matrix.postTranslate(AndroidUtilities.rectTmp.left, AndroidUtilities.rectTmp.top);
                canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, paint);

                if (border) {
                    final float sw = dp(1);
                    strokePaint.setStrokeWidth(sw);
                    matrix.reset();
                    matrix.postTranslate(AndroidUtilities.rectTmp.left, AndroidUtilities.rectTmp.top);
                    AndroidUtilities.rectTmp.inset(sw / 2.0f, sw / 2.0f);
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, strokePaint);
                }
            }

            public void setAlpha(int alpha) {}
            public void setColorFilter(ColorFilter colorFilter) {}
            public int getOpacity() {
                return PixelFormat.TRANSPARENT;
            }
        }

        public static class Factory extends UItem.UItemFactory<SettingCell> {
            static { setup(new Factory()); }

            @Override
            public SettingCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new SettingCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                int iconColorTop    = (int) item.longValue;
                int iconColorBottom = (int) (item.longValue >>> 32);
                SettingCell cell = (SettingCell) view;
                cell.object = item.object;
                cell.set(
                    iconColorTop, iconColorBottom, item.iconResId,
                    item.text,
                    item.subtext,
                    item.textValue,
                    item.checked
                );
            }

            public static UItem of(int id, int iconColorTop, int iconColorBottom, int icon, CharSequence title) {
                return of(id, iconColorTop, iconColorBottom, icon, title, null, null);
            }
            public static UItem of(int id, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence subtitle) {
                return of(id, iconColorTop, iconColorBottom, icon, title, subtitle, null);
            }
            public static UItem of(int id, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence subtitle, CharSequence value) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.iconResId = icon;
                item.text = title;
                item.subtext = subtitle;
                item.textValue = value;
                item.longValue = ((long) iconColorBottom << 32) | (iconColorTop & 0xFFFFFFFFL);
                return item;
            }

            public static UItem aiSwitch(int id, int iconColorTop, int iconColorBottom, int icon, CharSequence title, boolean checked, Utilities.Callback<Boolean> callback) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.iconResId = icon;
                item.text = title;
                item.textValue = "__switch__";
                item.checked = checked;
                item.object = callback;
                item.longValue = ((long) iconColorBottom << 32) | (iconColorTop & 0xFFFFFFFFL);
                return item;
            }
        }
    }
}
