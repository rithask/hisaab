package com.hisaab.messages.reading;

import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.EmailContent;
import com.hisaab.messages.model.RawEmail;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reads a stored message (bank SMS or alert email) and records what it says, as a pipeline of steps in order of
 * trust:
 * <ol>
 *   <li>{@link KnownFormat}: a format I taught</li>
 *   <li>{@link ReadWithParsers}: the general SMS reader, or each bank's email parser</li>
 *   <li>{@link BankFromSender}: an SMS that doesn't name its bank, from what its sender taught</li>
 *   <li>{@link CheckSender}: an email must really come from its bank</li>
 *   <li>{@link FindAccount}: which of my accounts it's about</li>
 *   <li>{@link CheckBalance}: an SMS's printed balance can correct the direction</li>
 *   <li>{@link Record}: the transaction, or "already recorded" from the other channel</li>
 *   <li>{@link LearnFromAgreement}: a reading confirmed independently teaches its wording</li>
 * </ol>
 * Each step either adds to the reading or decides the message's status and stops it. Steps that only apply to one
 * channel say so themselves.
 */
@Service
public class MessageReader {
    public static final String NOT_A_TXN_NOTE = "Not a transaction: worded like a message you marked as not one.";
    public static final String PAUSED_NOTE = "Worded like a learned format that was paused because it kept being corrected. Check it and teach it.";

    private final List<ReadingStep> steps;

    MessageReader(KnownFormat knownFormat, ReadWithParsers parsers, BankFromSender bankFromSender, CheckSender checkSender,
                  FindAccount findAccount, CheckBalance checkBalance, Record record, LearnFromAgreement learn) {
        this.steps = List.of(knownFormat, parsers, bankFromSender, checkSender, findAccount, checkBalance, record, learn);
    }

    /** Reads (or reads again) a stored message; its status, note, parser and transaction are set from the result. */
    public MessageReading read(RawEmail raw) {
        boolean sms = raw.getChannel() == Channel.SMS;
        return read(raw, sms ? null : new EmailContent(raw.getFromAddress(), raw.getSubject(), raw.getReceivedAt(), raw.getBodyText()));
    }

    /** @param content an email as it arrived (before what's stored was shortened); null for an SMS */
    public MessageReading read(RawEmail raw, EmailContent content) {
        boolean sms = raw.getChannel() == Channel.SMS;
        MessageReading r = new MessageReading(raw, content);
        raw.setNote(null);
        if (!sms) raw.setHold(null);
        for (ReadingStep step : steps) {
            step.apply(r);
            if (r.done()) break;
        }
        return r;
    }
}
