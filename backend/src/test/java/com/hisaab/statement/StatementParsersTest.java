package com.hisaab.statement;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/** Layouts copied from real statements as PDFBox extracts them; names, numbers and amounts are made up. */
class StatementParsersTest {

    private static void allChecksPass(ParsedStatement s) {
        assertFalse(s.checks().isEmpty(), "expected self-checks");
        s.checks().forEach(c -> assertTrue(c.ok(), c.label() + ": " + c.detail()));
    }

    @Test
    void canaraNarrationWrapsAroundTheDateAndCarriesTheRealTime() {
        String text = """
                e-Pass Sheet
                (Report is generated for the period from 02-Nov-2026 to 03-Nov-2026)
                Customer Name:TEST USER Branch Name:SOMEWHERE
                Account Number:0000111101234 Account Type:CANARA SB GENERAL
                Date Narration Debit Amount Credit Amount Balance
                Opening Balance 1000.00
                UPI/DR/600022223333/RAVI 
                K/YESB/**ABCDE@PTY/NO 
                02-Nov-2026 30.00 970.00
                REMARK//UPIA000000000000000000000000000001
                A2/01/11/2026 22:03:58 Cheque No.:600022223333
                UPI/DR/600033334444/SHOP/ICIC/**SHOP@ICICI/
                NO 
                02-Nov-2026 REMARKS//UPI0000000000000000000000000002 33.00 937.00
                0002/02/11/2026 11:08:32 Cheque 
                No.:600033334444
                UPI/CR/600044445555/ANU 
                S/YESB/**ANU91@PTY/NO 
                03-Nov-2026 500.00 1437.00
                REMARK//UPI0000000000000000000000000003
                003/03/11/2026 18:58:14 Cheque No.:600044445555
                Opening Balance:1000.00 Dr Count:2 Dr Amount:63
                Closing Balance:1437.00 Cr Count:1 Cr Amount:500
                """;
        var p = new CanaraStatementParser();
        assertTrue(p.supports(text));
        ParsedStatement s = p.parse(text);
        assertEquals("1234", s.last4());
        assertEquals(3, s.rows().size());
        var first = s.rows().get(0);
        assertEquals(LocalDate.of(2026, 11, 1), first.date(), "real date from the narration, not the posting date");
        assertEquals(LocalTime.of(22, 3, 58), first.time());
        assertEquals("RAVI K", first.counterparty());
        assertEquals("600022223333", first.reference());
        assertEquals(Direction.DEBIT, first.direction());
        assertEquals("SHOP", s.rows().get(1).counterparty());
        assertEquals("600033334444", s.rows().get(1).reference(), "reference split across lines");
        assertEquals(Direction.CREDIT, s.rows().get(2).direction(), "credit read from the running balance");
        allChecksPass(s);
    }

    @Test
    void axisBankRowsAreDateOnlyAndDirectionComesFromTheBalance() {
        String text = """
                Statement for Account No. 90000XXXXX01234 for the period from 01-10-2026 to 31-10-2026
                Date Transaction Details Chq Withdrawal Deposits Balance
                No.
                Opening Balance 10,000.00
                01-10-2026 SB:900000000001234:INT.PD:01-07-2026 TO 30-09-2026 60.00 10,060.00
                01-10-2026 UPI/P2A/600055556666/TEST USER /NO REM/CANARA BANK 6,000.00 4,060.00
                23-10-2026 IMPS/P2A/600066667777/SOMEONE/ICICIBAN/ 1.00 4,061.00
                FTTransf/9100000000000000001
                30-10-2026 IFT/ABCDE00000-00000001/900000000000099/MS. EMPLOYER 25,000.00 29,061.00
                31-10-2026 UPI TRANSFER TO TEST USER (600077778888) 1,061.00 28,000.00
                Closing Balance 28,000.00
                Total 7,061.00 25,061.00
                Page 1 of 3
                axis bank
                """;
        var p = new AxisBankStatementParser();
        assertTrue(p.supports(text));
        ParsedStatement s = p.parse(text);
        assertEquals("1234", s.last4());
        assertEquals(5, s.rows().size());
        assertNull(s.rows().get(0).time());
        assertEquals("Interest", s.rows().get(0).counterparty());
        assertEquals(Direction.CREDIT, s.rows().get(0).direction());
        assertEquals(Direction.DEBIT, s.rows().get(1).direction());
        assertEquals("TEST USER", s.rows().get(1).counterparty());
        assertTrue(s.rows().get(2).description().contains("FTTransf"), "continuation line joins the narration");
        assertEquals("MS. EMPLOYER", s.rows().get(3).counterparty());
        assertEquals("TEST USER", s.rows().get(4).counterparty());
        assertEquals("600077778888", s.rows().get(4).reference());
        allChecksPass(s);
    }

    @Test
    void axisCardRowsWithAGarbledRupeeSign() {
        String text = """
                Axis Bank MY Zone Card Monthly Statement
                Credit Card Number: 5305XXXXXXXX1234
                Total Payment Due Minimum Payment Due Payment Due Date
                ? 1,010.00 ? 100.00 02 Nov '26
                Selected Statement Month Credit Limit Opening Balance
                Oct 2026 ? 31,000.00 ? -0.00
                Transaction Summary
                Date Transaction Details Amount (INR) Debit/Credit
                12 Oct '26 GST ? 3.29 Debit
                11 Oct '26 CLOUDHOST.COM,SAN FRANCISCO ? 521.81 Debit
                09 Oct '26 Fuel Cashback Rebates ? 15.10 Credit
                30 Sep '26 SOME STORE LTD,KOZHIKODE ? 500.00 Debit
                **End of Transaction Summary**
                """;
        var p = new AxisCardStatementParser();
        assertTrue(p.supports(text));
        ParsedStatement s = p.parse(text);
        assertEquals(AccountType.CREDIT_CARD, s.accountType());
        assertEquals("1234", s.last4());
        assertEquals(new BigDecimal("31000.00"), s.creditLimit());
        assertEquals(4, s.rows().size());
        assertEquals(LocalDate.of(2026, 9, 30), s.rows().get(0).date(), "sorted by date");
        assertEquals("CLOUDHOST.COM", s.rows().get(2).counterparty(), "town after the comma dropped");
        assertEquals(Direction.CREDIT, s.rows().get(1).direction());
        allChecksPass(s);
    }

    @Test
    void hdfcCardTreatsMidnightAsNoTimeAndPlusAsCredit() {
        String text = """
                Swiggy HDFC Bank Credit Card Statement
                Test User Credit Card No. 526873XXXXXX1234
                Billing Period 02 Oct, 2026 - 01 Nov, 2026
                PREVIOUS STATEMENT DUES FINANCE CHARGES TOTAL AMOUNT DUE
                C1,000.00 C1,026.30 + C539.00 + C0.00 = C512.70
                DATE & TIME TRANSACTION DESCRIPTION AMOUNT PI
                01/10/2026| 16:38 Swiggy InstamartBENGALURU  C 263.00 l
                03/10/2026| 00:00 10% Swiggy CashBack +  C 26.30 l
                06/10/2026| 08:21 BPPY PYMT AX000000Aaaaa0a000a0 (Ref# 600088889999000) +  C 1,000.00 l
                11/10/2026| 19:29 ASSPLBangalore  C 139.00 l
                28/10/2026| 21:07 RSP*SWIGGY PVT LTD FOBENGALURU  C 137.00 l
                """;
        var p = new HdfcCardStatementParser();
        assertTrue(p.supports(text));
        ParsedStatement s = p.parse(text);
        assertEquals("1234", s.last4());
        assertEquals(5, s.rows().size());
        assertEquals(LocalTime.of(16, 38), s.rows().get(0).time());
        assertEquals("Swiggy Instamart", s.rows().get(0).counterparty(), "glued town removed");
        assertNull(s.rows().get(1).time(), "00:00 means no time");
        assertEquals(Direction.CREDIT, s.rows().get(1).direction());
        var payment = s.rows().get(2);
        assertTrue(payment.cardPayment());
        assertEquals("Card payment", payment.counterparty());
        assertEquals("600088889999000", payment.reference());
        assertEquals("ASSPL", s.rows().get(3).counterparty());
        assertEquals("SWIGGY PVT LTD FO", s.rows().get(4).counterparty(), "processor prefix and town removed");
        allChecksPass(s);
    }

    @Test
    void parsersDoNotClaimEachOthersStatements() {
        String hdfc = "HDFC Bank Credit Card Statement\nDATE & TIME";
        assertFalse(new CanaraStatementParser().supports(hdfc));
        assertFalse(new AxisBankStatementParser().supports(hdfc));
        assertFalse(new AxisCardStatementParser().supports(hdfc));
    }

    @Test
    void nameSimilarityForPossibleMatches() {
        assertTrue(StatementImportService.similar("ASSPL", "ASSPL Bangalore IND"));
        assertTrue(StatementImportService.similar("Swiggy Instamart", "SWIGGY INSTAMART"));
        assertFalse(StatementImportService.similar("Zomato", "Blinkit"));
        assertFalse(StatementImportService.similar(null, "Blinkit"));
    }
}
