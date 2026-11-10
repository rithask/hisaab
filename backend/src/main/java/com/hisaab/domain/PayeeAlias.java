package com.hisaab.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A learned rename: the bank's raw payee text (as a merchant key) and the name I prefer. */
@Entity
@Getter
@Setter
public class PayeeAlias {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String rawKey;

    private String displayName;

    private int hits;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
