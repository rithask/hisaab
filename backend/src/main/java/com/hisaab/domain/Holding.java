package com.hisaab.domain;

import com.hisaab.domain.Enums.AssetType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Setter
public class Holding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AssetType assetType;

    /** NSE/BSE trading symbol, or AMFI scheme code for mutual funds. */
    private String symbol;

    private String exchange;

    private String name;

    private BigDecimal quantity;

    private BigDecimal avgPrice;

    private BigDecimal lastPrice;

    private BigDecimal prevClose;

    private Instant priceAsOf;
}
