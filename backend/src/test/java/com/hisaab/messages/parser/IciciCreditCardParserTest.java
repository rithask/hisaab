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
 * ICICI Bank credit card spend alerts. The wording follows the bank's email (Nov 2026); every card number, amount,
 * merchant, date, time and limit here is made up.
 */
class IciciCreditCardParserTest {
    private final IciciCreditCardParser parser = new IciciCreditCardParser();

    private static String alert(String amount, String date, String time, String merchant) {
        return """
                <html><body><table><tr><td><font>
                Dear Customer, <br /><br />
                Your ICICI Bank Credit Card XX7342 has been used for a transaction of %s on %s at %s. Info: %s. <br /><br />
                The Available Credit Limit on your card is INR 61,240.00 and Total Credit Limit is INR 75,000.00. </p>
                The above limits are a total of the limits of all the Credit Cards issued to the primary card holder.<br /><br />
                In case you have not done this transaction, please call Customer Care.<br /><br />
                Sincerely, <br /><br />Team ICICI Bank
                </font></td></tr></table></body></html>""".formatted(amount, date, time, merchant);
    }

    /** @param received when the email arrived, in UTC */
    private static EmailContent email(String html, String received) {
        return new EmailContent("credit_cards@icici.bank.in", "Transaction alert for your ICICI Bank Credit Card", Instant.parse(received),
                HtmlText.toText(html));
    }

    @Test
    void readsASpend() {
        // written 08:14:52 with no am/pm; the email came at 8:15 pm India time, so it was the evening
        var p = parser.parse(email(alert("INR 2,315.50", "Dec 3, 2026", "08:14:52", "CHAI POINT KORAMANGALA"), "2026-12-03T14:45:30Z")).orElseThrow();
        assertThat(p.institution()).isEqualTo(Institution.ICICI);
        assertThat(p.accountType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("2315.50"));
        assertThat(p.currency()).isEqualTo("INR");
        assertThat(p.last4()).isEqualTo("7342");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(p.time()).isEqualTo(LocalTime.of(20, 14, 52));
        assertThat(p.counterparty()).isEqualTo("CHAI POINT KORAMANGALA");
    }

    @Test
    void aMorningTimeStaysMorning() {
        // the email came at 8:30 am India time: an evening reading would be in the future
        var p = parser.parse(email(alert("INR 499.00", "Dec 3, 2026", "08:14:52", "METRO CARD RECHARGE"), "2026-12-03T03:00:00Z")).orElseThrow();
        assertThat(p.time()).isEqualTo(LocalTime.of(8, 14, 52));
    }

    @Test
    void twelveIsNoonOrMidnightByWhenTheEmailCame() {
        var noon = parser.parse(email(alert("INR 180.00", "Dec 3, 2026", "12:05:10", "CANTEEN"), "2026-12-03T06:40:00Z")).orElseThrow();
        assertThat(noon.time()).isEqualTo(LocalTime.of(12, 5, 10));
        var midnight = parser.parse(email(alert("INR 180.00", "Dec 3, 2026", "12:05:10", "CANTEEN"), "2026-12-02T18:40:00Z")).orElseThrow();
        assertThat(midnight.time()).isEqualTo(LocalTime.of(0, 5, 10));
    }

    @Test
    void lateNightSpendEmailedAfterMidnight() {
        // 11:50 pm on the 3rd, emailed at 12:05 am on the 4th
        var p = parser.parse(email(alert("INR 760.00", "Dec 3, 2026", "11:50:00", "LATE NIGHT PHARMACY"), "2026-12-03T18:35:00Z")).orElseThrow();
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(p.time()).isEqualTo(LocalTime.of(23, 50));
    }

    @Test
    void aTwentyFourHourTimeIsTakenAsIs() {
        var p = parser.parse(email(alert("INR 90.00", "Dec 3, 2026", "21:10:00", "PARKING"), "2026-12-03T16:00:00Z")).orElseThrow();
        assertThat(p.time()).isEqualTo(LocalTime.of(21, 10));
    }

    @Test
    void keepsDotsInsideTheMerchant() {
        var p = parser.parse(email(alert("INR 1,099.00", "Dec 3, 2026", "10:02:44", "WWW.EXAMPLESHOP.IN"), "2026-12-03T05:00:00Z")).orElseThrow();
        assertThat(p.counterparty()).isEqualTo("WWW.EXAMPLESHOP.IN");
    }

    @Test
    void anUnreadableDateStillRecordsTheSpend() {
        // recorded anyway, at the time the email arrived, rather than lost
        for (String day : new String[]{"Smarch 41, 2026", "Feb 31, 2026"}) {
            var p = parser.parse(email(alert("INR 250.00", day, "10:02:44", "BAKERY"), "2026-12-03T05:00:00Z")).orElseThrow();
            assertThat(p.date()).isNull();
            assertThat(p.time()).isNull();
            assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        }
    }

    @Test
    void leavesRefundsAndOtherEmailsForReview() {
        String refund = alert("INR 499.00", "Dec 3, 2026", "08:14:52", "METRO CARD RECHARGE").replace("has been used for", "has been credited with a refund for");
        assertThat(parser.parse(email(refund, "2026-12-03T05:00:00Z"))).isEmpty();
        assertThat(parser.parse(email("<p>Your ICICI Bank Credit Card statement is ready.</p>", "2026-12-03T05:00:00Z"))).isEmpty();
        assertThat(parser.parse(email("<p>Your Axis Bank Credit Card XX1111 was used.</p>", "2026-12-03T05:00:00Z"))).isEmpty();
    }

    @Test
    void smsFromIciciNamesTheBank() {
        assertThat(SmsParser.institution("VM-ICICIT", "spent on card")).isEqualTo(Institution.ICICI);
        assertThat(SmsParser.institution("", "INR 500 spent on ICICI Bank Card XX1111")).isEqualTo(Institution.ICICI);
    }
}
