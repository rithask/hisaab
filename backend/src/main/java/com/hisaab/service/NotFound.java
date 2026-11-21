package com.hisaab.service;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

/** The 404 every lookup by id uses: {@code repo.findById(id).orElseThrow(notFound("Account"))} → "Account not found". */
public final class NotFound {
    private NotFound() {}

    public static Supplier<NoSuchElementException> notFound(String what) {
        return () -> new NoSuchElementException(what + " not found");
    }
}
