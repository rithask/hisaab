package com.hisaab.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Reading TZ: a tz-database name, blank for UTC, the POSIX ":Area/City" form; anything else stops the app clearly. */
class TimeZoneTest {
    @Test
    void namesBlankAndPosixForm() {
        assertEquals(ZoneId.of("UTC"), Money.zone(null));
        assertEquals(ZoneId.of("UTC"), Money.zone("  "));
        assertEquals(ZoneId.of("Asia/Kolkata"), Money.zone("Asia/Kolkata"));
        assertEquals(ZoneId.of("Europe/London"), Money.zone(":Europe/London"));
    }

    @Test
    void unknownZoneExplainsItself() {
        var e = assertThrows(IllegalStateException.class, () -> Money.zone("India"));
        assertTrue(e.getMessage().contains("TZ=\"India\""));
        assertTrue(e.getMessage().contains("Asia/Kolkata"));
    }

    @Test
    void bankTimeIsAlwaysIndia() {
        assertEquals(ZoneId.of("Asia/Kolkata"), Money.INDIA);
    }
}
