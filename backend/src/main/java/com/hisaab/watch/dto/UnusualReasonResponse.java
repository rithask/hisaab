package com.hisaab.watch.dto;

import java.math.BigDecimal;

/**
 * Why a payment was flagged. kind: DOUBLE_CHARGE (with {@code otherTxnId}, {@code minutesApart}),
 * BIG_FOR_MERCHANT ({@code typical} = my usual amount there, {@code timesBefore}), NEW_PAYEE_BIG, FOREIGN
 * ({@code currency}, {@code originalAmount}), LATE_NIGHT.
 */
public record UnusualReasonResponse(String kind, Long otherTxnId, Integer minutesApart, BigDecimal typical, Integer timesBefore,
                                    String currency, BigDecimal originalAmount) {}
