package com.hisaab.transactions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Something a friend paid for, with my share of it.
 *
 * @param total what they paid altogether
 * @param share my part of it; what goes into my spending, and comes off what they owe me
 * @param where where it was spent (shown as the payee); never learned from
 */
public record PaidByRequest(Long personId, BigDecimal total, BigDecimal share, Long categoryId, String where,
                            Instant occurredAt, String notes, List<String> tags) {}
