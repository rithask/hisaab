package com.hisaab.common.exception;

/**
 * The request is valid but can't be done right now (400): a feature is switched off, an import is already running,
 * something still depends on it. The message is shown to the user as is and says what to do instead.
 */
public class NotAllowedException extends IllegalStateException {
    public NotAllowedException(String message) {
        super(message);
    }
}
