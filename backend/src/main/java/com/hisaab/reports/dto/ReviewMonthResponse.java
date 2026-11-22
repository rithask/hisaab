package com.hisaab.reports.dto;

import java.math.BigDecimal;

public record ReviewMonthResponse(String month, BigDecimal income, BigDecimal expense) {}
