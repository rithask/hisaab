package com.hisaab.transactions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionUpdateRequest(Long categoryId, Boolean clearCategory, List<String> tags, String notes, String counterparty,
                                       Boolean transfer, BigDecimal amount, Instant occurredAt, Boolean learn) {}
