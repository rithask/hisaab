package com.hisaab.messages.reading;

import com.hisaab.common.Enums.EmailStatus;
import com.hisaab.common.Enums.Institution;
import com.hisaab.messages.SenderTrust;
import org.springframework.stereotype.Component;

/** Step 4, email: an alert is only recorded when it really comes from its bank (or a sender I trusted). */
@Component
class CheckSender implements ReadingStep {
    private final SenderTrust trust;

    CheckSender(SenderTrust trust) {
        this.trust = trust;
    }

    @Override
    public void apply(MessageReading r) {
        if (r.sms()) return;
        SenderTrust.Verdict v = trust.judge(r.raw.getFromAddress(), r.raw.getSenderCheck(), r.parsed.institution(), bankName(r.parsed.institution()));
        if (v.ok()) return;
        r.raw.setHold(v.hold());
        r.stop(EmailStatus.NEEDS_REVIEW, v.note());
    }

    private static String bankName(Institution i) {
        return switch (i) {
            case HDFC -> "HDFC Bank";
            case AXIS -> "Axis Bank";
            case SBI -> "SBI";
            case CANARA -> "Canara Bank";
            case OTHER -> "bank";
        };
    }
}
