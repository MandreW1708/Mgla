package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
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
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

public class MglaSettingsActivity extends BaseFragment {

    public MglaSettingsActivity() {
        this(null);
    }

    public MglaSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки Mgla");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
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
        addMenuItem(mainBlock, () -> presentFragment(new MglaChatsSettingsActivity()),
            IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom, R.drawable.filled_chatlist2, "Чаты");
        addMenuItem(mainBlock, () -> presentFragment(new MglaNotificationsSettingsActivity()),
            IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom, R.drawable.settings_sounds, "Уведомления");
        addMenuItem(mainBlock, () -> presentFragment(new MglaAppearanceSettingsActivity()),
            IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.msg_palette, "Внешний вид");
        addMenuItem(mainBlock, () -> presentFragment(new MglaCameraSettingsActivity()),
            IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.filled_premium_camera, "Камера");
        addMenuItem(mainBlock, () -> presentFragment(new MglaAiSettingsActivity()),
            IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.input_ai, "Искусственный интеллект", null, true);
        rootLayout.addView(mainBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        LinearLayout extraBlock = createBlock(context, "Дополнительно");
        addMenuItem(extraBlock, () -> openTelegramUsername(BuildVars.MGLA_DEV_CHANNEL_USERNAME),
            IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.settings_channel, "Канал разработчика",
            "@" + BuildVars.MGLA_DEV_CHANNEL_USERNAME);
        addMenuItem(extraBlock, () -> openSupportChat(),
            IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.settings_faq, "Поддержка");
        rootLayout.addView(extraBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, AndroidUtilities.navigationBarHeight + 16));

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
        iconView.setImageResource(R.mipmap.ic_launcher);
        iconContainer.addView(iconView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        header.addView(iconContainer, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        TextView titleView = new TextView(context);
        titleView.setText("Mgla");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        header.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 4));

        TextView versionView = new TextView(context);
        versionView.setText(BuildVars.MGLA_VERSION_STRING);
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        versionView.setGravity(Gravity.CENTER);
        header.addView(versionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        return header;
    }

    private LinearLayout createBlock(Context context, String title) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(bg);
        block.setClipToOutline(true);
        block.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        HeaderCell header = new HeaderCell(context, 22);
        header.setBackground(null);
        header.setText(title);
        block.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return block;
    }

    private SettingCell addMenuItem(LinearLayout block, Runnable onClick, int iconColorTop, int iconColorBottom, int icon, CharSequence title) {
        return addMenuItem(block, onClick, iconColorTop, iconColorBottom, icon, title, null);
    }

    private SettingCell addMenuItem(LinearLayout block, Runnable onClick, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence value) {
        return addMenuItem(block, onClick, iconColorTop, iconColorBottom, icon, title, value, false);
    }

    private SettingCell addMenuItem(LinearLayout block, Runnable onClick, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence value, boolean showBetaBadge) {
        SettingCell cell = new SettingCell(getContext(), null);
        cell.set(iconColorTop, iconColorBottom, icon, title, null, value, false, showBetaBadge);
        cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 0));
        cell.setOnClickListener(v -> onClick.run());
        block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private void openTelegramUsername(String username) {
        if (getParentActivity() == null || TextUtils.isEmpty(username)) {
            return;
        }
        Browser.openUrl(getParentActivity(), "https://t.me/" + username);
    }

    private void openSupportChat() {
        if (getParentActivity() == null || TextUtils.isEmpty(BuildVars.MGLA_SUPPORT_USERNAME)) {
            return;
        }
        MessagesController.getInstance(currentAccount).openByUserName(BuildVars.MGLA_SUPPORT_USERNAME, this, 1);
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
            set(iconColorTop, iconColorBottom, icon, title, subtitle, value, false, false);
        }

        public void set(
            int iconColorTop, int iconColorBottom, int icon,
            CharSequence title,
            CharSequence subtitle,
            CharSequence value,
            boolean checked
        ) {
            set(iconColorTop, iconColorBottom, icon, title, subtitle, value, checked, false);
        }

        public void set(
            int iconColorTop, int iconColorBottom, int icon,
            CharSequence title,
            CharSequence subtitle,
            CharSequence value,
            boolean checked,
            boolean showBetaBadge
        ) {
            iconLayout.setVisibility(icon != 0 ? View.VISIBLE : View.GONE);
            titleView.setTranslationX(icon == 0 ? dp(2) : 0);
            subtitleView.setTranslationX(icon == 0 ? dp(2) : 0);

            iconBackground.setColor(iconColorTop, iconColorBottom);
            iconView.setImageResource(icon);
            titleView.setText(title);
            betaBadge.setVisibility(showBetaBadge ? View.VISIBLE : View.GONE);
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
            if (showBetaBadge && betaBadge.getBackground() instanceof GradientDrawable) {
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
