package com.hisaab.messages.dto;

public record SkipResponse(InboxMessageResponse message, boolean learned, int conflicts, int alsoSkipped) {}
