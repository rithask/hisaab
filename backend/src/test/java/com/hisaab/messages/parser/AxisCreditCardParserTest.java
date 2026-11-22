package com.hisaab.messages.parser;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Direction;
import com.hisaab.common.model.Institution;
import com.hisaab.messages.model.EmailContent;
import com.hisaab.messages.util.HtmlText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Axis Bank credit card spend alerts. The layout follows the bank's email (Nov 2026); every name, amount, merchant,
 * card number, date and limit here is made up.
 */
class AxisCreditCardParserTest {
    private final AxisCreditCardParser parser = new AxisCreditCardParser();

    /** One table row per field: an icon cell, then a cell with the label and the value as separate blocks. */
    private static String row(String label, String value) {
        return "<tr><td><img alt=\"\" src=\"icon.png\"></td><td><div>" + label + "</div><div>" + value + "</div></td></tr>";
    }

    private static String alert(String amount, String merchant, String card, String when) {
        return """
                <html><body>
                <p><a href="https://example.com/emi">CLICK HERE</a></p>
                <p>05-12-2026</p>
                <p>Dear Priya Sharma,</p>
                <p>Here's the summary of your Axis Bank Credit Card Transaction:</p>
                <table>
                """ + row("Transaction Amount:", amount) + row("Merchant Name:", merchant)
                + row("Axis Bank Credit Card No.", card) + row("Date &amp; Time:", when)
                + row("Available Limit*:", "INR 48210") + row("Total Credit Limit*:", "INR 75000") + """
                </table>
                <p>*The information above includes the available and total credit limit across all of your Axis Bank credit cards.</p>
                <p>If this transaction was not intiated by you:<br>SMS BLOCK 4417 to +910000000000</p>
                <p>Regards,<br>Axis Bank Ltd.</p>
                <p>Please do not share your Internet Banking details, such as user ID/password or your Credit/Debit Card number/CVV/OTP with anyone.</p>
                </body></html>""";
    }

    private static EmailContent email(String html) {
        return new EmailContent("alerts@axisbank.com", "Transaction alert on Axis Bank Credit Card", Instant.parse("2026-12-05T14:15:00Z"), HtmlText.toText(html));
    }

    @Test
    void readsASpend() {
        var p = parser.parse(email(alert("INR 1249", "BLUE TOKAI COFFEE IN", "XX4417", "05-12-2026, 19:42:07 IST"))).orElseThrow();
        assertThat(p.institution()).isEqualTo(Institution.AXIS);
        assertThat(p.accountType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("1249.00"));
        assertThat(p.currency()).isEqualTo("INR");
        assertThat(p.last4()).isEqualTo("4417");
        assertThat(p.counterparty()).isEqualTo("BLUE TOKAI COFFEE IN");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(p.time()).isEqualTo(LocalTime.of(19, 42, 7));
        // the limits are across all Axis cards, so they're not this card's balance
        assertThat(p.balance()).isNull();
        assertThat(p.cardPayment()).isFalse();
    }

    @Test
    void amountsWithPaiseAndCommas() {
        var p = parser.parse(email(alert("INR 12,480.50", "CITY BOOKSHOP", "XX4417", "05-12-2026, 08:03:55 IST"))).orElseThrow();
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("12480.50"));
    }

    @Test
    void aForeignSpendKeepsItsCurrency() {
        var p = parser.parse(email(alert("USD 18.99", "SOME APP STORE", "XX4417", "05-12-2026, 08:03:55 IST"))).orElseThrow();
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("18.99"));
    }

    @Test
    void labelAndValueOnTheSameLine() {
        String html = "<html><body><p>Here's the summary of your Axis Bank Credit Card Transaction:</p>"
                + "<p>Transaction Amount: INR 350</p><p>Merchant Name: METRO RAIL</p><p>Axis Bank Credit Card No. XX4417</p>"
                + "<p>Date &amp; Time: 06-12-2026, 09:10:00 IST</p></body></html>";
        var p = parser.parse(email(html)).orElseThrow();
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(p.counterparty()).isEqualTo("METRO RAIL");
        assertThat(p.last4()).isEqualTo("4417");
    }

    @Test
    void theWholeTableOnOneLine() {
        String html = "<html><body><p>Here's the summary of your Axis Bank Credit Card Transaction:</p>"
                + "<p><span>Transaction Amount:</span> <span>INR 350</span></p>"
                + "<p><span>Merchant Name:</span> <span>METRO RAIL</span> <span>Axis Bank Credit Card No.</span> <span>XX4417</span>"
                + " <span>Date &amp; Time:</span> <span>06-12-2026, 09:10:00 IST</span></p></body></html>";
        var p = parser.parse(email(html)).orElseThrow();
        assertThat(p.counterparty()).isEqualTo("METRO RAIL");
    }

    @Test
    void anUnreadableDateFallsBackToWhenTheEmailArrived() {
        var p = parser.parse(email(alert("INR 99", "TEA STALL", "XX4417", "sometime yesterday"))).orElseThrow();
        assertThat(p.date()).isNull();
        assertThat(p.time()).isNull();
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("99.00"));
    }

    @Test
    void refundsAndPaymentsAreLeftForReview() {
        String refund = alert("INR 1249", "BLUE TOKAI COFFEE IN", "XX4417", "05-12-2026, 19:42:07 IST")
                .replace("Here's the summary of your Axis Bank Credit Card Transaction:", "A refund has been credited to your Axis Bank Credit Card:");
        assertThat(parser.parse(email(refund))).isEmpty();
    }

    @Test
    void otherAxisAndOtherBanksEmailsAreNotClaimed() {
        // the account alert (also from Axis) and an HDFC card alert
        assertThat(parser.parse(email("<html><body><p>Dear Customer,</p><p>Amount Debited: INR 600.00</p><p>Account Number: XX2286</p></body></html>"))).isEmpty();
        assertThat(parser.parse(email("<p>Rs. 692.00 has been debited from your HDFC Bank Credit Card ending 6619 towards Swiggy on 06 Nov, 2026</p>"))).isEmpty();
        // and the account parser leaves the card alert alone
        assertThat(new AxisBankAccountParser().parse(email(alert("INR 1249", "BLUE TOKAI COFFEE IN", "XX4417", "05-12-2026, 19:42:07 IST")))).isEmpty();
    }
}
