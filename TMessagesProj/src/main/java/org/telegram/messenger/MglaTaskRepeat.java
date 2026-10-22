package org.telegram.messenger;

import java.util.Calendar;
import java.util.HashSet;

public class MglaTaskRepeat {

    public static final int UNIT_DAY = 1;
    public static final int UNIT_WEEK = 2;
    public static final int UNIT_MONTH = 3;
    public static final int UNIT_YEAR = 4;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String[] WEEKDAYS_SHORT = { "вс", "пн", "вт", "ср", "чт", "пт", "сб" };

    public static String label(MglaTask task) {
        if (!task.hasRepeat()) {
            return null;
        }
        return label(task.repeatInterval, task.repeatUnit, task.dueAt);
    }

    public static String label(int interval, int unit, long dueAt) {
        if (interval <= 0 || dueAt <= 0) {
            return null;
        }
        int n = interval;
        switch (unit) {
            case UNIT_DAY:
                return n == 1 ? "Каждый день" : "Каждые " + n + " " + plural(n, "день", "дня", "дней");
            case UNIT_WEEK:
                if (n == 1) {
                    Calendar calendar = Calendar.getInstance();
                    calendar.setTimeInMillis(dueAt);
                    return "Каждый " + WEEKDAYS_SHORT[calendar.get(Calendar.DAY_OF_WEEK) - 1];
                }
                return "Каждые " + n + " " + plural(n, "неделя", "недели", "недель");
            case UNIT_MONTH:
                return n == 1 ? "Каждый месяц" : "Каждые " + n + " " + plural(n, "месяц", "месяца", "месяцев");
            case UNIT_YEAR:
                return n == 1 ? "Каждый год" : "Каждые " + n + " " + plural(n, "год", "года", "лет");
            default:
                return null;
        }
    }

    private static String plural(int n, String one, String few, String many) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        switch (n % 10) {
            case 1:
                return one;
            case 2:
            case 3:
            case 4:
                return few;
            default:
                return many;
        }
    }

    /** Первое повторение строго позже timeMs; 0 если повторов нет. */
    public static long nextOccurrenceAfter(MglaTask task, long timeMs) {
        if (!task.hasRepeat()) {
            return 0;
        }
        return firstOccurrenceNotBefore(task, timeMs + 1);
    }

    /** Effective due for list/calendar: dueAt, or remindAt if due was never set. */
    public static long effectiveDueAt(MglaTask task) {
        if (task == null) {
            return 0;
        }
        if (task.dueAt > 0) {
            return task.dueAt;
        }
        return task.remindAt > 0 ? task.remindAt : 0;
    }

    /** Задача (с учётом повторов) попадает на календарный день dayStart (локальная полуночь). */
    public static boolean occursOn(MglaTask task, long dayStart) {
        long due = effectiveDueAt(task);
        if (due <= 0) {
            return false;
        }
        // Compare by calendar day, not dayStart+DAY_MS — DST days are not 24h.
        if (dayStartOf(due) == dayStart) {
            return true;
        }
        if (!task.hasRepeat() || dayStartOf(due) > dayStart) {
            return false;
        }
        long occ = firstOccurrenceNotBefore(task, dayStart);
        return occ > 0 && dayStartOf(occ) == dayStart;
    }

    /** Добавляет в out начала дней всех повторений задачи в диапазоне [rangeStart, rangeEnd). */
    public static void addOccurrenceDays(MglaTask task, long rangeStart, long rangeEnd, HashSet<Long> out) {
        long due = effectiveDueAt(task);
        if (due <= 0) {
            return;
        }
        if (!task.hasRepeat()) {
            if (due >= rangeStart && due < rangeEnd) {
                out.add(dayStartOf(due));
            }
            return;
        }
        long occ = due < rangeStart ? firstOccurrenceNotBefore(task, rangeStart) : due;
        int guard = 0;
        while (occ > 0 && occ < rangeEnd && guard++ < 500) {
            out.add(dayStartOf(occ));
            occ = occurrenceAfter(task, occ);
        }
    }

    private static long firstOccurrenceNotBefore(MglaTask task, long timeMs) {
        if (timeMs <= task.dueAt) {
            return task.dueAt;
        }
        switch (task.repeatUnit) {
            case UNIT_DAY:
            case UNIT_WEEK: {
                long intervalMs = task.repeatInterval * DAY_MS * (task.repeatUnit == UNIT_WEEK ? 7 : 1);
                long k = (timeMs - task.dueAt + intervalMs - 1) / intervalMs;
                return task.dueAt + k * intervalMs;
            }
            case UNIT_MONTH: {
                Calendar target = Calendar.getInstance();
                target.setTimeInMillis(timeMs);
                Calendar occ = Calendar.getInstance();
                occ.setTimeInMillis(task.dueAt);
                int monthsDiff = (target.get(Calendar.YEAR) - occ.get(Calendar.YEAR)) * 12 + (target.get(Calendar.MONTH) - occ.get(Calendar.MONTH));
                long k = Math.max(0, monthsDiff / task.repeatInterval - 1);
                long result = calendarOccurrence(task, Calendar.MONTH, k);
                int guard = 0;
                while (result < timeMs && guard++ < 1000) {
                    k++;
                    result = calendarOccurrence(task, Calendar.MONTH, k);
                }
                return result;
            }
            case UNIT_YEAR: {
                Calendar target = Calendar.getInstance();
                target.setTimeInMillis(timeMs);
                Calendar occ = Calendar.getInstance();
                occ.setTimeInMillis(task.dueAt);
                int yearsDiff = target.get(Calendar.YEAR) - occ.get(Calendar.YEAR);
                long k = Math.max(0, yearsDiff / task.repeatInterval - 1);
                long result = calendarOccurrence(task, Calendar.YEAR, k);
                int guard = 0;
                while (result < timeMs && guard++ < 1000) {
                    k++;
                    result = calendarOccurrence(task, Calendar.YEAR, k);
                }
                return result;
            }
            default:
                return 0;
        }
    }

    private static long occurrenceAfter(MglaTask task, long fromOccurrence) {
        switch (task.repeatUnit) {
            case UNIT_DAY:
                return fromOccurrence + task.repeatInterval * DAY_MS;
            case UNIT_WEEK:
                return fromOccurrence + task.repeatInterval * 7 * DAY_MS;
            case UNIT_MONTH: {
                Calendar occ = Calendar.getInstance();
                Calendar base = Calendar.getInstance();
                base.setTimeInMillis(task.dueAt);
                occ.setTimeInMillis(fromOccurrence);
                int monthsDiff = (occ.get(Calendar.YEAR) - base.get(Calendar.YEAR)) * 12 + (occ.get(Calendar.MONTH) - base.get(Calendar.MONTH));
                long k = Math.max(1, monthsDiff / task.repeatInterval + 1);
                return calendarOccurrence(task, Calendar.MONTH, k);
            }
            case UNIT_YEAR: {
                Calendar occ = Calendar.getInstance();
                Calendar base = Calendar.getInstance();
                base.setTimeInMillis(task.dueAt);
                occ.setTimeInMillis(fromOccurrence);
                int yearsDiff = occ.get(Calendar.YEAR) - base.get(Calendar.YEAR);
                long k = Math.max(1, yearsDiff / task.repeatInterval + 1);
                return calendarOccurrence(task, Calendar.YEAR, k);
            }
            default:
                return 0;
        }
    }

    private static long calendarOccurrence(MglaTask task, int field, long k) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(task.dueAt);
        int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
        calendar.add(field, (int) (k * task.repeatInterval));
        int maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (dayOfMonth > maxDay) {
            calendar.set(Calendar.DAY_OF_MONTH, maxDay);
        }
        return calendar.getTimeInMillis();
    }

    private static long dayStartOf(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
