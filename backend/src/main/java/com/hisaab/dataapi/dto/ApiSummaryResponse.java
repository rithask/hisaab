package com.hisaab.dataapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** @param to the last day included */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiSummaryResponse(String from, String to, String direction, String groupBy, ApiAmountResponse total, long count, List<ApiCategoryGroupResponse> groups) {}
