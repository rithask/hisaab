package com.hisaab.domain;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A message format (SMS or bank email) learned from a correction. */
@Entity
@Getter
@Setter
public class SmsTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private com.hisaab.domain.Enums.Channel channel = com.hisaab.domain.Enums.Channel.SMS;

    public static final String TXN = "TXN", SKIP = "SKIP";

    /** TXN reads a transaction; SKIP means "not a transaction": messages worded like this are skipped. */
    private String kind = TXN;

    public boolean isSkip() {
        return SKIP.equals(kind);
    }

    @Column(columnDefinition = "text")
    private String skeleton;

    @Column(columnDefinition = "text")
    private String pattern;

    @Enumerated(EnumType.STRING)
    private Institution institution;

    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private boolean cardPayment;

    @Column(columnDefinition = "text")
    private String sample;

    private int hits;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant lastUsedAt;
}
