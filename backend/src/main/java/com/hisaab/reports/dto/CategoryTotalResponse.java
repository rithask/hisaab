package com.hisaab.reports.dto;

import java.math.BigDecimal;

public record CategoryTotalResponse(Long categoryId, String name, String color, BigDecimal total, long count) {}
