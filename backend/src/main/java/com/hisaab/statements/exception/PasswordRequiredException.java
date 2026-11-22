package com.hisaab.statements.exception;

/** A statement PDF is locked and none of the passwords tried opened it: the app asks for one and tries again (422). */
public class PasswordRequiredException extends java.io.IOException {
    public PasswordRequiredException(String message) {
        super(message);
    }
}
