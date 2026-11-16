package com.hisaab.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {
    private final LoginThrottle throttle = new LoginThrottle();
    private final Instant t0 = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void fiveWrongPasswordsLockTheClientOutForTheWindow() {
        for (int i = 0; i < 4; i++) throttle.failed("1.2.3.4", t0.plusSeconds(i));
        assertThat(throttle.waitFor("1.2.3.4", t0.plusSeconds(5))).isZero();
        throttle.failed("1.2.3.4", t0.plusSeconds(5));
        assertThat(throttle.waitFor("1.2.3.4", t0.plusSeconds(6))).isGreaterThan(Duration.ofMinutes(14));
        assertThat(throttle.waitFor("5.6.7.8", t0.plusSeconds(6))).isZero(); // others aren't affected
        assertThat(throttle.waitFor("1.2.3.4", t0.plus(LoginThrottle.WINDOW).plusSeconds(1))).isZero();
    }

    @Test
    void theRightPasswordClearsTheCount() {
        for (int i = 0; i < 4; i++) throttle.failed("1.2.3.4", t0);
        throttle.succeeded("1.2.3.4");
        throttle.failed("1.2.3.4", t0);
        assertThat(throttle.waitFor("1.2.3.4", t0)).isZero();
    }
}
