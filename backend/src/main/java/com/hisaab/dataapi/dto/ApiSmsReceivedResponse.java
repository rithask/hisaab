package com.hisaab.dataapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * @param status      parsed, duplicate, needs_review, ignored or error
 * @param message     one line saying what happened, e.g. for a phone notification
 * @param transaction what was recorded, if anything
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiSmsReceivedResponse(String status, String message, ApiTransactionResponse transaction) {}
