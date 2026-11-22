package com.hisaab.statements.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The statement's closing figure next to what the app shows for the same moment, so I can see whether the
 * import will leave the account agreeing with the bank. Null when the statement ends before my starting
 * balance (then it can't change anything).
 */
public record BalanceCheckResponse(LocalDate at, BigDecimal statement, BigDecimal appNow) {}
