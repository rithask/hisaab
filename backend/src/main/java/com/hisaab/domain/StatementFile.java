package com.hisaab.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
public class StatementFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    private RawEmail rawEmail;

    private String fileName;

    private String storedPath;

    private long sizeBytes;

    private String periodLabel;

    private Instant receivedAt;

    /** How reading this statement for import went: REVIEW, IMPORTED, CANCELLED, NEEDS_PASSWORD, UNSUPPORTED or ERROR. */
    private String importStatus;

    @Column(columnDefinition = "text")
    private String importNote;
}
