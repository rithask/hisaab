package com.hisaab.mail.reading;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.mail.AccountMatch;
import com.hisaab.mail.SmsSenders;
import com.hisaab.mail.parser.ParsedTransaction;
import com.hisaab.repo.AccountRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Which of my accounts a message is about: for reading messages and for the teaching form. */
@Component
public class MessageAccounts {
    private static final Logger log = LoggerFactory.getLogger(MessageAccounts.class);

    private final AccountRepo accounts;
    private final SmsSenders senders;

    public MessageAccounts(AccountRepo accounts, SmsSenders senders) {
        this.accounts = accounts;
        this.senders = senders;
    }

    /** Learned account links first, then the number in the text, then the bank's only account of that kind. */
    public Optional<Account> learnedOrMatched(ParsedTransaction p) {
        Optional<Account> hinted = senders.hintedAccount(p.institution(), p.last4());
        if (hinted.isPresent()) return hinted;
        // The sender id identifies the bank reliably, so a last-4 match at a different bank is not accepted.
        return byLast4(p)
                .filter(a -> p.institution() == Institution.OTHER || a.getInstitution() == Institution.OTHER
                        || a.getInstitution() == p.institution())
                .or(() -> soleAccount(p));
    }

    /** By the alert's last 4 digits; an account added without them learns them from its bank's first alert. */
    public Optional<Account> byLast4(ParsedTransaction p) {
        if (p.last4() == null) return Optional.empty();
        List<Account> withDigits = accounts.findByLast4AndArchivedFalse(p.last4());
        List<Account> all = withDigits.isEmpty() ? accounts.findByArchivedFalseOrderByTypeAscNameAsc() : List.of();
        return AccountMatch.pick(p.institution(), p.accountType(), p.last4(), withDigits, all).map(r -> {
            if (r.learnDigits()) {
                r.account().setLast4(p.last4());
                accounts.save(r.account());
                log.info("Account {} learned its last 4 digits from an alert", r.account().getId());
            }
            return r.account();
        });
    }

    /** A message without an account number: the bank's only account of that kind, if there is exactly one. */
    private Optional<Account> soleAccount(ParsedTransaction p) {
        if (p.last4() != null || p.institution() == Institution.OTHER) return Optional.empty();
        List<Account> m = accounts.findByArchivedFalseOrderByTypeAscNameAsc().stream()
                .filter(a -> a.getInstitution() == p.institution())
                .filter(a -> (a.getType() == AccountType.CREDIT_CARD) == (p.accountType() == AccountType.CREDIT_CARD))
                .toList();
        return m.size() == 1 ? Optional.of(m.get(0)) : Optional.empty();
    }
}
