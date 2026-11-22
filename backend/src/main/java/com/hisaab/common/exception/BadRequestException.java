package com.hisaab.common.exception;

/**
 * The request can't be done as asked: a missing or invalid value (400). The message is shown to the user as is, so
 * it says what to fix: "Amount must be positive", "Pick the account".
 */
public class BadRequestException extends IllegalArgumentException {
    public BadRequestException(String message) {
        super(message);
    }
}
