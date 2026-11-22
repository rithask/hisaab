package com.hisaab.reports.service;

import com.hisaab.common.model.Enums.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MyAmountTest {
    private static BigDecimal mine(Direction d, String amount, String shared, String repaidToMe, String iRepaid) {
        return ReportService.myAmount(d, new BigDecimal(amount), new BigDecimal(shared), new BigDecimal(repaidToMe), new BigDecimal(iRepaid));
    }

    @Test
    void moneyILentIsNoneOfMine() {
        assertThat(mine(Direction.DEBIT, "5000.00", "5000.00", "0", "0")).isZero();
    }

    @Test
    void aSplitLeavesMyShare() {
        assertThat(mine(Direction.DEBIT, "900.00", "600.00", "0", "0")).isEqualByComparingTo("300.00");
    }

    @Test
    void aRepaymentIsNotIncomeButTheRestIs() {
        assertThat(mine(Direction.CREDIT, "5000.00", "0", "5000.00", "0")).isZero();
        assertThat(mine(Direction.CREDIT, "6000.00", "0", "5000.00", "0")).isEqualByComparingTo("1000.00");
    }

    @Test
    void payingSomeoneBackIsNotSpending() {
        assertThat(mine(Direction.DEBIT, "2000.00", "0", "0", "2000.00")).isZero();
    }

    @Test
    void neverBelowZero() {
        assertThat(mine(Direction.CREDIT, "100.00", "0", "150.00", "0")).isZero();
    }
}
