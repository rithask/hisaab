package com.hisaab.reports.dto;

import java.math.BigDecimal;

public record MonthTotalResponse(String month, BigDecimal expense, BigDecimal income) {}
