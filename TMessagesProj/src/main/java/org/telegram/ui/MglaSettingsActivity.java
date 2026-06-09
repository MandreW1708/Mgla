package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class MglaSettingsActivity extends BaseFragment {

    private UniversalRecyclerView listView;

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

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        listView.setPadding(0, dp(8), 0, AndroidUtilities.navigationBarHeight);
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.adapter.update(false);
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {

        items.add(SettingCell.Factory.of(4, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_chat, "AI", "Искусственный интеллект"));
        items.add(UItem.asShadow(null));

        items.add(SettingCell.Factory.of(1, IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom, R.drawable.settings_account, "О приложении", "Mgla v" + BuildVars.BUILD_VERSION_STRING));
        items.add(SettingCell.Factory.of(2, IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.settings_chat, "Проверить обновления"));
        items.add(SettingCell.Factory.of(3, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.settings_faq, "Помощь"));

        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case 4:
                presentFragment(new MglaAiSettingsActivity());
                break;
            case 1:
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getParentActivity());
                builder.setTitle("О приложении Mgla");
                builder.setMessage("Mgla v" + BuildVars.BUILD_VERSION_STRING + "\n\nКастомный клиент Telegram.\n\nApplication ID: org.telegram.mgla");
                builder.setPositiveButton(getString(R.string.OK), null);
                showDialog(builder.create());
                break;
            case 2:
                Browser.openUrl(getParentActivity(), "https://telegram.org");
                break;
            case 3:
                Browser.openUrl(getParentActivity(), "https://telegram.org/faq");
                break;
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    public static class SettingCell extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final Background iconBackground;
        private final FrameLayout iconLayout;
        private final ImageView iconView;
        private final LinearLayout textLayout;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView valueView;

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
            textLayout.setOrientation(VERTICAL);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);

            addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL | Gravity.LEFT, 18, 0, 0, 0));
            addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, 18, 0, 20, 0));
            addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            iconBackground.setDrawBorder(resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark());
        }

        private boolean twoLines;

        public void set(
            int iconColorTop, int iconColorBottom, int icon,
            CharSequence title,
            CharSequence subtitle,
            CharSequence value
        ) {
            iconLayout.setVisibility(icon != 0 ? View.VISIBLE : View.GONE);
            titleView.setTranslationX(icon == 0 ? dp(2) : 0);
            subtitleView.setTranslationX(icon == 0 ? dp(2) : 0);

            iconBackground.setColor(iconColorTop, iconColorBottom);
            iconView.setImageResource(icon);
            titleView.setText(title);
            subtitleView.setVisibility((twoLines = !TextUtils.isEmpty(subtitle)) ? View.VISIBLE : View.GONE);
            subtitleView.setText(subtitle);
            valueView.setVisibility(!TextUtils.isEmpty(value) ? View.VISIBLE : View.GONE);
            valueView.setText(value);
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
                ((SettingCell) view).set(
                    iconColorTop, iconColorBottom, item.iconResId,
                    item.text,
                    item.subtext,
                    item.textValue
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
        }
    }
}