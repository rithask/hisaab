package com.hisaab.statement;

public interface StatementParser {
    String name();

    /** True when the extracted text looks like this parser's statement format. */
    boolean supports(String text);

    ParsedStatement parse(String text);
}
