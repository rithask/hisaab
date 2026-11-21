package com.hisaab.statements;

/** Published when a statement PDF from an email has been stored. */
public record StatementSaved(Long statementFileId) {}
