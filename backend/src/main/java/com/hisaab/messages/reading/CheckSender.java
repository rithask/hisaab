package com.hisaab.messages.reading;

import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.service.SenderTrust;
import org.springframework.stereotype.Component;

/** Step 4, email: an alert is only recorded when it really comes from its bank (or a sender I trusted). */
@Component
class CheckSender implements ReadingStep {
    private final SenderTrust trust;

    CheckSender(SenderTrust trust) {
        this.trust = trust;
    }

    @Override
    public void apply(MessageReading reading) {
        if (reading.sms()) return;
        SenderTrust.Verdict verdict = trust.judge(reading.raw.getFromAddress(), reading.raw.getSenderCheck(),
                reading.parsed.institution());
        if (verdict.ok()) return;
        reading.raw.setHold(verdict.hold());
        reading.stop(EmailStatus.NEEDS_REVIEW, verdict.note());
    }
}
