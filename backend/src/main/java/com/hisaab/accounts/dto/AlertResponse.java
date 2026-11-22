package com.hisaab.accounts.dto;

import com.hisaab.accounts.model.Alert;
import com.hisaab.accounts.model.AlertType;
import com.hisaab.common.dto.AccountRefResponse;
import java.math.BigDecimal;
import java.time.Instant;

public record AlertResponse(Long id, AlertType type, String message, AccountRefResponse account, Long txnId,
                            BigDecimal expected, BigDecimal actual, Instant createdAt) {
    public static AlertResponse of(Alert a) {
        return new AlertResponse(a.getId(), a.getType(), a.getMessage(),
                a.getAccount() == null ? null : AccountRefResponse.of(a.getAccount()),
                a.getTxn() == null ? null : a.getTxn().getId(), a.getExpected(), a.getActual(), a.getCreatedAt());
    }
}
