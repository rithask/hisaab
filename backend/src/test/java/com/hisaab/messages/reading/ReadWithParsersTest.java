package com.hisaab.messages.reading;

import com.hisaab.messages.parser.AxisBankAccountParser;
import com.hisaab.messages.parser.AxisCreditCardParser;
import com.hisaab.messages.parser.BankEmailParser;
import com.hisaab.messages.parser.CanaraBankParser;
import com.hisaab.messages.parser.HdfcCreditCardParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadWithParsersTest {
    // given in a jumbled order, as Spring may hand them over
    private final ReadWithParsers step = new ReadWithParsers(null,
            List.of(new HdfcCreditCardParser(), new CanaraBankParser(), new AxisCreditCardParser(), new AxisBankAccountParser()));

    private List<String> names(String from) {
        return step.parsersFor(from).stream().map(BankEmailParser::name).toList();
    }

    @Test
    void anEmailFromABankGoesOnlyToThatBanksParsers() {
        assertThat(names("Axis Bank Alerts <alerts@axis.bank.in>")).containsExactly("axis-bank-account", "axis-credit-card");
        assertThat(names("alerts@hdfcbank.bank.in")).containsExactly("hdfc-credit-card");
        assertThat(names("canarabank@canarabank.com")).containsExactly("canara-bank");
        // a bank's subdomain counts as the bank
        assertThat(names("cards@alerts.hdfcbank.net")).containsExactly("hdfc-credit-card");
    }

    @Test
    void aBankWithoutParsersGetsNoneSoTheEmailWaitsForReview() {
        assertThat(names("alerts@sbi.co.in")).isEmpty();
    }

    @Test
    void anyOtherSenderGoesToEveryParserInAFixedOrder() {
        assertThat(names("me@example.com")).containsExactly("axis-bank-account", "axis-credit-card", "canara-bank", "hdfc-credit-card");
        assertThat(names(null)).hasSize(4);
        // a look-alike domain isn't the bank
        assertThat(names("alerts@hdfcbank.net.example.com")).hasSize(4);
    }
}
