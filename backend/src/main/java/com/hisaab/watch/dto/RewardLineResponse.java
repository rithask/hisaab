package com.hisaab.watch.dto;

import java.math.BigDecimal;

/** One rate within a cycle: what was spent under it, what it earned, and whether its cap was hit. */
public record RewardLineResponse(String label, BigDecimal rate, BigDecimal spent, BigDecimal earned, BigDecimal cap, boolean capped) {}
