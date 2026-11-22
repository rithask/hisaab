package com.hisaab.messages.reading;

import com.hisaab.common.model.Enums.Channel;
import com.hisaab.common.model.Enums.EmailStatus;
import com.hisaab.common.model.Enums.TxnSource;
import com.hisaab.messages.model.RawEmail;
import com.hisaab.messages.parser.SmsParser;
import com.hisaab.messages.service.SmsLearningService;
import com.hisaab.transactions.model.Txn;
import com.hisaab.transactions.service.TransactionService;
import org.springframework.stereotype.Component;

/**
 * Step 8: an SMS read by the general reader whose reading was confirmed independently (its balance adds up, or its
 * alert email agrees) has its wording learned, so the next one is read exactly. An email that turned out to be the
 * copy of an SMS confirms that SMS the same way.
 */
@Component
class LearnFromAgreement implements ReadingStep {
    private final SmsLearningService learning;
    private final TransactionService txns;
    private final SmsParser smsParser;

    LearnFromAgreement(SmsLearningService learning, TransactionService txns, SmsParser smsParser) {
        this.learning = learning;
        this.txns = txns;
        this.smsParser = smsParser;
    }

    @Override
    public void apply(MessageReading r) {
        if (r.sms()) {
            if (!"sms".equals(r.raw.getParser())) return;
            boolean emailAgrees = r.txn == null && txns.pairedWith(r.raw).stream().anyMatch(t -> t.getSource() == TxnSource.EMAIL);
            if (r.balanceAddsUp || emailAgrees) {
                learning.autoLearn(Channel.SMS, r.raw.getBodyText(), new SmsLearningService.Truth(r.parsed.direction(), r.parsed.amount(), r.account, r.parsed.counterparty()), r.raw)
                        .template().ifPresent(r.raw::setTemplate);
            }
        } else if (r.raw.getStatus() == EmailStatus.DUPLICATE) {
            confirmSmsTwins(r.raw);
        }
    }

    /** This email matched a transaction recorded from an SMS read by the general reader: learn that SMS's wording. */
    private void confirmSmsTwins(RawEmail email) {
        for (Txn t : txns.pairedWith(email)) {
            RawEmail sms = t.getRawEmail();
            if (t.getSource() != TxnSource.SMS || sms == null || !"sms".equals(sms.getParser())) continue;
            SmsParser.Result r = smsParser.parse(sms.getSender(), sms.getBodyText(), sms.getReceivedAt());
            String payee = r.txn() == null ? null : r.txn().counterparty();
            learning.autoLearn(Channel.SMS, sms.getBodyText(), new SmsLearningService.Truth(t.getDirection(), t.getAmount(), t.getAccount(), payee), sms)
                    .template().ifPresent(sms::setTemplate);
        }
    }
}
