package com.hisaab.dataapi.exception;

/** An Idempotency-Key was sent again with a different request body (422). */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
