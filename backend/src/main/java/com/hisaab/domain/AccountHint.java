package com.hisaab.domain;

import com.hisaab.domain.Enums.Institution;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Learned link from "this bank's SMS with this number (or no number)" to one of my accounts. */
@Entity
@Getter
@Setter
public class AccountHint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private Institution institution;

    /** Empty string when the SMS carries no account number. */
    private String last4 = "";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Account account;
}
