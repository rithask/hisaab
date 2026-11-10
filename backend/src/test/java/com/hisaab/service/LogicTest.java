package com.hisaab.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LogicTest {

    @Test
    void cardCycleBeforeDueDate() {
        // statement on the 15th, due on the 5th of the following month
        CardCycle c = CardCycle.of(15, 5, LocalDate.of(2026, 11, 10));
        assertThat(c.lastStatement()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(c.nextStatement()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(c.cycleStart()).isEqualTo(LocalDate.of(2026, 10, 16));
        // the Oct 15 statement was due Nov 5, which has passed → next due is for the Nov 15 statement
        assertThat(c.dueDate()).isEqualTo(LocalDate.of(2026, 12, 5));
    }

    @Test
    void cardCycleDueStillAhead() {
        CardCycle c = CardCycle.of(1, 20, LocalDate.of(2026, 11, 10));
        assertThat(c.lastStatement()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(c.dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void cardCycleClampsShortMonths() {
        CardCycle c = CardCycle.of(31, 20, LocalDate.of(2026, 2, 28));
        assertThat(c.lastStatement()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(c.nextStatement()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(c.dueDate()).isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    void personMatching() {
        assertThat(Names.samePerson("Arjun Ravi K", "ARJUN RAVI K")).isTrue();
        assertThat(Names.samePerson("Arjun Ravi", "ARJUN RAVI K")).isTrue();
        assertThat(Names.samePerson("Arjun Das", "ARJUN RAVI K")).isFalse();
        assertThat(Names.samePerson("Arun", "ARUN KUMAR S")).isTrue();
        assertThat(Names.samePerson(null, "X")).isFalse();
    }

    @Test
    void wordMatching() {
        assertThat(Names.containsWord("Swiggy Instamart", "instamart")).isTrue();
        assertThat(Names.containsWord("Coca Cola", "ola")).isFalse();
        assertThat(Names.containsWord("OLA CABS", "ola")).isTrue();
        assertThat(Names.merchantKey("UPI-Swiggy 1234")).isEqualTo("upi swiggy");
    }
}
