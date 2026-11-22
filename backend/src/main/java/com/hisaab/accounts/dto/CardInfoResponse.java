package com.hisaab.accounts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CardInfoResponse(BigDecimal creditLimit, BigDecimal outstanding, BigDecimal available,
                               BigDecimal utilizationPct, LocalDate lastStatement, LocalDate nextStatement,
                               LocalDate dueDate, long daysToDue, BigDecimal spentThisCycle,
                               BigDecimal paidSinceStatement) {}
