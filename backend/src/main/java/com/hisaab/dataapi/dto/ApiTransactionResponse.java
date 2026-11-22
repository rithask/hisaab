package com.hisaab.dataapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * @param amount          the whole payment, in rupees (as on the bank's statement, before any split)
 * @param originalAmount  what was charged in a foreign currency, when it was; otherwise null
 * @param updatedAt       when it was last changed (a new tag counts; renaming its category or account doesn't)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiTransactionResponse(String id, String direction, String occurredAt, ApiAmountResponse amount, ApiAmountResponse originalAmount,
                                     String payee, ApiCategoryRefResponse category, ApiAccountRefResponse account, String notes, List<String> tags,
                                     String createdAt, String updatedAt) {}
