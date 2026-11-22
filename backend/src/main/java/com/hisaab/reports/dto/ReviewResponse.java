package com.hisaab.reports.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReviewResponse(int year, List<Integer> years, BigDecimal income, BigDecimal expense, BigDecimal saved, Integer savingsRate,
                             long transactions, BigDecimal perDay, List<ReviewMonthResponse> months, String bestMonth, String costliestMonth,
                             List<CategoryTotalResponse> categories, List<MerchantTotalResponse> merchants,
                             String mostVisited, long mostVisitedCount, ReviewBiggestResponse biggest, String busiestWeekday, long noSpendDays,
                             BigDecimal lastYearExpense, BigDecimal lastYearIncome, BigDecimal networthStart, BigDecimal networthEnd,
                             boolean partial) {}
