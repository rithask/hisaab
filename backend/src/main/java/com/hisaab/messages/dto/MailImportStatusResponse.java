package com.hisaab.messages.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Where an older-mail import has got to.
 *
 * @param state IDLE, CONNECTING (signing in and searching), RUNNING, DONE, CANCELLED or FAILED
 * @param total emails found since the date (known once the search finishes)
 * @param done  emails looked at so far
 * @param added of those, how many were new
 */
public record MailImportStatusResponse(String state, LocalDate since, Integer total, int done, int added, Instant startedAt,
                                       Instant finishedAt, String error) {
    public static final MailImportStatusResponse IDLE = new MailImportStatusResponse("IDLE", null, null, 0, 0, null, null, null);

    public boolean active() {
        return state.equals("CONNECTING") || state.equals("RUNNING");
    }

    public MailImportStatusResponse progress(int done, int added) {
        return new MailImportStatusResponse(state, since, total, done, added, startedAt, null, null);
    }

    public MailImportStatusResponse finish(String state, String error) {
        return new MailImportStatusResponse(state, since, total, done, added, startedAt, Instant.now(), error);
    }
}
