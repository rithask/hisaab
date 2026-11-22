package com.hisaab.dataapi.exception;

/** A Data API token asked for something outside its limits (403); the message says which limit. */
public class TokenForbiddenException extends RuntimeException {
    public TokenForbiddenException(String message) {
        super(message);
    }
}
