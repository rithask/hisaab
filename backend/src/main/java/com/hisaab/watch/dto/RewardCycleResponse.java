package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * @param earned   ₹ for cashback, points for points cards
 * @param value    the ₹ value of what was earned (points × point value), when known
 * @param received cashback or reward credits the card actually got during these dates
 */
public record RewardCycleResponse(LocalDate from, LocalDate to, boolean current, BigDecimal spent, BigDecimal earned, BigDecimal value,
                                  BigDecimal received, boolean capped, List<RewardLineResponse> lines) {}
