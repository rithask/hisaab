package com.hisaab.messages.reading;

import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.transactions.model.TransactionSource;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.messages.parser.SmsParser;
import com.hisaab.messages.service.FormatLearningService;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.service.TransactionService;
import org.springframework.stereotype.Component;

/**
 * Step 8: an SMS read by the general reader whose reading was confirmed independently (its balance adds up, or its
 * alert email agrees) has its wording learned, so the next one is read exactly. An email that turned out to be the
 * copy of an SMS confirms that SMS the same way.
 */
@Component
class LearnFromAgreement implements ReadingStep {
    private final FormatLearningService learning;
    private final TransactionService txns;
    private final SmsParser smsParser;

    LearnFromAgreement(FormatLearningService learning, TransactionService txns, SmsParser smsParser) {
        this.learning = learning;
        this.txns = txns;
        this.smsParser = smsParser;
    }

    @Override
    public void apply(MessageReading reading) {
        if (reading.sms()) {
            if (!"sms".equals(reading.raw.getParser())) return;
            boolean emailAgrees = reading.txn == null && txns.pairedWith(reading.raw).stream().anyMatch(t -> t.getSource() == TransactionSource.EMAIL);
            if (reading.balanceAddsUp || emailAgrees) {
                learning.autoLearn(Channel.SMS, reading.raw.getBodyText(), new FormatLearningService.Truth(reading.parsed.direction(), reading.parsed.amount(), reading.account, reading.parsed.counterparty()), reading.raw)
                        .template().ifPresent(reading.raw::setTemplate);
            }
        } else if (reading.raw.getStatus() == EmailStatus.DUPLICATE) {
            confirmSmsTwins(reading.raw);
        }
    }

    /** This email matched a transaction recorded from an SMS read by the general reader: learn that SMS's wording. */
    private void confirmSmsTwins(InboxMessage email) {
        for (Transaction transaction : txns.pairedWith(email)) {
            InboxMessage sms = transaction.getRawEmail();
            if (transaction.getSource() != TransactionSource.SMS || sms == null
                    || !"sms".equals(sms.getParser())) continue;
            SmsParser.Result result = smsParser.parse(sms.getSender(), sms.getBodyText(), sms.getReceivedAt());
            String payee = result.txn() == null ? null : result.txn().counterparty();
            learning.autoLearn(Channel.SMS, sms.getBodyText(), new FormatLearningService.Truth(transaction.getDirection(), transaction.getAmount(), transaction.getAccount(), payee), sms)
                    .template().ifPresent(sms::setTemplate);
        }
    }
}
