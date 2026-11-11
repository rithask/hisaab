package com.hisaab.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An email domain I've said may send transaction alerts. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class TrustedSender {
    @Id
    private String domain;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    public TrustedSender(String domain) {
        this.domain = domain;
    }
}
