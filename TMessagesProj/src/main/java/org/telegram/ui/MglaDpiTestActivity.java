package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.dpi.MglaDpiConfig;
import org.telegram.utils.dpi.MglaDpiTester;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mgla -> Обход блокировок -> Подбор стратегий.
 * Аналог «Подбора стратегий» ByeByeDPI: проверяет каждую стратегию на серверах Telegram
 * (MTProto) и сайтах Telegram, показывает результаты и позволяет применить лучшую.
 */
public class MglaDpiTestActivity extends BaseFragment {

    private static final int MENU_COPY = 1;
    private static final int MENU_SETTINGS = 2;

    private final MglaDpiTester tester = new MglaDpiTester();
    private final Map<MglaDpiTester.StrategyResult, ResultCard> cards = new HashMap<>();
    private final List<MglaDpiTester.StrategyResult> results = new ArrayList<>();

    private TextView startButton;
    private TextView statusView;
    private LinearLayout resultsContainer;
    private boolean destroyed;

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (tester.isRunning()) {
            tester.cancel();
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Подбор стратегий");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_COPY, R.drawable.msg_copy);
        menu.addItem(MENU_SETTINGS, R.drawable.msg_settings);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_COPY) {
                    copyResults();
                } else if (id == MENU_SETTINGS) {
                    showSettings();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        startButton = new TextView(context);
        startButton.setGravity(Gravity.CENTER);
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        startButton.setTypeface(AndroidUtilities.bold());
        startButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        startButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(24),
            Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        startButton.setPadding(dp(28), 0, dp(28), 0);
        startButton.setOnClickListener(v -> toggleTest());
        root.addView(startButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        statusView = new TextView(context);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));

        resultsContainer = new LinearLayout(context);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, AndroidUtilities.navigationBarHeight + 16));

        updateButton();
        statusView.setText("Проверяются серверы Telegram (MTProto) и сайты Telegram через каждую стратегию.\n"
            + "Первой идёт проверка без обхода — по ней видно, есть ли блокировка.\n"
            + "Во время проверки связь в клиенте может прерываться.");

        fragmentView = scrollView;
        return fragmentView;
    }

    // ---------------------------------------------------------------- test control

    private void toggleTest() {
        if (tester.isRunning()) {
            tester.cancel();
            statusView.setText("Остановка…");
            return;
        }
        results.clear();
        cards.clear();
        resultsContainer.removeAllViews();
        tester.start(new MglaDpiTester.Callback() {
            @Override
            public void onStrategyStarted(MglaDpiTester.StrategyResult result, int index, int total) {
                if (destroyed || fragmentView == null) {
                    return;
                }
                results.add(result);
                ResultCard card = new ResultCard(getContext(), result);
                cards.put(result, card);
                resultsContainer.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
                statusView.setText("Проверка " + (index + 1) + " из " + total);
            }

            @Override
            public void onProgress(MglaDpiTester.StrategyResult result) {
                if (destroyed) {
                    return;
                }
                ResultCard card = cards.get(result);
                if (card != null) {
                    card.update();
                }
            }

            @Override
            public void onFinished(boolean cancelled, List<MglaDpiTester.StrategyResult> all) {
                if (destroyed || fragmentView == null || getParentActivity() == null) {
                    return;
                }
                updateButton();
                showSortedResults();
                onTestFinished(cancelled, all);
            }
        });
        updateButton();
    }

    private void updateButton() {
        if (startButton != null) {
            startButton.setText(tester.isRunning() ? "ОСТАНОВИТЬ" : "НАЧАТЬ ПРОВЕРКУ");
        }
    }

    private void showSortedResults() {
        resultsContainer.removeAllViews();
        for (MglaDpiTester.StrategyResult result : MglaDpiTester.sorted(results)) {
            ResultCard card = cards.get(result);
            if (card != null) {
                card.update();
                resultsContainer.addView(card);
            }
        }
    }

    private void onTestFinished(boolean cancelled, List<MglaDpiTester.StrategyResult> all) {
        MglaDpiTester.StrategyResult baseline = null;
        MglaDpiTester.StrategyResult best = null;
        for (MglaDpiTester.StrategyResult result : MglaDpiTester.sorted(all)) {
            if (result.isBaseline()) {
                baseline = result;
            } else if (best == null && result.error == null) {
                best = result;
            }
        }
        if (cancelled) {
            statusView.setText("Проверка остановлена");
            return;
        }
        statusView.setText("Проверка завершена");
        if (best == null) {
            return;
        }

        int mtTotal = best.getMtprotoTotal();
        boolean baselineOk = baseline != null && baseline.getMtprotoOk() == baseline.getMtprotoTotal() && baseline.getMtprotoTotal() > 0;
        MglaDpiConfig.setTestSummary("MTProto " + best.getMtprotoOk() + "/" + mtTotal
            + " · всего " + best.getOk() + "/" + best.getTotal()
            + (baseline != null ? " · без обхода MTProto " + baseline.getMtprotoOk() + "/" + baseline.getMtprotoTotal() : ""));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Подбор стратегий");
        if (best.getMtprotoOk() == 0) {
            builder.setMessage("Ни одна стратегия не дала связи с серверами Telegram.\n\n"
                + "Если и сайты Telegram не открылись ни с одной стратегией, провайдер, скорее всего, "
                + "отбрасывает трафик к этим IP целиком, а не анализирует его через DPI. "
                + "Такую блокировку дезинхронизация не снимает: нужен внешний сервер (прокси или VPN).");
            builder.setPositiveButton("ОК", null);
        } else {
            final MglaDpiTester.StrategyResult chosen = best;
            String message = "Лучшая стратегия:\n" + best.command + "\n\n"
                + "MTProto: " + best.getMtprotoOk() + "/" + mtTotal
                + ", сайты: " + (best.getOk() - best.getMtprotoOk()) + "/" + (best.getTotal() - mtTotal);
            if (baselineOk) {
                message += "\n\nСерверы Telegram отвечают и без обхода — сейчас MTProto не блокируется.";
            }
            builder.setMessage(message);
            boolean enabled = MglaDpiBypass.getInstance().isEnabled();
            builder.setPositiveButton(enabled ? "Применить" : "Применить и включить", (d, w) -> applyStrategy(chosen.command));
            builder.setNegativeButton("Отмена", null);
        }
        showDialog(builder.create());
    }

    private void applyStrategy(String command) {
        MglaDpiBypass bypass = MglaDpiBypass.getInstance();
        String error = bypass.applyStrategy(command);
        if (error == null && !bypass.isEnabled() && !bypass.setEnabled(true)) {
            error = bypass.getLastError();
        }
        if (error != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Стратегия не применена");
            builder.setMessage(error);
            builder.setPositiveButton("ОК", null);
            showDialog(builder.create());
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Стратегия применена").show();
        }
    }

    private void copyResults() {
        if (results.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (MglaDpiTester.StrategyResult result : MglaDpiTester.sorted(results)) {
            if (result.isBaseline()) {
                continue;
            }
            sb.append(result.command).append("  # ").append(result.getOk()).append('/').append(result.getTotal()).append('\n');
        }
        AndroidUtilities.addToClipboard(sb.toString().trim());
        BulletinFactory.of(this).createCopyBulletin("Результаты скопированы").show();
    }

    // ---------------------------------------------------------------- settings

    private void showSettings() {
        if (getParentActivity() == null || tester.isRunning()) {
            return;
        }
        String[] items = {
            "Количество запросов к цели: " + MglaDpiConfig.getTestRequests(),
            "Максимум параллельных запросов: " + MglaDpiConfig.getTestParallel(),
            "Таймаут ответа, с: " + MglaDpiConfig.getTestTimeoutSec(),
            "SNI фейк-пакетов: " + MglaDpiConfig.getFakeSni(),
            "Свой список стратегий: " + (MglaDpiConfig.isTestCustomEnabled() ? "вкл" : "выкл"),
            "Список стратегий…",
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Настройки");
        builder.setItems(items, (dialog, which) -> {
            switch (which) {
                case 0:
                    MglaUi.showInputDialog(this, "Количество запросов к цели",
                        "Больше — точнее, но дольше (" + MglaDpiConfig.TEST_REQUESTS_MIN + "–" + MglaDpiConfig.TEST_REQUESTS_MAX + ")",
                        String.valueOf(MglaDpiConfig.getTestRequests()), false, true,
                        text -> MglaDpiConfig.setTestRequests(parseInt(text, MglaDpiConfig.getTestRequests())));
                    break;
                case 1:
                    MglaUi.showInputDialog(this, "Максимум параллельных запросов",
                        "Больше — быстрее, но может снизить стабильность (" + MglaDpiConfig.TEST_PARALLEL_MIN + "–" + MglaDpiConfig.TEST_PARALLEL_MAX + ")",
                        String.valueOf(MglaDpiConfig.getTestParallel()), false, true,
                        text -> MglaDpiConfig.setTestParallel(parseInt(text, MglaDpiConfig.getTestParallel())));
                    break;
                case 2:
                    MglaUi.showInputDialog(this, "Таймаут ответа, с",
                        "Больше — медленнее, но точнее на плохой сети (" + MglaDpiConfig.TEST_TIMEOUT_MIN + "–" + MglaDpiConfig.TEST_TIMEOUT_MAX + ")",
                        String.valueOf(MglaDpiConfig.getTestTimeoutSec()), false, true,
                        text -> MglaDpiConfig.setTestTimeoutSec(parseInt(text, MglaDpiConfig.getTestTimeoutSec())));
                    break;
                case 3:
                    MglaDpiBypassActivity.showFakeSniDialog(this);
                    break;
                case 4:
                    MglaDpiConfig.setTestCustomEnabled(!MglaDpiConfig.isTestCustomEnabled());
                    showSettings();
                    break;
                case 5:
                    MglaUi.showInputDialog(this, "Список стратегий",
                        "По одной стратегии на строку (формат ByeByeDPI). Используется, когда включён «Свой список стратегий».",
                        MglaDpiConfig.getTestCustomList(), true, false,
                        MglaDpiConfig::setTestCustomList);
                    break;
            }
        });
        showDialog(builder.create());
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- result card

    private class ResultCard extends LinearLayout {

        private final MglaDpiTester.StrategyResult result;
        private final ScoreBar bar;
        private final TextView countView;
        private final TextView errorView;
        private final TextView detailsToggle;
        private final LinearLayout details;
        private boolean expanded;

        ResultCard(Context context, MglaDpiTester.StrategyResult result) {
            super(context);
            this.result = result;
            setOrientation(VERTICAL);
            setBackground(MglaUi.createBlockBackground());
            setPadding(dp(16), dp(14), dp(16), dp(10));

            TextView commandView = new TextView(context);
            commandView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            commandView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            if (result.isBaseline()) {
                commandView.setText("Без обхода (проверка блокировки)");
                commandView.setTypeface(AndroidUtilities.bold());
            } else {
                commandView.setText(result.command);
                commandView.setTypeface(Typeface.MONOSPACE);
            }
            addView(commandView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            bar = new ScoreBar(context);
            row.addView(bar, LayoutHelper.createLinear(0, 6, 1f, Gravity.CENTER_VERTICAL));
            countView = new TextView(context);
            countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            countView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            row.addView(countView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

            errorView = new TextView(context);
            errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            errorView.setVisibility(GONE);
            addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

            detailsToggle = new TextView(context);
            detailsToggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            detailsToggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
            detailsToggle.setPadding(0, dp(8), 0, dp(4));
            detailsToggle.setOnClickListener(v -> {
                expanded = !expanded;
                update();
            });
            addView(detailsToggle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            details = new LinearLayout(context);
            details.setOrientation(VERTICAL);
            addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (!result.isBaseline()) {
                setOnClickListener(v -> showCardOptions());
            }
            update();
        }

        void update() {
            int total = result.getTotal();
            int ok = result.getOk();
            bar.setProgress(total > 0 ? ok / (float) total : 0f);
            String count = ok + "/" + total;
            if (!result.finished && result.error == null) {
                count += " · " + result.getDone() + "/" + total;
            }
            countView.setText(count);
            if (!TextUtils.isEmpty(result.error)) {
                errorView.setText(result.error);
                errorView.setVisibility(VISIBLE);
            } else {
                errorView.setVisibility(GONE);
            }
            detailsToggle.setText(expanded ? "Скрыть детали ▴" : "Показать детали ▾");
            details.removeAllViews();
            if (expanded) {
                for (MglaDpiTester.TargetResult target : result.targets) {
                    details.addView(createDetailRow(target), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
            }
        }

        private View createDetailRow(MglaDpiTester.TargetResult target) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView name = new TextView(getContext());
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(target.name);
            row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            TextView value = new TextView(getContext());
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            value.setTextColor(Theme.getColor(target.getOk() > 0 ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_windowBackgroundWhiteGrayText));
            value.setText(target.getOk() + "/" + target.total);
            row.addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));
            return row;
        }

        private void showCardOptions() {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(result.getOk() + "/" + result.getTotal());
            builder.setMessage(result.command);
            builder.setPositiveButton(MglaDpiBypass.getInstance().isEnabled() ? "Применить" : "Применить и включить",
                (d, w) -> applyStrategy(result.command));
            builder.setNeutralButton("Копировать", (d, w) -> {
                AndroidUtilities.addToClipboard(result.command);
                BulletinFactory.of(MglaDpiTestActivity.this).createCopyBulletin("Стратегия скопирована").show();
            });
            builder.setNegativeButton("Отмена", null);
            showDialog(builder.create());
        }
    }

    private static class ScoreBar extends View {

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;

        ScoreBar(Context context) {
            super(context);
            trackPaint.setColor(Theme.getColor(Theme.key_divider));
            fillPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        }

        void setProgress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, radius, radius, trackPaint);
            if (progress > 0) {
                rect.set(0, 0, Math.max(getHeight(), getWidth() * progress), getHeight());
                canvas.drawRoundRect(rect, radius, radius, fillPaint);
            }
        }
    }
}
