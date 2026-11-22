package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** status: WAITING, LATE (past expectedBy) or ARRIVED (with the refund's transaction). */
public record RefundResponse(long txnId, String name, String account, BigDecimal amount, Instant paidAt, LocalDate expectedBy,
                             String status, Long refundTxnId, BigDecimal refunded, Instant refundedAt) {}
