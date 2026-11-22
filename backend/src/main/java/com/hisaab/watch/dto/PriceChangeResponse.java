package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The price of a fixed-price series changed: {@code from} before {@code on}, {@code to} since. */
public record PriceChangeResponse(BigDecimal from, BigDecimal to, LocalDate on) {}
