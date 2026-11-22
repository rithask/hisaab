package com.hisaab.messages.util;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Institution;
import com.hisaab.accounts.model.Account;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMatchTest {
    private static Account account(Institution bank, AccountType type, String last4) {
        Account a = new Account();
        a.setInstitution(bank);
        a.setType(type);
        a.setLast4(last4);
        a.setName(bank + " " + type);
        return a;
    }

    private final Account hdfcCard = account(Institution.HDFC, AccountType.CREDIT_CARD, null);
    private final Account hdfcSavings = account(Institution.HDFC, AccountType.BANK, "4821");
    private final Account axisCard = account(Institution.AXIS, AccountType.CREDIT_CARD, null);

    @Test
    void anAccountWithTheDigitsWins() {
        var r = AccountMatch.pick(Institution.HDFC, AccountType.BANK, "4821", List.of(hdfcSavings), List.of());
        assertThat(r).get().satisfies(x -> {
            assertThat(x.account()).isSameAs(hdfcSavings);
            assertThat(x.learnDigits()).isFalse();
        });
    }

    @Test
    void theBanksOnlyCardWithoutDigitsLearnsThem() {
        var r = AccountMatch.pick(Institution.HDFC, AccountType.CREDIT_CARD, "7731", List.of(), List.of(hdfcCard, hdfcSavings, axisCard));
        assertThat(r).get().satisfies(x -> {
            assertThat(x.account()).isSameAs(hdfcCard);
            assertThat(x.learnDigits()).isTrue();
        });
    }

    @Test
    void twoCandidatesMeansAskInstead() {
        Account second = account(Institution.HDFC, AccountType.CREDIT_CARD, "");
        assertThat(AccountMatch.pick(Institution.HDFC, AccountType.CREDIT_CARD, "7731", List.of(), List.of(hdfcCard, second))).isEmpty();
    }

    @Test
    void neverLearnsForAnotherBankOrKind() {
        // a bank-account alert doesn't claim the card, and an unknown bank claims nothing
        assertThat(AccountMatch.pick(Institution.HDFC, AccountType.BANK, "9999", List.of(), List.of(hdfcCard, axisCard))).isEmpty();
        assertThat(AccountMatch.pick(Institution.OTHER, AccountType.CREDIT_CARD, "9999", List.of(), List.of(hdfcCard))).isEmpty();
        assertThat(AccountMatch.pick(Institution.SBI, AccountType.CREDIT_CARD, "9999", List.of(), List.of(hdfcCard))).isEmpty();
    }

    @Test
    void anAccountThatHasDigitsIsNeverOverwritten() {
        // HDFC savings has 4821; an alert for HDFC account 5555 doesn't take it over
        assertThat(AccountMatch.pick(Institution.HDFC, AccountType.BANK, "5555", List.of(), List.of(hdfcSavings))).isEmpty();
    }
}
