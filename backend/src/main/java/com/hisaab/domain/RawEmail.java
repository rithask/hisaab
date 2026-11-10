package com.hisaab.domain;

import com.hisaab.domain.Enums.Channel;
import com.hisaab.domain.Enums.EmailStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
public class RawEmail {
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

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
