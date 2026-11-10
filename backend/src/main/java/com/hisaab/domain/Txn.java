package com.hisaab.domain;

import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.TxnSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "txn")
@Getter
@Setter
public class Txn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    /** Always in INR. For foreign currency transactions this may be an estimate. */
    private BigDecimal amount;

    private BigDecimal originalAmount;

    private String originalCurrency;

    private boolean amountEstimated;

    private Instant occurredAt;

    /** The time of day is a guess: the alert or statement only gave a date. */
    private boolean timeEstimated;

    private String counterparty;

    private String merchantKey;

    private String description;

    private String referenceNo;

    private BigDecimal reportedBalance;

    @ManyToOne(fetch = FetchType.LAZY)
    private Category category;

    @Column(columnDefinition = "text")
    private String notes;

    @Enumerated(EnumType.STRING)
    private TxnSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    private RawEmail rawEmail;

    /** Movement between my own accounts (incl. card bill payments). Hidden from income/expense. */
    private boolean transfer;

    @ManyToOne(fetch = FetchType.LAZY)
    private Txn linkedTxn;

    @ManyToMany
    @JoinTable(name = "txn_tag", joinColumns = @JoinColumn(name = "txn_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }
}
