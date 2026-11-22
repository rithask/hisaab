package com.hisaab.watch.dto;

import java.math.BigDecimal;

public record RewardRuleResponse(Long id, String label, String merchantMatch, Long categoryId, String category, BigDecimal rate, BigDecimal cycleCap) {}
