package com.hisaab.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthLogTest {
    @Test
    void usernamesCannotForgeLogLines() {
        assertEquals("admin INFO Sign-in OK user=root", AuthLog.clean("admin\nINFO Sign-in OK user=root", 64));
        assertEquals("-", AuthLog.clean("  ", 64));
        assertEquals("abc…", AuthLog.clean("abcdef", 3));
    }
}
