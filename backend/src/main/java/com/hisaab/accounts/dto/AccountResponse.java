package com.hisaab.accounts.dto;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Institution;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(Long id, String name, AccountType type, Institution institution, String last4,
                              BigDecimal openingBalance, Instant openingDate, boolean archived,
                              /* bank/cash balance, or card outstanding */ BigDecimal balance,
                              Integer statementDay, Integer dueDay, CardInfoResponse card) {}
