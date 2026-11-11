package com.hisaab.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** How often I've put a payee (or UPI ID) in a category, and for what amounts. */
@Entity
@Getter
@Setter
public class CategoryVote {
    public static final String MERCHANT = "MERCHANT", VPA = "VPA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String keyType;

    @Column(name = "key")
    private String key;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Category category;

    private int votes;

    private BigDecimal amountSum = BigDecimal.ZERO;

    @Column(name = "amount_n")
    private int amountN;

    private Instant lastAt = Instant.now();

    /** Typical amount I've spent at this payee in this category, or null. */
    public BigDecimal meanAmount() {
        return amountN == 0 ? null : amountSum.divide(BigDecimal.valueOf(amountN), 2, java.math.RoundingMode.HALF_UP);
    }
}
