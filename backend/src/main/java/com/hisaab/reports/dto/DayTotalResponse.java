package com.hisaab.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DayTotalResponse(LocalDate day, BigDecimal expense, BigDecimal income) {}
