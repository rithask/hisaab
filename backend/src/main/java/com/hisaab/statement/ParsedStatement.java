package com.hisaab.statement;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Institution;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything read from one statement.
 *
 * @param opening   opening balance (bank) or previous outstanding (card), if printed
 * @param closing   closing balance (bank) or total amount due (card), if printed
 * @param checks    self-checks against totals the statement prints, so a mis-read row is caught before import
 */
public record ParsedStatement(String parser, String label, Institution institution, AccountType accountType, String last4,
                              LocalDate from, LocalDate to, BigDecimal opening, BigDecimal closing, BigDecimal creditLimit,
                              List<StatementRow> rows, List<Check> checks) {

    public record Check(String label, boolean ok, String detail) {}
}
