package com.hisaab.reports.dto;

import com.hisaab.accounts.dto.AccountResponse;
import java.util.List;

public record DashboardResponse(DashboardTotalsResponse totals, List<AccountResponse> accounts, ReportResponse month,
                                List<MonthTotalResponse> trend, DashboardCountsResponse counts) {}
