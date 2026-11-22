package com.hisaab.messages.reading;

import com.hisaab.accounts.model.Account;
import com.hisaab.common.model.Enums.Channel;
import com.hisaab.common.model.Enums.EmailStatus;
import com.hisaab.messages.model.EmailContent;
import com.hisaab.messages.model.RawEmail;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.transactions.model.Txn;

/**
 * One stored message being read: what the steps of {@link MessageReader} have worked out so far. A step either adds
 * to it (what was read, the account, the transaction) or decides the message's status and stops the reading.
 */
public final class MessageReading {
    final RawEmail raw;
    /** the email as the bank parsers see it; null for an SMS */
    final EmailContent content;
    ParsedTransaction parsed;
    Account account;
    Txn txn;
    /** the balance printed in an SMS fits exactly one direction, which confirms the reading */
    boolean balanceAddsUp;
    /** an SMS collects notes along the way ("Direction corrected …") and keeps them at the end */
    final StringBuilder notes = new StringBuilder();
    private boolean done;

    MessageReading(RawEmail raw, EmailContent content) {
        this.raw = raw;
        this.content = content;
    }

    boolean sms() {
        return raw.getChannel() == Channel.SMS;
    }

    /** The parser that read it was a format I taught ("sms-learned", "email-learned"). */
    boolean readByLearnedFormat() {
        return raw.getParser() != null && raw.getParser().endsWith("-learned");
    }

    /** Decide the message's status and note, and stop reading. */
    void stop(EmailStatus status, String note) {
        raw.setStatus(status);
        raw.setNote(note);
        done = true;
    }

    boolean done() {
        return done;
    }

    public ParsedTransaction parsed() {
        return parsed;
    }

    public Txn txn() {
        return txn;
    }
}
