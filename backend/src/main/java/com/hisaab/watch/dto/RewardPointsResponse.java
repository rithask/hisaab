package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Points: the bank's balance as of a date, plus what's been earned since. */
public record RewardPointsResponse(BigDecimal balance, LocalDate asOf, BigDecimal earnedSince, BigDecimal estimate, BigDecimal value) {}
