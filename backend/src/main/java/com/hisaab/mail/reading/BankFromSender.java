package com.hisaab.mail.reading;

import com.hisaab.domain.Enums.Institution;
import com.hisaab.mail.SmsLearningService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Step 3, SMS: a text that doesn't name its bank uses what earlier messages from this sender taught. */
@Component
class BankFromSender implements ReadingStep {
    private final SmsLearningService learning;

    BankFromSender(SmsLearningService learning) {
        this.learning = learning;
    }

    @Override
    public void apply(MessageReading r) {
        if (!r.sms() || r.parsed.institution() != Institution.OTHER) return;
        Optional<Institution> known = learning.senderInstitution(r.raw.getSender());
        known.ifPresent(i -> r.parsed = r.parsed.withInstitution(i));
    }
}
