package com.hisaab.messages.dto;

/** {@code status} is PARSED, DUPLICATE, NEEDS_REVIEW, IGNORED or ERROR; {@code message} is a one-line summary. */
public record SmsResponse(String status, String message, Long messageId, SmsTransactionResponse transaction, SmsParsedResponse parsed) {}
