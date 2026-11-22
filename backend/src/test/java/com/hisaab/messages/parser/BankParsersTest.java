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

/** Fixtures reproduce real alert emails (screenshots provided by the user, Nov 2026). */
class BankParsersTest {

    private static EmailContent html(String from, String subject, String html) {
        return new EmailContent(from, subject, Instant.parse("2026-11-06T18:00:00Z"), HtmlText.toText(html));
    }

    static final String HDFC_DEBIT = """
            <html><body>
            <p>Dear Customer,</p>
            <p>Greetings from HDFC Bank.</p>
            <p>We would like to inform you that <b>Rs. 692.00</b> has been debited from your HDFC Bank Credit Card
            ending <b>6619</b> towards <b>Swiggy Instamart</b> on <b>06 Nov, 2026 at 23:05:44</b>.<br>
            To check your available balance, outstanding amount, or view recent transactions, you may use:</p>
            <ul><li><b>Mycards:</b><a href="https://mycards.hdfc.bank.in">https://mycards.hdfc.bank.in</a></li>
            <li><b>WhatsApp Banking:</b><a href="https://hdfcbk.io/x">https://hdfcbk.io/HDFCBK/K/DUvfZ20acT6</a></li></ul>
            </body></html>""";

    static final String CANARA_DEBIT = """
            <html><body><p>Dear Customer,</p>
            <p>Thanking you for banking with Canara Bank.</p>
            <p>An amount of INR 5,222.00 has been DEBITED on 03/11/26 from your account XXXX4471 to Axis Bank with
            UPI Ref No.:600011112222. Total Available Balance INR 1250.40.To report fraud &amp; stop further debit
            transaction, Call : 18001030.</p>
            <p>If the transaction was not initiated by you, send an email from your registered e-mail id with the
            subject line in the below format to <a href="mailto:reportfraud@canarabank.com">reportfraud@canarabank.com</a> to block.</p>
            <p>BLOCKUPI&lt;Space&gt;&lt;Mobile Number prefixed with Country Code&gt;</p>
            <p>This is an auto generated mail</p></body></html>""";

    static final String AXIS_DEBIT = """
            <html><body><p>Dear Arjun Ravi K,</p>
            <p>Here's the summary of your transaction:</p>
            <table>
              <tr><td><img alt="?"></td><td><div>Amount Debited:</div><div>INR 6000.00</div></td>
                  <td><img alt="?"></td><td><div>Account Number:</div><div>XX2286</div></td></tr>
              <tr><td><img alt="?"></td><td><div>Date &amp; Time:</div><div>03-11-26, 08:54:07 IST</div></td>
                  <td><img alt="?"></td><td><div>Transaction Info:</div><div>UPI/P2A/612345678901/ARJUN RAVI K</div></td></tr>
            </table>
            <p>If this transaction was not initiated by you, please call us.</p>
            <p>Regards,<br>Axis Bank Ltd.</p></body></html>""";

    @Test
    void hdfcCreditCardDebit() {
        var p = new HdfcCreditCardParser().parse(html("alerts@hdfcbank.bank.in", "Alert", HDFC_DEBIT)).orElseThrow();
        assertThat(p.institution()).isEqualTo(Institution.HDFC);
        assertThat(p.accountType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.amount()).isEqualByComparingTo("692.00");
        assertThat(p.currency()).isEqualTo("INR");
        assertThat(p.last4()).isEqualTo("6619");
        assertThat(p.counterparty()).isEqualTo("Swiggy Instamart");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(p.time()).isEqualTo(LocalTime.of(23, 5, 44));
        assertThat(p.balance()).isNull();
    }

    @Test
    void hdfcForeignCurrency() {
        String body = HDFC_DEBIT.replace("Rs. 692.00", "USD 20.00").replace("Swiggy Instamart", "OPENAI *CHATGPT");
        var p = new HdfcCreditCardParser().parse(html("x", "Alert", body)).orElseThrow();
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.amount()).isEqualByComparingTo("20.00");
        assertThat(p.counterparty()).isEqualTo("OPENAI *CHATGPT");
    }

    @Test
    void canaraDebitWithBalanceAndNoTime() {
        var p = new CanaraBankParser().parse(html("canarabank@canarabank.com", "Transaction alert", CANARA_DEBIT)).orElseThrow();
        assertThat(p.institution()).isEqualTo(Institution.CANARA);
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.amount()).isEqualByComparingTo("5222.00");
        assertThat(p.last4()).isEqualTo("4471");
        assertThat(p.counterparty()).isEqualTo("Axis Bank");
        assertThat(p.reference()).isEqualTo("600011112222");
        assertThat(p.balance()).isEqualByComparingTo(new BigDecimal("1250.40"));
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(p.time()).isNull();
    }

    @Test
    void canaraCredit() {
        String body = CANARA_DEBIT.replace("DEBITED", "CREDITED").replace("from your account", "to your account")
                .replace("to Axis Bank", "by ARUN KUMAR");
        var p = new CanaraBankParser().parse(html("x", "Transaction alert", body)).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.CREDIT);
        assertThat(p.counterparty()).isEqualTo("ARUN KUMAR");
    }

    @Test
    void axisAccountDebitTable() {
        var p = new AxisBankAccountParser().parse(html("alerts@axisbank.com", "Debit transaction alert", AXIS_DEBIT)).orElseThrow();
        assertThat(p.institution()).isEqualTo(Institution.AXIS);
        assertThat(p.accountType()).isEqualTo(AccountType.BANK);
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.amount()).isEqualByComparingTo("6000.00");
        assertThat(p.last4()).isEqualTo("2286");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(p.time()).isEqualTo(LocalTime.of(8, 54, 7));
        assertThat(p.reference()).isEqualTo("612345678901");
        assertThat(p.counterparty()).isEqualTo("ARJUN RAVI K");
    }

    @Test
    void axisCredit() {
        String body = AXIS_DEBIT.replace("Amount Debited", "Amount Credited");
        var p = new AxisBankAccountParser().parse(html("alerts@axisbank.com", "Credit alert", body)).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.CREDIT);
    }

    /** Axis account credits are a sentence with the account first (layout from Oct 2026; every detail made up). */
    private static String axisSentence(String what) {
        return "<html><body><table><tr><td><p>30-11-2026 <br /><br />Dear Priya Sharma, <br /><br />Thank you for banking with us. <br /><br />"
                + "We wish to inform you that your A/c no. XX2286 " + what + ". <br /><br />To check your available balance, please "
                + "<a href=\"https://example.com\">click here</a>. <br /><br />Should you wish to reach us, please call 18000000000. <br /><br />"
                + "Regards, <br />Axis Bank Ltd. </p></td></tr></table></body></html>";
    }

    @Test
    void axisAccountCreditFromAnotherAxisAccount() {
        var p = new AxisBankAccountParser().parse(html("alerts@axis.bank.in", "Credit transaction alert for Axis Bank A/c",
                axisSentence("has been credited with INR 18,450.00 on 30-11-2026 at 10:12:45 IST by IFT/ABCDE12345-11223344/77"))).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.CREDIT);
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("18450.00"));
        assertThat(p.last4()).isEqualTo("2286");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(p.time()).isEqualTo(LocalTime.of(10, 12, 45));
        // an internal transfer carries codes, not a name
        assertThat(p.reference()).isEqualTo("ABCDE12345-11223344");
        assertThat(p.counterparty()).isNull();
        assertThat(p.description()).isEqualTo("IFT/ABCDE12345-11223344/77");
    }

    @Test
    void axisAccountSalaryCreditByNeft() {
        var p = new AxisBankAccountParser().parse(html("alerts@axis.bank.in", "Credit transaction alert for Axis Bank A/c",
                axisSentence("has been credited with INR 85000.00 on 01-12-2026 at 09:00:03 IST by NEFT/N123456789012/EXAMPLE TECH PVT LTD"))).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.CREDIT);
        assertThat(p.counterparty()).isEqualTo("EXAMPLE TECH PVT LTD");
        assertThat(p.reference()).isEqualTo("N123456789012");
    }

    @Test
    void axisAccountUpiCreditFromAPerson() {
        var p = new AxisBankAccountParser().parse(html("alerts@axis.bank.in", "Credit transaction alert for Axis Bank A/c",
                axisSentence("has been credited with INR 450.00 on 02-12-2026 at 21:30:00 IST by UPI/P2A/612398765432/RAHUL KUMAR"))).orElseThrow();
        assertThat(p.counterparty()).isEqualTo("RAHUL KUMAR");
        assertThat(p.reference()).isEqualTo("612398765432");
    }

    @Test
    void axisAccountDebitAsASentence() {
        var p = new AxisBankAccountParser().parse(html("alerts@axis.bank.in", "Debit transaction alert for Axis Bank A/c",
                axisSentence("has been debited with INR 1,200.00 on 03-12-2026 at 07:45:10 IST to UPI/P2M/612311112222/CHAI POINT"))).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(p.counterparty()).isEqualTo("CHAI POINT");
    }

    @Test
    void narrationWithOnlyCodesHasNoPayee() {
        assertThat(ParseUtil.splitNarration("IFT/ABCDE12345-11223344/77")).isEqualTo(new ParseUtil.RefAndParty("ABCDE12345-11223344", null));
        assertThat(ParseUtil.splitNarration("UPI/P2A/612345678901/ARJUN RAVI K")).isEqualTo(new ParseUtil.RefAndParty("612345678901", "ARJUN RAVI K"));
    }

    @Test
    void hdfcCardSpendUnderAPaymentSubjectIsNotABillPayment() {
        // HDFC's subject for a card spend (Nov 2026) says "payment"; the spend must stay a spend (details made up)
        String body = "<html><body><table><tr><td>Dear Customer,<br><br>Greetings from HDFC Bank.<br><br>We would like to inform you that "
                + "<b>Rs. 318.00</b> has been debited from your HDFC Bank Credit Card ending <b>6619</b> towards <b>PAY*SOME FOOD APP</b> on "
                + "<b>03 Dec, 2026 at 13:20:41</b>. <br>To check your available balance, outstanding amount, or view recent transactions, you may use:"
                + "<ul><li><b>Mycards:</b>https://example.com</li></ul></td></tr></table></body></html>";
        var p = new HdfcCreditCardParser().parse(html("alerts@hdfcbank.bank.in", "A payment was made using your Credit Card", body)).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.cardPayment()).isFalse();
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("318.00"));
        assertThat(p.counterparty()).isEqualTo("PAY*SOME FOOD APP");
        assertThat(p.time()).isEqualTo(LocalTime.of(13, 20, 41));
    }

    /** Canara sends plain text (Nov 2026); every detail here is made up. */
    private static EmailContent canaraPlain(String sentence) {
        String body = "Dear Customer,\n\nThanking you for banking with Canara Bank.\n\n" + sentence
                + "\n\n\nThis is an auto generated mail\n";
        return new EmailContent("canarabank@canarabank.com", "UPI/IMPS/MB Transaction Alert", Instant.parse("2026-12-04T09:10:00Z"),
                HtmlText.normaliseLines(body));
    }

    @Test
    void canaraPlainTextCreditFromAPerson() {
        var p = new CanaraBankParser().parse(canaraPlain("An amount of INR 2,750.00 has been CREDITED on 04/12/26 to your account  XXXX4471 "
                + "from RAHUL KUMAR with UPI Ref No.:600099998888. Total Available Balance INR 12,040.15.If you are not expecting this credit "
                + "or suspect any fraudulent activity, please contact: 18000000000")).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.CREDIT);
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("2750.00"));
        assertThat(p.last4()).isEqualTo("4471");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(p.time()).isNull();
        assertThat(p.counterparty()).isEqualTo("RAHUL KUMAR");
        assertThat(p.reference()).isEqualTo("600099998888");
        assertThat(p.balance()).isEqualByComparingTo(new BigDecimal("12040.15"));
    }

    @Test
    void canaraPlainTextDebitToAShop() {
        var p = new CanaraBankParser().parse(canaraPlain("An amount of INR 45.00 has been DEBITED on 05/12/26 from your account XXXX4471 "
                + "to Corner Bake with UPI Ref No.:600011113333. Total Available Balance INR 11,995.15.To report fraud & stop further debit "
                + "transaction, Call : 18000000000.\n\nIf the transaction was not initiated by you, send an email to block.")).orElseThrow();
        assertThat(p.direction()).isEqualTo(Direction.DEBIT);
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(p.counterparty()).isEqualTo("Corner Bake");
        assertThat(p.balance()).isEqualByComparingTo(new BigDecimal("11995.15"));
    }

    @Test
    void anUnreadableDateStillRecordsTheAlertAtTheTimeTheEmailArrived() {
        // an impossible date where the date should be (details made up)
        var p = new HdfcCreditCardParser().parse(html("alerts@hdfcbank.bank.in", "Alert", HDFC_DEBIT.replace("06 Nov, 2026", "31 Feb, 2026"))).orElseThrow();
        assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("692.00"));
        assertThat(p.date()).isNull();
        assertThat(p.time()).isNull();
    }

    @Test
    void datesAndTimesAreReadLeniently() {
        assertThat(ParseUtil.dateOrNull("31 Feb, 2026")).isNull();
        assertThat(ParseUtil.dateOrNull("not a date")).isNull();
        assertThat(ParseUtil.dateOrNull("21 Nov, 2026")).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(ParseUtil.timeOrNull("16:44:02")).isEqualTo(LocalTime.of(16, 44, 2));
        assertThat(ParseUtil.timeOrNull("4:44 PM")).isEqualTo(LocalTime.of(16, 44));
        assertThat(ParseUtil.timeOrNull("11:05:30 am")).isEqualTo(LocalTime.of(11, 5, 30));
        assertThat(ParseUtil.timeOrNull("09:10:00 IST")).isEqualTo(LocalTime.of(9, 10));
        assertThat(ParseUtil.timeOrNull("25:99")).isNull();
        assertThat(ParseUtil.timeOrNull(null)).isNull();
    }

    @Test
    void parsersDoNotClaimEachOthersEmails() {
        var hdfc = html("x", "s", HDFC_DEBIT);
        var canara = html("x", "s", CANARA_DEBIT);
        var axis = html("x", "s", AXIS_DEBIT);
        assertThat(new CanaraBankParser().parse(hdfc)).isEmpty();
        assertThat(new AxisBankAccountParser().parse(hdfc)).isEmpty();
        assertThat(new HdfcCreditCardParser().parse(canara)).isEmpty();
        assertThat(new AxisBankAccountParser().parse(canara)).isEmpty();
        assertThat(new HdfcCreditCardParser().parse(axis)).isEmpty();
        assertThat(new CanaraBankParser().parse(axis)).isEmpty();
    }

    @Test
    void ignoresOtpEmails() {
        var otp = html("alerts@axisbank.com", "OTP", "<p>Your OTP for Axis Bank transaction is 123456. Do not share.</p>");
        assertThat(new AxisBankAccountParser().parse(otp)).isEmpty();
    }
}
