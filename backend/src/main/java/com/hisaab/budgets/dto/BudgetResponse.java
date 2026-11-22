package com.hisaab.budgets.dto;

import java.math.BigDecimal;

/**
 * @param projected spending by the end of the month at the current pace
 * @param status    OK, WARN (80% used, or on pace to go over) or OVER
 */
public record BudgetResponse(Long categoryId, String name, String color, BigDecimal limit, BigDecimal spent, BigDecimal remaining,
                             int pct, BigDecimal projected, String status) {}
