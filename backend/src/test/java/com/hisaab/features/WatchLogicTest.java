package com.hisaab.features;

import com.hisaab.features.BillService.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Subscription price steps, reward billing cycles and monthly cost. Made-up amounts. */
class WatchLogicTest {
    private static List<Payment> monthly(double... amounts) {
        List<Payment> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 14);
        for (double a : amounts) {
            out.add(new Payment(out.size() + 1, "netflix", "Netflix", 1, "Card", "Subscriptions", null, BigDecimal.valueOf(a), d));
            d = d.plusMonths(1);
        }
        return out;
    }

    @Test
    void steadyPriceIsFixedWithNoChange() {
        var s = BillService.steps(monthly(649, 649, 649));
        assertNotNull(s);
        assertNull(s.change());
    }

    @Test
    void priceRiseIsFoundWithItsDate() {
        var s = BillService.steps(monthly(199, 199, 199, 399));
        assertNotNull(s);
        assertEquals(0, new BigDecimal("199").compareTo(s.change().from()));
        assertEquals(0, new BigDecimal("399").compareTo(s.change().to()));
        assertEquals(LocalDate.of(2026, 4, 14), s.change().on());
    }

    @Test
    void smallWobbleIsStillTheSamePrice() {
        assertNull(BillService.steps(monthly(500, 505, 498)).change());          // within 2%
    }

    @Test
    void priceMustBeEstablishedBeforeItChanges() {
        assertNull(BillService.steps(monthly(300, 650, 650)));                   // first run is one payment
        assertNull(BillService.steps(monthly(1640, 1810, 1960)));                // varying bill, not fixed
    }

    @Test
    void rewardCyclesFollowTheStatementDay() {
        var cycles = RewardService.cycles(12, LocalDate.of(2026, 11, 10), 3);
        assertEquals(LocalDate.of(2026, 10, 13), cycles.get(0)[0]);              // running cycle
        assertEquals(LocalDate.of(2026, 11, 12), cycles.get(0)[1]);
        assertEquals(LocalDate.of(2026, 9, 13), cycles.get(1)[0]);
        assertEquals(LocalDate.of(2026, 10, 12), cycles.get(1)[1]);
    }

    @Test
    void rewardCyclesHandleShortMonthsAndStatementDayToday() {
        var cycles = RewardService.cycles(31, LocalDate.of(2026, 2, 28), 2);    // February ends on the 28th
        assertEquals(LocalDate.of(2026, 2, 1), cycles.get(0)[0]);
        assertEquals(LocalDate.of(2026, 2, 28), cycles.get(0)[1]);
        assertEquals(LocalDate.of(2026, 1, 31), cycles.get(1)[1]);
    }

    @Test
    void rewardCyclesWithoutStatementDayAreCalendarMonths() {
        var cycles = RewardService.cycles(null, LocalDate.of(2026, 11, 10), 2);
        assertEquals(LocalDate.of(2026, 11, 1), cycles.get(0)[0]);
        assertEquals(LocalDate.of(2026, 10, 31), cycles.get(1)[1]);
    }

    @Test
    void monthlyCost() {
        assertEquals(0, new BigDecimal("100.00").compareTo(SubscriptionService.perMonth(new BigDecimal("1200"), "Every year")));
        assertEquals(0, new BigDecimal("433.33").compareTo(SubscriptionService.perMonth(new BigDecimal("100"), "Every week")));
        assertEquals(0, new BigDecimal("199.00").compareTo(SubscriptionService.perMonth(new BigDecimal("199"), "Every month")));
    }
}
