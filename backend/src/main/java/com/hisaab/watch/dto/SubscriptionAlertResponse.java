package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Something about a subscription worth a look. The app words it; the fields depend on the kind:
 * PRICE_UP / PRICE_DOWN: {@code previous} → {@code amount} since {@code day}, {@code perYear} = the yearly difference.
 * DOUBLE: {@code amount} charged on {@code otherDay} (from {@code otherAccount}) and again on {@code day} (from {@code account}).
 * NEW: started {@code otherDay}, {@code amount} {@code every}. STOPPED: expected on {@code day}, last paid {@code otherDay}.
 */
public record SubscriptionAlertResponse(String key, String kind, String subscriptionKey, String name, BigDecimal amount, BigDecimal previous,
                                        BigDecimal perYear, String every, LocalDate day, LocalDate otherDay, String account, String otherAccount,
                                        List<Long> txnIds) {}
