package com.hisaab.messages.reading;

import com.hisaab.common.Enums.Institution;
import com.hisaab.messages.SmsSenders;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Step 3, SMS: a text that doesn't name its bank uses what earlier messages from this sender taught. */
@Component
class BankFromSender implements ReadingStep {
    private final SmsSenders senders;

    BankFromSender(SmsSenders senders) {
        this.senders = senders;
    }

    @Override
    public void apply(MessageReading r) {
        if (!r.sms() || r.parsed.institution() != Institution.OTHER) return;
        Optional<Institution> known = senders.senderInstitution(r.raw.getSender());
        known.ifPresent(i -> r.parsed = r.parsed.withInstitution(i));
    }
}
