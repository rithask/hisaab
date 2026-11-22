package com.hisaab.messages.dto;

import com.hisaab.common.model.Direction;
import java.math.BigDecimal;

/**
 * @param counterparty the name to show for the payee
 * @param amountAt     where I marked the amount ({start, end} in the collapsed text), if I did
 * @param payeeAt      where I marked the payee, if I did
 * @param categoryId   the category it belongs in, if I picked one (learned for its payee, or for its wording
 *                     when it has no payee)
 */
public record TeachRequest(Direction direction, BigDecimal amount, Long accountId, String counterparty,
                           int[] amountAt, int[] payeeAt, Long categoryId) {}
