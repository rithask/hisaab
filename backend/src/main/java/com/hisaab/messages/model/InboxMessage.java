package com.hisaab.messages.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "raw_email")
@Getter
@Setter
public class InboxMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String messageId;

    @Enumerated(EnumType.STRING)
    private Channel channel = Channel.EMAIL;

    /** SMS sender id (e.g. AX-HDFCBK), when known. */
    private String sender;

    private String fromAddress;

    private String subject;

    private Instant receivedAt;

    /** Plain text rendering of the body (HTML converted, one block per line). */
    @Column(columnDefinition = "text")
    private String bodyText;

    @Enumerated(EnumType.STRING)
    private EmailStatus status;

    private String parser;

    @Column(columnDefinition = "text")
    private String note;

    /** PASS | FAIL | NONE: whether the mail provider confirmed the From domain (null for SMS and older emails). */
    private String senderCheck;

    /** Why an alert was held instead of recorded: UNKNOWN_SENDER | UNVERIFIED_SENDER, else null. */
    private String hold;

    /** The learned format it was read with, if any. */
    @ManyToOne(fetch = FetchType.LAZY)
    private LearnedFormat template;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
