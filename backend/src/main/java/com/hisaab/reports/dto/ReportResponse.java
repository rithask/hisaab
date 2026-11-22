package com.hisaab.reports.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReportResponse(Instant from, Instant to, BigDecimal income, BigDecimal expense,
                             List<CategoryTotalResponse> expenseByCategory, List<CategoryTotalResponse> incomeByCategory,
                             List<MerchantTotalResponse> topMerchants, List<MerchantTotalResponse> topSources, List<DayTotalResponse> daily) {}
