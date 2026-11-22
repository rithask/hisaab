package com.hisaab.statements.dto;

import java.math.BigDecimal;

/** @param startingBalance the account's new starting balance, when I asked to correct it; otherwise null */
public record ImportCommitResponse(int added, int updated, int skipped, BigDecimal startingBalance) {}
