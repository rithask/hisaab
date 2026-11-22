package com.hisaab.dataapi.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;

/**
 * A transaction an app adds. Only {@code amount} and {@code account} are required.
 *
 * @param amount      positive, in rupees, at most two decimals; a JSON string ("60.00") or number
 * @param direction   debit (default) or credit
 * @param category    a category id or name; left out, the app picks one from the payee as usual
 * @param occurredAt  RFC 3339, e.g. 2026-11-13T17:05:00+05:30; left out, now
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApiTransactionRequest(BigDecimal amount, String direction, String account, String payee,
                                    String category, String occurredAt, String notes) {}
