package com.hisaab.people.dto;

import com.hisaab.people.model.Settlement;
import com.hisaab.people.model.SettlementDirection;
import com.hisaab.people.model.SettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** @param direction IN: they paid me back; OUT: I paid them back */
public record SettlementResponse(Long id, Long personId, String personName, SettlementDirection direction, BigDecimal amount,
                                 Instant settledAt, SettlementStatus status, String note, Long txnId, String txnCounterparty) {
    public static SettlementResponse of(Settlement s) {
        return new SettlementResponse(s.getId(), s.getPerson().getId(), s.getPerson().getName(), s.getDirection(), s.getAmount(),
                s.getSettledAt(), s.getStatus(), s.getNote(),
                s.getTxn() == null ? null : s.getTxn().getId(),
                s.getTxn() == null ? null : s.getTxn().getCounterparty());
    }
}
