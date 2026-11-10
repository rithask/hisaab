package com.hisaab.domain;

import com.hisaab.domain.Enums.Institution;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A sender id (e.g. HDFCBK) seen on parsed SMS, and the bank it belongs to. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class SmsSender {
    @Id
    private String senderKey;

    @Enumerated(EnumType.STRING)
    private Institution institution;

    private int hits;

    public SmsSender(String senderKey, Institution institution) {
        this.senderKey = senderKey;
        this.institution = institution;
    }
}
