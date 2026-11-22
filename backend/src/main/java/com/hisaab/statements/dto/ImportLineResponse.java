package com.hisaab.statements.dto;

import com.hisaab.common.model.Direction;
import com.hisaab.statements.model.LineDecision;
import com.hisaab.statements.model.LineKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ImportLineResponse(Long id, LocalDate date, LocalTime time, String description, String counterparty, String reference,
                                 Direction direction, BigDecimal amount, BigDecimal balance, String uncertain, LineKind kind,
                                 MatchedTransactionResponse match, String matchReason, List<FieldFillResponse> fills, LineDecision decision,
                                 boolean beforeStart, BigDecimal balanceEffect) {}
