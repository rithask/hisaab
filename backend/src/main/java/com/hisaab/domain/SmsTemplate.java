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

    public static final String TAUGHT = "TAUGHT", AUTO = "AUTO";

    /** TAUGHT by me, or AUTO: learned from a reading confirmed by the balance or by the other alert. */
    private String origin = TAUGHT;

    public boolean isAuto() {
        return AUTO.equals(origin);
    }

    public boolean isSkip() {
        return SKIP.equals(kind);
    }

    @Column(columnDefinition = "text")
    private String skeleton;

    @Column(columnDefinition = "text")
    private String pattern;

    /** The format word by word (see FormatShape), for merging; null for formats learned before that existed. */
    @Column(columnDefinition = "text")
    private String parts;

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

    /** How often a reading by this format was corrected. */
    private int corrections;

    /** Not used until I resume it (set after repeated corrections). */
    private boolean paused;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant lastUsedAt;
}
