package com.hisaab.people;

import com.hisaab.common.Enums.SettlementDirection;
import com.hisaab.common.Enums.SettlementStatus;
import com.hisaab.transactions.Txn;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Setter
public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Person person;

    /** The incoming credit that paid this back, if any. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Txn txn;

    private BigDecimal amount;

    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    private SettlementStatus status;

    private String note;

    @Enumerated(EnumType.STRING)
    private SettlementDirection direction = SettlementDirection.IN;
}
