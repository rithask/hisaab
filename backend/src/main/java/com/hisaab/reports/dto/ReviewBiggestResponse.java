package com.hisaab.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReviewBiggestResponse(String payee, String category, BigDecimal amount, LocalDate day) {}
