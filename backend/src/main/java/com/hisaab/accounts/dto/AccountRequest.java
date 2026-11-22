package com.hisaab.accounts.dto;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Institution;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountRequest(String name, AccountType type, Institution institution, String last4,
                             BigDecimal openingBalance, Instant openingDate, BigDecimal creditLimit,
                             Integer statementDay, Integer dueDay, Boolean archived) {}
