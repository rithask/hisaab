package com.hisaab.dataapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** @param key the category or account id, or the month (2026-11) or day (2026-11-12); null for uncategorised */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiCategoryGroupResponse(String key, String name, ApiAmountResponse total, long count) {}
