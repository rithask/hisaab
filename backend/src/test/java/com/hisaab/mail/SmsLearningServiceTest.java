package com.hisaab.mail;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
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
            if (!store.contains(t)) store.add(t);
            return t;
        });
        when(repo.findAllByOrderByHitsDescIdAsc()).thenAnswer(i -> new ArrayList<>(store));
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
    void senderIdsAreNormalised() {
        assertEquals("HDFCBK", SmsLearningService.senderKey("VM-HDFCBK-S"));
        assertEquals("AXISBK", SmsLearningService.senderKey("ax-axisbk"));
        assertNull(SmsLearningService.senderKey("+919876543210"));
        assertNull(SmsLearningService.senderKey(null));
    }
}
