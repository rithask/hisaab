package com.hisaab.messages.reading;

import com.hisaab.common.model.Institution;
import com.hisaab.messages.service.SmsSenders;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Step 3, SMS: a text that doesn't name its bank uses what earlier messages from this sender taught. */
@Component
class FindBankFromSender implements ReadingStep {
    private final SmsSenders senders;

    FindBankFromSender(SmsSenders senders) {
        this.senders = senders;
    }

    @Override
    public void apply(MessageReading reading) {
        if (!reading.sms() || reading.parsed.institution() != Institution.OTHER) return;
        Optional<Institution> known = senders.senderInstitution(reading.raw.getSender());
        known.ifPresent(i -> reading.parsed = reading.parsed.withInstitution(i));
    }
}
