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
    public static HoldingResponse of(Holding holding) {
        BigDecimal invested = holding.getQuantity().multiply(holding.getAvgPrice()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal current = holding.getLastPrice() == null ? null
                : holding.getQuantity().multiply(holding.getLastPrice()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pnl = current == null ? null : current.subtract(invested);
        BigDecimal pct = pnl == null || invested.signum() == 0 ? null
                : pnl.multiply(BigDecimal.valueOf(100)).divide(invested, 2, RoundingMode.HALF_UP);
        BigDecimal day = holding.getLastPrice() == null || holding.getPrevClose() == null ? null
                : holding.getLastPrice().subtract(holding.getPrevClose()).multiply(holding.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        return new HoldingResponse(holding.getId(), holding.getAssetType(), holding.getSymbol(),
                holding.getExchange(), holding.getName(),
                holding.getQuantity(), holding.getAvgPrice(), holding.getLastPrice(), holding.getPrevClose(),
                        holding.getPriceAsOf(),
                invested, current, pnl, pct, day);
    }
}
