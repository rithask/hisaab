package com.hisaab.statements.dto;

import com.hisaab.common.dto.AccountRefResponse;
import com.hisaab.statements.model.StatementFile;
import java.time.Instant;

public record StatementFileResponse(Long id, AccountRefResponse account, String fileName, long sizeBytes, String periodLabel,
                                    Instant receivedAt, String importStatus, String importNote, boolean fromEmail) {
    public static StatementFileResponse of(StatementFile s) {
        return new StatementFileResponse(s.getId(), s.getAccount() == null ? null : AccountRefResponse.of(s.getAccount()),
                s.getFileName(), s.getSizeBytes(), s.getPeriodLabel(), s.getReceivedAt(), s.getImportStatus(),
                s.getImportNote(), s.getRawEmail() != null);
    }
}
