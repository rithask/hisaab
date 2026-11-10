package com.hisaab.statement;

import com.hisaab.domain.Enums.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One transaction line read from a statement.
 *
 * @param time        null when the statement only gives a date (or a placeholder like HDFC's 00:00)
 * @param balance     running balance after this line, for bank statements that print one
 * @param uncertain   why this row should be double-checked (e.g. the running balance didn't add up), or null
 */
public record StatementRow(LocalDate date, LocalTime time, String description, String counterparty, String reference,
                           Direction direction, BigDecimal amount, BigDecimal balance, boolean cardPayment,
                           String uncertain) {}
