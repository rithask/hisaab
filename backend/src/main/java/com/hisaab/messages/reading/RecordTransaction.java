package com.hisaab.messages.reading;

import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.service.SmsSenders;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.service.TransactionService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Step 7: record the transaction, unless the same payment was already recorded from the other channel (then it's a
 * duplicate). A recorded SMS teaches which bank its sender is.
 */
@Component
class RecordTransaction implements ReadingStep {
    private final TransactionService txns;
    private final SmsSenders senders;

    RecordTransaction(TransactionService txns, SmsSenders senders) {
        this.txns = txns;
        this.senders = senders;
    }

    @Override
    public void apply(MessageReading r) {
        Optional<Transaction> created = txns.createFromEmail(r.parsed, r.account, r.raw);
        r.txn = created.orElse(null);
        r.raw.setStatus(created.isPresent() ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
        if (!r.sms()) return;
        if (created.isEmpty()) r.notes.append("Already recorded (from the alert email or an earlier SMS).");
        else senders.rememberSender(r.raw.getSender(), r.account.getInstitution());
        r.raw.setNote(r.notes.isEmpty() ? null : r.notes.toString());
    }
}
