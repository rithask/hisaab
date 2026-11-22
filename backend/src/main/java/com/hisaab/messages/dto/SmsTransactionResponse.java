package com.hisaab.messages.dto;

import com.hisaab.common.model.Direction;
import java.math.BigDecimal;
import java.time.Instant;

public record SmsTransactionResponse(Long id, Direction direction, BigDecimal amount, String currency, String account,
                                     String counterparty, String category, Instant occurredAt) {}
