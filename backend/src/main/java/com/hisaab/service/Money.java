package com.hisaab.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;

public final class Money {
    /**
     * India time, fixed: how Indian banks, card statements, NSE and AMFI state their times. Bank alerts, SMS and
     * statements write "08:54" meaning India time, and billing cycles turn over on Indian dates. Never configurable.
     */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /**
     * Your time zone: where your days, months and years begin (reports, budgets, bills, "today", nightly jobs, the
     * times the app shows). From TZ, default UTC. Instants are always stored in UTC; this only decides the calendar.
     */
    public static final ZoneId ZONE = zone(System.getProperty("app.timezone", System.getenv("TZ")));

    static ZoneId zone(String configured) {
        String z = configured == null ? "" : configured.trim();
        if (z.startsWith(":")) z = z.substring(1);                         // POSIX ":Area/City" form
        if (z.isEmpty()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(z);
        } catch (Exception e) {
            throw new IllegalStateException("TZ=\"" + configured + "\" isn't a time zone. Use a name such as UTC, Asia/Kolkata or Europe/London.");
        }
    }

    private Money() {}

    public static BigDecimal r2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
