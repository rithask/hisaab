package com.hisaab.accounts.dto;

import com.hisaab.accounts.model.Alert;
import com.hisaab.accounts.model.AlertType;
import com.hisaab.common.dto.AccountRefResponse;
import java.math.BigDecimal;
import java.time.Instant;

public record AlertResponse(Long id, AlertType type, String message, AccountRefResponse account, Long txnId,
                            BigDecimal expected, BigDecimal actual, Instant createdAt) {
    public static AlertResponse of(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getType(), alert.getMessage(),
                alert.getAccount() == null ? null : AccountRefResponse.of(alert.getAccount()),
                alert.getTxn() == null ? null : alert.getTxn().getId(), alert.getExpected(), alert.getActual(),
                        alert.getCreatedAt());
    }
}
