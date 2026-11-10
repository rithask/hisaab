package com.hisaab.statement;

/** Published when a statement PDF from an email has been stored. */
public record StatementSaved(Long statementFileId) {}
