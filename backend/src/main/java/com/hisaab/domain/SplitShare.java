package com.hisaab.domain;

import com.hisaab.domain.Enums.SplitMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
public class SplitShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Txn txn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Person person;

    @Enumerated(EnumType.STRING)
    private SplitMethod method;

    private BigDecimal input;

    private BigDecimal amount;
}
