package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.MglaMemoryConfig;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.MglaUpdateChecker;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
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

    private static final int COLOR_HEADER_TOP = 0xFF0B1A2E;
    private static final int COLOR_HEADER_MID = 0xFF0F2742;
    private static final int COLOR_HEADER_BOTTOM = 0xFF123A56;
    private static final int COLOR_SHEET = 0xFF121417;
    private static final int COLOR_CARD = 0xFF1E1F24;
    private static final int COLOR_TEXT = 0xFFF2F3F5;
    private static final int COLOR_TEXT_SECONDARY = 0xFF9AA0A6;
    private static final int COLOR_HANDLE = 0xFF3A3D45;
    private static final int COLOR_ACCENT = 0xFF4DB8C9;
    private static final int COLOR_DIVIDER = 0x14FFFFFF;

    private HubLayout hubLayout;
    private View headerBrand;
    private TextView dpiBypassValueView;
    private View memoryRow;
    private TextView memoryValueView;
    private ImageView headerIconView;

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
        updateMemoryRow();
        updateDpiBypassValue();
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dpiBypassChanged) {
            updateDpiBypassValue();
        } else if (id == NotificationCenter.mglaHeaderSettingsChanged
                || id == NotificationCenter.mglaSubscriptionHintChanged) {
            updateMemoryRow();
        }
    }

    private void updateDpiBypassValue() {
        if (dpiBypassValueView != null) {
            dpiBypassValueView.setText(bypassStatusLabel());
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

    private void updateMemoryRow() {
        if (memoryRow == null) {
            return;
        }
        boolean allowed = MglaMemoryConfig.isActive();
        memoryRow.setVisibility(allowed ? View.VISIBLE : View.GONE);
        if (!allowed) {
            return;
        }
        boolean unlocked = MglaSubscription.canUseLiveMemory();
        boolean on = MglaMemoryConfig.isEnabled();
        if (memoryValueView != null) {
            memoryValueView.setText(on ? "Вкл" : "Выкл");
        }
        memoryRow.setAlpha(unlocked ? 1f : 0.4f);
    }

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
        hasOwnBackground = true;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setOccupyStatusBar(true);
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setTitleColor(Color.TRANSPARENT);
        actionBar.setItemsColor(0xFFFFFFFF, false);
        actionBar.setItemsBackgroundColor(0x33FFFFFF, false);
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

        hubLayout = new HubLayout(context);
        fragmentView = hubLayout;
        AndroidUtilities.runOnUIThread(this::playEntrance, 40);
        return fragmentView;
    }

    private void playEntrance() {
        if (hubLayout == null || headerBrand == null) {
            return;
        }
        hubLayout.playEntranceAnimation();
    }

    private void checkUpdates() {
        if (getParentActivity() == null || getFragmentView() == null) {
            return;
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, "Проверяем обновления…").show();
        MglaUpdateChecker.checkManual((result, info) -> {
            if (getParentActivity() == null || getFragmentView() == null) {
                return;
            }
            switch (result) {
                case UPDATE_AVAILABLE:
                    // MglaUpdateSheet already shown by checker.
                    break;
                case UP_TO_DATE:
                    BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.contact_check,
                        "У вас актуальная версия"
                    ).show();
                    break;
                case BUSY:
                    BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.chats_infotip,
                        "Проверка уже идёт…"
                    ).show();
                    break;
                case ERROR:
                default:
                    BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.error,
                        "Сервер обновлений недоступен. Попробуйте позже."
                    ).show();
                    break;
            }
        });
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

    private static Bitmap hubIconBitmap;

    private static Bitmap getHubIconBitmap(Context context) {
        final int size = dp(120);
        if (hubIconBitmap != null && !hubIconBitmap.isRecycled() && hubIconBitmap.getWidth() == size) {
            return hubIconBitmap;
        }
        if (hubIconBitmap != null && !hubIconBitmap.isRecycled()) {
            hubIconBitmap.recycle();
            hubIconBitmap = null;
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        try {
            Drawable src = context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
            src.setBounds(0, 0, size, size);
            src.draw(canvas);
        } catch (Exception e) {
            Drawable fallback = context.getResources().getDrawable(R.drawable.msg_fave).mutate();
            fallback.setBounds(dp(24), dp(24), size - dp(24), size - dp(24));
            fallback.draw(canvas);
        }
        return hubIconBitmap = bitmap;
    }

    // ── Hub shell ──────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private class HubLayout extends FrameLayout implements NestedScrollingParent3 {

        private final NestedScrollingParentHelper nestedHelper = new NestedScrollingParentHelper(this);
        private final FrameLayout sheet;
        private final NestedScrollView scrollView;
        private final LinearLayout content;
        private final View[] staggerViews = new View[16];
        private int staggerCount;

        private float progress; // 0 = peek, 1 = expanded
        private ValueAnimator settleAnimator;
        private float dragStartProgress;
        private float dragStartY;
        private boolean draggingHandle;
        private boolean entrancePlaying;
        private VelocityTracker velocityTracker;
        private final int touchSlop;
        private final int maxFling;
        /** Nested-scroll dy/sec (finger up = positive), for seamless settle. */
        private float nestedVelocityY;
        private long nestedVelocityTime;
        private boolean nestedScrolling;

        HubLayout(Context context) {
            super(context);
            setWillNotDraw(false);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            maxFling = ViewConfiguration.get(context).getScaledMaximumFlingVelocity();

            GradientDrawable headerBg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{COLOR_HEADER_TOP, COLOR_HEADER_MID, COLOR_HEADER_BOTTOM}
            );
            setBackground(headerBg);

            headerBrand = buildHeaderBrand(context);
            addView(headerBrand, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

            addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

            sheet = new FrameLayout(context) {
                private final Path clipPath = new Path();
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                {
                    paint.setColor(COLOR_SHEET);
                    setOutlineProvider(new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + dp(28), dp(28));
                        }
                    });
                    setClipToOutline(true);
                }

                @Override
                protected void dispatchDraw(Canvas canvas) {
                    float r = dp(28);
                    clipPath.rewind();
                    AndroidUtilities.rectTmp.set(0, 0, getWidth(), getHeight());
                    clipPath.addRoundRect(AndroidUtilities.rectTmp, r, r, Path.Direction.CW);
                    canvas.drawPath(clipPath, paint);
                    super.dispatchDraw(canvas);
                }
            };

            View handle = new View(context);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(COLOR_HANDLE);
            handleBg.setCornerRadius(dp(2));
            handle.setBackground(handleBg);
            sheet.addView(handle, LayoutHelper.createFrame(40, 4, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

            scrollView = new NestedScrollView(context) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    // Handle strip: drag sheet without fighting content scroll.
                    if (ev.getY() <= dp(28) || draggingHandle) {
                        return false;
                    }
                    return super.dispatchTouchEvent(ev);
                }

                @Override
                public void fling(int velocityY) {
                    // While sheet is mid-expand, keep fling on the sheet — not content.
                    if (progress < 0.999f) {
                        settleFromSheetVelocity(-velocityY);
                        return;
                    }
                    if (getScrollY() == 0 && velocityY < 0 && progress > 0.001f) {
                        settleFromSheetVelocity(-velocityY);
                        return;
                    }
                    super.fling(velocityY);
                }
            };
            scrollView.setOverScrollMode(OVER_SCROLL_NEVER);
            scrollView.setFillViewport(true);
            scrollView.setClipToPadding(false);
            scrollView.setNestedScrollingEnabled(true);
            scrollView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + dp(16));

            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(22), dp(16), dp(8));
            buildSheetContent(context, content);
            scrollView.addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            sheet.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 18, 0, 0));

            addView(sheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

            // Only the handle zone drives direct drag; content uses nested scroll.
            sheet.setOnTouchListener((v, event) -> {
                final float localY = event.getY();
                final float rawY = event.getRawY();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (localY > dp(36)) {
                            draggingHandle = false;
                            return false;
                        }
                        draggingHandle = true;
                        dragStartY = rawY;
                        dragStartProgress = progress;
                        obtainVelocityTracker().addMovement(event);
                        cancelSettle();
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (!draggingHandle) {
                            return false;
                        }
                        velocityTracker.addMovement(event);
                        float dy = dragStartY - rawY;
                        float range = sheetRange();
                        setProgress(dragStartProgress + dy / range, false);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (!draggingHandle) {
                            return false;
                        }
                        draggingHandle = false;
                        float vY = 0;
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(event);
                            velocityTracker.computeCurrentVelocity(1000, maxFling);
                            vY = velocityTracker.getYVelocity();
                            recycleVelocityTracker();
                        }
                        // Finger-down velocity > 0 → collapse
                        settleFromSheetVelocity(vY);
                        return true;
                    }
                }
                return false;
            });
        }

        private VelocityTracker obtainVelocityTracker() {
            if (velocityTracker != null) {
                velocityTracker.clear();
        } else {
                velocityTracker = VelocityTracker.obtain();
            }
            return velocityTracker;
        }

        private void recycleVelocityTracker() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        private float sheetRange() {
            return Math.max(1f, peekTop() - expandedTop());
        }

        private View buildHeaderBrand(Context context) {
            LinearLayout brand = new LinearLayout(context);
            brand.setOrientation(LinearLayout.VERTICAL);
            brand.setGravity(Gravity.CENTER_HORIZONTAL);
            int topPad = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(8);
            brand.setPadding(dp(24), topPad, dp(24), dp(28));

            headerIconView = new ImageView(context);
            headerIconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            headerIconView.setImageDrawable(new BitmapDrawable(context.getResources(), getHubIconBitmap(context)));
            brand.addView(headerIconView, LayoutHelper.createLinear(112, 112, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

            TextView title = new TextView(context);
            title.setText("Mgla");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(COLOR_TEXT);
            title.setGravity(Gravity.CENTER);
            brand.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));

            LinearLayout versionRow = new LinearLayout(context);
            versionRow.setOrientation(LinearLayout.HORIZONTAL);
            versionRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView version = new TextView(context);
            version.setText(BuildVars.MGLA_VERSION_STRING);
            version.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            version.setTextColor(ColorUtils.setAlphaComponent(COLOR_TEXT, 200));
            versionRow.addView(version, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            TextView beta = new TextView(context);
            beta.setText("beta");
            beta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            beta.setTypeface(AndroidUtilities.bold());
            beta.setTextColor(0xFFFFFFFF);
            beta.setPadding(dp(6), dp(2), dp(6), dp(3));
            GradientDrawable betaBg = new GradientDrawable();
            betaBg.setCornerRadius(dp(6));
            betaBg.setColor(ColorUtils.setAlphaComponent(COLOR_ACCENT, 180));
            beta.setBackground(betaBg);
            versionRow.addView(beta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            brand.addView(versionRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

            LinearLayout updateBtn = new LinearLayout(context);
            updateBtn.setOrientation(LinearLayout.HORIZONTAL);
            updateBtn.setGravity(Gravity.CENTER);
            updateBtn.setPadding(dp(16), dp(10), dp(18), dp(10));
            GradientDrawable updateBg = new GradientDrawable();
            updateBg.setCornerRadius(dp(22));
            updateBg.setColor(0x33FFFFFF);
            updateBtn.setBackground(updateBg);
            updateBtn.setOnClickListener(v -> checkUpdates());
            MglaUi.attachPressSpring(updateBtn);

            ImageView refreshIcon = new ImageView(context);
            refreshIcon.setImageResource(R.drawable.msg_retry);
            refreshIcon.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT, PorterDuff.Mode.SRC_IN));
            updateBtn.addView(refreshIcon, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            TextView updateText = new TextView(context);
            updateText.setText("Проверить обновления");
            updateText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            updateText.setTypeface(AndroidUtilities.bold());
            updateText.setTextColor(COLOR_TEXT);
            updateBtn.addView(updateText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            brand.addView(updateBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            return brand;
        }

        private void buildSheetContent(Context context, LinearLayout root) {
            // 2×2 grid
            LinearLayout row1 = new LinearLayout(context);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            View tileGeneral = createGridTile(context, R.drawable.settings_features, IconBackgroundColors.CYAN,
                    "Общие", () -> presentFragment(new MglaMainSettingsActivity()));
            View tileChats = createGridTile(context, R.drawable.filled_chatlist2, IconBackgroundColors.BLUE,
                    "Чаты", () -> presentFragment(new MglaChatsSettingsActivity()));
            row1.addView(tileGeneral, LayoutHelper.createLinear(0, 112, 1f, Gravity.FILL, 0, 0, 6, 0));
            row1.addView(tileChats, LayoutHelper.createLinear(0, 112, 1f, Gravity.FILL, 6, 0, 0, 0));
            root.addView(row1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
            trackStagger(tileGeneral, tileChats);

            LinearLayout row2 = new LinearLayout(context);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            View tileAppear = createGridTile(context, R.drawable.msg_palette, IconBackgroundColors.ORANGE,
                    "Внешний вид", () -> presentFragment(new MglaAppearanceSettingsActivity()));
            View tileVpn = createGridTile(context, R.drawable.mgla_dpi_shield, IconBackgroundColors.BURGUNDY,
                    "Подключение без VPN", () -> presentFragment(new MglaDpiBypassActivity()), true);
            row2.addView(tileAppear, LayoutHelper.createLinear(0, 112, 1f, Gravity.FILL, 0, 0, 6, 0));
            row2.addView(tileVpn, LayoutHelper.createLinear(0, 112, 1f, Gravity.FILL, 6, 0, 0, 0));
            root.addView(row2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));
            trackStagger(tileAppear, tileVpn);

            // Возможности
            TextView featsHeader = sectionTitle(context, "Возможности");
            root.addView(featsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 8));
            trackStagger(featsHeader);

            LinearLayout feats = createCard(context);
            addListRow(feats, R.drawable.settings_sounds, IconBackgroundColors.RED, "Уведомления", null,
                    () -> presentFragment(new MglaNotificationsSettingsActivity()), true);
            addListRow(feats, R.drawable.files_music, IconBackgroundColors.PURPLE, "Плеер", null,
                    () -> presentFragment(new MglaPlayerSettingsActivity()), true);
            addListRow(feats, R.drawable.filled_premium_camera, IconBackgroundColors.GREEN, "Камера", null,
                    () -> presentFragment(new MglaCameraSettingsActivity()), true);
            addListRow(feats, R.drawable.input_ai, IconBackgroundColors.PURPLE, "Искусственный интеллект", null,
                    () -> presentFragment(new MglaAiSettingsActivity()), true);

            memoryRow = addListRow(feats, R.drawable.msg_saved, IconBackgroundColors.BURGUNDY,
                    liveMemoryPremiumLabel(context), "Выкл", MglaSettingsActivity.this::openMemory, true);
            memoryValueView = (TextView) memoryRow.getTag();
            updateMemoryRow();

            addListRow(feats, R.drawable.settings_mgla_premium, null, "Mgla Premium", null,
                    () -> presentFragment(new MglaPremiumActivity()), false);
            root.addView(feats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));
            trackStagger(feats);

            // Сообщество
            TextView communityHeader = sectionTitle(context, "Сообщество");
            root.addView(communityHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 8));
            trackStagger(communityHeader);

            LinearLayout community = createCard(context);
            addListRow(community, R.drawable.settings_channel, IconBackgroundColors.ORANGE, "Канал разработчика", null,
                    () -> openTelegramUsername(BuildVars.MGLA_DEV_CHANNEL_USERNAME), true);
            addListRow(community, R.drawable.menu_website, IconBackgroundColors.BLUE_DEEP, "Инструкция", null,
                    MglaSettingsActivity.this::openMglaGuide, true);
            addListRow(community, R.drawable.msg_help, IconBackgroundColors.CYAN, "Поддержка", null,
                    MglaSettingsActivity.this::openSupport, true);
            addListRow(community, R.drawable.wallet_buy, IconBackgroundColors.GREEN, "Поддержать проект", null,
                    MglaSettingsActivity.this::openDonate, false);
            root.addView(community, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 18));
            trackStagger(community);

            TextView footer = new TextView(context);
            footer.setText("Используя Mgla, вы соглашаетесь с условиями использования. Собираются только обезличенные данные устройства и Telegram ID для подписки — личные сообщения не собираются.");
            footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            footer.setTextColor(COLOR_TEXT_SECONDARY);
            footer.setGravity(Gravity.CENTER_HORIZONTAL);
            footer.setLineSpacing(dp(2), 1f);
            footer.setOnClickListener(v -> openUrl(BuildVars.MGLA_TERMS_OF_SERVICE_URL));
            root.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 8));
            trackStagger(footer);
        }

        private void trackStagger(View... views) {
            for (View v : views) {
                if (v != null && staggerCount < staggerViews.length) {
                    staggerViews[staggerCount++] = v;
                    v.setAlpha(0f);
                    v.setTranslationY(dp(16));
                }
            }
        }

        void playEntranceAnimation() {
            entrancePlaying = true;
            if (headerBrand != null) {
                headerBrand.setAlpha(0f);
                headerBrand.setScaleX(0.94f);
                headerBrand.setScaleY(0.94f);
                // Keep brand translation driven by progress after entrance.
                headerBrand.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(480)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .start();
            }
            sheet.setAlpha(0f);
            final float from = currentSheetTop() + dp(72);
            final float to = currentSheetTop();
            sheet.setTranslationY(from);
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(520);
            anim.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                sheet.setAlpha(f);
                sheet.setTranslationY(AndroidUtilities.lerp(from, to, f));
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    entrancePlaying = false;
                    applyVisuals();
                }
            });
            anim.start();
            staggerContent();
        }

        void staggerContent() {
            for (int i = 0; i < staggerCount; i++) {
                final View v = staggerViews[i];
                if (v == null) {
                    continue;
                }
                v.animate()
                        .alpha(1f)
                        .translationY(0)
                        .setStartDelay(80L + i * 45L)
                        .setDuration(380)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .start();
            }
        }

        private TextView sectionTitle(Context context, String text) {
            TextView t = new TextView(context);
            t.setText(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(COLOR_TEXT_SECONDARY);
            t.setPadding(dp(4), 0, 0, 0);
            return t;
        }

        private LinearLayout createCard(Context context) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COLOR_CARD);
            bg.setCornerRadius(dp(18));
            card.setBackground(bg);
            card.setClipToOutline(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
                }
            });
            return card;
        }

        private View createGridTile(Context context, int iconRes, IconBackgroundColors colors, String title, Runnable onClick) {
            return createGridTile(context, iconRes, colors, title, onClick, false);
        }

        private View createGridTile(Context context, int iconRes, IconBackgroundColors colors, String title, Runnable onClick, boolean vpn) {
            FrameLayout tile = new FrameLayout(context);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COLOR_CARD);
            bg.setCornerRadius(dp(18));
            tile.setBackground(bg);
            tile.setOnClickListener(v -> onClick.run());
            MglaUi.attachPressSpring(tile);

            FrameLayout iconBox = new FrameLayout(context);
            SettingCell.Background iconBg = new SettingCell.Background();
            iconBg.setColor(colors.top, colors.bottom);
            iconBox.setBackground(iconBg);
            ImageView icon = new ImageView(context);
            icon.setImageResource(iconRes);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            iconBox.addView(icon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
            tile.addView(iconBox, LayoutHelper.createFrame(34, 34, Gravity.TOP | Gravity.LEFT, 14, 14, 0, 0));

            TextView label = new TextView(context);
            label.setText(title);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, title.length() > 12 ? 13 : 15);
            label.setTypeface(AndroidUtilities.bold());
            label.setTextColor(COLOR_TEXT);
            label.setMaxLines(2);
            label.setLineSpacing(0, 1.05f);
            tile.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 14, 0, 14, 14));

            if (vpn) {
                dpiBypassValueView = new TextView(context);
                dpiBypassValueView.setText(bypassStatusLabel());
                dpiBypassValueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                dpiBypassValueView.setTextColor(COLOR_ACCENT);
                dpiBypassValueView.setTypeface(AndroidUtilities.bold());
                tile.addView(dpiBypassValueView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 16, 14, 0));
            }
            return tile;
        }

        /** @return row; tag holds value TextView when value != null */
        private View addListRow(LinearLayout card, int iconRes, IconBackgroundColors colors, CharSequence title,
                                CharSequence value, Runnable onClick, boolean dividerAfter) {
            Context context = card.getContext();
            FrameLayout row = new FrameLayout(context);
            row.setMinimumHeight(dp(54));
            row.setOnClickListener(v -> onClick.run());
            row.setBackground(Theme.createSelectorDrawable(0x14FFFFFF, Theme.RIPPLE_MASK_ALL));
            MglaUi.attachPressSpring(row);

            if (iconRes != 0) {
                FrameLayout iconBox = new FrameLayout(context);
                if (colors != null) {
                    SettingCell.Background iconBg = new SettingCell.Background();
                    iconBg.setColor(colors.top, colors.bottom);
                    iconBox.setBackground(iconBg);
                } else {
                    GradientDrawable flat = new GradientDrawable();
                    flat.setCornerRadius(dp(10));
                    flat.setColor(0xFF111111);
                    iconBox.setBackground(flat);
                }
                ImageView icon = new ImageView(context);
                icon.setImageResource(iconRes);
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
                iconBox.addView(icon, LayoutHelper.createFrame(20, 20, Gravity.CENTER));
                row.addView(iconBox, LayoutHelper.createFrame(28, 28, Gravity.CENTER_VERTICAL | Gravity.LEFT, 14, 0, 0, 0));
            }

            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(COLOR_TEXT);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL | Gravity.LEFT, 56, 0, value != null ? 72 : 40, 0));

            TextView valueView = null;
            if (value != null) {
                valueView = new TextView(context);
                valueView.setText(value);
                valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                valueView.setTextColor(COLOR_ACCENT);
                row.addView(valueView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 36, 0));
                row.setTag(valueView);
            }

            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.msg_arrowright);
            chevron.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT_SECONDARY, PorterDuff.Mode.SRC_IN));
            row.addView(chevron, LayoutHelper.createFrame(16, 16, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 14, 0));

            card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            if (dividerAfter) {
                View d = new View(context);
                d.setBackgroundColor(COLOR_DIVIDER);
                card.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 56, 0, 14, 0));
            }
            return row;
        }

        private int peekTop() {
            return Math.max(expandedTop() + dp(120), (int) (getHeight() * 0.46f));
        }

        private int expandedTop() {
            return AndroidUtilities.statusBarHeight + dp(6);
        }

        float currentSheetTop() {
            return AndroidUtilities.lerp(peekTop(), expandedTop(), progress);
        }

        void setProgress(float p, boolean layoutOnly) {
            progress = Math.max(0f, Math.min(1f, p));
            applyVisuals();
            if (!layoutOnly) {
                invalidate();
            }
        }

        private void applyVisuals() {
            if (getHeight() <= 0 || entrancePlaying) {
            return;
        }
            float top = currentSheetTop();
            sheet.setTranslationY(top);
            float fade = 1f - progress * 0.92f;
            headerBrand.setAlpha(Math.max(0f, fade));
            float scale = 1f - progress * 0.1f;
            headerBrand.setScaleX(scale);
            headerBrand.setScaleY(scale);
            headerBrand.setTranslationY(-progress * dp(28));
            actionBar.setAlpha(1f - progress * 0.15f);
        }

        private void cancelSettle() {
            if (settleAnimator != null) {
                settleAnimator.cancel();
                settleAnimator = null;
            }
        }

        /**
         * @param velocityYPx finger-space px/s: positive = finger down = collapse
         */
        private void settleFromSheetVelocity(float velocityYPx) {
            if (entrancePlaying) {
            return;
        }
            float target;
            if (Math.abs(velocityYPx) > 600) {
                target = velocityYPx > 0 ? 0f : 1f;
            } else {
                target = progress >= 0.34f ? 1f : 0f;
            }
            if (Math.abs(progress - target) < 0.002f) {
                setProgress(target, false);
                return;
            }

            cancelSettle();
            final float start = progress;
            final float range = sheetRange();
            final float distancePx = Math.abs(target - start) * range;
            final float speed = Math.max(Math.abs(velocityYPx), 1f);

            // Continue motion instead of restarting from rest: duration from remaining distance / speed.
            long duration;
            if (Math.abs(velocityYPx) > 80) {
                duration = (long) Math.max(140, Math.min(360, distancePx / speed * 1000f));
            } else {
                duration = (long) Math.max(200, Math.min(380, 180 + distancePx * 0.35f));
            }

            // Decelerate from current speed — avoid EaseOutQuint "pause then move".
            final Interpolator interpolator = Math.abs(velocityYPx) > 80
                    ? CubicBezierInterpolator.EASE_OUT
                    : CubicBezierInterpolator.DEFAULT;

            settleAnimator = ValueAnimator.ofFloat(0f, 1f);
            settleAnimator.setDuration(duration);
            settleAnimator.setInterpolator(interpolator);
            settleAnimator.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                setProgress(start + (target - start) * f, false);
            });
            settleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    settleAnimator = null;
                    setProgress(target, false);
                }
            });
            settleAnimator.start();
        }

        private void trackNestedVelocity(int dy) {
            long now = SystemClock.uptimeMillis();
            if (nestedVelocityTime != 0) {
                float dt = (now - nestedVelocityTime) / 1000f;
                if (dt > 0.001f && dt < 0.08f) {
                    float instant = dy / dt;
                    nestedVelocityY = nestedVelocityY * 0.35f + instant * 0.65f;
                }
            }
            nestedVelocityTime = now;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            applyVisuals();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // Keep sheet full-height; position via translation.
            int sheetH = getHeight() - expandedTop() + dp(28);
            sheet.layout(0, 0, getWidth(), Math.max(sheetH, getHeight()));
            applyVisuals();
        }

        // Nested scroll drives expand/collapse continuously with content.
        @Override
        public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
            return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        }

        @Override
        public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
            nestedHelper.onNestedScrollAccepted(child, target, axes, type);
            nestedScrolling = true;
            nestedVelocityY = 0;
            nestedVelocityTime = 0;
            cancelSettle();
        }

        @Override
        public void onStopNestedScroll(@NonNull View target, int type) {
            nestedHelper.onStopNestedScroll(target, type);
            if (type != ViewCompat.TYPE_TOUCH) {
            return;
        }
            nestedScrolling = false;
            // Finger-up velocity = nestedVelocityY (positive) → expand; invert to sheet space.
            float sheetVel = -nestedVelocityY;
            nestedVelocityY = 0;
            nestedVelocityTime = 0;
            if (settleAnimator == null && progress > 0.002f && progress < 0.998f) {
                settleFromSheetVelocity(sheetVel);
            }
        }

        @Override
        public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
            // dy > 0: finger up → expand first; dy < 0 at top: collapse before content.
            final float range = sheetRange();
            if (dy > 0 && progress < 1f) {
                trackNestedVelocity(dy);
                float next = Math.min(1f, progress + dy / range);
                float appliedPx = (next - progress) * range;
                setProgress(next, false);
                consumed[1] = Math.round(appliedPx);
            } else if (dy < 0 && scrollView.getScrollY() <= 0 && progress > 0f) {
                trackNestedVelocity(dy);
                float next = Math.max(0f, progress + dy / range);
                float appliedPx = (next - progress) * range;
                setProgress(next, false);
                consumed[1] = Math.round(appliedPx);
            }
        }

        @Override
        public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
            if (dyUnconsumed < 0 && scrollView.getScrollY() <= 0 && progress > 0f) {
                trackNestedVelocity(dyUnconsumed);
                float range = sheetRange();
                float next = Math.max(0f, progress + dyUnconsumed / range);
                float appliedPx = (next - progress) * range;
                setProgress(next, false);
                consumed[1] += Math.round(appliedPx);
            }
        }

        @Override
        public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
            onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, new int[2]);
        }

        @Override
        public boolean onNestedPreFling(@NonNull View target, float velocityX, float velocityY) {
            // NestedScrollView: positive velocityY = fling content down (finger flicked up).
            if (progress < 0.999f && velocityY > 0) {
                settleFromSheetVelocity(-velocityY);
                return true;
            }
            if (scrollView.getScrollY() <= 0 && progress > 0.001f && velocityY < 0) {
                settleFromSheetVelocity(-velocityY);
                return true;
            }
            return false;
        }

        @Override
        public boolean onNestedFling(@NonNull View target, float velocityX, float velocityY, boolean consumed) {
            if (!consumed && progress > 0.001f && progress < 0.999f) {
                settleFromSheetVelocity(-velocityY);
                return true;
            }
            return false;
        }

        @Override
        public int getNestedScrollAxes() {
            return nestedHelper.getNestedScrollAxes();
        }
    }

    // ── Legacy SettingCell (kept for UItem factory compatibility) ──────────

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

        public void set(int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence subtitle, CharSequence value) {
            set(iconColorTop, iconColorBottom, icon, title, subtitle, value, false, null);
        }

        public void set(int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence subtitle, CharSequence value, boolean checked) {
            set(iconColorTop, iconColorBottom, icon, title, subtitle, value, checked, null);
        }

        public void set(int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence subtitle, CharSequence value, boolean checked, String badgeText) {
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
                strokeGradient = new android.graphics.LinearGradient(0, 0, 0, dp(28), new int[]{0x4dffffff, 0, 0x1affffff}, new float[]{0, 0.5f, 1}, android.graphics.Shader.TileMode.CLAMP);
                strokePaint.setShader(strokeGradient);
            }

            public void setColor(int topColor, int bottomColor) {
                gradient = new android.graphics.LinearGradient(0, 0, 0, dp(28), new int[]{topColor, bottomColor}, new float[]{0, 1}, android.graphics.Shader.TileMode.CLAMP);
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

            public void setAlpha(int alpha) {
            }

            public void setColorFilter(ColorFilter colorFilter) {
            }

            public int getOpacity() {
                return PixelFormat.TRANSPARENT;
            }
        }

        public static class Factory extends UItem.UItemFactory<SettingCell> {
            static {
                setup(new Factory());
            }

            @Override
            public SettingCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new SettingCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                int iconColorTop = (int) item.longValue;
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
