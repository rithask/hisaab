package com.hisaab.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record NetWorthPointResponse(LocalDate day, BigDecimal netWorth, BigDecimal cash, BigDecimal cardDues, BigDecimal investments,
                                    BigDecimal receivable, boolean estimated) {}
