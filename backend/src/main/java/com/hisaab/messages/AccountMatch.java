package com.hisaab.messages;

import com.hisaab.accounts.Account;
import com.hisaab.common.Enums.AccountType;
import com.hisaab.common.Enums.Institution;

import java.util.List;
import java.util.Optional;

/**
 * Which of my accounts an alert's "ending 1234" belongs to. An account doesn't need its last 4 digits typed in:
 * when no account has those digits, the bank's only account of that kind with no digits yet is the one, and it
 * learns them from this alert.
 */
public final class AccountMatch {
    private AccountMatch() {}

    /**
     * @param withDigits  active accounts whose last 4 are the alert's
     * @param all         every active account
     * @return the account, and whether its last 4 should be learned from the alert
     */
    public static Optional<Result> pick(Institution institution, AccountType type, String last4, List<Account> withDigits, List<Account> all) {
        if (last4 == null) return Optional.empty();
        if (withDigits.size() == 1) return Optional.of(new Result(withDigits.get(0), false));
        if (withDigits.size() > 1)
            return withDigits.stream().filter(a -> a.getInstitution() == institution && a.getType() == type).findFirst()
                    .or(() -> withDigits.stream().filter(a -> a.getInstitution() == institution).findFirst())
                    .map(a -> new Result(a, false));
        if (institution == null || institution == Institution.OTHER) return Optional.empty();
        List<Account> blank = all.stream()
                .filter(a -> a.getLast4() == null || a.getLast4().isBlank())
                .filter(a -> a.getInstitution() == institution)
                .filter(a -> (a.getType() == AccountType.CREDIT_CARD) == (type == AccountType.CREDIT_CARD))
                .filter(a -> a.getType() != AccountType.CASH)
                .toList();
        return blank.size() == 1 ? Optional.of(new Result(blank.get(0), true)) : Optional.empty();
    }

    public record Result(Account account, boolean learnDigits) {}
}
