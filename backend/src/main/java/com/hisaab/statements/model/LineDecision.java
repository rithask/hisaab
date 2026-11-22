package com.hisaab.statements.model;

/** What to do with a statement line: fill in the matched transaction, add it as new, or leave it out. */
public enum LineDecision { MERGE, ADD, SKIP }
