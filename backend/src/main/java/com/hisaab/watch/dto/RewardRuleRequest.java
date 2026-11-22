package com.hisaab.watch.dto;

import java.math.BigDecimal;

public record RewardRuleRequest(String label, String merchantMatch, Long categoryId, BigDecimal rate, BigDecimal cycleCap) {}
