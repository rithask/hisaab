package com.hisaab.accounts.model;

import jakarta.persistence.*;
import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Institution;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Setter
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    private Institution institution = Institution.OTHER;

    private String last4;

    /** Bank/cash: balance on openingDate. Credit card: outstanding on openingDate. */
    private BigDecimal openingBalance = BigDecimal.ZERO;

    private Instant openingDate;

    private BigDecimal creditLimit;

    private Integer statementDay;

    private Integer dueDay;

    private boolean archived;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    public boolean isCard() {
        return type == AccountType.CREDIT_CARD;
    }
}
