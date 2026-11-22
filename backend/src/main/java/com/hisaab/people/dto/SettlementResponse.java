package com.hisaab.people.dto;

import com.hisaab.people.model.Settlement;
import com.hisaab.people.model.SettlementDirection;
import com.hisaab.people.model.SettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** @param direction IN: they paid me back; OUT: I paid them back */
public record SettlementResponse(Long id, Long personId, String personName, SettlementDirection direction, BigDecimal amount,
                                 Instant settledAt, SettlementStatus status, String note, Long txnId, String txnCounterparty) {
    public static SettlementResponse of(Settlement settlement) {
        return new SettlementResponse(settlement.getId(), settlement.getPerson().getId(), settlement.getPerson().getName(), settlement.getDirection(), settlement.getAmount(),
                settlement.getSettledAt(), settlement.getStatus(), settlement.getNote(),
                settlement.getTxn() == null ? null : settlement.getTxn().getId(),
                settlement.getTxn() == null ? null : settlement.getTxn().getCounterparty());
    }
}
