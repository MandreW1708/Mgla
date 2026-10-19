package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaChatDna;
import org.telegram.messenger.MglaLocalModelsManager;
import org.telegram.messenger.MglaMemoryConfig;
import org.telegram.messenger.MglaMemoryExtractor;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class MglaAiSettingsActivity extends MglaBaseSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_AI_ENABLED = 0;
    private static final int ROW_SHADOW_1 = 1;
    private static final int ROW_FUNCTIONS_HEADER = 2;
    private static final int ROW_AI_SUMMARY = 3;
    private static final int ROW_AI_RETELL = 4;
    private static final int ROW_AI_EDITOR = 5;
    private static final int ROW_AI_EDITOR_LIMIT = 6;
    private static final int ROW_SHADOW_2 = 7;
    private static final int ROW_AI_TRANSCRIBE = 8;
    private static final int ROW_SHADOW_3 = 9;
    private static final int ROW_PROVIDER_HEADER = 10;
    private static final int ROW_PROVIDER_BASIC = 11;
    private static final int ROW_PROVIDER_GEMINI = 12;
    private static final int ROW_SHADOW_4 = 13;
    private static final int ROW_DNA_HEADER = 14;
    private static final int ROW_DNA_TOPICS = 15;
    private static final int ROW_DNA_MODELS = 16;
    private static final int ROW_SHADOW_5 = 17;
    private static final int ROW_MEMORY_HEADER = 18;
    private static final int ROW_MEMORY_ENABLED = 19;
    private static final int ROW_MEMORY_PROVIDER = 20;
    private static final int ROW_MEMORY_INTERVAL = 21;
    private static final int ROW_MEMORY_LIMIT = 22;
    private static final int ROW_COUNT = 23;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_SHADOW = 2;
    private static final int VIEW_TYPE_HEADER = 3;
    private static final int VIEW_TYPE_RADIO = 4;

    private SharedPreferences prefs;
    private RecyclerListView listView;

    public MglaAiSettingsActivity() {
        this(null);
    }

    public MglaAiSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaHeaderSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaSubscriptionHintChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaHeaderSettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaSubscriptionHintChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if ((id == NotificationCenter.mglaHeaderSettingsChanged
                || id == NotificationCenter.mglaSubscriptionHintChanged)
                && listView != null && listView.getAdapter() != null) {
            MglaMemoryConfig.clearIfUnauthorized();
            listView.getAdapter().notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Искусственный интеллект");
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

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setPadding(0, dp(8), 0, AndroidUtilities.navigationBarHeight);
        listView.setClipToPadding(false);
        listView.setSections();
        listView.setAdapter(new ListAdapter(context));
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final Paint dividerPaint = new Paint();

            {
                dividerPaint.setColor(Theme.getColor(Theme.key_divider));
            }

            @Override
            public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    int position = parent.getChildAdapterPosition(child);
                    if (position != ROW_AI_SUMMARY && position != ROW_AI_RETELL && position != ROW_AI_EDITOR
                            && position != ROW_PROVIDER_BASIC && position != ROW_DNA_TOPICS
                            && position != ROW_MEMORY_ENABLED && position != ROW_MEMORY_PROVIDER && position != ROW_MEMORY_INTERVAL) {
                        continue;
                    }
                    float bottom = child.getBottom() + child.getTranslationY();
                    c.drawRect(child.getLeft() + dp(21), bottom - dp(1), child.getRight() - dp(21), bottom, dividerPaint);
                }
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_AI_TRANSCRIBE) {
                if (!org.telegram.messenger.MglaFeatureFlags.isAllowed("ai_transcribe_enabled")) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                    return;
                }
                presentFragment(new MglaAiTranscribeActivity());
            } else if (position == ROW_DNA_TOPICS) {
                if (!org.telegram.messenger.MglaFeatureFlags.isAllowed("ai_chat_dna")) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                    return;
                }
                showDnaTopicsProviderDialog(context);
            } else if (position == ROW_DNA_MODELS) {
                if (!org.telegram.messenger.MglaFeatureFlags.isAllowed("ai_chat_dna")) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                    return;
                }
                presentFragment(new MglaLocalModelsActivity());
            } else if (position == ROW_MEMORY_PROVIDER) {
                if (!MglaMemoryConfig.isActive()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                    return;
                }
                if (!MglaSubscription.canUseLiveMemory()) {
                    showLiveMemoryUpsell();
                    return;
                }
                showMemoryProviderDialog(context);
            } else if (position == ROW_MEMORY_INTERVAL) {
                if (!MglaMemoryConfig.isActive()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                    return;
                }
                if (!MglaSubscription.canUseLiveMemory()) {
                    showLiveMemoryUpsell();
                    return;
                }
                showMemoryIntervalDialog(context);
            } else if (position == ROW_MEMORY_LIMIT) {
                if (!MglaSubscription.canUseLiveMemory()) {
                    showLiveMemoryUpsell();
                }
            } else if (position == ROW_MEMORY_ENABLED) {
                if (!MglaMemoryConfig.isActive()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                    return;
                }
                if (!MglaSubscription.canUseLiveMemory()) {
                    MglaMemoryConfig.setEnabled(false);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(false);
                    }
                    showLiveMemoryUpsell();
                    return;
                }
                boolean enabled = !MglaMemoryConfig.isToggleOn();
                MglaMemoryConfig.setEnabled(enabled);
                if (enabled) {
                    MglaMemoryExtractor.kick();
                }
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(MglaMemoryConfig.isEnabled());
                }
                if (listView != null && listView.getAdapter() != null) {
                    listView.getAdapter().notifyItemChanged(ROW_MEMORY_LIMIT);
                }
            } else if (position == ROW_PROVIDER_BASIC) {
                if (!"openrouter".equals(prefs.getString("ai_provider", "openrouter"))) {
                    prefs.edit().putString("ai_provider", "openrouter").apply();
                    if (listView.getAdapter() != null) {
                        listView.getAdapter().notifyDataSetChanged();
                    }
                }
            } else if (position == ROW_PROVIDER_GEMINI) {
                if (!"gemini".equals(prefs.getString("ai_provider", "openrouter"))) {
                    prefs.edit().putString("ai_provider", "gemini").apply();
                    if (listView.getAdapter() != null) {
                        listView.getAdapter().notifyDataSetChanged();
                    }
                }
            } else {
                String key = getSwitchKey(position);
                if (key != null) {
                    boolean enabled = !prefs.getBoolean(key, true);
                    if (enabled && !org.telegram.messenger.MglaFeatureFlags.isAllowed(key)) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                        return;
                    }
                    prefs.edit().putBoolean(key, enabled).apply();
                    if ("ai_summary".equals(key)) {
                        MglaMessageMenuController.setEnabled(context, ChatActivity.OPTION_AI_SUMMARY, enabled);
                    }
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(enabled);
                    }
                }
            }
        });

        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private String getSwitchKey(int position) {
        switch (position) {
            case ROW_AI_ENABLED:
                return "ai_enabled";
            case ROW_AI_SUMMARY:
                return "ai_summary";
            case ROW_AI_RETELL:
                return "ai_retell";
            case ROW_AI_EDITOR:
                return "ai_editor";
        }
        return null;
    }

    /** Выбор нейросети для генерации главных тем Chat DNA. */
    private void showDnaTopicsProviderDialog(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        String current = MglaChatDna.getTopicsProvider();
        String[] names = {"Gemini (Ваш API)", "Локальная модель"};
        String[] values = {MglaChatDna.PROVIDER_GEMINI, MglaChatDna.PROVIDER_LOCAL};
        int checked = MglaChatDna.PROVIDER_GEMINI.equals(current) ? 0 : 1;

        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Главные темы Chat DNA");
        dlg.setItems(names, (dialog, which) -> {
            MglaChatDna.setTopicsProvider(values[which]);
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyDataSetChanged();
            }
        });
        showDialog(dlg.create());
    }

    /** Провайдер фонового анализа для «Живой памяти». */
    private void showMemoryProviderDialog(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        String[] names = {"Авто (Gemini → OpenRouter → локально)", "Gemini (Ваш API)", "OpenRouter / хаб"};
        String[] values = {MglaMemoryConfig.PROVIDER_AUTO, MglaMemoryConfig.PROVIDER_GEMINI, MglaMemoryConfig.PROVIDER_OPENROUTER};
        int checked = 0;
        String current = MglaMemoryConfig.getProvider();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
                break;
            }
        }
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Провайдер памяти");
        dlg.setItems(names, (dialog, which) -> {
            MglaMemoryConfig.setProvider(values[which]);
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyItemChanged(ROW_MEMORY_PROVIDER);
            }
        });
        showDialog(dlg.create());
    }

    /** Как часто экстрактор дочитывает новые сообщения в наблюдаемых чатах. */
    private void showMemoryIntervalDialog(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        String[] names = {"Каждые 15 минут", "Каждый час", "Каждые 4 часа", "Раз в сутки"};
        int[] values = {15, 60, 240, 1440};
        int checked = 2;
        int current = MglaMemoryConfig.getIntervalMin();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
                break;
            }
        }
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Интервал обновления памяти");
        dlg.setItems(names, (dialog, which) -> {
            MglaMemoryConfig.setIntervalMin(values[which]);
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyItemChanged(ROW_MEMORY_INTERVAL);
            }
        });
        showDialog(dlg.create());
    }

    private String modelSummary() {
        String id = MglaLocalModelsManager.getSelectedModelId();
        if (id.isEmpty()) {
            return "Не выбрана";
        }
        // Короткое значение: заголовок пункта должен оставаться целиком.
        return id.length() > 14 ? id.substring(0, 14) + "…" : id;
    }

    @Override
    public void onResume() {
        super.onResume();
        MglaMemoryConfig.clearIfUnauthorized();
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyItemChanged(ROW_AI_EDITOR_LIMIT);
            if (MglaMemoryConfig.isActive()) {
                listView.getAdapter().notifyItemChanged(ROW_MEMORY_ENABLED);
                listView.getAdapter().notifyItemChanged(ROW_MEMORY_LIMIT);
            }
        }
    }

    private void showLiveMemoryUpsell() {
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.star_premium_2,
                "Живая память доступна с любой подпиской Mgla Premium",
                "Оформить",
                () -> presentFragment(new MglaPremiumActivity())
        ).show();
    }

    /** Gold premium star to the left of «Включить живую память». */
    private static CharSequence liveMemoryToggleLabel(Context context) {
        SpannableStringBuilder sb = new SpannableStringBuilder("  Включить живую память");
        Drawable star = context.getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
        int size = dp(16);
        star.setBounds(0, 0, size, size);
        star.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_starsGradient1), PorterDuff.Mode.SRC_IN));
        sb.setSpan(new ImageSpan(star, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Прокрутка к переключателю живой памяти и подсветка строки. */
    public void highlightMemoryToggle() {
        if (!MglaMemoryConfig.isActive()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (listView == null) {
                return;
            }
            RecyclerView.LayoutManager lm = listView.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(ROW_MEMORY_HEADER, dp(24));
            } else {
                listView.scrollToPosition(ROW_MEMORY_ENABLED);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (listView == null) {
                    return;
                }
                RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(ROW_MEMORY_ENABLED);
                if (holder == null) {
                    holder = listView.findViewHolderForAdapterPosition(ROW_MEMORY_HEADER);
                }
                if (holder != null && holder.itemView != null) {
                    MglaSettingsHighlight.flashRow(holder.itemView);
                } else {
                    MglaSettingsHighlight.highlight(this, "Включить живую память");
                }
            }, 360);
        }, 450);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == ROW_AI_ENABLED || position == ROW_AI_SUMMARY || position == ROW_AI_RETELL || position == ROW_AI_EDITOR || position == ROW_AI_TRANSCRIBE || position == ROW_PROVIDER_BASIC || position == ROW_PROVIDER_GEMINI || position == ROW_DNA_TOPICS || position == ROW_DNA_MODELS
                || position == ROW_MEMORY_ENABLED || position == ROW_MEMORY_PROVIDER || position == ROW_MEMORY_INTERVAL || position == ROW_MEMORY_LIMIT;
        }

        @Override
        public int getItemCount() {
            // Hide Живая память section entirely when hub kill-switch is on.
            return MglaMemoryConfig.isActive() ? ROW_COUNT : ROW_SHADOW_5;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_SHADOW_1 || position == ROW_SHADOW_2 || position == ROW_SHADOW_3 || position == ROW_SHADOW_4 || position == ROW_SHADOW_5) {
                return VIEW_TYPE_SHADOW;
            } else if (position == ROW_AI_EDITOR_LIMIT || position == ROW_AI_TRANSCRIBE || position == ROW_DNA_TOPICS || position == ROW_DNA_MODELS
                    || position == ROW_MEMORY_PROVIDER || position == ROW_MEMORY_INTERVAL || position == ROW_MEMORY_LIMIT) {
                return VIEW_TYPE_TEXT;
            } else if (position == ROW_PROVIDER_HEADER || position == ROW_DNA_HEADER || position == ROW_FUNCTIONS_HEADER || position == ROW_MEMORY_HEADER) {
                return VIEW_TYPE_HEADER;
            } else if (position == ROW_PROVIDER_BASIC || position == ROW_PROVIDER_GEMINI) {
                return VIEW_TYPE_RADIO;
            }
            return VIEW_TYPE_CHECK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
            } else if (viewType == VIEW_TYPE_TEXT) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(context, 22);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_RADIO) {
                view = new RadioCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            // Сброс alpha после recycle (блок памяти без подписки — 0.4f).
            if (position != ROW_MEMORY_HEADER && position != ROW_MEMORY_ENABLED
                    && position != ROW_MEMORY_PROVIDER && position != ROW_MEMORY_INTERVAL
                    && position != ROW_MEMORY_LIMIT) {
                holder.itemView.setAlpha(1f);
            }
            if (position == ROW_AI_EDITOR_LIMIT) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Лимит запросов к AI", AiAssistant.getUsageLabel(), false);
                cell.setCanDisable(false);
            } else if (position == ROW_AI_TRANSCRIBE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndIcon("ИИ-расшифровка", R.drawable.msg_arrowright, false);
                cell.setCanDisable(false);
            } else if (position == ROW_DNA_TOPICS) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Главные темы Chat DNA", MglaChatDna.getTopicsProviderTitle(), false);
                cell.setCanDisable(false);
            } else if (position == ROW_DNA_MODELS) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Локальные модели", modelSummary(), false);
                cell.setCanDisable(false);
            } else if (position == ROW_MEMORY_PROVIDER) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                boolean unlocked = MglaSubscription.canUseLiveMemory();
                cell.setTextAndValue("Провайдер памяти",
                    unlocked ? MglaMemoryConfig.getProviderTitle() : "—", false);
                cell.setCanDisable(false);
                cell.setAlpha(unlocked ? 1f : 0.4f);
            } else if (position == ROW_MEMORY_INTERVAL) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                boolean unlocked = MglaSubscription.canUseLiveMemory();
                cell.setTextAndValue("Интервал обновления",
                    unlocked ? MglaMemoryConfig.getIntervalTitle() : "—", false);
                cell.setCanDisable(false);
                cell.setAlpha(unlocked ? 1f : 0.4f);
            } else if (position == ROW_MEMORY_LIMIT) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                boolean unlocked = MglaSubscription.canUseLiveMemory();
                cell.setTextAndValue("Лимит запросов памяти",
                    unlocked
                        ? (MglaMemoryConfig.getUsedToday() + "/" + MglaMemoryConfig.AI_DAILY_LIMIT
                            + " (сброс " + MglaMemoryConfig.getResetInLabel() + ")")
                        : "—",
                    false);
                cell.setCanDisable(false);
                cell.setAlpha(unlocked ? 1f : 0.4f);
            } else if (holder.itemView instanceof HeaderCell) {
                String headerText = "Провайдер AI";
                if (position == ROW_DNA_HEADER) {
                    headerText = "Chat DNA";
                } else if (position == ROW_FUNCTIONS_HEADER) {
                    headerText = "Функции";
                } else if (position == ROW_MEMORY_HEADER) {
                    headerText = "Живая память";
                }
                HeaderCell header = (HeaderCell) holder.itemView;
                header.setText(headerText);
                header.setAlpha(position == ROW_MEMORY_HEADER && !MglaSubscription.canUseLiveMemory()
                        ? 0.4f : 1f);
            } else if (holder.itemView instanceof RadioCell) {
                RadioCell cell = (RadioCell) holder.itemView;
                String provider = prefs.getString("ai_provider", "openrouter");
                if (position == ROW_PROVIDER_BASIC) {
                    cell.setText("Базовый", "openrouter".equals(provider), false);
                } else if (position == ROW_PROVIDER_GEMINI) {
                    cell.setText("Gemini (Ваш API)", "gemini".equals(provider), false);
                }
            } else if (holder.itemView instanceof TextCheckCell) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                switch (position) {
                    case ROW_AI_ENABLED:
                        cell.setTextAndCheck("Включение AI",
                            org.telegram.messenger.MglaFeatureFlags.isAllowed("ai_enabled") && prefs.getBoolean("ai_enabled", true), false);
                        break;
                    case ROW_AI_SUMMARY:
                        cell.setTextAndCheck("Краткая Сводка",
                            org.telegram.messenger.MglaFeatureFlags.isAllowed("ai_summary") && prefs.getBoolean("ai_summary", true), false);
                        break;
                    case ROW_AI_RETELL:
                        cell.setTextAndCheck("Пересказ сообщений",
                            org.telegram.messenger.MglaFeatureFlags.isAllowed("ai_retell") && prefs.getBoolean("ai_retell", true), false);
                        break;
                    case ROW_AI_EDITOR:
                        cell.setTextAndCheck("AI-редактор",
                            org.telegram.messenger.MglaFeatureFlags.isAllowed("ai_editor") && prefs.getBoolean("ai_editor", true), false);
                        break;
                    case ROW_MEMORY_ENABLED:
                        MglaMemoryConfig.clearIfUnauthorized();
                        boolean unlocked = MglaSubscription.canUseLiveMemory();
                        // Без подписки переключатель всегда выкл и блок выглядит неактивным.
                        cell.setTextAndCheck(liveMemoryToggleLabel(context),
                            unlocked && MglaMemoryConfig.isEnabled(), true);
                        cell.setAlpha(unlocked ? 1f : 0.4f);
                        break;
                }
            }
        }
    }

}
