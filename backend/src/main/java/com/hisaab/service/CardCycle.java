package com.hisaab.service;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Billing cycle maths for a credit card with a fixed statement day and due day each month.
 *
 * @param lastStatement  most recent statement date on or before today
 * @param nextStatement  next statement date (end of the current cycle)
 * @param cycleStart     first day of the current (unbilled) cycle
 * @param dueDate        the next payment due date that hasn't passed yet
 * @param dueForStatement the statement that {@code dueDate} belongs to
 */
public record CardCycle(LocalDate lastStatement, LocalDate nextStatement, LocalDate cycleStart,
                        LocalDate dueDate, LocalDate dueForStatement) {

    public static CardCycle of(int statementDay, int dueDay, LocalDate today) {
        LocalDate thisMonth = onDay(YearMonth.from(today), statementDay);
        LocalDate last = thisMonth.isAfter(today) ? onDay(YearMonth.from(today).minusMonths(1), statementDay) : thisMonth;
        LocalDate next = onDay(YearMonth.from(last).plusMonths(1), statementDay);
        LocalDate dueLast = dueAfter(last, dueDay);
        if (!dueLast.isBefore(today)) {
            return new CardCycle(last, next, last.plusDays(1), dueLast, last);
        }
        return new CardCycle(last, next, last.plusDays(1), dueAfter(next, dueDay), next);
    }

    /** Due date for a statement: the first occurrence of dueDay strictly after the statement date. */
    static LocalDate dueAfter(LocalDate statement, int dueDay) {
        LocalDate sameMonth = onDay(YearMonth.from(statement), dueDay);
        return sameMonth.isAfter(statement) ? sameMonth : onDay(YearMonth.from(statement).plusMonths(1), dueDay);
    }

    static LocalDate onDay(YearMonth ym, int day) {
        return ym.atDay(Math.min(day, ym.lengthOfMonth()));
    }
}
