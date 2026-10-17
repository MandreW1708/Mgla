package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaTask;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;

public class MglaTasksCalendarView extends LinearLayout {

    public interface Delegate {
        void onDaySelected(long dayStartMillis);
    }

    private static final String[] MONTHS = {
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    };
    private static final String[] WEEKDAYS = { "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс" };
    private static final int GRID_CELLS = 42;

    private Delegate delegate;
    private final Calendar calendar = Calendar.getInstance();
    private int shownYear;
    private int shownMonth;
    private long selectedDay;
    private long todayDay;
    private final HashSet<Long> daysWithTasks = new HashSet<>();
    private final DayCell[] cells = new DayCell[GRID_CELLS];
    private final TextView titleView;

    public MglaTasksCalendarView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        TextView prevButton = makeNavButton(context, "‹");
        prevButton.setOnClickListener(v -> shiftMonth(-1));
        header.addView(prevButton, LayoutHelper.createLinear(44, 44));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        header.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView nextButton = makeNavButton(context, "›");
        nextButton.setOnClickListener(v -> shiftMonth(1));
        header.addView(nextButton, LayoutHelper.createLinear(44, 44));

        LinearLayout weekdays = new LinearLayout(context);
        weekdays.setOrientation(HORIZONTAL);
        addView(weekdays, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28, 8, 0, 8, 0));
        for (String name : WEEKDAYS) {
            TextView dayName = new TextView(context);
            dayName.setText(name);
            dayName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            dayName.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            dayName.setGravity(Gravity.CENTER);
            weekdays.addView(dayName, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }

        for (int row = 0; row < 6; row++) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(HORIZONTAL);
            addView(rowLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 8, 0, 8, 0));
            for (int col = 0; col < 7; col++) {
                DayCell cell = new DayCell(context);
                cells[row * 7 + col] = cell;
                cell.setOnClickListener(v -> onCellClicked((DayCell) v));
                rowLayout.addView(cell, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
            }
        }

        calendar.setTimeInMillis(System.currentTimeMillis());
        shownYear = calendar.get(Calendar.YEAR);
        shownMonth = calendar.get(Calendar.MONTH);
        todayDay = dayStartOf(calendar.getTimeInMillis());
        selectedDay = todayDay;
        rebuild();
    }

    private TextView makeNavButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        button.setGravity(Gravity.CENTER);
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        return button;
    }

    public void setDelegate(Delegate d) {
        delegate = d;
    }

    public long getSelectedDay() {
        return selectedDay;
    }

    public void goToday() {
        todayDay = dayStartOf(System.currentTimeMillis());
        selectedDay = todayDay;
        calendar.setTimeInMillis(todayDay);
        shownYear = calendar.get(Calendar.YEAR);
        shownMonth = calendar.get(Calendar.MONTH);
        rebuild();
    }

    public void setTasks(ArrayList<MglaTask> tasks) {
        daysWithTasks.clear();
        for (MglaTask task : tasks) {
            if (task.dueAt > 0) {
                daysWithTasks.add(dayStartOf(task.dueAt));
            }
        }
        rebuild();
    }

    private void shiftMonth(int delta) {
        calendar.clear();
        calendar.set(shownYear, shownMonth, 1);
        calendar.add(Calendar.MONTH, delta);
        shownYear = calendar.get(Calendar.YEAR);
        shownMonth = calendar.get(Calendar.MONTH);
        rebuild();
    }

    private void onCellClicked(DayCell cell) {
        if (cell.dayMillis == 0) {
            return;
        }
        selectedDay = cell.dayMillis;
        if (!cell.inMonth) {
            calendar.setTimeInMillis(cell.dayMillis);
            shownYear = calendar.get(Calendar.YEAR);
            shownMonth = calendar.get(Calendar.MONTH);
        }
        rebuild();
        if (delegate != null) {
            delegate.onDaySelected(selectedDay);
        }
    }

    private long dayStartOf(long time) {
        calendar.setTimeInMillis(time);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private void rebuild() {
        titleView.setText(MONTHS[shownMonth] + " " + shownYear);

        calendar.clear();
        calendar.set(shownYear, shownMonth, 1);
        int firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        int offset = (firstDayOfWeek - Calendar.MONDAY + 7) % 7;
        calendar.add(Calendar.DAY_OF_MONTH, -offset);

        for (int a = 0; a < GRID_CELLS; a++) {
            DayCell cell = cells[a];
            cell.dayMillis = calendar.getTimeInMillis();
            cell.dayNumber = calendar.get(Calendar.DAY_OF_MONTH);
            cell.inMonth = calendar.get(Calendar.MONTH) == shownMonth;
            cell.hasTasks = daysWithTasks.contains(cell.dayMillis);
            cell.isToday = cell.dayMillis == todayDay;
            cell.isSelected = cell.dayMillis == selectedDay;
            cell.invalidate();
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private static class DayCell extends View {

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        long dayMillis;
        int dayNumber;
        boolean inMonth;
        boolean hasTasks;
        boolean isToday;
        boolean isSelected;

        DayCell(Context context) {
            super(context);
            textPaint.setTextSize(dp(15));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (dayMillis == 0) {
                return;
            }
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = dp(16);
            int accent = Theme.getColor(Theme.key_chat_messagePanelSend);

            if (isSelected) {
                circlePaint.setStyle(Paint.Style.FILL);
                circlePaint.setColor(accent);
                canvas.drawCircle(cx, cy, radius, circlePaint);
            } else if (isToday) {
                circlePaint.setStyle(Paint.Style.STROKE);
                circlePaint.setStrokeWidth(dp(1.5f));
                circlePaint.setColor(accent);
                canvas.drawCircle(cx, cy, radius - dp(1), circlePaint);
            }

            if (isSelected) {
                textPaint.setColor(0xFFFFFFFF);
                textPaint.setAlpha(255);
            } else {
                textPaint.setColor(Theme.getColor(inMonth ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
                textPaint.setAlpha(inMonth ? 255 : (int) (255 * 0.35f));
            }
            float textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(String.valueOf(dayNumber), cx, textY, textPaint);

            if (hasTasks) {
                circlePaint.setStyle(Paint.Style.FILL);
                circlePaint.setColor(isSelected ? 0xFFFFFFFF : accent);
                canvas.drawCircle(cx, cy + dp(11), dp(2), circlePaint);
            }
        }
    }
}
