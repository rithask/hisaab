package com.hisaab.statements.dto;

import com.hisaab.common.model.Direction;
import com.hisaab.transactions.model.Txn;
import java.math.BigDecimal;
import java.time.Instant;

public record MatchedTransactionResponse(Long id, Direction direction, BigDecimal amount, Instant occurredAt, boolean timeEstimated,
                                         String counterparty, String referenceNo, String source) {
    public static MatchedTransactionResponse of(Txn t) {
        return t == null ? null : new MatchedTransactionResponse(t.getId(), t.getDirection(), t.getAmount(), t.getOccurredAt(), t.isTimeEstimated(),
                t.getCounterparty(), t.getReferenceNo(), t.getSource().name());
    }
}
