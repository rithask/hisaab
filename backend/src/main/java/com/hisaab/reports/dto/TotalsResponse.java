package com.hisaab.reports.dto;

import java.math.BigDecimal;

public record TotalsResponse(BigDecimal cash, BigDecimal cardOutstanding, BigDecimal cardLimit, BigDecimal investments,
                             BigDecimal invested, BigDecimal receivable, BigDecimal netWorth) {}
