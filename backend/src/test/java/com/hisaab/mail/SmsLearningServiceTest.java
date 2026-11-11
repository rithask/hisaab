package com.hisaab.mail;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.domain.Enums.Channel;
import com.hisaab.domain.LearnedExample;
import com.hisaab.domain.SmsTemplate;
import com.hisaab.mail.parser.ParsedTransaction;
import com.hisaab.mail.parser.SmsParser;
import com.hisaab.repo.AccountHintRepo;
import com.hisaab.repo.LearnedExampleRepo;
import com.hisaab.repo.SmsSenderRepo;
import com.hisaab.repo.SmsTemplateRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmsLearningServiceTest {
    private static final Instant DEC = Instant.parse("2026-12-12T10:00:00Z");
    private final List<SmsTemplate> store = new ArrayList<>();
    private final List<LearnedExample> lessons = new ArrayList<>();
    private SmsLearningService learning;
    private Account axis;

    @BeforeEach
    void setUp() {
        SmsTemplateRepo repo = mock(SmsTemplateRepo.class);
        when(repo.save(any())).thenAnswer(i -> {
            SmsTemplate t = i.getArgument(0);
            if (t.getId() == null) t.setId((long) store.size() + 1 + store.stream().mapToLong(x -> x.getId() == null ? 0 : x.getId()).max().orElse(0));
            if (!store.contains(t)) store.add(t);
            return t;
        });
        when(repo.findAllByOrderByHitsDescIdAsc()).thenAnswer(i -> new ArrayList<>(store));
        when(repo.findByChannelOrderByHitsDescIdAsc(any())).thenAnswer(i -> store.stream().filter(t -> t.getChannel() == i.getArgument(0)).collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        doAnswer(i -> store.remove(i.<SmsTemplate>getArgument(0))).when(repo).delete(any());
        when(repo.findBySkeleton(any())).thenAnswer(i -> store.stream().filter(t -> t.getSkeleton().equals(i.getArgument(0))).findFirst());
        LearnedExampleRepo examples = mock(LearnedExampleRepo.class);
        when(examples.save(any())).thenAnswer(i -> {
            LearnedExample e = i.getArgument(0);
            if (!lessons.contains(e)) lessons.add(e);
            return e;
        });
        when(examples.findByChannel(any())).thenAnswer(i -> lessons.stream().filter(e -> e.getChannel() == i.getArgument(0)).toList());
        doAnswer(i -> lessons.remove(i.<LearnedExample>getArgument(0))).when(examples).delete(any());
        learning = new SmsLearningService(repo, mock(AccountHintRepo.class), mock(SmsSenderRepo.class), examples);
        axis = new Account();
        axis.setInstitution(Institution.AXIS);
        axis.setType(AccountType.BANK);
        axis.setLast4("2286");
    }

    private ParsedTransaction read(String text) {
        Optional<SmsLearningService.Match> m = learning.match(text, DEC);
        assertTrue(m.isPresent(), "expected a learned format to match: " + text);
        return m.get().txn();
    }

    @Test
    void aFormatTheHeuristicsCannotReadIsLearnedFromOneCorrection() {
        String odd = "Txn alert: 300.00 INR moved out of acct 2286 for RAHUL K. Ticket 88123456";
        assertNull(new SmsParser().parse("AX-AXISBK", odd, DEC).txn(), "precondition: heuristics don't understand this wording");

        assertTrue(learning.learn(odd, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K")).isPresent());

        var p = read("Txn alert: 1,075.50 INR moved out of acct 2286 for PRIYA SHARMA. Ticket 99001122");
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("1075.50"), p.amount());
        assertEquals("PRIYA SHARMA", p.counterparty());
        assertEquals("2286", p.last4());
        assertEquals(Institution.AXIS, p.institution());
    }

    @Test
    void learnedFormatCarriesEveryFieldAndSurvivesNewDatesAndMonths() {
        learning.learn("Dear Customer, INR 500.00 debited from A/c XX2286 on 05-Nov-26 to SHOP ONE. UPI Ref 531234567890. Avl Bal INR 9,022.00 -Axis Bank",
                new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("500.00"), axis, "SHOP ONE"));

        var p = read("Dear Customer, INR 1,250.75 debited from A/c XX2286 on 12-Dec-26 to CAFE BLUE TOKAI. UPI Ref 600000000001. Avl Bal INR 7,771.25 -Axis Bank");
        assertEquals(new BigDecimal("1250.75"), p.amount());
        assertEquals("CAFE BLUE TOKAI", p.counterparty());
        assertEquals("600000000001", p.reference());
        assertEquals(new BigDecimal("7771.25"), p.balance());
        assertEquals(LocalDate.of(2026, 12, 12), p.date());
        assertEquals(1, store.get(0).getHits());
    }

    @Test
    void payeeGluedToAReferenceIsNotSwallowedByIt() {
        learning.learn("INR 20.00 debited A/c no. XX2286 10-11-26, 09:00:00 UPI/P2M/531400000001/PARKING Axis Bank",
                new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("20"), axis, "PARKING"));
        var p = read("INR 35.00 debited A/c no. XX2286 03-12-26, 11:30:00 UPI/P2M/600000000002/METRO CARD Axis Bank");
        assertEquals("METRO CARD", p.counterparty());
        assertEquals(new BigDecimal("35.00"), p.amount());
    }

    @Test
    void theCorrectedDirectionWinsOverTheWording() {
        // "credited" appears, but I said this was money going out (a reversal notice worded confusingly).
        learning.learn("Rs 40.00 credited to merchant ZEPTO from your A/c XX2286", new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("40"), axis, "ZEPTO"));
        assertEquals(Direction.DEBIT, read("Rs 95.00 credited to merchant BLINKIT from your A/c XX2286").direction());
    }

    @Test
    void differentWordingDoesNotMatch() {
        learning.learn("Txn alert: 300.00 INR moved out of acct 2286 for RAHUL K", new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K"));
        assertTrue(learning.match("Txn alert: 300.00 INR moved into acct 2286 from RAHUL K", DEC).isEmpty());
        assertTrue(learning.match("Your OTP is 300000 for acct 2286", DEC).isEmpty());
    }

    @Test
    void nothingIsLearnedWhenTheAmountIsNotInTheText() {
        assertTrue(learning.learn("Txn alert: 300.00 INR moved out of acct 2286", new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("999"), axis, null)).isEmpty());
        assertTrue(store.isEmpty());
    }

    @Test
    void teachingTheSameFormatTwiceUpdatesIt() {
        String sms = "Txn alert: 300.00 INR moved out of acct 2286 for RAHUL K";
        learning.learn(sms, new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("300"), axis, "RAHUL K"));
        learning.learn(sms, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K"));
        assertEquals(1, store.size());
        assertEquals(Direction.DEBIT, store.get(0).getDirection());
    }

    @Test
    void aChangeThatWouldBreakAnEarlierLessonIsRefused() {
        String out = "Txn alert: 300.00 INR moved for acct 2286 to RAHUL K";
        learning.learn(out, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K"));
        assertEquals(1, lessons.size(), "the corrected message is kept as a lesson");

        // Same wording, but I say this one was money coming in: one format can't be both, so the change is refused
        var r = learning.teach("Txn alert: 75.00 INR moved for acct 2286 to PRIYA S",
                new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("75"), axis, "PRIYA S"), null);
        assertTrue(r.template().isEmpty());
        assertEquals(1, r.conflicts());
        assertEquals(Direction.DEBIT, read(out).direction(), "the earlier lesson still reads as taught");
        assertEquals(1, lessons.size());
    }

    @Test
    void reteachingTheSameMessageReplacesItsLesson() {
        String sms = "Txn alert: 300.00 INR moved for acct 2286 to RAHUL K";
        learning.learn(sms, new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("300"), axis, "RAHUL K"));
        learning.learn(sms, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K"));
        assertEquals(1, lessons.size());
        assertEquals(Direction.DEBIT, lessons.get(0).getDirection());
    }

    @Test
    void aMarkedPayeeIsLearnedEvenWhenShownUnderAnotherName() {
        String sms = "INR 250.00 paid from acct XX2286 at SWIGGY*INSTAMART BLR on 05-11-26. Not you? Call 1860";
        int at = sms.indexOf("SWIGGY"), end = sms.indexOf(" on 05");
        // I mark "SWIGGY*INSTAMART BLR" but want it shown as "Swiggy Instamart": typing alone would never find it
        var r = learning.teach(sms, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("250"), axis, "SWIGGY*INSTAMART BLR",
                new int[]{sms.indexOf("250.00") - 4, sms.indexOf("250.00") + 6}, new int[]{at, end}), null);
        assertTrue(r.template().isPresent());
        var p = read("INR 99.00 paid from acct XX2286 at ZEPTO MARKETPLACE on 12-12-26. Not you? Call 1860");
        assertEquals("ZEPTO MARKETPLACE", p.counterparty());
        assertEquals(new BigDecimal("99.00"), p.amount());
        assertEquals("SWIGGY*INSTAMART BLR", lessons.get(0).getPayee());
    }

    @Test
    void marksOutsideTheTextAreIgnored() {
        assertNull(SmsLearningService.span(new int[]{5, 500}, "short text"));
        assertNull(SmsLearningService.span(new int[]{3, 3}, "short text"));
        assertArrayEquals(new int[]{0, 5}, SmsLearningService.span(new int[]{0, 7}, "short. text"));
    }

    @Test
    void emailFormatsAreKeptApartFromSmsFormats() {
        String mail = "Hello, your Axis account XX2286 was charged INR 315.00 at CITY BOOKSHOP today.";
        learning.teach(com.hisaab.domain.Enums.Channel.EMAIL, mail, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("315"), axis, "CITY BOOKSHOP"), null);
        String next = "Hello, your Axis account XX2286 was charged INR 99.00 at RAIL TICKETS today.";
        assertTrue(learning.match(next, DEC).isEmpty(), "an SMS isn't read with an email format");
        assertEquals("RAIL TICKETS", learning.match(com.hisaab.domain.Enums.Channel.EMAIL, next, DEC).orElseThrow().txn().counterparty());
    }

    @Test
    void notATransactionSkipsMessagesWordedTheSame() {
        String promo = "Get a pre-approved loan of Rs 5,00,000 at 10.5% on your A/c XX2286. Reply YES by 30-11-26";
        assertTrue(learning.skip(Channel.SMS, promo, null).template().isPresent());
        var m = learning.match("Get a pre-approved loan of Rs 2,00,000 at 11.0% on your A/c XX2286. Reply YES by 15-12-26", DEC);
        assertTrue(m.isPresent() && m.get().skip());
        assertTrue(learning.match("Get a car loan of Rs 2,00,000 today", DEC).isEmpty(), "different wording isn't skipped");
    }

    @Test
    void aSkipThatWouldHideATaughtTransactionIsRefused() {
        String sms = "Txn alert: 300.00 INR moved for acct 2286 to RAHUL K";
        learning.learn(sms, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K"));
        // another message worded the same can't be marked "not a transaction": that would hide the one I taught
        var r = learning.skip(Channel.SMS, "Txn alert: 45.00 INR moved for acct 2286 to RAHUL K", null);
        assertTrue(r.template().isEmpty(), "the same wording can't be skipped");
        assertEquals(1, r.conflicts());
        assertEquals(Direction.DEBIT, read(sms).direction());
    }

    @Test
    void teachingATransactionOverrulesASkipForItsWording() {
        String sms = "Refund of INR 120.00 initiated to acct 2286 for order 5512";
        learning.skip(Channel.SMS, sms, null);
        assertTrue(learning.match(sms, DEC).orElseThrow().skip());
        learning.learn("Refund of INR 80.00 initiated to acct 2286 for order 7781", new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("80"), axis, null));
        // the earlier skip lesson conflicts, so nothing changes until I remove that skip
        assertTrue(learning.match(sms, DEC).orElseThrow().skip());
        lessons.removeIf(e -> LearnedExample.SKIP.equals(e.getKind()));
        learning.learn("Refund of INR 80.00 initiated to acct 2286 for order 7781", new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("80"), axis, null));
        assertEquals(Direction.CREDIT, read(sms).direction());
    }

    @Test
    void aFormatThatKeepsBeingCorrectedIsPaused() {
        // a broad format: "<payee>" swallows everything after "to"
        String a = "Paid INR 300.00 from acct 2286 to RAHUL K";
        learning.learn(a, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K"));
        SmsTemplate broad = store.get(0);
        String b = "Paid INR 80.00 from acct 2286 to CAFE ONE";
        // I correct a message it read: the amount was right but I say it was money in (a reversal) -> wrong once
        assertFalse(learning.corrected(broad, b, new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("80"), axis, null)));
        assertEquals(1, broad.getCorrections());
        // a correction that agrees with it doesn't count
        assertFalse(learning.corrected(broad, b, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("80"), axis, null)));
        assertEquals(1, broad.getCorrections());
        assertTrue(learning.corrected(broad, b, null), "second time wrong (it isn't a transaction at all): paused");
        assertTrue(broad.isPaused());
        assertTrue(learning.match(b, DEC).isEmpty(), "a paused format isn't used");
        assertTrue(learning.pausedFor(Channel.SMS, b).isPresent());
        // the safety net still protects the paused format's own lesson
        var r = learning.teach("Paid INR 80.00 from acct 2286 to CAFE ONE", new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("80"), axis, "CAFE ONE"), null);
        assertEquals(1, r.conflicts());
        assertTrue(broad.isPaused());
        // teaching that exact wording again starts it afresh
        learning.learn(a, new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("300"), axis, "RAHUL K"));
        assertFalse(broad.isPaused());
        assertEquals(0, broad.getCorrections());
    }

    @Test
    void theSameKindOfMessageInSlightlyDifferentWordingBecomesOneFormat() {
        learning.learn("INR 500.00 debited from A/c XX2286 on 05-11-26 to SHOP ONE. UPI Ref 531234567890. Not you? Call 18001035577 -Axis Bank",
                new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("500"), axis, "SHOP ONE"));
        var r = learning.teach("INR 120.00 debited from A/c XX2286 on 06-11-26 to CAFE BLUE. UPI Ref 600000000001. Not you? SMS BLOCK 2286 to 919951860002 -Axis Bank",
                new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("120"), axis, "CAFE BLUE"), null);
        assertTrue(r.merged(), "merged into the first format");
        assertEquals(1, store.size());
        assertEquals(2, lessons.size());
        // a third footer it has never seen is read too, payee and all
        var p = read("INR 75.50 debited from A/c XX2286 on 07-11-26 to METRO CARD. UPI Ref 600000000002. Not you? Call 1860 500 5555 -Axis Bank");
        assertEquals(new BigDecimal("75.50"), p.amount());
        assertEquals("METRO CARD", p.counterparty());
        // both lessons still read as taught
        assertEquals("SHOP ONE", read("INR 500.00 debited from A/c XX2286 on 05-11-26 to SHOP ONE. UPI Ref 531234567890. Not you? Call 18001035577 -Axis Bank").counterparty());
    }

    @Test
    void differentKindsOfMessageAreNotMerged() {
        learning.learn("INR 500.00 debited from A/c XX2286 on 05-11-26 to SHOP ONE. UPI Ref 531234567890 -Axis Bank",
                new SmsLearningService.Truth(Direction.DEBIT, new BigDecimal("500"), axis, "SHOP ONE"));
        var r = learning.teach("INR 500.00 credited to A/c XX2286 on 05-11-26 from SHOP ONE. UPI Ref 531234567890 -Axis Bank",
                new SmsLearningService.Truth(Direction.CREDIT, new BigDecimal("500"), axis, "SHOP ONE"), null);
        assertFalse(r.merged(), "money in and money out stay separate formats");
        assertEquals(2, store.size());
    }

    @Test
    void formatsAreMergedOnlyWhenTheirSlotsLineUp() {
        var a = new FormatShape(java.util.List.of(new FormatShape.Token("inr", "INR", false), new FormatShape.Token("<AMT>", "x", false),
                new FormatShape.Token("debited", "d", false), new FormatShape.Token("to", "t", false), new FormatShape.Token("<PAYEE>", "p", false)), false);
        var b = new FormatShape(java.util.List.of(new FormatShape.Token("inr", "INR", false), new FormatShape.Token("<AMT>", "x", false),
                new FormatShape.Token("debited", "d", false), new FormatShape.Token("to", "t", false), new FormatShape.Token("<L4>", "l", false)), false);
        assertTrue(FormatShape.merge(a, b).isEmpty());
        assertTrue(FormatShape.merge(a, a).isPresent());
    }

    @Test
    void senderIdsAreNormalised() {
        assertEquals("HDFCBK", SmsLearningService.senderKey("VM-HDFCBK-S"));
        assertEquals("AXISBK", SmsLearningService.senderKey("ax-axisbk"));
        assertNull(SmsLearningService.senderKey("+919876543210"));
        assertNull(SmsLearningService.senderKey(null));
    }
}
