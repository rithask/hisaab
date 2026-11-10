package com.hisaab.mail.parser;

import com.hisaab.mail.EmailContent;

import java.util.Optional;

public interface BankEmailParser {
    String name();

    /** Returns empty when the email is not a transaction alert this parser understands. */
    Optional<ParsedTransaction> parse(EmailContent email);
}
