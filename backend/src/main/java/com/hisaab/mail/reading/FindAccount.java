package com.hisaab.mail.reading;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.EmailStatus;
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
    public void apply(MessageReading r) {
        boolean learnedFirst = r.sms() || r.readByLearnedFormat();
        Optional<Account> account = learnedFirst ? accounts.learnedOrMatched(r.parsed) : accounts.byLast4(r.parsed);
        if (account.isPresent()) {
            r.account = account.get();
            return;
        }
        String note;
        if (r.sms()) {
            note = r.parsed.last4() == null
                    ? "Couldn't tell which account this is. Open it and pick the account once; it will be remembered."
                    : "No %s account ending %s. Open it and pick the account once, or add the account.".formatted(r.parsed.institution(), r.parsed.last4());
        } else if (r.readByLearnedFormat()) {
            note = "Read the way you taught, but no account matches. Open it and pick the account.";
        } else {
            note = "No %s account ending %s. Add it under Accounts & cards, then tap Try again.".formatted(r.parsed.institution(), r.parsed.last4());
        }
        r.stop(EmailStatus.NEEDS_REVIEW, note);
    }
}
