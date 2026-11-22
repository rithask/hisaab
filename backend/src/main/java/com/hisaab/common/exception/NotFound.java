package com.hisaab.common.exception;

import java.util.function.Supplier;

/** The 404 every lookup by id uses: {@code repo.findById(id).orElseThrow(notFound("Account"))} → "Account not found". */
public final class NotFound {
    private NotFound() {}

    public static Supplier<NotFoundException> notFound(String what) {
        return () -> new NotFoundException(what + " not found");
    }
}
