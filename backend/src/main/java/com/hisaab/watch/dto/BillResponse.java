package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @param key       merchant key (for "not a bill"), or "card:<accountId>" for a card's due date
 * @param every     human interval: "Every month", "Every week"…
 * @param overdue   expected but not seen yet (within a grace period)
 */
public record BillResponse(String key, String name, Long accountId, String account, String category, String categoryColor,
                           BigDecimal amount, boolean amountVaries, LocalDate due, int daysLeft, boolean overdue, String every,
                           int timesSeen, LocalDate lastPaid, boolean card) {}
