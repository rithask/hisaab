package com.hisaab.investments.dto;

import com.hisaab.investments.model.AssetType;
import java.math.BigDecimal;

public record HoldingRequest(AssetType assetType, String symbol, String exchange, String name,
                             BigDecimal quantity, BigDecimal avgPrice) {}
