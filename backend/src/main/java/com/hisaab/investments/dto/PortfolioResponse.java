package com.hisaab.investments.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

public record PortfolioResponse(BigDecimal invested, BigDecimal currentValue, BigDecimal pnl, BigDecimal pnlPct,
                                BigDecimal dayChange, List<HoldingResponse> holdings) {
    public static PortfolioResponse of(List<HoldingResponse> list) {
        BigDecimal inv = BigDecimal.ZERO, cur = BigDecimal.ZERO, day = BigDecimal.ZERO;
        for (HoldingResponse h : list) {
            inv = inv.add(h.invested());
            cur = cur.add(h.currentValue() == null ? h.invested() : h.currentValue());
            if (h.dayChange() != null) day = day.add(h.dayChange());
        }
        BigDecimal pnl = cur.subtract(inv);
        BigDecimal pct = inv.signum() == 0 ? BigDecimal.ZERO
                : pnl.multiply(BigDecimal.valueOf(100)).divide(inv, 2, RoundingMode.HALF_UP);
        List<HoldingResponse> sorted = list.stream()
                .sorted(Comparator.comparing((HoldingResponse h) -> h.currentValue() == null ? h.invested() : h.currentValue()).reversed())
                .toList();
        return new PortfolioResponse(inv, cur, pnl, pct, day, sorted);
    }
}
