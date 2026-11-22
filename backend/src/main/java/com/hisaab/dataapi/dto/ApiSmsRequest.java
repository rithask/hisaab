package com.hisaab.dataapi.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A bank SMS a phone forwards.
 *
 * @param sender      the sender id (e.g. AX-HDFCBK), if known; it helps recognise the bank
 * @param receivedAt  RFC 3339; left out, now
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApiSmsRequest(String text, String sender, String receivedAt) {}
