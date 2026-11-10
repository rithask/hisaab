package com.hisaab.mail;

import java.time.Instant;

/**
 * A normalised email: {@code text} has one block-level element per line (HTML is converted),
 * {@code flat} is the same text with all whitespace collapsed to single spaces.
 */
public record EmailContent(String from, String subject, Instant receivedAt, String text) {

    public String flat() {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** Everything a parser might want to search: subject + body. */
    public String haystack() {
        return ((subject == null ? "" : subject) + " " + flat()).toLowerCase();
    }
}
