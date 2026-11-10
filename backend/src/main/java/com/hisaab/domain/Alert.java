package com.hisaab.domain;

import com.hisaab.domain.Enums.AlertType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Setter
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AlertType type;

    @ManyToOne(fetch = FetchType.LAZY)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    private Txn txn;

    @Column(columnDefinition = "text")
    private String message;

    private BigDecimal expected;

    private BigDecimal actual;

    private boolean dismissed;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
