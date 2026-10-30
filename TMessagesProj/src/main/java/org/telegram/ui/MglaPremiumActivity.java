package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MglaMainTabsConfig;
import org.telegram.messenger.MglaPremiumController;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumButtonView;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;

/**
 * Mgla Premium — hub-style screen for all plan tiers.
 */
public class MglaPremiumActivity extends MglaBaseSettingsActivity {

    public static final int PLAN_PREMIUM = 0;
    public static final int PLAN_PLUS = 1;
    public static final int PLAN_ULTRA = 2;
    public static final String ARG_INITIAL_PLAN = "initial_plan";

    public static final int FEATURE_ANIMATIONS = 0;
    public static final int FEATURE_TASKS = 1;
    public static final int FEATURE_LOCAL_MODELS = 2;
    public static final int FEATURE_MEMORY = 3;
    public static final int FEATURE_ICON_THEMES = 4;
    public static final int FEATURE_AUTO_TRANSLATE = 5;
    public static final int FEATURE_CLOUD_AI = 6;
    public static final int FEATURE_ULTRA_AI = 7;
    public static final int FEATURE_PRIORITY_BYPASS = 8;
    public static final int FEATURE_TASKS_SYNC = 9;
    public static final int FEATURE_SETTINGS_SYNC = 10;

    private static final int COLOR_BG = 0xFF000000;
    private static final int COLOR_SHEET = 0xFF121417;
    private static final int COLOR_CARD = 0xFF1E1F24;
    private static final int COLOR_TEXT = 0xFFF2F3F5;
    private static final int COLOR_HANDLE = 0xFF3A3D45;
    private static final int COLOR_DIVIDER = 0x14FFFFFF;

    private static final String ADMIN_USERNAME = "mgla_admin";

    private final ArrayList<PlanData> plans = new ArrayList<>();
    private final ArrayList<FeatureItem> features = new ArrayList<>();

    private int selectedPlanIndex = PLAN_PREMIUM;
    private PlanPalette accent = PlanPalette.forPlan(PLAN_PREMIUM);

    private PremiumHubLayout hubLayout;
    private View headerBrand;
    private GlowLogoView logoView;
    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout planCardsRow;
    private final ArrayList<PlanCardView> planCards = new ArrayList<>();
    private LinearLayout featuresCard;
    private TextView featuresHeader;
    private TextView hintView;
    private PremiumButtonView premiumButtonView;
    private FrameLayout buttonContainer;
    private ValueAnimator planSwitchAnimator;

    public MglaPremiumActivity() {
        this(null);
    }

    public MglaPremiumActivity(Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;

        plans.clear();
        plans.add(new PlanData(PLAN_PREMIUM, "Premium", "Premium", 200));
        plans.add(new PlanData(PLAN_PLUS, "Premium+", "Premium+", 400));
        plans.add(new PlanData(PLAN_ULTRA, "Ultra", "Premium Ultra", 1500));

        MglaPremiumController.ensureSeeded();
        int activePlan = activePlan();
        if (activePlan >= PLAN_PREMIUM && activePlan <= PLAN_ULTRA) {
            selectedPlanIndex = activePlan;
        }
        if (getArguments() != null && getArguments().containsKey(ARG_INITIAL_PLAN)) {
            int initial = getArguments().getInt(ARG_INITIAL_PLAN, -1);
            if (initial >= PLAN_PREMIUM && initial <= PLAN_ULTRA) {
                selectedPlanIndex = initial;
            }
        }
        accent = PlanPalette.forPlan(selectedPlanIndex);
        fillFeaturesForPlan(selectedPlanIndex);

        actionBar.setBackground(null);
        actionBar.setCastShadows(false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAddToContainer(false);
        actionBar.setItemsColor(COLOR_TEXT, false);
        actionBar.setItemsBackgroundColor(0x33FFFFFF, false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        hubLayout = new PremiumHubLayout(context);
        fragmentView = hubLayout;
        updateAccentSurfaces(false);
        updateButtonText(false);
        AndroidUtilities.runOnUIThread(this::playEntrance, 40);
        return fragmentView;
    }

    @Override
    public boolean isActionBarCrossfadeEnabled() {
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        if (planSwitchAnimator != null) {
            planSwitchAnimator.cancel();
            planSwitchAnimator = null;
        }
        if (hubLayout != null) {
            hubLayout.stopAmbientGlow();
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSubtitle();
        updateButtonText(false);
        refreshPlanCards();
        if (hubLayout != null) {
            hubLayout.startAmbientGlow();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (hubLayout != null) {
            hubLayout.stopAmbientGlow();
        }
    }

    private void playEntrance() {
        if (hubLayout != null) {
            hubLayout.playEntrance();
        }
    }

    private long currentUserId() {
        return getUserConfig().getClientUserId();
    }

    private int activePlan() {
        return MglaPremiumController.getPlanForAccount(currentAccount);
    }

    private static int logoResForPlan(int plan) {
        if (plan == PLAN_PLUS) {
            return R.drawable.mgla_premium_logo_red;
        }
        if (plan == PLAN_PREMIUM) {
            return R.drawable.mgla_premium_logo_blue;
        }
        return R.drawable.mgla_premium_logo;
    }

    private void fillFeaturesForPlan(int plan) {
        features.clear();
        features.add(new FeatureItem(FEATURE_ANIMATIONS, R.drawable.menu_premium_effects,
                "Новые анимации", "Очень много крутых новых анимаций интерфейса"));
        features.add(new FeatureItem(FEATURE_TASKS, R.drawable.msg_premium_liststar,
                "Планировщик задач", "Удобный список задач прямо в приложении"));
        features.add(new FeatureItem(FEATURE_TASKS_SYNC, R.drawable.msg_retry,
                "Синхронизация задач", "Задачи этого аккаунта синхронизируются между устройствами"));
        features.add(new FeatureItem(FEATURE_SETTINGS_SYNC, R.drawable.msg_premium_liststar,
                "Синхронизация настроек", "Отправка в облако и ручное применение на другом устройстве"));
        features.add(new FeatureItem(FEATURE_LOCAL_MODELS, R.drawable.premium_ai_editor,
                "Локальные модели", "Используйте локальные ИИ-модели на устройстве"));
        features.add(new FeatureItem(FEATURE_MEMORY, R.drawable.msg_premium_voice,
                "Живая память", "Персональная память ассистента"));
        features.add(new FeatureItem(FEATURE_ICON_THEMES, R.drawable.msg_premium_icons,
                "Темы для иконок", "Специальные темы под новый тип иконок"));
        features.add(new FeatureItem(FEATURE_AUTO_TRANSLATE, R.drawable.msg_premium_translate,
                "Автоперевод", "Автоматический перевод сообщений и чатов"));
        if (plan >= PLAN_PLUS) {
            features.add(new FeatureItem(FEATURE_CLOUD_AI, R.drawable.premium_ai_editor,
                    "Облачные ИИ-модели", "Более умные облачные модели"));
        }
        if (plan >= PLAN_ULTRA) {
            features.add(new FeatureItem(FEATURE_ULTRA_AI, R.drawable.premium_ai_editor,
                    "Продвинутые ИИ-модели", "Ещё более умные и дорогие облачные модели"));
            features.add(new FeatureItem(FEATURE_PRIORITY_BYPASS, R.drawable.msg_premium_speed,
                    "Приоритет подключения", "Приоритет и увеличенная скорость скачивания"));
        }
    }

    private void updateSubtitle() {
        if (subtitleView == null) {
            return;
        }
        int active = activePlan();
        if (active >= PLAN_PREMIUM) {
            String until = MglaPremiumController.formatExpiresLabel(currentUserId());
            subtitleView.setText(TextUtils.isEmpty(until)
                    ? "На этом аккаунте активен " + MglaPremiumController.planTitle(active)
                    : "Активен " + MglaPremiumController.planTitle(active) + " · " + until);
        } else {
            subtitleView.setText("Эксклюзивные возможности. Выберите свой уровень.");
        }
    }

    private void updateHint() {
        if (hintView == null || selectedPlanIndex < 0 || selectedPlanIndex >= plans.size()) {
            return;
        }
        PlanData plan = plans.get(selectedPlanIndex);
        int active = activePlan();
        if (active == selectedPlanIndex) {
            String until = MglaPremiumController.formatExpiresLabel(currentUserId());
            hintView.setText(TextUtils.isEmpty(until)
                    ? "На этом аккаунте активен " + plan.title + ". На других аккаунтах подписка не действует."
                    : "На этом аккаунте активен " + plan.title + " " + until
                            + ". На других аккаунтах подписка не действует.");
        } else if (plan.id == PLAN_PREMIUM) {
            hintView.setText("Premium: анимации, задачи и настройки с синхронизацией, локальные модели, живая память, темы иконок и автоперевод.");
        } else if (plan.id == PLAN_PLUS) {
            hintView.setText("Premium+ включает всё из Premium и добавляет умные облачные ИИ-модели.");
        } else {
            hintView.setText("Ultra включает всё из Premium и Premium+, плюс продвинутые модели и приоритет подключения.");
        }
    }

    private void selectPlan(int index, boolean animated) {
        if (index < 0 || index >= plans.size() || selectedPlanIndex == index) {
            return;
        }
        final int from = selectedPlanIndex;
        final int to = index;
        selectedPlanIndex = to;
        fillFeaturesForPlan(to);

        if (!animated) {
            accent = PlanPalette.forPlan(to);
            updateAccentSurfaces(false);
            if (logoView != null) {
                logoView.setLogo(logoResForPlan(to));
            }
            rebuildFeatures();
            refreshPlanCards();
            updateButtonText(false);
            updateHint();
            return;
        }

        if (planSwitchAnimator != null) {
            planSwitchAnimator.cancel();
        }
        final PlanPalette fromPal = PlanPalette.forPlan(from);
        final PlanPalette toPal = PlanPalette.forPlan(to);
        final ArgbEvaluator eval = new ArgbEvaluator();
        final boolean[] mid = {false};

        planSwitchAnimator = ValueAnimator.ofFloat(0f, 1f);
        planSwitchAnimator.setDuration(340);
        planSwitchAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        planSwitchAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            accent = new PlanPalette(
                    (int) eval.evaluate(t, fromPal.light, toPal.light),
                    (int) eval.evaluate(t, fromPal.mid, toPal.mid),
                    (int) eval.evaluate(t, fromPal.deep, toPal.deep),
                    (int) eval.evaluate(t, fromPal.dark, toPal.dark),
                    (int) eval.evaluate(t, fromPal.onAccent, toPal.onAccent)
            );
            updateAccentSurfaces(true);
            if (featuresCard != null) {
                float alpha = t < 0.45f ? 1f - t / 0.45f : (t - 0.45f) / 0.55f;
                featuresCard.setAlpha(Math.max(0f, Math.min(1f, alpha)));
            }
            if (!mid[0] && t >= 0.45f) {
                mid[0] = true;
                if (logoView != null) {
                    logoView.setLogo(logoResForPlan(to));
                }
                // Build feature icons with target palette, not the blended mid color.
                PlanPalette blended = accent;
                accent = toPal;
                rebuildFeatures();
                accent = blended;
                refreshPlanCards();
                updateButtonText(true);
                updateHint();
            }
        });
        planSwitchAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Always rebind with final palette — mid-switch rebuild used blended colors.
                accent = PlanPalette.forPlan(to);
                updateAccentSurfaces(false);
                if (logoView != null) {
                    logoView.setLogo(logoResForPlan(to));
                }
                rebuildFeatures();
                refreshPlanCards();
                updateButtonText(true);
                updateHint();
                if (featuresCard != null) {
                    featuresCard.setAlpha(1f);
                }
                if (planSwitchAnimator == animation) {
                    planSwitchAnimator = null;
                }
            }
        });
        planSwitchAnimator.start();
        refreshPlanCards();
    }

    private void updateAccentSurfaces(boolean duringAnim) {
        if (hubLayout != null) {
            hubLayout.setAmbientGlowColors(accent.mid, accent.light, accent.deep);
        }
        if (titleView != null) {
            titleView.setTextColor(COLOR_TEXT);
        }
        if (subtitleView != null) {
            subtitleView.setTextColor(ColorUtils.setAlphaComponent(COLOR_TEXT, 200));
        }
        if (premiumButtonView != null) {
            premiumButtonView.buttonTextView.setTextColor(accent.onAccent);
            premiumButtonView.invalidate();
        }
        actionBar.setItemsColor(COLOR_TEXT, false);
        if (!duringAnim) {
            for (PlanCardView card : planCards) {
                card.invalidate();
            }
        }
    }

    private void refreshPlanCards() {
        for (int i = 0; i < planCards.size(); i++) {
            planCards.get(i).setSelected(i == selectedPlanIndex, true);
        }
    }

    private void rebuildFeatures() {
        if (featuresCard == null) {
            return;
        }
        featuresCard.removeAllViews();
        Context context = featuresCard.getContext();
        for (int i = 0; i < features.size(); i++) {
            FeatureItem item = features.get(i);
            if (i > 0) {
                View div = new View(context);
                div.setBackgroundColor(COLOR_DIVIDER);
                featuresCard.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 56, 0, 0, 0));
            }
            featuresCard.addView(createFeatureRow(context, item),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        MglaUi.attachPressSpringRecursive(featuresCard);
    }

    private View createFeatureRow(Context context, FeatureItem item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setOnClickListener(v -> openFeature(item.type));
        ScaleStateListAnimator.apply(row, 0.02f, 1.5f);

        GlowFeatureIcon iconWrap = new GlowFeatureIcon(context);
        iconWrap.setIcon(item.icon, accent.light, accent.mid, accent.deep, accent.onAccent);
        row.addView(iconWrap, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(item.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(COLOR_TEXT);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView desc = new TextView(context);
        desc.setText(item.description);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        desc.setTextColor(0xFF9AA0A6);
        desc.setPadding(0, dp(2), 0, 0);
        texts.addView(desc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.msg_arrowright);
        arrow.setColorFilter(new PorterDuffColorFilter(0xFF6B7078, PorterDuff.Mode.SRC_IN));
        row.addView(arrow, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        return row;
    }

    private void updateButtonText(boolean animated) {
        if (premiumButtonView == null || selectedPlanIndex < 0 || selectedPlanIndex >= plans.size()) {
            return;
        }
        final int active = activePlan();
        final long userId = currentUserId();
        final PlanData plan = plans.get(selectedPlanIndex);

        if (active == selectedPlanIndex) {
            String until = MglaPremiumController.formatExpiresLabel(userId);
            String text = TextUtils.isEmpty(until) ? "Ваш текущий план" : plan.title + " · " + until;
            premiumButtonView.setButton(text, v -> {
                String msg = TextUtils.isEmpty(until)
                        ? "Подписка активна на этом аккаунте"
                        : "Активен до " + MglaSubscription.formatExpiresAt(
                        MglaPremiumController.getExpiresAt(userId));
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, msg).show();
            }, animated);
            return;
        }
        if (active > selectedPlanIndex) {
            premiumButtonView.setButton("У вас " + MglaPremiumController.planTitle(active),
                    v -> selectPlan(active, true), animated);
            return;
        }
        premiumButtonView.setButton("Оплатить · " + plan.priceRub + " ₽/мес",
                v -> openAdminChatForPlan(plan), animated);
    }

    private void openFeature(int type) {
        switch (type) {
            case FEATURE_ANIMATIONS:
                presentFragment(MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_COMMON));
                break;
            case FEATURE_TASKS:
            case FEATURE_TASKS_SYNC:
                openMainTab(MglaMainTabsConfig.TabId.TASKS);
                break;
            case FEATURE_SETTINGS_SYNC:
                presentFragment(new MglaMainSettingsActivity());
                break;
            case FEATURE_LOCAL_MODELS:
            case FEATURE_CLOUD_AI:
            case FEATURE_ULTRA_AI:
                presentFragment(new MglaAiSettingsActivity());
                break;
            case FEATURE_MEMORY:
                presentFragment(new MglaMemoryActivity());
                break;
            case FEATURE_ICON_THEMES:
                presentFragment(new MglaAppearanceSettingsActivity());
                break;
            case FEATURE_AUTO_TRANSLATE:
                presentFragment(new MglaChatsSettingsActivity());
                break;
            case FEATURE_PRIORITY_BYPASS:
                presentFragment(new MglaDpiBypassActivity());
                break;
            default:
                break;
        }
    }

    private void openMainTab(MglaMainTabsConfig.TabId tabId) {
        finishFragment();
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last instanceof MainTabsActivity) {
                ((MainTabsActivity) last).openTab(tabId);
            }
        });
    }

    private void openAdminChatForPlan(PlanData plan) {
        if (getParentActivity() == null || plan == null) {
            return;
        }
        final String draft = "Привет! Хочу купить подписку тарифа " + plan.tabTitle;
        try {
            TLRPC.User cached = null;
            TLObject obj = getMessagesController().getUserOrChat(ADMIN_USERNAME);
            if (obj instanceof TLRPC.User && !((TLRPC.User) obj).min) {
                cached = (TLRPC.User) obj;
            }
            if (cached != null) {
                presentAdminChat(cached, draft);
                return;
            }
            AlertDialog[] progressDialog = new AlertDialog[]{
                    new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER)
            };
            boolean[] canceled = new boolean[]{false};
            getMessagesController().getUserNameResolver().resolve(ADMIN_USERNAME, peerId -> {
                try {
                    if (progressDialog[0] != null) {
                        progressDialog[0].dismiss();
                    }
                } catch (Exception ignored) {
                }
                progressDialog[0] = null;
                setVisibleDialog(null);
                if (canceled[0]) {
                    return;
                }
                if (peerId != null && peerId > 0) {
                    TLRPC.User user = getMessagesController().getUser(peerId);
                    if (user != null) {
                        presentAdminChat(user, draft);
                        return;
                    }
                }
                Browser.openUrl(getParentActivity(), "https://t.me/" + ADMIN_USERNAME);
            });
            AndroidUtilities.runOnUIThread(() -> {
                if (progressDialog[0] == null || getParentActivity() == null) {
                    return;
                }
                progressDialog[0].setOnCancelListener(d -> canceled[0] = true);
                showDialog(progressDialog[0]);
            }, 400);
        } catch (Throwable e) {
            FileLog.e(e);
            Browser.openUrl(getParentActivity(), "https://t.me/" + ADMIN_USERNAME);
        }
    }

    private void presentAdminChat(TLRPC.User user, String draft) {
        if (user == null || UserObject.isDeleted(user)) {
            Browser.openUrl(getParentActivity(), "https://t.me/" + ADMIN_USERNAME);
            return;
        }
        Bundle args = new Bundle();
        args.putLong("user_id", user.id);
        if (!TextUtils.isEmpty(draft)) {
            args.putString("start_text", draft);
        }
        presentFragment(new ChatActivity(args));
    }

    // ── Rounded logo (ambient glow lives on the whole hub background) ──────

    private class GlowLogoView extends View {
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint roundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private final Matrix shaderMatrix = new Matrix();
        private Bitmap logoBitmap;
        private BitmapShader logoShader;

        GlowLogoView(Context context) {
            super(context);
            setWillNotDraw(false);
            roundPaint.setColor(0xFF0A0A0C);
        }

        void setLogo(int resId) {
            Drawable d = getResources().getDrawable(resId).mutate();
            int size = Math.max(dp(96), 1);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            logoBitmap = bmp;
            logoShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            logoPaint.setShader(logoShader);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float cx = w / 2f;
            float cy = h / 2f;
            float icon = Math.min(w, h) * 0.78f;
            float r = icon * 0.22f;
            rect.set(cx - icon / 2f, cy - icon / 2f, cx + icon / 2f, cy + icon / 2f);
            clipPath.rewind();
            clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.drawPath(clipPath, roundPaint);
            if (logoBitmap != null && logoShader != null) {
                shaderMatrix.reset();
                float scale = icon / logoBitmap.getWidth();
                shaderMatrix.setScale(scale, scale);
                shaderMatrix.postTranslate(rect.left, rect.top);
                logoShader.setLocalMatrix(shaderMatrix);
                canvas.save();
                canvas.clipPath(clipPath);
                canvas.drawRoundRect(rect, r, r, logoPaint);
                canvas.restore();
            }
        }
    }

    private class GlowFeatureIcon extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Drawable icon;
        private int colorTop;
        private int colorBottom;

        GlowFeatureIcon(Context context) {
            super(context);
            setWillNotDraw(false);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(1));
            strokePaint.setColor(0x33FFFFFF);
        }

        void setIcon(int resId, int light, int mid, int deep, int onAccent) {
            colorTop = light;
            colorBottom = deep != 0 ? deep : mid;
            icon = getResources().getDrawable(resId).mutate();
            icon.setColorFilter(new PorterDuffColorFilter(onAccent, PorterDuff.Mode.SRC_IN));
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float corner = dp(10);
            rect.set(0, 0, w, h);
            LinearGradient gradient = new LinearGradient(0, 0, 0, h,
                    new int[]{colorTop, colorBottom},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
            bgPaint.setShader(gradient);
            canvas.drawRoundRect(rect, corner, corner, bgPaint);
            canvas.drawRoundRect(rect, corner, corner, strokePaint);
            if (icon != null) {
                int s = dp(22);
                int l = (int) ((w - s) / 2f);
                int top = (int) ((h - s) / 2f);
                icon.setBounds(l, top, l + s, top + s);
                icon.draw(canvas);
            }
        }
    }

    // ── Hub shell with draggable sheet ─────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private class PremiumHubLayout extends FrameLayout implements NestedScrollingParent3 {

        private final NestedScrollingParentHelper nestedHelper = new NestedScrollingParentHelper(this);
        private final FrameLayout sheet;
        private final NestedScrollView scrollView;
        private final LinearLayout content;
        private final View[] stagger = new View[20];
        private int staggerCount;

        private float progress; // 0 = peek (header visible), 1 = expanded
        private ValueAnimator settleAnimator;
        private float dragStartProgress;
        private float dragStartY;
        private boolean draggingHandle;
        private boolean entrancePlaying;
        private VelocityTracker velocityTracker;
        private final int touchSlop;
        private final int maxFling;
        private float nestedVelocityY;
        private long nestedVelocityTime;
        private int measuredHeaderH;

        private final Paint ambientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int ambientMid = 0xFF4A90D9;
        private int ambientLight = 0xFF8EC5FF;
        private int ambientDeep = 0xFF1A3A66;
        private boolean ambientRunning;
        private long ambientStartMs;

        PremiumHubLayout(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(COLOR_BG);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            maxFling = ViewConfiguration.get(context).getScaledMaximumFlingVelocity();
            ambientMid = accent.mid;
            ambientLight = accent.light;
            ambientDeep = accent.deep;
            startAmbientGlow();

            headerBrand = buildHeader(context);
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
                    if (ev.getY() <= dp(28) || draggingHandle) {
                        return false;
                    }
                    return super.dispatchTouchEvent(ev);
                }

                @Override
                public void fling(int velocityY) {
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
            scrollView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + dp(88));

            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(22), dp(16), dp(8));
            buildSheet(context, content);
            scrollView.addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            sheet.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 18, 0, 0));
            addView(sheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

            premiumButtonView = new PremiumButtonView(context, dp(14), false, getResourceProvider()) {
                @Override
                protected void dispatchDraw(Canvas canvas) {
                    AndroidUtilities.rectTmp.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(accent.mid);
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(14), dp(14), p);
                    drawGradient = false;
                    for (int i = 0; i < getChildCount(); i++) {
                        drawChild(canvas, getChildAt(i), getDrawingTime());
                    }
                }
            };
            premiumButtonView.buttonTextView.setTextColor(accent.onAccent);
            buttonContainer = new FrameLayout(context) {
                @Override
                protected void dispatchDraw(@NonNull Canvas canvas) {
                    Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fade.setShader(new LinearGradient(0, 0, 0, getMeasuredHeight(),
                            new int[]{0x00121417, 0xE6121417, COLOR_SHEET},
                            new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                    canvas.drawRect(0, 0, getWidth(), getHeight(), fade);
                    super.dispatchDraw(canvas);
                }
            };
            buttonContainer.setWillNotDraw(false);
            buttonContainer.setPadding(dp(16), dp(16), dp(16), dp(16) + AndroidUtilities.navigationBarHeight / 3);
            buttonContainer.addView(premiumButtonView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50));
            ScaleStateListAnimator.apply(premiumButtonView, 0.02f, 1.5f);
            addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

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
                        setProgress(dragStartProgress + dy / sheetRange(), false);
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

        private View buildHeader(Context context) {
            LinearLayout brand = new LinearLayout(context);
            brand.setOrientation(LinearLayout.VERTICAL);
            brand.setGravity(Gravity.CENTER_HORIZONTAL);
            int topPad = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(8);
            brand.setPadding(dp(24), topPad, dp(24), dp(24));

            logoView = new GlowLogoView(context);
            logoView.setLogo(logoResForPlan(selectedPlanIndex));
            brand.addView(logoView, LayoutHelper.createLinear(112, 112, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

            titleView = new TextView(context);
            titleView.setText("Mgla Premium");
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(COLOR_TEXT);
            titleView.setGravity(Gravity.CENTER);
            brand.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setLineSpacing(dp(2), 1f);
            updateSubtitle();
            brand.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            return brand;
        }

        private void buildSheet(Context context, LinearLayout root) {
            TextView plansTitle = sectionTitle(context, "Тарифы");
            root.addView(plansTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 10));
            track(plansTitle);

            planCardsRow = new LinearLayout(context);
            planCardsRow.setOrientation(LinearLayout.HORIZONTAL);
            planCards.clear();
            for (int i = 0; i < plans.size(); i++) {
                final int index = i;
                PlanCardView card = new PlanCardView(context);
                card.bind(plans.get(i));
                card.setSelected(i == selectedPlanIndex, false);
                card.setOnClickListener(v -> selectPlan(index, true));
                ScaleStateListAnimator.apply(card, 0.03f, 1.5f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f);
                if (i > 0) {
                    lp.leftMargin = dp(8);
                }
                planCardsRow.addView(card, lp);
                planCards.add(card);
            }
            root.addView(planCardsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));
            track(planCardsRow);

            featuresHeader = sectionTitle(context, "Что входит");
            root.addView(featuresHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 10));
            track(featuresHeader);

            featuresCard = createCard(context);
            rebuildFeatures();
            root.addView(featuresCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));
            track(featuresCard);

            hintView = new TextView(context);
            hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hintView.setTextColor(0xFF9AA0A6);
            hintView.setLineSpacing(dp(2), 1f);
            updateHint();
            root.addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 8));
            track(hintView);
        }

        private TextView sectionTitle(Context context, String text) {
            TextView t = new TextView(context);
            t.setText(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(0xFF9AA0A6);
            t.setAllCaps(true);
            t.setLetterSpacing(0.06f);
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

        private void track(View v) {
            if (v != null && staggerCount < stagger.length) {
                stagger[staggerCount++] = v;
                v.setAlpha(0f);
                v.setTranslationY(dp(14));
            }
        }

        void setAmbientGlowColors(int mid, int light, int deep) {
            ambientMid = mid;
            ambientLight = light;
            ambientDeep = deep;
            invalidate();
        }

        void startAmbientGlow() {
            if (ambientRunning) {
                return;
            }
            ambientRunning = true;
            ambientStartMs = SystemClock.uptimeMillis();
            invalidate();
        }

        void stopAmbientGlow() {
            ambientRunning = false;
        }

        private void drawAmbientGlow(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float t = ((SystemClock.uptimeMillis() - ambientStartMs) % 4200L) / 4200f;
            float pulseA = (float) (0.45 + 0.55 * Math.sin(t * Math.PI * 2));
            float pulseB = (float) (0.35 + 0.65 * Math.sin((t + 0.33f) * Math.PI * 2));
            float pulseC = (float) (0.40 + 0.60 * Math.sin((t + 0.66f) * Math.PI * 2));

            // Soft wash across the whole section (black area behind sheet).
            float r1 = Math.max(w, h) * (0.72f + 0.12f * pulseA);
            ambientPaint.setShader(new RadialGradient(
                    w * 0.5f, h * 0.18f, r1,
                    new int[]{
                            ColorUtils.setAlphaComponent(ambientLight, (int) (70 + 55 * pulseA)),
                            ColorUtils.setAlphaComponent(ambientMid, (int) (45 + 40 * pulseB)),
                            ColorUtils.setAlphaComponent(ambientDeep, (int) (18 + 18 * pulseA)),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.35f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, ambientPaint);

            float r2 = Math.max(w, h) * (0.55f + 0.14f * pulseB);
            ambientPaint.setShader(new RadialGradient(
                    w * 0.12f, h * 0.42f, r2,
                    new int[]{
                            ColorUtils.setAlphaComponent(ambientMid, (int) (50 + 45 * pulseB)),
                            ColorUtils.setAlphaComponent(ambientDeep, (int) (22 + 20 * pulseC)),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, ambientPaint);

            float r3 = Math.max(w, h) * (0.5f + 0.16f * pulseC);
            ambientPaint.setShader(new RadialGradient(
                    w * 0.88f, h * 0.32f, r3,
                    new int[]{
                            ColorUtils.setAlphaComponent(ambientLight, (int) (40 + 50 * pulseC)),
                            ColorUtils.setAlphaComponent(ambientMid, (int) (20 + 25 * pulseA)),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, ambientPaint);

            // Center bloom under logo / title.
            float r4 = Math.max(w, h) * (0.38f + 0.1f * pulseA);
            ambientPaint.setShader(new RadialGradient(
                    w * 0.5f, Math.max(dp(120), measuredHeaderH * 0.45f), r4,
                    new int[]{
                            ColorUtils.setAlphaComponent(ambientLight, (int) (55 + 45 * pulseA)),
                            ColorUtils.setAlphaComponent(ambientMid, (int) (28 + 25 * pulseB)),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, ambientPaint);
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            canvas.drawColor(COLOR_BG);
            drawAmbientGlow(canvas);
            super.dispatchDraw(canvas);
            if (ambientRunning) {
                invalidate();
            }
        }

        void playEntrance() {
            entrancePlaying = true;
            if (headerBrand != null) {
                headerBrand.setAlpha(0f);
                headerBrand.setScaleX(0.94f);
                headerBrand.setScaleY(0.94f);
                headerBrand.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(480).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
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
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    entrancePlaying = false;
                    applyVisuals();
                }
            });
            anim.start();
            for (int i = 0; i < staggerCount; i++) {
                View v = stagger[i];
                if (v == null) continue;
                v.animate().alpha(1f).translationY(0)
                        .setStartDelay(90L + i * 40L).setDuration(360)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
            if (buttonContainer != null) {
                buttonContainer.setAlpha(0f);
                buttonContainer.setTranslationY(dp(24));
                buttonContainer.animate().alpha(1f).translationY(0)
                        .setStartDelay(220).setDuration(400)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
        }

        private int peekTop() {
            // Keep full brand (logo + title + subtitle) visible above the sheet.
            int byHeader = measuredHeaderH > 0
                    ? measuredHeaderH - dp(10)
                    : (int) (getHeight() * 0.52f);
            return Math.max(expandedTop() + dp(160), Math.min(byHeader, getHeight() - dp(220)));
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
            float fade = 1f - progress * 0.85f;
            headerBrand.setAlpha(Math.max(0f, fade));
            float scale = 1f - progress * 0.08f;
            headerBrand.setScaleX(scale);
            headerBrand.setScaleY(scale);
            headerBrand.setTranslationY(-progress * dp(24));
            actionBar.setAlpha(1f - progress * 0.12f);
        }

        private void cancelSettle() {
            if (settleAnimator != null) {
                settleAnimator.cancel();
                settleAnimator = null;
            }
        }

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
            long duration;
            if (Math.abs(velocityYPx) > 80) {
                duration = (long) Math.max(140, Math.min(360, distancePx / speed * 1000f));
            } else {
                duration = (long) Math.max(200, Math.min(380, 180 + distancePx * 0.35f));
            }
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
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            measuredHeaderH = headerBrand.getMeasuredHeight();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            measuredHeaderH = headerBrand.getHeight();
            int sheetH = getHeight() - expandedTop() + dp(28);
            sheet.layout(0, 0, getWidth(), Math.max(sheetH, getHeight()));
            int btnH = buttonContainer.getMeasuredHeight();
            buttonContainer.layout(0, getHeight() - btnH, getWidth(), getHeight());
            applyVisuals();
        }

        @Override
        public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
            return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        }

        @Override
        public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
            nestedHelper.onNestedScrollAccepted(child, target, axes, type);
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
            float sheetVel = -nestedVelocityY;
            nestedVelocityY = 0;
            nestedVelocityTime = 0;
            if (settleAnimator == null && progress > 0.002f && progress < 0.998f) {
                settleFromSheetVelocity(sheetVel);
            }
        }

        @Override
        public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
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
            if (!consumed && progress > 0.001f && progress < 0.998f) {
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

    private class PlanCardView extends FrameLayout {
        private final TextView nameView;
        private final TextView priceView;
        private final TextView badgeView;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private PlanPalette palette = PlanPalette.forPlan(PLAN_PREMIUM);
        private boolean selected;
        private int planId;

        PlanCardView(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(10), dp(14), dp(10), dp(14));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(1.2f));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setPadding(dp(6), dp(2), dp(6), dp(2));
            badgeView.setVisibility(GONE);
            col.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setGravity(Gravity.CENTER);
            nameView.setSingleLine();
            col.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            priceView = new TextView(context);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            priceView.setGravity(Gravity.CENTER);
            priceView.setSingleLine();
            col.addView(priceView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));

            addView(col, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void bind(PlanData plan) {
            planId = plan.id;
            palette = PlanPalette.forPlan(plan.id);
            nameView.setText(plan.tabTitle);
            priceView.setText(plan.priceRub + " ₽");
            int active = activePlan();
            if (active == plan.id) {
                badgeView.setVisibility(VISIBLE);
                badgeView.setText("активен");
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(8));
                bg.setColor(ColorUtils.setAlphaComponent(palette.mid, 200));
                badgeView.setBackground(bg);
                badgeView.setTextColor(palette.onAccent);
            } else {
                badgeView.setVisibility(GONE);
            }
            invalidate();
        }

        @Override
        public void setSelected(boolean selected) {
            setSelected(selected, false);
        }

        void setSelected(boolean selected, boolean animated) {
            boolean changed = this.selected != selected;
            this.selected = selected;
            nameView.setTextColor(selected ? palette.onAccent : COLOR_TEXT);
            priceView.setTextColor(selected ? ColorUtils.setAlphaComponent(palette.onAccent, 0xCC) : 0xFF9AA0A6);
            if (animated && changed) {
                animate().cancel();
                setScaleX(selected ? 0.94f : 1.04f);
                setScaleY(selected ? 0.94f : 1.04f);
                animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float r = dp(16);
            AndroidUtilities.rectTmp.set(dp(0.5f), dp(0.5f), getWidth() - dp(0.5f), getHeight() - dp(0.5f));
            path.rewind();
            path.addRoundRect(AndroidUtilities.rectTmp, r, r, Path.Direction.CW);
            if (selected) {
                fillPaint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                        new int[]{palette.dark, palette.mid, palette.light},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawPath(path, fillPaint);
            } else {
                fillPaint.setShader(null);
                fillPaint.setColor(COLOR_CARD);
                canvas.drawPath(path, fillPaint);
                strokePaint.setColor(ColorUtils.setAlphaComponent(palette.mid, 0x55));
                canvas.drawPath(path, strokePaint);
            }
            super.onDraw(canvas);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(96), MeasureSpec.EXACTLY));
        }
    }

    private static final class PlanPalette {
        final int light;
        final int mid;
        final int deep;
        final int dark;
        final int onAccent;

        PlanPalette(int light, int mid, int deep, int dark, int onAccent) {
            this.light = light;
            this.mid = mid;
            this.deep = deep;
            this.dark = dark;
            this.onAccent = onAccent;
        }

        static PlanPalette forPlan(int plan) {
            int p = plan;
            if (p < PLAN_PREMIUM || p > PLAN_ULTRA) {
                p = PLAN_ULTRA;
            }
            return new PlanPalette(
                    MglaPremiumController.colorLight(p),
                    MglaPremiumController.colorMid(p),
                    MglaPremiumController.colorDeep(p),
                    MglaPremiumController.colorDark(p),
                    MglaPremiumController.colorOnAccent(p)
            );
        }
    }

    private static final class FeatureItem {
        final int type;
        final int icon;
        final String title;
        final String description;

        FeatureItem(int type, int icon, String title, String description) {
            this.type = type;
            this.icon = icon;
            this.title = title;
            this.description = description;
        }
    }

    public static class PlanData {
        public final int id;
        public final String tabTitle;
        public final String title;
        public final int priceRub;

        public PlanData(int id, String tabTitle, String title, int priceRub) {
            this.id = id;
            this.tabTitle = tabTitle;
            this.title = title;
            this.priceRub = priceRub;
        }
    }
}
