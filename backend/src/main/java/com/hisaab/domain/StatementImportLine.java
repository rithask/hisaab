package com.hisaab.domain;

import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.LineDecision;
import com.hisaab.domain.Enums.LineKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Getter
@Setter
public class StatementImportLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id")
    private StatementImport statementImport;

    private int lineNo;
    private LocalDate txnDate;
    private LocalTime txnTime;

    @Column(columnDefinition = "text")
    private String description;

    private String counterparty;
    private String referenceNo;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private BigDecimal amount;
    private BigDecimal balance;
    private boolean cardPayment;

    @Column(columnDefinition = "text")
    private String uncertain;

    @Enumerated(EnumType.STRING)
    private LineKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    private Txn matchTxn;

    @Column(columnDefinition = "text")
    private String matchReason;

    @Enumerated(EnumType.STRING)
    private LineDecision decision;

    @ManyToOne(fetch = FetchType.LAZY)
    private Txn createdTxn;
}
