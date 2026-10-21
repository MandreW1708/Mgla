package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaMainTabsConfig;
import org.telegram.messenger.MglaPremiumController;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FillLastLinearLayoutManager;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaPremiumStarHelper;
import org.telegram.ui.Components.Premium.PremiumButtonView;
import org.telegram.ui.Components.Premium.StarParticlesView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.HintView2;

import java.util.ArrayList;

/**
 * Экран подписки Mgla Premium — чёрно-золотое оформление,
 * три плана: Premium / Premium+ / Premium Ultra.
 */
public class MglaPremiumActivity extends MglaBaseSettingsActivity {

    public static final int PLAN_PREMIUM = 0;
    public static final int PLAN_PLUS = 1;
    public static final int PLAN_ULTRA = 2;
    /** Bundle: open on a specific plan tab ({@link #PLAN_PREMIUM}..{@link #PLAN_ULTRA}). */
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

    private static final int COLOR_BG = 0xFF000000;
    private static final int COLOR_CARD = 0xFF121212;
    private static final int COLOR_TAB_IDLE = 0xFF1A1A1A;
    private static final int COLOR_TEXT = 0xFFF5F5F5;

    private final ArrayList<PlanData> plans = new ArrayList<>();
    private final ArrayList<PremiumPreviewFragment.PremiumFeatureData> premiumFeatures = new ArrayList<>();

    private int selectedPlanIndex = PLAN_PREMIUM;
    private PlanPalette accent = PlanPalette.forPlan(PLAN_PREMIUM);

    private FrameLayout contentView;
    private BackgroundView backgroundView;
    private StarParticlesView particlesView;
    private Paint[] particlePaints;
    private RecyclerListView listView;
    private FillLastLinearLayoutManager layoutManager;
    private FrameLayout buttonContainer;
    private PremiumButtonView premiumButtonView;
    private PremiumFeatureCell dummyCell;

    private int rowCount;
    private int paddingRow;
    private int featuresStartRow;
    private int featuresEndRow;
    private int statusRow;
    private int lastPaddingRow;

    private int firstViewHeight;
    private int statusBarHeight;
    private int totalGradientHeight;
    private float progress;
    private boolean inc = true;
    private float totalProgress;
    private float progressToFull;
    private int currentYOffset;
    private boolean isLandscapeMode;

    private final Paint accentIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentButtonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader iconShader;
    private Shader buttonShader;
    private final Matrix matrix = new Matrix();
    private int buttonWidth;
    private ValueAnimator planSwitchAnimator;

    public MglaPremiumActivity() {
        this(null);
    }

    public MglaPremiumActivity(android.os.Bundle args) {
        super(args);
    }

    @SuppressLint("NotifyDataSetChanged")
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

        accentStrokePaint.setStyle(Paint.Style.STROKE);
        accentStrokePaint.setStrokeWidth(dp(1));
        applyAccentForPlan(selectedPlanIndex);

        dummyCell = new PremiumFeatureCell(context);
        fillFeaturesForPlan(selectedPlanIndex);
        statusBarHeight = AndroidUtilities.statusBarHeight;

        contentView = new FrameLayout(context) {
            boolean tabsInterceptedTouch;

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                float tabsX = backgroundView.getX() + backgroundView.tabsRow.getX();
                float tabsY = backgroundView.getY() + backgroundView.tabsRow.getY();
                AndroidUtilities.rectTmp.set(tabsX, tabsY, tabsX + backgroundView.tabsRow.getWidth(), tabsY + backgroundView.tabsRow.getHeight());
                if (progressToFull < 1.0f && (AndroidUtilities.rectTmp.contains(ev.getX(), ev.getY()) || tabsInterceptedTouch) && !listView.scrollingByUser) {
                    ev.offsetLocation(-tabsX, -tabsY);
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        tabsInterceptedTouch = true;
                    } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                        tabsInterceptedTouch = false;
                    }
                    backgroundView.tabsRow.dispatchTouchEvent(ev);
                    if (tabsInterceptedTouch) {
                        return true;
                    }
                }
                return super.dispatchTouchEvent(ev);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                isLandscapeMode = MeasureSpec.getSize(widthMeasureSpec) > MeasureSpec.getSize(heightMeasureSpec);
                statusBarHeight = AndroidUtilities.statusBarHeight;
                backgroundView.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                particlesView.getLayoutParams().height = backgroundView.getMeasuredHeight();
                int buttonHeight = buttonContainer.getVisibility() == View.GONE ? 0 : dp(68);
                layoutManager.setAdditionalHeight(buttonHeight + statusBarHeight - dp(16));
                layoutManager.setMinimumLastViewHeight(buttonHeight);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                measureGradient(w, h);
                buttonWidth = w;
                rebuildButtonShader();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (inc) {
                    progress += 16f / 1000f;
                    if (progress > 3) {
                        inc = false;
                    }
                } else {
                    progress -= 16f / 1000f;
                    if (progress < 1) {
                        inc = true;
                    }
                }

                View firstView = null;
                if (listView.getLayoutManager() != null) {
                    firstView = listView.getLayoutManager().findViewByPosition(0);
                }
                currentYOffset = firstView == null ? 0 : firstView.getBottom();
                int h = actionBar.getBottom() + dp(16);
                totalProgress = Utilities.clamp(1f - (currentYOffset - h) / (float) Math.max(1, firstViewHeight - h), 1f, 0f);

                int maxTop = actionBar.getBottom() + dp(16);
                if (currentYOffset < maxTop) {
                    currentYOffset = maxTop;
                }

                float oldProgress = progressToFull;
                progressToFull = 0;
                if (currentYOffset < maxTop + dp(30)) {
                    progressToFull = (maxTop + dp(30) - currentYOffset) / (float) dp(30);
                }
                if (isLandscapeMode) {
                    progressToFull = 1f;
                    totalProgress = 1f;
                }
                if (oldProgress != progressToFull) {
                    listView.invalidate();
                }

                float fromTranslation = currentYOffset
                        - (actionBar.getMeasuredHeight() + backgroundView.getMeasuredHeight() - statusBarHeight)
                        + dp(48);
                float toTranslation = ((actionBar.getMeasuredHeight() - statusBarHeight - backgroundView.titleView.getMeasuredHeight()) / 2f)
                        + statusBarHeight - backgroundView.getTop() - backgroundView.titleView.getTop();
                float translationsY = Math.max(toTranslation, fromTranslation);
                float iconTranslationsY = -translationsY / 4f + dp(12);
                backgroundView.setTranslationY(translationsY);

                backgroundView.logoContainer.setTranslationY(iconTranslationsY + dp(8));
                float s = 0.62f + (1f - totalProgress) * 0.38f;
                float alpha = 1f - (totalProgress > 0.5f ? (totalProgress - 0.5f) / 0.5f : 0f);
                backgroundView.logoContainer.setScaleX(s);
                backgroundView.logoContainer.setScaleY(s);
                backgroundView.logoContainer.setAlpha(alpha);
                backgroundView.subtitleView.setAlpha(alpha);
                backgroundView.tabsRow.setAlpha(alpha);
                particlesView.setAlpha(1f - totalProgress);
                particlesView.setTranslationY(-(particlesView.getMeasuredHeight() - backgroundView.logoContainer.getMeasuredWidth()) / 2f
                        + backgroundView.getY() + backgroundView.logoContainer.getY());

                float toX = dp(72) - backgroundView.titleView.getLeft();
                float f = totalProgress > 0.3f ? (totalProgress - 0.3f) / 0.7f : 0f;
                backgroundView.titleView.setTranslationX(toX * (1f - CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(1 - f)));

                invalidate();
                buttonContainer.invalidate();

                canvas.drawColor(COLOR_BG);
                super.dispatchDraw(canvas);
            }
        };

        listView = new RecyclerListView(context);
        listView.setClipToOutline(true);
        listView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(dp(12), actionBar.getBottom() + dp(12), view.getWidth() - dp(12), view.getMeasuredHeight() + dp(16), dp(16));
            }
        });
        listView.setClipToPadding(false);
        listView.setLayoutManager(layoutManager = new FillLastLinearLayoutManager(context, dp(68) + statusBarHeight - dp(16), listView));
        layoutManager.setFixedLastItemHeight();
        listView.setAdapter(new Adapter());
        listView.setOnItemClickListener((view, position) -> {
            if (position < featuresStartRow || position >= featuresEndRow) {
                return;
            }
            int index = position - featuresStartRow;
            if (index < 0 || index >= premiumFeatures.size()) {
                return;
            }
            openFeature(premiumFeatures.get(index).type);
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int maxTop = actionBar.getBottom() + dp(16);
                    if (totalProgress > 0.5f) {
                        listView.smoothScrollBy(0, currentYOffset - maxTop);
                    } else {
                        View firstView = listView.getLayoutManager() != null ? listView.getLayoutManager().findViewByPosition(0) : null;
                        if (firstView != null && firstView.getTop() < 0) {
                            listView.smoothScrollBy(0, firstView.getTop());
                        }
                    }
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                contentView.invalidate();
            }
        });

        backgroundView = new BackgroundView(context);
        particlePaints = MglaPremiumStarHelper.createPaints();
        particlesView = MglaPremiumStarHelper.create(context, 80, selectedPlanIndex, particlePaints);
        particlesView.setClipWithGradient();

        contentView.addView(particlesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        contentView.addView(backgroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 0, 0, -48));

        premiumButtonView = new PremiumButtonView(context, dp(14), false, getResourceProvider()) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                // Flat solid fill — no gradient rim / highlight frames on the edges.
                AndroidUtilities.rectTmp.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                accentButtonPaint.setShader(null);
                accentButtonPaint.setColor(accent.mid);
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(14), dp(14), accentButtonPaint);

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
                        new int[]{0x00000000, 0xE6000000, 0xFF000000},
                        new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, getWidth(), getHeight(), fade);
                super.dispatchDraw(canvas);
            }
        };
        buttonContainer.setPadding(dp(16), dp(20), dp(16), dp(16));
        buttonContainer.setWillNotDraw(false);
        buttonContainer.addView(premiumButtonView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50));
        ScaleStateListAnimator.apply(premiumButtonView, 0.02f, 1.5f);
        contentView.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        actionBar.setBackground(null);
        actionBar.setCastShadows(false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAddToContainer(false);
        actionBar.setForceSkipTouches(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        updateColors();
        updateRows();
        updateButtonText(false);

        fragmentView = contentView;
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
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (premiumButtonView != null) {
            updateButtonText(false);
        }
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (backgroundView != null && backgroundView.subtitleView != null) {
            int active = activePlan();
            if (active >= PLAN_PREMIUM) {
                String until = MglaPremiumController.formatExpiresLabel(currentUserId());
                backgroundView.subtitleView.setText(TextUtils.isEmpty(until)
                        ? "На этом аккаунте активен " + MglaPremiumController.planTitle(active) + "."
                        : "На этом аккаунте активен " + MglaPremiumController.planTitle(active)
                                + " · " + until + ".");
            }
        }
    }

    /** Подписка текущего аккаунта (по user id), не всего клиента. */
    private long currentUserId() {
        return getUserConfig().getClientUserId();
    }

    private int activePlan() {
        return MglaPremiumController.getPlanForAccount(currentAccount);
    }

    private void applyAccentForPlan(int plan) {
        accent = PlanPalette.forPlan(plan);
        iconShader = new LinearGradient(
                0, 0, 0, 100,
                new int[]{accent.light, accent.mid, accent.deep, accent.dark},
                new float[]{0f, 0.35f, 0.7f, 1f},
                Shader.TileMode.CLAMP
        );
        accentIconPaint.setShader(iconShader);
        accentStrokePaint.setColor(ColorUtils.setAlphaComponent(accent.light, 90));
        rebuildButtonShader();
        if (premiumButtonView != null && premiumButtonView.buttonTextView != null) {
            premiumButtonView.buttonTextView.setTextColor(accent.onAccent);
        }
        MglaPremiumStarHelper.applyPlan(particlesView, particlePaints, plan);
        updateColors();
        if (premiumButtonView != null) {
            premiumButtonView.invalidate();
        }
    }

    private void rebuildButtonShader() {
        int w = Math.max(buttonWidth, AndroidUtilities.displaySize.x);
        buttonShader = new LinearGradient(0, 0, w, 0,
                new int[]{accent.dark, accent.mid, accent.light, accent.mid},
                new float[]{0f, 0.35f, 0.65f, 1f},
                Shader.TileMode.CLAMP);
        accentButtonPaint.setShader(buttonShader);
    }

    /** Open the settings / tab that corresponds to a premium feature row. */
    private void openFeature(int type) {
        switch (type) {
            case FEATURE_ANIMATIONS:
                presentFragment(MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_COMMON));
                break;
            case FEATURE_TASKS:
                openMainTab(MglaMainTabsConfig.TabId.TASKS);
                break;
            case FEATURE_LOCAL_MODELS:
                presentFragment(new MglaLocalModelsActivity());
                break;
            case FEATURE_MEMORY:
                presentFragment(new MglaMemoryActivity());
                break;
            case FEATURE_ICON_THEMES:
                presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
                break;
            case FEATURE_AUTO_TRANSLATE:
                presentFragment(new MglaChatsSettingsActivity());
                break;
            case FEATURE_CLOUD_AI:
            case FEATURE_ULTRA_AI:
                openMainTab(MglaMainTabsConfig.TabId.AI);
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

    private void fillFeaturesForPlan(int plan) {
        premiumFeatures.clear();
        premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                FEATURE_ANIMATIONS, R.drawable.menu_premium_effects,
                "Новые анимации",
                "Очень много крутых новых анимаций интерфейса"
        ));
        premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                FEATURE_TASKS, R.drawable.msg_premium_liststar,
                "Планировщик задач",
                "Удобный список задач прямо в приложении"
        ));
        premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                FEATURE_LOCAL_MODELS, R.drawable.premium_ai_editor,
                "Локальные модели",
                "Используйте локальные ИИ-модели на устройстве"
        ));
        premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                FEATURE_MEMORY, R.drawable.msg_premium_voice,
                "Живая память",
                "Персональная память ассистента, которая растёт вместе с вами"
        ));
        premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                FEATURE_ICON_THEMES, R.drawable.msg_premium_icons,
                "Темы для новых иконок",
                "Специально созданные темы под все иконки нового типа"
        ));
        premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                FEATURE_AUTO_TRANSLATE, R.drawable.msg_premium_translate,
                "Автоперевод",
                "Автоматический перевод сообщений и чатов"
        ));

        if (plan >= PLAN_PLUS) {
            premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                    FEATURE_CLOUD_AI, R.drawable.premium_ai_editor,
                    "Облачные ИИ-модели",
                    "Более умные облачные модели в дополнение к локальным"
            ));
        }
        if (plan >= PLAN_ULTRA) {
            premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                    FEATURE_ULTRA_AI, R.drawable.premium_ai_editor,
                    "Продвинутые ИИ-модели",
                    "Ещё более умные и дорогие облачные модели"
            ));
            premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                    FEATURE_PRIORITY_BYPASS, R.drawable.msg_premium_speed,
                    "Приоритет подключения и увеличенная скорость скачивания",
                    "Приоритет подключения и увеличенная скорость скачивания"
            ));
        }
    }

    private void updateRows() {
        rowCount = 0;
        paddingRow = rowCount++;
        featuresStartRow = rowCount;
        rowCount += premiumFeatures.size();
        featuresEndRow = rowCount;
        statusRow = rowCount++;
        lastPaddingRow = rowCount++;
    }

    private void measureGradient(int w, int h) {
        int yOffset = 0;
        for (int i = 0; i < premiumFeatures.size(); i++) {
            dummyCell.setData(premiumFeatures.get(i), false);
            dummyCell.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
            premiumFeatures.get(i).yOffset = yOffset;
            yOffset += dummyCell.getMeasuredHeight();
        }
        totalGradientHeight = Math.max(1, yOffset);
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

    @SuppressLint("NotifyDataSetChanged")
    private void selectPlan(int index, boolean animated) {
        if (index < 0 || index >= plans.size() || selectedPlanIndex == index) {
            return;
        }
        final int fromPlan = selectedPlanIndex;
        final int toPlan = index;
        selectedPlanIndex = toPlan;

        if (!animated || backgroundView == null) {
            applyAccentForPlan(toPlan);
            fillFeaturesForPlan(toPlan);
            updateRows();
            updateButtonText(false);
            if (backgroundView != null) {
                backgroundView.setLogo(toPlan, false);
                backgroundView.updateTabs(false);
            }
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyDataSetChanged();
            }
            if (contentView != null) {
                contentView.requestLayout();
            }
            return;
        }

        if (planSwitchAnimator != null) {
            planSwitchAnimator.cancel();
        }

        final PlanPalette from = PlanPalette.forPlan(fromPlan);
        final PlanPalette to = PlanPalette.forPlan(toPlan);
        final ArgbEvaluator eval = new ArgbEvaluator();

        // Mid-switch: swap feature list + logo while content is faded.
        final boolean[] midApplied = {false};

        backgroundView.updateTabs(true);
        backgroundView.setLogo(toPlan, true);

        planSwitchAnimator = ValueAnimator.ofFloat(0f, 1f);
        planSwitchAnimator.setDuration(320);
        planSwitchAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        planSwitchAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Blend accent paints live so icon chips / button / action bar morph.
            accent = new PlanPalette(
                    (int) eval.evaluate(t, from.light, to.light),
                    (int) eval.evaluate(t, from.mid, to.mid),
                    (int) eval.evaluate(t, from.deep, to.deep),
                    (int) eval.evaluate(t, from.dark, to.dark),
                    (int) eval.evaluate(t, from.onAccent, to.onAccent),
                    (int) eval.evaluate(t, from.textSecondary, to.textSecondary)
            );
            iconShader = new LinearGradient(
                    0, 0, 0, 100,
                    new int[]{accent.light, accent.mid, accent.deep, accent.dark},
                    new float[]{0f, 0.35f, 0.7f, 1f},
                    Shader.TileMode.CLAMP
            );
            accentIconPaint.setShader(iconShader);
            accentStrokePaint.setColor(ColorUtils.setAlphaComponent(accent.light, 90));
            rebuildButtonShader();
            if (premiumButtonView != null && premiumButtonView.buttonTextView != null) {
                premiumButtonView.buttonTextView.setTextColor(accent.onAccent);
            }
            updateColors();
            if (listView != null) {
                // Fade list out then in around the data swap.
                float listAlpha = t < 0.45f ? 1f - t / 0.45f : (t - 0.45f) / 0.55f;
                listView.setAlpha(Utilities.clamp(listAlpha, 1f, 0f));
                listView.invalidate();
            }
            if (premiumButtonView != null) {
                premiumButtonView.invalidate();
            }
            if (contentView != null) {
                contentView.invalidate();
            }

            if (!midApplied[0] && t >= 0.45f) {
                midApplied[0] = true;
                applyAccentForPlan(toPlan);
                fillFeaturesForPlan(toPlan);
                updateRows();
                updateButtonText(true);
                if (listView != null && listView.getAdapter() != null) {
                    listView.getAdapter().notifyDataSetChanged();
                }
                if (contentView != null) {
                    contentView.requestLayout();
                }
            }
        });
        planSwitchAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!midApplied[0]) {
                    applyAccentForPlan(toPlan);
                    fillFeaturesForPlan(toPlan);
                    updateRows();
                    updateButtonText(true);
                    if (listView != null && listView.getAdapter() != null) {
                        listView.getAdapter().notifyDataSetChanged();
                    }
                }
                if (listView != null) {
                    listView.setAlpha(1f);
                }
                planSwitchAnimator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (listView != null) {
                    listView.setAlpha(1f);
                }
                planSwitchAnimator = null;
            }
        });
        planSwitchAnimator.start();
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
            String text = TextUtils.isEmpty(until)
                    ? "Ваш текущий план"
                    : plan.title + " · " + until;
            premiumButtonView.setButton(text, v -> {
                String msg = TextUtils.isEmpty(until)
                        ? "Подписка активна на этом аккаунте"
                        : "Активен до " + MglaSubscription.formatExpiresAt(
                                MglaPremiumController.getExpiresAt(userId));
                BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.contact_check, msg)
                        .show();
            }, animated);
            return;
        }

        if (active > selectedPlanIndex) {
            premiumButtonView.setButton("У вас " + MglaPremiumController.planTitle(active), v -> {
                selectPlan(active, true);
            }, animated);
            return;
        }

        // Platega moderation path: tariff + concrete price + clickable pay → stub (no live checkout yet).
        String text = "Оплатить · " + plan.priceRub + " ₽/мес";
        premiumButtonView.setButton(text, v -> {
            BulletinFactory.of(this)
                    .createSimpleBulletin(
                            R.raw.error,
                            "Оплата пока недоступна. Подключение платёжной системы ещё не завершено.")
                    .show();
        }, animated);
    }

    private void updateColors() {
        if (backgroundView == null || actionBar == null) {
            return;
        }
        actionBar.setItemsColor(accent.light, true);
        actionBar.setItemsColor(accent.light, false);
        actionBar.setItemsBackgroundColor(ColorUtils.setAlphaComponent(accent.mid, 40), false);
        backgroundView.titleView.setTextColor(accent.light);
        backgroundView.subtitleView.setTextColor(accent.textSecondary);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private static final int TYPE_PADDING = 0;
        private static final int TYPE_FEATURE = 1;
        private static final int TYPE_SHADOW = 2;
        private static final int TYPE_BOTTOM_PADDING = 3;

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                default:
                case TYPE_PADDING:
                    view = new View(context) {
                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            if (isLandscapeMode) {
                                firstViewHeight = 0;
                            } else {
                                int h = dp(80 - 16);
                                if (backgroundView.getMeasuredHeight() + dp(24 - 16) > h) {
                                    h = backgroundView.getMeasuredHeight() + dp(24 - 16);
                                }
                                firstViewHeight = h;
                            }
                            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(firstViewHeight, MeasureSpec.EXACTLY));
                        }
                    };
                    break;
                case TYPE_FEATURE:
                    view = new PremiumFeatureCell(context) {
                        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

                        {
                            cardPaint.setColor(COLOR_CARD);
                            framePaint.setStyle(Paint.Style.STROKE);
                            framePaint.setStrokeWidth(dp(1));
                            // Icon vertically centered; more inset from the left card edge.
                            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) imageView.getLayoutParams();
                            iconLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                            iconLp.leftMargin = dp(30);
                            iconLp.topMargin = 0;
                            iconLp.bottomMargin = 0;
                            imageView.setLayoutParams(iconLp);
                            View textCol = getChildAt(0);
                            if (textCol != null) {
                                FrameLayout.LayoutParams textLp = (FrameLayout.LayoutParams) textCol.getLayoutParams();
                                textLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                                textLp.leftMargin = dp(78);
                                textLp.topMargin = dp(10);
                                textLp.bottomMargin = dp(10);
                                textLp.rightMargin = dp(44);
                                textCol.setLayoutParams(textLp);
                            }
                            FrameLayout.LayoutParams arrowLp = (FrameLayout.LayoutParams) nextIcon.getLayoutParams();
                            arrowLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                            arrowLp.rightMargin = dp(28);
                            nextIcon.setLayoutParams(arrowLp);
                        }

                        @Override
                        protected void dispatchDraw(Canvas canvas) {
                            // Every feature is its own framed card (no first/last-only border).
                            final float cardR = dp(16);
                            Path path = new Path();
                            AndroidUtilities.rectTmp.set(dp(16), dp(4), getWidth() - dp(16), getHeight() - dp(4));
                            path.addRoundRect(AndroidUtilities.rectTmp, cardR, cardR, Path.Direction.CW);
                            canvas.drawPath(path, cardPaint);
                            framePaint.setColor(accent.stroke);
                            canvas.drawPath(path, framePaint);

                            final float r = dp(10);
                            AndroidUtilities.rectTmp.set(imageView.getLeft(), imageView.getTop(), imageView.getRight(), imageView.getBottom());
                            matrix.reset();
                            matrix.postScale(1f, totalGradientHeight / 100f, 0, 0);
                            matrix.postTranslate(0, -data.yOffset);
                            if (iconShader instanceof LinearGradient) {
                                iconShader.setLocalMatrix(matrix);
                            }
                            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, accentIconPaint);
                            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, accentStrokePaint);

                            super.dispatchDraw(canvas);
                        }
                    };
                    break;
                case TYPE_SHADOW:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case TYPE_BOTTOM_PADDING:
                    view = new View(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position >= featuresStartRow && position < featuresEndRow) {
                PremiumFeatureCell cell = (PremiumFeatureCell) holder.itemView;
                cell.setData(premiumFeatures.get(position - featuresStartRow), false);
                cell.nextIcon.setVisibility(View.VISIBLE);
                cell.nextIcon.setColorFilter(new PorterDuffColorFilter(accent.textSecondary, PorterDuff.Mode.SRC_IN));
                cell.setBackgroundColor(Color.TRANSPARENT);
                cell.title.setTextColor(COLOR_TEXT);
                cell.description.setTextColor(accent.textSecondary);
            } else if (position == statusRow) {
                TextInfoPrivacyCell privacyCell = (TextInfoPrivacyCell) holder.itemView;
                privacyCell.setTextColor(accent.textSecondary);
                privacyCell.setFixedSize(0);
                privacyCell.setBackground(null);
                PlanData plan = plans.get(selectedPlanIndex);
                int active = activePlan();
                String hint;
                if (active == selectedPlanIndex) {
                    String until = MglaPremiumController.formatExpiresLabel(currentUserId());
                    hint = TextUtils.isEmpty(until)
                            ? "На этом аккаунте активен " + plan.title + ". На других аккаунтах подписка не действует."
                            : "На этом аккаунте активен " + plan.title + " " + until
                                    + ". На других аккаунтах подписка не действует.";
                } else if (plan.id == PLAN_PREMIUM) {
                    hint = "Premium: анимации, задачи, локальные модели, живая память, темы иконок и автоперевод.";
                } else if (plan.id == PLAN_PLUS) {
                    hint = "Premium+ включает всё из Premium и добавляет умные облачные ИИ-модели.";
                } else {
                    hint = "Premium Ultra включает всё из Premium и Premium+, плюс продвинутые модели, приоритет подключения и увеличенную скорость скачивания.";
                }
                privacyCell.setText(hint);
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == paddingRow) {
                return TYPE_PADDING;
            } else if (position >= featuresStartRow && position < featuresEndRow) {
                return TYPE_FEATURE;
            } else if (position == statusRow) {
                return TYPE_SHADOW;
            } else if (position == lastPaddingRow) {
                return TYPE_BOTTOM_PADDING;
            }
            return TYPE_PADDING;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_FEATURE;
        }
    }

    private class BackgroundView extends LinearLayout {

        final TextView titleView;
        final TextView subtitleView;
        final FrameLayout logoContainer;
        final ImageView logoView;
        final ImageView logoNextView;
        final LinearLayout tabsRow;
        final ArrayList<PlanTabView> tabViews = new ArrayList<>();

        public BackgroundView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setClipChildren(false);

            logoContainer = new FrameLayout(context);
            logoContainer.setClipChildren(false);
            addView(logoContainer, LayoutHelper.createLinear(168, 168, Gravity.CENTER_HORIZONTAL));

            logoView = new ImageView(context);
            logoView.setImageResource(logoResForPlan(selectedPlanIndex));
            logoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logoView.setAdjustViewBounds(true);
            logoContainer.addView(logoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // Second layer for cross-fade when switching Premium / Premium+ / Ultra icons.
            logoNextView = new ImageView(context);
            logoNextView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logoNextView.setAdjustViewBounds(true);
            logoNextView.setAlpha(0f);
            logoNextView.setVisibility(View.GONE);
            logoContainer.addView(logoNextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER_HORIZONTAL);
            titleView.setText("Mgla Premium");
            titleView.setLetterSpacing(0.04f);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_HORIZONTAL, 16, 14, 16, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitleView.setLineSpacing(dp(2), 1f);
            subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
            int active = activePlan();
            if (active >= PLAN_PREMIUM) {
                String until = MglaPremiumController.formatExpiresLabel(currentUserId());
                subtitleView.setText(TextUtils.isEmpty(until)
                        ? "На этом аккаунте активен " + MglaPremiumController.planTitle(active) + "."
                        : "На этом аккаунте активен " + MglaPremiumController.planTitle(active)
                                + " · " + until + ".");
            } else {
                subtitleView.setText("Эксклюзивные возможности Mgla. Выберите свой уровень.");
            }
            addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 0));
            subtitleView.getLayoutParams().width = Math.min(
                    AndroidUtilities.displaySize.x - dp(42),
                    HintView2.cutInFancyHalf(subtitleView.getText(), subtitleView.getPaint())
            );

            tabsRow = new LinearLayout(context);
            tabsRow.setOrientation(LinearLayout.HORIZONTAL);
            tabsRow.setPadding(dp(12), 0, dp(12), 0);
            addView(tabsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 4));

            for (int i = 0; i < plans.size(); i++) {
                final int index = i;
                PlanTabView tab = new PlanTabView(context);
                tab.bind(plans.get(i));
                tab.setOnClickListener(v -> selectPlan(index, true));
                ScaleStateListAnimator.apply(tab, 0.03f, 1.5f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f);
                if (i > 0) {
                    lp.leftMargin = dp(8);
                }
                tabsRow.addView(tab, lp);
                tabViews.add(tab);
            }
            updateTabs(false);
        }

        void setLogo(int plan, boolean animated) {
            final int res = logoResForPlan(plan);
            if (!animated) {
                logoView.animate().cancel();
                logoNextView.animate().cancel();
                logoView.setImageResource(res);
                logoView.setAlpha(1f);
                logoView.setScaleX(1f);
                logoView.setScaleY(1f);
                logoNextView.setVisibility(View.GONE);
                logoNextView.setAlpha(0f);
                return;
            }
            logoNextView.setImageResource(res);
            logoNextView.setVisibility(View.VISIBLE);
            logoNextView.setAlpha(0f);
            logoNextView.setScaleX(0.82f);
            logoNextView.setScaleY(0.82f);
            logoView.animate().cancel();
            logoNextView.animate().cancel();
            logoView.animate()
                    .alpha(0f)
                    .scaleX(0.82f)
                    .scaleY(0.82f)
                    .setDuration(180)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                    .start();
            logoNextView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            logoView.setImageResource(res);
                            logoView.setAlpha(1f);
                            logoView.setScaleX(1f);
                            logoView.setScaleY(1f);
                            logoNextView.setAlpha(0f);
                            logoNextView.setVisibility(View.GONE);
                            logoNextView.animate().setListener(null);
                        }
                    })
                    .start();
        }

        void updateTabs(boolean animated) {
            for (int i = 0; i < tabViews.size(); i++) {
                tabViews.get(i).setSelected(i == selectedPlanIndex, animated);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return true;
        }
    }

    private class PlanTabView extends FrameLayout {
        private final TextView titleView;
        private final TextView priceView;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean selected;
        private Shader selectedShader;
        private PlanPalette palette = PlanPalette.forPlan(PLAN_PREMIUM);
        private int planId = PLAN_PREMIUM;

        public PlanTabView(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(6), dp(6), dp(6), dp(6));

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(1));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER);
            titleView.setSingleLine();
            column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            priceView = new TextView(context);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            priceView.setGravity(Gravity.CENTER);
            priceView.setSingleLine();
            column.addView(priceView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 1, 0, 0));

            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        }

        void bind(PlanData plan) {
            planId = plan.id;
            palette = PlanPalette.forPlan(plan.id);
            titleView.setText(plan.tabTitle);
            priceView.setText(plan.priceRub + " ₽/мес");
            rebuildShader(getWidth());
        }

        @Override
        public void setSelected(boolean selected) {
            setSelected(selected, false);
        }

        void setSelected(boolean selected, boolean animated) {
            boolean changed = this.selected != selected;
            this.selected = selected;
            int titleColor = selected ? palette.onAccent : 0x99FFFFFF;
            int priceColor = selected ? ColorUtils.setAlphaComponent(palette.onAccent, 0xCC) : palette.mid;
            titleView.setTextColor(titleColor);
            priceView.setTextColor(priceColor);
            if (animated && changed) {
                animate().cancel();
                setScaleX(selected ? 0.94f : 1.04f);
                setScaleY(selected ? 0.94f : 1.04f);
                animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .start();
            }
            invalidate();
        }

        private void rebuildShader(int w) {
            if (w <= 0) {
                w = AndroidUtilities.displaySize.x / 3;
            }
            selectedShader = new LinearGradient(0, 0, w, 0,
                    new int[]{palette.dark, palette.mid, palette.light},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rebuildShader(w);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float r = dp(14);
            AndroidUtilities.rectTmp.set(dp(0.5f), dp(0.5f), getWidth() - dp(0.5f), getHeight() - dp(0.5f));
            path.rewind();
            path.addRoundRect(AndroidUtilities.rectTmp, r, r, Path.Direction.CW);
            if (selected) {
                fillPaint.setShader(selectedShader);
                fillPaint.setColor(palette.mid);
                canvas.drawPath(path, fillPaint);
            } else {
                fillPaint.setShader(null);
                fillPaint.setColor(COLOR_TAB_IDLE);
                canvas.drawPath(path, fillPaint);
                strokePaint.setColor(palette.stroke);
                canvas.drawPath(path, strokePaint);
            }
            super.onDraw(canvas);
        }
    }

    private static final class PlanPalette {
        final int light;
        final int mid;
        final int deep;
        final int dark;
        final int onAccent;
        final int textSecondary;
        final int stroke;
        final int divider;

        private PlanPalette(int light, int mid, int deep, int dark, int onAccent, int textSecondary) {
            this.light = light;
            this.mid = mid;
            this.deep = deep;
            this.dark = dark;
            this.onAccent = onAccent;
            this.textSecondary = textSecondary;
            this.stroke = ColorUtils.setAlphaComponent(mid, 0x44);
            this.divider = ColorUtils.setAlphaComponent(mid, 0x28);
        }

        static PlanPalette forPlan(int plan) {
            int p = plan;
            if (p < PLAN_PREMIUM || p > PLAN_ULTRA) {
                p = PLAN_ULTRA;
            }
            int mid = MglaPremiumController.colorMid(p);
            int secondary = ColorUtils.setAlphaComponent(MglaPremiumController.colorLight(p), 0xB3);
            return new PlanPalette(
                    MglaPremiumController.colorLight(p),
                    mid,
                    MglaPremiumController.colorDeep(p),
                    MglaPremiumController.colorDark(p),
                    MglaPremiumController.colorOnAccent(p),
                    secondary
            );
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
