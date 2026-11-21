package com.hisaab.mail.reading;

import com.hisaab.domain.Enums.EmailStatus;
import com.hisaab.domain.Txn;
import com.hisaab.mail.SmsSenders;
import com.hisaab.service.TransactionService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Step 7: record the transaction, unless the same payment was already recorded from the other channel (then it's a
 * duplicate). A recorded SMS teaches which bank its sender is.
 */
@Component
class Record implements ReadingStep {
    private final TransactionService txns;
    private final SmsSenders senders;

    Record(TransactionService txns, SmsSenders senders) {
        this.txns = txns;
        this.senders = senders;
    }

    @Override
    public void apply(MessageReading r) {
        Optional<Txn> created = txns.createFromEmail(r.parsed, r.account, r.raw);
        r.txn = created.orElse(null);
        r.raw.setStatus(created.isPresent() ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
        if (!r.sms()) return;
        if (created.isEmpty()) r.notes.append("Already recorded (from the alert email or an earlier SMS).");
        else senders.rememberSender(r.raw.getSender(), r.account.getInstitution());
        r.raw.setNote(r.notes.isEmpty() ? null : r.notes.toString());
    }
}
