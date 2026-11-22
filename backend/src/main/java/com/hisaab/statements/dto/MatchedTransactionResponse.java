package com.hisaab.statements.dto;

import com.hisaab.common.model.Direction;
import com.hisaab.transactions.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;

public record MatchedTransactionResponse(Long id, Direction direction, BigDecimal amount, Instant occurredAt, boolean timeEstimated,
                                         String counterparty, String referenceNo, String source) {
    public static MatchedTransactionResponse of(Transaction transaction) {
        return transaction == null ? null : new MatchedTransactionResponse(transaction.getId(), transaction.getDirection(), transaction.getAmount(), transaction.getOccurredAt(), transaction.isTimeEstimated(),
                transaction.getCounterparty(), transaction.getReferenceNo(), transaction.getSource().name());
    }
}
