package com.hisaab.transactions.dto;

import com.hisaab.common.model.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record NewTransactionRequest(Long accountId, Direction direction, BigDecimal amount, String currency, Instant occurredAt,
                                    String counterparty, String description, Long categoryId, List<String> tags, String notes,
                                    Boolean transfer) {}
