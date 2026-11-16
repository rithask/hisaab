package com.hisaab.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SameOriginFilterTest {
    @Test
    void onlyTheAppsOwnPagesMayChangeThings() {
        assertThat(SameOriginFilter.allowed("same-origin", "https://money.example.com", "money.example.com")).isTrue();
        // another of my subdomains is the "same site" to a browser, but not the same origin
        assertThat(SameOriginFilter.allowed("same-site", "https://id.example.com", "money.example.com")).isFalse();
        assertThat(SameOriginFilter.allowed("cross-site", "https://evil.test", "money.example.com")).isFalse();
        assertThat(SameOriginFilter.allowed("none", null, "money.example.com")).isTrue();
    }

    @Test
    void olderBrowsersAreJudgedByOriginAndScriptsWithoutOneAreLetThrough() {
        assertThat(SameOriginFilter.allowed(null, "https://money.example.com", "money.example.com")).isTrue();
        assertThat(SameOriginFilter.allowed(null, "https://id.example.com", "money.example.com")).isFalse();
        assertThat(SameOriginFilter.allowed(null, "null", "money.example.com")).isFalse();
        assertThat(SameOriginFilter.allowed(null, "http://localhost:8080", "localhost:8080")).isTrue();
        assertThat(SameOriginFilter.allowed(null, null, "money.example.com")).isTrue();
    }
}
