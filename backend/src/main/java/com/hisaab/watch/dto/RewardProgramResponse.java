package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RewardProgramResponse(String kind, BigDecimal baseRate, BigDecimal cycleCap, BigDecimal pointValue,
                                    BigDecimal pointsBalance, LocalDate pointsAsOf, List<RewardRuleResponse> rules) {}
