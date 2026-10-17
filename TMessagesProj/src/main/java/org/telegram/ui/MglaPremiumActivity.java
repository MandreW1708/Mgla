package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FillLastLinearLayoutManager;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumButtonView;
import org.telegram.ui.Components.Premium.StarParticlesView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.HintView2;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Экран подписки Mgla Premium — чёрно-золотое оформление,
 * три плана: Premium / Premium+ / Premium Ultra.
 */
public class MglaPremiumActivity extends MglaBaseSettingsActivity {

    public static final int PLAN_PREMIUM = 0;
    public static final int PLAN_PLUS = 1;
    public static final int PLAN_ULTRA = 2;

    public static final int FEATURE_ANIMATIONS = 0;
    public static final int FEATURE_TASKS = 1;
    public static final int FEATURE_LOCAL_MODELS = 2;
    public static final int FEATURE_MEMORY = 3;
    public static final int FEATURE_ICON_THEMES = 4;
    public static final int FEATURE_CLOUD_AI = 5;
    public static final int FEATURE_ULTRA_AI = 6;
    public static final int FEATURE_PRIORITY_BYPASS = 7;

    // Black & gold palette
    private static final int COLOR_BG_TOP = 0xFF0A0A0A;
    private static final int COLOR_BG_MID = 0xFF12100A;
    private static final int COLOR_BG_BOTTOM = 0xFF1A1408;
    private static final int COLOR_CARD = 0xFF161410;
    private static final int COLOR_CARD_STROKE = 0x33D4AF37;
    private static final int COLOR_GOLD_LIGHT = 0xFFF5D76E;
    private static final int COLOR_GOLD = 0xFFD4AF37;
    private static final int COLOR_GOLD_DEEP = 0xFFB8860B;
    private static final int COLOR_GOLD_DARK = 0xFF8A6914;
    private static final int COLOR_TEXT = 0xFFFFF6D8;
    private static final int COLOR_TEXT_SECONDARY = 0xB3E8D5A3;
    private static final int COLOR_DIVIDER = 0x22D4AF37;

    private final ArrayList<PlanData> plans = new ArrayList<>();
    private final ArrayList<PremiumPreviewFragment.PremiumFeatureData> premiumFeatures = new ArrayList<>();

    private int selectedPlanIndex = PLAN_PREMIUM;

    private FrameLayout contentView;
    private BackgroundView backgroundView;
    private StarParticlesView particlesView;
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

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldButtonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader iconShader;
    private Shader buttonShader;
    private final Matrix matrix = new Matrix();
    private LinearGradient bgGradient;

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
        plans.add(new PlanData(PLAN_PREMIUM, "Premium", 200));
        plans.add(new PlanData(PLAN_PLUS, "Premium+", 500));
        plans.add(new PlanData(PLAN_ULTRA, "Premium Ultra", 1500));

        iconShader = new LinearGradient(
                0, 0, 0, 100,
                new int[]{COLOR_GOLD_LIGHT, COLOR_GOLD, COLOR_GOLD_DEEP, COLOR_GOLD_DARK},
                new float[]{0f, 0.35f, 0.7f, 1f},
                Shader.TileMode.CLAMP
        );
        goldIconPaint.setShader(iconShader);

        goldStrokePaint.setStyle(Paint.Style.STROKE);
        goldStrokePaint.setStrokeWidth(dp(1));
        goldStrokePaint.setColor(ColorUtils.setAlphaComponent(COLOR_GOLD_LIGHT, 90));

        dummyCell = new PremiumFeatureCell(context);
        fillFeaturesForPlan(selectedPlanIndex);
        statusBarHeight = AndroidUtilities.statusBarHeight;

        contentView = new FrameLayout(context) {
            boolean listInterceptedTouch;

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                float listX = backgroundView.getX() + backgroundView.tierListView.getX();
                float listY = backgroundView.getY() + backgroundView.tierListView.getY();
                AndroidUtilities.rectTmp.set(listX, listY, listX + backgroundView.tierListView.getWidth(), listY + backgroundView.tierListView.getHeight());
                if (progressToFull < 1.0f && (AndroidUtilities.rectTmp.contains(ev.getX(), ev.getY()) || listInterceptedTouch) && !listView.scrollingByUser) {
                    ev.offsetLocation(-listX, -listY);
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        listInterceptedTouch = true;
                    } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                        listInterceptedTouch = false;
                    }
                    backgroundView.tierListView.dispatchTouchEvent(ev);
                    if (listInterceptedTouch) {
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
                bgGradient = new LinearGradient(0, 0, 0, h,
                        new int[]{COLOR_BG_TOP, COLOR_BG_MID, COLOR_BG_BOTTOM},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP);
                bgPaint.setShader(bgGradient);
                buttonShader = new LinearGradient(0, 0, w, 0,
                        new int[]{COLOR_GOLD_DARK, COLOR_GOLD, COLOR_GOLD_LIGHT, COLOR_GOLD},
                        new float[]{0f, 0.35f, 0.65f, 1f},
                        Shader.TileMode.CLAMP);
                goldButtonPaint.setShader(buttonShader);
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
                backgroundView.tierListView.setAlpha(alpha);
                particlesView.setAlpha(1f - totalProgress);
                particlesView.setTranslationY(-(particlesView.getMeasuredHeight() - backgroundView.logoContainer.getMeasuredWidth()) / 2f
                        + backgroundView.getY() + backgroundView.logoContainer.getY());

                float toX = dp(72) - backgroundView.titleView.getLeft();
                float f = totalProgress > 0.3f ? (totalProgress - 0.3f) / 0.7f : 0f;
                backgroundView.titleView.setTranslationX(toX * (1f - CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(1 - f)));

                invalidate();
                buttonContainer.invalidate();

                if (bgPaint.getShader() != null) {
                    canvas.drawRect(0, 0, getMeasuredWidth(), getMeasuredHeight(), bgPaint);
                } else {
                    canvas.drawColor(COLOR_BG_TOP);
                }

                // Soft gold ambient glow behind the logo area
                float glowCx = getMeasuredWidth() / 2f;
                float glowCy = backgroundView.getY() + backgroundView.logoContainer.getY() + backgroundView.logoContainer.getMeasuredHeight() / 2f;
                float glowR = Math.max(dp(140), backgroundView.logoContainer.getMeasuredWidth() * 0.85f);
                backgroundView.updateGlow(glowCx, glowCy, glowR, alpha);
                canvas.drawCircle(glowCx, glowCy, glowR, backgroundView.glowPaint);

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
        particlesView = new StarParticlesView(context);
        particlesView.setClipWithGradient();
        particlesView.drawable.colorKey = Theme.key_starsGradient1;
        particlesView.drawable.size1 = 14;
        particlesView.drawable.useRotate = true;
        particlesView.drawable.useBlur = true;
        particlesView.drawable.checkBounds = true;
        particlesView.drawable.updateColors();

        contentView.addView(particlesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        contentView.addView(backgroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 0, 0, -48));

        premiumButtonView = new PremiumButtonView(context, dp(14), false, getResourceProvider()) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                AndroidUtilities.rectTmp.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                if (buttonShader != null) {
                    matrix.reset();
                    matrix.setTranslate(-getMeasuredWidth() * 0.15f * progress, 0);
                    buttonShader.setLocalMatrix(matrix);
                    goldButtonPaint.setShader(buttonShader);
                }
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(14), dp(14), goldButtonPaint);

                // Inner highlight
                Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
                highlight.setShader(new LinearGradient(0, 0, 0, getMeasuredHeight(),
                        new int[]{0x55FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.55f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(14), dp(14), highlight);

                // Soft gold rim
                Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
                rim.setStyle(Paint.Style.STROKE);
                rim.setStrokeWidth(dp(1.2f));
                rim.setColor(ColorUtils.setAlphaComponent(COLOR_GOLD_LIGHT, 160));
                AndroidUtilities.rectTmp.inset(dp(0.6f), dp(0.6f));
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(13), dp(13), rim);

                // Draw children (text) without parent purple gradient
                drawGradient = false;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    drawChild(canvas, child, getDrawingTime());
                }
            }
        };
        premiumButtonView.buttonTextView.setTextColor(0xFF1A1200);
        buttonContainer = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
                fade.setShader(new LinearGradient(0, 0, 0, getMeasuredHeight(),
                        new int[]{0x000A0A0A, 0xE60A0A0A, 0xFF0A0A0A},
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

        if (plan >= PLAN_PLUS) {
            premiumFeatures.add(new PremiumPreviewFragment.PremiumFeatureData(
                    FEATURE_CLOUD_AI, R.drawable.msg_premium_translate,
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
                    "Приоритет и автообход",
                    "Приоритетная скорость и подключение автообхода"
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

    @SuppressLint("NotifyDataSetChanged")
    private void selectPlan(int index, boolean animated) {
        if (index < 0 || index >= plans.size() || selectedPlanIndex == index) {
            return;
        }
        selectedPlanIndex = index;
        fillFeaturesForPlan(selectedPlanIndex);
        updateRows();
        updateButtonText(animated);
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (backgroundView != null && backgroundView.tierListView.getAdapter() != null) {
            backgroundView.tierListView.getAdapter().notifyDataSetChanged();
        }
        if (contentView != null) {
            contentView.requestLayout();
        }
    }

    private void updateButtonText(boolean animated) {
        if (premiumButtonView == null || selectedPlanIndex < 0 || selectedPlanIndex >= plans.size()) {
            return;
        }
        PlanData plan = plans.get(selectedPlanIndex);
        String text = "Подписаться за " + plan.priceRub + " ₽/мес";
        premiumButtonView.setButton(text, v -> {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.contact_check, "Оплата Mgla Premium скоро появится")
                    .show();
        }, animated);
    }

    private void updateColors() {
        if (backgroundView == null || actionBar == null) {
            return;
        }
        actionBar.setItemsColor(COLOR_GOLD_LIGHT, true);
        actionBar.setItemsColor(COLOR_GOLD_LIGHT, false);
        actionBar.setItemsBackgroundColor(ColorUtils.setAlphaComponent(COLOR_GOLD, 40), false);
        particlesView.drawable.colorKey = Theme.key_starsGradient1;
        particlesView.drawable.updateColors();
        backgroundView.titleView.setTextColor(COLOR_GOLD_LIGHT);
        backgroundView.subtitleView.setTextColor(COLOR_TEXT_SECONDARY);
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
                        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

                        {
                            cardPaint.setColor(COLOR_CARD);
                            dividerPaint.setColor(COLOR_DIVIDER);
                        }

                        @Override
                        protected void dispatchDraw(Canvas canvas) {
                            boolean first = getParent() instanceof RecyclerView
                                    && ((RecyclerView) getParent()).getChildAdapterPosition(this) == featuresStartRow;
                            boolean last = getParent() instanceof RecyclerView
                                    && ((RecyclerView) getParent()).getChildAdapterPosition(this) == featuresEndRow - 1;
                            float topR = first ? dp(18) : 0;
                            float bottomR = last ? dp(18) : 0;
                            float[] radii = new float[]{topR, topR, topR, topR, bottomR, bottomR, bottomR, bottomR};
                            Path path = new Path();
                            AndroidUtilities.rectTmp.set(dp(12), 0, getWidth() - dp(12), getHeight());
                            path.addRoundRect(AndroidUtilities.rectTmp, radii, Path.Direction.CW);
                            canvas.drawPath(path, cardPaint);

                            if (first || last) {
                                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                                stroke.setStyle(Paint.Style.STROKE);
                                stroke.setStrokeWidth(dp(1));
                                stroke.setColor(COLOR_CARD_STROKE);
                                canvas.drawPath(path, stroke);
                            }

                            final float r = dp(10);
                            AndroidUtilities.rectTmp.set(imageView.getLeft(), imageView.getTop(), imageView.getRight(), imageView.getBottom());
                            matrix.reset();
                            matrix.postScale(1f, totalGradientHeight / 100f, 0, 0);
                            matrix.postTranslate(0, -data.yOffset);
                            if (iconShader instanceof LinearGradient) {
                                iconShader.setLocalMatrix(matrix);
                            }
                            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, goldIconPaint);
                            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, goldStrokePaint);

                            super.dispatchDraw(canvas);

                            if (drawDivider) {
                                canvas.drawRect(dp(74), getMeasuredHeight() - 1, getMeasuredWidth() - dp(24), getMeasuredHeight(), dividerPaint);
                            }
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
                cell.setData(premiumFeatures.get(position - featuresStartRow), position != featuresEndRow - 1);
                cell.nextIcon.setVisibility(View.GONE);
                cell.setBackgroundColor(Color.TRANSPARENT);
                cell.title.setTextColor(COLOR_TEXT);
                cell.description.setTextColor(COLOR_TEXT_SECONDARY);
            } else if (position == statusRow) {
                TextInfoPrivacyCell privacyCell = (TextInfoPrivacyCell) holder.itemView;
                privacyCell.setTextColor(COLOR_TEXT_SECONDARY);
                privacyCell.setFixedSize(0);
                privacyCell.setBackground(null);
                PlanData plan = plans.get(selectedPlanIndex);
                String hint;
                if (plan.id == PLAN_PREMIUM) {
                    hint = "Premium открывает анимации, задачи, локальные модели, живую память и темы иконок.";
                } else if (plan.id == PLAN_PLUS) {
                    hint = "Premium+ включает всё из Premium и добавляет умные облачные ИИ-модели.";
                } else {
                    hint = "Premium Ultra включает всё из Premium и Premium+, плюс продвинутые модели, приоритет и автообход.";
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
            return false;
        }
    }

    private class BackgroundView extends LinearLayout {

        final TextView titleView;
        final TextView subtitleView;
        final FrameLayout logoContainer;
        final ImageView logoView;
        final RecyclerListView tierListView;
        final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastGlowCx, lastGlowCy, lastGlowR, lastGlowAlpha = -1;

        void updateGlow(float cx, float cy, float r, float alpha) {
            if (Math.abs(cx - lastGlowCx) < 0.5f && Math.abs(cy - lastGlowCy) < 0.5f
                    && Math.abs(r - lastGlowR) < 0.5f && Math.abs(alpha - lastGlowAlpha) < 0.02f) {
                return;
            }
            lastGlowCx = cx;
            lastGlowCy = cy;
            lastGlowR = r;
            lastGlowAlpha = alpha;
            glowPaint.setShader(new RadialGradient(cx, cy, Math.max(1f, r),
                    new int[]{ColorUtils.setAlphaComponent(COLOR_GOLD, (int) (55 * alpha)), Color.TRANSPARENT},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP));
        }

        public BackgroundView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setClipChildren(false);

            logoContainer = new FrameLayout(context) {
                private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Path clipPath = new Path();

                {
                    ringPaint.setStyle(Paint.Style.STROKE);
                    ringPaint.setStrokeWidth(dp(1.5f));
                }

                @Override
                protected void dispatchDraw(@NonNull Canvas canvas) {
                    float cx = getWidth() / 2f;
                    float cy = getHeight() / 2f;
                    float r = Math.min(getWidth(), getHeight()) * 0.46f;

                    clipPath.rewind();
                    clipPath.addCircle(cx, cy, r - dp(2), Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(clipPath);
                    super.dispatchDraw(canvas);
                    canvas.restore();

                    ringPaint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                            new int[]{COLOR_GOLD_LIGHT, COLOR_GOLD_DEEP, COLOR_GOLD_LIGHT},
                            new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                    canvas.drawCircle(cx, cy, r, ringPaint);
                }
            };
            logoContainer.setClipChildren(true);
            addView(logoContainer, LayoutHelper.createLinear(176, 176, Gravity.CENTER_HORIZONTAL));

            logoView = new ImageView(context);
            logoView.setImageResource(R.drawable.settings_mgla_premium);
            logoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            logoContainer.addView(logoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER_HORIZONTAL);
            titleView.setText("Mgla Premium");
            titleView.setLetterSpacing(0.04f);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_HORIZONTAL, 16, 18, 16, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitleView.setLineSpacing(dp(2), 1f);
            subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
            subtitleView.setText("Эксклюзивные возможности Mgla. Выберите свой уровень.");
            addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 0));
            subtitleView.getLayoutParams().width = Math.min(
                    AndroidUtilities.displaySize.x - dp(42),
                    HintView2.cutInFancyHalf(subtitleView.getText(), subtitleView.getPaint())
            );

            tierListView = new RecyclerListView(context) {
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                final Path path = new Path();

                {
                    paint.setColor(COLOR_CARD);
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(dp(1));
                    stroke.setColor(COLOR_CARD_STROKE);
                }

                @Override
                public void dispatchDraw(Canvas c) {
                    path.rewind();
                    AndroidUtilities.rectTmp.set(dp(0.5f), dp(0.5f), getWidth() - dp(0.5f), getHeight() - dp(0.5f));
                    path.addRoundRect(AndroidUtilities.rectTmp, dp(18), dp(18), Path.Direction.CW);
                    c.drawPath(path, paint);
                    c.drawPath(path, stroke);
                    c.save();
                    c.clipPath(path);
                    super.dispatchDraw(c);
                    c.restore();
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent e) {
                    if (progressToFull >= 1.0f) {
                        return false;
                    }
                    return super.onInterceptTouchEvent(e);
                }

                @Override
                public boolean dispatchTouchEvent(MotionEvent e) {
                    if (progressToFull >= 1.0f) {
                        return false;
                    }
                    return super.dispatchTouchEvent(e);
                }
            };
            tierListView.setOverScrollMode(OVER_SCROLL_NEVER);
            tierListView.setLayoutManager(new LinearLayoutManager(context));
            tierListView.setAdapter(new RecyclerListView.SelectionAdapter() {
                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    return new RecyclerListView.Holder(new PlanTierCell(context));
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    PlanTierCell cell = (PlanTierCell) holder.itemView;
                    cell.bind(plans.get(position), position != getItemCount() - 1);
                    cell.setChecked(selectedPlanIndex == position, false);
                }

                @Override
                public boolean isEnabled(RecyclerView.ViewHolder holder) {
                    return true;
                }

                @Override
                public int getItemCount() {
                    return plans.size();
                }
            });
            tierListView.setOnItemClickListener((view, position) -> {
                if (!(view instanceof PlanTierCell)) {
                    return;
                }
                PlanTierCell tierCell = (PlanTierCell) view;
                selectPlan(position, true);
                tierCell.setChecked(true, true);
                for (int i = 0; i < tierListView.getChildCount(); i++) {
                    View ch = tierListView.getChildAt(i);
                    if (ch instanceof PlanTierCell && ch != tierCell) {
                        ((PlanTierCell) ch).setChecked(false, true);
                    }
                }
            });

            Path path = new Path();
            float[] radii = new float[8];
            tierListView.setSelectorTransformer(canvas -> {
                View child = tierListView.getPressedChildView();
                int position = child == null ? -1 : tierListView.getChildViewHolder(child).getAdapterPosition();
                path.rewind();
                Rect selectorRect = tierListView.getSelectorRect();
                AndroidUtilities.rectTmp.set(selectorRect.left, selectorRect.top, selectorRect.right, selectorRect.bottom);
                Arrays.fill(radii, 0);
                if (position == 0) {
                    Arrays.fill(radii, 0, 4, dp(16));
                }
                if (tierListView.getAdapter() != null && position == tierListView.getAdapter().getItemCount() - 1) {
                    Arrays.fill(radii, 4, 8, dp(16));
                }
                path.addRoundRect(AndroidUtilities.rectTmp, radii, Path.Direction.CW);
                canvas.clipPath(path);
            });

            setClipToPadding(false);
            addView(tierListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 18, 12, 4));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return true;
        }
    }

    private class PlanTierCell extends FrameLayout {
        private final CheckBox2 checkBox;
        private final TextView titleView;
        private final TextView priceView;
        private final TextView hintView;
        private final Paint goldCheckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean hasDivider;
        private boolean checked;
        PlanData plan;

        public PlanTierCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(4), dp(10), dp(4), dp(10));

            goldCheckPaint.setShader(new LinearGradient(0, 0, 0, dp(28),
                    new int[]{COLOR_GOLD_LIGHT, COLOR_GOLD_DEEP},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));

            checkBox = new CheckBox2(context, 24);
            checkBox.setDrawBackgroundAsArc(10);
            checkBox.setColor(Theme.key_radioBackground, Theme.key_radioBackground, Theme.key_windowBackgroundWhiteBlackText);
            checkBox.setCirclePaintProvider(obj -> goldCheckPaint);
            addView(checkBox, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(COLOR_TEXT);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine();
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 48, 2, 100, 0));

            hintView = new TextView(context);
            hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hintView.setTextColor(COLOR_TEXT_SECONDARY);
            hintView.setSingleLine();
            addView(hintView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 48, 0, 100, 2));

            priceView = new TextView(context);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            priceView.setTextColor(COLOR_GOLD);
            priceView.setTypeface(AndroidUtilities.bold());
            priceView.setSingleLine();
            priceView.setGravity(Gravity.RIGHT);
            addView(priceView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        }

        void bind(PlanData plan, boolean hasDivider) {
            this.plan = plan;
            this.hasDivider = hasDivider;
            titleView.setText(plan.title);
            priceView.setText(plan.priceRub + " ₽/мес");
            if (plan.id == PLAN_PREMIUM) {
                hintView.setText("Базовый набор функций");
            } else if (plan.id == PLAN_PLUS) {
                hintView.setText("Всё из Premium + облачный ИИ");
            } else {
                hintView.setText("Максимум: ИИ, скорость и автообход");
            }
            updateCheckedStyle();
        }

        void setChecked(boolean checked, boolean animate) {
            this.checked = checked;
            checkBox.setChecked(checked, animate);
            updateCheckedStyle();
        }

        private void updateCheckedStyle() {
            titleView.setTextColor(checked ? COLOR_GOLD_LIGHT : COLOR_TEXT);
            priceView.setTextColor(checked ? COLOR_GOLD_LIGHT : COLOR_GOLD);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (checked) {
                Paint sel = new Paint(Paint.ANTI_ALIAS_FLAG);
                sel.setColor(ColorUtils.setAlphaComponent(COLOR_GOLD, 18));
                canvas.drawRect(0, 0, getWidth(), getHeight(), sel);
            }
            super.onDraw(canvas);
            if (hasDivider) {
                Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
                d.setColor(COLOR_DIVIDER);
                canvas.drawRect(dp(48), getMeasuredHeight() - 1, getMeasuredWidth() - dp(16), getMeasuredHeight(), d);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(66), MeasureSpec.EXACTLY));
        }
    }

    public static class PlanData {
        public final int id;
        public final String title;
        public final int priceRub;

        public PlanData(int id, String title, int priceRub) {
            this.id = id;
            this.title = title;
            this.priceRub = priceRub;
        }
    }
}
