package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @param status  ACTIVE, NEW (started in the last ~2 months) or STOPPED (the last payment didn't come)
 * @param monthly the cost per month (a yearly plan counts a twelfth)
 */
public record SubscriptionResponse(String key, String name, Long accountId, String account, String category, String categoryColor,
                                   BigDecimal amount, String every, BigDecimal monthly, BigDecimal yearly, LocalDate since,
                                   LocalDate lastPaid, LocalDate nextDue, int timesPaid, String status, PriceChangeResponse priceChange,
                                   BigDecimal paidThisYear) {}
