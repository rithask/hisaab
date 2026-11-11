package com.hisaab.domain;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Channel;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A message I taught, with the right answer: the safety net every learned-format change is checked against. */
@Entity
@Getter
@Setter
public class LearnedExample {
    public static final String TXN = "TXN", SKIP = "SKIP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private RawEmail rawEmail;

    @Enumerated(EnumType.STRING)
    private Channel channel = Channel.SMS;

    @Column(columnDefinition = "text")
    private String text;

    private String kind = TXN;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Institution institution;

    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    /** As written in the message; null means the payee isn't checked. */
    private String payee;

    /** TAUGHT by me, or AUTO (confirmed automatically). My lessons always outrank automatic ones. */
    private String origin = com.hisaab.domain.SmsTemplate.TAUGHT;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
