package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MglaAiChatService;
import org.telegram.messenger.MglaAiChatStorage;
import org.telegram.messenger.MglaAiLimits;
import org.telegram.messenger.MglaLocalChatRuntime;
import org.telegram.messenger.MglaLocalModelsManager;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MenuDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaAiMarkdown;
import org.telegram.ui.Components.MglaGlobalMusicBar;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import java.util.ArrayList;

/** Persistent, streamed AI chat used by the AI item in the bottom island. */
public class MglaAiChatActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int MENU_OVERFLOW = 1;
    private static final int MENU_NEW = 2;
    private static final int MENU_SETTINGS = 3;
    private static final int MENU_CHATS = 4;
    private static final int MENU_QUOTA = 5;
    private static final int MENU_MODEL_RAM = 6;
    /** Extra lift above the IME so the glass composer isn't clipped by the keyboard. */
    private static final int KEYBOARD_EXTRA_GAP_DP = 14;

    private final boolean hasMainTabs;
    private SizeNotifierFrameLayout root;
    private ScrollView scroll;
    private LinearLayout messages;
    private EditText input;
    private TextView send;
    private TextView provider;
    private TextView attachmentBadge;
    private TextView attachButton;
    private FrameLayout composer;
    private FrameLayout lockedOverlay;
    private View drawerScrim;
    private FrameLayout drawer;
    private MenuDrawable menuDrawable;
    private ActionBarMenuItem overflowMenu;
    private ActionBarMenuItem quotaMenuItem;
    private ActionBarMenuItem modelRamMenuItem;
    private boolean emptyStateVisible;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;
    private int lastKeyboardHeight;
    private final ArrayList<String> attachedPhotos = new ArrayList<>();
    private MglaAiChatStorage.Chat chat;
    private MglaAiChatService.Request request;
    private TextView streamedBubble;
    private String streamedText = "";

    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private final @NonNull BlurredBackgroundDrawableViewFactory iBlur3FactoryLiquidGlass;
    private IBlur3Capture iBlur3Capture;
    private boolean iBlur3Invalidated;
    private BlurredBackgroundDrawable composerGlass;
    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final RectF iBlur3PositionActionBar = new RectF();
    private final RectF iBlur3PositionMainTabs = new RectF();
    private final RectF iBlur3PositionComposer = new RectF();

    public MglaAiChatActivity(Bundle args) {
        super(args);
        hasMainTabs = args != null && args.getBoolean("hasMainTabs");

        iBlur3SourceColor = new BlurredBackgroundSourceColor();
        iBlur3SourceColor.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlass);
            iBlur3FactoryLiquidGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        }
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
        iBlur3Positions.add(iBlur3PositionComposer);
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("ИИ");
        actionBar.setAllowOverlayTitle(true);
        if (hasMainTabs) {
            menuDrawable = new MenuDrawable();
            actionBar.setBackButtonDrawable(menuDrawable);
        } else {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }

        ActionBarMenu menu = actionBar.createMenu();
        // Order (left → right among action items): Load/Unload → Ultra counter → ⋮
        modelRamMenuItem = menu.addItem(MENU_MODEL_RAM, "Загрузить модель");
        styleModelRamButton();
        modelRamMenuItem.setVisibility(View.GONE);
        quotaMenuItem = menu.addItem(MENU_QUOTA, "0/20");
        if (quotaMenuItem.getTextView() != null) {
            quotaMenuItem.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            quotaMenuItem.getTextView().setTypeface(AndroidUtilities.bold());
        }
        overflowMenu = menu.addItem(MENU_OVERFLOW, R.drawable.ic_ab_other);
        overflowMenu.addSubItem(MENU_CHATS, R.drawable.msg_folders, "Чаты");
        overflowMenu.addSubItem(MENU_NEW, R.drawable.msg_add, "Новый чат");
        // MENU_SETTINGS is added only for local GGUF — OpenRouter has no AI settings UI.
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (hasMainTabs) {
                        showHistory();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_MODEL_RAM) {
                    onModelRamMenuClick();
                } else if (id == MENU_QUOTA) {
                    if (MglaAiLimits.hasDailyQuotaUi()) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (getParentActivity() == null) {
                                return;
                            }
                            new AlertDialog.Builder(getParentActivity())
                                    .setTitle("Лимит Ultra ★")
                                    .setMessage("Сегодня: " + MglaAiLimits.usedToday() + " из "
                                            + MglaAiLimits.ULTRA_DAILY_REQUESTS
                                            + " запросов к моделям ★.\nМодели без звёздочки без дневного лимита.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        });
                    }
                } else if (id == MENU_CHATS) {
                    showHistory();
                } else if (id == MENU_NEW) {
                    newChat();
                } else if (id == MENU_SETTINGS) {
                    showSettings();
                }
            }
        });
        updateQuotaCounter();
        updateModelRamMenu();

        root = new SizeNotifierFrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                    final int width = getMeasuredWidth();
                    final int height = getMeasuredHeight();
                    if (iBlur3SourceGlassFrosted != null && !iBlur3SourceGlassFrosted.inRecording()) {
                        final Canvas c = iBlur3SourceGlassFrosted.beginRecording(width, height);
                        c.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                        if (SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                        }
                        iBlur3SourceGlassFrosted.endRecording();
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        final Canvas c = iBlur3SourceGlass.beginRecording(width, height);
                        c.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                        if (SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                        }
                        iBlur3SourceGlass.endRecording();
                    }
                    iBlur3Invalidated = false;
                }
                super.dispatchDraw(canvas);
            }

            @Override
            public void drawBlurRect(Canvas canvas, float y, Rect rectTmp, Paint blurScrimPaint, boolean top) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !SharedConfig.chatBlurEnabled() || iBlur3SourceGlassFrosted == null) {
                    canvas.drawRect(rectTmp, blurScrimPaint);
                    return;
                }
                canvas.save();
                canvas.translate(0, -y);
                iBlur3SourceGlassFrosted.draw(canvas, rectTmp.left, rectTmp.top + y, rectTmp.right, rectTmp.bottom + y);
                canvas.restore();
                final int oldScrimAlpha = blurScrimPaint.getAlpha();
                blurScrimPaint.setAlpha(ChatActivity.ACTION_BAR_BLUR_ALPHA);
                canvas.drawRect(rectTmp, blurScrimPaint);
                blurScrimPaint.setAlpha(oldScrimAlpha);
            }
        };
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        iBlur3FactoryLiquidGlass.setSourceRootView(new ViewPositionWatcher(root), root);
        iBlur3FactoryLiquidGlass.setGlassEngine(glassEngine);
        glassEngine.setRoot(root);

        scroll = new ScrollView(context) {
            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(l - oldl, t - oldt);
                    iBlur3Invalidated = true;
                    root.invalidate();
                }
            }
        };
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(12), AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(12), dp(12), dp(116));
        scroll.addView(messages, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        iBlur3Capture = new ViewGroupPartRenderer(scroll, root, (canvas, child, drawingTime) -> {
            int save = canvas.save();
            canvas.translate(child.getLeft() - scroll.getScrollX(), child.getTop() - scroll.getScrollY());
            child.draw(canvas);
            canvas.restoreToCount(save);
            return true;
        });

        createComposer(context);
        root.setDelegate((keyboardHeight, isWidthGreater) -> applyKeyboardInset(keyboardHeight));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            applyKeyboardInset(Math.max(ime.bottom, root.measureKeyboardHeight()));
            return insets;
        });
        keyboardLayoutListener = () -> {
            if (root == null) {
                return;
            }
            applyKeyboardInset(root.measureKeyboardHeight());
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);

        chat = restoreOrCreateChat();
        renderChat(false);
        applySubscriptionEntitlements();
        updateSettingsMenuVisibility();
        MglaGlobalMusicBar.addGlobalVisibilityListener(musicBarVisibilityListener);
        updateComposerInset(0);
        return fragmentView;
    }

    /** Restore the last open chat for this account, or start a blank one. */
    private MglaAiChatStorage.Chat restoreOrCreateChat() {
        String lastId = prefs().getString(currentChatKey(), null);
        if (!TextUtils.isEmpty(lastId)) {
            ArrayList<MglaAiChatStorage.Chat> chats = MglaAiChatStorage.getChats(currentAccount);
            for (int i = 0; i < chats.size(); i++) {
                MglaAiChatStorage.Chat c = chats.get(i);
                if (lastId.equals(c.id)) {
                    return c;
                }
            }
        }
        return MglaAiChatStorage.newChat(preferredProvider(), preferredModel());
    }

    private String currentChatKey() {
        return "ai_chat_current_" + currentAccount;
    }

    private void rememberCurrentChat() {
        if (chat != null && !TextUtils.isEmpty(chat.id)) {
            prefs().edit().putString(currentChatKey(), chat.id).apply();
        }
    }

    private String preferredProvider() {
        if (!MglaSubscription.canUseOpenRouter()) {
            return MglaAiChatService.PROVIDER_LOCAL;
        }
        String saved = prefs().getString("ai_chat_provider", MglaAiChatService.PROVIDER_OPENROUTER);
        if (MglaAiChatService.PROVIDER_LOCAL.equals(saved) && MglaSubscription.canUseLocalModels()) {
            return MglaAiChatService.PROVIDER_LOCAL;
        }
        return MglaAiChatService.PROVIDER_OPENROUTER;
    }

    private String preferredModel() {
        if (!MglaSubscription.canUseOpenRouter()) {
            return prefs().getString("ai_chat_model", MglaAiChatService.DEFAULT_MODEL);
        }
        return prefs().getString("ai_chat_model", MglaAiChatService.DEFAULT_MODEL);
    }

    private void applySubscriptionEntitlements() {
        if (root == null) {
            return;
        }
        final boolean locked = !MglaSubscription.canAccessAiSection();
        if (locked) {
            ensureLockedOverlay();
            if (composer != null) {
                composer.setVisibility(View.GONE);
            }
            if (scroll != null) {
                scroll.setVisibility(View.GONE);
            }
            lockedOverlay.setVisibility(View.VISIBLE);
            return;
        }
        if (lockedOverlay != null) {
            lockedOverlay.setVisibility(View.GONE);
        }
        if (composer != null) {
            composer.setVisibility(View.VISIBLE);
        }
        if (scroll != null) {
            scroll.setVisibility(View.VISIBLE);
        }
        if (chat != null) {
            if (!MglaSubscription.canUseOpenRouter()
                    && MglaAiChatService.PROVIDER_OPENROUTER.equals(chat.provider)) {
                chat.provider = MglaAiChatService.PROVIDER_LOCAL;
                prefs().edit().putString("ai_chat_provider", chat.provider).apply();
            }
            if (!MglaSubscription.canUseLocalModels()
                    && MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider)
                    && MglaSubscription.canUseOpenRouter()) {
                chat.provider = MglaAiChatService.PROVIDER_OPENROUTER;
                prefs().edit().putString("ai_chat_provider", chat.provider).apply();
            }
        }
        updateAttachVisibility();
        updateProvider();
        updateSettingsMenuVisibility();
        updateQuotaCounter();
    }

    private void ensureLockedOverlay() {
        if (lockedOverlay != null || root == null || getParentActivity() == null) {
            return;
        }
        Context c = getParentActivity();
        lockedOverlay = new FrameLayout(c);
        lockedOverlay.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(24), dp(28), dp(24));

        TextView title = new TextView(c);
        title.setText("ИИ доступен с Mgla Premium");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView body = new TextView(c);
        body.setText("На Free раздел закрыт. Premium — локальные модели, Premium+ — облачные, Ultra — медиа и всё остальное.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(12), 0, dp(20));
        box.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView cta = new TextView(c);
        cta.setText("Открыть Mgla Premium");
        cta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        cta.setGravity(Gravity.CENTER);
        cta.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        cta.setBackground(round(Theme.getColor(Theme.key_featuredStickers_addButton), 18));
        cta.setPadding(dp(18), dp(12), dp(18), dp(12));
        cta.setOnClickListener(v -> presentFragment(new MglaPremiumActivity()));
        box.addView(cta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        lockedOverlay.addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        root.addView(lockedOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private void updateAttachVisibility() {
        if (attachButton == null) {
            return;
        }
        boolean show = chat != null
                && MglaAiChatService.PROVIDER_OPENROUTER.equals(chat.provider)
                && MglaSubscription.canAttachMedia();
        attachButton.setVisibility(show ? View.VISIBLE : View.GONE);
        if (input != null) {
            FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) input.getLayoutParams();
            if (ilp != null) {
                int left = show ? dp(36) : 0;
                int right = dp(34 + 6);
                if (ilp.leftMargin != left || ilp.rightMargin != right) {
                    ilp.leftMargin = left;
                    ilp.rightMargin = right;
                    input.setLayoutParams(ilp);
                }
            }
        }
        if (!show) {
            attachedPhotos.clear();
            updateAttachmentBadge();
        }
    }

    private void updateQuotaCounter() {
        if (quotaMenuItem == null) {
            return;
        }
        if (!MglaAiLimits.hasDailyQuotaUi()) {
            quotaMenuItem.setVisibility(View.GONE);
            return;
        }
        quotaMenuItem.setVisibility(View.VISIBLE);
        quotaMenuItem.setText(MglaAiLimits.counterLabel());
    }

    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        // Persist the open chat; never auto-unload the local model — only the user button does.
        if (chat != null && !chat.messages.isEmpty()) {
            MglaAiChatStorage.save(currentAccount, chat);
            rememberCurrentChat();
        }
        // Refresh the main chats-list hint so it appears as soon as the user leaves AI.
        MglaLocalChatRuntime.notifyRamChanged();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        applySubscriptionEntitlements();
        updateModelRamMenu();
        // Keep the same chat when returning to the AI tab (do not open a new one).
        if (chat == null && fragmentView != null) {
            chat = restoreOrCreateChat();
            renderChat(false);
        }
    }

    private void createComposer(Context c) {
        composer = new FrameLayout(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        // Slimmer horizontal padding so the glass spans closer to the music island length.
        box.setPadding(dp(8), dp(6), dp(8), dp(6));

        composerGlass = iBlur3FactoryLiquidGlass.create(composer, BlurredBackgroundProviderImpl.inputFieldShareAlert(resourceProvider));
        composerGlass.setRadius(dp(22));
        composerGlass.setPadding(dp(6));
        composer.setBackground(composerGlass);
        composer.setPadding(dp(6), dp(6), dp(6), dp(6));
        composer.setClipToPadding(false);
        composer.setClipChildren(false);

        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
        if (ColorUtils.calculateLuminance(accent) > 0.55f && ColorUtils.calculateLuminance(onAccent) > 0.55f) {
            onAccent = 0xFF17212B;
        } else if (ColorUtils.calculateLuminance(accent) < 0.35f && ColorUtils.calculateLuminance(onAccent) < 0.35f) {
            onAccent = Color.WHITE;
        }

        provider = new TextView(c);
        provider.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        provider.setTextColor(onAccent);
        provider.setGravity(Gravity.CENTER_VERTICAL);
        provider.setPadding(dp(12), 0, dp(12), 0);
        provider.setBackground(round(accent, 16));
        provider.setOnClickListener(v -> {
            if (chat != null && MglaAiChatService.PROVIDER_OPENROUTER.equals(chat.provider)) {
                chooseOpenRouterModel();
            } else {
                chooseProvider();
            }
        });
        provider.setOnLongClickListener(v -> {
            chooseProvider();
            return true;
        });
        box.addView(provider, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.LEFT, 0, 0, 0, 6));

        attachmentBadge = new TextView(c);
        attachmentBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        attachmentBadge.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        attachmentBadge.setPadding(dp(10), 0, dp(10), dp(5));
        attachmentBadge.setVisibility(View.GONE);
        box.addView(attachmentBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        // FrameLayout: send stays bottom-right; EditText grows upward above it.
        final int sendSize = 34;
        final int attachW = 36;
        FrameLayout row = new FrameLayout(c);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        attachButton = new TextView(c);
        attachButton.setText("📎");
        attachButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        attachButton.setGravity(Gravity.CENTER);
        attachButton.setTextColor(Theme.getColor(Theme.key_chat_messagePanelIcons));
        attachButton.setOnClickListener(v -> openPhotoPicker());
        row.addView(attachButton, LayoutHelper.createFrame(attachW, sendSize, Gravity.LEFT | Gravity.BOTTOM));

        input = new EditText(c);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHint("Сообщение");
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMaxLines(5);
        input.setMinHeight(dp(sendSize));
        input.setSingleLine(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(4), dp(6), dp(4), dp(6));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        row.addView(input, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.BOTTOM,
                attachW, 0, sendSize + 6, 0
        ));

        send = new TextView(c);
        send.setText("➤");
        send.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        send.setGravity(Gravity.CENTER);
        send.setIncludeFontPadding(false);
        send.setTextColor(onAccent);
        send.setBackground(round(accent, sendSize / 2f));
        send.setOnClickListener(v -> {
            if (request == null) {
                sendMessage();
            } else {
                stopRequest();
            }
        });
        row.addView(send, LayoutHelper.createFrame(sendSize, sendSize, Gravity.RIGHT | Gravity.BOTTOM));
        box.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        composer.addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        int side = dp(DialogsActivity.MAIN_TABS_MARGIN);
        root.addView(composer, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                side, 0, side, idleBottomMargin()
        ));
        composer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> syncComposerWidthToMusicIsland());
        root.post(this::syncComposerWidthToMusicIsland);
    }

    /** Match composer glass width to the mini-player island when it is visible. */
    private void syncComposerWidthToMusicIsland() {
        if (composer == null || root == null) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) composer.getLayoutParams();
        if (lp == null) {
            return;
        }
        int parentW = root.getWidth();
        if (parentW <= 0) {
            parentW = AndroidUtilities.displaySize.x;
        }
        int target = MglaGlobalMusicBar.preferredComposerWidthPx(parentW);
        int side = Math.max(0, (parentW - target) / 2);
        if (lp.width != target || lp.leftMargin != side || lp.rightMargin != side) {
            lp.width = target;
            lp.leftMargin = side;
            lp.rightMargin = side;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            composer.setLayoutParams(lp);
        }
    }

    private int idleBottomMargin() {
        int tabs = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : dp(6);
        // Mini-player island sits above the tabs; lift the composer so it isn't covered.
        int music = hasMainTabs ? dp(MglaGlobalMusicBar.blurExclusionExtraDp()) : 0;
        if (music > 0) {
            music += dp(6);
        }
        return AndroidUtilities.navigationBarHeight + tabs + music;
    }

    private void applyKeyboardInset(int keyboardHeight) {
        if (keyboardHeight < 0) {
            keyboardHeight = 0;
        }
        if (Math.abs(keyboardHeight - lastKeyboardHeight) < dp(2) && composer != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) composer.getLayoutParams();
            int expected = keyboardBottomMargin(keyboardHeight);
            if (lp != null && lp.bottomMargin == expected) {
                return;
            }
        }
        lastKeyboardHeight = keyboardHeight;
        updateComposerInset(keyboardHeight);
    }

    private int keyboardBottomMargin(int keyboardHeight) {
        if (keyboardHeight > dp(20)) {
            return keyboardHeight + dp(KEYBOARD_EXTRA_GAP_DP);
        }
        return idleBottomMargin();
    }

    private void updateComposerInset(int keyboardHeight) {
        if (composer == null) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) composer.getLayoutParams();
        if (lp == null) {
            return;
        }
        boolean keyboardVisible = keyboardHeight > dp(20);
        int bottom = keyboardBottomMargin(keyboardHeight);
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom;
            composer.setLayoutParams(lp);
        }
        syncComposerWidthToMusicIsland();
        int composerH = composer.getHeight();
        if (composerH <= 0) {
            composerH = dp(72);
        }
        messages.setPadding(
                dp(12),
                AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(12),
                dp(12),
                composerH + bottom + dp(12)
        );
        iBlur3Invalidated = true;
        if (root != null) {
            root.invalidate();
        }
        if (keyboardVisible) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null || fragmentView == null) {
            return;
        }
        final int additionalList = dp(48);
        final int mainTabBottom = fragmentView.getMeasuredHeight() - AndroidUtilities.navigationBarHeight - dp(DialogsActivity.MAIN_TABS_MARGIN);
        final int mainTabTop = mainTabBottom - dp(DialogsActivity.MAIN_TABS_HEIGHT)
                - dp(MglaGlobalMusicBar.blurExclusionExtraDp());

        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight + additionalList);
        if (hasMainTabs) {
            iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
            iBlur3PositionMainTabs.inset(0, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : -dp(48));
        } else {
            iBlur3PositionMainTabs.setEmpty();
        }
        if (composer != null && composer.getWidth() > 0) {
            float left = composer.getLeft();
            float top = composer.getTop();
            iBlur3PositionComposer.set(left, top, left + composer.getWidth(), top + composer.getHeight());
        } else {
            iBlur3PositionComposer.setEmpty();
        }

        int count = 1;
        if (hasMainTabs) {
            count++;
        }
        if (!iBlur3PositionComposer.isEmpty()) {
            count++;
        }
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, count);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }

    private void renderChat(boolean animate) {
        messages.removeAllViews();
        streamedBubble = null;
        streamedText = "";
        emptyStateVisible = false;
        updateProvider();
        updateSettingsMenuVisibility();
        updateModelRamMenu();
        if (chat.messages.isEmpty()) {
            showEmptyState();
        } else {
            for (MglaAiChatStorage.Message m : chat.messages) {
                addBubble(m.role, m.text, animate);
            }
        }
        iBlur3Invalidated = true;
        if (root != null) {
            root.invalidate();
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showEmptyState() {
        emptyStateVisible = true;
        boolean local = chat != null && MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider);
        TextView title = text("Чем могу помочь?", 26, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        // Sit higher under the action bar (was dp(38)).
        title.setPadding(dp(16), dp(8), dp(16), dp(8));
        messages.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (local) {
            TextView warn = text(
                    "Локальный ИИ работает на процессоре телефона: это ресурсоёмко, ответы могут быть медленными, "
                            + "сбои и обрывы возможны. Перед чатом нажмите «Загрузить» в шапке — "
                            + "после использования лучше «Выгрузить», чтобы освободить память.",
                    14, Theme.key_windowBackgroundWhiteGrayText);
            warn.setGravity(Gravity.CENTER);
            warn.setPadding(dp(20), dp(4), dp(20), dp(8));
            warn.setLineSpacing(dp(2), 1f);
            messages.addView(warn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            TextView hint = text("Новый диалог начинается с чистого контекста.\nИстория хранится только на устройстве.", 15, Theme.key_windowBackgroundWhiteGrayText);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(16), dp(4), dp(16), 0);
            messages.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void clearEmptyStateIfNeeded() {
        if (!emptyStateVisible || messages == null) {
            return;
        }
        emptyStateVisible = false;
        messages.removeAllViews();
    }

    private void addBubble(String role, String value, boolean animate) {
        boolean mine = "user".equals(role);
        int bubbleColor = Theme.getColor(mine ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhite);
        TextView b = text("", 16, Theme.key_windowBackgroundWhiteBlackText);
        b.setTextColor(ColorUtils.calculateLuminance(bubbleColor) < .42f ? Color.WHITE : 0xFF17212B);
        b.setLineSpacing(dp(2), 1);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(round(bubbleColor, mine ? 22 : 18));
        // Assistant replies: full Markdown. User bubbles stay plain text.
        if (mine) {
            b.setText(value);
        } else {
            applyAssistantMarkdown(b, value);
        }
        messages.addView(b, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                mine ? Gravity.RIGHT : Gravity.LEFT,
                mine ? dp(40) : 0, 0, mine ? 0 : dp(40), dp(10)
        ));
        if (animate) {
            b.setAlpha(0);
            b.setTranslationY(dp(10));
            b.animate().alpha(1).translationY(0).setDuration(180).start();
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void applyAssistantMarkdown(TextView bubble, String markdown) {
        if (bubble == null) {
            return;
        }
        MglaAiMarkdown.setMarkdown(bubble, markdown != null ? markdown : "", true);
        // Re-apply bubble text color after Markwon theme spans (code/quote keep their own).
        int bubbleColor = Theme.getColor(Theme.key_windowBackgroundWhite);
        if (ColorUtils.calculateLuminance(bubbleColor) < .42f) {
            bubble.setTextColor(Color.WHITE);
        }
    }

    private void openPhotoPicker() {
        if (!MglaSubscription.canAttachMedia()) {
            if (getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Вложения")
                        .setMessage("Прикрепление медиа доступно только на Premium Ultra.")
                        .setPositiveButton("Mgla Premium", (d, w) -> presentFragment(new MglaPremiumActivity()))
                        .setNegativeButton("Закрыть", null)
                        .show();
            }
            return;
        }
        PhotoAlbumPickerActivity picker = new PhotoAlbumPickerActivity(PhotoAlbumPickerActivity.SELECT_TYPE_ALL, false, true, null);
        picker.setMaxSelectedPhotos(4, true);
        picker.setDelegate(new PhotoAlbumPickerActivity.PhotoAlbumPickerActivityDelegate() {
            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify, int scheduleDate) {
                attachedPhotos.clear();
                for (SendMessagesHelper.SendingMediaInfo photo : photos) {
                    String path = !TextUtils.isEmpty(photo.path) ? photo.path : photo.imagePath;
                    if (!TextUtils.isEmpty(path)) {
                        attachedPhotos.add(path);
                    }
                }
                updateAttachmentBadge();
            }

            @Override
            public void startPhotoSelectActivity() {
            }
        });
        presentFragment(picker);
    }

    private void updateAttachmentBadge() {
        if (attachmentBadge == null) {
            return;
        }
        attachmentBadge.setVisibility(attachedPhotos.isEmpty() ? View.GONE : View.VISIBLE);
        attachmentBadge.setText("📷  Выбрано фото: " + attachedPhotos.size());
    }

    private void sendMessage() {
        if (request != null) {
            return;
        }
        if (!MglaSubscription.canAccessAiSection()) {
            applySubscriptionEntitlements();
            return;
        }
        if (chat != null
                && MglaAiChatService.PROVIDER_OPENROUTER.equals(chat.provider)
                && !MglaAiLimits.canSendCloudRequest(chat.model)) {
            if (getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Лимит Ultra")
                        .setMessage("Исчерпан дневной лимит моделей ★: "
                                + MglaAiLimits.ULTRA_DAILY_REQUESTS
                                + " запросов в сутки. Модели без звёздочки доступны без лимита.")
                        .setPositiveButton("OK", null)
                        .show();
            }
            updateQuotaCounter();
            return;
        }
        if (!MglaSubscription.canAttachMedia()) {
            attachedPhotos.clear();
            updateAttachmentBadge();
        }
        String value = input.getText().toString().trim();
        if (TextUtils.isEmpty(value) && attachedPhotos.isEmpty()) {
            return;
        }
        input.setText("");
        if (!attachedPhotos.isEmpty()) {
            value = (TextUtils.isEmpty(value) ? "" : value + "\n") + "📷 Прикреплено фото: " + attachedPhotos.size();
        }
        if (MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider)
                && !MglaLocalChatRuntime.isSelectedModelLoaded(getParentActivity() != null
                ? getParentActivity() : ApplicationLoader.applicationContext)) {
            if (getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Локальная модель")
                        .setMessage("Сначала загрузите модель в память кнопкой «Загрузить» в шапке чата.")
                        .setPositiveButton("Загрузить", (d, w) -> onModelRamMenuClick())
                        .setNegativeButton("Отмена", null)
                        .show();
            }
            return;
        }
        ArrayList<String> imagesForRequest = new ArrayList<>(attachedPhotos);
        clearEmptyStateIfNeeded();
        chat.messages.add(new MglaAiChatStorage.Message("user", value, System.currentTimeMillis()));
        addBubble("user", value, true);
        chat.messages.add(new MglaAiChatStorage.Message("assistant", "", System.currentTimeMillis()));
        addBubble("assistant", "Генерирую…", true);
        streamedBubble = (TextView) messages.getChildAt(messages.getChildCount() - 1);
        streamedText = "";
        send.setText("■");
        // OpenRouter has no user-facing AI settings — fixed defaults only.
        // Temperature / tokens prefs are local-GGUF settings.
        final boolean localProvider = MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider);
        if (localProvider) {
            MglaLocalChatRuntime.ensureLocalDefaults();
        }
        final float temperature = localProvider ? MglaLocalChatRuntime.temperature() : 0.7f;
        // OpenRouter output cap is per-model (Ultra table) inside MglaAiChatService.
        final int maxTokens = localProvider
                ? MglaLocalChatRuntime.maxTokens()
                : MglaAiLimits.budgetFor(chat.model).outputTokens;
        request = MglaAiChatService.send(
                chat.provider, chat.model, chat.messages, imagesForRequest,
                temperature,
                maxTokens,
                new MglaAiChatService.Callback() {
                    @Override
                    public void onStatus(String status) {
                        if (streamedBubble != null && streamedText.length() == 0 && !TextUtils.isEmpty(status)) {
                            streamedBubble.setText(status);
                        }
                    }

                    @Override
                    public void onDelta(String token) {
                        streamedText += token;
                        if (streamedBubble != null) {
                            // Plain while streaming so unfinished fences/lists don't glitch;
                            // full Markdown is applied in finishRequest().
                            streamedBubble.setText(streamedText);
                        }
                        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                    }

                    @Override
                    public void onComplete() {
                        finishRequest();
                    }

                    @Override
                    public void onError(String error) {
                        String msg = TextUtils.isEmpty(error) ? "Не удалось получить ответ" : error;
                        if (streamedText.length() == 0) {
                            streamedText = "Ошибка: " + msg;
                        } else if (!streamedText.contains(msg)) {
                            // Mid-stream failures used to leave a silent partial answer.
                            streamedText = streamedText + "\n\n⚠️ " + msg;
                        }
                        if (streamedBubble != null) {
                            streamedBubble.setText(streamedText);
                        }
                        finishRequest();
                    }
                }
        );
        attachedPhotos.clear();
        updateAttachmentBadge();
    }

    private void finishRequest() {
        if (!chat.messages.isEmpty()) {
            chat.messages.get(chat.messages.size() - 1).text = streamedText;
        }
        if (streamedBubble != null && !TextUtils.isEmpty(streamedText)
                && !streamedText.startsWith("Ошибка:")) {
            applyAssistantMarkdown(streamedBubble, streamedText);
        } else if (streamedBubble != null) {
            streamedBubble.setText(streamedText);
        }
        request = null;
        if (send != null) {
            send.setText("➤");
        }
        MglaAiChatStorage.save(currentAccount, chat);
        rememberCurrentChat();
        updateQuotaCounter();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void stopRequest() {
        // Only abort native generation when a request is actually running.
        // Calling abort with no request left a sticky flag and broke the next reply
        // after leaving/returning to the AI tab («модель выгружается»).
        if (request != null) {
            request.cancel();
            request = null;
        }
        // Finalize partial answer with Markdown if the user aborted mid-stream.
        if (streamedBubble != null && !TextUtils.isEmpty(streamedText)
                && !streamedText.startsWith("Ошибка:")) {
            if (chat != null && !chat.messages.isEmpty()) {
                chat.messages.get(chat.messages.size() - 1).text = streamedText;
            }
            applyAssistantMarkdown(streamedBubble, streamedText);
            MglaAiChatStorage.save(currentAccount, chat);
            rememberCurrentChat();
        }
        if (send != null) {
            send.setText("➤");
        }
    }

    private void newChat() {
        stopRequest();
        if (chat != null && !chat.messages.isEmpty()) {
            MglaAiChatStorage.save(currentAccount, chat);
        }
        chat = MglaAiChatStorage.newChat(preferredProvider(), preferredModel());
        rememberCurrentChat();
        renderChat(true);
        applySubscriptionEntitlements();
    }

    private void chooseProvider() {
        if (getParentActivity() == null || chat == null) {
            return;
        }
        final boolean openRouter = MglaSubscription.canUseOpenRouter();
        final boolean local = MglaSubscription.canUseLocalModels();
        if (!openRouter && local) {
            chat.provider = MglaAiChatService.PROVIDER_LOCAL;
            MglaLocalChatRuntime.ensureLocalDefaults();
            prefs().edit().putString("ai_chat_provider", chat.provider).apply();
            MglaAiChatStorage.save(currentAccount, chat);
            updateAttachVisibility();
            updateProvider();
            updateSettingsMenuVisibility();
            if (chat.messages.isEmpty()) {
                renderChat(false);
            }
            return;
        }
        if (openRouter && !local) {
            chooseOpenRouterModel();
            return;
        }
        if (!openRouter) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle("Провайдер")
                    .setMessage("Облачные модели доступны с Premium+. Локальные — с Premium.")
                    .setPositiveButton("Mgla Premium", (d, w) -> presentFragment(new MglaPremiumActivity()))
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Провайдер")
                .setItems(new String[]{"OpenRouter", "Локальная GGUF"}, (d, n) -> {
                    chat.provider = n == 0 ? MglaAiChatService.PROVIDER_OPENROUTER : MglaAiChatService.PROVIDER_LOCAL;
                    if (n == 1) {
                        MglaLocalChatRuntime.ensureLocalDefaults();
                    }
                    prefs().edit().putString("ai_chat_provider", chat.provider).apply();
                    MglaAiChatStorage.save(currentAccount, chat);
                    updateAttachVisibility();
                    updateProvider();
                    updateSettingsMenuVisibility();
                    if (chat.messages.isEmpty()) {
                        renderChat(false);
                    }
                    if (n == 0) {
                        chooseOpenRouterModel();
                    }
                })
                .show();
    }

    private boolean modelPickerOpen;
    private String selectedModelStatus;
    private final MglaGlobalMusicBar.VisibilityListener musicBarVisibilityListener = visible -> {
        if (composer != null) {
            updateComposerInset(lastKeyboardHeight);
            syncComposerWidthToMusicIsland();
        }
    };

    private void chooseOpenRouterModel() {
        if (getParentActivity() == null || chat == null || modelPickerOpen) {
            return;
        }
        if (!MglaSubscription.canUseOpenRouter()) {
            chooseProvider();
            return;
        }
        modelPickerOpen = true;
        ArrayList<MglaAiChatService.ModelInfo> cached = MglaAiChatService.peekCachedOpenRouterModels();
        final boolean hasCache = cached != null && !cached.isEmpty();
        final AlertDialog progress = hasCache ? null
                : new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        if (progress != null) {
            progress.setCanCancel(true);
            progress.setOnCancelListener(d -> modelPickerOpen = false);
            progress.show();
        }
        MglaAiChatService.fetchOpenRouterModels(new MglaAiChatService.ModelsCallback() {
            @Override
            public void onResult(ArrayList<MglaAiChatService.ModelInfo> models) {
                if (progress != null && progress.isShowing()) {
                    progress.dismiss();
                }
                if (getParentActivity() == null || models == null || models.isEmpty()) {
                    modelPickerOpen = false;
                    return;
                }
                showOpenRouterModelPicker(models);
            }

            @Override
            public void onError(String message) {
                if (progress != null && progress.isShowing()) {
                    progress.dismiss();
                }
                modelPickerOpen = false;
                if (getParentActivity() == null) {
                    return;
                }
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Модели OpenRouter")
                        .setMessage(TextUtils.isEmpty(message) ? "Не удалось загрузить список с сервера" : message)
                        .setPositiveButton("Повторить", (d, w) -> chooseOpenRouterModel())
                        .setNegativeButton("Провайдер…", (d, w) -> chooseProvider())
                        .show();
            }
        });
    }

    private void showOpenRouterModelPicker(ArrayList<MglaAiChatService.ModelInfo> models) {
        if (getParentActivity() == null || models == null || models.isEmpty()) {
            modelPickerOpen = false;
            return;
        }
        // Sync chip status for the currently selected model.
        if (chat != null) {
            selectedModelStatus = null;
            if (TextUtils.isEmpty(chat.model)) {
                selectedModelStatus = null;
            } else {
                for (MglaAiChatService.ModelInfo info : models) {
                    if (chat.model.equals(info.id)) {
                        selectedModelStatus = info.status;
                        break;
                    }
                }
            }
            updateProvider();
        }
        // Row 0 = Авто (hub picks a working free model).
        final int autoIndex = 0;
        final String[] labels = new String[models.size() + 1];
        final boolean autoSelected = chat != null && TextUtils.isEmpty(chat.model);
        labels[autoIndex] = "⚪  Авто" + (autoSelected ? "  ✓" : "");
        for (int i = 0; i < models.size(); i++) {
            MglaAiChatService.ModelInfo info = models.get(i);
            boolean current = chat != null && !TextUtils.isEmpty(chat.model) && chat.model.equals(info.id);
            labels[i + 1] = MglaAiChatService.statusDot(info.status)
                    + shortModel(info.id)
                    + (info.ultra ? "  ★" : "")
                    + (current ? "  ✓" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity())
                .setTitle("Модель OpenRouter")
                .setItems(labels, (d, which) -> {
                    if (which < 0 || which >= labels.length) {
                        return;
                    }
                    if (which == autoIndex) {
                        chat.model = MglaAiChatService.MODEL_AUTO;
                        selectedModelStatus = null;
                    } else {
                        MglaAiChatService.ModelInfo picked = models.get(which - 1);
                        chat.model = picked.id;
                        selectedModelStatus = picked.status;
                    }
                    chat.provider = MglaAiChatService.PROVIDER_OPENROUTER;
                    prefs().edit()
                            .putString("ai_chat_provider", chat.provider)
                            .putString("ai_chat_model", chat.model != null ? chat.model : "")
                            .apply();
                    MglaAiChatStorage.save(currentAccount, chat);
                    updateAttachVisibility();
                    updateProvider();
                    updateSettingsMenuVisibility();
                });
        if (MglaSubscription.canUseLocalModels()) {
            builder.setNegativeButton("Провайдер…", (d, w) -> chooseProvider());
        }
        AlertDialog dialog = builder.create();
        if (showDialog(dialog, d -> modelPickerOpen = false) == null) {
            modelPickerOpen = false;
            try {
                dialog.setOnDismissListener(d -> modelPickerOpen = false);
                dialog.show();
            } catch (Throwable ignore) {
                modelPickerOpen = false;
            }
        }
    }

    private void updateProvider() {
        if (provider == null || chat == null) {
            return;
        }
        updateModelRamMenu();
        if (MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider)) {
            MglaLocalChatRuntime.ensureLocalDefaults();
            provider.setText("⌁  Локальная модель");
            return;
        }
        if (TextUtils.isEmpty(chat.model)) {
            provider.setText("⚪  Авто ▾");
            return;
        }
        String status = TextUtils.isEmpty(selectedModelStatus) ? "⚪  "
                : MglaAiChatService.statusDot(selectedModelStatus);
        String star = MglaAiLimits.isUltraCatalogModel(chat.model) ? " ★" : "";
        provider.setText(status + shortModel(chat.model) + star + " ▾");
    }

    private void updateSettingsMenuVisibility() {
        if (overflowMenu == null) {
            return;
        }
        final boolean local = chat != null && MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider);
        if (local) {
            if (!overflowMenu.hasSubItem(MENU_SETTINGS)) {
                overflowMenu.addSubItem(MENU_SETTINGS, R.drawable.msg_settings, "Настройки локальной модели");
            } else {
                overflowMenu.setSubItemShown(MENU_SETTINGS, true);
            }
        } else {
            // OpenRouter: no AI settings entry at all.
            overflowMenu.setSubItemShown(MENU_SETTINGS, false);
        }
        updateModelRamMenu();
    }

    private void styleModelRamButton() {
        if (modelRamMenuItem == null) {
            return;
        }
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
        if (ColorUtils.calculateLuminance(accent) > 0.55f && ColorUtils.calculateLuminance(onAccent) > 0.55f) {
            onAccent = 0xFF17212B;
        } else if (ColorUtils.calculateLuminance(accent) < 0.35f && ColorUtils.calculateLuminance(onAccent) < 0.35f) {
            onAccent = Color.WHITE;
        }
        int pressed = ColorUtils.blendARGB(accent, Color.BLACK, 0.18f);
        TextView tv = modelRamMenuItem.getTextView();
        if (tv != null) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            tv.setTypeface(AndroidUtilities.bold());
            tv.setTextColor(onAccent);
            tv.setPadding(dp(14), dp(6), dp(14), dp(6));
            tv.setIncludeFontPadding(false);
            tv.setMinHeight(dp(28));
            tv.setGravity(Gravity.CENTER);
            tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), accent, pressed));
        }
        // Drop default action-bar ripples so the oval reads as a pill.
        modelRamMenuItem.setBackground(null);
        modelRamMenuItem.setPadding(dp(4), dp(8), dp(4), dp(8));
    }

    private void updateModelRamMenu() {
        if (modelRamMenuItem == null) {
            return;
        }
        final boolean local = chat != null
                && MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider)
                && MglaSubscription.canUseLocalModels();
        if (!local) {
            modelRamMenuItem.setVisibility(View.GONE);
            return;
        }
        modelRamMenuItem.setVisibility(View.VISIBLE);
        styleModelRamButton();
        if (MglaLocalChatRuntime.isUnloading()) {
            modelRamMenuItem.setText("Выгрузка…");
            modelRamMenuItem.setEnabled(false);
            return;
        }
        if (MglaLocalChatRuntime.isLoading()) {
            modelRamMenuItem.setText("Загрузка…");
            modelRamMenuItem.setEnabled(false);
            return;
        }
        modelRamMenuItem.setEnabled(true);
        Context ctx = getParentActivity() != null ? getParentActivity() : ApplicationLoader.applicationContext;
        if (MglaLocalChatRuntime.isSelectedModelLoaded(ctx)) {
            modelRamMenuItem.setText("Выгрузить модель");
        } else {
            modelRamMenuItem.setText("Загрузить модель");
        }
    }

    private void onModelRamMenuClick() {
        if (chat == null || !MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider)) {
            return;
        }
        Context ctx = getParentActivity() != null ? getParentActivity() : ApplicationLoader.applicationContext;
        if (MglaLocalChatRuntime.isBusy()) {
            return;
        }
        if (MglaLocalChatRuntime.isSelectedModelLoaded(ctx)) {
            // Cancel in-flight reply so native free isn't blocked behind complete().
            if (request != null) {
                request.cancel();
                request = null;
                send.setText("➤");
            }
            updateModelRamMenu();
            modelRamMenuItem.setText("Выгрузка…");
            modelRamMenuItem.setEnabled(false);
            MglaLocalChatRuntime.unloadAsync(() -> {
                updateModelRamMenu();
                if (getParentActivity() != null) {
                    BulletinFactory.of(MglaAiChatActivity.this)
                            .createSimpleBulletin(R.raw.info, "Модель выгружена из памяти")
                            .show();
                }
            });
            return;
        }
        if (MglaLocalModelsManager.getRunnableModel(ctx) == null) {
            if (getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Локальная модель")
                        .setMessage("Сначала скачайте и выберите GGUF в настройках локальной модели.")
                        .setPositiveButton("OK", null)
                        .show();
            }
            return;
        }
        updateModelRamMenu();
        MglaLocalChatRuntime.loadAsync(new MglaLocalChatRuntime.LoadCallback() {
            @Override
            public void onProgress(String status) {
                if (modelRamMenuItem != null) {
                    modelRamMenuItem.setText("Загрузка…");
                    modelRamMenuItem.setEnabled(false);
                }
            }

            @Override
            public void onReady() {
                updateModelRamMenu();
                if (getParentActivity() != null) {
                    BulletinFactory.of(MglaAiChatActivity.this)
                            .createSimpleBulletin(R.raw.contact_check, "Модель загружена в память")
                            .show();
                }
            }

            @Override
            public void onError(String message) {
                updateModelRamMenu();
                if (getParentActivity() != null) {
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Не удалось загрузить")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
        });
    }

    private String shortModel(String s) {
        if (TextUtils.isEmpty(s)) {
            return "Авто";
        }
        int i = s.lastIndexOf('/');
        return (i < 0 ? s : s.substring(i + 1)).replace(":free", "");
    }

    private void showHistory() {
        if (drawer != null) {
            closeDrawer();
            return;
        }
        Context c = getParentActivity();
        if (c == null || root == null) {
            return;
        }
        if (menuDrawable != null) {
            menuDrawable.setRotation(1f, true);
        }

        drawerScrim = new View(c);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setAlpha(0);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        root.addView(drawerScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        drawer = new FrameLayout(c);
        drawer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        drawer.setTranslationX(-dp(320));
        drawer.setElevation(dp(8));

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, AndroidUtilities.statusBarHeight + dp(8), 0, dp(16) + idleBottomMargin());

        TextView header = text("Чаты", 24, Theme.key_windowBackgroundWhiteBlackText);
        header.setTypeface(AndroidUtilities.bold());
        header.setPadding(dp(20), dp(8), dp(20), dp(12));
        list.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (MglaLocalChatRuntime.shouldShowRamWarning()) {
            list.addView(buildRamWarningBanner(c, list), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        View add = historyRow(c, "+  Новый чат", "Начать без предыдущего контекста", false);
        add.setOnClickListener(v -> {
            newChat();
            closeDrawer();
        });
        list.addView(add);

        ArrayList<MglaAiChatStorage.Chat> chats = MglaAiChatStorage.getChats(currentAccount);
        if (chats.isEmpty()) {
            TextView empty = text("Пока нет сохранённых диалогов", 14, Theme.key_windowBackgroundWhiteGrayText);
            empty.setPadding(dp(20), dp(16), dp(20), dp(8));
            list.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            for (MglaAiChatStorage.Chat saved : chats) {
                boolean selected = chat != null && saved.id.equals(chat.id);
                String subtitle = MglaAiChatService.PROVIDER_LOCAL.equals(saved.provider) ? "Локальная модель" : "OpenRouter";
                View row = historyRow(c, saved.title, subtitle, selected);
                row.setOnClickListener(v -> {
                    stopRequest();
                    if (chat != null && !chat.messages.isEmpty()) {
                        MglaAiChatStorage.save(currentAccount, chat);
                    }
                    chat = saved;
                    rememberCurrentChat();
                    renderChat(true);
                    closeDrawer();
                });
                row.setOnLongClickListener(v -> {
                    showChatOptions(saved);
                    return true;
                });
                list.addView(row);
            }
        }

        ScrollView s = new ScrollView(c);
        s.addView(list);
        drawer.addView(s, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(drawer, LayoutHelper.createFrame(Math.min(dp(310), AndroidUtilities.displaySize.x - dp(56)), LayoutHelper.MATCH_PARENT, Gravity.LEFT));
        drawerScrim.animate().alpha(1).setDuration(180).start();
        drawer.animate().translationX(0).setDuration(220).start();
    }

    /**
     * Persistent warning while a GGUF is mapped in RAM. Stays until the user swipes/dismisses
     * it or unloads the model (then it reappears on the next load).
     */
    private View buildRamWarningBanner(Context c, LinearLayout parentList) {
        final FrameLayout banner = new FrameLayout(c);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int bg = ColorUtils.setAlphaComponent(accent, 0x28);
        banner.setBackground(round(bg, 14));
        banner.setPadding(dp(12), dp(10), dp(8), dp(10));

        TextView body = new TextView(c);
        body.setText("Локальная модель всё ещё в памяти и занимает ОЗУ. Выгрузите её кнопкой в шапке, когда не нужна.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        body.setLineSpacing(dp(2), 1f);
        banner.addView(body, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, 28, 0));

        TextView close = new TextView(c);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        close.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        close.setPadding(dp(8), dp(4), dp(8), dp(4));
        close.setOnClickListener(v -> dismissRamBanner(banner, parentList));
        banner.addView(close, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP));

        final float[] downX = {0};
        banner.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getX();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX[0];
                    if (dx > 0) {
                        banner.setTranslationX(dx);
                        banner.setAlpha(Math.max(0.25f, 1f - dx / dp(160)));
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (banner.getTranslationX() > dp(72)) {
                        dismissRamBanner(banner, parentList);
                    } else {
                        banner.animate().translationX(0).alpha(1f).setDuration(160).start();
                    }
                    return true;
            }
            return false;
        });

        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(16), dp(4), dp(16), dp(10));
        wrap.addView(banner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return wrap;
    }

    private void dismissRamBanner(View banner, LinearLayout parentList) {
        MglaLocalChatRuntime.dismissRamWarning();
        banner.animate().translationX(dp(240)).alpha(0f).setDuration(180).withEndAction(() -> {
            View wrap = (View) banner.getParent();
            if (wrap != null && parentList != null) {
                parentList.removeView(wrap);
            }
        }).start();
    }

    private void showChatOptions(MglaAiChatStorage.Chat saved) {
        new AlertDialog.Builder(getParentActivity())
                .setTitle(saved.title)
                .setItems(new CharSequence[]{"Удалить", "Переименовать чат"}, (d, which) -> {
                    if (which == 0) {
                        MglaAiChatStorage.delete(currentAccount, saved.id);
                        if (chat != null && saved.id.equals(chat.id)) {
                            newChat();
                        }
                        closeDrawer();
                        showHistory();
                    } else if (which == 1) {
                        renameChat(saved);
                    }
                })
                .show();
    }

    private void renameChat(MglaAiChatStorage.Chat saved) {
        Context c = getParentActivity();
        if (c == null) {
            return;
        }
        EditText field = new EditText(c);
        field.setText(saved.title);
        field.setSelectAllOnFocus(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setPadding(dp(20), dp(12), dp(20), dp(12));
        field.setSingleLine(true);
        new AlertDialog.Builder(c)
                .setTitle("Переименовать чат")
                .setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String title = field.getText().toString().trim();
                    if (TextUtils.isEmpty(title)) {
                        return;
                    }
                    saved.title = title;
                    saved.customTitle = true;
                    MglaAiChatStorage.save(currentAccount, saved);
                    if (chat != null && saved.id.equals(chat.id)) {
                        chat.title = saved.title;
                        chat.customTitle = true;
                    }
                    closeDrawer();
                    showHistory();
                })
                .show();
        field.requestFocus();
        AndroidUtilities.showKeyboard(field);
    }

    private void closeDrawer() {
        if (drawer == null) {
            return;
        }
        if (menuDrawable != null) {
            menuDrawable.setRotation(0f, true);
        }
        View panel = drawer;
        View scrim = drawerScrim;
        panel.animate().translationX(-panel.getWidth()).setDuration(180).withEndAction(() -> {
            if (drawer != null) {
                root.removeView(drawer);
            }
            if (drawerScrim != null) {
                root.removeView(drawerScrim);
            }
            drawer = null;
            drawerScrim = null;
        }).start();
        if (scrim != null) {
            scrim.animate().alpha(0).setDuration(180).start();
        }
    }

    private void showSettings() {
        // OpenRouter: no settings screen. Local GGUF only.
        if (chat == null || !MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider)) {
            return;
        }
        Context c = getParentActivity();
        if (c == null) {
            return;
        }
        MglaLocalChatRuntime.ensureLocalDefaults();
        LinearLayout form = new LinearLayout(c);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(4), dp(24), dp(6));
        EditText temperature = settingField(c, form, "Температура (0 = точнее)", String.valueOf(MglaLocalChatRuntime.temperature()), String.valueOf(MglaLocalChatRuntime.DEFAULT_TEMPERATURE));
        EditText tokens = settingField(c, form, "Макс. токенов ответа", String.valueOf(MglaLocalChatRuntime.maxTokens()), String.valueOf(MglaLocalChatRuntime.DEFAULT_MAX_TOKENS));
        EditText context = settingField(c, form, "Контекст (256–2048)", String.valueOf(MglaLocalChatRuntime.contextTokens()), String.valueOf(MglaLocalChatRuntime.DEFAULT_CONTEXT));
        EditText threads = settingField(c, form, "Потоки CPU (1–2)", String.valueOf(MglaLocalChatRuntime.threads()), String.valueOf(MglaLocalChatRuntime.defaultThreads()));
        TextView note = text("Модель выгружается только вручную кнопкой в шапке чата.", 12, Theme.key_dialogTextGray2);
        note.setPadding(0, dp(12), 0, 0);
        form.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        new AlertDialog.Builder(c).setTitle("Настройки локальной модели").setView(form)
                .setNeutralButton("Сброс", (d, w) -> {
                    MglaLocalChatRuntime.resetLocalDefaults();
                    if (getParentActivity() != null) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Рекомендуемые настройки применены").show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
            try {
                prefs().edit()
                        .putFloat("ai_chat_temperature", Math.max(0, Math.min(1.0f, Float.parseFloat(temperature.getText().toString()))))
                        .putInt("ai_chat_max_tokens", Math.max(16, Math.min(256, Integer.parseInt(tokens.getText().toString()))))
                        .putInt("ai_local_context", Math.max(256, Math.min(2048, Integer.parseInt(context.getText().toString()))))
                        .putInt("ai_local_threads", Math.max(1, Math.min(2, Integer.parseInt(threads.getText().toString()))))
                        .apply();
            } catch (Throwable ignore) {
            }
        }).show();
    }

    private EditText settingField(Context c, LinearLayout form, String label, String value, String hint) {
        TextView title = text(label, 13, Theme.key_dialogTextGray2);
        title.setPadding(0, dp(10), 0, 0);
        form.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        EditText field = new EditText(c);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        form.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        return field;
    }

    private View historyRow(Context c, String title, String sub, boolean selected) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        if (selected) {
            row.setBackgroundColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 28));
        }
        TextView t = text(title, 16, Theme.key_windowBackgroundWhiteBlackText);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView s = text(sub, 13, Theme.key_windowBackgroundWhiteGrayText);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(s, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return row;
    }

    private TextView text(String s, int size, int colorKey) {
        TextView v = new TextView(getParentActivity());
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        v.setTextColor(Theme.getColor(colorKey));
        return v;
    }

    private GradientDrawable round(int c, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(c);
        d.setCornerRadius(dp(r));
        return d;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (drawer != null) {
            closeDrawer();
            return false;
        }
        if (request != null) {
            stopRequest();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onFragmentDestroy() {
        MglaGlobalMusicBar.removeGlobalVisibilityListener(musicBarVisibilityListener);
        if (root != null && keyboardLayoutListener != null) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
            keyboardLayoutListener = null;
        }
        closeDrawer();
        stopRequest();
        super.onFragmentDestroy();
    }
}
