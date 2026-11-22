package com.hisaab.people.dto;

/** @param rememberedName the sender's name, saved as the person's UPI name so the next one matches */
public record SettlementRecordedResponse(SettlementResponse settlement, String rememberedName) {}
