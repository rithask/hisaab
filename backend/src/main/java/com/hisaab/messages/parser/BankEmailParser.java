package com.hisaab.messages.parser;

import com.hisaab.common.model.Enums.Institution;
import com.hisaab.messages.model.EmailContent;

import java.util.Optional;

/**
 * Reads one bank's alert emails of one kind. An email from a known bank address is only offered to that bank's
 * parsers (see {@code ReadWithParsers}); other emails (forwarded, or from a sender I trusted) go to every parser.
 */
public interface BankEmailParser {
    String name();

    /** The bank whose emails this parser reads. */
    Institution institution();

    /** Returns empty when the email is not a transaction alert this parser understands. */
    Optional<ParsedTransaction> parse(EmailContent email);
}
