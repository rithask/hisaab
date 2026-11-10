package com.hisaab.mail.parser;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class SmsParserTest {
    private final SmsParser parser = new SmsParser();
    private static final Instant RECEIVED = Instant.parse("2026-11-06T18:00:00Z"); // 6 Nov 23:30 IST

    private ParsedTransaction ok(String sender, String text) {
        SmsParser.Result r = parser.parse(sender, text, RECEIVED);
        assertNotNull(r.txn(), () -> "expected a transaction, got: " + r.reason());
        return r.txn();
    }

    private void skipped(String text) {
        assertNull(parser.parse("AX-HDFCBK", text, RECEIVED).txn(), text);
    }

    @Test
    void hdfcUpiSent() {
        var p = ok("VM-HDFCBK", "Sent Rs.500.00\nFrom HDFC Bank A/C *2286\nTo RAHUL KUMAR\nOn 05/11/26\nRef 531234567890\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808");
        assertEquals(Institution.HDFC, p.institution());
        assertEquals(AccountType.BANK, p.accountType());
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("500.00"), p.amount());
        assertEquals("2286", p.last4());
        assertEquals(LocalDate.of(2026, 11, 5), p.date());
        assertEquals("RAHUL KUMAR", p.counterparty());
        assertEquals("531234567890", p.reference());
    }

    @Test
    void hdfcCardSpend() {
        var p = ok("AD-HDFCBK", "Spent Rs.692.00 On HDFC Bank Card 6619 At SWIGGY INSTAMART On 2026-11-06:23:05:44 Bal Rs.96808.00 Not You? Call 18002586161/SMS BLOCK CC 6619 to 7308080808");
        assertEquals(AccountType.CREDIT_CARD, p.accountType());
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("692.00"), p.amount());
        assertEquals("6619", p.last4());
        assertEquals(LocalDate.of(2026, 11, 6), p.date());
        assertEquals(LocalTime.of(23, 5, 44), p.time());
        assertEquals("SWIGGY INSTAMART", p.counterparty());
        assertNull(p.balance(), "card limits are not account balances");
    }

    @Test
    void hdfcCredit() {
        var p = ok("JD-HDFCBK", "Credit Alert!\nRs.300.00 credited to HDFC Bank A/c XX2286 on 06-11-26 from VPA arun.k@okaxis (UPI 531298765432)");
        assertEquals(Direction.CREDIT, p.direction());
        assertEquals("2286", p.last4());
        assertEquals("arun.k@okaxis", p.counterparty());
        assertEquals(LocalDate.of(2026, 11, 6), p.date());
    }

    @Test
    void supportEmailIsNotThePayee() {
        var p = ok("VM-HDFCBK", "Rs.250.00 debited from HDFC Bank A/c XX2286 on 06-11-26. Not you? Write to customercare@hdfcbank.com");
        assertNull(p.counterparty());
    }

    @Test
    void axisDebitWithNarration() {
        var p = ok("AX-AXISBK", "INR 5222.00 debited\nA/c no. XX2286\n05-11-26, 08:54:07\nUPI/P2A/612345678901/ARJUN RAVI K\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank");
        assertEquals(Institution.AXIS, p.institution());
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("5222.00"), p.amount());
        assertEquals(LocalTime.of(8, 54, 7), p.time());
        assertEquals("ARJUN RAVI K", p.counterparty());
        assertEquals("612345678901", p.reference());
    }

    @Test
    void axisCardSpend() {
        var p = ok("BZ-AXISBK", "Spent INR 450\nAxis Bank Card no. XX1234\n05-11-26 10:00:00 IST\nZOMATO\nAvl Limit: INR 9,550.00\nNot you? SMS BLOCK 1234 to 919951860002");
        assertEquals(AccountType.CREDIT_CARD, p.accountType());
        assertEquals(new BigDecimal("450.00"), p.amount());
        assertEquals("1234", p.last4());
        assertEquals("ZOMATO", p.counterparty());
    }

    @Test
    void sbiUpiDebitWithoutCurrency() {
        var p = ok("VK-SBIUPI", "Dear UPI user A/C X5678 debited by 200.0 on date 05Nov26 trf to RAHUL K Refno 531234567890. If not u? call 1800111109. -SBI");
        assertEquals(Institution.SBI, p.institution());
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("200.00"), p.amount());
        assertEquals("5678", p.last4());
        assertEquals(LocalDate.of(2026, 11, 5), p.date());
        assertEquals("RAHUL K", p.counterparty());
        assertEquals("531234567890", p.reference());
    }

    @Test
    void sbiCardSpend() {
        var p = ok("CP-SBICRD", "Rs.1,299.00 spent on your SBI Credit Card ending 4321 at AMAZON on 05/11/26. Trxn. not done by you? Report at https://sbicard.com/Dispute");
        assertEquals(Institution.SBI, p.institution());
        assertEquals(AccountType.CREDIT_CARD, p.accountType());
        assertEquals(new BigDecimal("1299.00"), p.amount());
        assertEquals("4321", p.last4());
        assertEquals("AMAZON", p.counterparty());
    }

    @Test
    void canaraCreditWithBalance() {
        var p = ok("CANBNK", "An amount of INR 1,000.00 has been CREDITED to your account XXX4471 on 05/11/2026 towards UPI from ARUN. Total Avail.bal INR 1,1250.40 - Canara Bank");
        assertEquals(Institution.CANARA, p.institution());
        assertEquals(Direction.CREDIT, p.direction());
        assertEquals("4471", p.last4());
        assertEquals(new BigDecimal("11250.40"), p.balance());
    }

    @Test
    void cardBillPaymentIsAPayment() {
        var p = ok("AD-HDFCBK", "Payment of Rs 5,000.00 received on your HDFC Bank Credit Card ending 6619 on 05-11-2026. Thank you.");
        assertEquals(Direction.CREDIT, p.direction());
        assertEquals(AccountType.CREDIT_CARD, p.accountType());
        assertTrue(p.cardPayment());
    }

    @Test
    void foreignCurrency() {
        var p = ok("AD-HDFCBK", "Spent USD 12.99 On HDFC Bank Card 6619 At NETFLIX.COM On 2026-11-05:10:00:00");
        assertEquals("USD", p.currency());
        assertEquals(new BigDecimal("12.99"), p.amount());
    }

    @Test
    void implausibleDateFallsBackToReceivedTime() {
        var p = ok("AX-AXISBK", "INR 100.00 debited from A/c no. XX2286 card valid till 12/30 UPI/P2M/531234567890/CHAI POINT");
        assertNull(p.date());
    }

    @Test
    void nonTransactionsAreSkipped() {
        skipped("123456 is your OTP for txn of Rs 500.00 at AMAZON on HDFC Bank Card 6619. Do not share.");
        skipped("Rs.1,200.00 will be debited from your A/c XX2286 on 10-11-26 towards NETFLIX e-mandate.");
        skipped("Your HDFC Bank Credit Card 6619 statement: Total Amt Due Rs.3,192.00, Min Amt Due Rs.200. Due date 05-Dec-26.");
        skipped("Txn of Rs.500.00 on HDFC Bank Card 6619 at AMAZON declined due to insufficient limit.");
        skipped("RAHUL has requested Rs.200.00 from you on Google Pay. Approve on app.");
        skipped("Get a pre-approved loan of Rs 5,00,000 at 10.5%! Apply now.");
        skipped("Hey, are we still meeting at 6?");
    }
}
