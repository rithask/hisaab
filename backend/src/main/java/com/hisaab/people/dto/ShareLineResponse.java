package com.hisaab.people.dto;

import com.hisaab.transactions.dto.TransactionResponse;
import java.math.BigDecimal;

public record ShareLineResponse(Long shareId, BigDecimal amount, TransactionResponse txn) {}
