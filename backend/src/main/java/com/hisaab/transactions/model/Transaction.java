package com.hisaab.transactions.model;

import com.hisaab.common.model.Direction;
import com.hisaab.accounts.model.Account;
import com.hisaab.categories.model.Category;
import com.hisaab.dataapi.model.ApiToken;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.people.model.Person;
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
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null only when someone else paid ({@link #paidBy}): no money left an account of mine. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Account account;
    /** The person who paid, when this is my share of something they paid for. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by_person_id")
    private Person paidBy;
    /** What they paid altogether (the amount is my share of it). */
    private BigDecimal paidTotal;

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
    private TransactionSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    private InboxMessage rawEmail;

    /** The alert from the other channel that was matched to this one (its email for an SMS, or the reverse). */
    @ManyToOne(fetch = FetchType.LAZY)
    private InboxMessage twinRawEmail;

    /** The category was chosen by the app (from what it learned), not set by me. */
    private boolean categorySuggested;

    /** Movement between my own accounts (incl. card bill payments). Hidden from income/expense. */
    private boolean transfer;

    @ManyToOne(fetch = FetchType.LAZY)
    private Transaction linkedTxn;

    /** The API token that added it, when an app did (shown as "Added by …"). */
    @ManyToOne(fetch = FetchType.LAZY)
    private ApiToken apiToken;

    /** The Idempotency-Key the app sent with it, and a fingerprint of that request. */
    private String requestKey;

    private String requestHash;

    @ManyToMany
    @JoinTable(name = "txn_tag", joinColumns = @JoinColumn(name = "txn_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    /** When it last changed; set by the database (V11), never by the app. */
    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    /** Someone else paid; this is my share. Not a bank transaction: nothing is learned from it or matched to it. */
    public boolean isPaidByPerson() {
        return paidBy != null;
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }
}
