package com.hisaab.statements.dto;

import com.hisaab.common.dto.AccountRefResponse;
import com.hisaab.statements.parser.ParsedStatement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record StatementImportResponse(Long id, String status, String label, String fileName, String institution, String accountType,
                                      String last4, LocalDate from, LocalDate to, BigDecimal opening, BigDecimal closing,
                                      BigDecimal creditLimit, AccountRefResponse account, LocalDate accountStart,
                                      List<ParsedStatement.Check> checks, BalanceCheckResponse balance, List<ImportLineResponse> lines,
                                      List<MatchedTransactionResponse> notInStatement) {}
