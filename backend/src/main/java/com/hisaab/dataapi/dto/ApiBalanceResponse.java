package com.hisaab.dataapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** @param balance for a bank or cash account what's in it; for a card what's owed on it */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiBalanceResponse(ApiAccountRefResponse account, ApiAmountResponse balance, ApiCardResponse card) {}
