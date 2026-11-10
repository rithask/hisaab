package com.hisaab.domain;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.ImportStatus;
import com.hisaab.domain.Enums.Institution;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A statement read and waiting for my review before its transactions are imported. */
@Entity
@Getter
@Setter
public class StatementImport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    private StatementFile statementFile;

    private String parser;
    private String label;

    @Enumerated(EnumType.STRING)
    private Institution institution;

    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    private String last4;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal creditLimit;

    /** One check per line: "OK|label|detail" or "FAIL|label|detail". */
    @Column(columnDefinition = "text")
    private String checks;

    private String fileName;
    private String storedPath;

    /** Password to remember for this account once the import completes; cleared afterwards. */
    private String rememberPassword;

    @Enumerated(EnumType.STRING)
    private ImportStatus status;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;
}
