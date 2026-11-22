package com.hisaab.messages.reading;

import com.hisaab.messages.model.EmailStatus;
import com.hisaab.accounts.model.Account;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Step 5: which account it's about. An SMS, or an email read by a taught format, uses learned links first; a bank
 * parser's email goes by its last 4 digits. No match sends it for review, saying what to do.
 */
@Component
class FindAccount implements ReadingStep {
    private final MessageAccounts accounts;

    FindAccount(MessageAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public void apply(MessageReading reading) {
        boolean learnedFirst = reading.sms() || reading.readByLearnedFormat();
        Optional<Account> account = learnedFirst ? accounts.learnedOrMatched(reading.parsed)
                : accounts.byLast4(reading.parsed);
        if (account.isPresent()) {
            reading.account = account.get();
            return;
        }
        String note;
        if (reading.sms()) {
            note = reading.parsed.last4() == null
                    ? "Couldn't tell which account this is. Open it and pick the account once; it will be remembered."
                    : "No %s account ending %s. Open it and pick the account once, or add the account.".formatted(reading.parsed.institution(), reading.parsed.last4());
        } else if (reading.readByLearnedFormat()) {
            note = "Read the way you taught, but no account matches. Open it and pick the account.";
        } else {
            note = "No %s account ending %s. Add it under Accounts & cards, then tap Try again.".formatted(reading.parsed.institution(), reading.parsed.last4());
        }
        reading.stop(EmailStatus.NEEDS_REVIEW, note);
    }
}
