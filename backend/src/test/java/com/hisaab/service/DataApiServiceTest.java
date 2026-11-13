package com.hisaab.service;

import com.hisaab.domain.Account;
import com.hisaab.domain.ApiToken;
import com.hisaab.domain.Category;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Txn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataApiServiceTest {
    private static Txn txn(Direction d, Long categoryId, long accountId, boolean transfer) {
        Txn t = new Txn();
        t.setDirection(d);
        t.setTransfer(transfer);
        Account a = new Account();
        a.setId(accountId);
        t.setAccount(a);
        if (categoryId != null) {
            Category c = new Category();
            c.setId(categoryId);
            t.setCategory(c);
        }
        return t;
    }

    @Test
    void aTokenSeesOnlyWhatItsLimitsAllow() {
        ApiToken fuel = new ApiToken();
        fuel.setCategoryIds("5");
        assertThat(DataApiService.visible(fuel, txn(Direction.DEBIT, 5L, 1, false))).isTrue();
        assertThat(DataApiService.visible(fuel, txn(Direction.DEBIT, 1L, 1, false))).isFalse();
        assertThat(DataApiService.visible(fuel, txn(Direction.DEBIT, null, 1, false))).isFalse();
        // money coming in only when allowed; transfers between my own accounts never
        assertThat(DataApiService.visible(fuel, txn(Direction.CREDIT, 5L, 1, false))).isFalse();
        ApiToken all = new ApiToken();
        all.setMoneyIn(true);
        assertThat(DataApiService.visible(all, txn(Direction.CREDIT, 16L, 1, false))).isTrue();
        assertThat(DataApiService.visible(all, txn(Direction.DEBIT, null, 1, true))).isFalse();
        all.setAccountIds("2");
        assertThat(DataApiService.visible(all, txn(Direction.DEBIT, 5L, 1, false))).isFalse();
    }

    @Test
    void cursorsRoundTripAndBadOnesAreRefused() {
        var c = new DataApiService.Cursor(Instant.parse("2026-11-12T08:14:00Z"), 812);
        assertThat(DataApiService.Cursor.decode(c.encode())).isEqualTo(c);
        assertThatThrownBy(() -> DataApiService.Cursor.decode("zzz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyIsADecimalStringWithItsCurrencyAndTimesCarryTheirOffset() {
        assertThat(DataApiService.amount(new BigDecimal("2500")).value()).isEqualTo("2500.00");
        assertThat(DataApiService.amount(new BigDecimal("0.1")).value()).isEqualTo("0.10");
        assertThat(DataApiService.amount(new BigDecimal("2500")).currency()).isEqualTo("INR");
        // RFC 3339 with seconds, in the app's zone (UTC in tests)
        assertThat(DataApiService.time(Instant.parse("2026-11-12T08:14:00Z"))).matches("2026-11-12T08:14:00(Z|[+-]\\d\\d:\\d\\d)");
    }
}
