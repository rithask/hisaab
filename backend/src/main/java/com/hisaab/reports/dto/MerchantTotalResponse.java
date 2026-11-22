package com.hisaab.reports.dto;

import java.math.BigDecimal;

public record MerchantTotalResponse(String name, BigDecimal total, long count) {}
