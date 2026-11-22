package com.hisaab.statements.exception;

import com.hisaab.common.exception.BadRequestException;

/** A statement PDF that no parser recognises (400): the message says which banks and cards are supported. */
public class UnsupportedStatementException extends BadRequestException {
    public UnsupportedStatementException(String message) {
        super(message);
    }
}
