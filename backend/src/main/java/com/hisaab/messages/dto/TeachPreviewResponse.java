package com.hisaab.messages.dto;

import com.hisaab.common.model.Direction;
import java.math.BigDecimal;

/**
 * @param flat       the message with whitespace collapsed; {@code amountAt}/{@code payeeAt} are {start, end} in it
 * @param categoryId the category I already chose for its transaction, if any
 */
public record TeachPreviewResponse(Direction direction, BigDecimal amount, Long accountId, String counterparty,
                                   String flat, int[] amountAt, int[] payeeAt, Long categoryId) {}
