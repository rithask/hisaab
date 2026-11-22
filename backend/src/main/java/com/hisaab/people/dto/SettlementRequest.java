package com.hisaab.people.dto;

import com.hisaab.people.model.SettlementDirection;
import java.math.BigDecimal;
import java.time.Instant;

/** @param direction IN (default): they paid me back; OUT: I paid them back */
public record SettlementRequest(Long personId, SettlementDirection direction, BigDecimal amount, Instant settledAt,
                                Long txnId, String note) {}
