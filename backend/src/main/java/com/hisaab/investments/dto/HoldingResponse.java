package com.hisaab.investments.dto;

import com.hisaab.investments.model.AssetType;
import com.hisaab.investments.model.Holding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record HoldingResponse(Long id, AssetType assetType, String symbol, String exchange, String name,
                              BigDecimal quantity, BigDecimal avgPrice, BigDecimal lastPrice, BigDecimal prevClose,
                              Instant priceAsOf, BigDecimal invested, BigDecimal currentValue, BigDecimal pnl,
                              BigDecimal pnlPct, BigDecimal dayChange) {
    public static HoldingResponse of(Holding h) {
        BigDecimal invested = h.getQuantity().multiply(h.getAvgPrice()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal current = h.getLastPrice() == null ? null
                : h.getQuantity().multiply(h.getLastPrice()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pnl = current == null ? null : current.subtract(invested);
        BigDecimal pct = pnl == null || invested.signum() == 0 ? null
                : pnl.multiply(BigDecimal.valueOf(100)).divide(invested, 2, RoundingMode.HALF_UP);
        BigDecimal day = h.getLastPrice() == null || h.getPrevClose() == null ? null
                : h.getLastPrice().subtract(h.getPrevClose()).multiply(h.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        return new HoldingResponse(h.getId(), h.getAssetType(), h.getSymbol(), h.getExchange(), h.getName(),
                h.getQuantity(), h.getAvgPrice(), h.getLastPrice(), h.getPrevClose(), h.getPriceAsOf(),
                invested, current, pnl, pct, day);
    }
}
