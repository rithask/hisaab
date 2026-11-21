package com.hisaab.messages.reading;

import com.hisaab.common.Enums.EmailStatus;
import com.hisaab.messages.parser.BankEmailParser;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.messages.parser.SmsParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Step 2, when no taught format read it: an SMS goes to the general SMS reader, an email to each bank's parser in
 * turn. Nothing read it: it goes for review if it looks like a payment, otherwise it's ignored.
 */
@Component
class ReadWithParsers implements ReadingStep {
    private static final Logger log = LoggerFactory.getLogger(ReadWithParsers.class);
    private static final Pattern MONEY = Pattern.compile("(rs\\.?|inr|₹)\\s*[0-9][0-9,]*(\\.[0-9]{1,2})?|[0-9][0-9,]*(\\.[0-9]{1,2})?\\s*(rs\\.?|inr|₹|rupees)");
    private static final Pattern TXN_WORDS = Pattern.compile("\\b(debited|credited|spent|withdrawn|deposited|received|paid|sent|transferred|moved|purchase|txn|transaction)\\b");
    private static final Pattern NOISE = Pattern.compile("\\b(otp|one time password|verification code)\\b");

    private final SmsParser smsParser;
    private final List<BankEmailParser> parsers;

    ReadWithParsers(SmsParser smsParser, List<BankEmailParser> parsers) {
        this.smsParser = smsParser;
        this.parsers = parsers;
    }

    @Override
    public void apply(MessageReading r) {
        if (r.parsed != null) return;
        if (r.sms()) readSms(r);
        else readEmail(r);
    }

    private void readSms(MessageReading r) {
        r.raw.setParser("sms");
        SmsParser.Result result;
        try {
            result = smsParser.parse(r.raw.getSender(), r.raw.getBodyText(), r.raw.getReceivedAt());
        } catch (RuntimeException e) {
            log.warn("SMS parser failed on message {}: {}", r.raw.getId(), e.toString());
            r.stop(EmailStatus.ERROR, "Couldn't read it: " + e.getMessage());
            return;
        }
        if (result.txn() != null) {
            r.parsed = result.txn();
            return;
        }
        String lower = r.raw.getBodyText().toLowerCase(Locale.ROOT);
        boolean looksLikeMoney = looksLikeMoney(lower);
        r.stop(looksLikeMoney && !result.reason().startsWith("Not a completed") ? EmailStatus.NEEDS_REVIEW : EmailStatus.IGNORED, result.reason());
    }

    private void readEmail(MessageReading r) {
        for (BankEmailParser parser : parsers) {
            Optional<ParsedTransaction> parsed;
            try {
                parsed = parser.parse(r.content);
            } catch (RuntimeException e) {
                log.warn("Parser {} failed on email {}: {}", parser.name(), r.raw.getId(), e.toString());
                r.raw.setParser(parser.name());
                r.stop(EmailStatus.ERROR, "Couldn't read it: " + e.getMessage());
                return;
            }
            if (parsed.isEmpty()) continue;
            r.parsed = parsed.get();
            r.raw.setParser(parser.name());
            return;
        }
        if (looksLikeMoney(r.content.haystack())) {
            r.stop(EmailStatus.NEEDS_REVIEW, "Looks like a payment, but the app couldn't read it. Tell it what this is.");
        } else {
            r.stop(EmailStatus.IGNORED, null);
        }
    }

    /** Mentions an amount and a payment word, and isn't a one-time password. */
    private static boolean looksLikeMoney(String lower) {
        return MONEY.matcher(lower).find() && TXN_WORDS.matcher(lower).find() && !NOISE.matcher(lower).find();
    }
}
