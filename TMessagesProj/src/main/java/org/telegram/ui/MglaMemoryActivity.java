package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaMemoryConfig;
import org.telegram.messenger.MglaMemoryEngine;
import org.telegram.messenger.MglaMemoryExtractor;
import org.telegram.messenger.MglaMemoryStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;

/**
 * Memory — личная база знаний из переписок.
 * Три раздела: обзор, записи и карта связей.
 */
public class MglaMemoryActivity extends MglaBaseSettingsActivity {

    private android.content.Context context;
    private LinearLayout rootLayout;
    private FrameLayout pages;
    private View overviewPage;
    private View memoryPage;
    private View linksPage;
    private int section;

    private EditTextBoldCursor askEdit;
    private LinearLayout answerCard;
    private TextView answerView;
    private TextView answerMetaView;
    private LinearLayout answerSources;

    private MglaMemoryPerimeterGlow resumeGlow;
    private LinearLayout resumeCard;
    private TextView resumeChatView;
    private TextView resumeWhenView;
    private TextView resumeContextView;
    private TextView resumeDecisionView;
    private TextView resumeNextView;
    private LinearLayout importantList;
    private LinearLayout tasksList;
    private LinearLayout recentList;
    private TextView overviewEmpty;
    private LinearLayout sourcesList;

    private LinearLayout typeChipsRow;
    private LinearLayout statusChipsRow;
    private LinearLayout factsList;
    private TextView factsEmpty;
    private String typeFilter;
    private String statusFilter;

    private MglaMemoryGraphView graphView;
    private MglaMemoryStorage.MemoryGraph lastGraph;
    private LinearLayout nodeCard;
    private TextView nodeCardTitle;
    private TextView nodeCardSubtitle;
    private TextView nodeCardLinks;
    private TextView nodeCardSummary;
    private TextView nodeCardOpen;
    private MglaMemoryStorage.MemoryGraph.Node selectedNode;
    private MglaMemoryStorage.MemoryFact selectedNodeFact;
    private String graphNodeType;
    private boolean graphMonthOnly;
    private String pendingFocus;
    private boolean memoryPageBuilt;
    private boolean linksPageBuilt;

    public MglaMemoryActivity() {
        super();
    }

    @Override
    public View createView(android.content.Context context) {
        this.context = context;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Живая память");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        if (!MglaMemoryConfig.isActive()) {
            rootLayout.addView(wrapScroll(createDisabledBlock(context)));
        } else if (!MglaMemoryConfig.isEnabled()) {
            rootLayout.addView(wrapScroll(createEnableCard(context)));
        } else {
            buildShell(context);
        }
        fragmentView = rootLayout;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Сначала отдаём кадр интерфейса, тяжёлую подгрузку — следом.
        AndroidUtilities.runOnUIThread(this::reloadVisible, 16);
    }

    private View wrapScroll(View child) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);
        box.addView(child, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 16));
        scrollView.addView(box, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return scrollView;
    }

    private View createDisabledBlock(android.content.Context context) {
        LinearLayout block = card(context);
        TextView text = body(context, "Функция отключена администратором", 14, false);
        text.setGravity(Gravity.CENTER);
        block.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 28, 16, 28));
        return block;
    }

    private View createEnableCard(android.content.Context context) {
        LinearLayout block = card(context);
        TextView kicker = label(context, "Живая память");
        kicker.setGravity(Gravity.CENTER);
        block.addView(kicker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 22, 20, 4));
        TextView title = title(context, "Память выключена", 20);
        title.setGravity(Gravity.CENTER);
        block.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 8));
        TextView subtitle = body(context, "Пока она выключена, переписки не разбираются и восстановить контекст разговора не из чего.", 14, false);
        subtitle.setGravity(Gravity.CENTER);
        block.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 16));
        block.addView(accentButton(context, "Включить память", () -> {
            MglaMemoryConfig.setEnabled(true);
            MglaMemoryExtractor.kick();
            rootLayout.removeAllViews();
            buildShell(context);
            reloadAll();
        }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 16, 0, 16, 18));
        return block;
    }

    private void buildShell(android.content.Context context) {
        rootLayout.addView(createTabs(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 0));
        pages = new FrameLayout(context);
        overviewPage = createOverviewPage(context);
        pages.addView(overviewPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // Память и связи собираем лениво — иначе открытие раздела заметно тормозит.
        memoryPage = null;
        linksPage = null;
        memoryPageBuilt = false;
        linksPageBuilt = false;
        rootLayout.addView(pages, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        showSection(0);
    }

    private View createTabs(android.content.Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        row.setBackground(bg);
        String[] names = {"Обзор", "Память", "Связи"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView tab = new TextView(context);
            tab.setText(names[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tab.setTypeface(AndroidUtilities.bold());
            tab.setOnClickListener(v -> showSection(index));
            tab.setTag(index);
            row.addView(tab, LayoutHelper.createLinear(0, 36, 1f));
        }
        return row;
    }

    private void showSection(int index) {
        section = index;
        ensureSectionBuilt(index);
        if (overviewPage != null) {
            overviewPage.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        }
        if (memoryPage != null) {
            memoryPage.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        }
        if (linksPage != null) {
            linksPage.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        }
        if (rootLayout != null && rootLayout.getChildCount() > 0 && rootLayout.getChildAt(0) instanceof LinearLayout) {
            LinearLayout tabs = (LinearLayout) rootLayout.getChildAt(0);
            for (int i = 0; i < tabs.getChildCount(); i++) {
                TextView tab = (TextView) tabs.getChildAt(i);
                boolean on = i == index;
                tab.setTextColor(on ? accent() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                GradientDrawable chip = new GradientDrawable();
                chip.setCornerRadius(dp(11));
                chip.setColor(on ? accentSoft(0x1A) : Color.TRANSPARENT);
                tab.setBackground(chip);
            }
        }
        if (index == 1) {
            reloadFacts();
        } else if (index == 2) {
            reloadGraph();
        }
    }

    private void ensureSectionBuilt(int index) {
        if (pages == null || context == null) {
            return;
        }
        if (index == 1 && !memoryPageBuilt) {
            memoryPage = createMemoryPage(context);
            pages.addView(memoryPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            memoryPageBuilt = true;
        } else if (index == 2 && !linksPageBuilt) {
            linksPage = createLinksPage(context);
            pages.addView(linksPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            linksPageBuilt = true;
            if (graphView != null) {
                graphView.setAccount(currentAccount);
            }
        }
    }

    // region обзор

    private View createOverviewPage(android.content.Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(24) + AndroidUtilities.navigationBarHeight);

        TextView kicker = label(context, "Твоя память в Mgla");
        page.addView(kicker);

        TextView askTitle = title(context, "Спроси свою память", 26);
        page.addView(askTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 10));

        askEdit = new EditTextBoldCursor(context);
        askEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        askEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        askEdit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        askEdit.setCursorColor(accent());
        askEdit.setCursorSize(AndroidUtilities.dp(20));
        askEdit.setCursorWidth(1.5f);
        askEdit.setHint("Например, на чём мы остановились с проектом…");
        askEdit.setMinLines(2);
        askEdit.setMaxLines(4);
        askEdit.setGravity(Gravity.TOP | Gravity.START);
        askEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        askEdit.setBackground(fieldBackground());
        askEdit.setPadding(dp(16), dp(14), dp(16), dp(14));
        page.addView(askEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout quick = new LinearLayout(context);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        String[] prompts = {"На чём остановились?", "Открытые обещания", "Что изменилось?"};
        String[] queries = {"На чём мы остановились?", "Какие обещания ещё не закрыты?", "Что изменилось за месяц?"};
        for (int i = 0; i < prompts.length; i++) {
            final String q = queries[i];
            TextView chip = chip(prompts[i], false);
            chip.setOnClickListener(v -> {
                askEdit.setText(q);
                askEdit.setSelection(askEdit.length());
                performAsk();
            });
            quick.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
        HorizontalScrollView quickScroll = new HorizontalScrollView(context);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickScroll.addView(quick);
        page.addView(quickScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        TextView askButton = accentButton(context, "Спросить", this::performAsk);
        page.addView(askButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 10, 0, 0));

        answerCard = card(context);
        answerCard.setVisibility(View.GONE);
        answerView = body(context, "", 15, false);
        answerView.setLineSpacing(dp(3), 1f);
        answerCard.addView(answerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 4));
        answerMetaView = label(context, "");
        answerCard.addView(answerMetaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 4));
        answerSources = new LinearLayout(context);
        answerSources.setOrientation(LinearLayout.VERTICAL);
        answerCard.addView(answerSources, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 12));
        page.addView(answerCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        overviewEmpty = body(context, "Память ещё пустая. Добавьте чат в источники внизу — из переписки появятся решения, задачи и связи.", 14, false);
        overviewEmpty.setVisibility(View.GONE);
        page.addView(overviewEmpty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 16, 4, 0));

        page.addView(sectionTitle(context, "Продолжить с того места, где остановился"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 22, 2, 8));
        resumeGlow = new MglaMemoryPerimeterGlow(context);
        resumeCard = card(context);
        resumeChatView = title(context, "", 18);
        resumeCard.addView(resumeChatView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));
        resumeWhenView = label(context, "");
        resumeCard.addView(resumeWhenView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));
        resumeContextView = body(context, "", 14, false);
        resumeCard.addView(resumeContextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 10));
        resumeCard.addView(miniLabel(context, "Принятое решение"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 2));
        resumeDecisionView = body(context, "", 15, true);
        resumeCard.addView(resumeDecisionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 10));
        resumeCard.addView(miniLabel(context, "Требует продолжения"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 2));
        resumeNextView = body(context, "", 15, false);
        resumeCard.addView(resumeNextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));
        resumeCard.addView(accentButton(context, "Продолжить работу", () -> {
            String chat = resumeChatView.getText().toString();
            String q = TextUtils.isEmpty(chat) ? "На чём мы остановились?" : "На чём мы остановились с " + chat + "?";
            askEdit.setText(q);
            askEdit.setSelection(askEdit.length());
            performAsk();
        }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 4, 12, 14));
        resumeGlow.setContent(resumeCard);
        resumeGlow.setVisibility(View.GONE);
        page.addView(resumeGlow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        page.addView(sectionTitle(context, "Важное за последнее время"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 20, 2, 8));
        importantList = column(context);
        page.addView(importantList);

        page.addView(sectionTitle(context, "Незавершённые задачи"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 12, 2, 8));
        tasksList = column(context);
        page.addView(tasksList);

        page.addView(sectionTitle(context, "Недавно обновлённые"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 12, 2, 8));
        recentList = column(context);
        page.addView(recentList);

        LinearLayout sourcesHeader = new LinearLayout(context);
        sourcesHeader.setOrientation(LinearLayout.HORIZONTAL);
        sourcesHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView sourcesTitle = sectionTitle(context, "Источники");
        sourcesHeader.addView(sourcesTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        TextView add = new TextView(context);
        add.setText("Добавить");
        add.setTextColor(accent());
        add.setTypeface(AndroidUtilities.bold());
        add.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        add.setOnClickListener(v -> showAddChatDialog());
        sourcesHeader.addView(add);
        page.addView(sourcesHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 18, 2, 8));
        sourcesList = column(context);
        page.addView(sourcesList);
        TextView sourcesHint = label(context, "Память читает только те чаты, которые вы сюда добавили.");
        page.addView(sourcesHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 8, 2, 0));

        scroll.addView(page, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return scroll;
    }

    private void performAsk() {
        if (askEdit == null) {
            return;
        }
        String question = askEdit.getText().toString().trim();
        if (question.isEmpty()) {
            return;
        }
        AndroidUtilities.hideKeyboard(askEdit);
        answerCard.setVisibility(View.VISIBLE);
        answerView.setText("Смотрю записи и источники…");
        answerMetaView.setText("");
        answerSources.removeAllViews();
        MglaMemoryEngine.answer(currentAccount, question, answer -> {
            if (answerView == null) {
                return;
            }
            formatBold(answerView, answer.text == null ? "" : answer.text);
            if (answer.empty) {
                answerMetaView.setText("Точного ответа нет");
            } else {
                String src = answer.local ? "собрано из записей" : "собрано с помощью модели";
                answerMetaView.setText(answer.factsUsed + " записей · " + src);
            }
            answerSources.removeAllViews();
            for (MglaMemoryStorage.MemoryFact fact : answer.sources) {
                answerSources.addView(sourceLine(fact));
            }
            if (answer.focusGraph) {
                pendingFocus = answer.focusLabel;
                showSection(2);
            }
        });
    }

    private View sourceLine(MglaMemoryStorage.MemoryFact fact) {
        TextView line = new TextView(context);
        String chat = MglaMemoryEngine.dialogTitle(currentAccount, fact.uid);
        if (TextUtils.isEmpty(chat)) {
            chat = "чат";
        }
        line.setText("Источник · " + chat + " · " + relativeDay(fact.lastSeenAt));
        line.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        line.setTextColor(accent());
        line.setPadding(0, dp(6), 0, dp(2));
        line.setOnClickListener(v -> openFactSource(fact));
        return line;
    }

    private void bindOverview(MglaMemoryEngine.Overview overview) {
        if (resumeGlow == null || overview == null) {
            return;
        }
        boolean empty = overview.resume == null && overview.important.isEmpty() && overview.openTasks.isEmpty() && overview.recent.isEmpty();
        overviewEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        setBlockVisible(resumeGlow, overview.resume != null);
        if (overview.resume != null) {
            resumeGlow.setVisibility(View.VISIBLE);
            String chat = TextUtils.isEmpty(overview.resumeChat) ? "Переписка" : overview.resumeChat;
            resumeChatView.setText(chat);
            resumeWhenView.setText(relativeDay(overview.resume.lastSeenAt));
            resumeContextView.setText(overview.resume.text == null ? "" : overview.resume.text);
            resumeDecisionView.setText(TextUtils.isEmpty(overview.decisionLine)
                ? "Прямого решения в записях пока нет."
                : overview.decisionLine);
            resumeNextView.setText(TextUtils.isEmpty(overview.nextLine)
                ? "Открытых шагов по этой теме не видно."
                : overview.nextLine);
        }
        fillFactColumn(importantList, overview.important);
        fillFactColumn(tasksList, overview.openTasks);
        fillFactColumn(recentList, overview.recent);
        setBlockVisible(importantList, !overview.important.isEmpty());
        setBlockVisible(tasksList, !overview.openTasks.isEmpty());
        setBlockVisible(recentList, !overview.recent.isEmpty());
        rebuildSources();
    }

    private void setBlockVisible(View block, boolean visible) {
        block.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!(block.getParent() instanceof LinearLayout)) {
            return;
        }
        LinearLayout parent = (LinearLayout) block.getParent();
        int index = parent.indexOfChild(block);
        if (index > 0) {
            parent.getChildAt(index - 1).setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void fillFactColumn(LinearLayout list, ArrayList<MglaMemoryStorage.MemoryFact> facts) {
        list.removeAllViews();
        if (facts == null) {
            return;
        }
        for (MglaMemoryStorage.MemoryFact fact : facts) {
            list.addView(createCompactFact(fact), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private View createCompactFact(MglaMemoryStorage.MemoryFact fact) {
        LinearLayout card = card(context);
        card.setOnClickListener(v -> showSection(1));
        String day = relativeDay(fact.lastSeenAt);
        TextView kind = miniLabel(context, joinDot(MglaMemoryEngine.typeRu(fact.type), day));
        kind.setTextColor(isHot(fact.type) ? accent() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(kind, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 12, 14, 0));
        TextView text = body(context, fact.text == null ? "" : fact.text, 15, false);
        text.setMaxLines(3);
        text.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 4, 14, 4));
        String chat = MglaMemoryEngine.dialogTitle(currentAccount, fact.uid);
        String meta = (TextUtils.isEmpty(chat) ? "чат" : chat) + " · " + MglaMemoryEngine.certaintyRu(fact);
        card.addView(label(context, meta), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 12));
        return card;
    }

    // endregion

    // region память

    private View createMemoryPage(android.content.Context context) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(24) + AndroidUtilities.navigationBarHeight);

        TextView hint = label(context, "Решение, событие, обещание, идея или факт. У каждой записи есть источник.");
        page.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 8, 2, 8));

        typeChipsRow = new LinearLayout(context);
        typeChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(horizontal(typeChipsRow), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        statusChipsRow = new LinearLayout(context);
        statusChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(horizontal(statusChipsRow), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        factsList = column(context);
        page.addView(factsList);
        factsEmpty = body(context, "Записей с таким фильтром нет.", 14, false);
        factsEmpty.setVisibility(View.GONE);
        page.addView(factsEmpty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 12, 2, 0));
        updateFilterChips();
        scroll.addView(page, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return scroll;
    }

    private void updateFilterChips() {
        if (typeChipsRow == null) {
            return;
        }
        typeChipsRow.removeAllViews();
        String[] labels = {"Все", "Решения", "Задачи", "Обещания", "События", "Идеи", "Факты", "Вопросы"};
        String[] types = {null, MglaMemoryStorage.TYPE_DECISION, MglaMemoryStorage.TYPE_PLAN,
            MglaMemoryStorage.TYPE_PROMISE, MglaMemoryStorage.TYPE_EVENT, MglaMemoryStorage.TYPE_IDEA,
            MglaMemoryStorage.TYPE_FACT, MglaMemoryStorage.TYPE_QUESTION};
        for (int i = 0; i < labels.length; i++) {
            final String type = types[i];
            boolean on = type == null ? typeFilter == null : type.equals(typeFilter);
            TextView chip = chip(labels[i], on);
            chip.setOnClickListener(v -> {
                typeFilter = type;
                updateFilterChips();
                reloadFacts();
            });
            typeChipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 8, 4));
        }
        statusChipsRow.removeAllViews();
        String[] statusLabels = {"Все", "Актуальные", "Сделанные", "Отменённые", "Скрытые"};
        String[] statuses = {null, MglaMemoryStorage.STATUS_OPEN, MglaMemoryStorage.STATUS_DONE,
            MglaMemoryStorage.STATUS_DROPPED, MglaMemoryStorage.STATUS_EXCLUDED};
        for (int i = 0; i < statusLabels.length; i++) {
            final String status = statuses[i];
            boolean on = status == null ? statusFilter == null : status.equals(statusFilter);
            TextView chip = chip(statusLabels[i], on);
            chip.setOnClickListener(v -> {
                statusFilter = status;
                updateFilterChips();
                reloadFacts();
            });
            statusChipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 8, 4));
        }
    }

    private void reloadFacts() {
        if (factsList == null) {
            return;
        }
        MglaMemoryStorage.MemoryQuery query = new MglaMemoryStorage.MemoryQuery();
        if (typeFilter != null) {
            query.types = new HashSet<>(Collections.singleton(typeFilter));
        }
        query.status = statusFilter;
        query.limit = 80;
        query.openFirst = true;
        MglaMemoryEngine.queryFacts(currentAccount, query, facts -> {
            if (factsList == null) {
                return;
            }
            factsList.removeAllViews();
            boolean empty = facts == null || facts.isEmpty();
            factsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (empty) {
                return;
            }
            for (MglaMemoryStorage.MemoryFact fact : facts) {
                factsList.addView(createFactCard(fact), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
            }
        });
    }

    private View createFactCard(MglaMemoryStorage.MemoryFact fact) {
        LinearLayout card = card(context);
        if (isHot(fact.type)) {
            card.addView(hairline(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 2));
        }
        TextView kind = miniLabel(context, joinDot(MglaMemoryEngine.typeRu(fact.type), MglaMemoryEngine.statusRu(fact.status)));
        kind.setTextColor(isHot(fact.type) ? accent() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(kind, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 12, 14, 0));

        TextView text = body(context, fact.text == null ? "" : fact.text, 16, false);
        text.setLineSpacing(dp(2), 1f);
        card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 6, 14, 0));

        String chat = MglaMemoryEngine.dialogTitle(currentAccount, fact.uid);
        String author = fact.fromSelf ? "я" : MglaMemoryEngine.peerName(currentAccount, fact.peerId);
        String meta = relativeDay(fact.lastSeenAt) + " · " + (TextUtils.isEmpty(chat) ? "чат" : chat)
            + " · " + author + " · " + MglaMemoryEngine.certaintyRu(fact);
        card.addView(label(context, meta), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 6, 14, 0));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addAction(actions, "К сообщению", () -> openFactSource(fact));
        addAction(actions, "Почему", () -> showWhy(fact));
        addAction(actions, "Править", () -> editFact(fact));
        if (MglaMemoryStorage.STATUS_EXCLUDED.equals(fact.status)) {
            addAction(actions, "Вернуть в ответы", () -> {
                MglaMemoryEngine.setFactStatus(currentAccount, fact.id, MglaMemoryStorage.STATUS_OPEN);
                reloadFacts();
                reloadOverview();
            });
        } else {
            addAction(actions, "Не использовать", () -> {
                MglaMemoryEngine.setFactStatus(currentAccount, fact.id, MglaMemoryStorage.STATUS_EXCLUDED);
                reloadFacts();
                reloadOverview();
            });
        }
        addAction(actions, "Удалить", () -> {
            MglaMemoryEngine.deleteFact(currentAccount, fact.id);
            reloadFacts();
            reloadOverview();
            reloadGraph();
        });
        card.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 4, 8, 8));
        return card;
    }

    private void showWhy(MglaMemoryStorage.MemoryFact fact) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Почему это важно");
        builder.setMessage(MglaMemoryEngine.whyImportant(currentAccount, fact));
        builder.setPositiveButton("К сообщению", (d, w) -> openFactSource(fact));
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    private void editFact(MglaMemoryStorage.MemoryFact fact) {
        MglaUi.showInputDialog(this, "Исправить запись", "Текст, который память будет использовать в ответах.",
            fact.text, true, false, text -> {
                if (TextUtils.isEmpty(text) || text.trim().length() < 2) {
                    return;
                }
                MglaMemoryEngine.updateFactText(currentAccount, fact.id, text.trim());
                AndroidUtilities.runOnUIThread(() -> {
                    reloadFacts();
                    reloadOverview();
                }, 250);
            });
    }

    private void openFactSource(MglaMemoryStorage.MemoryFact fact) {
        if (fact == null || fact.uid == 0) {
            return;
        }
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(fact.uid)) {
            args.putLong("user_id", fact.uid);
        } else {
            args.putLong("chat_id", -fact.uid);
        }
        int mid = firstMid(fact.sourceMids);
        if (mid != 0) {
            args.putInt("message_id", mid);
        }
        presentFragment(new ChatActivity(args));
    }

    private static int firstMid(String mids) {
        if (TextUtils.isEmpty(mids)) {
            return 0;
        }
        String first = mids.split(",")[0].trim();
        try {
            return Integer.parseInt(first);
        } catch (Exception e) {
            return 0;
        }
    }

    // endregion

    // region связи

    private View createLinksPage(android.content.Context context) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView hint = label(context, "Люди, темы и события. Нажмите узел, чтобы увидеть записи и сообщения.");
        page.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 6, 2, 6));

        LinearLayout filters = new LinearLayout(context);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Все", "Люди", "Темы", "События", "Файлы", "Месяц"};
        String[] types = {null, "person", "topic", "event", "file", "month"};
        for (int i = 0; i < names.length; i++) {
            final String type = types[i];
            TextView chip = chip(names[i], type == null);
            chip.setTag(type == null ? "all" : type);
            chip.setOnClickListener(v -> applyGraphFilter(type, filters));
            filters.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
        page.addView(horizontal(filters), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        graphView = new MglaMemoryGraphView(context);
        graphView.setNodeClickListener(this::showNodeDetails);
        page.addView(graphView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        nodeCard = card(context);
        nodeCard.setVisibility(View.GONE);
        nodeCardTitle = title(context, "", 15);
        nodeCardTitle.setMaxLines(2);
        nodeCardTitle.setEllipsize(TextUtils.TruncateAt.END);
        nodeCard.addView(nodeCardTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 10, 14, 0));
        nodeCardSubtitle = label(context, "");
        nodeCard.addView(nodeCardSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 2, 14, 0));
        nodeCardLinks = body(context, "", 13, false);
        nodeCardLinks.setMaxLines(2);
        nodeCardLinks.setEllipsize(TextUtils.TruncateAt.END);
        nodeCard.addView(nodeCardLinks, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 6, 14, 0));
        nodeCardSummary = label(context, "");
        nodeCardSummary.setMaxLines(2);
        nodeCardSummary.setEllipsize(TextUtils.TruncateAt.END);
        nodeCard.addView(nodeCardSummary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 4, 14, 0));
        nodeCardOpen = accentButton(context, "Открыть в чате", this::openSelectedNode);
        nodeCard.addView(nodeCardOpen, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 12, 8, 12, 10));
        page.addView(nodeCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        return page;
    }

    private void applyGraphFilter(String type, LinearLayout filters) {
        if ("month".equals(type)) {
            graphMonthOnly = !graphMonthOnly;
        } else {
            graphNodeType = type;
        }
        for (int i = 0; i < filters.getChildCount(); i++) {
            TextView chip = (TextView) filters.getChildAt(i);
            String tag = String.valueOf(chip.getTag());
            boolean on;
            if ("month".equals(tag)) {
                on = graphMonthOnly;
            } else if ("all".equals(tag)) {
                on = graphNodeType == null;
            } else {
                on = tag.equals(graphNodeType);
            }
            styleChip(chip, on);
        }
        reloadGraph();
    }

    private void showNodeDetails(MglaMemoryStorage.MemoryGraph.Node node) {
        if (nodeCard == null || node == null) {
            return;
        }
        selectedNode = node;
        selectedNodeFact = null;
        nodeCard.setVisibility(View.VISIBLE);
        nodeCardTitle.setText(node.label == null ? "" : node.label);

        ArrayList<String> linkedNames = new ArrayList<>();
        int links = 0;
        if (lastGraph != null) {
            for (MglaMemoryStorage.MemoryGraph.Edge edge : lastGraph.edges) {
                String otherKey = null;
                if (node.key.equals(edge.fromKey)) {
                    otherKey = edge.toKey;
                } else if (node.key.equals(edge.toKey)) {
                    otherKey = edge.fromKey;
                }
                if (otherKey == null) {
                    continue;
                }
                links++;
                String name = labelForGraphKey(otherKey);
                if (!TextUtils.isEmpty(name) && !linkedNames.contains(name) && linkedNames.size() < 4) {
                    linkedNames.add(name);
                }
            }
        }
        final int linksCount = links;
        nodeCardSubtitle.setText(nodeTypeRu(node.type) + " · " + linksRu(linksCount));
        if (linkedNames.isEmpty()) {
            nodeCardLinks.setText("Связанных узлов пока нет");
            nodeCardLinks.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        } else {
            nodeCardLinks.setText("Связи: " + TextUtils.join(", ", linkedNames));
            nodeCardLinks.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        if (!TextUtils.isEmpty(node.summary) && !node.summary.equals(node.label)) {
            nodeCardSummary.setVisibility(View.VISIBLE);
            nodeCardSummary.setText(node.summary);
        } else {
            nodeCardSummary.setVisibility(View.GONE);
        }
        nodeCardOpen.setEnabled(false);
        nodeCardOpen.setAlpha(0.55f);
        nodeCardOpen.setText("Открыть в чате");

        MglaMemoryEngine.factsForNode(currentAccount, node.key, facts -> {
            if (nodeCard == null || selectedNode == null || !selectedNode.key.equals(node.key)) {
                return;
            }
            MglaMemoryStorage.MemoryFact best = pickBestSourceFact(facts, node);
            selectedNodeFact = best;
            if (best != null) {
                String chat = MglaMemoryEngine.dialogTitle(currentAccount, best.uid);
                String who = best.fromSelf ? "я" : MglaMemoryEngine.peerName(currentAccount, best.peerId);
                String meta = (!TextUtils.isEmpty(chat) ? chat : "чат")
                    + (TextUtils.isEmpty(who) ? "" : " · " + who)
                    + " · " + relativeDay(best.lastSeenAt);
                if (nodeCardSummary.getVisibility() != View.VISIBLE) {
                    nodeCardSummary.setVisibility(View.VISIBLE);
                    nodeCardSummary.setText(meta);
                } else {
                    nodeCardSubtitle.setText(nodeTypeRu(node.type) + " · " + linksRu(linksCount) + " · " + meta);
                }
                boolean canOpen = best.uid != 0 && firstMid(best.sourceMids) != 0;
                nodeCardOpen.setEnabled(canOpen || best.uid != 0);
                nodeCardOpen.setAlpha(1f);
                nodeCardOpen.setText(canOpen ? "Открыть в чате" : "Открыть чат");
            } else if (node.uid != 0) {
                String chat = MglaMemoryEngine.dialogTitle(currentAccount, node.uid);
                if (!TextUtils.isEmpty(chat)) {
                    nodeCardSubtitle.setText(nodeTypeRu(node.type) + " · " + linksRu(linksCount) + " · " + chat);
                }
                nodeCardOpen.setEnabled(true);
                nodeCardOpen.setAlpha(1f);
            }
        });
    }

    private String labelForGraphKey(String key) {
        if (lastGraph != null) {
            for (MglaMemoryStorage.MemoryGraph.Node n : lastGraph.nodes) {
                if (key.equals(n.key) && !TextUtils.isEmpty(n.label)) {
                    return n.label;
                }
            }
        }
        if ("person:self".equals(key)) {
            return "Я";
        }
        if (key != null && key.startsWith("person:")) {
            try {
                return MglaMemoryEngine.peerName(currentAccount, Long.parseLong(key.substring("person:".length())));
            } catch (Exception ignore) {
            }
        }
        int idx = key == null ? -1 : key.indexOf(':');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    private static String linksRu(int count) {
        int n = Math.abs(count) % 100;
        int n1 = n % 10;
        if (n > 10 && n < 20) {
            return count + " связей";
        }
        if (n1 == 1) {
            return count + " связь";
        }
        if (n1 >= 2 && n1 <= 4) {
            return count + " связи";
        }
        return count + " связей";
    }

    private static MglaMemoryStorage.MemoryFact pickBestSourceFact(ArrayList<MglaMemoryStorage.MemoryFact> facts,
                                                                  MglaMemoryStorage.MemoryGraph.Node node) {
        if (facts == null || facts.isEmpty()) {
            return null;
        }
        MglaMemoryStorage.MemoryFact withMid = null;
        for (MglaMemoryStorage.MemoryFact fact : facts) {
            if (firstMid(fact.sourceMids) != 0) {
                withMid = fact;
                break;
            }
        }
        return withMid != null ? withMid : facts.get(0);
    }

    private void openSelectedNode() {
        if (selectedNodeFact != null) {
            openFactSource(selectedNodeFact);
            return;
        }
        if (selectedNode == null || selectedNode.uid == 0) {
            return;
        }
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(selectedNode.uid)) {
            args.putLong("user_id", selectedNode.uid);
        } else {
            args.putLong("chat_id", -selectedNode.uid);
        }
        if (selectedNode.messageId != 0) {
            args.putInt("message_id", selectedNode.messageId);
        }
        presentFragment(new ChatActivity(args));
    }

    private static String nodeTypeRu(String type) {
        if ("person".equals(type)) return "человек";
        if ("topic".equals(type)) return "тема";
        if ("file".equals(type)) return "файл";
        if ("event".equals(type)) return "событие";
        return "узел";
    }

    private void reloadGraph() {
        if (graphView == null) {
            return;
        }
        long from = 0;
        if (graphMonthOnly) {
            from = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - 30L * 24 * 3600;
        }
        MglaMemoryEngine.graph(currentAccount, 0, graphNodeType, from, graph -> {
            lastGraph = graph;
            if (graphView == null) {
                return;
            }
            graphView.setGraph(graph);
            if (!TextUtils.isEmpty(pendingFocus)) {
                graphView.focusLabel(pendingFocus);
                pendingFocus = null;
            }
        });
    }

    // endregion

    // region источники

    private void rebuildSources() {
        if (sourcesList == null) {
            return;
        }
        sourcesList.removeAllViews();
        ArrayList<Long> uids = MglaMemoryConfig.watchedForAccount(currentAccount);
        if (uids.isEmpty()) {
            sourcesList.addView(label(context, "Чаты не выбраны."), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 0, 2, 0));
            return;
        }
        for (Long uid : uids) {
            LinearLayout row = card(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(18));
            bindDialogAvatar(avatar, uid);
            row.addView(avatar, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 12, 10, 0, 10));
            String title = MglaMemoryEngine.dialogTitle(currentAccount, uid);
            TextView name = body(context, TextUtils.isEmpty(title) ? "Чат" : title, 15, false);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 12, 8, 12));
            TextView remove = new TextView(context);
            remove.setText("Убрать");
            remove.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            remove.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            remove.setPadding(dp(8), dp(12), dp(14), dp(12));
            remove.setOnClickListener(v -> {
                MglaMemoryConfig.removeWatched(currentAccount, uid);
                rebuildSources();
                reloadOverview();
            });
            row.addView(remove);
            sourcesList.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private void bindDialogAvatar(BackupImageView avatar, long uid) {
        AvatarDrawable drawable = new AvatarDrawable();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        if (DialogObject.isUserDialog(uid)) {
            TLRPC.User user = uid == UserConfig.getInstance(currentAccount).getClientUserId()
                ? UserConfig.getInstance(currentAccount).getCurrentUser()
                : mc.getUser(uid);
            drawable.setInfo(currentAccount, user);
            avatar.setForUserOrChat(user, drawable);
        } else {
            TLRPC.Chat chat = mc.getChat(-uid);
            drawable.setInfo(currentAccount, chat);
            avatar.setForUserOrChat(chat, drawable);
        }
    }

    private void showAddChatDialog() {
        int remaining = MglaMemoryConfig.remainingWatchSlots(currentAccount);
        if (remaining <= 0) {
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), "Уже выбрано максимум " + MglaMemoryConfig.MAX_WATCHED + " чатов", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Bundle args = new Bundle();
        args.putInt("maxCount", remaining);
        MglaMemorySourcesPickerActivity picker = new MglaMemorySourcesPickerActivity(args);
        picker.setDelegate(ids -> {
            int added = 0;
            for (int i = 0; i < ids.size(); i++) {
                long uid = ids.get(i);
                if (DialogObject.isEncryptedDialog(uid) || MglaMemoryConfig.isWatched(currentAccount, uid)) {
                    continue;
                }
                if (MglaMemoryConfig.remainingWatchSlots(currentAccount) <= 0) {
                    break;
                }
                MglaMemoryConfig.addWatched(currentAccount, uid);
                added++;
            }
            if (added > 0) {
                MglaMemoryExtractor.kick();
                rebuildSources();
                reloadOverview();
            }
        });
        presentFragment(picker);
    }

    // endregion

    private void reloadVisible() {
        reloadOverview();
        rebuildSources();
        if (section == 1) {
            reloadFacts();
        } else if (section == 2) {
            reloadGraph();
        }
    }

    private void reloadAll() {
        reloadOverview();
        if (memoryPageBuilt) {
            reloadFacts();
        }
        if (linksPageBuilt) {
            reloadGraph();
        }
        rebuildSources();
    }

    private void reloadOverview() {
        if (resumeGlow == null) {
            return;
        }
        MglaMemoryEngine.overview(currentAccount, this::bindOverview);
    }

    // region вид

    private int accent() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private int accentSoft(int alpha) {
        return (accent() & 0x00FFFFFF) | (alpha << 24);
    }

    private int onAccent() {
        return Theme.getColor(Theme.key_featuredStickers_buttonText);
    }

    private LinearLayout card(android.content.Context context) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(MglaUi.createBlockBackground());
        return block;
    }

    private View hairline(android.content.Context context) {
        View line = new View(context);
        line.setBackgroundColor(accent());
        return line;
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bg.setStroke(dp(1), accentSoft(0x55));
        return bg;
    }

    private TextView accentButton(android.content.Context context, String text, Runnable action) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(onAccent());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        button.setBackground(bg);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView title(android.content.Context context, String text, int size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }

    private TextView body(android.content.Context context, String text, int size, boolean strong) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        if (strong) {
            view.setTypeface(AndroidUtilities.bold());
        }
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }

    private TextView label(android.content.Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        return view;
    }

    private TextView miniLabel(android.content.Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(accent());
        return view;
    }

    private TextView sectionTitle(android.content.Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }

    private LinearLayout column(android.content.Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private HorizontalScrollView horizontal(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private TextView chip(String text, boolean selected) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        styleChip(chip, selected);
        return chip;
    }

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(selected ? accentSoft(0x22) : Theme.getColor(Theme.key_windowBackgroundWhite));
        if (selected) {
            bg.setStroke(dp(1), accentSoft(0x66));
        }
        chip.setBackground(bg);
        chip.setTextColor(selected ? accent() : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    private void addAction(LinearLayout row, String text, Runnable action) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        button.setTextColor(accent());
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setOnClickListener(v -> action.run());
        row.addView(button);
    }

    private static boolean isHot(String type) {
        return MglaMemoryStorage.TYPE_DECISION.equals(type)
            || MglaMemoryStorage.TYPE_PROMISE.equals(type)
            || MglaMemoryStorage.TYPE_EVENT.equals(type);
    }

    /** «ОБЕЩАНИЕ · Сегодня» — mid-dot, чтобы слова не слипались. */
    private static String joinDot(String left, String right) {
        if (TextUtils.isEmpty(left)) {
            return right == null ? "" : right;
        }
        if (TextUtils.isEmpty(right)) {
            return left;
        }
        return left + " · " + right;
    }

    private static String relativeDay(long sec) {
        if (sec <= 0) {
            return "";
        }
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(sec * 1000L);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "Сегодня";
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "Вчера";
        }
        return new SimpleDateFormat("d MMM", new Locale("ru")).format(new Date(sec * 1000L));
    }

    private static void formatBold(TextView view, String text) {
        if (text == null) {
            view.setText("");
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int idx = 0;
        while (idx < text.length()) {
            int start = text.indexOf("**", idx);
            if (start < 0) {
                sb.append(text.substring(idx));
                break;
            }
            int end = text.indexOf("**", start + 2);
            if (end < 0) {
                sb.append(text.substring(idx));
                break;
            }
            sb.append(text, idx, start);
            int boldStart = sb.length();
            sb.append(text, start + 2, end);
            sb.setSpan(new StyleSpan(Typeface.BOLD), boldStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx = end + 2;
        }
        view.setText(sb);
    }

    // endregion
}
