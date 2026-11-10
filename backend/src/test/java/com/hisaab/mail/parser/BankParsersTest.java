package com.hisaab.mail.parser;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.mail.EmailContent;
import com.hisaab.mail.HtmlText;
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
