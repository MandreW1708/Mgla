package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaChatDna;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * Mgla: Chat DNA — живой визуальный профиль разговора за выбранный период.
 */
public class MglaChatDnaActivity extends BaseFragment {

    private static final int DAY_SEC = 86400;
    private static final int TOPIC_COUNT = 3;

    private final long dialogId;
    private final long periodStartSec;
    private final long periodEndSec;
    private final String periodLabel;

    private LinearLayout rootLayout;
    private LinearLayout statsBlock;
    private LinearLayout topicsBlock;
    private TextView topicsHeader;
    private ActivityChartView chartView;
    private TextView analysisView;
    private TextView topicsProviderView;
    private final String[] statValues = new String[5];
    private final TextView[] statViews = new TextView[5];
    private final TextView[] topicViews = new TextView[TOPIC_COUNT];

    /** Точка входа из меню чата: сначала диалог выбора периода, потом экран. */
    public static void openWithPeriodPicker(BaseFragment fragment, long dialogId) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        String[] items = {"За день", "За неделю", "За месяц", "Произвольно…"};
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chat DNA — период анализа");
        builder.setItems(items, (dialog, which) -> {
            long now = System.currentTimeMillis() / 1000L;
            switch (which) {
                case 0:
                    open(fragment, dialogId, now - DAY_SEC, now, "последние 24 часа");
                    break;
                case 1:
                    open(fragment, dialogId, now - 7L * DAY_SEC, now, "последние 7 дней");
                    break;
                case 2:
                    open(fragment, dialogId, now - 30L * DAY_SEC, now, "последние 30 дней");
                    break;
                case 3:
                    showCustomPeriodPicker(fragment, dialogId);
                    break;
            }
        });
        builder.setNegativeButton("Отмена", null);
        fragment.showDialog(builder.create());
    }

    private static void open(BaseFragment fragment, long dialogId, long startSec, long endSec, String label) {
        fragment.presentFragment(new MglaChatDnaActivity(dialogId, startSec, endSec, label));
    }

    /** Произвольный период: два андроидовских DatePicker'а (с / по). */
    private static void showCustomPeriodPicker(BaseFragment fragment, long dialogId) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);

        TextView fromLabel = new TextView(context);
        fromLabel.setText("С:");
        fromLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        fromLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        box.addView(fromLabel);
        DatePicker fromPicker = new DatePicker(context);
        Calendar defFrom = Calendar.getInstance();
        defFrom.add(Calendar.DAY_OF_YEAR, -6);
        fromPicker.init(defFrom.get(Calendar.YEAR), defFrom.get(Calendar.MONTH), defFrom.get(Calendar.DAY_OF_MONTH), null);
        box.addView(fromPicker);

        TextView toLabel = new TextView(context);
        toLabel.setText("По:");
        toLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        toLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        box.addView(toLabel);
        DatePicker toPicker = new DatePicker(context);
        Calendar defTo = Calendar.getInstance();
        toPicker.init(defTo.get(Calendar.YEAR), defTo.get(Calendar.MONTH), defTo.get(Calendar.DAY_OF_MONTH), null);
        box.addView(toPicker);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(box);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Произвольный период");
        builder.setView(scrollView);
        builder.setPositiveButton("Готово", (dialog, which) -> {
            Calendar from = Calendar.getInstance();
            from.set(fromPicker.getYear(), fromPicker.getMonth(), fromPicker.getDayOfMonth(), 0, 0, 0);
            Calendar to = Calendar.getInstance();
            to.set(toPicker.getYear(), toPicker.getMonth(), toPicker.getDayOfMonth(), 23, 59, 59);
            long startMillis = Math.min(from.getTimeInMillis(), to.getTimeInMillis());
            long endMillis = Math.max(from.getTimeInMillis(), to.getTimeInMillis());
            String label = formatDate(fromPicker) + " — " + formatDate(toPicker);
            open(fragment, dialogId, startMillis / 1000L, endMillis / 1000L, label);
        });
        builder.setNegativeButton("Отмена", null);
        fragment.showDialog(builder.create());
    }

    private static String formatDate(DatePicker picker) {
        return String.format(Locale.getDefault(), "%d.%02d.%d",
            picker.getDayOfMonth(), picker.getMonth() + 1, picker.getYear());
    }

    public MglaChatDnaActivity(long dialogId, long periodStartSec, long periodEndSec, String periodLabel) {
        this.dialogId = dialogId;
        this.periodStartSec = periodStartSec;
        this.periodEndSec = periodEndSec;
        this.periodLabel = periodLabel;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Chat DNA");
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

        rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Заголовок с периодом анализа
        rootLayout.addView(createHeaderBlock(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        // Статистика
        statsBlock = createBlock(context, "Профиль");
        String[] statLabels = {
            "💬 Сообщений",
            "😂 Реакций",
            "📸 Фото",
            "🎤 Голосовых",
            "🔗 Ссылок"
        };
        for (int i = 0; i < statLabels.length; i++) {
            statsBlock.addView(createStatRow(context, statLabels[i], i), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        rootLayout.addView(statsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        // Активность (график)
        LinearLayout activityBlock = createBlock(context, "Активность");
        TextView chartSubtitle = new TextView(context);
        chartSubtitle.setText("Сколько сообщений приходится на каждый час дня");
        chartSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        chartSubtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        activityBlock.addView(chartSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 4));
        chartView = new ActivityChartView(context);
        activityBlock.addView(chartView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(88), 12, 0, 12, 12));
        rootLayout.addView(activityBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        // Главные темы
        topicsBlock = createBlock(context, null);
        topicsHeader = new TextView(context);
        topicsHeader.setText("Главные темы");
        topicsHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        topicsHeader.setTypeface(AndroidUtilities.bold());
        topicsHeader.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        topicsHeader.setPadding(dp(21), dp(10), dp(21), dp(2));
        topicsBlock.addView(topicsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (int i = 0; i < TOPIC_COUNT; i++) {
            topicViews[i] = createTopicRow(context, i);
            topicsBlock.addView(topicViews[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }


        topicsProviderView = new TextView(context);
        topicsProviderView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        topicsProviderView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        topicsProviderView.setGravity(Gravity.RIGHT);
        topicsBlock.addView(topicsProviderView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 0, 21, 12));
        rootLayout.addView(topicsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        // Характер общения
        LinearLayout analysisBlock = createBlock(context, "Характер общения");
        analysisView = new TextView(context);
        analysisView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        analysisView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        analysisView.setLineSpacing(dp(4), 1);
        analysisBlock.addView(analysisView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 0, 21, 14));
        rootLayout.addView(analysisBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        fragmentView = scrollView;

        loadDna();
        return fragmentView;
    }

    private void loadDna() {
        if (analysisView != null) {
            analysisView.setText("Анализируем переписку…");
        }
        if (topicsProviderView != null) {
            topicsProviderView.setText("Подбор главных слов…");
        }
        MglaChatDna.collect(currentAccount, dialogId, periodStartSec, periodEndSec, new MglaChatDna.Callback() {
            @Override
            public void onStats(MglaChatDna.Stats stats) {
                if (rootLayout == null) {
                    return;
                }
                bindStats(stats);
                bindAnalysis(stats);
                if (chartView != null) {
                    chartView.setValues(stats.hourly);
                }
            }

            @Override
            public void onResult(MglaChatDna.Stats stats, ArrayList<String> topics, String provider) {
                if (rootLayout == null) {
                    return;
                }
                bindTopics(topics, provider);
            }
        });
    }

    private void bindStats(MglaChatDna.Stats stats) {
        statValues[0] = formatNumber(stats.totalMessages);
        statValues[1] = formatNumber(stats.reactions);
        statValues[2] = formatNumber(stats.photos);
        statValues[3] = formatNumber(stats.voice);
        statValues[4] = formatNumber(stats.links);
        for (int i = 0; i < statViews.length; i++) {
            if (statViews[i] != null) {
                statViews[i].setText(statValues[i]);
            }
        }
    }

    private void bindTopics(ArrayList<String> topics, String provider) {
        boolean dictionary = provider != null && provider.startsWith("Словарь");
        if (topicsHeader != null) {
            topicsHeader.setText(dictionary ? "Главные слова" : "Главные темы");
        }
        for (int i = 0; i < topicViews.length; i++) {
            String title = topics != null && i < topics.size() ? topics.get(i) : "—";
            topicViews[i].setText((i + 1) + ". " + title);
        }
        if (topicsProviderView != null) {
            topicsProviderView.setText("Источник: " + provider);
        }
    }

    private void bindAnalysis(MglaChatDna.Stats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("• Этот чат обычно оживает в ")
            .append(String.format(Locale.US, "%02d:00–%02d:00", stats.peakHourFrom, stats.peakHourTo == 23 ? 23 : stats.peakHourTo)).append(".\n");
        if (stats.usualDailyCount > 0) {
            int delta = (int) ((stats.todayCount - stats.usualDailyCount) * 100f / stats.usualDailyCount);
            if (delta >= 0) {
                sb.append("• Сегодня активность на ").append(delta).append("% выше обычной.\n");
            } else {
                sb.append("• Сегодня активность на ").append(-delta).append("% ниже обычной.\n");
            }
        }
        if (stats.recentFocus != null) {
            sb.append("• Последние 30 сообщений преимущественно посвящены теме «")
                .append(stats.recentFocus).append("».\n");
        }
        if (stats.mostActiveTodayName != null) {
            sb.append("• Самый активный участник сегодня — ").append(stats.mostActiveTodayName).append(".\n");
        }
        if (stats.avgWords > 0) {
            sb.append("• Средняя длина сообщения: ").append(stats.avgWords).append(" слов.\n");
        }
        if (stats.typicalReplySeconds > 0) {
            sb.append("• Типичный ответ: ").append(formatDuration(stats.typicalReplySeconds)).append(".");
        }
        analysisView.setText(sb.toString().trim());
    }

    // ——— Вспомогательные вью ———

    private View createHeaderBlock(Context context) {
        LinearLayout block = createBlock(context, null);
        TextView title = new TextView(context);
        title.setText("CHAT DNA");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        block.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 4));

        TextView subtitle = new TextView(context);
        subtitle.setText("Живой профиль разговора");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setGravity(Gravity.CENTER);
        block.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));

        TextView period = new TextView(context);
        period.setText("Период: " + periodLabel);
        period.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        period.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        period.setGravity(Gravity.CENTER);
        block.addView(period, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        return block;
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

        if (title != null) {
            HeaderCell header = new HeaderCell(context, 22);
            header.setBackground(null);
            header.setText(title);
            block.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return block;
    }

    private View createStatRow(Context context, String label, int index) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(21), 0, dp(21), 0);
        row.setMinimumHeight(dp(44));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL));

        statViews[index] = new TextView(context);
        statViews[index].setText("0");
        statViews[index].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statViews[index].setTypeface(AndroidUtilities.bold());
        statViews[index].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        row.addView(statViews[index], LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        return row;
    }

    private TextView createTopicRow(Context context, int index) {
        TextView topic = new TextView(context);
        topic.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        topic.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        topic.setGravity(Gravity.CENTER_VERTICAL);
        topic.setSingleLine(true);
        topic.setEllipsize(TextUtils.TruncateAt.END);
        topic.setIncludeFontPadding(false);
        topic.setPadding(dp(21), dp(2), dp(21), dp(2));
        topic.setText("…");
        return topic;
    }

    // ——— Утилиты ———

    private static String formatNumber(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    private static String formatDuration(int seconds) {
        if (seconds < 60) {
            return seconds + " секунд";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " минут";
        }
        return (seconds / 3600) + " часов";
    }

    /**
     * График активности по часам. Слева — сколько сообщений (число + подпись
     * «сообщений» вертикально), снизу — час дня.
     */
    private static class ActivityChartView extends View {

        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path linePath = new Path();
        private final Path fillPath = new Path();
        private final RectF rect = new RectF();
        private int[] values = new int[24];

        ActivityChartView(Context context) {
            super(context);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(dp(1));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(2));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            fillPaint.setStyle(Paint.Style.FILL);
            labelPaint.setTextSize(dp(8));
        }

        void setValues(int[] hourly) {
            if (hourly != null && hourly.length == 24) {
                values = hourly;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            framePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            linePaint.setColor(Theme.getColor(Theme.key_chat_outBubble));
            fillPaint.setColor(Theme.getColor(Theme.key_chat_outBubble));
            fillPaint.setAlpha(48);
            labelPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));

            rect.set(dp(2), dp(2), getWidth() - dp(2), getHeight() - dp(2));
            canvas.drawRoundRect(rect, dp(10), dp(10), framePaint);

            int max = 1;
            for (int v : values) {
                max = Math.max(max, v);
            }

            // Область построения — слева колонка под числа и подпись, снизу — часы.
            RectF plot = new RectF(rect.left + dp(28), rect.top + dp(4), rect.right - dp(4), rect.bottom - dp(14));

            // Слева: максимум сверху, ноль снизу.
            labelPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(String.valueOf(max), rect.left + dp(15), rect.top + dp(10), labelPaint);
            canvas.drawText("0", rect.left + dp(15), rect.bottom - dp(14), labelPaint);

            // Вертикальная подпись «сообщений» у левого края.
            labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.save();
            canvas.translate(rect.left + dp(8), plot.centerY());
            canvas.rotate(-90);
            canvas.drawText("сообщений", 0, 0, labelPaint);
            canvas.restore();

            float step = (plot.width() - dp(8)) / 23f;
            float baseY = plot.bottom - dp(4);
            float height = plot.height() - dp(8);

            linePath.reset();
            fillPath.reset();
            for (int i = 0; i < 24; i++) {
                float x = plot.left + dp(4) + step * i;
                float y = baseY - height * values[i] / max;
                if (i == 0) {
                    linePath.moveTo(x, y);
                    fillPath.moveTo(x, baseY);
                    fillPath.lineTo(x, y);
                } else {
                    linePath.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
            }
            fillPath.lineTo(plot.left + dp(4) + step * 23, baseY);
            fillPath.close();

            canvas.drawPath(fillPath, fillPaint);
            canvas.drawPath(linePath, linePaint);

            // Снизу: часы дня каждые 6 часов + подпись «час».
            for (int i = 0; i < 24; i += 6) {
                float x = plot.left + dp(4) + step * i;
                canvas.drawText(String.format(Locale.US, "%02d", i), x, rect.bottom - dp(3), labelPaint);
            }
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("час", rect.right - dp(4), rect.bottom - dp(3), labelPaint);
        }
    }
}
