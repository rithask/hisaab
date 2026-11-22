package com.hisaab.common.exception;

import java.util.NoSuchElementException;

/** Something asked for by id doesn't exist (404). The message is shown to the user as is: "Account not found". */
public class NotFoundException extends NoSuchElementException {
    public NotFoundException(String message) {
        super(message);
    }
}
